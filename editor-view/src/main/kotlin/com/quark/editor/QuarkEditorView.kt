package com.quark.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import com.quark.buffer.BufferChangeListener
import com.quark.buffer.Selection
import com.quark.buffer.TextBuffer
import com.quark.buffer.TextPosition
import com.quark.buffer.TextRange
import com.quark.editor.syntax.RegexSyntaxHighlighter
import com.quark.editor.syntax.SyntaxGrammar
import com.quark.editor.syntax.SyntaxGrammarRegistry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Custom hardware-accelerated code editor [View] written from scratch.
 *
 * Designed for zero object allocations inside [onDraw] and on the keystroke path:
 * uses primitive scratch arrays, cached glyph advance widths with a monospace fast path,
 * visible-line-only rendering, line-layout caching for word wrap, multi-cursor selection handles,
 * indent guides, rulers, code folding, bracket matching, and full IME + VS Code keybindings.
 */
class QuarkEditorView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

  private val bufferChangeListener = BufferChangeListener { event ->
    val line = buffer.getLineAtOffset(event.offset.coerceIn(0, buffer.length))
    val multiLineChange =
      event.removedLength > 0 || event.insertedText.indexOf('\n') >= 0
    regexHighlighter.invalidateFromLine(buffer, (line - 1).coerceAtLeast(0), multiLineChange)
  }

  /** Active text document buffer. Replacing it resets scroll and layout caches. */
  var buffer: TextBuffer = TextBuffer("")
    set(value) {
      field.removeChangeListener(bufferChangeListener)
      field = value
      value.addChangeListener(bufferChangeListener)
      layoutCache.clear()
      foldManager.recomputeIndentFolds(value)
      regexHighlighter.configure(value, activeGrammar)
      scrollTo(0, 0)
      updateMetricsAndCursorInfo()
      invalidate()
    }

  var config: EditorConfig = EditorConfig()
    private set

  var palette: EditorColorPalette = defaultDarkPalette()
    private set

  private val regexHighlighter = RegexSyntaxHighlighter { postInvalidate() }
  var activeGrammar: SyntaxGrammar? = null
    private set
  private var currentFileName: String = ""

  var spanProvider: SyntaxSpanProvider? = regexHighlighter
    set(value) {
      field = value
      invalidate()
    }

  private val diagnosticsByLine = HashMap<Int, MutableList<DiagnosticMarker>>()
  private val gitAddedLines = HashSet<Int>()
  private val gitModifiedLines = HashSet<Int>()
  private val gitDeletedLines = HashSet<Int>()
  val foldManager = FoldManager()
  private val glyphCache = GlyphWidthCache()
  private val layoutCache = LineLayoutCache()

  // Sticky modifiers controlled by the extra-key row
  var stickyCtrl: Boolean = false
    private set
  var stickyAlt: Boolean = false
    private set

  // Callbacks to Compose shell
  var onActionRequested: ((EditorAction) -> Unit)? = null
  var onCursorOrBufferChanged: ((TextPosition, Int, Int, Boolean) -> Unit)? = null
  var onFontSizePinched: ((Float) -> Unit)? = null
  var onMetricsUpdated: ((EditorMetrics) -> Unit)? = null
  var onStickyModifiersChanged: ((Boolean, Boolean) -> Unit)? = null
  var onSelectionContextMenuRequested: ((Boolean) -> Unit)? = null
  var onCaretViewportPositionChanged: ((xPx: Float, yPx: Float, lineHeightPx: Float) -> Unit)? = null
  var onCompletionKeyIntercept: ((keyCode: Int) -> Boolean)? = null
  var isCompletionPopupOpen: Boolean = false

  var findState: FindState = FindState()
    private set

  // Pre-allocated Paints (zero allocations in onDraw)
  private val bgPaint = Paint()
  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val gutterBgPaint = Paint()
  private val gutterTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val gutterDividerPaint = Paint()
  private val lineHighlightPaint = Paint()
  private val selectionPaint = Paint()
  private val cursorPaint = Paint()
  private val guidePaint = Paint()
  private val rulerPaint = Paint()
  private val bracketBgPaint = Paint()
  private val bracketBorderPaint = Paint().apply { style = Paint.Style.STROKE }
  private val searchMatchPaint = Paint()
  private val searchActivePaint = Paint()
  private val composingPaint = Paint()
  private val squigglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
  }
  private val foldBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val foldBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val diffGutterPaint = Paint()

  // Reusable scratch buffers (zero allocations in onDraw)
  private var lineScratch = CharArray(4096)
  private val squigglePath = Path()
  private val handlePath = Path()
  private val rectScratch = RectF()
  private val charBuf1 = CharArray(1)

  // Font & geometry metrics
  private var lineHeightPx = 42f
  private var baselineOffsetPx = 30f
  private var gutterWidthPx = 96f
  private var textPaddingLeftPx = 12f
  private var density = resources.displayMetrics.density
  private var scaledDensity = resources.displayMetrics.scaledDensity
  private var maxLineWidthPx = 0f

  // Cursor blinking
  private var cursorVisible = true
  private val cursorBlinkRunnable = object : Runnable {
    override fun run() {
      if (hasFocus()) {
        cursorVisible = !cursorVisible
        invalidate()
        postDelayed(this, CURSOR_BLINK_MS)
      }
    }
  }

  // Scrolling & Gestures
  private val scroller = OverScroller(context)
  private val gestureDetector: GestureDetector
  private val scaleDetector: ScaleGestureDetector
  private var isScaling = false

  // Draggable handle hit tracking
  private enum class DragHandleTarget { NONE, CARET, SELECTION_START, SELECTION_END }
  private var draggingHandle = DragHandleTarget.NONE
  private var startHandleX = -1f
  private var startHandleY = -1f
  private var endHandleX = -1f
  private var endHandleY = -1f
  private var singleHandleX = -1f
  private var singleHandleY = -1f
  private var showSingleCaretHandle = false

  private var lastKeystrokeLatencyMs = 0f

  init {
    isFocusable = true
    isFocusableInTouchMode = true

    gestureDetector = GestureDetector(context, EditorGestureListener())
    scaleDetector = ScaleGestureDetector(context, EditorScaleListener())
    buffer.addChangeListener(bufferChangeListener)

    applyPalette(palette)
    applyConfig(config)
  }

  /** Applies an [EditorColorPalette] and refreshes paint colors. */
  fun applyPalette(newPalette: EditorColorPalette) {
    palette = newPalette
    bgPaint.color = newPalette.background
    textPaint.color = newPalette.foreground
    gutterBgPaint.color = newPalette.gutterBackground
    gutterTextPaint.color = newPalette.gutterForeground
    gutterDividerPaint.color = newPalette.gutterDivider
    gutterDividerPaint.strokeWidth = max(1f, density)
    lineHighlightPaint.color = newPalette.currentLineHighlight
    selectionPaint.color = newPalette.selectionBackground
    cursorPaint.color = newPalette.cursorColor
    cursorPaint.strokeWidth = max(2f, 2f * density)
    guidePaint.color = newPalette.indentGuideColor
    guidePaint.strokeWidth = max(1f, density)
    guidePaint.pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    rulerPaint.color = newPalette.rulerColor
    rulerPaint.strokeWidth = max(1f, density)
    bracketBgPaint.color = newPalette.bracketMatchBackground
    bracketBorderPaint.color = newPalette.bracketMatchBorder
    bracketBorderPaint.strokeWidth = max(1f, density)
    searchMatchPaint.color = newPalette.searchMatchBackground
    searchActivePaint.color = newPalette.searchActiveMatchBackground
    composingPaint.color = newPalette.composingUnderlineColor
    composingPaint.strokeWidth = max(1.5f, 1.5f * density)
    squigglePaint.strokeWidth = max(1.5f, 1.4f * density)
    foldBadgePaint.color = newPalette.foldBadgeBackground
    foldBadgeTextPaint.color = newPalette.foldBadgeForeground
    handlePaint.color = newPalette.handleColor
    invalidate()
  }

  /** Applies [EditorConfig] (font size, word wrap, tab size, ligatures, etc.). */
  fun applyConfig(newConfig: EditorConfig) {
    val wrapChanged = config.wordWrap != newConfig.wordWrap
    val sizeChanged = config.fontSizeSp != newConfig.fontSizeSp
    val ligChanged = config.ligaturesEnabled != newConfig.ligaturesEnabled
    config = newConfig

    val px =
      android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP,
        newConfig.fontSizeSp,
        resources.displayMetrics,
      )
    textPaint.textSize = px
    textPaint.fontFeatureSettings = if (newConfig.ligaturesEnabled) "liga 1, calt 1" else "liga 0, calt 0"
    gutterTextPaint.textSize = px * 0.86f
    foldBadgeTextPaint.textSize = px * 0.82f

    val fm = textPaint.fontMetrics
    val rawHeight = fm.descent - fm.ascent
    lineHeightPx = ceil((rawHeight * newConfig.lineHeightMultiplier).toDouble()).toFloat().coerceAtLeast(16f)
    baselineOffsetPx = -fm.ascent + (lineHeightPx - rawHeight) * 0.5f

    if (sizeChanged || ligChanged || textPaint.typeface != null) {
      glyphCache.rebuild(textPaint)
      layoutCache.clear()
    }
    if (wrapChanged) {
      layoutCache.clear()
      if (newConfig.wordWrap) scrollTo(0, scrollY)
    }
    recomputeGutterWidth()
    invalidate()
  }

  /** Sets editor typeface (e.g. bundled JetBrains Mono). */
  fun setEditorTypeface(tf: Typeface) {
    textPaint.typeface = tf
    gutterTextPaint.typeface = tf
    foldBadgeTextPaint.typeface = tf
    glyphCache.rebuild(textPaint)
    layoutCache.clear()
    recomputeGutterWidth()
    invalidate()
  }

  /** Replaces active diagnostic markers. */
  fun setDiagnostics(markers: List<DiagnosticMarker>) {
    diagnosticsByLine.clear()
    for (m in markers) {
      diagnosticsByLine.getOrPut(m.line) { ArrayList(2) }.add(m)
    }
    invalidate()
  }

  /** Selects regex syntax grammar based on [fileName] (e.g. `Main.kt`, `script.py`). */
  fun setSyntaxLanguage(fileName: String): SyntaxGrammar? {
    val grammar = SyntaxGrammarRegistry.forFileName(fileName)
    if (fileName == currentFileName && grammar?.id == activeGrammar?.id) {
      return activeGrammar
    }
    currentFileName = fileName
    activeGrammar = grammar
    if (spanProvider == null || spanProvider === regexHighlighter) {
      spanProvider = regexHighlighter
      regexHighlighter.configure(buffer, grammar)
    }
    return grammar
  }

  /** Updates inline Git gutter change markers (0-based line indices). */
  fun setGitDiffLines(
    addedLines: Set<Int>,
    modifiedLines: Set<Int>,
    deletedLines: Set<Int>,
  ) {
    if (gitAddedLines == addedLines && gitModifiedLines == modifiedLines && gitDeletedLines == deletedLines) {
      return
    }
    gitAddedLines.clear()
    gitAddedLines.addAll(addedLines)
    gitModifiedLines.clear()
    gitModifiedLines.addAll(modifiedLines)
    gitDeletedLines.clear()
    gitDeletedLines.addAll(deletedLines)
    invalidate()
  }

  /** Updates active search query and computes matches. */
  fun setFindQuery(
    query: String,
    isRegex: Boolean = findState.isRegex,
    caseSensitive: Boolean = findState.caseSensitive,
    wholeWord: Boolean = findState.wholeWord,
  ) {
    val matches =
      if (query.isEmpty()) emptyList()
      else buffer.findAllMatches(query, isRegex, caseSensitive, wholeWord)
    val caret = buffer.selectionSet.primary.start
    val idx =
      if (matches.isEmpty()) -1
      else matches.indexOfFirst { it.start >= caret }.let { if (it < 0) 0 else it }
    findState = FindState(query, isRegex, caseSensitive, wholeWord, matches, idx)
    if (idx >= 0) {
      val m = matches[idx]
      buffer.setSelection(m.start, m.end)
      ensureCaretVisible()
    }
    invalidate()
  }

  fun findNext(): Boolean {
    val matches = findState.matches
    if (matches.isEmpty()) return false
    val nextIdx = (findState.activeIndex + 1) % matches.size
    findState = findState.copy(activeIndex = nextIdx)
    val m = matches[nextIdx]
    buffer.setSelection(m.start, m.end)
    ensureCaretVisible()
    invalidate()
    return true
  }

  fun findPrevious(): Boolean {
    val matches = findState.matches
    if (matches.isEmpty()) return false
    val prevIdx = if (findState.activeIndex <= 0) matches.lastIndex else findState.activeIndex - 1
    findState = findState.copy(activeIndex = prevIdx)
    val m = matches[prevIdx]
    buffer.setSelection(m.start, m.end)
    ensureCaretVisible()
    invalidate()
    return true
  }

  fun replaceCurrent(replacement: String): Boolean {
    val matches = findState.matches
    if (matches.isEmpty() || findState.activeIndex !in matches.indices) return false
    val active = matches[findState.activeIndex]
    buffer.replace(active.start, active.length, replacement)
    setFindQuery(findState.query, findState.isRegex, findState.caseSensitive, findState.wholeWord)
    onBufferMutatedInternal()
    return true
  }

  fun replaceAll(replacement: String): Int {
    val matches = findState.matches
    if (matches.isEmpty()) return 0
    val count = matches.size
    for (i in matches.indices.reversed()) {
      val m = matches[i]
      buffer.replace(m.start, m.length, replacement)
    }
    setFindQuery(findState.query, findState.isRegex, findState.caseSensitive, findState.wholeWord)
    onBufferMutatedInternal()
    return count
  }

  /** Navigates cursor to 1-based [line1Based] and optional 1-based [col1Based]. */
  fun goToLine(line1Based: Int, col1Based: Int = 1) {
    val docLine = (line1Based - 1).coerceIn(0, buffer.lineCount - 1)
    val col = (col1Based - 1).coerceAtLeast(0)
    // Expand fold if target line is hidden inside a collapsed region
    if (foldManager.visibleLineForDoc(docLine) != docLine) {
      foldManager.expandAll(buffer.lineCount)
    }
    val offset = buffer.positionToOffset(docLine, col)
    buffer.setCursor(offset)
    ensureCaretVisible()
    updateMetricsAndCursorInfo()
    invalidate()
  }

  fun toggleStickyCtrl() {
    stickyCtrl = !stickyCtrl
    onStickyModifiersChanged?.invoke(stickyCtrl, stickyAlt)
  }

  fun toggleStickyAlt() {
    stickyAlt = !stickyAlt
    onStickyModifiersChanged?.invoke(stickyCtrl, stickyAlt)
  }

  fun clearStickyModifiers() {
    if (stickyCtrl || stickyAlt) {
      stickyCtrl = false
      stickyAlt = false
      onStickyModifiersChanged?.invoke(false, false)
    }
  }

  /** Handles input from the on-screen Extra-Key Row. */
  fun sendExtraKey(symbol: String) {
    val t0 = SystemClock.elapsedRealtimeNanos()
    requestFocus()
    when (symbol) {
      "TAB" -> {
        if (buffer.selectionSet.selections.any { !it.isEmpty }) {
          buffer.indentSelectedLines(outdent = false, config.tabSize, config.insertSpaces)
        } else {
          val indent = if (config.insertSpaces) " ".repeat(config.tabSize) else "\t"
          insertCommittedText(indent)
        }
      }
      "ESC" -> {
        clearStickyModifiers()
        if (buffer.selectionSet.isMultiCursor) {
          buffer.setCursor(buffer.selectionSet.primary.active)
        } else {
          buffer.setCursor(buffer.selectionSet.primary.active)
        }
        showSingleCaretHandle = false
      }
      "LEFT" -> moveCaretsHorizontally(-1, shift = false)
      "RIGHT" -> moveCaretsHorizontally(1, shift = false)
      "UP" -> moveCaretsVertically(-1, shift = false)
      "DOWN" -> moveCaretsVertically(1, shift = false)
      else -> {
        if (stickyCtrl && symbol.length == 1) {
          handleCtrlShortcut(symbol[0].uppercaseChar(), shift = false)
          clearStickyModifiers()
        } else {
          insertCommittedText(symbol)
        }
      }
    }
    onBufferMutatedInternal(t0)
  }

  fun selectAll() {
    buffer.setSelection(0, buffer.length)
    updateMetricsAndCursorInfo()
    invalidate()
  }

  fun copySelection() {
    val text = buildCombinedSelectionText()
    if (text.isEmpty()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Quark", text))
  }

  fun cutSelection() {
    val text = buildCombinedSelectionText()
    if (text.isEmpty()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Quark", text))
    buffer.deleteBackwardAtCursors()
    onBufferMutatedInternal()
  }

  fun pasteFromClipboard() {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip ?: return
    if (clip.itemCount == 0) return
    val pasted = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
    if (pasted.isNotEmpty()) {
      buffer.insertTextAtCursors(pasted)
      onBufferMutatedInternal()
    }
  }

  private fun buildCombinedSelectionText(): String {
    val sels = buffer.selectionSet.selections
    if (sels.size == 1) {
      val s = sels[0]
      return if (s.isEmpty) "" else buffer.getText(s.start, s.end)
    }
    return sels.joinToString("\n") { if (it.isEmpty) "" else buffer.getText(it.start, it.end) }
  }

  fun insertCommittedText(str: String) {
    val t0 = SystemClock.elapsedRealtimeNanos()
    if (config.autoCloseBrackets && str.length == 1 && buffer.selectionSet.selections.all { it.isEmpty }) {
      val closePair =
        when (str[0]) {
          '(' -> ')'
          '[' -> ']'
          '{' -> '}'
          '"' -> '"'
          '\'' -> '\''
          else -> null
        }
      if (closePair != null) {
        buffer.insertTextAtCursors("$str$closePair")
        // Step back 1 inside the pair
        val stepped = buffer.selectionSet.selections.map { Selection.caret((it.active - 1).coerceAtLeast(0)) }
        buffer.setSelections(stepped)
        onBufferMutatedInternal(t0)
        return
      }
    }
    buffer.insertTextAtCursors(str)
    onBufferMutatedInternal(t0)
  }

  fun insertNewline() {
    val t0 = SystemClock.elapsedRealtimeNanos()
    buffer.insertNewlineWithAutoIndent(config.tabSize, config.insertSpaces)
    onBufferMutatedInternal(t0)
  }

  fun undo() {
    if (buffer.undo()) {
      onBufferMutatedInternal()
    }
  }

  fun redo() {
    if (buffer.redo()) {
      onBufferMutatedInternal()
    }
  }

  internal fun onBufferMutatedFromIme() {
    onBufferMutatedInternal(SystemClock.elapsedRealtimeNanos())
  }

  internal fun onSelectionMovedFromIme() {
    ensureCaretVisible()
    updateMetricsAndCursorInfo()
    invalidate()
  }

  private fun onBufferMutatedInternal(startNanos: Long = SystemClock.elapsedRealtimeNanos()) {
    recomputeGutterWidth()
    if (buffer.lineCount <= 10_000) {
      foldManager.recomputeIndentFolds(buffer)
    }
    resetCursorBlink()
    ensureCaretVisible()
    updateMetricsAndCursorInfo()
    invalidate()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val primary = buffer.selectionSet.primary
    val comp = buffer.composingRange
    imm?.updateSelection(
      this,
      primary.start,
      primary.end,
      comp?.start ?: -1,
      comp?.end ?: -1,
    )
    lastKeystrokeLatencyMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000f
  }

  /** Replaces [replaceStartOffset, replaceEndOffset) with an accepted IntelliSense completion. */
  fun applyCompletion(
    replaceStartOffset: Int,
    replaceEndOffset: Int,
    insertText: String,
    cursorBackstep: Int = 0,
  ) {
    val start = replaceStartOffset.coerceIn(0, buffer.length)
    val end = replaceEndOffset.coerceIn(start, buffer.length)
    buffer.replace(start, end - start, insertText)
    val nextCaret = (start + insertText.length - cursorBackstep).coerceIn(start, buffer.length)
    buffer.setCursor(nextCaret)
    onBufferMutatedInternal()
  }

  private fun updateMetricsAndCursorInfo() {
    val primary = buffer.selectionSet.primary
    val pos = buffer.offsetToPosition(primary.active)
    onCursorOrBufferChanged?.invoke(
      pos,
      buffer.selectionSet.selections.size,
      primary.length,
      buffer.isDirty,
    )
    notifyCaretViewportPosition(pos.line, pos.column)
  }

  private fun notifyCaretViewportPosition(docLine: Int, col: Int) {
    val cb = onCaretViewportPositionChanged ?: return
    val visLine = foldManager.visibleLineForDoc(docLine)
    val textStartX = gutterWidthPx + textPaddingLeftPx
    val lineStart = buffer.getLineStartOffset(docLine)
    val lineLen = buffer.getLineLength(docLine)
    ensureLineScratch(lineLen)
    if (lineLen > 0) buffer.copyChars(lineStart, lineLen, lineScratch, 0)
    val cx = textStartX + measureLinePrefix(lineScratch, 0, col.coerceIn(0, lineLen))
    val cy = paddingTop + visLine * lineHeightPx
    cb.invoke((cx - scrollX).coerceAtLeast(gutterWidthPx), (cy - scrollY).coerceAtLeast(0f), lineHeightPx)
  }

  private fun recomputeGutterWidth() {
    if (!config.showLineNumbers) {
      gutterWidthPx = 20f * density
      textPaddingLeftPx = 8f * density
      return
    }
    val digits = buffer.lineCount.toString().length.coerceAtLeast(3)
    val charW = gutterTextPaint.measureText("8")
    // Line number + fold chevron area
    gutterWidthPx = charW * digits + 28f * density
    textPaddingLeftPx = 10f * density
  }

  // --- View lifecycle & IME ---

  override fun onCheckIsTextEditor(): Boolean = true

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
    outAttrs.inputType =
      InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    outAttrs.imeOptions =
      EditorInfo.IME_FLAG_NO_EXTRACT_UI or
        EditorInfo.IME_FLAG_NO_FULLSCREEN or
        EditorInfo.IME_ACTION_NONE
    val primary = buffer.selectionSet.primary
    outAttrs.initialSelStart = primary.start
    outAttrs.initialSelEnd = primary.end
    return EditorInputConnection(this)
  }

  override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    if (gainFocus) {
      resetCursorBlink()
    } else {
      removeCallbacks(cursorBlinkRunnable)
      cursorVisible = false
      invalidate()
    }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    removeCallbacks(cursorBlinkRunnable)
    regexHighlighter.dispose()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w != oldw && config.wordWrap) {
      layoutCache.clear()
    }
  }

  private fun resetCursorBlink() {
    removeCallbacks(cursorBlinkRunnable)
    cursorVisible = true
    if (hasFocus()) {
      postDelayed(cursorBlinkRunnable, CURSOR_BLINK_MS)
    }
  }

  // --- Hardware Key Handling (VS Code Keybindings) ---

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    val t0 = SystemClock.elapsedRealtimeNanos()
    val ctrl = event.isCtrlPressed || stickyCtrl
    val shift = event.isShiftPressed
    val alt = event.isAltPressed || stickyAlt

    if (ctrl) {
      val handled =
        when (keyCode) {
          KeyEvent.KEYCODE_P -> {
            onActionRequested?.invoke(if (shift) EditorAction.COMMAND_PALETTE else EditorAction.QUICK_OPEN)
            true
          }
          KeyEvent.KEYCODE_S -> {
            onActionRequested?.invoke(EditorAction.SAVE)
            true
          }
          KeyEvent.KEYCODE_SLASH -> {
            buffer.toggleLineComment(activeGrammar?.lineCommentPrefix ?: "//")
            onBufferMutatedInternal(t0)
            true
          }
          KeyEvent.KEYCODE_D -> {
            if (buffer.addNextOccurrence()) {
              ensureCaretVisible()
              updateMetricsAndCursorInfo()
              invalidate()
            }
            true
          }
          KeyEvent.KEYCODE_B -> {
            onActionRequested?.invoke(EditorAction.TOGGLE_SIDEBAR)
            true
          }
          KeyEvent.KEYCODE_F -> {
            onActionRequested?.invoke(if (shift) EditorAction.GLOBAL_SEARCH else EditorAction.FIND)
            true
          }
          KeyEvent.KEYCODE_H -> {
            onActionRequested?.invoke(EditorAction.REPLACE)
            true
          }
          KeyEvent.KEYCODE_G -> {
            onActionRequested?.invoke(EditorAction.GO_TO_LINE)
            true
          }
          KeyEvent.KEYCODE_GRAVE -> {
            onActionRequested?.invoke(EditorAction.TOGGLE_TERMINAL)
            true
          }
          KeyEvent.KEYCODE_Z -> {
            if (shift) redo() else undo()
            true
          }
          KeyEvent.KEYCODE_Y -> {
            redo()
            true
          }
          KeyEvent.KEYCODE_A -> {
            selectAll()
            true
          }
          KeyEvent.KEYCODE_C -> {
            copySelection()
            true
          }
          KeyEvent.KEYCODE_X -> {
            cutSelection()
            true
          }
          KeyEvent.KEYCODE_V -> {
            pasteFromClipboard()
            true
          }
          KeyEvent.KEYCODE_SPACE -> {
            onActionRequested?.invoke(EditorAction.TRIGGER_COMPLETION)
            true
          }
          else -> false
        }
      if (handled) {
        clearStickyModifiers()
        return true
      }
    }

    if (alt) {
      when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> {
          buffer.moveSelectedLines(moveUp = true)
          onBufferMutatedInternal(t0)
          clearStickyModifiers()
          return true
        }
        KeyEvent.KEYCODE_DPAD_DOWN -> {
          buffer.moveSelectedLines(moveUp = false)
          onBufferMutatedInternal(t0)
          clearStickyModifiers()
          return true
        }
      }
    }

    if (isCompletionPopupOpen && (keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
        keyCode == KeyEvent.KEYCODE_TAB ||
        keyCode == KeyEvent.KEYCODE_ESCAPE)
    ) {
      if (onCompletionKeyIntercept?.invoke(keyCode) == true) {
        return true
      }
    }

    when (keyCode) {
      KeyEvent.KEYCODE_DEL -> {
        buffer.deleteBackwardAtCursors()
        onBufferMutatedInternal(t0)
        return true
      }
      KeyEvent.KEYCODE_FORWARD_DEL -> {
        buffer.deleteForwardAtCursors()
        onBufferMutatedInternal(t0)
        return true
      }
      KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
        insertNewline()
        return true
      }
      KeyEvent.KEYCODE_TAB -> {
        if (shift || buffer.selectionSet.selections.any { !it.isEmpty }) {
          buffer.indentSelectedLines(outdent = shift, config.tabSize, config.insertSpaces)
          onBufferMutatedInternal(t0)
        } else {
          val indent = if (config.insertSpaces) " ".repeat(config.tabSize) else "\t"
          insertCommittedText(indent)
        }
        return true
      }
      KeyEvent.KEYCODE_DPAD_LEFT -> {
        moveCaretsHorizontally(-1, shift)
        return true
      }
      KeyEvent.KEYCODE_DPAD_RIGHT -> {
        moveCaretsHorizontally(1, shift)
        return true
      }
      KeyEvent.KEYCODE_DPAD_UP -> {
        moveCaretsVertically(-1, shift)
        return true
      }
      KeyEvent.KEYCODE_DPAD_DOWN -> {
        moveCaretsVertically(1, shift)
        return true
      }
      KeyEvent.KEYCODE_MOVE_HOME -> {
        moveCaretsToLineBoundary(start = true, shift = shift)
        return true
      }
      KeyEvent.KEYCODE_MOVE_END -> {
        moveCaretsToLineBoundary(start = false, shift = shift)
        return true
      }
      KeyEvent.KEYCODE_PAGE_UP -> {
        val pageLines = (height / lineHeightPx).toInt().coerceAtLeast(1)
        moveCaretsVertically(-pageLines, shift)
        return true
      }
      KeyEvent.KEYCODE_PAGE_DOWN -> {
        val pageLines = (height / lineHeightPx).toInt().coerceAtLeast(1)
        moveCaretsVertically(pageLines, shift)
        return true
      }
      KeyEvent.KEYCODE_ESCAPE -> {
        clearStickyModifiers()
        if (buffer.selectionSet.isMultiCursor) {
          buffer.setCursor(buffer.selectionSet.primary.active)
          invalidate()
        }
        return true
      }
    }

    val uni = event.unicodeChar
    if (uni != 0 && !event.isCtrlPressed && !event.isAltPressed) {
      val ch = uni.toChar()
      if (!ch.isISOControl()) {
        insertCommittedText(ch.toString())
        return true
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  private fun handleCtrlShortcut(ch: Char, shift: Boolean): Boolean {
    val action =
      when (ch) {
        'P' -> if (shift) EditorAction.COMMAND_PALETTE else EditorAction.QUICK_OPEN
        'S' -> EditorAction.SAVE
        '/' -> {
          buffer.toggleLineComment(activeGrammar?.lineCommentPrefix ?: "//")
          onBufferMutatedInternal()
          return true
        }
        'D' -> {
          buffer.addNextOccurrence()
          ensureCaretVisible()
          updateMetricsAndCursorInfo()
          invalidate()
          return true
        }
        'B' -> EditorAction.TOGGLE_SIDEBAR
        'F' -> if (shift) EditorAction.GLOBAL_SEARCH else EditorAction.FIND
        'H' -> EditorAction.REPLACE
        'G' -> EditorAction.GO_TO_LINE
        '`' -> EditorAction.TOGGLE_TERMINAL
        'Z' -> {
          if (shift) redo() else undo()
          return true
        }
        'Y' -> {
          redo()
          return true
        }
        'A' -> {
          selectAll()
          return true
        }
        'C' -> {
          copySelection()
          return true
        }
        'X' -> {
          cutSelection()
          return true
        }
        'V' -> {
          pasteFromClipboard()
          return true
        }
        else -> null
      }
    if (action != null) {
      onActionRequested?.invoke(action)
      return true
    }
    return false
  }

  private fun moveCaretsHorizontally(delta: Int, shift: Boolean) {
    val moved =
      buffer.selectionSet.selections.map { sel ->
        val nextActive = (sel.active + delta).coerceIn(0, buffer.length)
        if (shift) Selection(sel.anchor, nextActive) else Selection.caret(nextActive)
      }
    buffer.setSelections(moved)
    resetCursorBlink()
    ensureCaretVisible()
    updateMetricsAndCursorInfo()
    invalidate()
  }

  private fun moveCaretsVertically(lineDelta: Int, shift: Boolean) {
    val moved =
      buffer.selectionSet.selections.map { sel ->
        val pos = buffer.offsetToPosition(sel.active)
        val targetDocLine = (pos.line + lineDelta).coerceIn(0, buffer.lineCount - 1)
        val nextActive = buffer.positionToOffset(targetDocLine, pos.column)
        if (shift) Selection(sel.anchor, nextActive) else Selection.caret(nextActive)
      }
    buffer.setSelections(moved)
    resetCursorBlink()
    ensureCaretVisible()
    updateMetricsAndCursorInfo()
    invalidate()
  }

  private fun moveCaretsToLineBoundary(start: Boolean, shift: Boolean) {
    val moved =
      buffer.selectionSet.selections.map { sel ->
        val line = buffer.getLineAtOffset(sel.active)
        val packed = buffer.getLineBoundsPacked(line)
        val lineStart = (packed ushr 32).toInt()
        val lineEnd = packed.toInt()
        val target = if (start) lineStart else lineEnd
        if (shift) Selection(sel.anchor, target) else Selection.caret(target)
      }
    buffer.setSelections(moved)
    resetCursorBlink()
    ensureCaretVisible()
    updateMetricsAndCursorInfo()
    invalidate()
  }

  // --- Rendering (Zero Allocation Hot Path) ---

  override fun onDraw(canvas: Canvas) {
    val drawStartNanos = SystemClock.elapsedRealtimeNanos()
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= 0f || h <= 0f) return

    // 1. Background
    canvas.drawRect(scrollX.toFloat(), scrollY.toFloat(), scrollX + w, scrollY + h, bgPaint)

    val textStartX = gutterWidthPx + textPaddingLeftPx
    val wrapWidthPx = if (config.wordWrap) (w - textStartX - 16f * density).toInt().coerceAtLeast(100) else 0
    val visibleDocCount = foldManager.getVisibleCount(buffer.lineCount)

    // Compute visible document line window
    val topY = scrollY.toFloat()
    val bottomY = scrollY + h
    var firstVisLine = floor((topY - paddingTop) / lineHeightPx).toInt().coerceAtLeast(0)
    if (config.wordWrap) {
      // With word wrap, estimate conservatively from top
      firstVisLine = (firstVisLine / 3).coerceAtLeast(0)
    }

    val primary = buffer.selectionSet.primary
    val primaryPos = buffer.offsetToPosition(primary.active)
    val bracketPair = buffer.findMatchingBracket(primary.active)
    val composing = buffer.composingRange

    // 2. Vertical Ruler
    if (config.rulerColumn > 0 && !config.wordWrap) {
      val rulerX = textStartX + config.rulerColumn * glyphCache.spaceWidth
      if (rulerX >= scrollX + gutterWidthPx && rulerX <= scrollX + w) {
        canvas.drawLine(rulerX, topY, rulerX, bottomY, rulerPaint)
      }
    }

    // Track handle anchor positions while drawing cursors/selections
    startHandleX = -1f; startHandleY = -1f
    endHandleX = -1f; endHandleY = -1f
    singleHandleX = -1f; singleHandleY = -1f

    var visualRowY = paddingTop.toFloat()
    if (!config.wordWrap) {
      visualRowY += firstVisLine * lineHeightPx
    } else {
      // Walk from line 0 quickly to reach firstVisLine when wrapped
      var curVisLine = 0
      while (curVisLine < visibleDocCount && visualRowY + lineHeightPx < topY) {
        val docLine = foldManager.docLineForVisible(curVisLine, buffer.lineCount)
        val subRows =
          layoutCache.getWrappedRanges(
            buffer, docLine, wrapWidthPx, config.tabSize, textPaint, glyphCache, lineScratch
          )
        val blockH = subRows.size * lineHeightPx
        if (visualRowY + blockH >= topY) {
          firstVisLine = curVisLine
          break
        }
        visualRowY += blockH
        curVisLine++
      }
      if (curVisLine >= visibleDocCount) firstVisLine = curVisLine
    }

    var drawnRows = 0
    var curVisLine = firstVisLine
    maxLineWidthPx = max(maxLineWidthPx, w)

    while (curVisLine < visibleDocCount && visualRowY < bottomY) {
      val docLine = foldManager.docLineForVisible(curVisLine, buffer.lineCount)
      val packedBounds = buffer.getLineBoundsPacked(docLine)
      val lineStartOffset = (packedBounds ushr 32).toInt()
      val lineEndOffset = packedBounds.toInt()
      val lineLen = (lineEndOffset - lineStartOffset).coerceAtLeast(0)
      ensureLineScratch(lineLen)
      if (lineLen > 0) {
        buffer.copyChars(lineStartOffset, lineLen, lineScratch, 0)
      }

      val wrappedRanges =
        if (config.wordWrap) {
          layoutCache.getWrappedRanges(
            buffer, docLine, wrapWidthPx, config.tabSize, textPaint, glyphCache, lineScratch
          )
        } else {
          null
        }
      val subRowCount = wrappedRanges?.size ?: 1
      val docLineHeight = subRowCount * lineHeightPx

      // Current line highlight
      if (config.highlightCurrentLine && docLine == primaryPos.line && primary.isEmpty) {
        canvas.drawRect(
          scrollX + gutterWidthPx,
          visualRowY,
          scrollX + w,
          visualRowY + docLineHeight,
          lineHighlightPaint,
        )
      }

      // Draw each visual sub-row
      for (subIdx in 0 until subRowCount) {
        val rowTop = visualRowY + subIdx * lineHeightPx
        val rowBottom = rowTop + lineHeightPx
        if (rowBottom < topY) continue
        if (rowTop > bottomY) break

        val subStartCol =
          if (wrappedRanges != null) LineLayoutCache.unpackStart(wrappedRanges[subIdx]) else 0
        val subEndCol =
          if (wrappedRanges != null) LineLayoutCache.unpackEnd(wrappedRanges[subIdx]) else lineLen
        val subStartOffset = lineStartOffset + subStartCol
        val subEndOffset = lineStartOffset + subEndCol

        // Indent guides (only on first sub-row)
        if (config.showIndentGuides && subIdx == 0 && lineLen > 0) {
          var leadingCols = 0
          for (i in 0 until lineLen) {
            val ch = lineScratch[i]
            if (ch == ' ') leadingCols++
            else if (ch == '\t') leadingCols += config.tabSize
            else break
          }
          var col = config.tabSize
          while (col <= leadingCols) {
            val gx = textStartX + col * glyphCache.spaceWidth
            if (gx >= scrollX + gutterWidthPx && gx <= scrollX + w) {
              canvas.drawLine(gx, rowTop, gx, rowBottom, guidePaint)
            }
            col += config.tabSize
          }
        }

        // Search match highlights
        drawRangeHighlights(
          canvas, findState.matches, findState.activeIndex,
          subStartOffset, subEndOffset, lineStartOffset, subStartCol, textStartX, rowTop, rowBottom
        )

        // Selection highlights across all cursors
        for (sIdx in 0 until buffer.selectionSet.selections.size) {
          val sel = buffer.selectionSet.selections[sIdx]
          if (sel.isEmpty) continue
          val ovStart = max(sel.start, subStartOffset)
          val ovEnd = min(sel.end, subEndOffset)
          if (ovStart <= ovEnd && (ovStart < ovEnd || (sel.end > lineEndOffset && subIdx == subRowCount - 1))) {
            val x1 = textStartX + measureLinePrefix(lineScratch, subStartCol, ovStart - lineStartOffset)
            var x2 = textStartX + measureLinePrefix(lineScratch, subStartCol, ovEnd - lineStartOffset)
            if (sel.end > lineEndOffset && subIdx == subRowCount - 1) {
              x2 += glyphCache.spaceWidth * 0.75f
            }
            canvas.drawRect(x1, rowTop, x2, rowBottom, selectionPaint)
            if (sIdx == buffer.selectionSet.primaryIndex) {
              if (sel.start in subStartOffset..subEndOffset) {
                startHandleX = x1
                startHandleY = rowBottom
              }
              if (sel.end in subStartOffset..subEndOffset) {
                endHandleX = x2
                endHandleY = rowBottom
              }
            }
          }
        }

        // Bracket match highlights
        if (bracketPair != null) {
          val b1 = bracketPair.first
          val b2 = bracketPair.second
          if (b1 in subStartOffset until subEndOffset) {
            drawCharBox(canvas, lineScratch, subStartCol, b1 - lineStartOffset, textStartX, rowTop, rowBottom)
          }
          if (b2 in subStartOffset until subEndOffset) {
            drawCharBox(canvas, lineScratch, subStartCol, b2 - lineStartOffset, textStartX, rowTop, rowBottom)
          }
        }

        // Draw Text (syntax-highlighted spans or fast whole-row draw)
        val tokens = spanProvider?.getLineTokens(docLine)
        drawTextSlice(
          canvas,
          lineScratch,
          subStartCol,
          subEndCol,
          tokens,
          textStartX,
          rowTop + baselineOffsetPx,
        )

        // Fold badge on collapsed line end
        if (subIdx == subRowCount - 1 && foldManager.isCollapsed(docLine)) {
          val endX = textStartX + measureLinePrefix(lineScratch, subStartCol, subEndCol) + 6f * density
          rectScratch.set(endX, rowTop + 4f * density, endX + 26f * density, rowBottom - 4f * density)
          canvas.drawRoundRect(rectScratch, 4f * density, 4f * density, foldBadgePaint)
          canvas.drawText("...", endX + 5f * density, rowTop + baselineOffsetPx - 1f * density, foldBadgeTextPaint)
        }

        // IME composing underline
        if (composing != null && !composing.isEmpty) {
          val cs = max(composing.start, subStartOffset)
          val ce = min(composing.end, subEndOffset)
          if (cs < ce) {
            val x1 = textStartX + measureLinePrefix(lineScratch, subStartCol, cs - lineStartOffset)
            val x2 = textStartX + measureLinePrefix(lineScratch, subStartCol, ce - lineStartOffset)
            canvas.drawLine(x1, rowBottom - 2f * density, x2, rowBottom - 2f * density, composingPaint)
          }
        }

        // Diagnostic squiggles
        val lineDiags = diagnosticsByLine[docLine]
        if (lineDiags != null) {
          for (dIdx in 0 until lineDiags.size) {
            val d = lineDiags[dIdx]
            val ds = max(d.startCol, subStartCol)
            val de = min(d.endCol.coerceAtLeast(d.startCol + 1), subEndCol.coerceAtLeast(ds + 1))
            if (ds < de) {
              val x1 = textStartX + measureLinePrefix(lineScratch, subStartCol, ds)
              val x2 = textStartX + measureLinePrefix(lineScratch, subStartCol, de)
              squigglePaint.color =
                when (d.severity) {
                  DiagnosticSeverity.ERROR -> palette.errorSquiggle
                  DiagnosticSeverity.WARNING -> palette.warningSquiggle
                  else -> palette.infoSquiggle
                }
              drawSquiggle(canvas, x1, x2, rowBottom - 2f * density)
            }
          }
        }

        // Cursors
        for (sIdx in 0 until buffer.selectionSet.selections.size) {
          val sel = buffer.selectionSet.selections[sIdx]
          if (sel.active in subStartOffset..subEndOffset) {
            // Avoid double-drawing on wrap seam
            val isWrapSeam =
              wrappedRanges != null &&
                subIdx < subRowCount - 1 &&
                sel.active == subEndOffset
            if (!isWrapSeam) {
              val cx = textStartX + measureLinePrefix(lineScratch, subStartCol, sel.active - lineStartOffset)
              if (cursorVisible) {
                canvas.drawLine(cx, rowTop + 2f * density, cx, rowBottom - 2f * density, cursorPaint)
              }
              if (sIdx == buffer.selectionSet.primaryIndex && sel.isEmpty) {
                singleHandleX = cx
                singleHandleY = rowBottom
              }
            }
          }
        }

        drawnRows++
      }

      if (!config.wordWrap) {
        val rowW = textStartX + measureLinePrefix(lineScratch, 0, lineLen) + 64f * density
        if (rowW > maxLineWidthPx) maxLineWidthPx = rowW
      }

      visualRowY += docLineHeight
      curVisLine++
    }

    // 3. Draw Gutter overlay (pinned to horizontal scrollX!)
    val gxLeft = scrollX.toFloat()
    val gxRight = gxLeft + gutterWidthPx
    canvas.drawRect(gxLeft, topY, gxRight, bottomY, gutterBgPaint)
    canvas.drawLine(gxRight, topY, gxRight, bottomY, gutterDividerPaint)

    var gRowY = paddingTop.toFloat()
    if (!config.wordWrap) {
      gRowY += firstVisLine * lineHeightPx
    } else {
      // Re-align using firstVisLine top from earlier loop:
      var walk = 0
      gRowY = paddingTop.toFloat()
      while (walk < firstVisLine && walk < visibleDocCount) {
        val dl = foldManager.docLineForVisible(walk, buffer.lineCount)
        val sr = layoutCache.getWrappedRanges(buffer, dl, wrapWidthPx, config.tabSize, textPaint, glyphCache, lineScratch)
        gRowY += sr.size * lineHeightPx
        walk++
      }
    }

    var gVis = firstVisLine
    while (gVis < visibleDocCount && gRowY < bottomY) {
      val docLine = foldManager.docLineForVisible(gVis, buffer.lineCount)
      val subRows =
        if (config.wordWrap) {
          layoutCache.getWrappedRanges(buffer, docLine, wrapWidthPx, config.tabSize, textPaint, glyphCache, lineScratch).size
        } else {
          1
        }
      if (config.showLineNumbers) {
        gutterTextPaint.color =
          if (docLine == primaryPos.line) palette.gutterActiveForeground else palette.gutterForeground
        val label = (docLine + 1).toString()
        val labelW = gutterTextPaint.measureText(label)
        canvas.drawText(
          label,
          gxRight - 18f * density - labelW,
          gRowY + baselineOffsetPx,
          gutterTextPaint,
        )
      }
      val foldReg = foldManager.regionAt(docLine)
      if (foldReg != null) {
        val cx = gxRight - 9f * density
        val cy = gRowY + lineHeightPx * 0.5f
        val r = 3.2f * density
        gutterTextPaint.color = palette.gutterActiveForeground
        if (foldReg.isCollapsed) {
          // Right-pointing chevron '>'
          canvas.drawLine(cx - r * 0.5f, cy - r, cx + r * 0.7f, cy, gutterTextPaint)
          canvas.drawLine(cx + r * 0.7f, cy, cx - r * 0.5f, cy + r, gutterTextPaint)
        } else {
          // Downward chevron 'v'
          canvas.drawLine(cx - r, cy - r * 0.4f, cx, cy + r * 0.6f, gutterTextPaint)
          canvas.drawLine(cx, cy + r * 0.6f, cx + r, cy - r * 0.4f, gutterTextPaint)
        }
      }
      if (docLine in gitAddedLines) {
        diffGutterPaint.color = 0xFF3FB950.toInt()
        canvas.drawRect(gxRight - 3.5f * density, gRowY + 1f, gxRight - 0.5f * density, gRowY + subRows * lineHeightPx - 1f, diffGutterPaint)
      } else if (docLine in gitModifiedLines) {
        diffGutterPaint.color = 0xFFD29922.toInt()
        canvas.drawRect(gxRight - 3.5f * density, gRowY + 1f, gxRight - 0.5f * density, gRowY + subRows * lineHeightPx - 1f, diffGutterPaint)
      } else if (docLine in gitDeletedLines) {
        diffGutterPaint.color = 0xFFF85149.toInt()
        canvas.drawRect(gxRight - 4.5f * density, gRowY + subRows * lineHeightPx - 3f * density, gxRight - 0.5f * density, gRowY + subRows * lineHeightPx, diffGutterPaint)
      }
      gRowY += subRows * lineHeightPx
      gVis++
    }

    // 4. Selection Teardrop Handles
    if (!primary.isEmpty) {
      if (startHandleX >= 0f) drawTeardropHandle(canvas, startHandleX, startHandleY, isStart = true)
      if (endHandleX >= 0f) drawTeardropHandle(canvas, endHandleX, endHandleY, isStart = false)
    } else if (showSingleCaretHandle && singleHandleX >= 0f && hasFocus()) {
      drawCenterHandle(canvas, singleHandleX, singleHandleY)
    }

    val drawTimeMs = (SystemClock.elapsedRealtimeNanos() - drawStartNanos) / 1_000_000f
    onMetricsUpdated?.invoke(EditorMetrics(lastKeystrokeLatencyMs, drawTimeMs, drawnRows))
  }

  private fun ensureLineScratch(needed: Int) {
    if (needed > lineScratch.size) {
      var cap = lineScratch.size
      while (cap < needed) cap *= 2
      lineScratch = CharArray(cap)
    }
  }

  private fun measureLinePrefix(chars: CharArray, fromCol: Int, toCol: Int): Float {
    val safeEnd = toCol.coerceIn(fromCol, lineScratch.size)
    return glyphCache.measureSlice(chars, fromCol, safeEnd, textPaint, config.tabSize)
  }

  private fun drawTextSlice(
    canvas: Canvas,
    chars: CharArray,
    startCol: Int,
    endCol: Int,
    tokens: List<LineToken>?,
    startX: Float,
    baselineY: Float,
  ) {
    if (startCol >= endCol) return
    if (tokens.isNullOrEmpty()) {
      textPaint.color = palette.foreground
      drawRunWithTabs(canvas, chars, startCol, endCol, startX, baselineY, textPaint)
      return
    }
    var curCol = startCol
    var x = startX
    for (i in 0 until tokens.size) {
      val tok = tokens[i]
      if (tok.endCol <= startCol) continue
      if (tok.startCol >= endCol) break
      val gapEnd = min(tok.startCol, endCol)
      if (curCol < gapEnd) {
        textPaint.color = palette.foreground
        x = drawRunWithTabs(canvas, chars, curCol, gapEnd, x, baselineY, textPaint)
        curCol = gapEnd
      }
      val spanStart = max(curCol, tok.startCol)
      val spanEnd = min(tok.endCol, endCol)
      if (spanStart < spanEnd) {
        textPaint.color = palette.colorFor(tok.type)
        x = drawRunWithTabs(canvas, chars, spanStart, spanEnd, x, baselineY, textPaint)
        curCol = spanEnd
      }
    }
    if (curCol < endCol) {
      textPaint.color = palette.foreground
      drawRunWithTabs(canvas, chars, curCol, endCol, x, baselineY, textPaint)
    }
  }

  private fun drawRunWithTabs(
    canvas: Canvas,
    chars: CharArray,
    start: Int,
    end: Int,
    startX: Float,
    baselineY: Float,
    paint: Paint,
  ): Float {
    var x = startX
    var segStart = start
    for (i in start until end) {
      if (chars[i] == '\t') {
        if (i > segStart) {
          canvas.drawText(chars, segStart, i - segStart, x, baselineY, paint)
          x += glyphCache.measureSlice(chars, segStart, i, paint, config.tabSize)
        }
        x += glyphCache.spaceWidth * config.tabSize
        segStart = i + 1
      }
    }
    if (segStart < end) {
      canvas.drawText(chars, segStart, end - segStart, x, baselineY, paint)
      x += glyphCache.measureSlice(chars, segStart, end, paint, config.tabSize)
    }
    return x
  }

  private fun drawRangeHighlights(
    canvas: Canvas,
    matches: List<TextRange>,
    activeIdx: Int,
    subStartOffset: Int,
    subEndOffset: Int,
    lineStartOffset: Int,
    subStartCol: Int,
    textStartX: Float,
    rowTop: Float,
    rowBottom: Float,
  ) {
    if (matches.isEmpty()) return
    // Binary search first match ending after subStartOffset
    var lo = 0
    var hi = matches.size
    while (lo < hi) {
      val mid = (lo + hi) ushr 1
      if (matches[mid].end <= subStartOffset) lo = mid + 1 else hi = mid
    }
    var i = lo
    while (i < matches.size) {
      val m = matches[i]
      if (m.start >= subEndOffset) break
      val ovStart = max(m.start, subStartOffset)
      val ovEnd = min(m.end, subEndOffset)
      if (ovStart < ovEnd) {
        val x1 = textStartX + measureLinePrefix(lineScratch, subStartCol, ovStart - lineStartOffset)
        val x2 = textStartX + measureLinePrefix(lineScratch, subStartCol, ovEnd - lineStartOffset)
        canvas.drawRect(x1, rowTop, x2, rowBottom, if (i == activeIdx) searchActivePaint else searchMatchPaint)
      }
      i++
    }
  }

  private fun drawCharBox(
    canvas: Canvas,
    chars: CharArray,
    subStartCol: Int,
    col: Int,
    textStartX: Float,
    rowTop: Float,
    rowBottom: Float,
  ) {
    val x1 = textStartX + measureLinePrefix(chars, subStartCol, col)
    val ch = if (col in chars.indices) chars[col] else ' '
    val x2 = x1 + glyphCache.widthOf(ch, textPaint, config.tabSize)
    canvas.drawRect(x1, rowTop, x2, rowBottom, bracketBgPaint)
    canvas.drawRect(x1, rowTop, x2, rowBottom, bracketBorderPaint)
  }

  private fun drawSquiggle(canvas: Canvas, startX: Float, endX: Float, y: Float) {
    val step = 4f * density
    val amp = 1.6f * density
    squigglePath.reset()
    squigglePath.moveTo(startX, y)
    var x = startX
    var up = true
    while (x < endX) {
      val nextX = min(x + step, endX)
      squigglePath.lineTo(nextX, if (up) y - amp else y + amp)
      up = !up
      x = nextX
    }
    canvas.drawPath(squigglePath, squigglePaint)
  }

  private fun drawTeardropHandle(canvas: Canvas, x: Float, y: Float, isStart: Boolean) {
    val r = 9f * density
    val cx = if (isStart) x - r else x + r
    val cy = y + r * 1.2f
    handlePath.reset()
    handlePath.addCircle(cx, cy, r, Path.Direction.CW)
    canvas.drawPath(handlePath, handlePaint)
    // Connecting corner stem
    if (isStart) {
      canvas.drawRect(x - r, y, x, y + r * 1.2f, handlePaint)
    } else {
      canvas.drawRect(x, y, x + r, y + r * 1.2f, handlePaint)
    }
  }

  private fun drawCenterHandle(canvas: Canvas, x: Float, y: Float) {
    val r = 9f * density
    canvas.drawCircle(x, y + r * 1.3f, r, handlePaint)
  }

  // --- Coordinate Mapping & Scrolling ---

  private fun hitTestOffset(rawX: Float, rawY: Float): Int {
    val docX = (rawX + scrollX - (gutterWidthPx + textPaddingLeftPx)).coerceAtLeast(0f)
    val docY = (rawY + scrollY - paddingTop).coerceAtLeast(0f)
    val visibleCount = foldManager.getVisibleCount(buffer.lineCount).coerceAtLeast(1)

    var targetDocLine = 0
    var subStartCol = 0
    var subEndCol = 0

    if (!config.wordWrap) {
      val visLine = (docY / lineHeightPx).toInt().coerceIn(0, visibleCount - 1)
      targetDocLine = foldManager.docLineForVisible(visLine, buffer.lineCount)
      val packed = buffer.getLineBoundsPacked(targetDocLine)
      subEndCol = packed.toInt() - (packed ushr 32).toInt()
    } else {
      val wrapW = (width - gutterWidthPx - textPaddingLeftPx - 16f * density).toInt().coerceAtLeast(100)
      var accY = 0f
      var found = false
      for (v in 0 until visibleCount) {
        val dl = foldManager.docLineForVisible(v, buffer.lineCount)
        val ranges = layoutCache.getWrappedRanges(buffer, dl, wrapW, config.tabSize, textPaint, glyphCache, lineScratch)
        for (rIdx in ranges.indices) {
          if (docY < accY + lineHeightPx || (v == visibleCount - 1 && rIdx == ranges.lastIndex)) {
            targetDocLine = dl
            subStartCol = LineLayoutCache.unpackStart(ranges[rIdx])
            subEndCol = LineLayoutCache.unpackEnd(ranges[rIdx])
            found = true
            break
          }
          accY += lineHeightPx
        }
        if (found) break
      }
    }

    val lineStart = buffer.getLineStartOffset(targetDocLine)
    val lineLen = buffer.getLineLength(targetDocLine)
    ensureLineScratch(lineLen)
    if (lineLen > 0) buffer.copyChars(lineStart, lineLen, lineScratch, 0)

    var accX = 0f
    var col = subStartCol
    while (col < subEndCol) {
      val ch = lineScratch[col]
      val cw = glyphCache.widthOf(ch, textPaint, config.tabSize)
      if (accX + cw * 0.5f >= docX) break
      accX += cw
      col++
    }
    return (lineStart + col).coerceIn(0, buffer.length)
  }

  private fun hitTestDocLine(rawY: Float): Int {
    val docY = (rawY + scrollY - paddingTop).coerceAtLeast(0f)
    val visibleCount = foldManager.getVisibleCount(buffer.lineCount).coerceAtLeast(1)
    if (!config.wordWrap) {
      val vis = (docY / lineHeightPx).toInt().coerceIn(0, visibleCount - 1)
      return foldManager.docLineForVisible(vis, buffer.lineCount)
    }
    val wrapW = (width - gutterWidthPx - textPaddingLeftPx - 16f * density).toInt().coerceAtLeast(100)
    var accY = 0f
    for (v in 0 until visibleCount) {
      val dl = foldManager.docLineForVisible(v, buffer.lineCount)
      val ranges = layoutCache.getWrappedRanges(buffer, dl, wrapW, config.tabSize, textPaint, glyphCache, lineScratch)
      val h = ranges.size * lineHeightPx
      if (docY < accY + h) return dl
      accY += h
    }
    return buffer.lineCount - 1
  }

  /** Ensures primary caret is comfortably inside the visible viewport. */
  fun ensureCaretVisible() {
    if (width <= 0 || height <= 0) return
    val primary = buffer.selectionSet.primary
    val pos = buffer.offsetToPosition(primary.active)
    val visLine = foldManager.visibleLineForDoc(pos.line)
    val caretY = paddingTop + visLine * lineHeightPx
    var targetScrollY = scrollY
    val marginY = lineHeightPx * 1.5f
    if (caretY < scrollY + marginY) {
      targetScrollY = (caretY - marginY).toInt().coerceAtLeast(0)
    } else if (caretY + lineHeightPx > scrollY + height - marginY) {
      targetScrollY = (caretY + lineHeightPx - height + marginY).toInt().coerceAtLeast(0)
    }

    var targetScrollX = scrollX
    if (!config.wordWrap) {
      val lineStart = buffer.getLineStartOffset(pos.line)
      val lineLen = buffer.getLineLength(pos.line)
      ensureLineScratch(lineLen)
      if (lineLen > 0) buffer.copyChars(lineStart, lineLen, lineScratch, 0)
      val caretX = gutterWidthPx + textPaddingLeftPx + measureLinePrefix(lineScratch, 0, pos.column)
      val visibleTextWidth = (width - gutterWidthPx - textPaddingLeftPx).coerceAtLeast(60f)
      val marginX = 32f * density
      if (caretX - gutterWidthPx < scrollX + marginX) {
        targetScrollX = (caretX - gutterWidthPx - marginX).toInt().coerceAtLeast(0)
      } else if (caretX > scrollX + width - marginX) {
        targetScrollX = (caretX - width + marginX).toInt().coerceAtLeast(0)
      }
      if (visibleTextWidth <= 60f) targetScrollX = 0
    } else {
      targetScrollX = 0
    }

    scrollTo(targetScrollX, clampScrollY(targetScrollY))
  }

  private fun maxScrollY(): Int {
    val visCount = foldManager.getVisibleCount(buffer.lineCount)
    val totalH = (visCount * lineHeightPx + paddingTop + height * 0.4f).toInt()
    return (totalH - height).coerceAtLeast(0)
  }

  private fun maxScrollX(): Int {
    if (config.wordWrap) return 0
    return (maxLineWidthPx - width).toInt().coerceAtLeast(0)
  }

  private fun clampScrollY(y: Int): Int = y.coerceIn(0, maxScrollY())
  private fun clampScrollX(x: Int): Int = x.coerceIn(0, maxScrollX())

  override fun computeScroll() {
    if (scroller.computeScrollOffset()) {
      scrollTo(clampScrollX(scroller.currX), clampScrollY(scroller.currY))
      postInvalidateOnAnimation()
    }
  }

  // --- Touch & Handle Dragging ---

  override fun onTouchEvent(event: MotionEvent): Boolean {
    scaleDetector.onTouchEvent(event)
    if (isScaling) return true

    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        if (!scroller.isFinished) scroller.abortAnimation()
        draggingHandle = hitTestHandle(event.x + scrollX, event.y + scrollY)
        if (draggingHandle != DragHandleTarget.NONE) {
          parent?.requestDisallowInterceptTouchEvent(true)
          return true
        }
      }
      MotionEvent.ACTION_MOVE -> {
        if (draggingHandle != DragHandleTarget.NONE) {
          val offset = hitTestOffset(event.x, event.y - lineHeightPx * 0.6f)
          val primary = buffer.selectionSet.primary
          when (draggingHandle) {
            DragHandleTarget.CARET -> buffer.setCursor(offset)
            DragHandleTarget.SELECTION_START -> buffer.setSelection(primary.end, offset)
            DragHandleTarget.SELECTION_END -> buffer.setSelection(primary.start, offset)
            DragHandleTarget.NONE -> {}
          }
          // Edge auto-scroll during handle drag
          val edge = 48f * density
          if (event.y < edge) scrollBy(0, (-12f * density).toInt())
          else if (event.y > height - edge) scrollBy(0, (12f * density).toInt())
          scrollTo(clampScrollX(scrollX), clampScrollY(scrollY))
          updateMetricsAndCursorInfo()
          invalidate()
          return true
        }
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        if (draggingHandle != DragHandleTarget.NONE) {
          draggingHandle = DragHandleTarget.NONE
          parent?.requestDisallowInterceptTouchEvent(false)
          if (!buffer.selectionSet.primary.isEmpty) {
            onSelectionContextMenuRequested?.invoke(true)
          }
          return true
        }
      }
    }

    return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
  }

  override fun onGenericMotionEvent(event: MotionEvent): Boolean {
    if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)) {
      val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
      val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
      val stepY = (-vScroll * lineHeightPx * 3f).toInt()
      val stepX = (hScroll * glyphCache.spaceWidth * 6f).toInt()
      if (stepY != 0 || stepX != 0) {
        scrollTo(clampScrollX(scrollX + stepX), clampScrollY(scrollY + stepY))
        return true
      }
    }
    return super.onGenericMotionEvent(event)
  }

  override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): android.view.PointerIcon? {
    val x = event.getX(pointerIndex)
    val iconType =
      if (x <= gutterWidthPx) android.view.PointerIcon.TYPE_ARROW
      else android.view.PointerIcon.TYPE_TEXT
    return android.view.PointerIcon.getSystemIcon(context, iconType)
  }

  private fun hitTestHandle(docX: Float, docY: Float): DragHandleTarget {
    val hitRadius = 24f * density
    val primary = buffer.selectionSet.primary
    if (!primary.isEmpty) {
      if (startHandleX >= 0f && hypot(docX - startHandleX, docY - (startHandleY + 12f * density)) <= hitRadius) {
        return DragHandleTarget.SELECTION_START
      }
      if (endHandleX >= 0f && hypot(docX - endHandleX, docY - (endHandleY + 12f * density)) <= hitRadius) {
        return DragHandleTarget.SELECTION_END
      }
    } else if (showSingleCaretHandle && singleHandleX >= 0f) {
      if (hypot(docX - singleHandleX, docY - (singleHandleY + 12f * density)) <= hitRadius) {
        return DragHandleTarget.CARET
      }
    }
    return DragHandleTarget.NONE
  }

  private inner class EditorGestureListener : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(e: MotionEvent): Boolean = true

    override fun onSingleTapUp(e: MotionEvent): Boolean {
      requestFocus()
      // Check if tap was in the gutter (fold chevron toggle)
      if (e.x <= gutterWidthPx) {
        val docLine = hitTestDocLine(e.y)
        if (foldManager.toggleFoldAt(docLine, buffer.lineCount)) {
          invalidate()
          return true
        }
      }

      val offset = hitTestOffset(e.x, e.y)
      if (stickyAlt) {
        buffer.addCursor(offset)
        clearStickyModifiers()
      } else {
        buffer.setCursor(offset)
      }
      showSingleCaretHandle = true
      onSelectionContextMenuRequested?.invoke(false)
      resetCursorBlink()
      updateMetricsAndCursorInfo()
      invalidate()

      val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
      imm?.showSoftInput(this@QuarkEditorView, InputMethodManager.SHOW_IMPLICIT)
      return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
      requestFocus()
      val offset = hitTestOffset(e.x, e.y)
      val word = buffer.getWordRangeAt(offset)
      buffer.setSelection(word.start, word.end)
      showSingleCaretHandle = false
      onSelectionContextMenuRequested?.invoke(!word.isEmpty)
      updateMetricsAndCursorInfo()
      invalidate()
      return true
    }

    override fun onLongPress(e: MotionEvent) {
      requestFocus()
      val offset = hitTestOffset(e.x, e.y)
      val word = buffer.getWordRangeAt(offset)
      buffer.setSelection(word.start, word.end)
      showSingleCaretHandle = false
      onSelectionContextMenuRequested?.invoke(!word.isEmpty)
      updateMetricsAndCursorInfo()
      invalidate()
    }

    override fun onScroll(
      e1: MotionEvent?,
      e2: MotionEvent,
      distanceX: Float,
      distanceY: Float,
    ): Boolean {
      val nextX = clampScrollX(scrollX + distanceX.toInt())
      val nextY = clampScrollY(scrollY + distanceY.toInt())
      scrollTo(nextX, nextY)
      return true
    }

    override fun onFling(
      e1: MotionEvent?,
      e2: MotionEvent,
      velocityX: Float,
      velocityY: Float,
    ): Boolean {
      scroller.fling(
        scrollX,
        scrollY,
        -velocityX.toInt(),
        -velocityY.toInt(),
        0,
        maxScrollX(),
        0,
        maxScrollY(),
      )
      postInvalidateOnAnimation()
      return true
    }
  }

  private inner class EditorScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
      isScaling = true
      return true
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
      val newSize = (config.fontSizeSp * detector.scaleFactor).coerceIn(10f, 32f)
      if (abs(newSize - config.fontSizeSp) >= 0.2f) {
        applyConfig(config.copy(fontSizeSp = newSize))
        onFontSizePinched?.invoke(newSize)
      }
      return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
      isScaling = false
    }
  }

  companion object {
    private const val CURSOR_BLINK_MS = 530L

    fun defaultDarkPalette(): EditorColorPalette =
      EditorColorPalette(
        background = 0xFF282C34.toInt(),
        foreground = 0xFFABB2BF.toInt(),
        gutterBackground = 0xFF21252B.toInt(),
        gutterForeground = 0xFF4B5263.toInt(),
        gutterActiveForeground = 0xFFABB2BF.toInt(),
        gutterDivider = 0xFF181A1F.toInt(),
        currentLineHighlight = 0x18FFFFFF,
        selectionBackground = 0x553E4451,
        cursorColor = 0xFF528BFF.toInt(),
        indentGuideColor = 0xFF3B4048.toInt(),
        rulerColor = 0x443B4048,
        bracketMatchBackground = 0x33528BFF,
        bracketMatchBorder = 0xFF528BFF.toInt(),
        searchMatchBackground = 0x44E5C07B,
        searchActiveMatchBackground = 0x88E5C07B.toInt(),
        composingUnderlineColor = 0xFF56B6C2.toInt(),
        foldBadgeBackground = 0xFF3B4048.toInt(),
        foldBadgeForeground = 0xFFABB2BF.toInt(),
        errorSquiggle = 0xFFE06C75.toInt(),
        warningSquiggle = 0xFFE5C07B.toInt(),
        infoSquiggle = 0xFF61AFEF.toInt(),
        handleColor = 0xFF528BFF.toInt(),
        tokenColors = mapOf(
          TokenType.KEYWORD to 0xFFC678DD.toInt(),
          TokenType.TYPE to 0xFFE5C07B.toInt(),
          TokenType.FUNCTION to 0xFF61AFEF.toInt(),
          TokenType.VARIABLE to 0xFFE06C75.toInt(),
          TokenType.STRING to 0xFF98C379.toInt(),
          TokenType.NUMBER to 0xFFD19A66.toInt(),
          TokenType.COMMENT to 0xFF5C6370.toInt(),
          TokenType.OPERATOR to 0xFF56B6C2.toInt(),
          TokenType.PUNCTUATION to 0xFFABB2BF.toInt(),
          TokenType.ANNOTATION to 0xFFE5C07B.toInt(),
          TokenType.CONSTANT to 0xFFD19A66.toInt(),
          TokenType.TAG to 0xFFE06C75.toInt(),
          TokenType.ATTRIBUTE to 0xFFD19A66.toInt(),
        ),
      )
  }
}
