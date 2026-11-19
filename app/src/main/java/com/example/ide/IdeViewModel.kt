package com.example.ide

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.settings.AppSettings
import com.example.settings.SettingsRepository
import com.quark.buffer.TextBuffer
import com.quark.buffer.TextPosition
import com.quark.editor.DiagnosticMarker
import com.quark.editor.DiagnosticSeverity
import com.quark.editor.EditorAction
import com.quark.editor.EditorMetrics
import com.quark.editor.QuarkEditorView
import com.quark.fs.FuzzyMatcher
import com.quark.fs.ProjectTemplate
import com.quark.fs.SessionRecoveryManager
import com.quark.fs.StorageBackend
import com.quark.fs.VfsNode
import com.quark.fs.VisibleTreeItem
import com.quark.fs.WorkspaceFileSystem
import com.quark.fs.WorkspaceRoot
import com.quark.fs.ZipManager
import com.quark.git.PureGitRepository
import com.quark.intel.CompletionEngine
import com.quark.intel.CompletionItem
import com.quark.intel.DiagSeverity
import com.quark.intel.ProjectIntelIndex
import com.quark.intel.SymbolParser
import com.quark.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Primary ViewModel orchestrating workspace storage, lazy file tree, multi-tab buffers,
 * auto-save, crash recovery, fuzzy Quick Open, parallel global search, and IDE settings.
 */
class IdeViewModel(application: Application) : AndroidViewModel(application) {

  private val fs = WorkspaceFileSystem(application)
  private val recovery = SessionRecoveryManager(application)
  private val codeRunner = CodeRunner(application, fs)
  private val projectIntelIndex = ProjectIntelIndex()
  private val completionEngine = CompletionEngine(projectIntelIndex)
  val settingsRepo = SettingsRepository(application)

  val settings: StateFlow<AppSettings> = settingsRepo.settings

  private val _uiState = MutableStateFlow(IdeUiState())
  val uiState: StateFlow<IdeUiState> = _uiState.asStateFlow()

  // Lazy tree caches
  private val expandedDirIds = LinkedHashSet<String>()
  private val loadingDirIds = HashSet<String>()
  private val dirChildrenCache = HashMap<String, List<VfsNode>>()
  private var indexedWorkspaceFiles: List<VfsNode> = emptyList()
  private val lastIntelVersionByTab = HashMap<String, Long>()

  private var autoSaveJob: Job? = null
  private var recoveryJob: Job? = null
  private var globalSearchJob: Job? = null
  private var gutterDiffJob: Job? = null
  private var intelJob: Job? = null
  private var diagnosticsJob: Job? = null
  private var indexWorkspaceJob: Job? = null

  private val terminalSessions = ArrayList<TerminalSession>()
  private var terminalSeq = 1

  private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

  init {
    logOutput(
      "Quark initialized (${BuildConfig.FLAVOR_NAME} flavor, targetSdk=${BuildConfig.TARGET_SDK_INT}, canExecAppBin=${BuildConfig.CAN_EXEC_APP_BIN})"
    )
    ensureTerminalSession()
    restoreInitialSession()
  }

  override fun onCleared() {
    super.onCleared()
    for (s in terminalSessions) s.dispose()
    terminalSessions.clear()
  }

  private fun restoreInitialSession() {
    viewModelScope.launch {
      val projects = fs.listPrivateProjects()
      val restored = recovery.restoreSession()
      _uiState.update {
        it.copy(
          isInitialized = true,
          privateProjects = projects,
          recentWorkspaces = restored.recentWorkspaces,
        )
      }
      val restoredWs = restored.workspace
      if (restoredWs != null) {
        openWorkspace(restoredWs, recordRecent = false)
        var recoveredCount = 0
        for (savedTab in restored.tabs) {
          val recText = savedTab.recoveredText
          val text =
            if (savedTab.hasUnsavedRecovery && recText != null) {
              recoveredCount++
              recText
            } else {
              runCatching { fs.readText(savedTab.node) }.getOrNull() ?: continue
            }
          val buf = TextBuffer(text)
          if (savedTab.hasUnsavedRecovery) buf.markDirty()
          buf.setCursor(savedTab.cursorOffset.coerceIn(0, buf.length))
          val pos = buf.offsetToPosition(buf.selectionSet.primary.active)
          val tab =
            OpenTab(
              id = savedTab.node.id,
              node = savedTab.node,
              buffer = buf,
              isDirty = buf.isDirty,
              cursorLine = pos.line + 1,
              cursorCol = pos.column + 1,
              recoveredFromCrash = savedTab.hasUnsavedRecovery,
            )
          _uiState.update { s ->
            if (s.openTabs.any { it.id == tab.id }) s
            else s.copy(openTabs = s.openTabs + tab, activeTabId = s.activeTabId ?: tab.id)
          }
        }
        if (restored.activeTabId != null) {
          _uiState.update { s ->
            if (s.openTabs.any { it.id == restored.activeTabId }) s.copy(activeTabId = restored.activeTabId)
            else s
          }
        }
        if (recoveredCount > 0) {
          logOutput("Recovered $recoveredCount unsaved buffer(s) from crash snapshot.")
          setStatusMessage("Recovered $recoveredCount unsaved file(s)")
        }
        _uiState.value.activeTabId?.let { scheduleDiagnosticsForTab(it, immediate = true) }
      }
    }
  }

  fun showWelcomeScreen(show: Boolean) {
    viewModelScope.launch {
      val projects = fs.listPrivateProjects()
      val recents = recovery.loadRecentWorkspaces()
      _uiState.update {
        it.copy(showWelcome = show, privateProjects = projects, recentWorkspaces = recents)
      }
    }
  }

  fun createProject(name: String, template: ProjectTemplate) {
    viewModelScope.launch {
      val root = fs.createStarterProject(name, template)
      logOutput("Created ${template.displayName} project '${root.name}'")
      openWorkspace(root)
      // Automatically open main starter file
      val files = fs.collectAllFiles(root)
      val entry = files.firstOrNull { !it.relativePath.startsWith(".editor/") && it.name != "README.md" }
        ?: files.firstOrNull()
      if (entry != null) openFile(entry)
    }
  }

  fun openSafFolder(treeUri: Uri) {
    viewModelScope.launch {
      val root = fs.openSafWorkspace(treeUri)
      logOutput("Opened SAF workspace '${root.name}'")
      openWorkspace(root)
    }
  }

