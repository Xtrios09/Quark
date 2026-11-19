package com.quark.intel

/**
 * Pure-Kotlin multi-language lightweight parser extracting classes, functions, parameters,
 * local variables, inferred variable types, and imports with 0-based line/column positions.
 */
object SymbolParser {

  fun detectLanguageId(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
      "kt", "kts" -> "kotlin"
      "java" -> "java"
      "js", "mjs", "cjs", "jsx" -> "javascript"
      "ts", "tsx" -> "typescript"
      "py", "pyw" -> "python"
      "c", "h", "cpp", "cc", "cxx", "hpp", "hh" -> "cpp"
      "rs" -> "rust"
      "go" -> "go"
      "html", "htm", "xml", "svg" -> "html"
      "css", "scss", "sass" -> "css"
      "sql", "sqlite" -> "sql"
      "sh", "bash", "zsh" -> "shell"
      else -> "text"
    }
  }

  fun parse(text: String, filePath: String): ParsedFileSymbols {
    val lang = detectLanguageId(filePath)
    val symbols = ArrayList<IndexedSymbol>(32)
    val imports = ArrayList<IndexedImport>(8)
    val lines = text.split('\n')

    var braceDepth = 0
    var currentContainer: String? = null
    var containerExitDepth = -1

    for (lineIdx in lines.indices) {
      val line = lines[lineIdx]
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
        braceDepth = updateBraceDepth(line, braceDepth)
        if (currentContainer != null && braceDepth < containerExitDepth) {
          currentContainer = null
          containerExitDepth = -1
        }
        continue
      }

      when (lang) {
        "kotlin" -> {
          parseKotlinLine(line, lineIdx, braceDepth, filePath, currentContainer, symbols, imports) { clsName, targetDepth ->
            currentContainer = clsName
            containerExitDepth = targetDepth
          }
        }
        "java" -> {
          parseJavaLine(line, lineIdx, braceDepth, filePath, currentContainer, symbols, imports) { clsName, targetDepth ->
            currentContainer = clsName
            containerExitDepth = targetDepth
          }
        }
        "javascript", "typescript" -> {
          parseJsTsLine(line, lineIdx, braceDepth, filePath, currentContainer, symbols, imports)
        }
        "python" -> {
          parsePythonLine(line, lineIdx, filePath, symbols, imports)
        }
        "rust" -> parseRustLine(line, lineIdx, braceDepth, filePath, symbols)
        "go" -> parseGoLine(line, lineIdx, braceDepth, filePath, symbols)
        "cpp" -> parseCppLine(line, lineIdx, braceDepth, filePath, symbols)
        "css" -> parseCssLine(line, lineIdx, filePath, symbols)
        "sql" -> parseSqlLine(line, lineIdx, filePath, symbols)
        "shell" -> parseShellLine(line, lineIdx, filePath, symbols)
      }

      braceDepth = updateBraceDepth(line, braceDepth)
      if (currentContainer != null && braceDepth < containerExitDepth) {
        currentContainer = null
        containerExitDepth = -1
      }
    }

    return ParsedFileSymbols(
      filePath = filePath,
      languageId = lang,
      symbols = symbols,
      imports = imports,
    )
  }

  private fun parseKotlinLine(
    line: String,
    lineIdx: Int,
    depth: Int,
    filePath: String,
    container: String?,
    out: MutableList<IndexedSymbol>,
    imports: MutableList<IndexedImport>,
    onEnterContainer: (String, Int) -> Unit,
  ) {
    KT_IMPORT_RE.find(line)?.let { m ->
      val fq = m.groupValues[1].trim()
      val shortName = m.groupValues[2].ifEmpty { fq.substringAfterLast('.') }
      if (shortName != "*") {
        imports.add(
          IndexedImport(
            rawLine = line.trim(),
            importedName = shortName,
            line0Based = lineIdx,
            startCol = m.range.first,
            endCol = line.length,
          )
        )
      }
      return
    }

    KT_CLASS_RE.find(line)?.let { m ->
      val kindWord = m.groupValues[1]
      val name = m.groupValues[2]
      val paramsRaw = m.groupValues[3]
      val kind =
        when {
          kindWord.contains("interface") -> SymbolKind.INTERFACE
          kindWord.contains("enum") -> SymbolKind.ENUM
          kindWord.contains("object") -> SymbolKind.OBJECT
          else -> SymbolKind.CLASS
        }
      val sig = if (paramsRaw.isNotEmpty()) "$kindWord $name($paramsRaw)" else "$kindWord $name"
      val col = line.indexOf(name).coerceAtLeast(0)
      out.add(IndexedSymbol(name, kind, sig, name, filePath, lineIdx, col, col + name.length, depth, container))
      if (paramsRaw.isNotBlank()) {
        extractTypedParams(paramsRaw, lineIdx, depth + 1, filePath, name, out)
      }
      if (line.contains('{')) onEnterContainer(name, depth + 1)
      return
    }

    KT_FUN_RE.find(line)?.let { m ->
      val name = m.groupValues[1]
      val params = m.groupValues[2].trim()
      val retType = m.groupValues[3].trim().ifEmpty { "Unit" }
      val col = line.indexOf(name).coerceAtLeast(0)
      val kind = if (container != null && depth > 0) SymbolKind.METHOD else SymbolKind.FUNCTION
      out.add(
        IndexedSymbol(
          name = name,
          kind = kind,
          signature = "fun $name($params): $retType",
          returnOrValueType = retType,
          filePath = filePath,
          line0Based = lineIdx,
          col0Based = col,
          endCol0Based = col + name.length,
          scopeDepth = depth,
          containerName = container,
        )
      )
      if (params.isNotEmpty()) {
        extractTypedParams(params, lineIdx, depth + 1, filePath, name, out)
      }
      return
    }

    KT_VAL_VAR_RE.find(line)?.let { m ->
      val mut = m.groupValues[1]
      val name = m.groupValues[2]
      val declaredType = m.groupValues[3].trim().takeIf { it.isNotEmpty() }
      val rhs = m.groupValues[4].trim()
      val inferredType = declaredType ?: inferKotlinType(rhs)
      val col = line.indexOf(name).coerceAtLeast(0)
      val kind =
        when {
          line.contains("const val") -> SymbolKind.CONSTANT
          depth == 0 -> SymbolKind.PROPERTY
          else -> SymbolKind.VARIABLE
        }
      val typeLabel = inferredType ?: "Any"
      out.add(
        IndexedSymbol(
          name = name,
          kind = kind,
          signature = "$mut $name: $typeLabel",
          returnOrValueType = inferredType,
          filePath = filePath,
          line0Based = lineIdx,
          col0Based = col,
          endCol0Based = col + name.length,
          scopeDepth = depth,
          containerName = container,
        )
      )
    }

    KT_FOR_RE.find(line)?.let { m ->
      val loopVar = m.groupValues[1]
      val col = line.indexOf(loopVar).coerceAtLeast(0)
      out.add(
        IndexedSymbol(
          name = loopVar,
          kind = SymbolKind.VARIABLE,
          signature = "$loopVar (for loop)",
          returnOrValueType = null,
          filePath = filePath,
          line0Based = lineIdx,
          col0Based = col,
          endCol0Based = col + loopVar.length,
          scopeDepth = depth + 1,
          containerName = container,
        )
      )
    }

    KT_LAMBDA_PARAMS_RE.find(line)?.let { m ->
      val params = m.groupValues[1].split(',')
      for (raw in params) {
        val clean = raw.trim().substringBefore(':').trim()
        if (clean.matches(IDENT_RE)) {
          val col = line.indexOf(clean).coerceAtLeast(0)
          out.add(
            IndexedSymbol(
              name = clean,
              kind = SymbolKind.PARAMETER,
              signature = "$clean (lambda param)",
              returnOrValueType = null,
              filePath = filePath,
              line0Based = lineIdx,
              col0Based = col,
              endCol0Based = col + clean.length,
              scopeDepth = depth + 1,
              containerName = container,
            )
          )
        }
      }
    }
  }

  private fun parseJavaLine(
    line: String,
    lineIdx: Int,
    depth: Int,
    filePath: String,
    container: String?,
    out: MutableList<IndexedSymbol>,
    imports: MutableList<IndexedImport>,
    onEnterContainer: (String, Int) -> Unit,
  ) {
    JAVA_IMPORT_RE.find(line)?.let { m ->
      val fq = m.groupValues[1].trim()
      val shortName = fq.substringAfterLast('.')
      if (shortName != "*") {
        imports.add(IndexedImport(line.trim(), shortName, lineIdx, m.range.first, line.length))
      }
      return
    }
    JAVA_CLASS_RE.find(line)?.let { m ->
      val kw = m.groupValues[1]
      val name = m.groupValues[2]
      val col = line.indexOf(name).coerceAtLeast(0)
      val kind = if (kw == "interface") SymbolKind.INTERFACE else if (kw == "enum") SymbolKind.ENUM else SymbolKind.CLASS
      out.add(IndexedSymbol(name, kind, "$kw $name", name, filePath, lineIdx, col, col + name.length, depth, container))
      if (line.contains('{')) onEnterContainer(name, depth + 1)
      return
    }
    JAVA_METHOD_RE.find(line)?.let { m ->
      val ret = m.groupValues[1].trim()
      val name = m.groupValues[2]
      if (name !in JAVA_CONTROL_WORDS) {
        val params = m.groupValues[3].trim()
        val col = line.indexOf(name).coerceAtLeast(0)
        out.add(IndexedSymbol(name, SymbolKind.METHOD, "$ret $name($params)", ret, filePath, lineIdx, col, col + name.length, depth, container))
        if (params.isNotEmpty()) extractJavaParams(params, lineIdx, depth + 1, filePath, name, out)
      }
      return
    }
    JAVA_VAR_RE.find(line)?.let { m ->
      val typeName = m.groupValues[1]
      val name = m.groupValues[2]
      if (typeName !in JAVA_CONTROL_WORDS && name !in JAVA_CONTROL_WORDS) {
        val col = line.indexOf(name).coerceAtLeast(0)
        out.add(IndexedSymbol(name, SymbolKind.VARIABLE, "$typeName $name", typeName, filePath, lineIdx, col, col + name.length, depth, container))
      }
    }
  }

  private fun parseJsTsLine(
    line: String,
    lineIdx: Int,
    depth: Int,
    filePath: String,
    container: String?,
    out: MutableList<IndexedSymbol>,
    imports: MutableList<IndexedImport>,
  ) {
    JS_IMPORT_RE.find(line)?.let { m ->
      val names = m.groupValues[1].replace("{", "").replace("}", "").split(',')
      for (n in names) {
        val clean = n.trim().substringAfter(" as ").trim()
        if (clean.matches(IDENT_RE)) {
          imports.add(IndexedImport(line.trim(), clean, lineIdx, 0, line.length))
        }
      }
      return
    }
    JS_CLASS_RE.find(line)?.let { m ->
      val kind = if (m.groupValues[1] == "interface") SymbolKind.INTERFACE else SymbolKind.CLASS
      val name = m.groupValues[2]
      val col = line.indexOf(name).coerceAtLeast(0)
      out.add(IndexedSymbol(name, kind, "${m.groupValues[1]} $name", name, filePath, lineIdx, col, col + name.length, depth, container))
      return
    }
    JS_FN_RE.find(line)?.let { m ->
      val name = m.groupValues[1]
      val params = m.groupValues[2].trim()
      val ret = m.groupValues[3].trim().ifEmpty { "any" }
      val col = line.indexOf(name).coerceAtLeast(0)
      out.add(IndexedSymbol(name, SymbolKind.FUNCTION, "function $name($params): $ret", ret, filePath, lineIdx, col, col + name.length, depth, container))
      extractJsParams(params, lineIdx, depth + 1, filePath, name, out)
      return
    }
    JS_VAR_RE.find(line)?.let { m ->
      val kw = m.groupValues[1]
      val name = m.groupValues[2]
      val typeAnn = m.groupValues[3].trim().takeIf { it.isNotEmpty() }
      val rhs = m.groupValues[4].trim()
      val inferred = typeAnn ?: inferJsType(rhs)
      val col = line.indexOf(name).coerceAtLeast(0)
      val kind = if (kw == "const" && name.all { it.isUpperCase() || it == '_' || it.isDigit() }) SymbolKind.CONSTANT else SymbolKind.VARIABLE
      out.add(IndexedSymbol(name, kind, "$kw $name" + (inferred?.let { ": $it" } ?: ""), inferred, filePath, lineIdx, col, col + name.length, depth, container))
    }
  }

  private fun parsePythonLine(
    line: String,
    lineIdx: Int,
    filePath: String,
    out: MutableList<IndexedSymbol>,
    imports: MutableList<IndexedImport>,
  ) {
    val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it / 4 }
    PY_IMPORT_RE.find(line)?.let { m ->
      val parts = m.groupValues[1].split(',')
      for (p in parts) {
        val clean = p.trim().substringAfter(" as ").substringBefore('.').trim()
        if (clean.matches(IDENT_RE)) {
          imports.add(IndexedImport(line.trim(), clean, lineIdx, 0, line.length))
        }
      }
      return
    }
    PY_CLASS_RE.find(line)?.let { m ->
      val name = m.groupValues[1]
      val col = line.indexOf(name).coerceAtLeast(0)
      out.add(IndexedSymbol(name, SymbolKind.CLASS, "class $name", name, filePath, lineIdx, col, col + name.length, indent, null))
      return
    }
    PY_DEF_RE.find(line)?.let { m ->
      val name = m.groupValues[1]
      val params = m.groupValues[2].trim()
      val ret = m.groupValues[3].trim().takeIf { it.isNotEmpty() }
      val col = line.indexOf(name).coerceAtLeast(0)
      val sig = "def $name($params)" + (ret?.let { " -> $it" } ?: "")
      out.add(IndexedSymbol(name, SymbolKind.FUNCTION, sig, ret, filePath, lineIdx, col, col + name.length, indent, null))
      for (p in params.split(',')) {
        val pn = p.trim().substringBefore(':').substringBefore('=').trim()
        if (pn.matches(IDENT_RE) && pn != "self" && pn != "cls") {
          val pCol = line.indexOf(pn).coerceAtLeast(0)
          out.add(IndexedSymbol(pn, SymbolKind.PARAMETER, "param $pn", null, filePath, lineIdx, pCol, pCol + pn.length, indent + 1, name))
        }
      }
      return
    }
    PY_ASSIGN_RE.find(line)?.let { m ->
      val name = m.groupValues[1]
      val typeHint = m.groupValues[2].trim().takeIf { it.isNotEmpty() }
      val rhs = m.groupValues[3].trim()
      val inferred = typeHint ?: inferPythonType(rhs)
      val col = line.indexOf(name).coerceAtLeast(0)
      out.add(IndexedSymbol(name, SymbolKind.VARIABLE, "$name" + (inferred?.let { ": $it" } ?: ""), inferred, filePath, lineIdx, col, col + name.length, indent, null))
    }
    PY_FOR_RE.find(line)?.let { m ->
      val varName = m.groupValues[1].trim()
      if (varName.matches(IDENT_RE)) {
        val col = line.indexOf(varName).coerceAtLeast(0)
        out.add(IndexedSymbol(varName, SymbolKind.VARIABLE, "$varName (for)", null, filePath, lineIdx, col, col + varName.length, indent + 1, null))
      }
    }
  }

  private fun parseRustLine(line: String, lineIdx: Int, depth: Int, filePath: String, out: MutableList<IndexedSymbol>) {
    Regex("""\b(struct|enum|trait)\s+([A-Za-z_][A-Za-z0-9_]*)""").find(line)?.let { m ->
      val col = line.indexOf(m.groupValues[2]).coerceAtLeast(0)
      out.add(IndexedSymbol(m.groupValues[2], SymbolKind.CLASS, "${m.groupValues[1]} ${m.groupValues[2]}", m.groupValues[2], filePath, lineIdx, col, col + m.groupValues[2].length, depth))
      return
    }
    Regex("""\bfn\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""").find(line)?.let { m ->
      val name = m.groupValues[1]
      val col = line.indexOf(name).coerceAtLeast(0)
      out.add(IndexedSymbol(name, SymbolKind.FUNCTION, "fn $name(${m.groupValues[2].trim()})", null, filePath, lineIdx, col, col + name.length, depth))
      return
    }
    Regex("""\blet\s+(?:mut\s+)?([A-Za-z_][A-Za-z0-9_]*)""").find(line)?.let { m ->
      val name = m.groupValues[1]
      val col = line.indexOf(name).coerceAtLeast(0)
      out.add(IndexedSymbol(name, SymbolKind.VARIABLE, "let $name", null, filePath, lineIdx, col, col + name.length, depth))
    }
  }

  private fun parseGoLine(line: String, lineIdx: Int, depth: Int, filePath: String, out: MutableList<IndexedSymbol>) {
    Regex("""\btype\s+([A-Za-z_][A-Za-z0-9_]*)\s+(struct|interface)""").find(line)?.let { m ->
      val col = line.indexOf(m.groupValues[1]).coerceAtLeast(0)
      out.add(IndexedSymbol(m.groupValues[1], SymbolKind.CLASS, "type ${m.groupValues[1]} ${m.groupValues[2]}", m.groupValues[1], filePath, lineIdx, col, col + m.groupValues[1].length, depth))
      return
    }
    Regex("""\bfunc\s+(?:\([^)]+\)\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""").find(line)?.let { m ->
      val name = m.groupValues[1]
      val col = line.indexOf(name).coerceAtLeast(0)
      out.add(IndexedSymbol(name, SymbolKind.FUNCTION, "func $name(${m.groupValues[2].trim()})", null, filePath, lineIdx, col, col + name.length, depth))
      return
    }
    Regex("""^\s*(?:var\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*:?=""").find(line)?.let { m ->
      val name = m.groupValues[1]
      val col = line.indexOf(name).coerceAtLeast(0)
      out.add(IndexedSymbol(name, SymbolKind.VARIABLE, "var $name", null, filePath, lineIdx, col, col + name.length, depth))
    }
  }

  private fun parseCppLine(line: String, lineIdx: Int, depth: Int, filePath: String, out: MutableList<IndexedSymbol>) {
    Regex("""\b(class|struct)\s+([A-Za-z_][A-Za-z0-9_]*)""").find(line)?.let { m ->
      val col = line.indexOf(m.groupValues[2]).coerceAtLeast(0)
      out.add(IndexedSymbol(m.groupValues[2], SymbolKind.CLASS, "${m.groupValues[1]} ${m.groupValues[2]}", m.groupValues[2], filePath, lineIdx, col, col + m.groupValues[2].length, depth))
      return
    }
    Regex("""^\s*(?:[A-Za-z_][A-Za-z0-9_:*&<>]*\s+)+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:const)?\s*\{?""").find(line)?.let { m ->
      val name = m.groupValues[1]
      if (name !in JAVA_CONTROL_WORDS) {
        val col = line.indexOf(name).coerceAtLeast(0)
        out.add(IndexedSymbol(name, SymbolKind.FUNCTION, "$name(${m.groupValues[2].trim()})", null, filePath, lineIdx, col, col + name.length, depth))
      }
    }
  }

  private fun parseCssLine(line: String, lineIdx: Int, filePath: String, out: MutableList<IndexedSymbol>) {
    Regex("""(\.[A-Za-z_][A-Za-z0-9_-]*|#[A-Za-z_][A-Za-z0-9_-]*)""").findAll(line).forEach { m ->
      val sel = m.groupValues[1]
      out.add(IndexedSymbol(sel, SymbolKind.CLASS, "CSS selector $sel", null, filePath, lineIdx, m.range.first, m.range.last + 1, 0))
    }
  }

  private fun parseSqlLine(line: String, lineIdx: Int, filePath: String, out: MutableList<IndexedSymbol>) {
    Regex("""(?i)\bCREATE\s+(?:TABLE|VIEW)\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z_][A-Za-z0-9_]*)""").find(line)?.let { m ->
      val name = m.groupValues[1]
      val col = line.indexOf(name).coerceAtLeast(0)
      out.add(IndexedSymbol(name, SymbolKind.CLASS, "TABLE $name", name, filePath, lineIdx, col, col + name.length, 0))
    }
  }

  private fun parseShellLine(line: String, lineIdx: Int, filePath: String, out: MutableList<IndexedSymbol>) {
    Regex("""^\s*(?:function\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*\)""").find(line)?.let { m ->
      val name = m.groupValues[1]
      out.add(IndexedSymbol(name, SymbolKind.FUNCTION, "$name()", null, filePath, lineIdx, 0, name.length, 0))
      return
    }
    Regex("""^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=""").find(line)?.let { m ->
      val name = m.groupValues[1]
      out.add(IndexedSymbol(name, SymbolKind.VARIABLE, "$name=...", null, filePath, lineIdx, 0, name.length, 0))
    }
  }

  private fun extractTypedParams(
    paramsRaw: String,
    lineIdx: Int,
    depth: Int,
    filePath: String,
    container: String,
    out: MutableList<IndexedSymbol>,
  ) {
    for (part in splitTopLevelCommas(paramsRaw)) {
      val m = Regex("""(?:(?:val|var)\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([A-Za-z_][A-Za-z0-9_<>?*]*)""").find(part)
      if (m != null) {
        val pName = m.groupValues[1]
        val pType = m.groupValues[2]
        out.add(
          IndexedSymbol(
            name = pName,
            kind = SymbolKind.PARAMETER,
            signature = "$pName: $pType",
            returnOrValueType = pType,
            filePath = filePath,
            line0Based = lineIdx,
            scopeDepth = depth,
            containerName = container,
          )
        )
      }
    }
  }

  private fun extractJavaParams(
    paramsRaw: String,
    lineIdx: Int,
    depth: Int,
    filePath: String,
    container: String,
    out: MutableList<IndexedSymbol>,
  ) {
    for (part in splitTopLevelCommas(paramsRaw)) {
      val tokens = part.trim().split(Regex("\\s+")).filter { it != "final" }
      if (tokens.size >= 2) {
        val type = tokens[tokens.size - 2]
        val name = tokens.last()
        if (name.matches(IDENT_RE)) {
          out.add(IndexedSymbol(name, SymbolKind.PARAMETER, "$type $name", type, filePath, lineIdx, scopeDepth = depth, containerName = container))
        }
      }
    }
  }

  private fun extractJsParams(
    paramsRaw: String,
    lineIdx: Int,
    depth: Int,
    filePath: String,
    container: String,
    out: MutableList<IndexedSymbol>,
  ) {
    for (part in splitTopLevelCommas(paramsRaw)) {
      val clean = part.trim().removePrefix("...")
      val name = clean.substringBefore(':').substringBefore('=').trim()
      val type = clean.substringAfter(':', "").substringBefore('=').trim().takeIf { it.isNotEmpty() }
      if (name.matches(IDENT_RE)) {
        out.add(IndexedSymbol(name, SymbolKind.PARAMETER, name + (type?.let { ": $it" } ?: ""), type, filePath, lineIdx, scopeDepth = depth, containerName = container))
      }
    }
  }

  private fun splitTopLevelCommas(raw: String): List<String> {
    val result = ArrayList<String>()
    var angle = 0
    var paren = 0
    var start = 0
    for (i in raw.indices) {
      when (raw[i]) {
        '<' -> angle++
        '>' -> if (angle > 0) angle--
        '(' -> paren++
        ')' -> if (paren > 0) paren--
        ',' -> if (angle == 0 && paren == 0) {
          result.add(raw.substring(start, i))
          start = i + 1
        }
      }
    }
    if (start < raw.length) result.add(raw.substring(start))
    return result
  }

  private fun inferKotlinType(rhs: String): String? =
    when {
      rhs.startsWith("\"") -> "String"
      rhs.startsWith("'") -> "Char"
      rhs == "true" || rhs == "false" -> "Boolean"
      rhs.startsWith("listOf") || rhs.startsWith("mutableListOf") || rhs.startsWith("emptyList") -> "List"
      rhs.startsWith("mapOf") || rhs.startsWith("mutableMapOf") || rhs.startsWith("emptyMap") -> "Map"
      rhs.startsWith("setOf") || rhs.startsWith("mutableSetOf") -> "Set"
      rhs.startsWith("StringBuilder") -> "StringBuilder"
      rhs.matches(Regex("""-?\d+[lL]?""")) -> "Int"
      rhs.matches(Regex("""-?\d+\.\d+[fF]?""")) -> "Double"
      else -> Regex("""^([A-Z][A-Za-z0-9_]*)\s*\(""").find(rhs)?.groupValues?.get(1)
    }

  private fun inferJsType(rhs: String): String? =
    when {
      rhs.startsWith("\"") || rhs.startsWith("'") || rhs.startsWith("`") -> "string"
      rhs.startsWith("[") -> "Array"
      rhs == "true" || rhs == "false" -> "boolean"
      rhs.matches(Regex("""-?\d+(?:\.\d+)?""")) -> "number"
      else -> null
    }

  private fun inferPythonType(rhs: String): String? =
    when {
      rhs.startsWith("\"") || rhs.startsWith("'") || rhs.startsWith("f\"") || rhs.startsWith("f'") -> "str"
      rhs.startsWith("[") -> "list"
      rhs.startsWith("{") -> "dict"
      rhs == "True" || rhs == "False" -> "bool"
      rhs.matches(Regex("""-?\d+""")) -> "int"
      rhs.matches(Regex("""-?\d+\.\d+""")) -> "float"
      else -> null
    }

  private fun updateBraceDepth(line: String, current: Int): Int {
    var d = current
    var inStr: Char? = null
    var escaped = false
    for (i in line.indices) {
      val c = line[i]
      if (inStr != null) {
        if (escaped) escaped = false
        else if (c == '\\') escaped = true
        else if (c == inStr) inStr = null
        continue
      }
      if (c == '/' && i + 1 < line.length && line[i + 1] == '/') break
      if (c == '"' || c == '\'') {
        inStr = c
        continue
      }
      if (c == '{') d++
      else if (c == '}' && d > 0) d--
    }
    return d
  }

  private val IDENT_RE = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
  private val JAVA_CONTROL_WORDS = setOf("if", "for", "while", "switch", "catch", "synchronized", "return", "new", "throw", "else")

  private val KT_IMPORT_RE = Regex("""^\s*import\s+([A-Za-z_][A-Za-z0-9_.*]*)(?:\s+as\s+([A-Za-z_][A-Za-z0-9_]*))?""")
  private val KT_CLASS_RE = Regex("""^\s*(?:(?:public|private|internal|protected|open|abstract|sealed|data|value|inner|annotation|enum)\s+)*((?:data\s+|sealed\s+|enum\s+|annotation\s+)?class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*\(([^)]*)\))?""")
  private val KT_FUN_RE = Regex("""^\s*(?:(?:public|private|internal|protected|override|open|abstract|suspend|inline|operator|infix|tailrec)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[A-Za-z0-9_<>.?*]+\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?::\s*([A-Za-z_][A-Za-z0-9_<>.?*]*))?""")
  private val KT_VAL_VAR_RE = Regex("""^\s*(?:(?:public|private|internal|protected|override|const|lateinit)\s+)*(val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*([A-Za-z_][A-Za-z0-9_<>.?*]*))?\s*(?:=\s*(.+))?$""")
  private val KT_FOR_RE = Regex("""^\s*for\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s+in\b""")
  private val KT_LAMBDA_PARAMS_RE = Regex("""\{\s*([A-Za-z_][A-Za-z0-9_,\s:]*)\s*->""")

  private val JAVA_IMPORT_RE = Regex("""^\s*import\s+(?:static\s+)?([A-Za-z_][A-Za-z0-9_.*]*)\s*;""")
  private val JAVA_CLASS_RE = Regex("""^\s*(?:(?:public|private|protected|abstract|final|static)\s+)*(class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)""")
  private val JAVA_METHOD_RE = Regex("""^\s*(?:(?:public|private|protected|static|final|synchronized|abstract|default)\s+)*([A-Za-z_][A-Za-z0-9_<>\[\]]*)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:throws\s+[A-Za-z0-9_,\s]+)?\s*\{?""")
  private val JAVA_VAR_RE = Regex("""^\s*(?:final\s+)?([A-Za-z_][A-Za-z0-9_<>\[\]]*)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=|;)""")

  private val JS_IMPORT_RE = Regex("""^\s*import\s+(?:\*\s+as\s+)?([A-Za-z0-9_{},\s*]+)\s+from\s+""")
  private val JS_CLASS_RE = Regex("""^\s*(?:export\s+(?:default\s+)?)?(class|interface)\s+([A-Za-z_][A-Za-z0-9_]*)""")
  private val JS_FN_RE = Regex("""^\s*(?:export\s+(?:default\s+)?)?(?:async\s+)?function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?::\s*([A-Za-z_][A-Za-z0-9_<>\[\]|&]*))?""")
  private val JS_VAR_RE = Regex("""^\s*(?:export\s+)?(const|let|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*([A-Za-z_][A-Za-z0-9_<>\[\]|&]*))?\s*(?:=\s*(.+))?;?$""")

  private val PY_IMPORT_RE = Regex("""^\s*(?:from\s+[A-Za-z0-9_.]+\s+)?import\s+(.+)$""")
  private val PY_CLASS_RE = Regex("""^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)""")
  private val PY_DEF_RE = Regex("""^\s*(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:->\s*([A-Za-z_][A-Za-z0-9_\[\],.\s]*))?\s*:""")
  private val PY_ASSIGN_RE = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*([A-Za-z_][A-Za-z0-9_\[\],.\s]*))?\s*=\s*(.+)$""")
  private val PY_FOR_RE = Regex("""^\s*for\s+([A-Za-z_][A-Za-z0-9_]*)\s+in\b""")
}
