package com.quark.editor

import android.graphics.Paint
import com.quark.buffer.TextBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Zero-allocation glyph advance width cache using a flat ASCII array and an open-addressed
 * primitive hash map for non-ASCII BMP/supplementary code points.
 */
class GlyphWidthCache {
  private val asciiWidths = FloatArray(128)
  private var keys = IntArray(256) { EMPTY_KEY }
  private var values = FloatArray(256)
  private val singleCharBuf = CharArray(2)

  var spaceWidth: Float = 8f
    private set

  var isMonospaceFastPath: Boolean = false
    private set

  /** Rebuilds cached ASCII metrics from [paint] whenever font family or size changes. */
  fun rebuild(paint: Paint) {
    keys.fill(EMPTY_KEY)
    val charArr = CharArray(1)
    for (c in 0 until 128) {
      charArr[0] = c.toChar()
      asciiWidths[c] = if (c < 32) 0f else paint.measureText(charArr, 0, 1)
    }
    spaceWidth = asciiWidths[' '.code].coerceAtLeast(1f)
    val wM = asciiWidths['M'.code]
    val wI = asciiWidths['i'.code]
    val w0 = asciiWidths['0'.code]
    isMonospaceFastPath =
      wM > 0f &&
        kotlin.math.abs(wM - wI) < 0.05f &&
        kotlin.math.abs(wM - w0) < 0.05f &&
        kotlin.math.abs(wM - spaceWidth) < 0.05f
  }

  /** Returns the advance width of [ch] without allocating any objects. */
  fun widthOf(ch: Char, paint: Paint, tabSize: Int): Float {
    val code = ch.code
    if (code == '\t'.code) return spaceWidth * tabSize
    if (code < 128) return asciiWidths[code]
    return lookupOrMeasure(code, ch, paint)
  }

  /** Measures a slice `[start, end)` of [chars] using cached widths. */
  fun measureSlice(chars: CharArray, start: Int, end: Int, paint: Paint, tabSize: Int): Float {
    if (isMonospaceFastPath) {
      var cols = 0
      var nonAscii = false
      for (i in start until end) {
        val c = chars[i]
        if (c.code >= 128) {
          nonAscii = true
          break
        }
        cols += if (c == '\t') tabSize else 1
      }
      if (!nonAscii) return cols * spaceWidth
    }
    var sum = 0f
    var i = start
    while (i < end) {
      val c = chars[i]
      if (Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(chars[i + 1])) {
        singleCharBuf[0] = c
        singleCharBuf[1] = chars[i + 1]
        sum += paint.measureText(singleCharBuf, 0, 2)
        i += 2
      } else {
        sum += widthOf(c, paint, tabSize)
        i++
      }
    }
    return sum
  }

  private fun lookupOrMeasure(code: Int, ch: Char, paint: Paint): Float {
    val mask = keys.size - 1
    var slot = (code * 0x9E3779B9.toInt()) and mask
    var probes = 0
    while (probes < 16) {
      val k = keys[slot]
      if (k == code) return values[slot]
      if (k == EMPTY_KEY) {
        singleCharBuf[0] = ch
        val w = paint.measureText(singleCharBuf, 0, 1)
        keys[slot] = code
        values[slot] = w
        return w
      }
      slot = (slot + 1) and mask
      probes++
    }
    singleCharBuf[0] = ch
    return paint.measureText(singleCharBuf, 0, 1)
  }

  private companion object {
    const val EMPTY_KEY = -1
  }
}

/**
 * Caches wrapped visual sub-rows per document line when word wrap is enabled.
 * Stores packed `(startCol shl 16) or endCol` ints to avoid object allocation.
 */
class LineLayoutCache {
  private data class Entry(
    val version: Long,
    val wrapWidthPx: Int,
    val tabSize: Int,
    val packedRanges: LongArray, // (startCol.toLong() shl 32) or (endCol.toLong() and 0xFFFFFFFFL)
  )

  private val cache = object : LinkedHashMap<Int, Entry>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>?): Boolean =
      size > 1024
  }

  fun clear() {
    cache.clear()
  }

  /**
   * Returns packed `[startCol, endCol]` sub-row ranges for [lineIndex].
   */
  fun getWrappedRanges(
    buffer: TextBuffer,
    lineIndex: Int,
    wrapWidthPx: Int,
    tabSize: Int,
    paint: Paint,
    glyphCache: GlyphWidthCache,
    scratchChars: CharArray,
  ): LongArray {
    val version = buffer.version
    val existing = cache[lineIndex]
    if (
      existing != null &&
        existing.version == version &&
        existing.wrapWidthPx == wrapWidthPx &&
        existing.tabSize == tabSize
    ) {
      return existing.packedRanges
    }

    val packedBounds = buffer.getLineBoundsPacked(lineIndex)
    val startOffset = (packedBounds ushr 32).toInt()
    val endOffset = packedBounds.toInt()
    val lineLen = (endOffset - startOffset).coerceAtLeast(0)

    if (lineLen == 0 || wrapWidthPx <= 0) {
      val single = longArrayOf(pack(0, lineLen))
      cache[lineIndex] = Entry(version, wrapWidthPx, tabSize, single)
      return single
    }

    val chars =
      if (lineLen <= scratchChars.size) {
        buffer.copyChars(startOffset, lineLen, scratchChars, 0)
        scratchChars
      } else {
        CharArray(lineLen).also { buffer.copyChars(startOffset, lineLen, it, 0) }
      }

    val ranges = ArrayList<Long>(4)
    var rowStart = 0
    var curWidth = 0f
    var lastBreakCol = -1
    var i = 0
    val maxWidth = max(wrapWidthPx.toFloat(), glyphCache.spaceWidth * 4f)

    while (i < lineLen) {
      val c = chars[i]
      val w = glyphCache.widthOf(c, paint, tabSize)
      if (c == ' ' || c == '\t' || c == '-' || c == '/' || c == '.') {
        lastBreakCol = i + 1
      }
      if (curWidth + w > maxWidth && i > rowStart) {
        val splitAt = if (lastBreakCol > rowStart) lastBreakCol else i
        ranges.add(pack(rowStart, splitAt))
        rowStart = splitAt
        lastBreakCol = -1
        curWidth = glyphCache.measureSlice(chars, rowStart, i + 1, paint, tabSize)
      } else {
        curWidth += w
      }
      i++
    }
    if (rowStart < lineLen || ranges.isEmpty()) {
      ranges.add(pack(rowStart, min(lineLen, lineLen)))
    }
    val arr = LongArray(ranges.size) { ranges[it] }
    cache[lineIndex] = Entry(version, wrapWidthPx, tabSize, arr)
    return arr
  }

  companion object {
    fun pack(startCol: Int, endCol: Int): Long =
      (startCol.toLong() shl 32) or (endCol.toLong() and 0xFFFFFFFFL)

    fun unpackStart(packed: Long): Int = (packed ushr 32).toInt()

    fun unpackEnd(packed: Long): Int = packed.toInt()
  }
}