  fun importZipProject(zipUri: Uri, fileNameHint: String) {
    viewModelScope.launch {
      try {
        val root = ZipManager.importZip(getApplication(), fs, zipUri, fileNameHint)
        logOutput("Imported zip archive into '${root.name}'")
        openWorkspace(root)
        setStatusMessage("Imported ${root.name}")
      } catch (t: Throwable) {
        logOutput("Zip import failed: ${t.message}")
        setStatusMessage("Import failed: ${t.message}")
      }
    }
  }

  fun exportCurrentProjectZip(destUri: Uri) {
    val root = _uiState.value.workspace ?: return
    viewModelScope.launch {
      try {
        val count = ZipManager.exportZip(getApplication(), fs, root, destUri)
        logOutput("Exported $count file(s) from '${root.name}' to zip")
        setStatusMessage("Exported $count files")
      } catch (t: Throwable) {
        logOutput("Zip export failed: ${t.message}")
        setStatusMessage("Export failed: ${t.message}")
      }
    }
  }

  fun openWorkspace(root: WorkspaceRoot, recordRecent: Boolean = true) {
    viewModelScope.launch {
      expandedDirIds.clear()
      dirChildrenCache.clear()
      loadingDirIds.clear()

      val rootNode = fs.rootNode(root)
      expandedDirIds.add(rootNode.id)
      val rootChildren = fs.listChildren(rootNode)
      dirChildrenCache[rootNode.id] = rootChildren

      if (recordRecent) {
        recovery.recordRecentWorkspace(root)
      }
      val recents = recovery.loadRecentWorkspaces()
      val privates = fs.listPrivateProjects()

      _uiState.update {
        it.copy(
          workspace = root,
          showWelcome = false,
          openTabs = if (it.workspace?.id == root.id) it.openTabs else emptyList(),
          activeTabId = if (it.workspace?.id == root.id) it.activeTabId else null,
          secondaryTabId = if (it.workspace?.id == root.id) it.secondaryTabId else null,
          privateProjects = privates,
          recentWorkspaces = recents,
          newProjectDialogVisible = false,
        )
      }
      rebuildVisibleTree(rootNode)
      refreshFileIndex(root)
      refreshGitStatus()
      if (root.backend == StorageBackend.APP_PRIVATE) {
        val dir = File(root.rootUriOrPath)
        activeTerminalSession()?.setWorkingDirectory(dir)
      }
      persistSessionSnapshot()
    }
  }

  private fun refreshFileIndex(root: WorkspaceRoot) {
    viewModelScope.launch {
      indexedWorkspaceFiles = fs.collectAllFiles(root)
      if (_uiState.value.quickOpenVisible) {
        updateQuickOpenQuery(_uiState.value.quickOpenQuery)
      }
      indexWorkspaceForIntel(indexedWorkspaceFiles)
    }
  }

  private fun indexWorkspaceForIntel(files: List<VfsNode>) {
    indexWorkspaceJob?.cancel()
    indexWorkspaceJob = viewModelScope.launch(Dispatchers.IO) {
      for (node in files) {
        if (node.isDirectory || node.sizeBytes > 256_000L) continue
        if (SymbolParser.detectLanguageId(node.relativePath) == "text") continue
        val text = runCatching { fs.readText(node) }.getOrNull() ?: continue
        projectIntelIndex.updateFile(node.relativePath, text)
      }
    }
  }

  fun refreshTree() {
    val root = _uiState.value.workspace ?: return
    viewModelScope.launch {
      val rootNode = fs.rootNode(root)
      for (dirId in expandedDirIds.toList()) {
        val node = findNodeById(dirId) ?: if (dirId == rootNode.id) rootNode else continue
        dirChildrenCache[dirId] = fs.listChildren(node)
      }
      rebuildVisibleTree(rootNode)
      refreshFileIndex(root)
    }
  }

  fun toggleDirectory(dirNode: VfsNode) {
    if (!dirNode.isDirectory) return
    val root = _uiState.value.workspace ?: return
    val rootNode = fs.rootNode(root)
    if (expandedDirIds.contains(dirNode.id)) {
      expandedDirIds.remove(dirNode.id)
      rebuildVisibleTree(rootNode)
      return
    }
    expandedDirIds.add(dirNode.id)
    if (dirChildrenCache.containsKey(dirNode.id)) {
      rebuildVisibleTree(rootNode)
      return
    }
    loadingDirIds.add(dirNode.id)
    rebuildVisibleTree(rootNode)
    viewModelScope.launch {
      val children = fs.listChildren(dirNode)
      dirChildrenCache[dirNode.id] = children
      loadingDirIds.remove(dirNode.id)
      rebuildVisibleTree(rootNode)
    }
  }

  private fun rebuildVisibleTree(rootNode: VfsNode) {
    val out = ArrayList<VisibleTreeItem>(64)
    fun visit(node: VfsNode, depth: Int) {
      val expanded = expandedDirIds.contains(node.id)
      out.add(
        VisibleTreeItem(
          node = node,
          depth = depth,
          isExpanded = expanded,
          isLoading = loadingDirIds.contains(node.id),
        )
      )
      if (node.isDirectory && expanded) {
        val children = dirChildrenCache[node.id] ?: emptyList()
        for (child in children) {
          visit(child, depth + 1)
        }
      }
    }
    // Display children of workspace root at depth 0 for clean IDE look
    val topLevel = dirChildrenCache[rootNode.id] ?: emptyList()
    for (child in topLevel) {
      visit(child, 0)
    }
    _uiState.update { it.copy(visibleTreeItems = out) }
  }

  private fun findNodeById(id: String): VfsNode? {
    for (list in dirChildrenCache.values) {
      for (n in list) if (n.id == id) return n
    }
    return null
  }

