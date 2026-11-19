package com.quark.git

import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin line-level LCS diff engine generating unified diff output, structured UI diff lines,
 * and 0-based line sets for inline editor gutter indicators.
 */
object GitDiffEngine {

  private sealed class RawOp {
    data class Keep(val oldIdx: Int, val newIdx: Int, val text: String) : RawOp()
    data class Remove(val oldIdx: Int, val text: String) : RawOp()
    data class Add(val newIdx: Int, val text: String) : RawOp()
  }

  fun diffText(
    oldText: String?,
    newText: String?,
    path: String,
  ): FileDiffResult {
    val oldLines = if (oldText.isNullOrEmpty()) emptyList() else splitLines(oldText)
    val newLines = if (newText.isNullOrEmpty()) emptyList() else splitLines(newText)

    val ops = computeOps(oldLines, newLines)
    val addedLines = HashSet<Int>()
    val modifiedLines = HashSet<Int>()
    val deletedLines = HashSet<Int>()

    // Track paired remove+add runs as modified lines in gutter, standalone adds as added,
    // and standalone removes as deleted indicators on the adjacent line.
    var i = 0
    while (i < ops.size) {
      if (ops[i] is RawOp.Remove) {
        var remCount = 0
        while (i + remCount < ops.size && ops[i + remCount] is RawOp.Remove) remCount++
        var addCount = 0
        while (i + remCount + addCount < ops.size && ops[i + remCount + addCount] is RawOp.Add) addCount++

        val modPairs = min(remCount, addCount)
        for (k in 0 until modPairs) {
          val addOp = ops[i + remCount + k] as RawOp.Add
          modifiedLines.add(addOp.newIdx)
        }
        for (k in modPairs until addCount) {
          val addOp = ops[i + remCount + k] as RawOp.Add
          addedLines.add(addOp.newIdx)
        }
        if (remCount > addCount) {
          val nextKeep = ops.drop(i + remCount + addCount).firstOrNull()
          val anchorNewIdx =
            when (nextKeep) {
              is RawOp.Keep -> nextKeep.newIdx
              is RawOp.Add -> nextKeep.newIdx
              else -> (newLines.size - 1).coerceAtLeast(0)
            }
          deletedLines.add(anchorNewIdx)
        }
        i += remCount + addCount
      } else if (ops[i] is RawOp.Add) {
        addedLines.add((ops[i] as RawOp.Add).newIdx)
        i++
      } else {
        i++
      }
    }

    val uiLines = ArrayList<DiffLine>(ops.size + 4)
    val sb = StringBuilder()
    sb.append("--- a/").append(path).append('\n')
    sb.append("+++ b/").append(path).append('\n')
    uiLines.add(DiffLine(DiffLineKind.HEADER, null, null, "--- a/$path"))
    uiLines.add(DiffLine(DiffLineKind.HEADER, null, null, "+++ b/$path"))

    val changedIndices = ops.indices.filter { ops[it] !is RawOp.Keep }
    if (changedIndices.isNotEmpty()) {
      // Group changed indices into hunks with 3 lines of context
      val context = 3
      var groupStart = (changedIndices.first() - context).coerceAtLeast(0)
      var idxInChanged = 0
      while (idxInChanged < changedIndices.size) {
        var groupEnd = (changedIndices[idxInChanged] + context).coerceAtMost(ops.lastIndex)
        while (
          idxInChanged + 1 < changedIndices.size &&
          changedIndices[idxInChanged + 1] - context <= groupEnd + 1
        ) {
          idxInChanged++
          groupEnd = (changedIndices[idxInChanged] + context).coerceAtMost(ops.lastIndex)
        }

        val hunkOps = ops.subList(groupStart, groupEnd + 1)
        val firstOld = hunkOps.firstNotNullOfOrNull {
          when (it) {
            is RawOp.Keep -> it.oldIdx + 1
            is RawOp.Remove -> it.oldIdx + 1
            is RawOp.Add -> null
          }
        } ?: 0
        val firstNew = hunkOps.firstNotNullOfOrNull {
          when (it) {
            is RawOp.Keep -> it.newIdx + 1
            is RawOp.Add -> it.newIdx + 1
            is RawOp.Remove -> null
          }
        } ?: 0
        val oldCount = hunkOps.count { it is RawOp.Keep || it is RawOp.Remove }
        val newCount = hunkOps.count { it is RawOp.Keep || it is RawOp.Add }

        val hdr = "@@ -$firstOld,$oldCount +$firstNew,$newCount @@"
        uiLines.add(DiffLine(DiffLineKind.HEADER, null, null, hdr))
        sb.append(hdr).append('\n')

        for (op in hunkOps) {
          when (op) {
            is RawOp.Keep -> {
              uiLines.add(DiffLine(DiffLineKind.CONTEXT, op.oldIdx + 1, op.newIdx + 1, op.text))
              sb.append(' ').append(op.text).append('\n')
            }
            is RawOp.Remove -> {
              uiLines.add(DiffLine(DiffLineKind.REMOVED, op.oldIdx + 1, null, op.text))
              sb.append('-').append(op.text).append('\n')
            }
            is RawOp.Add -> {
              uiLines.add(DiffLine(DiffLineKind.ADDED, null, op.newIdx + 1, op.text))
              sb.append('+').append(op.text).append('\n')
            }
          }
        }

        idxInChanged++
        if (idxInChanged < changedIndices.size) {
          groupStart = (changedIndices[idxInChanged] - context).coerceAtLeast(groupEnd + 1)
        }
      }
    }

    return FileDiffResult(
      path = path,
      lines = uiLines,
      unifiedText = sb.toString(),
      addedLines0Based = addedLines,
      modifiedLines0Based = modifiedLines,
      deletedLines0Based = deletedLines,
    )
  }

