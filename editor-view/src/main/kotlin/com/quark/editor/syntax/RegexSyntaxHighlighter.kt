package com.quark.editor.syntax

import com.quark.buffer.TextBuffer
import com.quark.editor.LineToken
import com.quark.editor.SyntaxSpanProvider
import com.quark.editor.TokenType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Regex-driven incremental syntax highlighter implementing [SyntaxSpanProvider].
 *
 * Maintains per-line multi-line lexer states (`NORMAL`, `BLOCK_COMMENT`, `TRIPLE_DOUBLE`,
 * `TRIPLE_SINGLE`) and cached [LineToken] lists so [getLineTokens] during `onDraw` performs
 * zero allocations. Re-highlighting after edits runs off the main thread on [Dispatchers.Default]
 * from the dirty line downward until lexer state converges with cached states.
 */
class RegexSyntaxHighlighter(
  private val onTokensUpdated: (() -> Unit)? = null,
) : SyntaxSpanProvider {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val lineTokens = ConcurrentHashMap<Int, List<LineToken>>()
  private val lineExitStates = ConcurrentHashMap<Int, Int>()
  private val activeGrammarRef = AtomicReference<SyntaxGrammar?>(null)
  private var currentJob: Job? = null
  @Volatile private var generation = 0L

  override fun getLineTokens(lineIndex: Int): List<LineToken> =
    lineTokens[lineIndex] ?: emptyList()

  fun currentGrammar(): SyntaxGrammar? = activeGrammarRef.get()

  /** Configures grammar and highlights the full buffer incrementally in the background. */
  fun configure(buffer: TextBuffer, grammar: SyntaxGrammar?) {
    currentJob?.cancel()
    generation++
    activeGrammarRef.set(grammar)
    lineTokens.clear()
    lineExitStates.clear()
    if (grammar == null || buffer.lineCount <= 0) {
      onTokensUpdated?.invoke()
      return
    }
    scheduleFrom(buffer, startLine = 0, grammar = grammar, forceFullSweep = true)
  }

  /**
   * Invalidates caches from [dirtyLine] onward and re-highlights incrementally until
   * exit state converges (or after structural line count shifts).
   */
  fun invalidateFromLine(
    buffer: TextBuffer,
    dirtyLine: Int,
    linesRemovedOrInserted: Boolean = true,
  ) {
    val grammar = activeGrammarRef.get() ?: return
    val clampedStart = dirtyLine.coerceIn(0, (buffer.lineCount - 1).coerceAtLeast(0))
    scheduleFrom(buffer, startLine = clampedStart, grammar = grammar, forceFullSweep = linesRemovedOrInserted)
  }

  /** Synchronous full highlight helper for deterministic unit testing. */
  fun highlightSync(buffer: TextBuffer, grammar: SyntaxGrammar?) {
    currentJob?.cancel()
    generation++
    activeGrammarRef.set(grammar)
    lineTokens.clear()
    lineExitStates.clear()
    if (grammar == null) return
    var state = STATE_NORMAL
    val total = buffer.lineCount
    for (line in 0 until total) {
      val text = buffer.getLineText(line)
      val (tokens, nextState) = tokenizeLine(text, state, grammar)
      lineTokens[line] = tokens
      lineExitStates[line] = nextState
      state = nextState
    }
  }

  fun dispose() {
    currentJob?.cancel()
    scope.cancel()
  }

  private fun scheduleFrom(
    buffer: TextBuffer,
    startLine: Int,
    grammar: SyntaxGrammar,
    forceFullSweep: Boolean,
  ) {
    currentJob?.cancel()
    val gen = ++generation
    currentJob = scope.launch {
      val totalLines = buffer.lineCount
      // Remove stale entries beyond current EOF
      val staleKeys = lineTokens.keys.filter { it >= totalLines }
      for (k in staleKeys) {
        lineTokens.remove(k)
        lineExitStates.remove(k)
      }

      var state = if (startLine > 0) (lineExitStates[startLine - 1] ?: STATE_NORMAL) else STATE_NORMAL
      var updatedAny = false
      var batchCount = 0

      for (line in startLine until totalLines) {
        if (!isActive || gen != generation) return@launch
        val text = try {
          buffer.getLineText(line)
        } catch (_: Throwable) {
          break
        }
        val (tokens, exitState) = tokenizeLine(text, state, grammar)
        val prevExit = lineExitStates[line]
        val prevTokens = lineTokens[line]

        lineTokens[line] = tokens
        lineExitStates[line] = exitState
        if (prevTokens != tokens) {
          updatedAny = true
        }
        batchCount++

        if (batchCount == INITIAL_PAINT_BATCH) {
          onTokensUpdated?.invoke()
          updatedAny = false
        } else if (batchCount % REFRESH_INTERVAL == 0 && updatedAny) {
          onTokensUpdated?.invoke()
          updatedAny = false
        }

        state = exitState
        // Early convergence when line structure didn't shift and exit state matches cache
        if (!forceFullSweep && line > startLine && prevExit != null && prevExit == exitState && prevTokens == tokens) {
          break
        }
      }

      if (updatedAny || startLine == 0) {
        onTokensUpdated?.invoke()
      }
    }
  }

  companion object {
    const val STATE_NORMAL = 0
    const val STATE_BLOCK_COMMENT = 1
    const val STATE_TRIPLE_DOUBLE = 2
    const val STATE_TRIPLE_SINGLE = 3

    private const val INITIAL_PAINT_BATCH = 80
    private const val REFRESH_INTERVAL = 250
    private const val MAX_TOKENIZE_LINE_CHARS = 4096

    /**
     * Pure, stateless regex tokenizer for a single line given an [entryState].
     * Returns `(sortedNonOverlappingTokens, exitState)`.
     */
    fun tokenizeLine(
      lineText: String,
      entryState: Int,
      grammar: SyntaxGrammar,
    ): Pair<List<LineToken>, Int> {
      if (lineText.isEmpty()) return emptyList<LineToken>() to entryState

      val scanLen = lineText.length.coerceAtMost(MAX_TOKENIZE_LINE_CHARS)
      val out = ArrayList<LineToken>(12)
      var pos = 0
      var state = entryState

      while (pos < scanLen) {
        when (state) {
          STATE_BLOCK_COMMENT -> {
            val endDelim = grammar.blockCommentEnd
            if (endDelim != null) {
              val closeIdx = lineText.indexOf(endDelim, startIndex = pos)
              if (closeIdx >= 0 && closeIdx < scanLen) {
                val tokenEnd = (closeIdx + endDelim.length).coerceAtMost(scanLen)
                out.add(LineToken(pos, tokenEnd, TokenType.COMMENT))
                pos = tokenEnd
                state = STATE_NORMAL
              } else {
                out.add(LineToken(pos, scanLen, TokenType.COMMENT))
                pos = scanLen
              }
            } else {
              state = STATE_NORMAL
            }
          }

          STATE_TRIPLE_DOUBLE -> {
            val closeIdx = lineText.indexOf("\"\"\"", startIndex = pos)
            if (closeIdx >= 0 && closeIdx < scanLen) {
              val tokenEnd = (closeIdx + 3).coerceAtMost(scanLen)
              out.add(LineToken(pos, tokenEnd, TokenType.STRING))
              pos = tokenEnd
              state = STATE_NORMAL
            } else {
              out.add(LineToken(pos, scanLen, TokenType.STRING))
              pos = scanLen
            }
          }

          STATE_TRIPLE_SINGLE -> {
            val closeIdx = lineText.indexOf("'''", startIndex = pos)
            if (closeIdx >= 0 && closeIdx < scanLen) {
              val tokenEnd = (closeIdx + 3).coerceAtMost(scanLen)
              out.add(LineToken(pos, tokenEnd, TokenType.STRING))
              pos = tokenEnd
              state = STATE_NORMAL
            } else {
              out.add(LineToken(pos, scanLen, TokenType.STRING))
              pos = scanLen
            }
          }

          else -> {
            // Find earliest multiline opener in the remainder of the line
            var nextOpenerIdx = scanLen
            var nextOpenerState = STATE_NORMAL
            var nextOpenerLen = 0

            val bStart = grammar.blockCommentStart
            if (bStart != null) {
              val idx = lineText.indexOf(bStart, startIndex = pos)
              if (idx in pos until nextOpenerIdx) {
                nextOpenerIdx = idx
                nextOpenerState = STATE_BLOCK_COMMENT
                nextOpenerLen = bStart.length
              }
            }
            if (grammar.supportsTripleDoubleQuote) {
              val idx = lineText.indexOf("\"\"\"", startIndex = pos)
              if (idx in pos until nextOpenerIdx) {
                nextOpenerIdx = idx
                nextOpenerState = STATE_TRIPLE_DOUBLE
                nextOpenerLen = 3
              }
            }
            if (grammar.supportsTripleSingleQuote) {
              val idx = lineText.indexOf("'''", startIndex = pos)
              if (idx in pos until nextOpenerIdx) {
                nextOpenerIdx = idx
                nextOpenerState = STATE_TRIPLE_SINGLE
                nextOpenerLen = 3
              }
            }

            val segmentEnd = nextOpenerIdx
            if (segmentEnd > pos) {
              val segmentHadComment = tokenizeNormalSegment(lineText, pos, segmentEnd, grammar.rules, out)
              // If a line comment (//, #, --) ran all the way to segmentEnd, skip any opener inside the comment
              if (segmentHadComment) {
                val lineCommentStart = findLineCommentStart(out, pos, segmentEnd)
                if (lineCommentStart >= 0 && nextOpenerIdx < scanLen) {
                  // Extend the line comment to the end of the line
                  val lastIdx = out.lastIndex
                  if (lastIdx >= 0 && out[lastIdx].type == TokenType.COMMENT) {
                    out[lastIdx] = out[lastIdx].copy(endCol = scanLen)
                  }
                  pos = scanLen
                  break
                }
              }
            }

            if (nextOpenerIdx < scanLen) {
              out.add(
                LineToken(
                  nextOpenerIdx,
                  (nextOpenerIdx + nextOpenerLen).coerceAtMost(scanLen),
                  if (nextOpenerState == STATE_BLOCK_COMMENT) TokenType.COMMENT else TokenType.STRING,
                )
              )
              pos = nextOpenerIdx + nextOpenerLen
              state = nextOpenerState
            } else {
              pos = scanLen
            }
          }
        }
      }

      return coalesceTokens(out) to state
    }

    private fun findLineCommentStart(tokens: List<LineToken>, from: Int, segmentEnd: Int): Int {
      for (i in tokens.indices.reversed()) {
        val t = tokens[i]
        if (t.endCol <= from) break
        if (t.type == TokenType.COMMENT && t.endCol == segmentEnd) return t.startCol
      }
      return -1
    }

    private fun tokenizeNormalSegment(
      text: String,
      segStart: Int,
      segEnd: Int,
      rules: List<SyntaxRule>,
      out: MutableList<LineToken>,
    ): Boolean {
      // Collect all candidate spans across regex rules in priority order
      // Rule index breaks ties when start positions match, ensuring comments/strings win over keywords/operators
      data class Candidate(val start: Int, val end: Int, val priority: Int, val type: TokenType)

      val slice = if (segStart == 0 && segEnd == text.length) text else text.substring(segStart, segEnd)
      val candidates = ArrayList<Candidate>(16)

      for (ruleIdx in rules.indices) {
        val rule = rules[ruleIdx]
        var match = rule.regex.find(slice)
        while (match != null) {
          val grp = if (rule.group == 0) match.groups[0] else match.groups[rule.group]
          if (grp != null && !grp.range.isEmpty()) {
            candidates.add(
              Candidate(
                start = segStart + grp.range.first,
                end = segStart + grp.range.last + 1,
                priority = ruleIdx,
                type = rule.type,
              )
            )
          }
          match = match.next()
        }
      }

      if (candidates.isEmpty()) return false

      candidates.sortWith(
        Comparator { a, b ->
          val cmpStart = a.start.compareTo(b.start)
          if (cmpStart != 0) return@Comparator cmpStart
          val cmpPrio = a.priority.compareTo(b.priority)
          if (cmpPrio != 0) return@Comparator cmpPrio
          b.end.compareTo(a.end)
        }
      )

      var cursor = segStart
      var emittedLineComment = false
      for (c in candidates) {
        if (c.start < cursor) continue
        if (c.end <= c.start) continue
        out.add(LineToken(c.start, c.end, c.type))
        if (c.type == TokenType.COMMENT && c.end == segEnd) {
          emittedLineComment = true
        }
        cursor = c.end
      }
      return emittedLineComment
    }

    private fun coalesceTokens(raw: List<LineToken>): List<LineToken> {
      if (raw.size <= 1) return raw
      val sorted = raw.sortedBy { it.startCol }
      val merged = ArrayList<LineToken>(sorted.size)
      for (tok in sorted) {
        val prev = merged.lastOrNull()
        if (prev != null && prev.type == tok.type && prev.endCol == tok.startCol) {
          merged[merged.lastIndex] = prev.copy(endCol = tok.endCol)
        } else {
          merged.add(tok)
        }
      }
      return merged
    }
  }
}
