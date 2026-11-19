package com.quark.buffer

import kotlin.math.max
import kotlin.math.min

/**
 * Immutable cursor/selection span defined by [anchor] and [active] offsets.
 * When [anchor] == [active], this represents a collapsed caret.
 */
data class Selection(
  val anchor: Int,
  val active: Int,
) {
  val start: Int get() = min(anchor, active)
  val end: Int get() = max(anchor, active)
  val isEmpty: Boolean get() = anchor == active
  val isReversed: Boolean get() = active < anchor
  val length: Int get() = end - start

  fun clamped(maxOffset: Int): Selection =
    Selection(anchor.coerceIn(0, maxOffset), active.coerceIn(0, maxOffset))

  companion object {
    fun caret(offset: Int): Selection = Selection(offset, offset)
  }
}

/**
 * Normalized, sorted, non-overlapping set of multi-cursor [Selection]s.
 */
data class SelectionSet(
  val selections: List<Selection>,
  val primaryIndex: Int = 0,
) {
  init {
    require(selections.isNotEmpty()) { "SelectionSet must contain at least one selection" }
  }

  val primary: Selection
    get() = selections[primaryIndex.coerceIn(0, selections.lastIndex)]

  val isMultiCursor: Boolean
    get() = selections.size > 1

  companion object {
    fun single(offset: Int): SelectionSet = SelectionSet(listOf(Selection.caret(offset)), 0)

    fun single(anchor: Int, active: Int): SelectionSet =
      SelectionSet(listOf(Selection(anchor, active)), 0)

    /**
     * Sorts [raw] selections and merges any overlapping or touching non-empty ranges,
     * preserving the primary selection as closely as possible.
     */
    fun normalize(
      raw: List<Selection>,
      preferredPrimary: Selection? = null,
      maxLength: Int = Int.MAX_VALUE,
    ): SelectionSet {
      if (raw.isEmpty()) return single(0)
      val clamped = raw.map { it.clamped(maxLength) }.sortedWith(compareBy({ it.start }, { it.end }))
      val merged = ArrayList<Selection>(clamped.size)
      for (sel in clamped) {
        if (merged.isEmpty()) {
          merged.add(sel)
          continue
        }
        val prev = merged.last()
        val overlaps =
          if (prev.isEmpty && sel.isEmpty) {
            prev.start == sel.start
          } else {
            sel.start < prev.end || (sel.start == prev.end && (prev.isEmpty || sel.isEmpty))
          }
        if (overlaps) {
          val newStart = min(prev.start, sel.start)
          val newEnd = max(prev.end, sel.end)
          val reversed = if (sel == preferredPrimary) sel.isReversed else prev.isReversed
          merged[merged.lastIndex] =
            if (reversed) Selection(newEnd, newStart) else Selection(newStart, newEnd)
        } else {
          merged.add(sel)
        }
      }
      val primaryIdx =
        if (preferredPrimary != null) {
          merged.indexOfFirst {
            it == preferredPrimary ||
              (preferredPrimary.active in it.start..it.end)
          }.coerceAtLeast(0)
        } else {
          0
        }
      return SelectionSet(merged, primaryIdx)
    }
  }
}