  private fun computeOps(oldLines: List<String>, newLines: List<String>): List<RawOp> {
    val n = oldLines.size
    val m = newLines.size
    // Trim common prefix & suffix first for fast O(N) common case
    var prefix = 0
    val minLen = min(n, m)
    while (prefix < minLen && oldLines[prefix] == newLines[prefix]) prefix++
    var suffix = 0
    while (suffix < minLen - prefix && oldLines[n - 1 - suffix] == newLines[m - 1 - suffix]) suffix++

    val ops = ArrayList<RawOp>(max(n, m) + 8)
    for (p in 0 until prefix) {
      ops.add(RawOp.Keep(p, p, oldLines[p]))
    }

    val midOldLen = n - prefix - suffix
    val midNewLen = m - prefix - suffix

    if (midOldLen == 0 && midNewLen == 0) {
      // identical files
    } else if (midOldLen.toLong() * midNewLen.toLong() <= 350_000L) {
      // Standard DP LCS on the changed middle region
      val cols = midNewLen + 1
      val dp = IntArray((midOldLen + 1) * cols)
      for (i in midOldLen - 1 downTo 0) {
        val rowOff = i * cols
        val nextRowOff = (i + 1) * cols
        val aLine = oldLines[prefix + i]
        for (j in midNewLen - 1 downTo 0) {
          dp[rowOff + j] =
            if (aLine == newLines[prefix + j]) {
              1 + dp[nextRowOff + j + 1]
            } else {
              max(dp[nextRowOff + j], dp[rowOff + j + 1])
            }
        }
      }
      var i = 0
      var j = 0
      while (i < midOldLen && j < midNewLen) {
        if (oldLines[prefix + i] == newLines[prefix + j]) {
          ops.add(RawOp.Keep(prefix + i, prefix + j, oldLines[prefix + i]))
          i++
          j++
        } else if (dp[(i + 1) * cols + j] >= dp[i * cols + j + 1]) {
          ops.add(RawOp.Remove(prefix + i, oldLines[prefix + i]))
          i++
        } else {
          ops.add(RawOp.Add(prefix + j, newLines[prefix + j]))
          j++
        }
      }
      while (i < midOldLen) {
        ops.add(RawOp.Remove(prefix + i, oldLines[prefix + i]))
        i++
      }
      while (j < midNewLen) {
        ops.add(RawOp.Add(prefix + j, newLines[prefix + j]))
        j++
      }
    } else {
      // Fallback for huge middle hunks
      for (i in 0 until midOldLen) ops.add(RawOp.Remove(prefix + i, oldLines[prefix + i]))
      for (j in 0 until midNewLen) ops.add(RawOp.Add(prefix + j, newLines[prefix + j]))
    }

    for (s in 0 until suffix) {
      val oi = n - suffix + s
      val ni = m - suffix + s
      ops.add(RawOp.Keep(oi, ni, oldLines[oi]))
    }
    return ops
  }

  private fun splitLines(text: String): List<String> =
    text.replace("\r\n", "\n").split('\n').let {
      if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it
    }
}
