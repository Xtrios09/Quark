package com.quark.buffer

/**
 * Zero-based line and column coordinates within a [TextBuffer].
 *
 * @property line Zero-based line index.
 * @property column Zero-based UTF-16 character offset within [line].
 */
data class TextPosition(
  val line: Int,
  val column: Int,
) : Comparable<TextPosition> {
  override fun compareTo(other: TextPosition): Int {
    val lineCmp = line.compareTo(other.line)
    return if (lineCmp != 0) lineCmp else column.compareTo(other.column)
  }
}

/**
 * Half-open character range `[start, end)` in a [TextBuffer].
 *
 * @property start Inclusive start offset.
 * @property end Exclusive end offset.
 */
data class TextRange(
  val start: Int,
  val end: Int,
) {
  val length: Int get() = (end - start).coerceAtLeast(0)
  val isEmpty: Boolean get() = start >= end

  operator fun contains(offset: Int): Boolean = offset in start until end
}

/** Line separator style detected or configured for a document. */
enum class LineEnding(val sequence: String, val label: String) {
  LF("\n", "LF"),
  CRLF("\r\n", "CRLF"),
}

/**
 * Immutable event emitted synchronously after a [TextBuffer] mutation.
 *
 * @property version Monotonically increasing document version after this edit.
 * @property offset Character offset where the change occurred.
 * @property removedLength Number of UTF-16 chars removed starting at [offset].
 * @property insertedText Text inserted at [offset].
 * @property startLine Zero-based line containing [offset] before and after the edit.
 * @property oldEndLine Zero-based end line affected before the edit.
 * @property newEndLine Zero-based end line affected after the edit.
 * @property isUndo True if triggered by an undo operation.
 * @property isRedo True if triggered by a redo operation.
 */
data class BufferChangeEvent(
  val version: Long,
  val offset: Int,
  val removedLength: Int,
  val insertedText: String,
  val startLine: Int,
  val oldEndLine: Int,
  val newEndLine: Int,
  val isUndo: Boolean = false,
  val isRedo: Boolean = false,
)

/** Listener notified synchronously on buffer mutations and selection changes. */
fun interface BufferChangeListener {
  /** Called immediately after [event] is applied to the buffer. */
  fun onBufferChanged(event: BufferChangeEvent)
}