  fun openFile(node: VfsNode, line1Based: Int? = null, col1Based: Int? = null, inSecondaryPane: Boolean = false) {
    if (node.isDirectory) {
      toggleDirectory(node)
      return
    }
    val existing = _uiState.value.openTabs.firstOrNull { it.id == node.id }
    if (existing != null) {
      if (line1Based != null) {
        val off = existing.buffer.positionToOffset((line1Based - 1).coerceAtLeast(0), ((col1Based ?: 1) - 1).coerceAtLeast(0))
        existing.buffer.setCursor(off)
      }
      _uiState.update { s ->
        if (inSecondaryPane || (s.isSplitEnabled && s.focusedPane == 1)) {
          s.copy(secondaryTabId = existing.id)
        } else {
          s.copy(activeTabId = existing.id)
        }
      }
      dismissCompletion()
      scheduleDiagnosticsForTab(existing.id, immediate = true)
      persistSessionSnapshot()
      return
    }

    viewModelScope.launch {
      try {
        val text = fs.readText(node)
        val buffer = TextBuffer(text)
        if (line1Based != null) {
          val off = buffer.positionToOffset((line1Based - 1).coerceAtLeast(0), ((col1Based ?: 1) - 1).coerceAtLeast(0))
          buffer.setCursor(off)
        }
        val pos = buffer.offsetToPosition(buffer.selectionSet.primary.active)
        val newTab = OpenTab(
          id = node.id,
          node = node,
          buffer = buffer,
          isDirty = false,
          cursorLine = pos.line + 1,
          cursorCol = pos.column + 1,
        )
        lastIntelVersionByTab[newTab.id] = buffer.version
        _uiState.update { s ->
          val updatedTabs = s.openTabs + newTab
          if (inSecondaryPane || (s.isSplitEnabled && s.focusedPane == 1)) {
            s.copy(openTabs = updatedTabs, secondaryTabId = newTab.id)
          } else {
            s.copy(openTabs = updatedTabs, activeTabId = newTab.id)
          }
        }
        dismissCompletion()
        scheduleDiagnosticsForTab(newTab.id, immediate = true)
        refreshActiveTabGutterDiff()
        persistSessionSnapshot()
      } catch (t: Throwable) {
        logOutput("Failed to open ${node.name}: ${t.message}")
        setStatusMessage("Cannot open ${node.name}")
      }
    }
  }

  fun selectTab(tabId: String, pane: Int = _uiState.value.focusedPane) {
    _uiState.update { s ->
      if (s.isSplitEnabled && pane == 1) {
        s.copy(secondaryTabId = tabId, focusedPane = 1)
      } else {
        s.copy(activeTabId = tabId, focusedPane = 0)
      }
    }
    dismissCompletion()
    scheduleDiagnosticsForTab(tabId, immediate = true)
    refreshActiveTabGutterDiff()
    persistSessionSnapshot()
  }

  fun setFocusedPane(pane: Int) {
    _uiState.update { it.copy(focusedPane = pane) }
  }

  fun closeTab(tabId: String) {
    lastIntelVersionByTab.remove(tabId)
    viewModelScope.launch { recovery.clearRecoverySnapshot(tabId) }
    _uiState.update { s ->
      val idx = s.openTabs.indexOfFirst { it.id == tabId }
      if (idx < 0) return@update s
      val remaining = s.openTabs.filterNot { it.id == tabId }
      val nextPrimary =
        if (s.activeTabId == tabId) {
          remaining.getOrNull(idx.coerceAtMost(remaining.lastIndex))?.id
        } else {
          s.activeTabId
        }
      val nextSecondary =
        if (s.secondaryTabId == tabId) remaining.firstOrNull()?.id else s.secondaryTabId
      s.copy(
        openTabs = remaining,
        activeTabId = nextPrimary,
        secondaryTabId = nextSecondary,
        isSplitEnabled = if (remaining.size < 2 && s.isSplitEnabled) false else s.isSplitEnabled,
      )
    }
    dismissCompletion()
    val nextId = focusedTabId(_uiState.value)
    if (nextId != null) {
      scheduleDiagnosticsForTab(nextId, immediate = true)
    } else {
      _uiState.update { it.copy(activeDiagnostics = emptyList(), activeEditorDiagnostics = emptyList()) }
    }
    persistSessionSnapshot()
  }

  fun toggleSplitPane() {
    _uiState.update { s ->
      if (s.isSplitEnabled) {
        s.copy(isSplitEnabled = false, focusedPane = 0)
      } else {
        val fallbackSecondary = s.openTabs.firstOrNull { it.id != s.activeTabId }?.id ?: s.activeTabId
        s.copy(isSplitEnabled = true, secondaryTabId = fallbackSecondary)
      }
    }
  }

  fun onTabCursorOrBufferChanged(
    tabId: String,
    pos: TextPosition,
    cursorCount: Int,
    selectionLen: Int,
    isDirty: Boolean,
  ) {
    val editedTab = _uiState.value.openTabs.firstOrNull { it.id == tabId }
    val currentVersion = editedTab?.buffer?.version
    val bufferChanged = currentVersion != null && lastIntelVersionByTab[tabId] != currentVersion
    if (currentVersion != null) {
      lastIntelVersionByTab[tabId] = currentVersion
    }

    _uiState.update { s ->
      val updated =
        s.openTabs.map { tab ->
          if (tab.id == tabId) {
            tab.copy(
              isDirty = isDirty,
              cursorLine = pos.line + 1,
              cursorCol = pos.column + 1,
              cursorCount = cursorCount,
              selectionLen = selectionLen,
            )
          } else {
            tab
          }
        }
      s.copy(openTabs = updated)
    }
    if (isDirty) {
      scheduleRecoverySnapshot(tabId)
      if (settings.value.autoSave) {
        scheduleAutoSave(tabId)
      }
    }
    if (tabId == focusedTabId(_uiState.value)) {
      if (bufferChanged) {
        scheduleCompletion(tabId, manual = false)
        scheduleDiagnosticsForTab(tabId, immediate = false)
      } else if (_uiState.value.completionPopup.visible) {
        scheduleCompletion(tabId, manual = false)
      } else {
        scheduleSignatureHintOnly(tabId)
      }
    }
  }

  private fun focusedTabId(s: IdeUiState): String? =
    if (s.isSplitEnabled && s.focusedPane == 1) s.secondaryTabId else s.activeTabId

  fun updateCaretViewportPos(xPx: Float, yPx: Float, lineHeightPx: Float) {
    _uiState.update { s ->
      s.copy(
        completionPopup = s.completionPopup.copy(
          caretXPx = xPx,
          caretYPx = yPx,
          lineHeightPx = lineHeightPx,
        )
      )
    }
  }

  fun triggerCompletionManually() {
    val tabId = focusedTabId(_uiState.value) ?: return
    scheduleCompletion(tabId, manual = true)
  }

