package com.quark.fs

/**
 * Fast subsequence fuzzy matcher with word-boundary, camelCase, path-segment, and consecutive
 * match bonuses. Reusable across Quick Open (`Ctrl+P`), Command Palette, and Intel completion.
 */
object FuzzyMatcher {

  data class MatchResult(
    val score: Int,
    val matchedIndices: IntArray,
  )

  /**
   * Matches [query] against [candidate]. Returns null if [query] is not a subsequence of
   * [candidate] (case-insensitive), or a [MatchResult] with higher scores indicating better matches.
   */
  fun match(query: String, candidate: String): MatchResult? {
    if (query.isEmpty()) return MatchResult(0, IntArray(0))
    if (query.length > candidate.length) return null

    val qLen = query.length
    val cLen = candidate.length
    val indices = IntArray(qLen)
    var qIdx = 0
    var score = 0
    var prevMatchIdx = -2

    val lastSlash = candidate.lastIndexOf('/')
    for (cIdx in 0 until cLen) {
      val qc = query[qIdx]
      val cc = candidate[cIdx]
      if (qc.equals(cc, ignoreCase = true)) {
        indices[qIdx] = cIdx
        var charScore = 10

        // Exact case bonus
        if (qc == cc) charScore += 2
        // Consecutive characters bonus
        if (cIdx == prevMatchIdx + 1) charScore += 14
        // Start of string or immediately after path separator / delimiter
        if (cIdx == 0 || candidate[cIdx - 1] in charArrayOf('/', '_', '-', '.', ' ')) {
          charScore += 18
        }
        // CamelCase boundary bonus
        if (cIdx > 0 && cc.isUpperCase() && candidate[cIdx - 1].isLowerCase()) {
          charScore += 15
        }
        // Filename (after last '/') bonus
        if (cIdx > lastSlash) {
          charScore += 6
        }

        score += charScore
        prevMatchIdx = cIdx
        qIdx++
        if (qIdx == qLen) {
          // Prefer shorter candidates when match quality is tied
          score -= (cLen - qLen)
          return MatchResult(score, indices)
        }
      }
    }
    return null
  }
}
