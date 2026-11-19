package com.quark.terminal

/**
 * Streaming VT100 / ANSI CSI + OSC escape sequence parser feeding a [TerminalBuffer].
 */
class AnsiParser(private val buffer: TerminalBuffer) {

  private enum class State {
    GROUND,
    ESC,
    CSI,
    OSC,
    OSC_ESC,
  }

  private var state = State.GROUND
  private val paramBuf = StringBuilder(64)
  private val oscBuf = StringBuilder(128)

  fun feed(text: String) {
    synchronized(buffer.lock) {
      for (i in 0 until text.length) {
        processChar(text[i])
      }
    }
  }

  private fun processChar(ch: Char) {
    when (state) {
      State.GROUND -> {
        when (ch) {
          '\u001B' -> state = State.ESC
          '\r' -> buffer.carriageReturn()
          '\n' -> buffer.lineFeed()
          '\b' -> buffer.backspace()
          '\t' -> buffer.tab()
          '\u0007' -> { /* BEL ignored */ }
          else -> {
            if (ch.code >= 0x20 || ch == '\u0000') {
              if (ch != '\u0000') buffer.putChar(ch)
            }
          }
        }
      }

      State.ESC -> {
        when (ch) {
          '[' -> {
            paramBuf.clear()
            state = State.CSI
          }
          ']' -> {
            oscBuf.clear()
            state = State.OSC
          }
          'c' -> {
            buffer.resetAttributes()
            buffer.eraseDisplay(2)
            state = State.GROUND
          }
          else -> state = State.GROUND
        }
      }

      State.CSI -> {
        if (ch.code in 0x40..0x7E) {
          executeCsi(ch, paramBuf.toString())
          state = State.GROUND
        } else if (paramBuf.length < 128) {
          paramBuf.append(ch)
        }
      }

      State.OSC -> {
        when (ch) {
          '\u0007' -> {
            applyOsc(oscBuf.toString())
            state = State.GROUND
          }
          '\u001B' -> state = State.OSC_ESC
          else -> if (oscBuf.length < 512) oscBuf.append(ch)
        }
      }

      State.OSC_ESC -> {
        if (ch == '\\') {
          applyOsc(oscBuf.toString())
        }
        state = State.GROUND
      }
    }
  }

  private fun applyOsc(raw: String) {
    val semi = raw.indexOf(';')
    if (semi > 0) {
      val code = raw.substring(0, semi)
      if (code == "0" || code == "2") {
        buffer.title = raw.substring(semi + 1)
      }
    }
  }

  private fun executeCsi(cmd: Char, rawParams: String) {
    val cleanParams = rawParams.trimStart('?', '>')
    val params =
      if (cleanParams.isEmpty()) emptyList()
      else cleanParams.split(';').map { it.toIntOrNull() ?: 0 }

    when (cmd) {
      'm' -> applySgr(params)
      'A' -> buffer.moveCursor(-(params.firstOrNull()?.coerceAtLeast(1) ?: 1), 0)
      'B' -> buffer.moveCursor(params.firstOrNull()?.coerceAtLeast(1) ?: 1, 0)
      'C' -> buffer.moveCursor(0, params.firstOrNull()?.coerceAtLeast(1) ?: 1)
      'D' -> buffer.moveCursor(0, -(params.firstOrNull()?.coerceAtLeast(1) ?: 1))
      'E' -> {
        buffer.moveCursor(params.firstOrNull()?.coerceAtLeast(1) ?: 1, 0)
        buffer.carriageReturn()
      }
      'F' -> {
        buffer.moveCursor(-(params.firstOrNull()?.coerceAtLeast(1) ?: 1), 0)
        buffer.carriageReturn()
      }
      'G' -> buffer.setCursorCol(params.firstOrNull()?.coerceAtLeast(1) ?: 1)
      'H', 'f' -> {
        val row = params.getOrNull(0)?.coerceAtLeast(1) ?: 1
        val col = params.getOrNull(1)?.coerceAtLeast(1) ?: 1
        buffer.setCursorPos(row, col)
      }
      'J' -> buffer.eraseDisplay(params.firstOrNull() ?: 0)
      'K' -> buffer.eraseInLine(params.firstOrNull() ?: 0)
    }
  }

  private fun applySgr(params: List<Int>) {
    if (params.isEmpty()) {
      buffer.resetAttributes()
      return
    }
    var i = 0
    while (i < params.size) {
      when (val p = params[i]) {
        0 -> buffer.resetAttributes()
        1 -> buffer.currentAttrs = buffer.currentAttrs or CellAttr.BOLD
        2 -> buffer.currentAttrs = buffer.currentAttrs or CellAttr.DIM
        4 -> buffer.currentAttrs = buffer.currentAttrs or CellAttr.UNDERLINE
        7 -> buffer.currentAttrs = buffer.currentAttrs or CellAttr.INVERSE
        22 -> buffer.currentAttrs = buffer.currentAttrs and (CellAttr.BOLD or CellAttr.DIM).inv()
        24 -> buffer.currentAttrs = buffer.currentAttrs and CellAttr.UNDERLINE.inv()
        27 -> buffer.currentAttrs = buffer.currentAttrs and CellAttr.INVERSE.inv()
        in 30..37 -> buffer.currentFg = CellColor.indexed(p - 30)
        39 -> buffer.currentFg = CellColor.DEFAULT
        in 40..47 -> buffer.currentBg = CellColor.indexed(p - 40)
        49 -> buffer.currentBg = CellColor.DEFAULT
        in 90..97 -> buffer.currentFg = CellColor.indexed(p - 90 + 8)
        in 100..107 -> buffer.currentBg = CellColor.indexed(p - 100 + 8)
        38, 48 -> {
          val isFg = (p == 38)
          val mode = params.getOrNull(i + 1)
          if (mode == 5 && i + 2 < params.size) {
            val c = CellColor.indexed(params[i + 2])
            if (isFg) buffer.currentFg = c else buffer.currentBg = c
            i += 2
          } else if (mode == 2 && i + 4 < params.size) {
            val c = CellColor.trueColor(params[i + 2], params[i + 3], params[i + 4])
            if (isFg) buffer.currentFg = c else buffer.currentBg = c
            i += 4
          }
        }
      }
      i++
    }
  }
}