  fun moveCompletionSelection(delta: Int) {
    _uiState.update { s ->
      val cur = s.completionPopup
      if (!cur.visible || cur.items.isEmpty()) return@update s
      val size = cur.items.size
      val next = ((cur.selectedIndex + delta) % size + size) % size
      s.copy(completionPopup = cur.copy(selectedIndex = next))
    }
  }

  fun dismissCompletion() {
    _uiState.update { s ->
      if (!s.completionPopup.visible && s.completionPopup.signatureHint == null) s
      else s.copy(completionPopup = s.completionPopup.copy(visible = false, items = emptyList(), selectedIndex = 0))
    }
  }

  fun acceptSelectedCompletion(editor: QuarkEditorView?): Boolean {
    val cur = _uiState.value.completionPopup
    if (!cur.visible || cur.items.isEmpty()) return false
    val item = cur.items.getOrNull(cur.selectedIndex.coerceIn(0, cur.items.lastIndex)) ?: return false
    acceptCompletionItem(item, editor)
    return true
  }

  fun acceptCompletionItem(item: CompletionItem, editor: QuarkEditorView?) {
    completionEngine.recordAccepted(item.label)
    _uiState.update { s ->
      s.copy(completionPopup = s.completionPopup.copy(visible = false, items = emptyList(), selectedIndex = 0))
    }
    editor?.applyCompletion(
      replaceStartOffset = item.replaceStartOffset,
      replaceEndOffset = item.replaceEndOffset,
      insertText = item.insertText,
      cursorBackstep = item.cursorBackstep,
    )
  }

  private fun scheduleCompletion(tabId: String, manual: Boolean) {
    intelJob?.cancel()
    intelJob = viewModelScope.launch {
      if (!manual) delay(85L)
      val tab = _uiState.value.openTabs.firstOrNull { it.id == tabId } ?: return@launch
      val caretOffset = tab.buffer.selectionSet.primary.active
      val filePath = tab.node.relativePath
      val (items, sig) = withContext(Dispatchers.Default) {
        val comp = completionEngine.complete(tab.buffer, filePath, caretOffset, manualTrigger = manual)
        val hint = completionEngine.computeSignatureHint(tab.buffer, filePath, caretOffset)
        comp to hint
      }
      _uiState.update { s ->
        if (focusedTabId(s) != tabId) return@update s
        val existing = s.completionPopup
        val keepVisible = items.isNotEmpty()
        s.copy(
          completionPopup = existing.copy(
            visible = keepVisible,
            tabId = tabId,
            items = items,
            selectedIndex = 0,
            signatureHint = sig,
          )
        )
      }
    }
  }

  private fun scheduleSignatureHintOnly(tabId: String) {
    intelJob?.cancel()
    intelJob = viewModelScope.launch {
      delay(60L)
      val tab = _uiState.value.openTabs.firstOrNull { it.id == tabId } ?: return@launch
      val caretOffset = tab.buffer.selectionSet.primary.active
      val filePath = tab.node.relativePath
      val hint = withContext(Dispatchers.Default) {
        completionEngine.computeSignatureHint(tab.buffer, filePath, caretOffset)
      }
      _uiState.update { s ->
        if (focusedTabId(s) != tabId) return@update s
        s.copy(completionPopup = s.completionPopup.copy(signatureHint = hint))
      }
    }
  }

  private fun scheduleDiagnosticsForTab(tabId: String, immediate: Boolean) {
    diagnosticsJob?.cancel()
    diagnosticsJob = viewModelScope.launch {
      if (!immediate) delay(240L)
      val tab = _uiState.value.openTabs.firstOrNull { it.id == tabId } ?: return@launch
      val text = tab.buffer.getText()
      val path = tab.node.relativePath
      val (rawDiags, markers) = withContext(Dispatchers.Default) {
        val docs = completionEngine.computeDiagnostics(text, path)
        val m = docs.map { d ->
          DiagnosticMarker(
            line = d.line0Based,
            startCol = d.startCol,
            endCol = d.endCol,
            severity = when (d.severity) {
              DiagSeverity.ERROR -> DiagnosticSeverity.ERROR
              DiagSeverity.WARNING -> DiagnosticSeverity.WARNING
              DiagSeverity.INFO, DiagSeverity.HINT -> DiagnosticSeverity.INFO
            },
            message = d.message,
          )
        }
        docs to m
      }
      _uiState.update { s ->
        if (focusedTabId(s) != tabId) return@update s
        s.copy(activeDiagnostics = rawDiags, activeEditorDiagnostics = markers)
      }
    }
  }

  private fun scheduleRecoverySnapshot(tabId: String) {
    recoveryJob?.cancel()
    recoveryJob = viewModelScope.launch {
      delay(800L)
      val tab = _uiState.value.openTabs.firstOrNull { it.id == tabId } ?: return@launch
      if (tab.isDirty) {
        recovery.saveRecoverySnapshot(tab.id, tab.buffer.getText())
      }
      persistSessionSnapshot()
    }
  }

  private fun scheduleAutoSave(tabId: String) {
    autoSaveJob?.cancel()
    autoSaveJob = viewModelScope.launch {
      delay(settings.value.autoSaveDelayMs)
      saveTab(tabId)
    }
  }

  fun saveActiveTab() {
    val s = _uiState.value
    val targetId = if (s.isSplitEnabled && s.focusedPane == 1) s.secondaryTabId else s.activeTabId
    if (targetId != null) {
      viewModelScope.launch { saveTab(targetId) }
    }
  }

  private suspend fun saveTab(tabId: String) {
    val tab = _uiState.value.openTabs.firstOrNull { it.id == tabId } ?: return
    val ok = fs.writeText(tab.node, tab.buffer.getText())
    if (ok) {
      tab.buffer.markSaved()
      recovery.clearRecoverySnapshot(tab.id)
      _uiState.update { s ->
        s.copy(
          openTabs = s.openTabs.map { if (it.id == tabId) it.copy(isDirty = false, recoveredFromCrash = false) else it }
        )
      }
      logOutput("Saved ${tab.node.relativePath}")
      setStatusMessage("Saved ${tab.node.name}")
      refreshGitStatus()
    } else {
      logOutput("Failed to save ${tab.node.relativePath}")
      setStatusMessage("Save failed: ${tab.node.name}")
    }
  }

  // --- File CRUD ---

