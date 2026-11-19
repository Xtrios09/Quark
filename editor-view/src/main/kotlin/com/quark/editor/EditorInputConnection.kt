package com.quark.editor

import android.text.Editable
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import kotlin.math.max
import kotlin.math.min

/**
 * Custom [BaseInputConnection] connecting Android soft keyboards (Gboard, Samsung, SwiftKey)
 * and hardware IMEs directly to [QuarkEditorView] and its [com.quark.buffer.TextBuffer].
 */
internal class EditorInputConnection(
  private val editorView: QuarkEditorView,
) : BaseInputConnection(editorView, true) {

  private var batchDepth = 0
  private val dummyEditable = SpannableStringBuilder()

  override fun getEditable(): Editable = dummyEditable

  override fun beginBatchEdit(): Boolean {
    batchDepth++
    return true
  }

  override fun endBatchEdit(): Boolean {
    if (batchDepth > 0) batchDepth--
    if (batchDepth == 0) {
      editorView.onBufferMutatedFromIme()
    }
    return batchDepth > 0
  }

  override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
    val str = text?.toString().orEmpty()
    val buf = editorView.buffer
    val comp = buf.composingRange
    if (comp != null) {
      buf.setComposingText(str)
      buf.finishComposingText()
    } else if (str.isNotEmpty()) {
      editorView.insertCommittedText(str)
    }
    editorView.onBufferMutatedFromIme()
    return true
  }

  override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
    val str = text?.toString().orEmpty()
    editorView.buffer.setComposingText(str)
    editorView.onBufferMutatedFromIme()
    return true
  }

  override fun setComposingRegion(start: Int, end: Int): Boolean {
    val s = min(start, end)
    val e = max(start, end)
    editorView.buffer.setComposingRegion(s, e)
    editorView.invalidate()
    return true
  }

  override fun finishComposingText(): Boolean {
    editorView.buffer.finishComposingText()
    editorView.onBufferMutatedFromIme()
    return true
  }

  override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
    val buf = editorView.buffer
    buf.finishComposingText()
    if (beforeLength > 0) {
      val primary = buf.selectionSet.primary
      val delStart = (primary.start - beforeLength).coerceAtLeast(0)
      val delLen = primary.start - delStart
      if (delLen > 0) {
        buf.replace(delStart, delLen, "")
      }
    }
    if (afterLength > 0) {
      val primary = buf.selectionSet.primary
      val delLen = afterLength.coerceAtMost(buf.length - primary.end)
      if (delLen > 0) {
        buf.replace(primary.end, delLen, "")
      }
    }
    editorView.onBufferMutatedFromIme()
    return true
  }

  override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
    return deleteSurroundingText(beforeLength, afterLength)
  }

  override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
    if (n <= 0) return ""
    val buf = editorView.buffer
    val pos = buf.selectionSet.primary.start
    val start = (pos - n).coerceAtLeast(0)
    return buf.getText(start, pos)
  }

  override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
    if (n <= 0) return ""
    val buf = editorView.buffer
    val pos = buf.selectionSet.primary.end
    val end = (pos + n).coerceAtMost(buf.length)
    return buf.getText(pos, end)
  }

  override fun getSelectedText(flags: Int): CharSequence? {
    val buf = editorView.buffer
    val primary = buf.selectionSet.primary
    if (primary.isEmpty) return null
    return buf.getText(primary.start, primary.end)
  }

  override fun setSelection(start: Int, end: Int): Boolean {
    editorView.buffer.setSelection(start, end)
    editorView.onSelectionMovedFromIme()
    return true
  }

  override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
    if (request == null) return null
    val buf = editorView.buffer
    val primary = buf.selectionSet.primary
    // Bound extracted text for huge files (10MB+) so Android IME IPC never crashes with TransactionTooLargeException
    val windowStart = (primary.start - 2048).coerceAtLeast(0)
    val windowEnd = (primary.end + 2048).coerceAtMost(buf.length)
    return ExtractedText().apply {
      text = buf.getText(windowStart, windowEnd)
      startOffset = windowStart
      selectionStart = primary.start - windowStart
      selectionEnd = primary.end - windowStart
    }
  }

  override fun performContextMenuAction(id: Int): Boolean {
    return when (id) {
      android.R.id.selectAll -> {
        editorView.selectAll()
        true
      }
      android.R.id.copy -> {
        editorView.copySelection()
        true
      }
      android.R.id.cut -> {
        editorView.cutSelection()
        true
      }
      android.R.id.paste -> {
        editorView.pasteFromClipboard()
        true
      }
      else -> false
    }
  }

  override fun sendKeyEvent(event: KeyEvent): Boolean {
    return if (event.action == KeyEvent.ACTION_DOWN) {
      editorView.onKeyDown(event.keyCode, event)
    } else if (event.action == KeyEvent.ACTION_UP) {
      editorView.onKeyUp(event.keyCode, event)
    } else {
      super.sendKeyEvent(event)
    }
  }

  override fun performEditorAction(editorAction: Int): Boolean {
    editorView.insertNewline()
    return true
  }
}
