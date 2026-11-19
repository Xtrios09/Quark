package com.quark.intel

import com.quark.buffer.TextBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 100% on-device IntelliSense completion, signature help, and live diagnostics engine.
 * Combines scope-aware buffer symbols, project-wide index, language stdlib/member catalogs,
 * fuzzy matching, recency/frequency ranking, and structural + unused-import diagnostics.
 */
class CompletionEngine(
  private val projectIndex: ProjectIntelIndex = ProjectIntelIndex(),
) {

  private val acceptCounts = ConcurrentHashMap<String, Int>()
  private val acceptRecency = ConcurrentHashMap<String, Int>()
  private val recencyClock = AtomicInteger(0)

  fun recordAccepted(label: String) {
    acceptCounts.merge(label, 1, Int::plus)
    acceptRecency[label] = recencyClock.incrementAndGet()
  }

  fun complete(
    buffer: TextBuffer,
    filePath: String,
    caretOffset: Int,
    manualTrigger: Boolean = false,
  ): List<CompletionItem> {
    val lang = SymbolParser.detectLanguageId(filePath)
    if (lang == "text") return emptyList()

    val safeOffset = caretOffset.coerceIn(0, buffer.length)
    val lineIndex = buffer.getLineAtOffset(safeOffset)
    val lineStart = buffer.getLineStartOffset(lineIndex)
    val col = (safeOffset - lineStart).coerceAtLeast(0)
    val lineText = buffer.getLineText(lineIndex)
    val beforeCaret = if (col <= lineText.length) lineText.substring(0, col) else lineText

    // Extract current identifier prefix at caret
    var wordStartCol = col
    while (wordStartCol > 0 && isIdentChar(beforeCaret[wordStartCol - 1])) {
      wordStartCol--
    }
    val prefix = beforeCaret.substring(wordStartCol, col)
    val replaceStart = lineStart + wordStartCol
    val replaceEnd = safeOffset

    // Check if preceded by '.' (member completion)
    val beforeWord = beforeCaret.substring(0, wordStartCol)
    val isDotTrigger = beforeWord.endsWith('.')
    val isDollarInterpolation = beforeWord.endsWith('$')
    val isTagTrigger = lang == "html" && beforeWord.trimEnd().endsWith('<')

    if (!manualTrigger && prefix.isEmpty() && !isDotTrigger && !isDollarInterpolation && !isTagTrigger) {
      return emptyList()
    }

    val fullText = buffer.getText()
    val parsedFile = projectIndex.updateFile(filePath, fullText)

    val candidates = LinkedHashMap<String, CompletionItem>(48)

    fun addCandidate(
      label: String,
      insertText: String = label,
      kind: SymbolKind,
      detail: String,
      source: String,
      baseScore: Int,
      cursorBackstep: Int = 0,
    ) {
      val matchScore = scoreMatch(prefix, label) ?: return
      val boost = (acceptCounts[label] ?: 0) * 6 + ((acceptRecency[label] ?: 0) * 2).coerceAtMost(40)
      val total = baseScore + matchScore + boost
      val key = "$label|${kind.name}"
      val existing = candidates[key]
      if (existing == null || total > existing.score) {
        candidates[key] = CompletionItem(
          id = key,
          label = label,
          insertText = insertText,
          kind = kind,
          detail = detail,
          sourceLabel = source,
          score = total,
          replaceStartOffset = replaceStart,
          replaceEndOffset = replaceEnd,
          cursorBackstep = cursorBackstep,
        )
      }
    }

    if (isDotTrigger) {
      val receiver = extractReceiverExpr(beforeWord.dropLast(1))
      val receiverType = inferReceiverType(receiver, parsedFile.symbols, lineIndex)
      for (member in membersForType(lang, receiver, receiverType)) {
        addCandidate(
          label = member.label,
          insertText = member.insertText,
          kind = member.kind,
          detail = member.detail,
          source = receiverType ?: receiver,
          baseScore = 160,
          cursorBackstep = member.cursorBackstep,
        )
      }
      return candidates.values.sortedByDescending { it.score }.take(24)
    }

    if (isTagTrigger) {
      for (tag in HTML_TAGS) {
        addCandidate(
          label = tag,
          insertText = "$tag></$tag>",
          kind = SymbolKind.TAG,
          detail = "<$tag> element",
          source = "html",
          baseScore = 130,
          cursorBackstep = tag.length + 3,
        )
      }
      return candidates.values.sortedByDescending { it.score }.take(20)
    }

    // 1. Local & file symbols (prioritized by scope proximity to current line)
    for (sym in parsedFile.symbols) {
      val lineDist = abs(lineIndex - sym.line0Based)
      val proximityBonus =
        if (sym.line0Based <= lineIndex) (110 - (lineDist * 2).coerceAtMost(60))
        else 75
      val (ins, back) =
        if (sym.kind == SymbolKind.FUNCTION || sym.kind == SymbolKind.METHOD) {
          if (sym.signature.contains("()")) "${sym.name}()" to 0 else "${sym.name}()" to 1
        } else {
          sym.name to 0
        }
      addCandidate(
        label = sym.name,
        insertText = ins,
        kind = sym.kind,
        detail = sym.signature,
        source = if (sym.scopeDepth > 0) "local" else filePath.substringAfterLast('/'),
        baseScore = proximityBonus,
        cursorBackstep = back,
      )
    }

    if (isDollarInterpolation) {
      return candidates.values.sortedByDescending { it.score }.take(20)
    }

    // 2. Imported symbol names
    for (imp in parsedFile.imports) {
      addCandidate(
        label = imp.importedName,
        insertText = imp.importedName,
        kind = SymbolKind.IMPORT,
        detail = imp.rawLine,
        source = "import",
        baseScore = 95,
      )
    }

    // 3. Project-wide symbols from sibling files
    for (sym in projectIndex.allWorkspaceSymbols(excludeFilePath = filePath)) {
      val (ins, back) =
        if (sym.kind == SymbolKind.FUNCTION || sym.kind == SymbolKind.METHOD) {
          if (sym.signature.contains("()")) "${sym.name}()" to 0 else "${sym.name}()" to 1
        } else {
          sym.name to 0
        }
      addCandidate(
        label = sym.name,
        insertText = ins,
        kind = sym.kind,
        detail = sym.signature,
        source = sym.filePath.substringAfterLast('/'),
        baseScore = 88,
        cursorBackstep = back,
      )
    }

    // 4. Language stdlib functions & snippets & keywords
    for (entry in stdlibEntriesFor(lang)) {
      addCandidate(
        label = entry.label,
        insertText = entry.insertText,
        kind = entry.kind,
        detail = entry.detail,
        source = "stdlib",
        baseScore = 80,
        cursorBackstep = entry.cursorBackstep,
      )
    }
    for (kw in keywordsFor(lang)) {
      addCandidate(
        label = kw,
        insertText = kw,
        kind = SymbolKind.KEYWORD,
        detail = "$lang keyword",
        source = "keyword",
        baseScore = 60,
      )
    }

    return candidates.values.sortedWith(compareByDescending<CompletionItem> { it.score }.thenBy { it.label.length }).take(28)
  }

  fun computeSignatureHint(
    buffer: TextBuffer,
    filePath: String,
    caretOffset: Int,
  ): SignatureHint? {
    val safeOffset = caretOffset.coerceIn(0, buffer.length)
    val lineIndex = buffer.getLineAtOffset(safeOffset)
    val lineStart = buffer.getLineStartOffset(lineIndex)
    val lineText = buffer.getLineText(lineIndex)
    val col = (safeOffset - lineStart).coerceIn(0, lineText.length)

    var depth = 0
    var commaCount = 0
    var openParenCol = -1
    var i = col - 1
    while (i >= 0) {
      when (lineText[i]) {
        ')' -> depth++
        '(' -> {
          if (depth == 0) {
            openParenCol = i
            break
          }
          depth--
        }
        ',' -> if (depth == 0) commaCount++
      }
      i--
    }
    if (openParenCol <= 0) return null

    var nameStart = openParenCol - 1
    while (nameStart >= 0 && isIdentChar(lineText[nameStart])) nameStart--
    val fnName = lineText.substring(nameStart + 1, openParenCol).trim()
    if (fnName.isEmpty()) return null

    val lang = SymbolParser.detectLanguageId(filePath)
    val fileSym = projectIndex.getFileSymbols(filePath)?.symbols?.firstOrNull {
      it.name == fnName && (it.kind == SymbolKind.FUNCTION || it.kind == SymbolKind.METHOD || it.kind == SymbolKind.CLASS)
    }
    val wsSym = fileSym ?: projectIndex.findDefinition(fnName, filePath)
    if (wsSym != null) {
      return SignatureHint(fnName, wsSym.signature, commaCount, wsSym.filePath.substringAfterLast('/'))
    }
    val stdlib = stdlibEntriesFor(lang).firstOrNull { it.label == fnName }
    if (stdlib != null) {
      return SignatureHint(fnName, stdlib.detail, commaCount, "$lang stdlib")
    }
    return null
  }

  fun computeDiagnostics(
    text: String,
    filePath: String,
  ): List<DocumentDiagnostic> {
    val lang = SymbolParser.detectLanguageId(filePath)
    if (lang == "text" || text.length > 500_000) return emptyList()

    val diags = ArrayList<DocumentDiagnostic>(8)

    // 1. Structural bracket/string/comment check
    val stack = ArrayDeque<Triple<Char, Int, Int>>() // char, line0, col0
    var line = 0
    var col = 0
    var inLineComment = false
    var inBlockComment = false
    var blockStartLine = 0
    var blockStartCol = 0
    var inString: Char? = null
    var stringStartLine = 0
    var stringStartCol = 0
    var escaped = false

    val singleHashComments = lang in setOf("python", "shell", "yaml")
    val slashComments = lang in setOf("kotlin", "java", "javascript", "typescript", "cpp", "rust", "go", "css")

    var i = 0
    while (i < text.length) {
      val c = text[i]
      val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
      if (c == '\n') {
        inLineComment = false
        if (inString != null && inString != '`') {
          diags.add(
            DocumentDiagnostic(
              line0Based = stringStartLine,
              startCol = stringStartCol,
              endCol = (col).coerceAtLeast(stringStartCol + 1),
              severity = DiagSeverity.ERROR,
              message = "Unterminated string literal",
            )
          )
          inString = null
        }
        line++
        col = 0
        escaped = false
        i++
        continue
      }
      if (inLineComment) {
        col++
        i++
        continue
      }
      if (inBlockComment) {
        if (c == '*' && next == '/') {
          inBlockComment = false
          i += 2
          col += 2
          continue
        }
        col++
        i++
        continue
      }
      if (inString != null) {
        if (escaped) {
          escaped = false
        } else if (c == '\\') {
          escaped = true
        } else if (c == inString) {
          inString = null
        }
        col++
        i++
        continue
      }

      if (singleHashComments && c == '#') {
        inLineComment = true
        i++
        col++
        continue
      }
      if (slashComments && c == '/' && next == '/') {
        inLineComment = true
        i += 2
        col += 2
        continue
      }
      if (slashComments && c == '/' && next == '*') {
        inBlockComment = true
        blockStartLine = line
        blockStartCol = col
        i += 2
        col += 2
        continue
      }
      if (c == '"' || c == '\'' || (c == '`' && lang in setOf("javascript", "typescript", "go", "sql"))) {
        // Skip Kotlin/Python triple quotes
        if ((c == '"' || c == '\'') && i + 2 < text.length && text[i + 1] == c && text[i + 2] == c) {
          val triple = "$c$c$c"
          val closeIdx = text.indexOf(triple, startIndex = i + 3)
          if (closeIdx < 0) {
            diags.add(DocumentDiagnostic(line, col, col + 3, DiagSeverity.ERROR, "Unterminated triple-quoted string"))
            break
          }
          for (k in i until closeIdx + 3) {
            if (text[k] == '\n') {
              line++
              col = 0
            } else {
              col++
            }
          }
          i = closeIdx + 3
          continue
        }
        inString = c
        stringStartLine = line
        stringStartCol = col
        col++
        i++
        continue
      }

      if (lang != "html") {
        when (c) {
          '(', '[', '{' -> stack.addLast(Triple(c, line, col))
          ')', ']', '}' -> {
            val expected = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
            if (stack.isEmpty()) {
              diags.add(DocumentDiagnostic(line, col, col + 1, DiagSeverity.ERROR, "Unexpected closing '$c'"))
            } else {
              val top = stack.removeLast()
              if (top.first != expected) {
                diags.add(
                  DocumentDiagnostic(
                    line,
                    col,
                    col + 1,
                    DiagSeverity.ERROR,
                    "Mismatched '$c' (expected closing for '${top.first}' on line ${top.second + 1})",
                  )
                )
              }
            }
          }
        }
      }
      col++
      i++
    }

    if (inBlockComment) {
      diags.add(DocumentDiagnostic(blockStartLine, blockStartCol, blockStartCol + 2, DiagSeverity.ERROR, "Unclosed block comment"))
    }
    while (stack.isNotEmpty()) {
      val unclosed = stack.removeLast()
      diags.add(DocumentDiagnostic(unclosed.second, unclosed.third, unclosed.third + 1, DiagSeverity.ERROR, "Unclosed '${unclosed.first}'"))
    }

    // 2. Unused imports check (Kotlin/Java/Python/JS/TS)
    val parsed = SymbolParser.parse(text, filePath)
    if (parsed.imports.isNotEmpty()) {
      val lines = text.split('\n')
      for (imp in parsed.imports) {
        val wordRe = Regex("""\b${Regex.escape(imp.importedName)}\b""")
        var usedElsewhere = false
        for (li in lines.indices) {
          if (li == imp.line0Based) continue
          if (wordRe.containsMatchIn(lines[li])) {
            usedElsewhere = true
            break
          }
        }
        if (!usedElsewhere) {
          diags.add(
            DocumentDiagnostic(
              line0Based = imp.line0Based,
              startCol = imp.startCol,
              endCol = imp.endCol,
              severity = DiagSeverity.WARNING,
              message = "Unused import '${imp.importedName}'",
            )
          )
        }
      }
    }

    return diags.take(100)
  }

  private fun inferReceiverType(
    receiver: String,
    fileSymbols: List<IndexedSymbol>,
    caretLine: Int,
  ): String? {
    val clean = receiver.trim()
    val sym = fileSymbols.filter { it.name == clean && it.line0Based <= caretLine }.maxByOrNull { it.line0Based }
    if (sym?.returnOrValueType != null) return sym.returnOrValueType
    if (clean.startsWith("\"") && clean.endsWith("\"")) return "String"
    if (clean == "Math" || clean == "System.out" || clean == "console" || clean == "Console") return clean
    return null
  }

  private fun extractReceiverExpr(beforeDot: String): String {
    var i = beforeDot.length - 1
    while (i >= 0 && (beforeDot[i].isLetterOrDigit() || beforeDot[i] == '_' || beforeDot[i] == '.')) {
      i--
    }
    return beforeDot.substring(i + 1)
  }

  private fun membersForType(lang: String, receiver: String, type: String?): List<CatalogEntry> {
    if (receiver == "System.out") {
      return listOf(
        CatalogEntry("println", "println()", SymbolKind.METHOD, "void println(x)", 1),
        CatalogEntry("print", "print()", SymbolKind.METHOD, "void print(x)", 1),
        CatalogEntry("printf", "printf()", SymbolKind.METHOD, "PrintStream printf(fmt, ...)", 1),
      )
    }
    if (receiver == "Math") {
      return listOf(
        CatalogEntry("max", "max()", SymbolKind.METHOD, "max(a, b)", 1),
        CatalogEntry("min", "min()", SymbolKind.METHOD, "min(a, b)", 1),
        CatalogEntry("abs", "abs()", SymbolKind.METHOD, "abs(x)", 1),
        CatalogEntry("sqrt", "sqrt()", SymbolKind.METHOD, "sqrt(x): Double", 1),
        CatalogEntry("pow", "pow()", SymbolKind.METHOD, "pow(base, exp)", 1),
        CatalogEntry("round", "round()", SymbolKind.METHOD, "round(x)", 1),
        CatalogEntry("PI", "PI", SymbolKind.CONSTANT, "const PI = 3.14159...", 0),
      )
    }
    if (receiver == "console") {
      return listOf(
        CatalogEntry("log", "log()", SymbolKind.METHOD, "console.log(...data)", 1),
        CatalogEntry("error", "error()", SymbolKind.METHOD, "console.error(...data)", 1),
        CatalogEntry("warn", "warn()", SymbolKind.METHOD, "console.warn(...data)", 1),
        CatalogEntry("table", "table()", SymbolKind.METHOD, "console.table(data)", 1),
      )
    }
    val normType = type?.substringBefore('<')?.lowercase().orEmpty()
    return when {
      lang == "kotlin" && normType in setOf("list", "mutablelist", "set", "array") -> KOTLIN_COLLECTION_MEMBERS
      lang == "kotlin" && normType == "string" -> KOTLIN_STRING_MEMBERS
      lang == "kotlin" -> (KOTLIN_COLLECTION_MEMBERS + KOTLIN_STRING_MEMBERS + KOTLIN_ANY_MEMBERS).distinctBy { it.label }
      lang == "javascript" || lang == "typescript" -> JS_ARRAY_STRING_MEMBERS
      lang == "python" -> PYTHON_COMMON_MEMBERS
      lang == "java" -> JAVA_COMMON_MEMBERS
      else -> KOTLIN_ANY_MEMBERS
    }
  }

  private fun scoreMatch(query: String, candidate: String): Int? {
    if (query.isEmpty()) return 0
    if (candidate == query) return 220
    if (candidate.startsWith(query)) return 180 - (candidate.length - query.length)
    val qLower = query.lowercase()
    val cLower = candidate.lowercase()
    if (cLower.startsWith(qLower)) return 150 - (candidate.length - query.length)
    val containsIdx = cLower.indexOf(qLower)
    if (containsIdx >= 0) return 110 - containsIdx * 2
    // Subsequence fuzzy match
    var qi = 0
    var score = 60
    var lastMatch = -1
    for (ci in cLower.indices) {
      if (qi < qLower.length && cLower[ci] == qLower[qi]) {
        if (lastMatch == ci - 1) score += 6
        if (ci == 0 || !candidate[ci - 1].isLetterOrDigit() || candidate[ci].isUpperCase()) score += 8
        lastMatch = ci
        qi++
      }
    }
    return if (qi == qLower.length) score else null
  }

  private fun isIdentChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

  private data class CatalogEntry(
    val label: String,
    val insertText: String,
    val kind: SymbolKind,
    val detail: String,
    val cursorBackstep: Int = 0,
  )

  private fun stdlibEntriesFor(lang: String): List<CatalogEntry> =
    when (lang) {
      "kotlin" -> KOTLIN_STDLIB
      "java" -> JAVA_STDLIB
      "javascript", "typescript" -> JS_STDLIB
      "python" -> PYTHON_STDLIB
      else -> emptyList()
    }

  private fun keywordsFor(lang: String): List<String> =
    when (lang) {
      "kotlin" -> KOTLIN_KEYWORDS
      "java" -> JAVA_KEYWORDS
      "javascript", "typescript" -> JS_KEYWORDS
      "python" -> PYTHON_KEYWORDS
      "rust" -> listOf("fn", "let", "mut", "struct", "enum", "impl", "trait", "pub", "match", "if", "else", "for", "while", "loop", "return", "use", "mod")
      "go" -> listOf("func", "package", "import", "var", "const", "type", "struct", "interface", "if", "else", "for", "range", "switch", "case", "return", "go", "defer")
      "cpp" -> listOf("int", "double", "float", "char", "bool", "void", "auto", "const", "class", "struct", "public", "private", "if", "else", "for", "while", "return", "include", "namespace")
      "sql" -> listOf("SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "JOIN", "ON", "GROUP", "BY", "ORDER", "LIMIT")
      "shell" -> listOf("if", "then", "else", "elif", "fi", "for", "do", "done", "while", "case", "esac", "export", "echo", "local", "return", "exit")
      "css" -> listOf("display", "flex-direction", "justify-content", "align-items", "background", "color", "padding", "margin", "border-radius", "font-size")
      else -> emptyList()
    }

  companion object {
    private val HTML_TAGS = listOf("div", "span", "p", "h1", "h2", "h3", "main", "section", "header", "footer", "button", "input", "a", "ul", "li", "script", "style", "link")

    private val KOTLIN_KEYWORDS = listOf(
      "fun", "val", "var", "class", "data", "object", "interface", "enum", "sealed",
      "if", "else", "when", "for", "while", "do", "return", "break", "continue",
      "package", "import", "true", "false", "null", "try", "catch", "finally",
      "private", "public", "internal", "override", "suspend", "in", "is", "as",
    )
    private val JAVA_KEYWORDS = listOf(
      "public", "private", "protected", "static", "final", "class", "interface", "void",
      "int", "double", "boolean", "String", "if", "else", "for", "while", "return",
      "new", "true", "false", "null", "import", "try", "catch", "switch", "case",
    )
    private val JS_KEYWORDS = listOf(
      "function", "const", "let", "var", "return", "if", "else", "for", "of", "in",
      "while", "class", "interface", "type", "import", "export", "async", "await",
      "true", "false", "null", "undefined", "try", "catch",
    )
    private val PYTHON_KEYWORDS = listOf(
      "def", "class", "return", "if", "elif", "else", "for", "in", "while",
      "import", "from", "as", "try", "except", "finally", "with", "True", "False", "None",
      "lambda", "yield", "pass", "break", "continue", "and", "or", "not",
    )

    private val KOTLIN_STDLIB = listOf(
      CatalogEntry("println", "println()", SymbolKind.FUNCTION, "fun println(message: Any?)", 1),
      CatalogEntry("print", "print()", SymbolKind.FUNCTION, "fun print(message: Any?)", 1),
      CatalogEntry("listOf", "listOf()", SymbolKind.FUNCTION, "fun <T> listOf(vararg elements: T): List<T>", 1),
      CatalogEntry("mutableListOf", "mutableListOf()", SymbolKind.FUNCTION, "fun <T> mutableListOf(): MutableList<T>", 1),
      CatalogEntry("mapOf", "mapOf()", SymbolKind.FUNCTION, "fun <K, V> mapOf(vararg pairs: Pair<K, V>)", 1),
      CatalogEntry("mutableMapOf", "mutableMapOf()", SymbolKind.FUNCTION, "fun <K, V> mutableMapOf()", 1),
      CatalogEntry("setOf", "setOf()", SymbolKind.FUNCTION, "fun <T> setOf(vararg elements: T): Set<T>", 1),
      CatalogEntry("main", "fun main() {\n    \n}", SymbolKind.SNIPPET, "Main entry function", 2),
      CatalogEntry("fun", "fun name() {\n    \n}", SymbolKind.SNIPPET, "Function declaration", 12),
      CatalogEntry("data class", "data class Name()", SymbolKind.SNIPPET, "Data class template", 1),
      CatalogEntry("forEach", "forEach { }", SymbolKind.SNIPPET, "forEach lambda", 2),
      CatalogEntry("max", "max()", SymbolKind.FUNCTION, "fun max(a, b)", 1),
      CatalogEntry("min", "min()", SymbolKind.FUNCTION, "fun min(a, b)", 1),
      CatalogEntry("abs", "abs()", SymbolKind.FUNCTION, "fun abs(x)", 1),
      CatalogEntry("sqrt", "sqrt()", SymbolKind.FUNCTION, "fun sqrt(x: Double): Double", 1),
      CatalogEntry("String", "String", SymbolKind.CLASS, "kotlin.String", 0),
      CatalogEntry("Int", "Int", SymbolKind.CLASS, "kotlin.Int", 0),
      CatalogEntry("Double", "Double", SymbolKind.CLASS, "kotlin.Double", 0),
      CatalogEntry("Boolean", "Boolean", SymbolKind.CLASS, "kotlin.Boolean", 0),
      CatalogEntry("List", "List", SymbolKind.CLASS, "kotlin.collections.List", 0),
      CatalogEntry("StringBuilder", "StringBuilder()", SymbolKind.CLASS, "kotlin.text.StringBuilder", 1),
    )

    private val KOTLIN_COLLECTION_MEMBERS = listOf(
      CatalogEntry("forEach", "forEach { item -> }", SymbolKind.METHOD, "forEach(action: (T) -> Unit)", 2),
      CatalogEntry("forEachIndexed", "forEachIndexed { idx, item -> }", SymbolKind.METHOD, "forEachIndexed(action: (Int, T) -> Unit)", 2),
      CatalogEntry("map", "map { it }", SymbolKind.METHOD, "map(transform: (T) -> R): List<R>", 2),
      CatalogEntry("filter", "filter { it }", SymbolKind.METHOD, "filter(predicate: (T) -> Boolean): List<T>", 2),
      CatalogEntry("any", "any { it }", SymbolKind.METHOD, "any(predicate: (T) -> Boolean): Boolean", 2),
      CatalogEntry("all", "all { it }", SymbolKind.METHOD, "all(predicate: (T) -> Boolean): Boolean", 2),
      CatalogEntry("count", "count()", SymbolKind.METHOD, "count(): Int", 0),
      CatalogEntry("find", "find { it }", SymbolKind.METHOD, "find(predicate: (T) -> Boolean): T?", 2),
      CatalogEntry("first", "first()", SymbolKind.METHOD, "first(): T", 0),
      CatalogEntry("last", "last()", SymbolKind.METHOD, "last(): T", 0),
      CatalogEntry("sum", "sum()", SymbolKind.METHOD, "sum(): Int/Double", 0),
      CatalogEntry("sorted", "sorted()", SymbolKind.METHOD, "sorted(): List<T>", 0),
      CatalogEntry("reversed", "reversed()", SymbolKind.METHOD, "reversed(): List<T>", 0),
      CatalogEntry("joinToString", "joinToString(\", \")", SymbolKind.METHOD, "joinToString(separator: String): String", 0),
      CatalogEntry("contains", "contains()", SymbolKind.METHOD, "contains(element: T): Boolean", 1),
      CatalogEntry("add", "add()", SymbolKind.METHOD, "add(element: T): Boolean", 1),
      CatalogEntry("size", "size", SymbolKind.PROPERTY, "val size: Int", 0),
      CatalogEntry("isEmpty", "isEmpty()", SymbolKind.METHOD, "isEmpty(): Boolean", 0),
      CatalogEntry("isNotEmpty", "isNotEmpty()", SymbolKind.METHOD, "isNotEmpty(): Boolean", 0),
    )

    private val KOTLIN_STRING_MEMBERS = listOf(
      CatalogEntry("length", "length", SymbolKind.PROPERTY, "val length: Int", 0),
      CatalogEntry("uppercase", "uppercase()", SymbolKind.METHOD, "uppercase(): String", 0),
      CatalogEntry("lowercase", "lowercase()", SymbolKind.METHOD, "lowercase(): String", 0),
      CatalogEntry("trim", "trim()", SymbolKind.METHOD, "trim(): String", 0),
      CatalogEntry("split", "split()", SymbolKind.METHOD, "split(delimiter: String): List<String>", 1),
      CatalogEntry("replace", "replace()", SymbolKind.METHOD, "replace(old, new): String", 1),
      CatalogEntry("startsWith", "startsWith()", SymbolKind.METHOD, "startsWith(prefix: String): Boolean", 1),
      CatalogEntry("endsWith", "endsWith()", SymbolKind.METHOD, "endsWith(suffix: String): Boolean", 1),
      CatalogEntry("contains", "contains()", SymbolKind.METHOD, "contains(other: String): Boolean", 1),
      CatalogEntry("substring", "substring()", SymbolKind.METHOD, "substring(startIndex, endIndex): String", 1),
      CatalogEntry("toInt", "toInt()", SymbolKind.METHOD, "toInt(): Int", 0),
      CatalogEntry("toDouble", "toDouble()", SymbolKind.METHOD, "toDouble(): Double", 0),
      CatalogEntry("isEmpty", "isEmpty()", SymbolKind.METHOD, "isEmpty(): Boolean", 0),
      CatalogEntry("isNotEmpty", "isNotEmpty()", SymbolKind.METHOD, "isNotEmpty(): Boolean", 0),
    )

    private val KOTLIN_ANY_MEMBERS = listOf(
      CatalogEntry("toString", "toString()", SymbolKind.METHOD, "toString(): String", 0),
      CatalogEntry("let", "let { it }", SymbolKind.METHOD, "let(block: (T) -> R): R", 2),
      CatalogEntry("also", "also { it }", SymbolKind.METHOD, "also(block: (T) -> Unit): T", 2),
      CatalogEntry("apply", "apply { }", SymbolKind.METHOD, "apply(block: T.() -> Unit): T", 2),
    )

    private val JAVA_STDLIB = listOf(
      CatalogEntry("System.out.println", "System.out.println()", SymbolKind.SNIPPET, "Print line to stdout", 1),
      CatalogEntry("ArrayList", "ArrayList<>()", SymbolKind.CLASS, "java.util.ArrayList", 1),
      CatalogEntry("HashMap", "HashMap<>()", SymbolKind.CLASS, "java.util.HashMap", 1),
      CatalogEntry("StringBuilder", "StringBuilder()", SymbolKind.CLASS, "java.lang.StringBuilder", 1),
      CatalogEntry("Math", "Math", SymbolKind.CLASS, "java.lang.Math", 0),
    )

    private val JAVA_COMMON_MEMBERS = listOf(
      CatalogEntry("length", "length()", SymbolKind.METHOD, "int length()", 0),
      CatalogEntry("size", "size()", SymbolKind.METHOD, "int size()", 0),
      CatalogEntry("equals", "equals()", SymbolKind.METHOD, "boolean equals(Object obj)", 1),
      CatalogEntry("toString", "toString()", SymbolKind.METHOD, "String toString()", 0),
      CatalogEntry("add", "add()", SymbolKind.METHOD, "boolean add(E e)", 1),
      CatalogEntry("get", "get()", SymbolKind.METHOD, "E get(int index)", 1),
      CatalogEntry("toUpperCase", "toUpperCase()", SymbolKind.METHOD, "String toUpperCase()", 0),
      CatalogEntry("toLowerCase", "toLowerCase()", SymbolKind.METHOD, "String toLowerCase()", 0),
      CatalogEntry("trim", "trim()", SymbolKind.METHOD, "String trim()", 0),
    )

    private val JS_STDLIB = listOf(
      CatalogEntry("console.log", "console.log()", SymbolKind.SNIPPET, "Log to console", 1),
      CatalogEntry("Array", "Array", SymbolKind.CLASS, "Global Array", 0),
      CatalogEntry("Promise", "Promise", SymbolKind.CLASS, "Async Promise", 0),
      CatalogEntry("JSON.stringify", "JSON.stringify()", SymbolKind.FUNCTION, "JSON.stringify(value)", 1),
      CatalogEntry("JSON.parse", "JSON.parse()", SymbolKind.FUNCTION, "JSON.parse(text)", 1),
      CatalogEntry("Math", "Math", SymbolKind.CLASS, "Math built-in", 0),
    )

    private val JS_ARRAY_STRING_MEMBERS = listOf(
      CatalogEntry("map", "map(x => )", SymbolKind.METHOD, "array.map(fn)", 1),
      CatalogEntry("filter", "filter(x => )", SymbolKind.METHOD, "array.filter(fn)", 1),
      CatalogEntry("forEach", "forEach(x => )", SymbolKind.METHOD, "array.forEach(fn)", 1),
      CatalogEntry("find", "find(x => )", SymbolKind.METHOD, "array.find(fn)", 1),
      CatalogEntry("reduce", "reduce((acc, cur) => , 0)", SymbolKind.METHOD, "array.reduce(fn, init)", 4),
      CatalogEntry("push", "push()", SymbolKind.METHOD, "array.push(...items)", 1),
      CatalogEntry("join", "join(\", \")", SymbolKind.METHOD, "array.join(separator)", 0),
      CatalogEntry("length", "length", SymbolKind.PROPERTY, "number", 0),
      CatalogEntry("trim", "trim()", SymbolKind.METHOD, "string.trim()", 0),
      CatalogEntry("split", "split()", SymbolKind.METHOD, "string.split(sep)", 1),
      CatalogEntry("toUpperCase", "toUpperCase()", SymbolKind.METHOD, "string.toUpperCase()", 0),
      CatalogEntry("toLowerCase", "toLowerCase()", SymbolKind.METHOD, "string.toLowerCase()", 0),
    )

    private val PYTHON_STDLIB = listOf(
      CatalogEntry("print", "print()", SymbolKind.FUNCTION, "print(*values, sep=' ', end='\\n')", 1),
      CatalogEntry("len", "len()", SymbolKind.FUNCTION, "len(obj) -> int", 1),
      CatalogEntry("range", "range()", SymbolKind.FUNCTION, "range(start, stop, step)", 1),
      CatalogEntry("enumerate", "enumerate()", SymbolKind.FUNCTION, "enumerate(iterable)", 1),
      CatalogEntry("zip", "zip()", SymbolKind.FUNCTION, "zip(*iterables)", 1),
      CatalogEntry("sorted", "sorted()", SymbolKind.FUNCTION, "sorted(iterable)", 1),
      CatalogEntry("sum", "sum()", SymbolKind.FUNCTION, "sum(iterable)", 1),
      CatalogEntry("min", "min()", SymbolKind.FUNCTION, "min(iterable)", 1),
      CatalogEntry("max", "max()", SymbolKind.FUNCTION, "max(iterable)", 1),
      CatalogEntry("str", "str()", SymbolKind.FUNCTION, "str(object)", 1),
      CatalogEntry("int", "int()", SymbolKind.FUNCTION, "int(x)", 1),
      CatalogEntry("float", "float()", SymbolKind.FUNCTION, "float(x)", 1),
      CatalogEntry("list", "list()", SymbolKind.FUNCTION, "list(iterable)", 1),
      CatalogEntry("dict", "dict()", SymbolKind.FUNCTION, "dict(**kwargs)", 1),
    )

    private val PYTHON_COMMON_MEMBERS = listOf(
      CatalogEntry("append", "append()", SymbolKind.METHOD, "list.append(object)", 1),
      CatalogEntry("extend", "extend()", SymbolKind.METHOD, "list.extend(iterable)", 1),
      CatalogEntry("pop", "pop()", SymbolKind.METHOD, "list.pop(index=-1)", 0),
      CatalogEntry("sort", "sort()", SymbolKind.METHOD, "list.sort()", 0),
      CatalogEntry("strip", "strip()", SymbolKind.METHOD, "str.strip()", 0),
      CatalogEntry("split", "split()", SymbolKind.METHOD, "str.split(sep=None)", 1),
      CatalogEntry("join", "join()", SymbolKind.METHOD, "str.join(iterable)", 1),
      CatalogEntry("upper", "upper()", SymbolKind.METHOD, "str.upper()", 0),
      CatalogEntry("lower", "lower()", SymbolKind.METHOD, "str.lower()", 0),
      CatalogEntry("replace", "replace()", SymbolKind.METHOD, "str.replace(old, new)", 1),
      CatalogEntry("keys", "keys()", SymbolKind.METHOD, "dict.keys()", 0),
      CatalogEntry("values", "values()", SymbolKind.METHOD, "dict.values()", 0),
      CatalogEntry("items", "items()", SymbolKind.METHOD, "dict.items()", 0),
      CatalogEntry("get", "get()", SymbolKind.METHOD, "dict.get(key, default=None)", 1),
    )
  }
}