  fun createFileIn(parentDir: VfsNode?, name: String) {
    val root = _uiState.value.workspace ?: return
    val targetParent = if (parentDir != null && parentDir.isDirectory) parentDir else fs.rootNode(root)
    viewModelScope.launch {
      val created = fs.createFile(targetParent, name.trim())
      if (created != null) {
        expandedDirIds.add(targetParent.id)
        refreshTree()
        refreshGitStatus()
        openFile(created)
        logOutput("Created file ${created.relativePath}")
      } else {
        setStatusMessage("Could not create file '$name'")
      }
    }
  }

  fun createDirectoryIn(parentDir: VfsNode?, name: String) {
    val root = _uiState.value.workspace ?: return
    val targetParent = if (parentDir != null && parentDir.isDirectory) parentDir else fs.rootNode(root)
    viewModelScope.launch {
      val created = fs.createDirectory(targetParent, name.trim())
      if (created != null) {
        expandedDirIds.add(targetParent.id)
        expandedDirIds.add(created.id)
        refreshTree()
        logOutput("Created folder ${created.relativePath}")
      } else {
        setStatusMessage("Could not create folder '$name'")
      }
    }
  }

  fun renameNode(node: VfsNode, newName: String) {
    viewModelScope.launch {
      val renamed = fs.rename(node, newName.trim())
      if (renamed != null) {
        _uiState.update { s ->
          s.copy(
            openTabs = s.openTabs.map { t ->
              if (t.id == node.id) t.copy(id = renamed.id, node = renamed) else t
            },
            activeTabId = if (s.activeTabId == node.id) renamed.id else s.activeTabId,
            secondaryTabId = if (s.secondaryTabId == node.id) renamed.id else s.secondaryTabId,
          )
        }
        refreshTree()
        refreshGitStatus()
        logOutput("Renamed ${node.name} -> ${renamed.name}")
      } else {
        setStatusMessage("Rename failed")
      }
    }
  }

  fun deleteNode(node: VfsNode) {
    viewModelScope.launch {
      if (fs.delete(node)) {
        if (!node.isDirectory) closeTab(node.id)
        refreshTree()
        refreshGitStatus()
        logOutput("Deleted ${node.relativePath}")
      } else {
        setStatusMessage("Delete failed")
      }
    }
  }

  fun duplicateNode(node: VfsNode) {
    val root = _uiState.value.workspace ?: return
    viewModelScope.launch {
      val parent = node.parentId?.let { findNodeById(it) } ?: fs.rootNode(root)
      val ext = node.name.substringAfterLast('.', "")
      val base = if (ext.isNotEmpty()) node.name.substringBeforeLast('.') else node.name
      val copyName = if (ext.isNotEmpty()) "${base}_copy.$ext" else "${base}_copy"
      if (!node.isDirectory) {
        val text = fs.readText(node)
        val created = fs.createFile(parent, copyName)
        if (created != null) {
          fs.writeText(created, text)
          refreshTree()
          openFile(created)
        }
      }
    }
  }

  // --- Quick Open, Global Search, Command Palette, Panels ---

  fun openQuickOpen(show: Boolean) {
    _uiState.update {
      it.copy(
        quickOpenVisible = show,
        quickOpenQuery = "",
        quickOpenMatches = if (show) fs.fuzzySearchFiles(indexedWorkspaceFiles, "") else emptyList(),
      )
    }
  }

  fun updateQuickOpenQuery(query: String) {
    val matches = fs.fuzzySearchFiles(indexedWorkspaceFiles, query)
    _uiState.update { it.copy(quickOpenQuery = query, quickOpenMatches = matches) }
  }

  fun openCommandPalette(show: Boolean) {
    _uiState.update { it.copy(commandPaletteVisible = show) }
  }

  fun openThemePicker(show: Boolean) {
    _uiState.update { it.copy(themePickerVisible = show) }
  }

  fun selectBottomPanel(tab: BottomPanelTab) {
    _uiState.update { it.copy(bottomPanelVisible = true, activeBottomPanel = tab) }
  }

  /** Saves and compiles/runs the focused tab (or workspace entry file) with output streamed to the bottom panel. */
  fun compileAndRunActiveFile() {
    val s = _uiState.value
    val targetId = if (s.isSplitEnabled && s.focusedPane == 1) s.secondaryTabId else s.activeTabId
    val tab = s.openTabs.firstOrNull { it.id == targetId } ?: s.openTabs.firstOrNull()
    if (tab == null) {
      _uiState.update { it.copy(bottomPanelVisible = true, activeBottomPanel = BottomPanelTab.OUTPUT) }
      logOutput("No open file to compile or run. Open a file from the Explorer first.")
      setStatusMessage("No open file")
      return
    }
    viewModelScope.launch {
      if (tab.isDirty) saveTab(tab.id)
      _uiState.update {
        it.copy(
          isRunningCode = true,
          bottomPanelVisible = true,
          activeBottomPanel = BottomPanelTab.OUTPUT,
        )
      }
      val result =
        codeRunner.runFile(
          workspace = _uiState.value.workspace,
          node = tab.node,
          sourceText = tab.buffer.getText(),
          onLog = { line -> logOutput(line) },
        )
      _uiState.update { cur ->
        cur.copy(
          isRunningCode = false,
          htmlPreviewContent = if (result.openHtmlPreview) result.htmlPreviewContent else cur.htmlPreviewContent,
          activeBottomPanel = if (result.openHtmlPreview) BottomPanelTab.PREVIEW else BottomPanelTab.OUTPUT,
        )
      }
      setStatusMessage(result.summary)
    }
  }

  fun openGoToLine(show: Boolean) {
    _uiState.update { it.copy(goToLineVisible = show) }
  }

  fun showNewProjectDialog(show: Boolean) {
    _uiState.update { it.copy(newProjectDialogVisible = show) }
  }

  fun selectSidePanel(tab: SidePanelTab) {
    _uiState.update { s ->
      if (s.sidePanelVisible && s.activeSidePanel == tab) {
        s.copy(sidePanelVisible = false)
      } else {
        s.copy(sidePanelVisible = true, activeSidePanel = tab)
      }
    }
  }

  fun toggleSidePanel() {
    _uiState.update { it.copy(sidePanelVisible = !it.sidePanelVisible) }
  }

