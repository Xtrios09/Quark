package com.quark.buffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BufferTest {

  @Test
  fun fuzzPieceTreeAgainstReferenceStringBuilder() {
    val rng = Random(42)
    val initial = "line0\nline1\nline2\n"
    val tree = PieceTree(initial)
    val ref = StringBuilder(initial)

    for (step in 0 until 2000) {
      if (ref.isEmpty() || rng.nextInt(100) < 60) {
        val pos = rng.nextInt(ref.length + 1)
        val chunkLen = rng.nextInt(1, 40)
        val sb = StringBuilder(chunkLen)
        for (i in 0 until chunkLen) {
          sb.append(
            when (rng.nextInt(10)) {
              0 -> '\n'
              1 -> ' '
              else -> ('a' + rng.nextInt(26))
            }
          )
        }
        val text = sb.toString()
        tree.insert(pos, text)
        ref.insert(pos, text)
      } else {
        val pos = rng.nextInt(ref.length)
        val maxDel = (ref.length - pos).coerceAtMost(50)
        val delLen = rng.nextInt(1, maxDel + 1)
        tree.delete(pos, delLen)
        ref.delete(pos, pos + delLen)
      }

      assertEquals("Length mismatch at step $step", ref.length, tree.length)
      if (step % 50 == 0 || step == 1999) {
        assertEquals("Full text mismatch at step $step", ref.toString(), tree.getText())
        val refLines = ref.toString().split('\n')
        assertEquals("Line count mismatch at step $step", refLines.size, tree.lineCount)
        for (l in refLines.indices) {
          assertEquals("Line $l mismatch at step $step", refLines[l], tree.getLineText(l))
        }
      }
    }
  }

  @Test
  fun multiCursorAndGroupedUndoRedo() {
    val buf = TextBuffer("alpha beta\nalpha gamma\nalpha delta\n")
    buf.setCursor(0)
    assertTrue(buf.addNextOccurrence()) // selects "alpha" on line 0
    assertTrue(buf.addNextOccurrence()) // adds "alpha" on line 1
    assertTrue(buf.addNextOccurrence()) // adds "alpha" on line 2
    assertEquals(3, buf.selectionSet.selections.size)

    buf.insertTextAtCursors("omega")
    assertEquals("omega beta\nomega gamma\nomega delta\n", buf.getText())

    assertTrue(buf.undo())
    assertEquals("alpha beta\nalpha gamma\nalpha delta\n", buf.getText())

    assertTrue(buf.redo())
    assertEquals("omega beta\nomega gamma\nomega delta\n", buf.getText())
  }

  @Test
  fun open200kLinesUnderOneSecond() {
    val line = "val item = computeSomething(index, factor = 42) // sample code line\n"
    val big = buildString(line.length * 200_000) {
      repeat(200_000) { append(line) }
    }
    val startNs = System.nanoTime()
    val buf = TextBuffer(big)
    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
    assertEquals(200_001, buf.lineCount)
    assertTrue("Opening 200k lines took ${elapsedMs}ms", elapsedMs < 1000.0)
    assertEquals(line.trimEnd('\n'), buf.getLineText(150_000))
  }
}
