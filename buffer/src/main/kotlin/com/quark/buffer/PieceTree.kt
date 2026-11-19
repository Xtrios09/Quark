package com.quark.buffer

import kotlin.math.min

/**
 * High-performance Red-Black Piece Tree text storage with per-chunk line-start indices.
 *
 * Supports O(log P) insert/delete (where P is piece count) and fast O(log P) line lookup
 * with zero heap allocations on read/render paths via [copyChars] and [getLineBoundsPacked].
 */
class PieceTree(initialText: String = "") {

  private class SourceBuffer(
    var data: CharArray,
    var length: Int,
    // Relative indices in [data] where a '\n' appears. Strictly ascending.
    var lineFeeds: IntArray,
    var lineFeedCount: Int,
  ) {
    fun append(text: String): Int {
      val start = length
      val needed = start + text.length
      if (needed > data.size) {
        var newCap = data.size.coerceAtLeast(1024)
        while (newCap < needed) newCap *= 2
        data = data.copyOf(newCap)
      }
      for (i in 0 until text.length) {
        val ch = text[i]
        data[start + i] = ch
        if (ch == '\n') {
          if (lineFeedCount == lineFeeds.size) {
            lineFeeds = lineFeeds.copyOf((lineFeeds.size * 2).coerceAtLeast(64))
          }
          lineFeeds[lineFeedCount++] = start + i
        }
      }
      length = needed
      return start
    }

    /** Counts '\n' characters strictly inside [start, start + len). */
    fun countLineFeedsInRange(start: Int, len: Int): Int {
      if (len <= 0 || lineFeedCount == 0) return 0
      val end = start + len
      val firstIdx = lowerBound(lineFeeds, lineFeedCount, start)
      if (firstIdx >= lineFeedCount) return 0
      val lastIdx = lowerBound(lineFeeds, lineFeedCount, end)
      return (lastIdx - firstIdx).coerceAtLeast(0)
    }

    /**
     * Returns the buffer-relative offset immediately AFTER the [kthNewline] (0-based)
     * inside [start, start + len).
     */
    fun getLineStartAfterNthNewline(start: Int, len: Int, kthNewline: Int): Int {
      val firstIdx = lowerBound(lineFeeds, lineFeedCount, start)
      return lineFeeds[firstIdx + kthNewline] + 1
    }
  }

  private data class Piece(
    val bufferIndex: Int,
    val start: Int,
    val length: Int,
    val lfCount: Int,
  )

  private class Node(
    var piece: Piece,
    var color: Boolean,
    left: Node?,
    right: Node?,
    parent: Node?,
    var subtreeSize: Int,
    var subtreeLf: Int,
  ) {
    var left: Node = left ?: this
    var right: Node = right ?: this
    var parent: Node = parent ?: this
  }

  private class NodePosition(
    var node: Node = SENTINEL,
    var remainder: Int = 0,
    var nodeStartOffset: Int = 0,
  )

  private val buffers = ArrayList<SourceBuffer>(4)
  private val addBuffer: SourceBuffer
  private val addBufferIndex: Int

  private var root: Node = SENTINEL
  private val scratchPos = NodePosition()

  /** Total UTF-16 character count in the document. */
  var length: Int = 0
    private set

  /** Total line count in the document (always >= 1). */
  var lineCount: Int = 1
    private set

  init {
    if (initialText.isNotEmpty()) {
      // Split large initial text into 64 KB chunks so initial tree depth stays balanced and
      // chunks never hold giant monolithic arrays after edits.
      val chunkSize = 65536
      var offset = 0
      while (offset < initialText.length) {
        val end = min(offset + chunkSize, initialText.length)
        val chunkLen = end - offset
        val chars = CharArray(chunkLen)
        var lfCap = (chunkLen / 32).coerceAtLeast(16)
        var lfs = IntArray(lfCap)
        var lfCount = 0
        for (i in 0 until chunkLen) {
          val c = initialText[offset + i]
          chars[i] = c
          if (c == '\n') {
            if (lfCount == lfs.size) {
              lfCap *= 2
              lfs = lfs.copyOf(lfCap)
            }
            lfs[lfCount++] = i
          }
        }
        val bufIdx = buffers.size
        buffers.add(SourceBuffer(chars, chunkLen, lfs, lfCount))
        insertNodeAtEnd(Piece(bufIdx, 0, chunkLen, lfCount))
        offset = end
      }
    }
    addBuffer = SourceBuffer(CharArray(4096), 0, IntArray(128), 0)
    addBufferIndex = buffers.size
    buffers.add(addBuffer)
    recomputeTotals()
  }