  fun toggleBottomPanel() {
    _uiState.update { it.copy(bottomPanelVisible = !it.bottomPanelVisible) }
  }

  fun showFindBar(withReplace: Boolean) {
    _uiState.update {
      it.copy(findBarVisible = true, replaceBarVisible = withReplace)
    }
  }

  fun hideFindBar() {
    _uiState.update { it.copy(findBarVisible = false, replaceBarVisible = false) }
  }

  fun updateGlobalSearchParams(
    query: String = _uiState.value.searchQuery,
    isRegex: Boolean = _uiState.value.searchRegex,
    caseSensitive: Boolean = _uiState.value.searchCaseSensitive,
    pathFilter: String = _uiState.value.searchPathFilter,
  ) {
    _uiState.update {
      it.copy(
        searchQuery = query,
        searchRegex = isRegex,
        searchCaseSensitive = caseSensitive,
        searchPathFilter = pathFilter,
      )
    }
  }

  fun runGlobalSearch() {
    val root = _uiState.value.workspace ?: return
    val s = _uiState.value
    globalSearchJob?.cancel()
    if (s.searchQuery.isEmpty()) {
      _uiState.update { it.copy(globalSearchResults = emptyList(), isSearchingGlobal = false) }
      return
    }
    _uiState.update { it.copy(globalSearchResults = emptyList(), isSearchingGlobal = true) }
    globalSearchJob = viewModelScope.launch {
      fs.globalSearchStream(
        root = root,
        query = s.searchQuery,
        isRegex = s.searchRegex,
        caseSensitive = s.searchCaseSensitive,
        pathFilter = s.searchPathFilter,
      ).collect { result ->
        _uiState.update { cur ->
          cur.copy(globalSearchResults = cur.globalSearchResults + result)
        }
      }
      _uiState.update { it.copy(isSearchingGlobal = false) }
    }
  }

  fun handleEditorAction(action: EditorAction) {
    when (action) {
      EditorAction.QUICK_OPEN -> openQuickOpen(true)
      EditorAction.COMMAND_PALETTE -> openCommandPalette(true)
      EditorAction.SAVE -> saveActiveTab()
      EditorAction.TOGGLE_SIDEBAR -> toggleSidePanel()
      EditorAction.FIND -> showFindBar(withReplace = false)
      EditorAction.REPLACE -> showFindBar(withReplace = true)
      EditorAction.GLOBAL_SEARCH -> {
        _uiState.update { it.copy(sidePanelVisible = true, activeSidePanel = SidePanelTab.SEARCH) }
      }
      EditorAction.GO_TO_LINE -> openGoToLine(true)
      EditorAction.TRIGGER_COMPLETION -> triggerCompletionManually()
      EditorAction.TOGGLE_TERMINAL -> {
        _uiState.update { s ->
          if (s.bottomPanelVisible && s.activeBottomPanel == BottomPanelTab.TERMINAL) {
            s.copy(bottomPanelVisible = false)
          } else {
            s.copy(bottomPanelVisible = true, activeBottomPanel = BottomPanelTab.TERMINAL)
          }
        }
      }
      else -> {}
    }
  }

  fun updateMetrics(metrics: EditorMetrics) {
    _uiState.update { it.copy(metrics = metrics) }
  }

  fun updateStickyModifiers(ctrl: Boolean, alt: Boolean) {
    _uiState.update { it.copy(stickyCtrl = ctrl, stickyAlt = alt) }
  }

  fun updateSettings(transform: (AppSettings) -> AppSettings) {
    viewModelScope.launch { settingsRepo.update(transform) }
  }

  // --- Terminal Sessions ---

  fun activeTerminalSession(): TerminalSession? {
    ensureTerminalSession()
    val idx = _uiState.value.activeTerminalIndex.coerceIn(0, terminalSessions.lastIndex)
    return terminalSessions.getOrNull(idx)
  }

  fun newTerminalSession() {
    val session = createSessionInstance()
    terminalSessions.add(session)
    val nextIdx = terminalSessions.lastIndex
    _uiState.update {
      it.copy(
        terminalTabCount = terminalSessions.size,
        activeTerminalIndex = nextIdx,
        bottomPanelVisible = true,
        activeBottomPanel = BottomPanelTab.TERMINAL,
      )
    }
  }

  fun selectTerminalSession(index: Int) {
    if (index in terminalSessions.indices) {
      _uiState.update { it.copy(activeTerminalIndex = index) }
    }
  }

  fun closeTerminalSession(index: Int) {
    if (terminalSessions.size <= 1 || index !in terminalSessions.indices) return
    val removed = terminalSessions.removeAt(index)
    removed.dispose()
    val nextIdx = (_uiState.value.activeTerminalIndex).coerceIn(0, terminalSessions.lastIndex)
    _uiState.update {
      it.copy(terminalTabCount = terminalSessions.size, activeTerminalIndex = nextIdx)
    }
  }

  private fun ensureTerminalSession() {
    if (terminalSessions.isEmpty()) {
      terminalSessions.add(createSessionInstance())
    }
  }

  private fun createSessionInstance(): TerminalSession {
    val ctx = getApplication<Application>()
    val ws = _uiState.value.workspace
    val startDir =
      if (ws?.backend == StorageBackend.APP_PRIVATE) File(ws.rootUriOrPath)
      else ctx.filesDir
    val binDirs = buildList {
      add(File(ctx.filesDir, "bin"))
      add(File(ctx.applicationInfo.nativeLibraryDir))
    }
    return TerminalSession(
      id = "term_${terminalSeq++}",
      initialWorkDir = startDir,
      homeDir = ctx.filesDir,
      binDirs = binDirs,
      authorNameProvider = { settings.value.gitAuthorName },
      authorEmailProvider = { settings.value.gitAuthorEmail },
      onWorkspaceMutated = {
        viewModelScope.launch {
          refreshTree()
          refreshGitStatus()
        }
      },
    )
  }

  // --- Pure-Kotlin Git Source Control ---

  private fun currentGitRepo(): PureGitRepository? {
    val ws = _uiState.value.workspace ?: return null
    if (ws.backend != StorageBackend.APP_PRIVATE) return null
    return PureGitRepository(File(ws.rootUriOrPath))
  }

