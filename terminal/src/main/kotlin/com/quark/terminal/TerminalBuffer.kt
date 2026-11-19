package com.quark.terminal

import kotlin.math.max
import kotlin.math.min

/**
 * Color encoding packed in a 32-bit `Int` for zero-allocation terminal cells:
 * - `0`: Default foreground or background from active theme
 * - `1..256`: Indexed xterm-256 color (`index + 1`)
 * - `(TRUECOLOR_FLAG or rgb24)`: 24-bit RGB color (`rgb24` in low 24 bits)
 */
object CellColor {
  const val DEFAULT: Int = 0
  const val TRUECOLOR_FLAG: Int = 1 shl 30

  fun indexed(idx: Int): Int = (idx.coerceIn(0, 255)) + 1
  fun trueColor(r: Int, g: Int, b: Int): Int =
    TRUECOLOR_FLAG or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

  fun isDefault(packed: Int): Boolean = packed == DEFAULT
  fun isIndexed(packed: Int): Boolean = packed in 1..256
  fun indexedValue(packed: Int): Int = packed - 1
  fun isTrueColor(packed: Int): Boolean = (packed and TRUECOLOR_FLAG) != 0
  fun trueColorArgb(packed: Int): Int = (0xFF shl 24) or (packed and 0x00FFFFFF)
}

object CellAttr {
  const val BOLD: Int = 1 shl 0
  const val UNDERLINE: Int = 1 shl 1
  const val INVERSE: Int = 1 shl 2
  const val DIM: Int = 1 shl 3
}

/**
 * Single row in the terminal grid or scrollback ring buffer using flat primitive arrays.
 */
class TerminalRow(capacity: Int) {
  var chars: CharArray = CharArray(capacity) { ' ' }
  var fg: IntArray = IntArray(capacity) { CellColor.DEFAULT }
  var bg: IntArray = IntArray(capacity) { CellColor.DEFAULT }
  var attrs: ByteArray = ByteArray(capacity)
  var wrappedFromPrev: Boolean = false

  fun ensureWidth(newCols: Int) {
    if (chars.size >= newCols) return
    val oldLen = chars.size
    chars = chars.copyOf(newCols).apply { java.util.Arrays.fill(this, oldLen, newCols, ' ') }
    fg = fg.copyOf(newCols)
    bg = bg.copyOf(newCols)
    attrs = attrs.copyOf(newCols)
  }

  fun clearRange(fromCol: Int, toColExclusive: Int, clearBg: Int = CellColor.DEFAULT) {
    val start = fromCol.coerceIn(0, chars.size)
    val end = toColExclusive.coerceIn(start, chars.size)
    for (c in start until end) {
      chars[c] = ' '
      fg[c] = CellColor.DEFAULT
      bg[c] = clearBg
      attrs[c] = 0
    }
  }

  fun copySnapshotFrom(other: TerminalRow, width: Int) {
    ensureWidth(width)
    val count = min(width, other.chars.size)
    System.arraycopy(other.chars, 0, chars, 0, count)
    System.arraycopy(other.fg, 0, fg, 0, count)
    System.arraycopy(other.bg, 0, bg, 0, count)
    System.arraycopy(other.attrs, 0, attrs, 0, count)
    if (count < width) clearRange(count, width)
    wrappedFromPrev = other.wrappedFromPrev
  }

  fun trimText(): String {
    var end = chars.size
    while (end > 0 && chars[end - 1] == ' ') end--
    return String(chars, 0, end)
  }
}

/**
 * Thread-safe terminal screen grid + scrollback buffer supporting VT100 scrolling,
 * cursor positioning, SGR attributes, and zero-allocation row access for `QuarkTerminalView`.
 */
