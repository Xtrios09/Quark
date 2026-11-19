package com.quark.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Color palette applied to [QuarkTerminalView].
 */
data class TerminalViewPalette(
  val background: Int,
  val foreground: Int,
  val cursorColor: Int,
  val ansi16: IntArray,
)

/**
 * Custom hardware-accelerated terminal emulator [View] written from scratch.
 *
 * Performs zero object allocations inside [onDraw] by batching contiguous same-styled runs
 * from [TerminalBuffer] using pre-allocated [Paint] objects, fixed monospace cell geometry,
 * and a static xterm-256 LUT.
 */
class QuarkTerminalView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

  var session: TerminalSession? = null
    set(value) {
      field?.onDirty = null
      field = value
      value?.onDirty = {
        snapToBottomIfFollowing()
        postInvalidate()
      }
      recalculateGridAndNotify()
      scrollToBottom()
      invalidate()
    }

  private var palette = TerminalViewPalette(
    background = 0xFF1E2227.toInt(),
    foreground = 0xFFABB2BF.toInt(),
    cursorColor = 0xFF528BFF.toInt(),
    ansi16 = intArrayOf(
      0xFF282C34.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
      0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFABB2BF.toInt(),
      0xFF5C6370.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
      0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFFFFFFF.toInt(),
    ),
  )

  private var fontSizeSp = 13f
  private var charWidthPx = 20f
  private var rowHeightPx = 38f
  private var baselineOffsetPx = 28f
  private val density = resources.displayMetrics.density
  private val padX = 8f * density
  private val padY = 6f * density

  // Scrollback viewport tracking (0 = top of scrollback)
  private var viewportTopRow = 0
  private var followBottom = true

  // Zero-alloc Paints
  private val bgPaint = Paint()
  private val cellBgPaint = Paint()
  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val cursorPaint = Paint()
  private val underlinePaint = Paint()

  private var cursorVisible = true
  private val cursorBlinkRunnable = object : Runnable {
    override fun run() {
      if (hasFocus()) {
        cursorVisible = !cursorVisible
        invalidate()
        postDelayed(this, 530L)
      }
    }
  }

  private val scroller = OverScroller(context)
  private val gestureDetector = GestureDetector(context, TerminalGestureListener())
  private val scaleDetector = ScaleGestureDetector(context, TerminalScaleListener())
  private var isScaling = false

  init {
    isFocusable = true
    isFocusableInTouchMode = true
    applyPalette(palette)
    setTerminalTypeface(Typeface.MONOSPACE)
  }

  fun applyPalette(newPalette: TerminalViewPalette) {
    palette = newPalette
    bgPaint.color = newPalette.background
    cursorPaint.color = newPalette.cursorColor
    cursorPaint.alpha = 185
    invalidate()
  }

  fun setTerminalTypeface(tf: Typeface) {
    textPaint.typeface = tf
    updateMetrics()
  }

  fun setFontSizeSp(sp: Float) {
    val clamped = sp.coerceIn(9f, 24f)
    if (abs(clamped - fontSizeSp) >= 0.2f) {
      fontSizeSp = clamped
      updateMetrics()
    }
  }

  private fun updateMetrics() {
    textPaint.textSize = android.util.TypedValue.applyDimension(
      android.util.TypedValue.COMPLEX_UNIT_SP,
      fontSizeSp,
      resources.displayMetrics,
    )
    charWidthPx = max(6f, textPaint.measureText("M"))
    val fm = textPaint.fontMetrics
    val raw = fm.descent - fm.ascent
    rowHeightPx = ceil((raw * 1.28f).toDouble()).toFloat().coerceAtLeast(16f)
    baselineOffsetPx = -fm.ascent + (rowHeightPx - raw) * 0.5f
    underlinePaint.strokeWidth = max(1.2f, density)
    recalculateGridAndNotify()
    invalidate()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    recalculateGridAndNotify()
  }

  private fun recalculateGridAndNotify() {
    if (width <= 0 || height <= 0) return
    val newCols = ((width - padX * 2f) / charWidthPx).toInt().coerceIn(20, 320)
    val newRows = ((height - padY * 2f) / rowHeightPx).toInt().coerceIn(4, 160)
    session?.resize(newCols, newRows)
    snapToBottomIfFollowing()
  }

  private fun snapToBottomIfFollowing() {
    val buf = session?.buffer ?: return
    if (followBottom) {
      viewportTopRow = max(0, buf.totalLines() - buf.rows)
    }
  }

  fun scrollToBottom() {
    followBottom = true
    snapToBottomIfFollowing()
    invalidate()
  }

  override fun onCheckIsTextEditor(): Boolean = true

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
    return object : BaseInputConnection(this, false) {
      override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val str = text?.toString()
        if (!str.isNullOrEmpty()) {
          followBottom = true
          session?.sendInputText(str)
          invalidate()
        }
        return true
      }

      override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0) {
          followBottom = true
          repeat(beforeLength) { session?.handleSpecialKey("BACKSPACE") }
          invalidate()
        }
        return true
      }

      override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
          return this@QuarkTerminalView.onKeyDown(event.keyCode, event)
        }
        return super.sendKeyEvent(event)
      }
    }
  }

  override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    if (gainFocus) {
      removeCallbacks(cursorBlinkRunnable)
      cursorVisible = true
      postDelayed(cursorBlinkRunnable, 530L)
    } else {
      removeCallbacks(cursorBlinkRunnable)
      cursorVisible = false
      invalidate()
    }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    removeCallbacks(cursorBlinkRunnable)
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    val s = session ?: return super.onKeyDown(keyCode, event)
    followBottom = true
    if (event.isCtrlPressed) {
      when (keyCode) {
        KeyEvent.KEYCODE_C -> { s.handleSpecialKey("CTRL_C"); return true }
        KeyEvent.KEYCODE_L -> { s.handleSpecialKey("CTRL_L"); return true }
        KeyEvent.KEYCODE_D -> { s.handleSpecialKey("CTRL_D"); return true }
      }
    }
    when (keyCode) {
      KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { s.handleSpecialKey("ENTER"); return true }
      KeyEvent.KEYCODE_DEL -> { s.handleSpecialKey("BACKSPACE"); return true }
      KeyEvent.KEYCODE_TAB -> { s.handleSpecialKey("TAB"); return true }
      KeyEvent.KEYCODE_DPAD_UP -> { s.handleSpecialKey("UP"); return true }
      KeyEvent.KEYCODE_DPAD_DOWN -> { s.handleSpecialKey("DOWN"); return true }
      KeyEvent.KEYCODE_DPAD_LEFT -> { s.handleSpecialKey("LEFT"); return true }
      KeyEvent.KEYCODE_DPAD_RIGHT -> { s.handleSpecialKey("RIGHT"); return true }
      KeyEvent.KEYCODE_MOVE_HOME -> { s.handleSpecialKey("HOME"); return true }
      KeyEvent.KEYCODE_MOVE_END -> { s.handleSpecialKey("END"); return true }
    }
    val uni = event.unicodeChar
    if (uni != 0 && !event.isCtrlPressed && !event.isAltPressed) {
      val ch = uni.toChar()
      if (!ch.isISOControl()) {
        s.sendInputText(ch.toString())
        return true
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  // --- Zero-Allocation Rendering ---

  override fun onDraw(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= 0f || h <= 0f) return
    canvas.drawRect(0f, 0f, w, h, bgPaint)

    val buf = session?.buffer ?: return
    val visibleRowCount = ((h - padY * 2f) / rowHeightPx).toInt().coerceAtLeast(1)

    synchronized(buf.lock) {
      val sbSize = buf.scrollbackSize()
      val total = sbSize + buf.rows
      val maxTop = max(0, total - buf.rows)
      val topIdx = if (followBottom) maxTop else viewportTopRow.coerceIn(0, maxTop)
      val cols = buf.cols

      for (vRow in 0 until min(visibleRowCount + 1, total - topIdx)) {
        val absRow = topIdx + vRow
        val row = buf.getRowAtAbsoluteIndexLocked(absRow) ?: continue
        val topY = padY + vRow * rowHeightPx
        val bottomY = topY + rowHeightPx
        val baseY = topY + baselineOffsetPx

        // Batch contiguous identical style runs across columns
        var col = 0
        while (col < cols && col < row.chars.size) {
          val fgPack = row.fg[col]
          val bgPack = row.bg[col]
          val attr = row.attrs[col].toInt()
          var runEnd = col + 1
          while (
            runEnd < cols &&
            runEnd < row.chars.size &&
            row.fg[runEnd] == fgPack &&
            row.bg[runEnd] == bgPack &&
            row.attrs[runEnd].toInt() == attr
          ) {
            runEnd++
          }

          val isBold = (attr and CellAttr.BOLD) != 0
          val isInverse = (attr and CellAttr.INVERSE) != 0
          val isUnderline = (attr and CellAttr.UNDERLINE) != 0

          var fgArgb = resolveColor(fgPack, isFg = true, isBold = isBold)
          var bgArgb = resolveColor(bgPack, isFg = false, isBold = false)
          if (isInverse) {
            val tmp = fgArgb
            fgArgb = bgArgb
            bgArgb = tmp
          }

          val runX = padX + col * charWidthPx
          val runW = (runEnd - col) * charWidthPx

          if (bgArgb != palette.background) {
            cellBgPaint.color = bgArgb
            canvas.drawRect(runX, topY, runX + runW, bottomY, cellBgPaint)
          }

          // Skip drawing all-space runs unless underlined
          var hasNonSpace = false
          for (k in col until runEnd) {
            if (row.chars[k] != ' ') { hasNonSpace = true; break }
          }
          if (hasNonSpace) {
            textPaint.color = fgArgb
            textPaint.isFakeBoldText = isBold
            canvas.drawText(row.chars, col, runEnd - col, runX, baseY, textPaint)
          }
          if (isUnderline) {
            underlinePaint.color = fgArgb
            canvas.drawLine(runX, bottomY - 2f * density, runX + runW, bottomY - 2f * density, underlinePaint)
          }
          col = runEnd
        }

        // Draw cursor if visible on this active screen row
        val cursorAbsRow = sbSize + buf.cursorRow
        if (absRow == cursorAbsRow && (cursorVisible || !hasFocus())) {
          val cx = padX + buf.cursorCol * charWidthPx
          canvas.drawRect(cx, topY + 2f, cx + max(2.5f * density, charWidthPx * 0.85f), bottomY - 2f, cursorPaint)
        }
      }
    }
    textPaint.isFakeBoldText = false
  }

  private fun resolveColor(packed: Int, isFg: Boolean, isBold: Boolean): Int {
    if (CellColor.isDefault(packed)) {
      return if (isFg) palette.foreground else palette.background
    }
    if (CellColor.isTrueColor(packed)) {
      return CellColor.trueColorArgb(packed)
    }
    val idx = CellColor.indexedValue(packed)
    return if (idx in 0..15) {
      val mapped = if (isFg && isBold && idx in 0..7) idx + 8 else idx
      palette.ansi16[mapped]
    } else {
      XTERM_256_LUT[idx.coerceIn(0, 255)]
    }
  }

  // --- Touch Scroll / Pinch Zoom / IME Focus ---

  override fun onTouchEvent(event: MotionEvent): Boolean {
    scaleDetector.onTouchEvent(event)
    if (isScaling) return true
    if (event.actionMasked == MotionEvent.ACTION_DOWN && !scroller.isFinished) {
      scroller.abortAnimation()
    }
    return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
  }

  override fun computeScroll() {
    if (scroller.computeScrollOffset()) {
      val buf = session?.buffer ?: return
      val maxTop = max(0, buf.totalLines() - buf.rows)
      viewportTopRow = floor(scroller.currY / rowHeightPx).toInt().coerceIn(0, maxTop)
      followBottom = (viewportTopRow >= maxTop)
      postInvalidateOnAnimation()
    }
  }

  private inner class TerminalGestureListener : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(e: MotionEvent): Boolean = true

    override fun onSingleTapUp(e: MotionEvent): Boolean {
      requestFocus()
      val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
      imm?.showSoftInput(this@QuarkTerminalView, InputMethodManager.SHOW_IMPLICIT)
      invalidate()
      return true
    }

    override fun onScroll(
      e1: MotionEvent?,
      e2: MotionEvent,
      distanceX: Float,
      distanceY: Float,
    ): Boolean {
      val buf = session?.buffer ?: return false
      val rowDelta = (distanceY / rowHeightPx).toInt().let {
        if (it == 0 && abs(distanceY) > 3f) if (distanceY > 0) 1 else -1 else it
      }
      val maxTop = max(0, buf.totalLines() - buf.rows)
      viewportTopRow = (viewportTopRow + rowDelta).coerceIn(0, maxTop)
      followBottom = (viewportTopRow >= maxTop)
      invalidate()
      return true
    }

    override fun onFling(
      e1: MotionEvent?,
      e2: MotionEvent,
      velocityX: Float,
      velocityY: Float,
    ): Boolean {
      val buf = session?.buffer ?: return false
      val maxPx = (max(0, buf.totalLines() - buf.rows) * rowHeightPx).toInt()
      scroller.fling(
        0,
        (viewportTopRow * rowHeightPx).toInt(),
        0,
        -velocityY.toInt(),
        0,
        0,
        0,
        maxPx,
      )
      postInvalidateOnAnimation()
      return true
    }
  }

  private inner class TerminalScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
      isScaling = true
      return true
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
      setFontSizeSp(fontSizeSp * detector.scaleFactor)
      return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
      isScaling = false
    }
  }

  companion object {
    // Pre-computed static xterm-256 ARGB LUT (zero allocations at draw time)
    private val XTERM_256_LUT: IntArray = IntArray(256).apply {
      val base16 = intArrayOf(
        0xFF000000.toInt(), 0xFF800000.toInt(), 0xFF008000.toInt(), 0xFF808000.toInt(),
        0xFF000080.toInt(), 0xFF800080.toInt(), 0xFF008080.toInt(), 0xFFC0C0C0.toInt(),
        0xFF808080.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
        0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
      )
      for (i in 0..15) this[i] = base16[i]
      val steps = intArrayOf(0, 95, 135, 175, 215, 255)
      for (i in 16..231) {
        val v = i - 16
        val r = steps[v / 36]
        val g = steps[(v / 6) % 6]
        val b = steps[v % 6]
        this[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
      for (i in 232..255) {
        val g = 8 + (i - 232) * 10
        this[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
      }
    }
  }
}
