package com.example.ide

import com.quark.buffer.TextBuffer
import com.quark.editor.DiagnosticMarker
import com.quark.editor.EditorMetrics
import com.quark.fs.FuzzyFileMatch
import com.quark.fs.GlobalSearchFileResult
import com.quark.fs.VfsNode
import com.quark.fs.VisibleTreeItem
import com.quark.fs.WorkspaceRoot
import com.quark.git.FileDiffResult
import com.quark.git.GitRepoSnapshot
import com.quark.intel.CompletionItem
import com.quark.intel.DocumentDiagnostic
import com.quark.intel.SignatureHint

enum class SidePanelTab(val label: String) {
  EXPLORER("Explorer"),
  SEARCH("Search"),
  GIT("Source Control"),
  RUNTIME("Runtime"),
  SETTINGS("Settings"),
}

enum class BottomPanelTab(val label: String) {
  TERMINAL("Terminal"),
  OUTPUT("Output"),
  PREVIEW("Preview"),
}

/** Represents an open document tab backed by a live [TextBuffer]. */
data class OpenTab(
  val id: String,
  val node: VfsNode,
  val buffer: TextBuffer,
  val isDirty: Boolean = false,
  val cursorLine: Int = 1,
  val cursorCol: Int = 1,
  val cursorCount: Int = 1,
  val selectionLen: Int = 0,
  val recoveredFromCrash: Boolean = false,
)

/** Command entry displayed in the Command Palette (`Ctrl+Shift+P`). */
data class PaletteCommand(
  val id: String,
  val title: String,
  val category: String,
  val shortcut: String = "",
)

/** Anchored IntelliSense popup state rendered near the active editor caret. */
data class CompletionPopupState(
  val visible: Boolean = false,
  val tabId: String? = null,
  val items: List<CompletionItem> = emptyList(),
  val selectedIndex: Int = 0,
  val signatureHint: SignatureHint? = null,
  val caretXPx: Float = 0f,
  val caretYPx: Float = 0f,
  val lineHeightPx: Float = 42f,
)

/** Immutable state snapshot driving the IDE UI shell. */
data class IdeUiState(
  val isInitialized: Boolean = false,
  val workspace: WorkspaceRoot? = null,
  val showWelcome: Boolean = true,
  val privateProjects: List<WorkspaceRoot> = emptyList(),
  val recentWorkspaces: List<WorkspaceRoot> = emptyList(),
  val visibleTreeItems: List<VisibleTreeItem> = emptyList(),
  val selectedExplorerNodeId: String? = null,
  val openTabs: List<OpenTab> = emptyList(),
  val activeTabId: String? = null,
  val secondaryTabId: String? = null,
  val isSplitEnabled: Boolean = false,
  val focusedPane: Int = 0,
  val sidePanelVisible: Boolean = true,
  val activeSidePanel: SidePanelTab = SidePanelTab.EXPLORER,
  val bottomPanelVisible: Boolean = false,
  val activeBottomPanel: BottomPanelTab = BottomPanelTab.TERMINAL,
  val findBarVisible: Boolean = false,
  val replaceBarVisible: Boolean = false,
  val quickOpenVisible: Boolean = false,
  val quickOpenQuery: String = "",
  val quickOpenMatches: List<FuzzyFileMatch> = emptyList(),
  val commandPaletteVisible: Boolean = false,
  val themePickerVisible: Boolean = false,
  val goToLineVisible: Boolean = false,
  val newProjectDialogVisible: Boolean = false,
  val isRunningCode: Boolean = false,
  val htmlPreviewContent: String? = null,
  val searchQuery: String = "",
  val searchRegex: Boolean = false,
  val searchCaseSensitive: Boolean = false,
  val searchPathFilter: String = "",
  val isSearchingGlobal: Boolean = false,
  val globalSearchResults: List<GlobalSearchFileResult> = emptyList(),
  val gitSnapshot: GitRepoSnapshot = GitRepoSnapshot(),
  val activeDiff: FileDiffResult? = null,
  val activeTabGutterDiff: FileDiffResult? = null,
  val completionPopup: CompletionPopupState = CompletionPopupState(),
  val activeDiagnostics: List<DocumentDiagnostic> = emptyList(),
  val activeEditorDiagnostics: List<DiagnosticMarker> = emptyList(),
  val terminalTabCount: Int = 1,
  val activeTerminalIndex: Int = 0,
  val outputLogs: List<String> = emptyList(),
  val metrics: EditorMetrics = EditorMetrics(),
  val stickyCtrl: Boolean = false,
  val stickyAlt: Boolean = false,
  val statusMessage: String? = null,
)
