package com.quark.editor

import com.quark.buffer.TextRange

/**
 * Semantic syntax token categories used to colorize text spans without coupling `:editor-view`
 * directly to a specific lexer implementation.
 */
enum class TokenType {
  PLAIN,
  KEYWORD,
  TYPE,
  FUNCTION,
  VARIABLE,
  STRING,
  NUMBER,
  COMMENT,
  OPERATOR,
  PUNCTUATION,
  ANNOTATION,
  CONSTANT,
  TAG,
  ATTRIBUTE,
}

/**
 * Packed immutable token span within a single line (`[startCol, endCol)`).
 */
data class LineToken(
  val startCol: Int,
  val endCol: Int,
  val type: TokenType,
)

/**
 * Interface consumed by [QuarkEditorView] during `onDraw` to obtain syntax tokens for a line.
 * Implementations should return cached results without allocating on the UI thread when possible.
 */
fun interface SyntaxSpanProvider {
  fun getLineTokens(lineIndex: Int): List<LineToken>
}

/** Severity level for diagnostic squiggles and gutter markers. */
enum class DiagnosticSeverity {
  ERROR,
  WARNING,
  INFO,
  HINT,
}

/**
 * Diagnostic annotation displayed as a wavy underline and gutter indicator.
 */
data class DiagnosticMarker(
  val line: Int,
  val startCol: Int,
  val endCol: Int,
  val severity: DiagnosticSeverity,
  val message: String,
)

/**
 * Foldable code block spanning `[startLine, endLine]` (inclusive).
 */
data class FoldRegion(
  val startLine: Int,
  val endLine: Int,
  val isCollapsed: Boolean = false,
)

/** Color palette consumed by [QuarkEditorView]. */
data class EditorColorPalette(
  val background: Int,
  val foreground: Int,
  val gutterBackground: Int,
  val gutterForeground: Int,
  val gutterActiveForeground: Int,
  val gutterDivider: Int,
  val currentLineHighlight: Int,
  val selectionBackground: Int,
  val cursorColor: Int,
  val indentGuideColor: Int,
  val rulerColor: Int,
  val bracketMatchBackground: Int,
  val bracketMatchBorder: Int,
  val searchMatchBackground: Int,
  val searchActiveMatchBackground: Int,
  val composingUnderlineColor: Int,
  val foldBadgeBackground: Int,
  val foldBadgeForeground: Int,
  val errorSquiggle: Int,
  val warningSquiggle: Int,
  val infoSquiggle: Int,
  val handleColor: Int,
  val tokenColors: Map<TokenType, Int>,
) {
  fun colorFor(type: TokenType): Int = tokenColors[type] ?: foreground
}

/** Editor behavior and visual configuration. */
data class EditorConfig(
  val fontSizeSp: Float = 14f,
  val lineHeightMultiplier: Float = 1.42f,
  val tabSize: Int = 4,
  val insertSpaces: Boolean = true,
  val wordWrap: Boolean = false,
  val showLineNumbers: Boolean = true,
  val showIndentGuides: Boolean = true,
  val highlightCurrentLine: Boolean = true,
  val rulerColumn: Int = 80,
  val ligaturesEnabled: Boolean = true,
  val autoCloseBrackets: Boolean = true,
)

/** High-level IDE shortcuts dispatched by [QuarkEditorView] hardware keybindings. */
enum class EditorAction {
  QUICK_OPEN,
  COMMAND_PALETTE,
  SAVE,
  TOGGLE_COMMENT,
  ADD_NEXT_OCCURRENCE,
  MOVE_LINE_UP,
  MOVE_LINE_DOWN,
  TOGGLE_SIDEBAR,
  FIND,
  REPLACE,
  GLOBAL_SEARCH,
  GO_TO_LINE,
  TOGGLE_TERMINAL,
  FORMAT_DOCUMENT,
  TRIGGER_COMPLETION,
}

/** Telemetry snapshot exposed for the debug overlay. */
data class EditorMetrics(
  val lastKeystrokeLatencyMs: Float = 0f,
  val lastDrawTimeMs: Float = 0f,
  val visibleRows: Int = 0,
)

/** Active find/replace state held by [QuarkEditorView]. */
data class FindState(
  val query: String = "",
  val isRegex: Boolean = false,
  val caseSensitive: Boolean = false,
  val wholeWord: Boolean = false,
  val matches: List<TextRange> = emptyList(),
  val activeIndex: Int = -1,
)