  private fun recomputeTotals() {
    length = root.subtreeSize
    lineCount = root.subtreeLf + 1
  }

  /** Inserts [text] at character [offset]. */
  fun insert(offset: Int, text: String) {
    if (text.isEmpty()) return
    require(offset in 0..length) { "Insert offset $offset out of bounds 0..$length" }

    val bufStart = addBuffer.append(text)
    val lfCount = addBuffer.countLineFeedsInRange(bufStart, text.length)
    val newPiece = Piece(addBufferIndex, bufStart, text.length, lfCount)

    if (root === SENTINEL) {
      insertNodeAtEnd(newPiece)
      recomputeTotals()
      return
    }

    findNodeAtOffset(offset, scratchPos)
    val node = scratchPos.node
    val remainder = scratchPos.remainder

    if (node === SENTINEL) {
      insertNodeAtEnd(newPiece)
      recomputeTotals()
      return
    }

    val p = node.piece
    // Fast coalesce if inserting right at the end of the most recent append piece in addBuffer
    if (
      remainder == p.length &&
        p.bufferIndex == addBufferIndex &&
        p.start + p.length == bufStart
    ) {
      node.piece = Piece(addBufferIndex, p.start, p.length + text.length, p.lfCount + lfCount)
      pullUp(node)
      recomputeTotals()
      return
    }

    // Fast coalesce if inserting right after the previous node's end when remainder == 0
    if (remainder == 0) {
      val prev = predecessor(node)
      if (
        prev !== SENTINEL &&
          prev.piece.bufferIndex == addBufferIndex &&
          prev.piece.start + prev.piece.length == bufStart
      ) {
        val pp = prev.piece
        prev.piece = Piece(addBufferIndex, pp.start, pp.length + text.length, pp.lfCount + lfCount)
        pullUp(prev)
        recomputeTotals()
        return
      }
      insertBefore(node, newPiece)
      recomputeTotals()
      return
    }

    if (remainder == p.length) {
      insertAfter(node, newPiece)
      recomputeTotals()
      return
    }

    // Split existing piece into [leftPiece][newPiece][rightPiece]
    val leftLen = remainder
    val rightLen = p.length - remainder
    val src = buffers[p.bufferIndex]
    val leftLf = src.countLineFeedsInRange(p.start, leftLen)
    val rightLf = p.lfCount - leftLf

    node.piece = Piece(p.bufferIndex, p.start, leftLen, leftLf)
    pullUp(node)
    val rightNode = insertAfter(node, Piece(p.bufferIndex, p.start + leftLen, rightLen, rightLf))
    insertBefore(rightNode, newPiece)
    recomputeTotals()
  }

