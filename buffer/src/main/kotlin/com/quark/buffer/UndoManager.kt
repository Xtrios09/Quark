package com.quark.buffer

/**
 * Single atomic text edit operation recorded for undo/redo.
 */
data class EditOp(
  val offset: Int,
  val deletedText: String,
  val insertedText: String,
)

/**
 * Grouped undo transaction containing one or more [EditOp]s applied in order,
 * along with pre- and post-edit [SelectionSet]s.
 */
data class UndoGroup(
  val ops: List<EditOp>,
  val beforeSelections: SelectionSet,
  val afterSelections: SelectionSet,
  val timestampMs: Long,
  val isSingleCharInsert: Boolean = false,
  val isSingleCharDelete: Boolean = false,
)

/**
 * Manages grouped undo/redo history with automatic typing/backspace coalescing
 * and explicit transaction brackets for multi-cursor or compound commands.
 */
class UndoManager(private val maxHistory: Int = 500) {
  private val undoStack = ArrayDeque<UndoGroup>()
  private val redoStack = ArrayDeque<UndoGroup>()

  private var transactionDepth = 0
  private val pendingOps = ArrayList<EditOp>()
  private var transactionStartSelections: SelectionSet? = null

  val canUndo: Boolean get() = undoStack.isNotEmpty()
  val canRedo: Boolean get() = redoStack.isNotEmpty()

  /** Starts an explicit compound edit transaction. Supports nesting. */
  fun beginTransaction(currentSelections: SelectionSet) {
    if (transactionDepth == 0) {
      pendingOps.clear()
      transactionStartSelections = currentSelections
    }
    transactionDepth++
  }

  /** Commits an explicit compound edit transaction if outermost. */
  fun endTransaction(afterSelections: SelectionSet, nowMs: Long = System.currentTimeMillis()) {
    if (transactionDepth <= 0) return
    transactionDepth--
    if (transactionDepth == 0) {
      if (pendingOps.isNotEmpty()) {
        val group =
          UndoGroup(
            ops = pendingOps.toList(),
            beforeSelections = transactionStartSelections ?: afterSelections,
            afterSelections = afterSelections,
            timestampMs = nowMs,
          )
        pushUndoGroup(group)
      }
      pendingOps.clear()
      transactionStartSelections = null
    }
  }

  /** Records an atomic [op] either into the active transaction or as a coalescible group. */
  fun record(
    op: EditOp,
    beforeSelections: SelectionSet,
    afterSelections: SelectionSet,
    nowMs: Long = System.currentTimeMillis(),
  ) {
    redoStack.clear()
    if (transactionDepth > 0) {
      pendingOps.add(op)
      return
    }

    val isCharInsert =
      op.deletedText.isEmpty() &&
        op.insertedText.length == 1 &&
        op.insertedText[0] != '\n' &&
        !op.insertedText[0].isWhitespace()

    val isCharDelete =
      op.insertedText.isEmpty() &&
        op.deletedText.length == 1 &&
        op.deletedText[0] != '\n'

    val prev = undoStack.lastOrNull()
    if (prev != null && nowMs - prev.timestampMs <= COALESCE_WINDOW_MS) {
      if (isCharInsert && prev.isSingleCharInsert && prev.ops.size == 1) {
        val prevOp = prev.ops[0]
        if (op.offset == prevOp.offset + prevOp.insertedText.length) {
          undoStack.removeLast()
          undoStack.addLast(
            prev.copy(
              ops = listOf(prevOp.copy(insertedText = prevOp.insertedText + op.insertedText)),
              afterSelections = afterSelections,
              timestampMs = nowMs,
            )
          )
          return
        }
      }
      if (isCharDelete && prev.isSingleCharDelete && prev.ops.size == 1) {
        val prevOp = prev.ops[0]
        // Consecutive backspace
        if (op.offset + 1 == prevOp.offset) {
          undoStack.removeLast()
          undoStack.addLast(
            prev.copy(
              ops =
                listOf(
                  prevOp.copy(offset = op.offset, deletedText = op.deletedText + prevOp.deletedText)
                ),
              afterSelections = afterSelections,
              timestampMs = nowMs,
            )
          )
          return
        }
        // Consecutive forward delete at same offset
        if (op.offset == prevOp.offset) {
          undoStack.removeLast()
          undoStack.addLast(
            prev.copy(
              ops = listOf(prevOp.copy(deletedText = prevOp.deletedText + op.deletedText)),
              afterSelections = afterSelections,
              timestampMs = nowMs,
            )
          )
          return
        }
      }
    }

    pushUndoGroup(
      UndoGroup(
        ops = listOf(op),
        beforeSelections = beforeSelections,
        afterSelections = afterSelections,
        timestampMs = nowMs,
        isSingleCharInsert = isCharInsert,
        isSingleCharDelete = isCharDelete,
      )
    )
  }

  private fun pushUndoGroup(group: UndoGroup) {
    undoStack.addLast(group)
    while (undoStack.size > maxHistory) {
      undoStack.removeFirst()
    }
  }

  /** Pops the most recent group for undo and pushes it onto the redo stack. */
  fun popUndo(): UndoGroup? {
    val group = undoStack.removeLastOrNull() ?: return null
    redoStack.addLast(group)
    return group
  }

  /** Pops the most recent group from redo and pushes it back onto the undo stack. */
  fun popRedo(): UndoGroup? {
    val group = redoStack.removeLastOrNull() ?: return null
    undoStack.addLast(group)
    return group
  }

  /** Clears all undo and redo history. */
  fun clear() {
    undoStack.clear()
    redoStack.clear()
    pendingOps.clear()
    transactionDepth = 0
    transactionStartSelections = null
  }

  private companion object {
    const val COALESCE_WINDOW_MS = 650L
  }
}
