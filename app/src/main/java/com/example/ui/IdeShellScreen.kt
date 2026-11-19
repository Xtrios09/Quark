package com.example.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.ide.BottomPanelTab
import com.example.ide.IdeViewModel
import com.example.ide.OpenTab
import com.example.ide.SidePanelTab
import com.example.settings.EditorFontChoice
import com.example.ui.components.ActivityRail
import com.example.ui.components.CommandPaletteDialog
import com.example.ui.components.CompletionOverlay
import com.example.ui.components.DiffViewerDialog
import com.example.ui.components.ExplorerPanel
import com.example.ui.components.ExtraKeyRow
import com.example.ui.components.FindReplaceBar
import com.example.ui.components.GlobalSearchPanel
import com.example.ui.components.GoToLineDialog
import com.example.ui.components.QuickOpenDialog
import com.example.ui.components.RuntimeCapabilitiesPanel
import com.example.ui.components.SettingsPanel
import com.example.ui.components.SourceControlPanel
import com.example.ui.components.TerminalPanel
import com.example.ui.components.TextPromptDialog
import com.example.ui.components.ThemePickerDialog
import com.example.ui.components.WelcomeScreen
import com.example.ui.icons.FileTypeBadgeIcon
import com.example.ui.icons.QuarkIcons
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalQuarkTheme
import com.example.ui.theme.QuarkMaterialTheme
import com.example.ui.theme.QuarkThemes
import com.quark.editor.DiagnosticMarker
import com.quark.editor.QuarkEditorView
import com.quark.editor.syntax.SyntaxGrammarRegistry
import com.quark.fs.VfsNode
import com.quark.intel.DiagSeverity

private sealed class ActiveFileDialog {
  data class NewFile(val parent: VfsNode?) : ActiveFileDialog()
  data class NewFolder(val parent: VfsNode?) : ActiveFileDialog()
  data class Rename(val node: VfsNode) : ActiveFileDialog()
  data class ConfirmDelete(val node: VfsNode) : ActiveFileDialog()
  data object NewBranch : ActiveFileDialog()
}