  /** Deletes [deleteLength] characters starting at [offset]. */
  fun delete(offset: Int, deleteLength: Int) {
    if (deleteLength <= 0) return
    require(offset >= 0 && offset + deleteLength <= length) {
      "Delete [$offset, ${offset + deleteLength}) out of bounds 0..$length"
    }

    if (offset == 0 && deleteLength == length) {
      root = SENTINEL
      recomputeTotals()
      return
    }

    findNodeAtOffset(offset, scratchPos)
    val startNode = scratchPos.node
    val startRem = scratchPos.remainder

    findNodeAtOffset(offset + deleteLength, scratchPos)
    val endNode = scratchPos.node
    val endRem = scratchPos.remainder

    if (startNode === endNode) {
      val p = startNode.piece
      val src = buffers[p.bufferIndex]
      if (startRem == 0 && endRem == p.length) {
        deleteNode(startNode)
      } else if (startRem == 0) {
        val remLen = p.length - endRem
        val remLf = src.countLineFeedsInRange(p.start + endRem, remLen)
        startNode.piece = Piece(p.bufferIndex, p.start + endRem, remLen, remLf)
        pullUp(startNode)
      } else if (endRem == p.length) {
        val remLen = startRem
        val remLf = src.countLineFeedsInRange(p.start, remLen)
        startNode.piece = Piece(p.bufferIndex, p.start, remLen, remLf)
        pullUp(startNode)
      } else {
        // Middle cut inside single piece
        val leftLf = src.countLineFeedsInRange(p.start, startRem)
        val rightLen = p.length - endRem
        val rightLf = src.countLineFeedsInRange(p.start + endRem, rightLen)
        startNode.piece = Piece(p.bufferIndex, p.start, startRem, leftLf)
        pullUp(startNode)
        insertAfter(startNode, Piece(p.bufferIndex, p.start + endRem, rightLen, rightLf))
      }
      recomputeTotals()
      return
    }

    // Multi-node delete
    val nodesToDelete = ArrayList<Node>(8)
    var cur =
      if (startRem == 0) {
        startNode
      } else {
        val sp = startNode.piece
        val src = buffers[sp.bufferIndex]
        val keepLf = src.countLineFeedsInRange(sp.start, startRem)
        startNode.piece = Piece(sp.bufferIndex, sp.start, startRem, keepLf)
        pullUp(startNode)
        successor(startNode)
      }

    while (cur !== SENTINEL && cur !== endNode) {
      nodesToDelete.add(cur)
      cur = successor(cur)
    }

    if (endNode !== SENTINEL) {
      val ep = endNode.piece
      if (endRem == ep.length) {
        nodesToDelete.add(endNode)
      } else if (endRem > 0) {
        val src = buffers[ep.bufferIndex]
        val keepLen = ep.length - endRem
        val keepLf = src.countLineFeedsInRange(ep.start + endRem, keepLen)
        endNode.piece = Piece(ep.bufferIndex, ep.start + endRem, keepLen, keepLf)
        pullUp(endNode)
      }
    }

    for (i in 0 until nodesToDelete.size) {
      deleteNode(nodesToDelete[i])
    }
    recomputeTotals()
  }

  /** Returns the character at [offset]. */
  fun charAt(offset: Int): Char {
    require(offset in 0 until length) { "Offset $offset out of bounds 0..<$length" }
    findNodeAtOffset(offset, scratchPos)
    val node = scratchPos.node
    val rem = scratchPos.remainder
    if (rem == node.piece.length) {
      val next = successor(node)
      return buffers[next.piece.bufferIndex].data[next.piece.start]
    }
    return buffers[node.piece.bufferIndex].data[node.piece.start + rem]
  }

  /**
   * Copies [copyLen] characters starting at [offset] into [dst] starting at [dstOffset].
   * Performs zero heap allocations.
   */
  fun copyChars(offset: Int, copyLen: Int, dst: CharArray, dstOffset: Int = 0) {
    if (copyLen <= 0) return
    require(offset >= 0 && offset + copyLen <= length)
    findNodeAtOffset(offset, scratchPos)
    var node = scratchPos.node
    var rem = scratchPos.remainder
    if (rem == node.piece.length) {
      node = successor(node)
      rem = 0
    }
    var remaining = copyLen
    var outIdx = dstOffset
    while (remaining > 0 && node !== SENTINEL) {
      val p = node.piece
      val avail = p.length - rem
      val take = min(remaining, avail)
      val srcData = buffers[p.bufferIndex].data
      System.arraycopy(srcData, p.start + rem, dst, outIdx, take)
      outIdx += take
      remaining -= take
      rem = 0
      node = successor(node)
    }
  }

  /** Returns a substring `[start, end)`. */
  fun getText(start: Int = 0, end: Int = length): String {
    val safeStart = start.coerceIn(0, length)
    val safeEnd = end.coerceIn(safeStart, length)
    val len = safeEnd - safeStart
    if (len == 0) return ""
    val chars = CharArray(len)
    copyChars(safeStart, len, chars, 0)
    return String(chars)
  }