  fun refreshGitStatus() {
    val ws = _uiState.value.workspace
    if (ws == null) {
      _uiState.update { it.copy(gitSnapshot = com.quark.git.GitRepoSnapshot(), activeTabGutterDiff = null) }
      return
    }
    if (ws.backend != StorageBackend.APP_PRIVATE) {
      _uiState.update {
        it.copy(
          gitSnapshot = com.quark.git.GitRepoSnapshot(isRepository = false, isSupportedBackend = false),
          activeTabGutterDiff = null,
        )
      }
      return
    }
    val repo = PureGitRepository(File(ws.rootUriOrPath))
    viewModelScope.launch {
      val snap = repo.snapshot()
      _uiState.update { it.copy(gitSnapshot = snap) }
      refreshActiveTabGutterDiff()
    }
  }

  fun refreshActiveTabGutterDiff() {
    val tab = _uiState.value.openTabs.firstOrNull { it.id == _uiState.value.activeTabId } ?: return
    val repo = currentGitRepo() ?: run {
      _uiState.update { it.copy(activeTabGutterDiff = null) }
      return
    }
    gutterDiffJob?.cancel()
    gutterDiffJob = viewModelScope.launch {
      val diff = repo.workingGutterDiff(tab.node.relativePath, tab.buffer.getText())
      _uiState.update { it.copy(activeTabGutterDiff = diff) }
    }
  }

  fun gitInit() {
    val repo = currentGitRepo() ?: return
    viewModelScope.launch {
      repo.init().fold(
        onSuccess = { msg ->
          logOutput(msg)
          setStatusMessage("Initialized Git repository")
          refreshGitStatus()
        },
        onFailure = { e -> setStatusMessage("Git init failed: ${e.message}") },
      )
    }
  }

  fun gitStage(path: String) {
    val repo = currentGitRepo() ?: return
    viewModelScope.launch {
      repo.add(path).onFailure { setStatusMessage("Stage failed: ${it.message}") }
      refreshGitStatus()
    }
  }

  fun gitStageAll() {
    gitStage(".")
  }

  fun gitUnstage(path: String) {
    val repo = currentGitRepo() ?: return
    viewModelScope.launch {
      repo.unstage(path).onFailure { setStatusMessage("Unstage failed: ${it.message}") }
      refreshGitStatus()
    }
  }

  fun gitDiscard(path: String) {
    val repo = currentGitRepo() ?: return
    viewModelScope.launch {
      repo.discard(path).onSuccess {
        val openTab = _uiState.value.openTabs.firstOrNull { it.node.relativePath == path }
        if (openTab != null) {
          val text = runCatching { fs.readText(openTab.node) }.getOrNull()
          if (text != null) {
            openTab.buffer.replace(0, openTab.buffer.length, text)
            openTab.buffer.markSaved()
          }
        }
        refreshTree()
        refreshGitStatus()
        setStatusMessage("Discarded changes in $path")
      }.onFailure { setStatusMessage("Discard failed: ${it.message}") }
    }
  }

  fun gitCommit(message: String) {
    val repo = currentGitRepo() ?: return
    viewModelScope.launch {
      val s = settings.value
      repo.commit(message, s.gitAuthorName, s.gitAuthorEmail).fold(
        onSuccess = { commit ->
          logOutput("Git commit [${commit.shortSha}] ${commit.message}")
          setStatusMessage("Committed ${commit.shortSha}")
          refreshGitStatus()
        },
        onFailure = { e ->
          setStatusMessage("Commit failed: ${e.message}")
        },
      )
    }
  }

  fun gitOpenDiff(path: String, staged: Boolean) {
    val repo = currentGitRepo() ?: return
    viewModelScope.launch {
      val result = repo.diff(path, staged).firstOrNull()
      _uiState.update { it.copy(activeDiff = result) }
    }
  }

  fun closeDiffViewer() {
    _uiState.update { it.copy(activeDiff = null) }
  }

  fun gitCreateBranch(name: String, checkoutAfter: Boolean = true) {
    val repo = currentGitRepo() ?: return
    viewModelScope.launch {
      repo.createBranch(name, checkoutAfter).fold(
        onSuccess = {
          setStatusMessage("Branch '$name' created")
          refreshGitStatus()
        },
        onFailure = { setStatusMessage("Branch failed: ${it.message}") },
      )
    }
  }

  fun gitCheckoutBranch(name: String) {
    val repo = currentGitRepo() ?: return
    viewModelScope.launch {
      repo.checkout(name).fold(
        onSuccess = {
          // Reload open tabs from disk cleanly after checkout
          for (tab in _uiState.value.openTabs) {
            val fresh = runCatching { fs.readText(tab.node) }.getOrNull() ?: continue
            tab.buffer.replace(0, tab.buffer.length, fresh)
            tab.buffer.markSaved()
          }
          refreshTree()
          refreshGitStatus()
          setStatusMessage("Switched to branch '$name'")
        },
        onFailure = { setStatusMessage("Checkout failed: ${it.message}") },
      )
    }
  }

