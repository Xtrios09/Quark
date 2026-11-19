package com.quark.editor

import com.quark.buffer.TextBuffer

/**
 * Manages foldable code blocks and maps between visible display line indices and underlying
 * document line indices with O(1) array lookup.
 */
class FoldManager {
  private val regions = ArrayList<FoldRegion>()
  private val collapsedStarts = HashSet<Int>()

  // Mapping from visible line index -> document line index.
  private var visibleToDoc: IntArray = IntArray(0)
  private var docToVisible: IntArray = IntArray(0)
  var visibleLineCount: Int = 0
    private set

  /** Replaces active fold regions (preserving collapsed state on matching start lines). */
  fun setRegions(newRegions: List<FoldRegion>, docLineCount: Int) {
    regions.clear()
    for (r in newRegions) {
      if (r.endLine > r.startLine) {
        regions.add(r.copy(isCollapsed = collapsedStarts.contains(r.startLine)))
      }
    }
    regions.sortBy { it.startLine }
    rebuildMapping(docLineCount)
  }

  /**
   * Computes indent-based fold regions for [buffer] (used out-of-the-box before syntax AST folds
   * are provided by `:lang`).
   */
  fun recomputeIndentFolds(buffer: TextBuffer, maxLinesToScan: Int = 20_000) {
    val count = buffer.lineCount.coerceAtMost(maxLinesToScan)
    val indents = IntArray(count)
    val nonBlank = BooleanArray(count)
    for (i in 0 until count) {
      val text = buffer.getLineText(i)
      var indent = 0
      var blank = true
      for (c in text) {
        if (c == ' ') indent++
        else if (c == '\t') indent += 4
        else {
          blank = false
          break
        }
      }
      indents[i] = indent
      nonBlank[i] = !blank
    }

    val detected = ArrayList<FoldRegion>()
    val stackLine = IntArray(256)
    val stackIndent = IntArray(256)
    var sp = 0

    for (i in 0 until count) {
      if (!nonBlank[i]) continue
      val ind = indents[i]
      while (sp > 0 && stackIndent[sp - 1] >= ind) {
        sp--
        val startLine = stackLine[sp]
        var endLine = i - 1
        while (endLine > startLine && !nonBlank[endLine]) endLine--
        if (endLine > startLine) {
          detected.add(FoldRegion(startLine, endLine, collapsedStarts.contains(startLine)))
        }
      }
      if (sp < stackLine.size) {
        stackLine[sp] = i
        stackIndent[sp] = ind
        sp++
      }
    }
    while (sp > 0) {
      sp--
      val startLine = stackLine[sp]
      var endLine = count - 1
      while (endLine > startLine && !nonBlank[endLine]) endLine--
      if (endLine > startLine) {
        detected.add(FoldRegion(startLine, endLine, collapsedStarts.contains(startLine)))
      }
    }
    setRegions(detected, buffer.lineCount)
  }

  fun rebuildMapping(docLineCount: Int) {
    if (docLineCount <= 0) {
      visibleToDoc = IntArray(0)
      docToVisible = IntArray(0)
      visibleLineCount = 0
      return
    }
    if (collapsedStarts.isEmpty()) {
      visibleLineCount = docLineCount
      visibleToDoc = IntArray(0)
      docToVisible = IntArray(0)
      return
    }
    val hidden = BooleanArray(docLineCount)
    for (r in regions) {
      if (r.isCollapsed && r.startLine < docLineCount) {
        val end = r.endLine.coerceAtMost(docLineCount - 1)
        for (l in (r.startLine + 1)..end) {
          hidden[l] = true
        }
      }
    }
    val vis = IntArray(docLineCount)
    val d2v = IntArray(docLineCount)
    var vIdx = 0
    for (d in 0 until docLineCount) {
      if (!hidden[d]) {
        vis[vIdx] = d
        d2v[d] = vIdx
        vIdx++
      } else {
        d2v[d] = (vIdx - 1).coerceAtLeast(0)
      }
    }
    visibleToDoc = vis
    docToVisible = d2v
    visibleLineCount = vIdx
  }

  /** Toggles collapsed state for a fold starting on [docLine]. Returns true if toggled. */
  fun toggleFoldAt(docLine: Int, docLineCount: Int): Boolean {
    val idx = regions.indexOfFirst { it.startLine == docLine }
    if (idx < 0) return false
    val r = regions[idx]
    val nextCollapsed = !r.isCollapsed
    if (nextCollapsed) collapsedStarts.add(docLine) else collapsedStarts.remove(docLine)
    regions[idx] = r.copy(isCollapsed = nextCollapsed)
    rebuildMapping(docLineCount)
    return true
  }

  fun regionAt(docLine: Int): FoldRegion? {
    var lo = 0
    var hi = regions.lastIndex
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val r = regions[mid]
      when {
        r.startLine == docLine -> return r
        r.startLine < docLine -> lo = mid + 1
        else -> hi = mid - 1
      }
    }
    return null
  }

  fun isCollapsed(docLine: Int): Boolean = collapsedStarts.contains(docLine)

  fun docLineForVisible(visibleLine: Int, totalDocLines: Int): Int {
    if (collapsedStarts.isEmpty()) return visibleLine.coerceIn(0, (totalDocLines - 1).coerceAtLeast(0))
    if (visibleLineCount == 0) return 0
    return visibleToDoc[visibleLine.coerceIn(0, visibleLineCount - 1)]
  }

  fun visibleLineForDoc(docLine: Int): Int {
    if (collapsedStarts.isEmpty()) return docLine.coerceAtLeast(0)
    if (docToVisible.isEmpty()) return docLine
    return docToVisible[docLine.coerceIn(0, docToVisible.lastIndex)]
  }

  fun getVisibleCount(totalDocLines: Int): Int =
    if (collapsedStarts.isEmpty()) totalDocLines else visibleLineCount

  fun expandAll(docLineCount: Int) {
    collapsedStarts.clear()
    for (i in regions.indices) {
      regions[i] = regions[i].copy(isCollapsed = false)
    }
    rebuildMapping(docLineCount)
  }
}