  /**
   * Returns the start offset of zero-based [lineIndex].
   */
  fun getLineStartOffset(lineIndex: Int): Int {
    if (lineIndex <= 0) return 0
    if (lineIndex >= lineCount) return length
    var targetLf = lineIndex - 1 // 0-based index of the '\n' preceding this line
    var node = root
    var accOffset = 0
    while (node !== SENTINEL) {
      val leftLf = node.left.subtreeLf
      if (targetLf < leftLf) {
        node = node.left
      } else if (targetLf < leftLf + node.piece.lfCount) {
        val localLfIdx = targetLf - leftLf
        val p = node.piece
        val absOffsetInBuf = buffers[p.bufferIndex].getLineStartAfterNthNewline(p.start, p.length, localLfIdx)
        return accOffset + node.left.subtreeSize + (absOffsetInBuf - p.start)
      } else {
        targetLf -= (leftLf + node.piece.lfCount)
        accOffset += node.left.subtreeSize + node.piece.length
        node = node.right
      }
    }
    return length
  }

  /**
   * Returns packed `(startOffset shl 32) or (endOffsetExcludingNewline and 0xFFFFFFFFL)`
   * for zero-based [lineIndex] without any heap allocation.
   * Strips trailing `\n` and optional preceding `\r`.
   */
  fun getLineBoundsPacked(lineIndex: Int): Long {
    val clamped = lineIndex.coerceIn(0, lineCount - 1)
    val start = getLineStartOffset(clamped)
    var end = if (clamped + 1 < lineCount) getLineStartOffset(clamped + 1) - 1 else length
    if (end > start && charAt(end - 1) == '\r') {
      end--
    }
    return (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)
  }

  /** Returns the line text (without trailing line ending) for [lineIndex]. */
  fun getLineText(lineIndex: Int): String {
    val packed = getLineBoundsPacked(lineIndex)
    val start = (packed ushr 32).toInt()
    val end = packed.toInt()
    return getText(start, end)
  }

  /** Returns the zero-based line index containing character [offset]. */
  fun getLineAtOffset(offset: Int): Int {
    val safeOffset = offset.coerceIn(0, length)
    if (safeOffset == 0 || root === SENTINEL) return 0
    var remaining = safeOffset
    var node = root
    var line = 0
    while (node !== SENTINEL) {
      val leftSize = node.left.subtreeSize
      if (remaining < leftSize) {
        node = node.left
      } else if (remaining <= leftSize + node.piece.length) {
        val inPiece = remaining - leftSize
        line += node.left.subtreeLf
        if (inPiece > 0) {
          val p = node.piece
          line += buffers[p.bufferIndex].countLineFeedsInRange(p.start, inPiece)
        }
        return line
      } else {
        remaining -= (leftSize + node.piece.length)
        line += node.left.subtreeLf + node.piece.lfCount
        node = node.right
      }
    }
    return (lineCount - 1).coerceAtLeast(0)
  }

  /** Converts a character [offset] to [TextPosition]. */
  fun offsetToPosition(offset: Int): TextPosition {
    val safe = offset.coerceIn(0, length)
    val line = getLineAtOffset(safe)
    val lineStart = getLineStartOffset(line)
    val packed = getLineBoundsPacked(line)
    val lineEnd = packed.toInt()
    val col = (safe - lineStart).coerceIn(0, (lineEnd - lineStart).coerceAtLeast(0))
    return TextPosition(line, col)
  }

  /** Converts [line] and [column] into a clamped character offset. */
  fun positionToOffset(line: Int, column: Int): Int {
    val clampedLine = line.coerceIn(0, lineCount - 1)
    val packed = getLineBoundsPacked(clampedLine)
    val start = (packed ushr 32).toInt()
    val end = packed.toInt()
    return (start + column.coerceAtLeast(0)).coerceAtMost(end)
  }

  // --- Red-Black Tree Helpers ---