@Composable
fun IdeShellScreen(viewModel: IdeViewModel) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val systemDark = isSystemInDarkTheme()
  val themeDef = remember(settings.themeId, settings.followSystemTheme, systemDark) {
    val selected = QuarkThemes.byId(settings.themeId)
    if (!settings.followSystemTheme || selected.isDark == systemDark) selected
    else if (systemDark) QuarkThemes.OneDark else QuarkThemes.OneLight
  }
  val context = LocalContext.current

  val jetbrainsTypeface = remember {
    ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
  }

  // Activity Result Launchers for SAF & Zip Import/Export
  val openSafLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
    if (uri != null) viewModel.openSafFolder(uri)
  }
  val importZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
    if (uri != null) {
      var name = "imported_project"
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) name = c.getString(0) ?: name
      }
      viewModel.importZipProject(uri, name)
    }
  }
  val exportZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
    if (uri != null) viewModel.exportCurrentProjectZip(uri)
  }

  var activeFileDialog by remember { mutableStateOf<ActiveFileDialog?>(null) }
  var primaryEditorRef by remember { mutableStateOf<QuarkEditorView?>(null) }
  var secondaryEditorRef by remember { mutableStateOf<QuarkEditorView?>(null) }
  var showSelectionBar by remember { mutableStateOf(false) }

  val focusedEditor: QuarkEditorView? =
    if (state.isSplitEnabled && state.focusedPane == 1) secondaryEditorRef else primaryEditorRef

  // Clear transient status toast
  LaunchedEffect(state.statusMessage) {
    if (state.statusMessage != null) {
      kotlinx.coroutines.delay(2500L)
      viewModel.clearStatusMessage()
    }
  }

  BackHandler(enabled = state.themePickerVisible) {
    viewModel.openThemePicker(false)
  }
  BackHandler(enabled = state.completionPopup.visible) {
    viewModel.dismissCompletion()
  }
  BackHandler(enabled = state.showWelcome && state.workspace != null) {
    viewModel.showWelcomeScreen(false)
  }
  BackHandler(enabled = state.findBarVisible) {
    viewModel.hideFindBar()
  }

  QuarkMaterialTheme(theme = themeDef) {
    val theme = LocalQuarkTheme.current
    val density = LocalDensity.current

    var sidePanelWidthDp by remember { mutableFloatStateOf(260f) }
    var bottomPanelHeightDp by remember { mutableFloatStateOf(200f) }
    var splitWeight by remember { mutableFloatStateOf(0.5f) }

    BoxWithConstraints(
      modifier =
        Modifier
          .fillMaxSize()
          .background(theme.ui.editorBg)
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .imePadding()
    ) {
      val availableWidth = maxWidth
      val isCompact = availableWidth < 600.dp

      if (state.showWelcome) {
        WelcomeScreen(
          privateProjects = state.privateProjects,
          recentWorkspaces = state.recentWorkspaces,
          canDismiss = state.workspace != null,
          onCreateProject = { name, tpl -> viewModel.createProject(name, tpl) },
          onOpenWorkspace = { ws -> viewModel.openWorkspace(ws) },
          onOpenSafFolder = { openSafLauncher.launch(null) },
          onImportZip = { importZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
          onOpenThemes = { viewModel.openThemePicker(true) },
          onDismiss = { viewModel.showWelcomeScreen(false) },
        )
      } else {
        Column(modifier = Modifier.fillMaxSize()) {
          // Top compact IDE toolbar
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(theme.ui.activityRailBg)
                .border(0.5.dp, theme.ui.border)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            IconButton(
              onClick = { viewModel.toggleSidePanel() },
              modifier = Modifier.size(40.dp).testTag("toolbar_toggle_sidebar"),
            ) {
              Icon(Icons.Default.Menu, contentDescription = "Toggle Sidebar", tint = theme.ui.textSecondary, modifier = Modifier.size(20.dp))
            }
            Text(
              text = state.workspace?.name ?: "Quark",
              color = theme.ui.textPrimary,
              fontSize = 14.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.weight(1f))

            IconButton(
              onClick = { viewModel.compileAndRunActiveFile() },
              enabled = !state.isRunningCode,
              modifier = Modifier.size(40.dp).testTag("toolbar_run"),
            ) {
              if (state.isRunningCode) {
                CircularProgressIndicator(
                  color = theme.ui.success,
                  strokeWidth = 2.dp,
                  modifier = Modifier.size(16.dp),
                )
              } else {
                Icon(QuarkIcons.PlayTriangle, contentDescription = "Compile and Run", tint = theme.ui.success, modifier = Modifier.size(19.dp))
              }
            }
            IconButton(
              onClick = { focusedEditor?.undo() },
              modifier = Modifier.size(40.dp).testTag("toolbar_undo"),
            ) {
              Icon(QuarkIcons.UndoArrow, contentDescription = "Undo", tint = theme.ui.textSecondary, modifier = Modifier.size(19.dp))
            }
            IconButton(
              onClick = { focusedEditor?.redo() },
              modifier = Modifier.size(40.dp).testTag("toolbar_redo"),
            ) {
              Icon(QuarkIcons.RedoArrow, contentDescription = "Redo", tint = theme.ui.textSecondary, modifier = Modifier.size(19.dp))
            }
            IconButton(
              onClick = { viewModel.saveActiveTab() },
              modifier = Modifier.size(40.dp).testTag("toolbar_save"),
            ) {
              Icon(QuarkIcons.SaveDisk, contentDescription = "Save", tint = theme.ui.textSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(
              onClick = { viewModel.showFindBar(withReplace = false) },
              modifier = Modifier.size(40.dp).testTag("toolbar_find"),
            ) {
              Icon(QuarkIcons.SearchGlass, contentDescription = "Find", tint = theme.ui.textSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(
              onClick = { viewModel.toggleSplitPane() },
              modifier = Modifier.size(40.dp).testTag("toolbar_split"),
            ) {
              Icon(
                QuarkIcons.SplitPane,
                contentDescription = "Split Editor",
                tint = if (state.isSplitEnabled) theme.ui.accent else theme.ui.textSecondary,
                modifier = Modifier.size(19.dp),
              )
            }
            IconButton(
              onClick = { viewModel.selectBottomPanel(com.example.ide.BottomPanelTab.TERMINAL) },
              modifier = Modifier.size(40.dp).testTag("toolbar_terminal"),
            ) {
              Icon(
                QuarkIcons.TerminalPrompt,
                contentDescription = "Terminal",
                tint = if (state.bottomPanelVisible && state.activeBottomPanel == BottomPanelTab.TERMINAL) theme.ui.accent else theme.ui.textSecondary,
                modifier = Modifier.size(19.dp),
              )
            }
            IconButton(
              onClick = { viewModel.openThemePicker(true) },
              modifier = Modifier.size(40.dp).testTag("toolbar_themes"),
            ) {
              Icon(QuarkIcons.Palette, contentDescription = "Color Themes", tint = theme.ui.textSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(
              onClick = { viewModel.openQuickOpen(true) },
              modifier = Modifier.size(40.dp).testTag("toolbar_quick_open"),
            ) {
              Icon(QuarkIcons.QuickOpenFile, contentDescription = "Quick Open", tint = theme.ui.textSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(
              onClick = { viewModel.openCommandPalette(true) },
              modifier = Modifier.size(40.dp).testTag("toolbar_palette"),
            ) {
              Icon(Icons.Default.ViewAgenda, contentDescription = "Command Palette", tint = theme.ui.accent, modifier = Modifier.size(18.dp))
            }
          }

          // Main Workspace Row: ActivityRail + SidePanel + Editor/Bottom Area
          Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val gitTotalChanges = state.gitSnapshot.stagedChanges.size + state.gitSnapshot.unstagedChanges.size
            ActivityRail(
              activeTab = state.activeSidePanel,
              sidePanelVisible = state.sidePanelVisible,
              gitChangeCount = gitTotalChanges,
              onSelectTab = { viewModel.selectSidePanel(it) },
              onOpenWelcome = { viewModel.showWelcomeScreen(true) },
              onOpenThemePicker = { viewModel.openThemePicker(true) },
            )

            val maxSideDp = (availableWidth.value * 0.42f).coerceAtLeast(220f)
            val clampedSideDp = sidePanelWidthDp.coerceIn(180f, maxSideDp)

            if (state.sidePanelVisible) {
              Box(
                modifier =
                  Modifier
                    .width(if (isCompact) availableWidth - 52.dp else clampedSideDp.dp)
                    .fillMaxHeight()
              ) {
                when (state.activeSidePanel) {
                  SidePanelTab.EXPLORER ->
                    ExplorerPanel(
                      state = state,
                      onNodeClick = { node ->
                        viewModel.openFile(node)
                        if (isCompact && !node.isDirectory) viewModel.toggleSidePanel()
                      },
                      onCreateFile = { parent -> activeFileDialog = ActiveFileDialog.NewFile(parent) },
                      onCreateFolder = { parent -> activeFileDialog = ActiveFileDialog.NewFolder(parent) },
                      onRenameNode = { node -> activeFileDialog = ActiveFileDialog.Rename(node) },
                      onDeleteNode = { node -> activeFileDialog = ActiveFileDialog.ConfirmDelete(node) },
                      onDuplicateNode = { node -> viewModel.duplicateNode(node) },
                      onRefresh = { viewModel.refreshTree() },
                      onExportZip = {
                        val name = (state.workspace?.name ?: "project") + ".zip"
                        exportZipLauncher.launch(name)
                      },
                      onOpenSafFolder = { openSafLauncher.launch(null) },
                    )
                  SidePanelTab.SEARCH ->
                    GlobalSearchPanel(
                      state = state,
                      onParamsChange = { q, r, c, f -> viewModel.updateGlobalSearchParams(q, r, c, f) },
                      onRunSearch = { viewModel.runGlobalSearch() },
                      onOpenMatch = { node, line, col ->
                        viewModel.openFile(node, line, col)
                        if (isCompact) viewModel.toggleSidePanel()
                      },
                    )
                  SidePanelTab.GIT ->
                    SourceControlPanel(
                      snapshot = state.gitSnapshot,
                      onInitRepo = { viewModel.gitInit() },
                      onRefresh = { viewModel.refreshGitStatus() },
                      onStage = { path -> viewModel.gitStage(path) },
                      onStageAll = { viewModel.gitStageAll() },
                      onUnstage = { path -> viewModel.gitUnstage(path) },
                      onDiscard = { path -> viewModel.gitDiscard(path) },
                      onCommit = { msg -> viewModel.gitCommit(msg) },
                      onOpenDiff = { path, staged -> viewModel.gitOpenDiff(path, staged) },
                      onCheckoutBranch = { b -> viewModel.gitCheckoutBranch(b) },
                      onRequestNewBranch = { activeFileDialog = ActiveFileDialog.NewBranch },
                    )
                  SidePanelTab.RUNTIME -> RuntimeCapabilitiesPanel()
                  SidePanelTab.SETTINGS ->
                    SettingsPanel(
                      settings = settings,
                      onUpdate = { transform -> viewModel.updateSettings(transform) },
                    )
                }
              }

              // Touch-resizable Side Panel Splitter
              if (!isCompact) {
                Box(
                  modifier =
                    Modifier
                      .width(5.dp)
                      .fillMaxHeight()
                      .background(theme.ui.border)
                      .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                          val deltaDp = with(density) { dragAmount.toDp().value }
                          sidePanelWidthDp = (sidePanelWidthDp + deltaDp).coerceIn(180f, maxSideDp)
                        }
                      }
                )
              }
            }

            if (!isCompact || !state.sidePanelVisible) {
            // Right side: Editor Area + Bottom Panel
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
              // Tab Bar
              if (state.openTabs.isNotEmpty()) {
                Row(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .height(38.dp)
                      .background(theme.ui.tabBarBg)
                      .horizontalScroll(rememberScrollState()),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  for (tab in state.openTabs) {
                    val isActive = tab.id == state.activeTabId || (state.isSplitEnabled && tab.id == state.secondaryTabId)
                    EditorTabItem(
                      tab = tab,
                      isActive = isActive,
                      onClick = { viewModel.selectTab(tab.id) },
                      onClose = { viewModel.closeTab(tab.id) },
                    )
                  }
                }
                HorizontalDivider(color = theme.ui.border)
              }

              // Find/Replace Bar
              val currentTab = state.openTabs.firstOrNull { it.id == state.activeTabId }
              if (state.findBarVisible && currentTab != null) {
                val targetEditor = focusedEditor
                FindReplaceBar(
                  findState = targetEditor?.findState ?: com.quark.editor.FindState(),
                  showReplace = state.replaceBarVisible,
                  onQueryChange = { q, r, c, w -> targetEditor?.setFindQuery(q, r, c, w) },
                  onFindNext = { targetEditor?.findNext() },
                  onFindPrev = { targetEditor?.findPrevious() },
                  onReplaceOne = { rep -> targetEditor?.replaceCurrent(rep) },
                  onReplaceAll = { rep -> targetEditor?.replaceAll(rep) },
                  onClose = {
                    targetEditor?.setFindQuery("")
                    viewModel.hideFindBar()
                  },
                )
              }

              // Editor Surface (Single or Split Panes) + Anchored IntelliSense Overlay
              BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val editorSurfaceWidth = maxWidth
                val editorSurfaceHeight = maxHeight
                val primaryTab = state.openTabs.firstOrNull { it.id == state.activeTabId }
                val secondaryTab = state.openTabs.firstOrNull { it.id == state.secondaryTabId }
                val completionKeyHandler: (Int) -> Boolean = { keyCode ->
                  when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                      viewModel.moveCompletionSelection(-1)
                      true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                      viewModel.moveCompletionSelection(1)
                      true
                    }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> {
                      viewModel.acceptSelectedCompletion(focusedEditor)
                    }
                    KeyEvent.KEYCODE_ESCAPE -> {
                      viewModel.dismissCompletion()
                      true
                    }
                    else -> false
                  }
                }

                if (primaryTab == null) {
                  EmptyEditorPlaceholder(
                    onQuickOpen = { viewModel.openQuickOpen(true) },
                    onCommandPalette = { viewModel.openCommandPalette(true) },
                    onNewFile = { activeFileDialog = ActiveFileDialog.NewFile(null) },
                  )
                } else if (state.isSplitEnabled && secondaryTab != null) {
                  Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(splitWeight).fillMaxHeight()) {
                      EditorViewHost(
                        tab = primaryTab,
                        settings = settings,
                        palette = theme.editor,
                        typeface = if (settings.fontChoice == EditorFontChoice.JETBRAINS_MONO) jetbrainsTypeface else Typeface.MONOSPACE,
                        gutterDiff = state.activeTabGutterDiff,
                        diagnostics = if (state.focusedPane == 0) state.activeEditorDiagnostics else emptyList(),
                        isCompletionOpen = state.completionPopup.visible && state.focusedPane == 0,
                        onViewCreated = { primaryEditorRef = it },
                        onFocus = { viewModel.setFocusedPane(0) },
                        onActionRequested = { viewModel.handleEditorAction(it) },
                        onCursorOrBufferChanged = { pos, cnt, selLen, dirty ->
                          viewModel.onTabCursorOrBufferChanged(primaryTab.id, pos, cnt, selLen, dirty)
                        },
                        onCaretViewportPos = { x, y, lh -> viewModel.updateCaretViewportPos(x, y, lh) },
                        onCompletionKeyIntercept = completionKeyHandler,
                        onFontSizePinched = { sz -> viewModel.updateSettings { it.copy(fontSizeSp = sz) } },
                        onMetrics = { viewModel.updateMetrics(it) },
                        onStickyModifiers = { c, a -> viewModel.updateStickyModifiers(c, a) },
                        onSelectionMenu = { showSelectionBar = it },
                      )
                    }
                    Box(
                      modifier =
                        Modifier
                          .width(6.dp)
                          .fillMaxHeight()
                          .background(theme.ui.border)
                          .pointerInput(Unit) {
                            detectHorizontalDragGestures { _, dragAmount ->
                              splitWeight = (splitWeight + dragAmount / 1000f).coerceIn(0.25f, 0.75f)
                            }
                          }
                    )
                    Box(modifier = Modifier.weight(1f - splitWeight).fillMaxHeight()) {
                      EditorViewHost(
                        tab = secondaryTab,
                        settings = settings,
                        palette = theme.editor,
                        typeface = if (settings.fontChoice == EditorFontChoice.JETBRAINS_MONO) jetbrainsTypeface else Typeface.MONOSPACE,
                        gutterDiff = null,
                        diagnostics = if (state.focusedPane == 1) state.activeEditorDiagnostics else emptyList(),
                        isCompletionOpen = state.completionPopup.visible && state.focusedPane == 1,
                        onViewCreated = { secondaryEditorRef = it },
                        onFocus = { viewModel.setFocusedPane(1) },
                        onActionRequested = { viewModel.handleEditorAction(it) },
                        onCursorOrBufferChanged = { pos, cnt, selLen, dirty ->
                          viewModel.onTabCursorOrBufferChanged(secondaryTab.id, pos, cnt, selLen, dirty)
                        },
                        onCaretViewportPos = { x, y, lh -> viewModel.updateCaretViewportPos(x, y, lh) },
                        onCompletionKeyIntercept = completionKeyHandler,
                        onFontSizePinched = { sz -> viewModel.updateSettings { it.copy(fontSizeSp = sz) } },
                        onMetrics = { viewModel.updateMetrics(it) },
                        onStickyModifiers = { c, a -> viewModel.updateStickyModifiers(c, a) },
                        onSelectionMenu = { showSelectionBar = it },
                      )
                    }
                  }
                } else {
                  EditorViewHost(
                    tab = primaryTab,
                    settings = settings,
                    palette = theme.editor,
                    typeface = if (settings.fontChoice == EditorFontChoice.JETBRAINS_MONO) jetbrainsTypeface else Typeface.MONOSPACE,
                    gutterDiff = state.activeTabGutterDiff,
                    diagnostics = state.activeEditorDiagnostics,
                    isCompletionOpen = state.completionPopup.visible,
                    onViewCreated = { primaryEditorRef = it },
                    onFocus = { viewModel.setFocusedPane(0) },
                    onActionRequested = { viewModel.handleEditorAction(it) },
                    onCursorOrBufferChanged = { pos, cnt, selLen, dirty ->
                      viewModel.onTabCursorOrBufferChanged(primaryTab.id, pos, cnt, selLen, dirty)
                    },
                    onCaretViewportPos = { x, y, lh -> viewModel.updateCaretViewportPos(x, y, lh) },
                    onCompletionKeyIntercept = completionKeyHandler,
                    onFontSizePinched = { sz -> viewModel.updateSettings { it.copy(fontSizeSp = sz) } },
                    onMetrics = { viewModel.updateMetrics(it) },
                    onStickyModifiers = { c, a -> viewModel.updateStickyModifiers(c, a) },
                    onSelectionMenu = { showSelectionBar = it },
                  )
                }

                // Floating touch selection Cut/Copy/Paste bar
                if (showSelectionBar && primaryTab != null) {
                  Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = theme.ui.tabBarBg,
                    tonalElevation = 6.dp,
                    modifier =
                      Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .border(1.dp, theme.ui.border, RoundedCornerShape(8.dp)),
                  ) {
                    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                      TextButton(onClick = { focusedEditor?.cutSelection(); showSelectionBar = false }) {
                        Text("Cut", fontSize = 12.sp)
                      }
                      TextButton(onClick = { focusedEditor?.copySelection(); showSelectionBar = false }) {
                        Text("Copy", fontSize = 12.sp)
                      }
                      TextButton(onClick = { focusedEditor?.pasteFromClipboard(); showSelectionBar = false }) {
                        Text("Paste", fontSize = 12.sp)
                      }
                      TextButton(onClick = { focusedEditor?.selectAll() }) {
                        Text("All", fontSize = 12.sp)
                      }
                    }
                  }
                }

                // Caret-anchored on-device IntelliSense popup & signature help
                if (primaryTab != null) {
                  CompletionOverlay(
                    state = state.completionPopup,
                    editorWidth = editorSurfaceWidth,
                    editorHeight = editorSurfaceHeight,
                    onAcceptItem = { item -> viewModel.acceptCompletionItem(item, focusedEditor) },
                    onDismiss = { viewModel.dismissCompletion() },
                  )
                }
              }

              // Extra-Key Row
              if (settings.showExtraKeyRow && state.openTabs.isNotEmpty()) {
                ExtraKeyRow(
                  stickyCtrl = state.stickyCtrl,
                  stickyAlt = state.stickyAlt,
                  onToggleCtrl = { focusedEditor?.toggleStickyCtrl() },
                  onToggleAlt = { focusedEditor?.toggleStickyAlt() },
                  onSendKey = { sym ->
                    when {
                      sym == "INTEL" -> viewModel.triggerCompletionManually()
                      state.completionPopup.visible && sym == "ESC" -> viewModel.dismissCompletion()
                      state.completionPopup.visible && sym == "UP" -> viewModel.moveCompletionSelection(-1)
                      state.completionPopup.visible && sym == "DOWN" -> viewModel.moveCompletionSelection(1)
                      state.completionPopup.visible && sym == "TAB" -> viewModel.acceptSelectedCompletion(focusedEditor)
                      else -> focusedEditor?.sendExtraKey(sym)
                    }
                  },
                )
              }

              // Touch-resizable Bottom Output / HTML Preview Panel
              if (state.bottomPanelVisible) {
                Box(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .height(5.dp)
                      .background(theme.ui.border)
                      .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                          val deltaDp = with(density) { dragAmount.toDp().value }
                          bottomPanelHeightDp = (bottomPanelHeightDp - deltaDp).coerceIn(110f, 420f)
                        }
                      }
                )
                Column(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .height(bottomPanelHeightDp.dp)
                      .background(theme.ui.bottomPanelBg)
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                      for (panelTab in BottomPanelTab.entries) {
                        val selected = state.activeBottomPanel == panelTab
                        Text(
                          text = panelTab.label.uppercase(),
                          color = if (selected) theme.ui.textPrimary else theme.ui.textMuted,
                          fontSize = 11.sp,
                          fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                          letterSpacing = 0.8.sp,
                          modifier =
                            Modifier
                              .clip(RoundedCornerShape(4.dp))
                              .background(if (selected) theme.ui.selectionHighlight else Color.Transparent)
                              .clickable { viewModel.selectBottomPanel(panelTab) }
                              .padding(horizontal = 8.dp, vertical = 3.dp)
                              .testTag("bottom_tab_${panelTab.name.lowercase()}"),
                        )
                      }
                    }
                    IconButton(onClick = { viewModel.toggleBottomPanel() }, modifier = Modifier.size(28.dp)) {
                      Icon(Icons.Default.Close, contentDescription = "Close Bottom Panel", tint = theme.ui.textMuted, modifier = Modifier.size(16.dp))
                    }
                  }
                  HorizontalDivider(color = theme.ui.border)
                  when (state.activeBottomPanel) {
                    BottomPanelTab.TERMINAL -> {
                      TerminalPanel(
                        session = viewModel.activeTerminalSession(),
                        tabCount = state.terminalTabCount,
                        activeIndex = state.activeTerminalIndex,
                        typeface = if (settings.fontChoice == EditorFontChoice.JETBRAINS_MONO) jetbrainsTypeface else Typeface.MONOSPACE,
                        fontSizeSp = (settings.fontSizeSp - 1f).coerceIn(10f, 20f),
                        onSelectSession = { viewModel.selectTerminalSession(it) },
                        onNewSession = { viewModel.newTerminalSession() },
                        onCloseSession = { viewModel.closeTerminalSession(it) },
                      )
                    }
                    BottomPanelTab.PREVIEW -> {
                      val html = state.htmlPreviewContent
                      if (html != null) {
                        HtmlPreviewWebView(
                          html = html,
                          onConsoleLog = { msg -> viewModel.logOutput(msg) },
                        )
                      } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                          Text(
                            "Open an .html file and tap Run (▶) to preview it here.",
                            color = theme.ui.textMuted,
                            fontSize = 12.sp,
                          )
                        }
                      }
                    }
                    BottomPanelTab.OUTPUT -> {
                      LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
                        items(state.outputLogs) { line ->
                          Text(
                            text = line,
                            color = theme.ui.textSecondary,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                          )
                        }
                      }
                    }
                  }
                }
              }
            }
            }
          }

          // Bottom Status Bar
          val activeTab = state.openTabs.firstOrNull { it.id == state.activeTabId }
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(theme.ui.statusBarBg)
                .border(0.5.dp, theme.ui.border)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = "${BuildConfig.FLAVOR_NAME.uppercase()} SDK ${BuildConfig.TARGET_SDK_INT}",
              color = theme.ui.accent,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.clickable { viewModel.selectSidePanel(SidePanelTab.RUNTIME) },
            )
            if (state.gitSnapshot.isRepository) {
              Row(
                modifier = Modifier.clickable { viewModel.selectSidePanel(SidePanelTab.GIT) }.testTag("statusbar_git_branch"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Icon(QuarkIcons.GitBranch, contentDescription = "Git Branch", tint = theme.ui.accent, modifier = Modifier.size(13.dp))
                Text(
                  text = state.gitSnapshot.currentBranch,
                  color = theme.ui.statusBarFg,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 11.sp,
                )
              }
            }
            if (activeTab != null) {
              val errorCount = remember(state.activeDiagnostics) {
                state.activeDiagnostics.count { it.severity == DiagSeverity.ERROR }
              }
              val warnCount = remember(state.activeDiagnostics) {
                state.activeDiagnostics.count { it.severity == DiagSeverity.WARNING }
              }
              Text(
                text = "E:$errorCount W:$warnCount",
                color = when {
                  errorCount > 0 -> theme.ui.danger
                  warnCount > 0 -> theme.ui.warning
                  else -> theme.ui.statusBarFg
                },
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                modifier = Modifier.testTag("statusbar_diagnostics"),
              )
            }
            if (state.statusMessage != null) {
              Text(
                text = state.statusMessage!!,
                color = theme.ui.success,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Spacer(Modifier.weight(1f))
            if (activeTab != null) {
              val langName = remember(activeTab.node.name) {
                SyntaxGrammarRegistry.forFileName(activeTab.node.name)?.displayName ?: "Plain Text"
              }
              val selText = if (activeTab.selectionLen > 0) " (${activeTab.selectionLen} sel)" else ""
              val multiText = if (activeTab.cursorCount > 1) " [${activeTab.cursorCount} cursors]" else ""
              Text(
                text = "Ln ${activeTab.cursorLine}, Col ${activeTab.cursorCol}$selText$multiText",
                color = theme.ui.statusBarFg,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                modifier = Modifier.clickable { viewModel.openGoToLine(true) },
              )
              Text(
                text = langName,
                color = theme.ui.accent,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                modifier = Modifier.testTag("statusbar_language"),
              )
              Text(
                text = if (settings.insertSpaces) "Spaces: ${settings.tabSize}" else "Tabs: ${settings.tabSize}",
                color = theme.ui.statusBarFg,
                fontSize = 11.sp,
              )
              Text(
                text = "UTF-8  ${activeTab.buffer.detectedLineEnding.label}",
                color = theme.ui.statusBarFg,
                fontSize = 11.sp,
              )
            }
            Text(
              text = themeDef.displayName,
              color = theme.ui.statusBarFg,
              fontSize = 11.sp,
              modifier = Modifier.clickable { viewModel.openThemePicker(true) }.testTag("statusbar_theme"),
            )
            Icon(
              imageVector = QuarkIcons.TerminalPrompt,
              contentDescription = "Toggle Terminal Panel",
              tint = if (state.bottomPanelVisible) theme.ui.accent else theme.ui.statusBarFg,
              modifier = Modifier.size(16.dp).clickable { viewModel.toggleBottomPanel() }.testTag("statusbar_terminal"),
            )
          }
        }
      }

      // Modal Overlays
      if (state.quickOpenVisible) {
        QuickOpenDialog(
          query = state.quickOpenQuery,
          matches = state.quickOpenMatches,
          onQueryChange = { viewModel.updateQuickOpenQuery(it) },
          onSelect = { m ->
            viewModel.openQuickOpen(false)
            viewModel.openFile(m.node)
          },
          onDismiss = { viewModel.openQuickOpen(false) },
        )
      }

      if (state.commandPaletteVisible) {
        CommandPaletteDialog(
          filterCommands = { q -> viewModel.filterCommands(q) },
          onExecute = { id -> viewModel.executeCommand(id) },
          onDismiss = { viewModel.openCommandPalette(false) },
        )
      }

      if (state.themePickerVisible) {
        ThemePickerDialog(
          settings = settings,
          onSelectTheme = { id ->
            viewModel.updateSettings { it.copy(themeId = id, followSystemTheme = false) }
            viewModel.openThemePicker(false)
          },
          onToggleFollowSystem = { follow ->
            viewModel.updateSettings { it.copy(followSystemTheme = follow) }
          },
          onDismiss = { viewModel.openThemePicker(false) },
        )
      }

      if (state.goToLineVisible) {
        val activeBuf = state.openTabs.firstOrNull { it.id == state.activeTabId }?.buffer
        GoToLineDialog(
          maxLine = activeBuf?.lineCount ?: 1,
          onConfirm = { line, col ->
            viewModel.openGoToLine(false)
            focusedEditor?.goToLine(line, col)
          },
          onDismiss = { viewModel.openGoToLine(false) },
        )
      }

      when (val dlg = activeFileDialog) {
        is ActiveFileDialog.NewFile ->
          TextPromptDialog(
            title = "New File",
            placeholder = "filename.kt",
            confirmLabel = "Create",
            onConfirm = { name ->
              activeFileDialog = null
              viewModel.createFileIn(dlg.parent, name)
            },
            onDismiss = { activeFileDialog = null },
          )
        is ActiveFileDialog.NewFolder ->
          TextPromptDialog(
            title = "New Folder",
            placeholder = "folder_name",
            confirmLabel = "Create",
            onConfirm = { name ->
              activeFileDialog = null
              viewModel.createDirectoryIn(dlg.parent, name)
            },
            onDismiss = { activeFileDialog = null },
          )
        is ActiveFileDialog.Rename ->
          TextPromptDialog(
            title = "Rename ${dlg.node.name}",
            initialValue = dlg.node.name,
            confirmLabel = "Rename",
            onConfirm = { newName ->
              activeFileDialog = null
              viewModel.renameNode(dlg.node, newName)
            },
            onDismiss = { activeFileDialog = null },
          )
        is ActiveFileDialog.ConfirmDelete ->
          AlertDialog(
            onDismissRequest = { activeFileDialog = null },
            title = { Text("Delete ${dlg.node.name}?") },
            text = { Text("This permanently deletes '${dlg.node.relativePath}' from storage.") },
            confirmButton = {
              TextButton(onClick = {
                activeFileDialog = null
                viewModel.deleteNode(dlg.node)
              }) { Text("Delete", color = theme.ui.danger) }
            },
            dismissButton = {
              TextButton(onClick = { activeFileDialog = null }) { Text("Cancel") }
            },
          )
        ActiveFileDialog.NewBranch ->
          TextPromptDialog(
            title = "Create & Checkout Branch",
            placeholder = "feature/my-branch",
            confirmLabel = "Create",
            onConfirm = { branch ->
              activeFileDialog = null
              viewModel.gitCreateBranch(branch, checkoutAfter = true)
            },
            onDismiss = { activeFileDialog = null },
          )
        null -> {}
      }

      val diffToShow = state.activeDiff
      if (diffToShow != null) {
        DiffViewerDialog(
          diff = diffToShow,
          onDismiss = { viewModel.closeDiffViewer() },
        )
      }
    }
  }
}