class TerminalBuffer(
  initialCols: Int = 80,
  initialRows: Int = 24,
  private val maxScrollback: Int = 4000,
) {
  val lock = Any()

  var cols: Int = initialCols.coerceAtLeast(10)
    private set
  var rows: Int = initialRows.coerceAtLeast(4)
    private set

  var cursorCol: Int = 0
    private set
  var cursorRow: Int = 0
    private set

  var currentFg: Int = CellColor.DEFAULT
  var currentBg: Int = CellColor.DEFAULT
  var currentAttrs: Int = 0

  var title: String = "sh"

  private val scrollback = ArrayDeque<TerminalRow>(256)
  private var screen: Array<TerminalRow> = Array(rows) { TerminalRow(cols) }

  var version: Long = 0L
    private set

  fun scrollbackSize(): Int = synchronized(lock) { scrollback.size }

  fun totalLines(): Int = synchronized(lock) { scrollback.size + rows }

  fun resize(newCols: Int, newRows: Int) {
    val c = newCols.coerceIn(10, 400)
    val r = newRows.coerceIn(4, 200)
    synchronized(lock) {
      if (c == cols && r == rows) return
      for (row in screen) row.ensureWidth(c)
      if (r < rows) {
        val overflow = rows - r
        val keepCursorDown = cursorRow
        val pushCount = (keepCursorDown - (r - 1)).coerceIn(0, overflow)
        for (i in 0 until pushCount) {
          pushToScrollback(screen[i])
        }
        val next = Array(r) { idx ->
          if (idx + pushCount < rows) screen[idx + pushCount] else TerminalRow(c)
        }
        screen = next
        cursorRow = (cursorRow - pushCount).coerceIn(0, r - 1)
      } else if (r > rows) {
        val next = Array(r) { idx ->
          if (idx < screen.size) screen[idx] else TerminalRow(c)
        }
        screen = next
      }
      cols = c
      rows = r
      cursorCol = cursorCol.coerceIn(0, cols - 1)
      cursorRow = cursorRow.coerceIn(0, rows - 1)
      version++
    }
  }

  /** Direct zero-alloc row access for [QuarkTerminalView] while holding [lock]. */
  fun getRowAtAbsoluteIndexLocked(absIndex: Int): TerminalRow? {
    val sbSize = scrollback.size
    return if (absIndex < 0) null
    else if (absIndex < sbSize) scrollback[absIndex]
    else {
      val sIdx = absIndex - sbSize
      if (sIdx in screen.indices) screen[sIdx] else null
    }
  }

  fun putChar(ch: Char) {
    synchronized(lock) {
      if (cursorCol >= cols) {
        cursorCol = 0
        advanceLine(wrapped = true)
      }
      val row = screen[cursorRow]
      row.ensureWidth(cols)
      row.chars[cursorCol] = ch
      row.fg[cursorCol] = currentFg
      row.bg[cursorCol] = currentBg
      row.attrs[cursorCol] = currentAttrs.toByte()
      cursorCol++
      version++
    }
  }

  fun carriageReturn() {
    synchronized(lock) {
      cursorCol = 0
    }
  }

  fun lineFeed() {
    synchronized(lock) {
      advanceLine(wrapped = false)
      version++
    }
  }

  fun backspace() {
    synchronized(lock) {
      if (cursorCol > 0) cursorCol--
      version++
    }
  }

  fun tab() {
    synchronized(lock) {
      val nextStop = ((cursorCol / 8) + 1) * 8
      val target = min(cols - 1, nextStop)
      val row = screen[cursorRow]
      while (cursorCol < target) {
        row.chars[cursorCol] = ' '
        row.fg[cursorCol] = currentFg
        row.bg[cursorCol] = currentBg
        row.attrs[cursorCol] = currentAttrs.toByte()
        cursorCol++
      }
      version++
    }
  }

  fun setCursorPos(row1Based: Int, col1Based: Int) {
    synchronized(lock) {
      cursorRow = (row1Based - 1).coerceIn(0, rows - 1)
      cursorCol = (col1Based - 1).coerceIn(0, cols - 1)
      version++
    }
  }

  fun moveCursor(dRow: Int, dCol: Int) {
    synchronized(lock) {
      cursorRow = (cursorRow + dRow).coerceIn(0, rows - 1)
      cursorCol = (cursorCol + dCol).coerceIn(0, cols - 1)
      version++
    }
  }

  fun setCursorCol(col1Based: Int) {
    synchronized(lock) {
      cursorCol = (col1Based - 1).coerceIn(0, cols - 1)
      version++
    }
  }

  fun eraseInLine(mode: Int) {
    synchronized(lock) {
      val row = screen[cursorRow]
      when (mode) {
        0 -> row.clearRange(cursorCol, cols, currentBg)
        1 -> row.clearRange(0, cursorCol + 1, currentBg)
        2 -> row.clearRange(0, cols, currentBg)
      }
      version++
    }
  }

  fun eraseDisplay(mode: Int) {
    synchronized(lock) {
      when (mode) {
        0 -> {
          screen[cursorRow].clearRange(cursorCol, cols, currentBg)
          for (r in (cursorRow + 1) until rows) screen[r].clearRange(0, cols, currentBg)
        }
        1 -> {
          for (r in 0 until cursorRow) screen[r].clearRange(0, cols, currentBg)
          screen[cursorRow].clearRange(0, cursorCol + 1, currentBg)
        }
        2, 3 -> {
          for (r in 0 until rows) screen[r].clearRange(0, cols, CellColor.DEFAULT)
          if (mode == 3) scrollback.clear()
          cursorRow = 0
          cursorCol = 0
        }
      }
      version++
    }
  }

  fun resetAttributes() {
    synchronized(lock) {
      currentFg = CellColor.DEFAULT
      currentBg = CellColor.DEFAULT
      currentAttrs = 0
    }
  }

  fun dumpVisibleText(): String = synchronized(lock) {
    screen.joinToString("\n") { it.trimText() }.trimEnd('\n')
  }

  fun dumpAllText(): String = synchronized(lock) {
    val lines = ArrayList<String>(scrollback.size + rows)
    for (r in scrollback) lines.add(r.trimText())
    for (r in screen) lines.add(r.trimText())
    lines.joinToString("\n").trimEnd('\n')
  }

  private fun advanceLine(wrapped: Boolean) {
    if (cursorRow + 1 < rows) {
      cursorRow++
      screen[cursorRow].wrappedFromPrev = wrapped
    } else {
      pushToScrollback(screen[0])
      for (i in 0 until rows - 1) {
        screen[i] = screen[i + 1]
      }
      screen[rows - 1] = TerminalRow(cols).apply { wrappedFromPrev = wrapped }
    }
  }

  private fun pushToScrollback(row: TerminalRow) {
    val copy = TerminalRow(cols)
    copy.copySnapshotFrom(row, cols)
    scrollback.addLast(copy)
    while (scrollback.size > maxScrollback) {
      scrollback.removeFirst()
    }
  }
}