  private fun findNodeAtOffset(offset: Int, out: NodePosition) {
    if (root === SENTINEL) {
      out.node = SENTINEL
      out.remainder = 0
      out.nodeStartOffset = 0
      return
    }
    var rem = offset
    var node = root
    var nodeStart = 0
    while (node !== SENTINEL) {
      val leftSize = node.left.subtreeSize
      if (rem < leftSize) {
        node = node.left
      } else if (rem <= leftSize + node.piece.length) {
        // When rem == leftSize and left subtree exists, prefer previous end unless rem == 0
        if (rem == leftSize && node.left !== SENTINEL) {
          val prev = maximum(node.left)
          out.node = prev
          out.remainder = prev.piece.length
          out.nodeStartOffset = nodeStart + leftSize - prev.piece.length
          return
        }
        out.node = node
        out.remainder = rem - leftSize
        out.nodeStartOffset = nodeStart + leftSize
        return
      } else {
        rem -= (leftSize + node.piece.length)
        nodeStart += leftSize + node.piece.length
        node = node.right
      }
    }
    out.node = SENTINEL
    out.remainder = 0
    out.nodeStartOffset = length
  }

  private fun insertNodeAtEnd(piece: Piece): Node {
    val z = Node(piece, RED, SENTINEL, SENTINEL, SENTINEL, piece.length, piece.lfCount)
    if (root === SENTINEL) {
      z.color = BLACK
      root = z
      return z
    }
    val maxNode = maximum(root)
    maxNode.right = z
    z.parent = maxNode
    pullUp(maxNode)
    fixInsert(z)
    return z
  }

  private fun insertAfter(target: Node, piece: Piece): Node {
    val z = Node(piece, RED, SENTINEL, SENTINEL, SENTINEL, piece.length, piece.lfCount)
    if (target.right === SENTINEL) {
      target.right = z
      z.parent = target
      pullUp(target)
    } else {
      val next = minimum(target.right)
      next.left = z
      z.parent = next
      pullUp(next)
    }
    fixInsert(z)
    return z
  }

  private fun insertBefore(target: Node, piece: Piece): Node {
    val z = Node(piece, RED, SENTINEL, SENTINEL, SENTINEL, piece.length, piece.lfCount)
    if (target.left === SENTINEL) {
      target.left = z
      z.parent = target
      pullUp(target)
    } else {
      val prev = maximum(target.left)
      prev.right = z
      z.parent = prev
      pullUp(prev)
    }
    fixInsert(z)
    return z
  }

  private fun pull(n: Node) {
    if (n === SENTINEL) return
    n.subtreeSize = n.left.subtreeSize + n.piece.length + n.right.subtreeSize
    n.subtreeLf = n.left.subtreeLf + n.piece.lfCount + n.right.subtreeLf
  }

  private fun pullUp(start: Node) {
    var cur = start
    while (cur !== SENTINEL) {
      pull(cur)
      cur = cur.parent
    }
  }

  private fun leftRotate(x: Node) {
    val y = x.right
    x.right = y.left
    if (y.left !== SENTINEL) y.left.parent = x
    y.parent = x.parent
    when {
      x.parent === SENTINEL -> root = y
      x === x.parent.left -> x.parent.left = y
      else -> x.parent.right = y
    }
    y.left = x
    x.parent = y
    pull(x)
    pull(y)
  }

  private fun rightRotate(y: Node) {
    val x = y.left
    y.left = x.right
    if (x.right !== SENTINEL) x.right.parent = y
    x.parent = y.parent
    when {
      y.parent === SENTINEL -> root = x
      y === y.parent.right -> y.parent.right = x
      else -> y.parent.left = x
    }
    x.right = y
    y.parent = x
    pull(y)
    pull(x)
  }

  private fun fixInsert(node: Node) {
    var z = node
    while (z.parent.color == RED) {
      if (z.parent === z.parent.parent.left) {
        val y = z.parent.parent.right
        if (y.color == RED) {
          z.parent.color = BLACK
          y.color = BLACK
          z.parent.parent.color = RED
          z = z.parent.parent
        } else {
          if (z === z.parent.right) {
            z = z.parent
            leftRotate(z)
          }
          z.parent.color = BLACK
          z.parent.parent.color = RED
          rightRotate(z.parent.parent)
        }
      } else {
        val y = z.parent.parent.left
        if (y.color == RED) {
          z.parent.color = BLACK
          y.color = BLACK
          z.parent.parent.color = RED
          z = z.parent.parent
        } else {
          if (z === z.parent.left) {
            z = z.parent
            rightRotate(z)
          }
          z.parent.color = BLACK
          z.parent.parent.color = RED
          leftRotate(z.parent.parent)
        }
      }
    }
    root.color = BLACK
  }