@Composable
private fun EditorTabItem(
  tab: OpenTab,
  isActive: Boolean,
  onClick: () -> Unit,
  onClose: () -> Unit,
) {
  val theme = LocalQuarkTheme.current
  Row(
    modifier =
      Modifier
        .fillMaxHeight()
        .background(if (isActive) theme.ui.tabActiveBg else theme.ui.tabInactiveBg)
        .border(0.5.dp, theme.ui.border)
        .clickable(onClick = onClick)
        .padding(start = 10.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    FileTypeBadgeIcon(fileName = tab.node.name)
    if (tab.isDirty) {
      Box(
        modifier =
          Modifier
            .size(7.dp)
            .background(if (tab.recoveredFromCrash) theme.ui.warning else theme.ui.accent, CircleShape)
      )
    }
    Text(
      text = tab.node.name,
      color = if (isActive) theme.ui.textPrimary else theme.ui.textSecondary,
      fontSize = 12.sp,
      fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
      maxLines = 1,
    )
    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
      Icon(Icons.Default.Close, contentDescription = "Close ${tab.node.name}", tint = theme.ui.textMuted, modifier = Modifier.size(14.dp))
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPreviewWebView(
  html: String,
  onConsoleLog: (String) -> Unit,
) {
  AndroidView(
    factory = { ctx ->
      WebView(ctx).apply {
        settings.apply {
          javaScriptEnabled = true
          allowFileAccess = false
          allowContentAccess = false
          cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webViewClient = WebViewClient()
        webChromeClient = object : WebChromeClient() {
          override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            onConsoleLog("[PREVIEW ${consoleMessage.messageLevel().name}] ${consoleMessage.message()}")
            return true
          }
        }
        tag = html
        loadDataWithBaseURL("https://quark.local/", html, "text/html", "UTF-8", null)
      }
    },
    update = { webView ->
      if (webView.tag != html) {
        webView.tag = html
        webView.loadDataWithBaseURL("https://quark.local/", html, "text/html", "UTF-8", null)
      }
    },
    modifier = Modifier.fillMaxSize().testTag("html_preview_webview"),
  )
}

@Composable
private fun EmptyEditorPlaceholder(
  onQuickOpen: () -> Unit,
  onCommandPalette: () -> Unit,
  onNewFile: () -> Unit,
) {
  val theme = LocalQuarkTheme.current
  Box(modifier = Modifier.fillMaxSize().background(theme.ui.editorBg), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Quark Editor", color = theme.ui.textSecondary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
      Text("Select a file from the explorer or use shortcuts:", color = theme.ui.textMuted, fontSize = 13.sp)
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        ShortcutPill("Ctrl+P  Quick Open", onQuickOpen)
        ShortcutPill("Ctrl+Shift+P  Commands", onCommandPalette)
        ShortcutPill("New File", onNewFile)
      }
    }
  }
}

@Composable
private fun ShortcutPill(label: String, onClick: () -> Unit) {
  val theme = LocalQuarkTheme.current
  Box(
    modifier =
      Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(theme.ui.sidePanelBg)
        .border(1.dp, theme.ui.border, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp)
  ) {
    Text(label, color = theme.ui.textPrimary, fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp)
  }
}

@Composable
private fun EditorViewHost(
  tab: OpenTab,
  settings: com.example.settings.AppSettings,
  palette: com.quark.editor.EditorColorPalette,
  typeface: Typeface,
  gutterDiff: com.quark.git.FileDiffResult?,
  diagnostics: List<DiagnosticMarker>,
  isCompletionOpen: Boolean,
  onViewCreated: (QuarkEditorView) -> Unit,
  onFocus: () -> Unit,
  onActionRequested: (com.quark.editor.EditorAction) -> Unit,
  onCursorOrBufferChanged: (com.quark.buffer.TextPosition, Int, Int, Boolean) -> Unit,
  onCaretViewportPos: (Float, Float, Float) -> Unit,
  onCompletionKeyIntercept: (Int) -> Boolean,
  onFontSizePinched: (Float) -> Unit,
  onMetrics: (com.quark.editor.EditorMetrics) -> Unit,
  onStickyModifiers: (Boolean, Boolean) -> Unit,
  onSelectionMenu: (Boolean) -> Unit,
) {
  AndroidView(
    factory = { ctx ->
      QuarkEditorView(ctx).apply {
        setEditorTypeface(typeface)
        applyPalette(palette)
        applyConfig(settings.toEditorConfig())
        setSyntaxLanguage(tab.node.name)
        buffer = tab.buffer
        setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocus() }
        onViewCreated(this)
      }
    },
    update = { view ->
      view.onActionRequested = onActionRequested
      view.onCursorOrBufferChanged = onCursorOrBufferChanged
      view.onCaretViewportPositionChanged = onCaretViewportPos
      view.onCompletionKeyIntercept = onCompletionKeyIntercept
      view.isCompletionPopupOpen = isCompletionOpen
      view.onFontSizePinched = onFontSizePinched
      view.onMetricsUpdated = onMetrics
      view.onStickyModifiersChanged = onStickyModifiers
      view.onSelectionContextMenuRequested = onSelectionMenu
      view.setSyntaxLanguage(tab.node.name)
      if (view.buffer !== tab.buffer) {
        view.buffer = tab.buffer
      }
      view.setDiagnostics(diagnostics)
      if (gutterDiff != null && gutterDiff.path == tab.node.relativePath) {
        view.setGitDiffLines(
          addedLines = gutterDiff.addedLines0Based,
          modifiedLines = gutterDiff.modifiedLines0Based,
          deletedLines = gutterDiff.deletedLines0Based,
        )
      } else {
        view.setGitDiffLines(emptySet(), emptySet(), emptySet())
      }
      view.applyPalette(palette)
      view.applyConfig(settings.toEditorConfig())
      view.setEditorTypeface(typeface)
    },
    modifier = Modifier.fillMaxSize().testTag("editor_canvas_${tab.node.name}"),
  )
}
