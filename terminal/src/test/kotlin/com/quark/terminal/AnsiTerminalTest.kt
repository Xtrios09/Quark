package com.quark.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTerminalTest {

  @Test
  fun ansiParserAppliesSgr16And256AndTrueColorAttributes() {
    val buffer = TerminalBuffer(initialCols = 40, initialRows = 8)
    val parser = AnsiParser(buffer)

    parser.feed("\u001B[1;31mRED\u001B[0m \u001B[38;5;196mIDX\u001B[38;2;10;20;30mRGB\u001B[4;7mUL\u001B[0m")

    val row = buffer.getRowAtAbsoluteIndexLocked(0)!!
    assertEquals("RED IDXRGBUL", row.trimText())

    // "RED": index 1 + bold
    assertEquals(CellColor.indexed(1), row.fg[0])
    assertTrue((row.attrs[0].toInt() and CellAttr.BOLD) != 0)

    // " ": reset default
    assertEquals(CellColor.DEFAULT, row.fg[3])
    assertEquals(0, row.attrs[3].toInt())

    // "IDX": xterm 256 color 196
    assertEquals(CellColor.indexed(196), row.fg[4])

    // "RGB": 24-bit truecolor (10, 20, 30)
    assertTrue(CellColor.isTrueColor(row.fg[7]))
    assertEquals(0xFF0A141E.toInt(), CellColor.trueColorArgb(row.fg[7]))

    // "UL": underline + inverse
    val ulAttrs = row.attrs[10].toInt()
    assertTrue((ulAttrs and CellAttr.UNDERLINE) != 0)
    assertTrue((ulAttrs and CellAttr.INVERSE) != 0)
  }

  @Test
  fun cursorMotionEraseAndOscTitleWorkCorrectly() {
    val buffer = TerminalBuffer(initialCols = 20, initialRows = 5)
    val parser = AnsiParser(buffer)

    parser.feed("\u001B]2;Quark Shell\u0007")
    assertEquals("Quark Shell", buffer.title)

    parser.feed("hello world")
    parser.feed("\rhi")
    assertEquals("hillo world", buffer.dumpVisibleText())

    // Erase from cursor to end of line
    parser.feed("\u001B[1;6H\u001B[0K")
    assertEquals("hillo", buffer.dumpVisibleText())

    // Tab stops advance to column 8
    parser.feed("\rA\tB")
    val row0 = buffer.getRowAtAbsoluteIndexLocked(0)!!
    assertEquals('A', row0.chars[0])
    assertEquals('B', row0.chars[8])
  }

  @Test
  fun scrollbackRetainsOverflowLinesRespectingCapacity() {
    val buffer = TerminalBuffer(initialCols = 20, initialRows = 4, maxScrollback = 5)
    val parser = AnsiParser(buffer)

    for (i in 1..10) {
      parser.feed("line $i\r\n")
    }

    assertEquals(5, buffer.scrollbackSize())
    assertEquals(9, buffer.totalLines())
    val visible = buffer.dumpVisibleText()
    assertTrue(visible.contains("line 9"))
    assertTrue(visible.contains("line 10"))
  }
}