  private fun transplant(u: Node, v: Node) {
    when {
      u.parent === SENTINEL -> root = v
      u === u.parent.left -> u.parent.left = v
      else -> u.parent.right = v
    }
    v.parent = u.parent
  }

  private fun deleteNode(z: Node) {
    var y = z
    var yOriginalColor = y.color
    val x: Node
    var updateStart: Node

    if (z.left === SENTINEL) {
      x = z.right
      updateStart = z.parent
      transplant(z, z.right)
    } else if (z.right === SENTINEL) {
      x = z.left
      updateStart = z.parent
      transplant(z, z.left)
    } else {
      y = minimum(z.right)
      yOriginalColor = y.color
      x = y.right
      if (y.parent === z) {
        x.parent = y
        updateStart = y
      } else {
        updateStart = y.parent
        transplant(y, y.right)
        y.right = z.right
        y.right.parent = y
      }
      transplant(z, y)
      y.left = z.left
      y.left.parent = y
      y.color = z.color
      pull(y)
    }

    if (updateStart !== SENTINEL) {
      pullUp(updateStart)
    }

    if (yOriginalColor == BLACK) {
      fixDelete(x)
    }
  }

  private fun fixDelete(node: Node) {
    var x = node
    while (x !== root && x.color == BLACK) {
      if (x === x.parent.left) {
        var w = x.parent.right
        if (w.color == RED) {
          w.color = BLACK
          x.parent.color = RED
          leftRotate(x.parent)
          w = x.parent.right
        }
        if (w.left.color == BLACK && w.right.color == BLACK) {
          w.color = RED
          x = x.parent
        } else {
          if (w.right.color == BLACK) {
            w.left.color = BLACK
            w.color = RED
            rightRotate(w)
            w = x.parent.right
          }
          w.color = x.parent.color
          x.parent.color = BLACK
          w.right.color = BLACK
          leftRotate(x.parent)
          x = root
        }
      } else {
        var w = x.parent.left
        if (w.color == RED) {
          w.color = BLACK
          x.parent.color = RED
          rightRotate(x.parent)
          w = x.parent.left
        }
        if (w.right.color == BLACK && w.left.color == BLACK) {
          w.color = RED
          x = x.parent
        } else {
          if (w.left.color == BLACK) {
            w.right.color = BLACK
            w.color = RED
            leftRotate(w)
            w = x.parent.left
          }
          w.color = x.parent.color
          x.parent.color = BLACK
          w.left.color = BLACK
          rightRotate(x.parent)
          x = root
        }
      }
    }
    x.color = BLACK
  }

  private fun minimum(start: Node): Node {
    var n = start
    while (n.left !== SENTINEL) n = n.left
    return n
  }

  private fun maximum(start: Node): Node {
    var n = start
    while (n.right !== SENTINEL) n = n.right
    return n
  }

  private fun successor(node: Node): Node {
    if (node === SENTINEL) return SENTINEL
    if (node.right !== SENTINEL) return minimum(node.right)
    var y = node.parent
    var x = node
    while (y !== SENTINEL && x === y.right) {
      x = y
      y = y.parent
    }
    return y
  }

  private fun predecessor(node: Node): Node {
    if (node === SENTINEL) return SENTINEL
    if (node.left !== SENTINEL) return maximum(node.left)
    var y = node.parent
    var x = node
    while (y !== SENTINEL && x === y.left) {
      x = y
      y = y.parent
    }
    return y
  }

  private companion object {
    private const val RED = true
    private const val BLACK = false

    private val SENTINEL: Node =
      Node(
        piece = Piece(-1, 0, 0, 0),
        color = BLACK,
        left = null,
        right = null,
        parent = null,
        subtreeSize = 0,
        subtreeLf = 0,
      )

    private fun lowerBound(arr: IntArray, size: Int, target: Int): Int {
      var lo = 0
      var hi = size
      while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (arr[mid] < target) lo = mid + 1 else hi = mid
      }
      return lo
    }
  }
}