  fun availableCommands(): List<PaletteCommand> =
    listOf(
      PaletteCommand("run.compileAndRun", "Run: Compile & Run Active File", "Run", "F5"),
      PaletteCommand("file.save", "File: Save", "File", "Ctrl+S"),
      PaletteCommand("file.quickOpen", "File: Quick Open", "Navigation", "Ctrl+P"),
      PaletteCommand("file.newProject", "Workspace: New Project", "Workspace", ""),
      PaletteCommand("file.welcome", "Workspace: Welcome Screen", "Workspace", ""),
      PaletteCommand("view.toggleSidebar", "View: Toggle Side Panel", "View", "Ctrl+B"),
      PaletteCommand("view.toggleTerminal", "View: Toggle Terminal", "View", "Ctrl+`"),
      PaletteCommand("view.sourceControl", "View: Show Source Control (Git)", "Git", ""),
      PaletteCommand("terminal.new", "Terminal: New Terminal Tab", "Terminal", ""),
      PaletteCommand("terminal.clear", "Terminal: Clear Buffer", "Terminal", "Ctrl+L"),
      PaletteCommand("git.init", "Git: Initialize Repository", "Git", ""),
      PaletteCommand("git.stageAll", "Git: Stage All Changes", "Git", ""),
      PaletteCommand("view.splitEditor", "View: Toggle Split Editor", "View", ""),
      PaletteCommand("view.wordWrap", "View: Toggle Word Wrap", "Editor", ""),
      PaletteCommand("view.lineNumbers", "View: Toggle Line Numbers", "Editor", ""),
      PaletteCommand("view.extraKeys", "View: Toggle Extra-Key Row", "Editor", ""),
      PaletteCommand("edit.find", "Edit: Find in File", "Edit", "Ctrl+F"),
      PaletteCommand("edit.replace", "Edit: Find and Replace", "Edit", "Ctrl+H"),
      PaletteCommand("edit.triggerCompletion", "IntelliSense: Trigger Auto-Complete", "Edit", "Ctrl+Space"),
      PaletteCommand("edit.globalSearch", "Search: Find in Project", "Search", "Ctrl+Shift+F"),
      PaletteCommand("edit.goToLine", "Go: Go to Line...", "Navigation", "Ctrl+G"),
      PaletteCommand("prefs.settings", "Preferences: Open Settings", "Preferences", ""),
      PaletteCommand("prefs.themes", "Preferences: Color Theme Picker", "Preferences", ""),
      PaletteCommand("prefs.runtime", "Preferences: Runtime Capabilities", "Preferences", ""),
      PaletteCommand("theme.oneDark", "Theme: One Dark", "Theme", ""),
      PaletteCommand("theme.oneLight", "Theme: One Light", "Theme", ""),
      PaletteCommand("theme.dracula", "Theme: Dracula", "Theme", ""),
      PaletteCommand("theme.nord", "Theme: Nord", "Theme", ""),
      PaletteCommand("theme.catppuccinMocha", "Theme: Catppuccin Mocha", "Theme", ""),
      PaletteCommand("theme.tokyoNight", "Theme: Tokyo Night", "Theme", ""),
      PaletteCommand("theme.gruvboxDark", "Theme: Gruvbox Dark", "Theme", ""),
      PaletteCommand("theme.solarizedDark", "Theme: Solarized Dark", "Theme", ""),
      PaletteCommand("theme.githubLight", "Theme: GitHub Light", "Theme", ""),
      PaletteCommand("theme.highContrast", "Theme: High Contrast", "Theme", ""),
    )

  fun filterCommands(query: String): List<PaletteCommand> {
    val all = availableCommands()
    if (query.isBlank()) return all
    return all
      .mapNotNull { cmd -> FuzzyMatcher.match(query, cmd.title)?.let { cmd to it.score } }
      .sortedByDescending { it.second }
      .map { it.first }
  }

  fun executeCommand(id: String) {
    openCommandPalette(false)
    when (id) {
      "run.compileAndRun" -> compileAndRunActiveFile()
      "file.save" -> saveActiveTab()
      "file.quickOpen" -> openQuickOpen(true)
      "file.newProject" -> showNewProjectDialog(true)
      "file.welcome" -> showWelcomeScreen(true)
      "view.toggleSidebar" -> toggleSidePanel()
      "view.toggleTerminal" -> {
        _uiState.update { it.copy(bottomPanelVisible = !it.bottomPanelVisible, activeBottomPanel = BottomPanelTab.TERMINAL) }
      }
      "view.sourceControl" -> _uiState.update { it.copy(sidePanelVisible = true, activeSidePanel = SidePanelTab.GIT) }
      "terminal.new" -> newTerminalSession()
      "terminal.clear" -> activeTerminalSession()?.clearScreen()
      "git.init" -> gitInit()
      "git.stageAll" -> gitStageAll()
      "view.splitEditor" -> toggleSplitPane()
      "view.wordWrap" -> updateSettings { it.copy(wordWrap = !it.wordWrap) }
      "view.lineNumbers" -> updateSettings { it.copy(showLineNumbers = !it.showLineNumbers) }
      "view.extraKeys" -> updateSettings { it.copy(showExtraKeyRow = !it.showExtraKeyRow) }
      "edit.find" -> showFindBar(false)
      "edit.replace" -> showFindBar(true)
      "edit.triggerCompletion" -> triggerCompletionManually()
      "edit.globalSearch" -> _uiState.update { it.copy(sidePanelVisible = true, activeSidePanel = SidePanelTab.SEARCH) }
      "edit.goToLine" -> openGoToLine(true)
      "prefs.settings" -> _uiState.update { it.copy(sidePanelVisible = true, activeSidePanel = SidePanelTab.SETTINGS) }
      "prefs.themes" -> openThemePicker(true)
      "prefs.runtime" -> _uiState.update { it.copy(sidePanelVisible = true, activeSidePanel = SidePanelTab.RUNTIME) }
      "theme.oneDark" -> updateSettings { it.copy(themeId = "one_dark", followSystemTheme = false) }
      "theme.oneLight" -> updateSettings { it.copy(themeId = "one_light", followSystemTheme = false) }
      "theme.dracula" -> updateSettings { it.copy(themeId = "dracula", followSystemTheme = false) }
      "theme.nord" -> updateSettings { it.copy(themeId = "nord", followSystemTheme = false) }
      "theme.catppuccinMocha" -> updateSettings { it.copy(themeId = "catppuccin_mocha", followSystemTheme = false) }
      "theme.tokyoNight" -> updateSettings { it.copy(themeId = "tokyo_night", followSystemTheme = false) }
      "theme.gruvboxDark" -> updateSettings { it.copy(themeId = "gruvbox_dark", followSystemTheme = false) }
      "theme.solarizedDark" -> updateSettings { it.copy(themeId = "solarized_dark", followSystemTheme = false) }
      "theme.githubLight" -> updateSettings { it.copy(themeId = "github_light", followSystemTheme = false) }
      "theme.highContrast" -> updateSettings { it.copy(themeId = "high_contrast", followSystemTheme = false) }
    }
  }

  fun clearStatusMessage() {
    _uiState.update { it.copy(statusMessage = null) }
  }

  private fun setStatusMessage(msg: String) {
    _uiState.update { it.copy(statusMessage = msg) }
  }

  fun logOutput(message: String) {
    val stamp = timeFormat.format(Date())
    _uiState.update { s ->
      val next = (s.outputLogs + "[$stamp] $message").takeLast(400)
      s.copy(outputLogs = next)
    }
  }

  private fun persistSessionSnapshot() {
    val s = _uiState.value
    viewModelScope.launch {
      val tabs = s.openTabs.map { it.node to it.buffer.selectionSet.primary.active }
      recovery.saveSession(s.workspace, tabs, s.activeTabId)
    }
  }
}
