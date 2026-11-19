package com.quark.buffer

import kotlin.math.max
import kotlin.math.min

/**
 * Full-featured document buffer combining a [PieceTree], multi-cursor [SelectionSet],
 * grouped [UndoManager], composing region tracking, and synchronous [BufferChangeEvent] dispatch.
 */
class TextBuffer(
  initialText: String = "",
  val detectedLineEnding: LineEnding = detectLineEnding(initialText),
) {
  private val tree = PieceTree(initialText)
  private val undoManager = UndoManager()
  private val listeners = ArrayList<BufferChangeListener>(4)

  /** Monotonically increasing document version incremented on every mutation. */
  var version: Long = 0L
    private set

  /** Version at the last save point (used for dirty tracking). */
  var savedVersion: Long = 0L
    private set

  /** True if the buffer has unsaved edits relative to [savedVersion]. */
  val isDirty: Boolean
    get() = version != savedVersion

  /** Active multi-cursor selection set. */
  var selectionSet: SelectionSet = SelectionSet.single(0)
    private set

  /** Active IME composing range, or null if no composition is in progress. */
  var composingRange: TextRange? = null
    private set

  val length: Int get() = tree.length
  val lineCount: Int get() = tree.lineCount
  val canUndo: Boolean get() = undoManager.canUndo
  val canRedo: Boolean get() = undoManager.canRedo

  fun addChangeListener(listener: BufferChangeListener) {
    if (!listeners.contains(listener)) listeners.add(listener)
  }

  fun removeChangeListener(listener: BufferChangeListener) {
    listeners.remove(listener)
  }

  /** Marks the current [version] as clean/saved. */
  fun markSaved() {
    savedVersion = version
  }

  /** Marks the buffer as dirty (e.g. after crash recovery restore). */
  fun markDirty() {
    if (savedVersion == version) {
      savedVersion = -1L
    }
  }

  fun charAt(offset: Int): Char = tree.charAt(offset)

  fun getText(start: Int = 0, end: Int = length): String = tree.getText(start, end)

  fun getLineText(lineIndex: Int): String = tree.getLineText(lineIndex)

  fun getLineStartOffset(lineIndex: Int): Int = tree.getLineStartOffset(lineIndex)

  fun getLineBoundsPacked(lineIndex: Int): Long = tree.getLineBoundsPacked(lineIndex)

  fun getLineLength(lineIndex: Int): Int {
    val packed = tree.getLineBoundsPacked(lineIndex)
    return packed.toInt() - (packed ushr 32).toInt()
  }

  fun copyChars(offset: Int, copyLen: Int, dst: CharArray, dstOffset: Int = 0) {
    tree.copyChars(offset, copyLen, dst, dstOffset)
  }

  fun getLineAtOffset(offset: Int): Int = tree.getLineAtOffset(offset)

  fun offsetToPosition(offset: Int): TextPosition = tree.offsetToPosition(offset)

  fun positionToOffset(line: Int, column: Int): Int = tree.positionToOffset(line, column)

  /** Replaces the entire selection set after clamping and merging overlaps. */
  fun setSelections(selections: List<Selection>, preferredPrimary: Selection? = null) {
    selectionSet = SelectionSet.normalize(selections, preferredPrimary, length)
  }

  /** Sets a single collapsed caret at [offset]. */
  fun setCursor(offset: Int) {
    val clamped = offset.coerceIn(0, length)
    selectionSet = SelectionSet.single(clamped)
  }

  /** Sets a single selection from [anchor] to [active]. */
  fun setSelection(anchor: Int, active: Int) {
    selectionSet = SelectionSet.single(anchor.coerceIn(0, length), active.coerceIn(0, length))
  }

  /** Adds a new collapsed caret at [offset] (Alt+Tap / multi-cursor). */
  fun addCursor(offset: Int) {
    val clamped = offset.coerceIn(0, length)
    val newCaret = Selection.caret(clamped)
    selectionSet = SelectionSet.normalize(selectionSet.selections + newCaret, newCaret, length)
  }

  /** Selects the word around [offset] or returns the existing word range. */
  fun getWordRangeAt(offset: Int): TextRange {
    if (length == 0) return TextRange(0, 0)
    val pos = offset.coerceIn(0, length - 1)
    val c = charAt(pos)
    if (!isWordChar(c)) {
      return TextRange(pos, (pos + 1).coerceAtMost(length))
    }
    var start = pos
    while (start > 0 && isWordChar(charAt(start - 1))) start--
    var end = pos + 1
    while (end < length && isWordChar(charAt(end))) end++
    return TextRange(start, end)
  }

  /**
   * VS Code `Ctrl+D`: if the primary selection is empty, selects the word at caret;
   * otherwise finds the next occurrence of the primary selection's text and adds a cursor.
   */
  fun addNextOccurrence(): Boolean {
    val primary = selectionSet.primary
    if (primary.isEmpty) {
      val word = getWordRangeAt(primary.active)
      if (word.isEmpty) return false
      val updated = Selection(word.start, word.end)
      val list = selectionSet.selections.toMutableList()
      list[selectionSet.primaryIndex] = updated
      selectionSet = SelectionSet.normalize(list, updated, length)
      return true
    }

    val query = getText(primary.start, primary.end)
    if (query.isEmpty()) return false
    val lastEnd = selectionSet.selections.maxOf { it.end }
    var idx = indexOf(query, lastEnd)
    if (idx < 0) {
      idx = indexOf(query, 0)
    }
    if (idx < 0) return false
    val candidate = Selection(idx, idx + query.length)
    if (selectionSet.selections.any { it.start == candidate.start && it.end == candidate.end }) {
      return false
    }
    selectionSet = SelectionSet.normalize(selectionSet.selections + candidate, candidate, length)
    return true
  }

  /** Applies a raw [replace] edit at `[offset, offset + deleteLen)` and records undo. */
  fun replace(
    offset: Int,
    deleteLen: Int,
    insertedText: String,
    recordUndo: Boolean = true,
  ): BufferChangeEvent {
    val safeOffset = offset.coerceIn(0, length)
    val safeDelete = deleteLen.coerceIn(0, length - safeOffset)
    val beforeSelections = selectionSet
    val deletedText = if (safeDelete > 0) tree.getText(safeOffset, safeOffset + safeDelete) else ""

    val event = applyLowLevel(safeOffset, safeDelete, insertedText, isUndo = false, isRedo = false)

    // Shift selections cleanly
    val delta = insertedText.length - safeDelete
    val shifted =
      beforeSelections.selections.map { sel ->
        Selection(
          anchor = adjustOffsetAfterEdit(sel.anchor, safeOffset, safeDelete, insertedText.length),
          active = adjustOffsetAfterEdit(sel.active, safeOffset, safeDelete, insertedText.length),
        )
      }
    selectionSet = SelectionSet.normalize(shifted, null, length)

    if (recordUndo && (safeDelete > 0 || insertedText.isNotEmpty())) {
      undoManager.record(
        EditOp(safeOffset, deletedText, insertedText),
        beforeSelections,
        selectionSet,
      )
    }
    return event
  }

  /**
   * Inserts [text] at all active cursors (replacing any non-empty selections) in a single
   * undoable transaction. Auto-wraps or auto-closes pairs when applicable.
   */
  fun insertTextAtCursors(text: String) {
    if (text.isEmpty() && selectionSet.selections.all { it.isEmpty }) return
    val before = selectionSet
    undoManager.beginTransaction(before)
    val sels = selectionSet.selections.sortedByDescending { it.start }
    val newCursors = ArrayList<Selection>(sels.size)
    // Apply from bottom to top so earlier offsets remain valid; then compute final cursor offsets
    val deltas = IntArray(sels.size)
    val starts = IntArray(sels.size)
    for (i in sels.indices) {
      val sel = sels[i]
      val delLen = sel.length
      val deleted = if (delLen > 0) tree.getText(sel.start, sel.end) else ""
      applyLowLevel(sel.start, delLen, text, isUndo = false, isRedo = false)
      undoManager.record(EditOp(sel.start, deleted, text), before, selectionSet)
      starts[i] = sel.start
      deltas[i] = text.length - delLen
    }
    // Reconstruct final cursor positions in ascending order
    var cumulativeDelta = 0
    for (i in sels.indices.reversed()) {
      val pos = starts[i] + cumulativeDelta + text.length
      newCursors.add(Selection.caret(pos))
      cumulativeDelta += deltas[i]
    }
    selectionSet = SelectionSet.normalize(newCursors, newCursors.lastOrNull(), length)
    composingRange = null
    undoManager.endTransaction(selectionSet)
  }

  /** Backspace across all cursors in a grouped transaction. */
  fun deleteBackwardAtCursors() {
    val before = selectionSet
    val sels = selectionSet.selections.sortedByDescending { it.start }
    if (sels.all { it.isEmpty && it.start == 0 }) return

    val useSingleCharCoalesce = sels.size == 1 && sels[0].isEmpty && sels[0].start > 0
    if (!useSingleCharCoalesce) {
      undoManager.beginTransaction(before)
    }

    val starts = IntArray(sels.size)
    val deltas = IntArray(sels.size)
    val targetPositions = IntArray(sels.size)
    for (i in sels.indices) {
      val sel = sels[i]
      val delStart: Int
      val delLen: Int
      if (!sel.isEmpty) {
        delStart = sel.start
        delLen = sel.length
      } else if (sel.start > 0) {
        // Handle surrogate pairs and CRLF as single logical backspace unit
        val prevChar = charAt(sel.start - 1)
        if (prevChar == '\n' && sel.start >= 2 && charAt(sel.start - 2) == '\r') {
          delStart = sel.start - 2
          delLen = 2
        } else if (Character.isLowSurrogate(prevChar) && sel.start >= 2 && Character.isHighSurrogate(charAt(sel.start - 2))) {
          delStart = sel.start - 2
          delLen = 2
        } else {
          delStart = sel.start - 1
          delLen = 1
        }
      } else {
        starts[i] = 0
        deltas[i] = 0
        targetPositions[i] = 0
        continue
      }
      val deleted = tree.getText(delStart, delStart + delLen)
      applyLowLevel(delStart, delLen, "", isUndo = false, isRedo = false)
      starts[i] = delStart
      deltas[i] = -delLen
      targetPositions[i] = delStart
      if (useSingleCharCoalesce) {
        val after = SelectionSet.single(delStart)
        selectionSet = after
        undoManager.record(EditOp(delStart, deleted, ""), before, after)
        composingRange = null
        return
      } else {
        undoManager.record(EditOp(delStart, deleted, ""), before, selectionSet)
      }
    }

    val newCursors = ArrayList<Selection>(sels.size)
    var cumulativeDelta = 0
    for (i in sels.indices.reversed()) {
      newCursors.add(Selection.caret((targetPositions[i] + cumulativeDelta).coerceIn(0, length)))
      cumulativeDelta += deltas[i]
    }
    selectionSet = SelectionSet.normalize(newCursors, newCursors.lastOrNull(), length)
    composingRange = null
    undoManager.endTransaction(selectionSet)
  }

  /** Forward delete across all cursors. */
  fun deleteForwardAtCursors() {
    val before = selectionSet
    val sels = selectionSet.selections.sortedByDescending { it.start }
    if (sels.all { it.isEmpty && it.end >= length }) return

    val useSingle = sels.size == 1 && sels[0].isEmpty && sels[0].end < length
    if (!useSingle) undoManager.beginTransaction(before)

    val deltas = IntArray(sels.size)
    val targets = IntArray(sels.size)
    for (i in sels.indices) {
      val sel = sels[i]
      val delStart = sel.start
      val delLen =
        if (!sel.isEmpty) {
          sel.length
        } else if (sel.end < length) {
          val ch = charAt(sel.end)
          if (ch == '\r' && sel.end + 1 < length && charAt(sel.end + 1) == '\n') 2
          else if (Character.isHighSurrogate(ch) && sel.end + 1 < length && Character.isLowSurrogate(charAt(sel.end + 1))) 2
          else 1
        } else {
          0
        }
      if (delLen > 0) {
        val deleted = tree.getText(delStart, delStart + delLen)
        applyLowLevel(delStart, delLen, "", isUndo = false, isRedo = false)
        deltas[i] = -delLen
        targets[i] = delStart
        if (useSingle) {
          val after = SelectionSet.single(delStart)
          selectionSet = after
          undoManager.record(EditOp(delStart, deleted, ""), before, after)
          composingRange = null
          return
        } else {
          undoManager.record(EditOp(delStart, deleted, ""), before, selectionSet)
        }
      } else {
        deltas[i] = 0
        targets[i] = delStart
      }
    }

    val newCursors = ArrayList<Selection>(sels.size)
    var cumulativeDelta = 0
    for (i in sels.indices.reversed()) {
      newCursors.add(Selection.caret((targets[i] + cumulativeDelta).coerceIn(0, length)))
      cumulativeDelta += deltas[i]
    }
    selectionSet = SelectionSet.normalize(newCursors, newCursors.lastOrNull(), length)
    composingRange = null
    undoManager.endTransaction(selectionSet)
  }

  /** Inserts a newline preserving current line indentation (plus extra indent after `{` or `:`). */
  fun insertNewlineWithAutoIndent(tabSize: Int, insertSpaces: Boolean) {
    val primary = selectionSet.primary
    val lineIdx = getLineAtOffset(primary.start)
    val lineText = getLineText(lineIdx)
    val col = (primary.start - getLineStartOffset(lineIdx)).coerceIn(0, lineText.length)
    val indent = buildString {
      for (i in 0 until col) {
        val c = lineText[i]
        if (c == ' ' || c == '\t') append(c) else break
      }
    }
    val trimmedBeforeCursor = lineText.substring(0, col).trimEnd()
    val extraIndent =
      if (trimmedBeforeCursor.endsWith("{") || trimmedBeforeCursor.endsWith(":")) {
        if (insertSpaces) " ".repeat(tabSize) else "\t"
      } else {
        ""
      }
    insertTextAtCursors("\n$indent$extraIndent")
  }

  /** Indents or outdents lines covered by active selections (`Tab` / `Shift+Tab`). */
  fun indentSelectedLines(outdent: Boolean, tabSize: Int, insertSpaces: Boolean) {
    val before = selectionSet
    val indentUnit = if (insertSpaces) " ".repeat(tabSize) else "\t"
    val coveredLines = LinkedHashSet<Int>()
    for (sel in before.selections) {
      val startLine = getLineAtOffset(sel.start)
      var endLine = getLineAtOffset(sel.end)
      if (!sel.isEmpty && sel.end > getLineStartOffset(endLine) && sel.end == getLineStartOffset(endLine)) {
        endLine = max(startLine, endLine - 1)
      }
      for (l in startLine..endLine) coveredLines.add(l)
    }
    val sortedLines = coveredLines.sortedDescending()
    undoManager.beginTransaction(before)
    var totalDelta = 0
    for (lineIdx in sortedLines) {
      val lineStart = getLineStartOffset(lineIdx)
      if (!outdent) {
        applyLowLevel(lineStart, 0, indentUnit, isUndo = false, isRedo = false)
        undoManager.record(EditOp(lineStart, "", indentUnit), before, selectionSet)
        totalDelta += indentUnit.length
      } else {
        val lineText = getLineText(lineIdx)
        var removeCount = 0
        if (lineText.startsWith("\t")) {
          removeCount = 1
        } else {
          while (removeCount < tabSize && removeCount < lineText.length && lineText[removeCount] == ' ') {
            removeCount++
          }
        }
        if (removeCount > 0) {
          val removed = lineText.substring(0, removeCount)
          applyLowLevel(lineStart, removeCount, "", isUndo = false, isRedo = false)
          undoManager.record(EditOp(lineStart, removed, ""), before, selectionSet)
          totalDelta -= removeCount
        }
      }
    }
    val newSels =
      before.selections.map { sel ->
        val shift = if (outdent) -min(tabSize, sel.start - getLineStartOffset(getLineAtOffset(sel.start))) else indentUnit.length
        Selection((sel.anchor + shift).coerceIn(0, length), (sel.active + shift).coerceIn(0, length))
      }
    selectionSet = SelectionSet.normalize(newSels, null, length)
    undoManager.endTransaction(selectionSet)
  }

  /** Toggles line comment prefix (e.g. `// `) on selected lines (`Ctrl+/`). */
  fun toggleLineComment(commentPrefix: String = "//") {
    val before = selectionSet
    val coveredLines = LinkedHashSet<Int>()
    for (sel in before.selections) {
      val startLine = getLineAtOffset(sel.start)
      val endLine = getLineAtOffset(sel.end)
      for (l in startLine..endLine) coveredLines.add(l)
    }
    val lines = coveredLines.sorted()
    val nonBlank = lines.map { getLineText(it) }.filter { it.isNotBlank() }
    val shouldUncomment = nonBlank.isNotEmpty() && nonBlank.all { it.trimStart().startsWith(commentPrefix) }

    undoManager.beginTransaction(before)
    for (lineIdx in lines.sortedDescending()) {
      val lineStart = getLineStartOffset(lineIdx)
      val text = getLineText(lineIdx)
      if (text.isBlank()) continue
      val firstNonSpace = text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
      if (shouldUncomment) {
        val afterPrefix = firstNonSpace + commentPrefix.length
        val removeLen =
          if (afterPrefix < text.length && text[afterPrefix] == ' ') commentPrefix.length + 1
          else commentPrefix.length
        val removed = text.substring(firstNonSpace, firstNonSpace + removeLen)
        applyLowLevel(lineStart + firstNonSpace, removeLen, "", isUndo = false, isRedo = false)
        undoManager.record(EditOp(lineStart + firstNonSpace, removed, ""), before, selectionSet)
      } else {
        val insert = "$commentPrefix "
        applyLowLevel(lineStart + firstNonSpace, 0, insert, isUndo = false, isRedo = false)
        undoManager.record(EditOp(lineStart + firstNonSpace, "", insert), before, selectionSet)
      }
    }
    selectionSet = SelectionSet.normalize(before.selections, null, length)
    undoManager.endTransaction(selectionSet)
  }

  /** Moves the current line (or selected lines) up or down (`Alt+Up` / `Alt+Down`). */
  fun moveSelectedLines(moveUp: Boolean) {
    val sel = selectionSet.primary
    val startLine = getLineAtOffset(sel.start)
    val endLine = getLineAtOffset(sel.end)
    if (moveUp && startLine == 0) return
    if (!moveUp && endLine >= lineCount - 1) return

    val before = selectionSet
    undoManager.beginTransaction(before)
    if (moveUp) {
      val aboveIdx = startLine - 1
      val aboveStart = getLineStartOffset(aboveIdx)
      val aboveText = getLineText(aboveIdx)
      val blockEnd =
        if (endLine + 1 < lineCount) getLineStartOffset(endLine + 1) - 1 else length
      val blockStart = getLineStartOffset(startLine)
      val blockText = getText(blockStart, blockEnd)
      val replacement = "$blockText\n$aboveText"
      val totalLen = blockEnd - aboveStart
      val deleted = getText(aboveStart, blockEnd)
      applyLowLevel(aboveStart, totalLen, replacement, isUndo = false, isRedo = false)
      undoManager.record(EditOp(aboveStart, deleted, replacement), before, selectionSet)
      val shift = -(aboveText.length + 1)
      selectionSet = SelectionSet.single(sel.anchor + shift, sel.active + shift)
    } else {
      val belowIdx = endLine + 1
      val blockStart = getLineStartOffset(startLine)
      val belowEnd =
        if (belowIdx + 1 < lineCount) getLineStartOffset(belowIdx + 1) - 1 else length
      val blockEnd = getLineStartOffset(belowIdx) - 1
      val blockText = getText(blockStart, blockEnd)
      val belowText = getLineText(belowIdx)
      val replacement = "$belowText\n$blockText"
      val totalLen = belowEnd - blockStart
      val deleted = getText(blockStart, belowEnd)
      applyLowLevel(blockStart, totalLen, replacement, isUndo = false, isRedo = false)
      undoManager.record(EditOp(blockStart, deleted, replacement), before, selectionSet)
      val shift = belowText.length + 1
      selectionSet = SelectionSet.single(sel.anchor + shift, sel.active + shift)
    }
    undoManager.endTransaction(selectionSet)
  }

  /** Sets or replaces the active IME composing text. */
  fun setComposingText(text: String) {
    val existing = composingRange
    if (existing != null) {
      val safeStart = existing.start.coerceIn(0, length)
      val safeEnd = existing.end.coerceIn(safeStart, length)
      applyLowLevel(safeStart, safeEnd - safeStart, text, isUndo = false, isRedo = false)
      val newEnd = safeStart + text.length
      composingRange = if (text.isEmpty()) null else TextRange(safeStart, newEnd)
      selectionSet = SelectionSet.single(newEnd)
    } else {
      val primary = selectionSet.primary
      val delLen = primary.length
      applyLowLevel(primary.start, delLen, text, isUndo = false, isRedo = false)
      val newEnd = primary.start + text.length
      composingRange = if (text.isEmpty()) null else TextRange(primary.start, newEnd)
      selectionSet = SelectionSet.single(newEnd)
    }
  }

  /** Commits any active composing text into the undo stack cleanly. */
  fun finishComposingText() {
    val comp = composingRange ?: return
    composingRange = null
    if (!comp.isEmpty) {
      val text = getText(comp.start, comp.end.coerceAtMost(length))
      undoManager.record(
        EditOp(comp.start, "", text),
        SelectionSet.single(comp.start),
        SelectionSet.single(comp.end.coerceAtMost(length)),
      )
    }
  }

  /** Sets the composing region without modifying text. */
  fun setComposingRegion(start: Int, end: Int) {
    val s = start.coerceIn(0, length)
    val e = end.coerceIn(s, length)
    composingRange = if (s == e) null else TextRange(s, e)
  }

  /** Performs undo, restoring text and selections. */
  fun undo(): Boolean {
    composingRange = null
    val group = undoManager.popUndo() ?: return false
    for (i in group.ops.indices.reversed()) {
      val op = group.ops[i]
      applyLowLevel(op.offset, op.insertedText.length, op.deletedText, isUndo = true, isRedo = false)
    }
    selectionSet = SelectionSet.normalize(group.beforeSelections.selections, group.beforeSelections.primary, length)
    return true
  }

  /** Performs redo, restoring text and selections. */
  fun redo(): Boolean {
    composingRange = null
    val group = undoManager.popRedo() ?: return false
    for (op in group.ops) {
      applyLowLevel(op.offset, op.deletedText.length, op.insertedText, isUndo = false, isRedo = true)
    }
    selectionSet = SelectionSet.normalize(group.afterSelections.selections, group.afterSelections.primary, length)
    return true
  }

  /** Simple forward substring search from [fromOffset]. */
  fun indexOf(query: String, fromOffset: Int = 0, ignoreCase: Boolean = false): Int {
    if (query.isEmpty()) return fromOffset.coerceIn(0, length)
    val qLen = query.length
    if (qLen > length) return -1
    val startLine = getLineAtOffset(fromOffset.coerceIn(0, length))
    for (line in startLine until lineCount) {
      val lineStart = getLineStartOffset(line)
      val lineText = getLineText(line)
      val searchStart = if (line == startLine) (fromOffset - lineStart).coerceAtLeast(0) else 0
      val found = lineText.indexOf(query, searchStart, ignoreCase)
      if (found >= 0) return lineStart + found
    }
    return -1
  }

  /**
   * Finds all matches for [pattern] (literal or regex) across the document.
   * Intended for background coroutine or find-bar updates.
   */
  fun findAllMatches(
    pattern: String,
    isRegex: Boolean,
    caseSensitive: Boolean,
    wholeWord: Boolean,
    maxMatches: Int = 10_000,
  ): List<TextRange> {
    if (pattern.isEmpty()) return emptyList()
    val regex =
      try {
        val expr =
          when {
            isRegex && wholeWord -> "\\b(?:$pattern)\\b"
            isRegex -> pattern
            wholeWord -> "\\b${Regex.escape(pattern)}\\b"
            else -> Regex.escape(pattern)
          }
        val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        Regex(expr, opts)
      } catch (_: Throwable) {
        return emptyList()
      }

    val results = ArrayList<TextRange>()
    val fullText = getText()
    for (m in regex.findAll(fullText)) {
      if (m.range.isEmpty()) continue
      results.add(TextRange(m.range.first, m.range.last + 1))
      if (results.size >= maxMatches) break
    }
    return results
  }

  /**
   * Finds the matching bracket (`()`, `[]`, `{}`) adjacent to [offset], or null if none.
   * Bounded to [maxScanChars] for O(1) latency on 200k-line files.
   */
  fun findMatchingBracket(offset: Int, maxScanChars: Int = 16_384): Pair<Int, Int>? {
    if (length == 0) return null
    val candidates = intArrayOf(offset, offset - 1)
    for (pos in candidates) {
      if (pos !in 0 until length) continue
      val ch = charAt(pos)
      val matchPos =
        when (ch) {
          '(' -> scanForwardBracket(pos, '(', ')', maxScanChars)
          '[' -> scanForwardBracket(pos, '[', ']', maxScanChars)
          '{' -> scanForwardBracket(pos, '{', '}', maxScanChars)
          ')' -> scanBackwardBracket(pos, '(', ')', maxScanChars)
          ']' -> scanBackwardBracket(pos, '[', ']', maxScanChars)
          '}' -> scanBackwardBracket(pos, '{', '}', maxScanChars)
          else -> -1
        }
      if (matchPos >= 0) return pos to matchPos
    }
    return null
  }

  private fun scanForwardBracket(start: Int, open: Char, close: Char, limit: Int): Int {
    var depth = 0
    val end = min(length, start + limit)
    for (i in start until end) {
      val c = charAt(i)
      if (c == open) depth++
      else if (c == close) {
        depth--
        if (depth == 0) return i
      }
    }
    return -1
  }

  private fun scanBackwardBracket(start: Int, open: Char, close: Char, limit: Int): Int {
    var depth = 0
    val stop = max(0, start - limit)
    for (i in start downTo stop) {
      val c = charAt(i)
      if (c == close) depth++
      else if (c == open) {
        depth--
        if (depth == 0) return i
      }
    }
    return -1
  }

  private fun applyLowLevel(
    offset: Int,
    deleteLen: Int,
    insertedText: String,
    isUndo: Boolean,
    isRedo: Boolean,
  ): BufferChangeEvent {
    val startLine = tree.getLineAtOffset(offset)
    val oldEndLine = tree.getLineAtOffset((offset + deleteLen).coerceAtMost(tree.length))
    if (deleteLen > 0) tree.delete(offset, deleteLen)
    if (insertedText.isNotEmpty()) tree.insert(offset, insertedText)
    val newEndLine = tree.getLineAtOffset((offset + insertedText.length).coerceAtMost(tree.length))
    version++
    val event =
      BufferChangeEvent(
        version = version,
        offset = offset,
        removedLength = deleteLen,
        insertedText = insertedText,
        startLine = startLine,
        oldEndLine = oldEndLine,
        newEndLine = newEndLine,
        isUndo = isUndo,
        isRedo = isRedo,
      )
    for (i in 0 until listeners.size) {
      listeners[i].onBufferChanged(event)
    }
    return event
  }

  private fun adjustOffsetAfterEdit(
    target: Int,
    editOffset: Int,
    deletedLen: Int,
    insertedLen: Int,
  ): Int {
    val editEnd = editOffset + deletedLen
    return when {
      target < editOffset -> target
      target >= editEnd -> target - deletedLen + insertedLen
      else -> editOffset + insertedLen
    }.coerceIn(0, length)
  }

  companion object {
    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    fun detectLineEnding(text: String): LineEnding {
      val idx = text.indexOf('\n')
      return if (idx > 0 && text[idx - 1] == '\r') LineEnding.CRLF else LineEnding.LF
    }
  }
}
