package com.quark.editor.syntax

import com.quark.editor.TokenType

/**
 * A single regex-backed token rule used by [RegexSyntaxHighlighter].
 * Lower [priority] rules or earlier matches win when overlapping spans occur.
 */
data class SyntaxRule(
  val regex: Regex,
  val type: TokenType,
  val group: Int = 0,
)

/**
 * Language definition driven by ordered Regex patterns plus multi-line comment/string delimiters.
 */
data class SyntaxGrammar(
  val id: String,
  val displayName: String,
  val extensions: Set<String>,
  val lineCommentPrefix: String? = "//",
  val blockCommentStart: String? = "/*",
  val blockCommentEnd: String? = "*/",
  val supportsTripleDoubleQuote: Boolean = false,
  val supportsTripleSingleQuote: Boolean = false,
  val rules: List<SyntaxRule>,
)

/**
 * Registry of pre-compiled Regex grammars for common programming, markup, config, and query languages.
 */
object SyntaxGrammarRegistry {

  private fun kwRegex(words: List<String>): Regex =
    Regex("""\b(?:${words.joinToString("|")})\b""")

  private val cStyleString = Regex(""""(?:\\.|[^"\\])*"""")
  private val singleQuoteString = Regex("""'(?:\\.|[^'\\])*'""")
  private val backtickString = Regex("""`(?:\\.|[^`\\])*`""")
  private val numberLiteral = Regex("""\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|\d[\d_]*(?:\.[\d_]+)?(?:[eE][+-]?\d+)?[fFdDlLuU]*)\b""")
  private val typeIdentifier = Regex("""\b[A-Z][A-Za-z0-9_]*\b""")
  private val functionCall = Regex("""\b([a-zA-Z_][A-Za-z0-9_]*)\s*(?=\()""")
  private val annotationRule = Regex("""@[A-Za-z_][A-Za-z0-9_.]*""")
  private val operatorRule = Regex("""(?:===|!==|==|!=|<=|>=|&&|\|\||->|=>|::|\?\?|\?:|\+=|-=|\*=|/=|[+\-*/%=!<>&|^~?])""")
  private val punctuationRule = Regex("""[{}()\[\].,;:]""")

  private val kotlinGrammar = SyntaxGrammar(
    id = "kotlin",
    displayName = "Kotlin",
    extensions = setOf("kt", "kts"),
    supportsTripleDoubleQuote = true,
    rules = listOf(
      SyntaxRule(Regex("""//.*$"""), TokenType.COMMENT),
      SyntaxRule(cStyleString, TokenType.STRING),
      SyntaxRule(singleQuoteString, TokenType.STRING),
      SyntaxRule(annotationRule, TokenType.ANNOTATION),
      SyntaxRule(
        kwRegex(
          listOf(
            "package", "import", "class", "interface", "object", "enum", "sealed", "data",
            "value", "inner", "open", "abstract", "override", "final", "private", "protected",
            "public", "internal", "companion", "fun", "val", "var", "const", "lateinit",
            "suspend", "inline", "noinline", "crossinline", "reified", "operator", "infix",
            "tailrec", "external", "if", "else", "when", "for", "while", "do", "return",
            "break", "continue", "throw", "try", "catch", "finally", "in", "is", "as",
            "by", "init", "constructor", "super", "this", "where", "typealias", "vararg",
            "out", "expect", "actual",
          )
        ),
        TokenType.KEYWORD,
      ),
      SyntaxRule(kwRegex(listOf("true", "false", "null", "Unit", "Nothing")), TokenType.CONSTANT),
      SyntaxRule(numberLiteral, TokenType.NUMBER),
      SyntaxRule(functionCall, TokenType.FUNCTION, group = 1),
      SyntaxRule(typeIdentifier, TokenType.TYPE),
      SyntaxRule(operatorRule, TokenType.OPERATOR),
      SyntaxRule(punctuationRule, TokenType.PUNCTUATION),
    ),
  )

  private val javaGrammar = SyntaxGrammar(
    id = "java",
    displayName = "Java",
    extensions = setOf("java"),
    rules = listOf(
      SyntaxRule(Regex("""//.*$"""), TokenType.COMMENT),
      SyntaxRule(cStyleString, TokenType.STRING),
      SyntaxRule(singleQuoteString, TokenType.STRING),
      SyntaxRule(annotationRule, TokenType.ANNOTATION),
      SyntaxRule(
        kwRegex(
          listOf(
            "package", "import", "class", "interface", "enum", "record", "extends", "implements",
            "public", "private", "protected", "static", "final", "abstract", "synchronized",
            "volatile", "transient", "native", "strictfp", "default", "void", "boolean",
            "byte", "short", "int", "long", "float", "double", "char", "var", "new",
            "if", "else", "switch", "case", "for", "while", "do", "break", "continue",
            "return", "throw", "throws", "try", "catch", "finally", "instanceof", "assert",
            "this", "super", "sealed", "permits", "non-sealed", "yield",
          )
        ),
        TokenType.KEYWORD,
      ),
      SyntaxRule(kwRegex(listOf("true", "false", "null")), TokenType.CONSTANT),
      SyntaxRule(numberLiteral, TokenType.NUMBER),
      SyntaxRule(functionCall, TokenType.FUNCTION, group = 1),
      SyntaxRule(typeIdentifier, TokenType.TYPE),
      SyntaxRule(operatorRule, TokenType.OPERATOR),
      SyntaxRule(punctuationRule, TokenType.PUNCTUATION),
    ),
  )

  private val jsTsGrammar = SyntaxGrammar(
    id = "javascript",
    displayName = "JavaScript / TS",
    extensions = setOf("js", "mjs", "cjs", "ts", "jsx", "tsx"),
    rules = listOf(
      SyntaxRule(Regex("""//.*$"""), TokenType.COMMENT),
      SyntaxRule(cStyleString, TokenType.STRING),
      SyntaxRule(singleQuoteString, TokenType.STRING),
      SyntaxRule(backtickString, TokenType.STRING),
      SyntaxRule(annotationRule, TokenType.ANNOTATION),
      SyntaxRule(
        kwRegex(
          listOf(
            "import", "export", "from", "default", "as", "function", "const", "let", "var",
            "class", "extends", "implements", "interface", "type", "enum", "namespace",
            "async", "await", "return", "if", "else", "switch", "case", "for", "of", "in",
            "while", "do", "break", "continue", "throw", "try", "catch", "finally", "new",
            "typeof", "instanceof", "keyof", "readonly", "private", "public", "protected",
            "abstract", "static", "yield", "delete", "void", "super", "this", "declare",
          )
        ),
        TokenType.KEYWORD,
      ),
      SyntaxRule(kwRegex(listOf("true", "false", "null", "undefined", "NaN", "Infinity", "console", "window", "document")), TokenType.CONSTANT),
      SyntaxRule(Regex("""</?([A-Za-z][A-Za-z0-9._-]*)"""), TokenType.TAG, group = 1),
      SyntaxRule(numberLiteral, TokenType.NUMBER),
      SyntaxRule(functionCall, TokenType.FUNCTION, group = 1),
      SyntaxRule(typeIdentifier, TokenType.TYPE),
      SyntaxRule(operatorRule, TokenType.OPERATOR),
      SyntaxRule(punctuationRule, TokenType.PUNCTUATION),
    ),
  )

  private val pythonGrammar = SyntaxGrammar(
    id = "python",
    displayName = "Python",
    extensions = setOf("py", "pyw"),
    lineCommentPrefix = "#",
    blockCommentStart = null,
    blockCommentEnd = null,
    supportsTripleDoubleQuote = true,
    supportsTripleSingleQuote = true,
    rules = listOf(
      SyntaxRule(Regex("""#.*$"""), TokenType.COMMENT),
      SyntaxRule(Regex("""[fFrRbBuU]{0,2}"(?:\\.|[^"\\])*""""), TokenType.STRING),
      SyntaxRule(Regex("""[fFrRbBuU]{0,2}'(?:\\.|[^'\\])*'"""), TokenType.STRING),
      SyntaxRule(annotationRule, TokenType.ANNOTATION),
      SyntaxRule(
        kwRegex(
          listOf(
            "def", "class", "lambda", "import", "from", "as", "if", "elif", "else",
            "for", "while", "in", "not", "and", "or", "is", "return", "yield", "pass",
            "break", "continue", "try", "except", "finally", "raise", "with", "assert",
            "global", "nonlocal", "async", "await", "del", "match", "case",
          )
        ),
        TokenType.KEYWORD,
      ),
      SyntaxRule(kwRegex(listOf("True", "False", "None", "self", "cls")), TokenType.CONSTANT),
      SyntaxRule(numberLiteral, TokenType.NUMBER),
      SyntaxRule(functionCall, TokenType.FUNCTION, group = 1),
      SyntaxRule(typeIdentifier, TokenType.TYPE),
      SyntaxRule(operatorRule, TokenType.OPERATOR),
      SyntaxRule(punctuationRule, TokenType.PUNCTUATION),
    ),
  )

  private val cCppGrammar = SyntaxGrammar(
    id = "cpp",
    displayName = "C / C++",
    extensions = setOf("c", "h", "cpp", "cc", "cxx", "hpp", "hh"),
    rules = listOf(
      SyntaxRule(Regex("""//.*$"""), TokenType.COMMENT),
      SyntaxRule(Regex("""^\s*#\s*[A-Za-z_]+"""), TokenType.ANNOTATION),
      SyntaxRule(cStyleString, TokenType.STRING),
      SyntaxRule(singleQuoteString, TokenType.STRING),
      SyntaxRule(
        kwRegex(
          listOf(
            "auto", "break", "case", "char", "const", "constexpr", "continue", "default",
            "do", "double", "else", "enum", "extern", "float", "for", "goto", "if",
            "inline", "int", "long", "register", "return", "short", "signed", "sizeof",
            "static", "struct", "switch", "typedef", "union", "unsigned", "void",
            "volatile", "while", "class", "namespace", "using", "template", "typename",
            "public", "private", "protected", "virtual", "override", "final", "new",
            "delete", "try", "catch", "throw", "noexcept", "explicit", "friend",
            "operator", "this", "nullptr",
          )
        ),
        TokenType.KEYWORD,
      ),
      SyntaxRule(kwRegex(listOf("true", "false", "NULL", "size_t", "uint8_t", "int32_t", "int64_t", "std")), TokenType.CONSTANT),
      SyntaxRule(numberLiteral, TokenType.NUMBER),
      SyntaxRule(functionCall, TokenType.FUNCTION, group = 1),
      SyntaxRule(typeIdentifier, TokenType.TYPE),
      SyntaxRule(operatorRule, TokenType.OPERATOR),
      SyntaxRule(punctuationRule, TokenType.PUNCTUATION),
    ),
  )

  private val rustGrammar = SyntaxGrammar(
    id = "rust",
    displayName = "Rust",
    extensions = setOf("rs"),
    rules = listOf(
      SyntaxRule(Regex("""//.*$"""), TokenType.COMMENT),
      SyntaxRule(Regex("""#!?\[[^\]]*\]"""), TokenType.ANNOTATION),
      SyntaxRule(cStyleString, TokenType.STRING),
      SyntaxRule(Regex("""[A-Za-z_][A-Za-z0-9_]*!"""), TokenType.FUNCTION),
      SyntaxRule(
        kwRegex(
          listOf(
            "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl",
            "type", "where", "pub", "mod", "use", "crate", "super", "self", "Self",
            "if", "else", "match", "for", "while", "loop", "in", "break", "continue",
            "return", "async", "await", "move", "ref", "as", "dyn", "unsafe", "extern",
          )
        ),
        TokenType.KEYWORD,
      ),
      SyntaxRule(kwRegex(listOf("true", "false", "Some", "None", "Ok", "Err")), TokenType.CONSTANT),
      SyntaxRule(Regex("""'[a-zA-Z_][a-zA-Z0-9_]*"""), TokenType.ANNOTATION),
      SyntaxRule(numberLiteral, TokenType.NUMBER),
      SyntaxRule(functionCall, TokenType.FUNCTION, group = 1),
      SyntaxRule(typeIdentifier, TokenType.TYPE),
      SyntaxRule(operatorRule, TokenType.OPERATOR),
      SyntaxRule(punctuationRule, TokenType.PUNCTUATION),
    ),
  )

  private val goGrammar = SyntaxGrammar(
    id = "go",
    displayName = "Go",
    extensions = setOf("go"),
    rules = listOf(
      SyntaxRule(Regex("""//.*$"""), TokenType.COMMENT),
      SyntaxRule(cStyleString, TokenType.STRING),
      SyntaxRule(singleQuoteString, TokenType.STRING),
      SyntaxRule(backtickString, TokenType.STRING),
      SyntaxRule(
        kwRegex(
          listOf(
            "package", "import", "func", "var", "const", "type", "struct", "interface",
            "map", "chan", "go", "defer", "select", "case", "default", "if", "else",
            "switch", "for", "range", "break", "continue", "goto", "fallthrough", "return",
          )
        ),
        TokenType.KEYWORD,
      ),
      SyntaxRule(kwRegex(listOf("true", "false", "nil", "iota", "string", "int", "int64", "float64", "bool", "error", "byte", "rune")), TokenType.CONSTANT),
      SyntaxRule(numberLiteral, TokenType.NUMBER),
      SyntaxRule(functionCall, TokenType.FUNCTION, group = 1),
      SyntaxRule(typeIdentifier, TokenType.TYPE),
      SyntaxRule(operatorRule, TokenType.OPERATOR),
      SyntaxRule(punctuationRule, TokenType.PUNCTUATION),
    ),
  )

  private val htmlXmlGrammar = SyntaxGrammar(
    id = "html",
    displayName = "HTML / XML",
    extensions = setOf("html", "htm", "xml", "svg"),
    lineCommentPrefix = null,
    blockCommentStart = "<!--",
    blockCommentEnd = "-->",
    rules = listOf(
      SyntaxRule(Regex("""<!--.*?-->"""), TokenType.COMMENT),
      SyntaxRule(cStyleString, TokenType.STRING),
      SyntaxRule(singleQuoteString, TokenType.STRING),
      SyntaxRule(Regex("""</?([A-Za-z][A-Za-z0-9:_-]*)"""), TokenType.TAG, group = 1),
      SyntaxRule(Regex("""\b([A-Za-z_:][A-Za-z0-9:_-]*)\s*="""), TokenType.ATTRIBUTE, group = 1),
      SyntaxRule(Regex("""</?|/?>"""), TokenType.PUNCTUATION),
    ),
  )

  private val cssGrammar = SyntaxGrammar(
    id = "css",
    displayName = "CSS",
    extensions = setOf("css", "scss", "sass"),
    rules = listOf(
      SyntaxRule(Regex("""//.*$"""), TokenType.COMMENT),
      SyntaxRule(cStyleString, TokenType.STRING),
      SyntaxRule(singleQuoteString, TokenType.STRING),
      SyntaxRule(Regex("""#[0-9a-fA-F]{3,8}\b"""), TokenType.NUMBER),
      SyntaxRule(Regex("""@[A-Za-z-]+"""), TokenType.KEYWORD),
      SyntaxRule(Regex("""([A-Za-z-]+)\s*:"""), TokenType.ATTRIBUTE, group = 1),
      SyntaxRule(Regex("""\.[A-Za-z_][A-Za-z0-9_-]*"""), TokenType.TYPE),
      SyntaxRule(Regex("""-?\d+(?:\.\d+)?(?:px|em|rem|vh|vw|%|deg|s|ms)?"""), TokenType.NUMBER),
      SyntaxRule(punctuationRule, TokenType.PUNCTUATION),
    ),
  )

  private val jsonGrammar = SyntaxGrammar(
    id = "json",
    displayName = "JSON",
    extensions = setOf("json", "jsonc"),
    lineCommentPrefix = "//",
    rules = listOf(
      SyntaxRule(Regex("""//.*$"""), TokenType.COMMENT),
      SyntaxRule(Regex(""""(?:\\.|[^"\\])*"(?=\s*:)"""), TokenType.ATTRIBUTE),
      SyntaxRule(cStyleString, TokenType.STRING),
      SyntaxRule(kwRegex(listOf("true", "false", "null")), TokenType.CONSTANT),
      SyntaxRule(Regex("""-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?"""), TokenType.NUMBER),
      SyntaxRule(punctuationRule, TokenType.PUNCTUATION),
    ),
  )

  private val yamlTomlGrammar = SyntaxGrammar(
    id = "yaml",
    displayName = "YAML / TOML",
    extensions = setOf("yaml", "yml", "toml"),
    lineCommentPrefix = "#",
    blockCommentStart = null,
    blockCommentEnd = null,
    rules = listOf(
      SyntaxRule(Regex("""#.*$"""), TokenType.COMMENT),
      SyntaxRule(Regex("""^\s*\[[^\]]+\]"""), TokenType.TAG),
      SyntaxRule(cStyleString, TokenType.STRING),
      SyntaxRule(singleQuoteString, TokenType.STRING),
      SyntaxRule(Regex("""^(\s*-?\s*[A-Za-z0-9_.-]+)(?=\s*[:=])"""), TokenType.ATTRIBUTE, group = 1),
      SyntaxRule(kwRegex(listOf("true", "false", "null", "yes", "no")), TokenType.CONSTANT),
      SyntaxRule(numberLiteral, TokenType.NUMBER),
      SyntaxRule(punctuationRule, TokenType.PUNCTUATION),
    ),
  )

  private val markdownGrammar = SyntaxGrammar(
    id = "markdown",
    displayName = "Markdown",
    extensions = setOf("md", "markdown", "mdx"),
    lineCommentPrefix = null,
    blockCommentStart = null,
    blockCommentEnd = null,
    rules = listOf(
      SyntaxRule(Regex("""^#{1,6}\s.*$"""), TokenType.KEYWORD),
      SyntaxRule(Regex("""`[^`]+`"""), TokenType.STRING),
      SyntaxRule(Regex("""\*\*[^*]+\*\*|__[^_]+__"""), TokenType.CONSTANT),
      SyntaxRule(Regex("""\[[^\]]+\]\([^)]+\)"""), TokenType.FUNCTION),
      SyntaxRule(Regex("""^\s*[-*+>]\s"""), TokenType.OPERATOR),
    ),
  )

  private val shellGrammar = SyntaxGrammar(
    id = "shell",
    displayName = "Shell / Bash",
    extensions = setOf("sh", "bash", "zsh"),
    lineCommentPrefix = "#",
    blockCommentStart = null,
    blockCommentEnd = null,
    rules = listOf(
      SyntaxRule(Regex("""#.*$"""), TokenType.COMMENT),
      SyntaxRule(cStyleString, TokenType.STRING),
      SyntaxRule(singleQuoteString, TokenType.STRING),
      SyntaxRule(Regex("""\$\{?[A-Za-z_][A-Za-z0-9_]*\}?|\$[0-9@*?#]"""), TokenType.VARIABLE),
      SyntaxRule(
        kwRegex(
          listOf(
            "if", "then", "else", "elif", "fi", "case", "esac", "for", "while", "until",
            "do", "done", "in", "function", "return", "local", "export", "readonly",
            "source", "alias", "echo", "printf", "exit", "cd", "pwd", "ls", "cat",
            "grep", "sed", "awk", "git",
          )
        ),
        TokenType.KEYWORD,
      ),
      SyntaxRule(numberLiteral, TokenType.NUMBER),
      SyntaxRule(operatorRule, TokenType.OPERATOR),
      SyntaxRule(punctuationRule, TokenType.PUNCTUATION),
    ),
  )

  private val sqlGrammar = SyntaxGrammar(
    id = "sql",
    displayName = "SQL",
    extensions = setOf("sql", "sqlite"),
    lineCommentPrefix = "--",
    rules = listOf(
      SyntaxRule(Regex("""--.*$"""), TokenType.COMMENT),
      SyntaxRule(singleQuoteString, TokenType.STRING),
      SyntaxRule(cStyleString, TokenType.STRING),
      SyntaxRule(
        kwRegex(
          listOf(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
            "DELETE", "CREATE", "TABLE", "DROP", "ALTER", "INDEX", "VIEW", "JOIN",
            "INNER", "LEFT", "RIGHT", "OUTER", "ON", "GROUP", "BY", "ORDER", "ASC",
            "DESC", "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "DISTINCT", "AS",
            "AND", "OR", "NOT", "IN", "IS", "NULL", "PRIMARY", "KEY", "FOREIGN",
            "REFERENCES", "DEFAULT", "BEGIN", "COMMIT", "ROLLBACK",
            "select", "from", "where", "insert", "into", "values", "update", "set",
            "delete", "create", "table", "drop", "alter", "index", "view", "join",
            "inner", "left", "right", "on", "group", "by", "order", "limit", "as",
            "and", "or", "not", "in", "is", "null", "primary", "key",
          )
        ),
        TokenType.KEYWORD,
      ),
      SyntaxRule(numberLiteral, TokenType.NUMBER),
      SyntaxRule(functionCall, TokenType.FUNCTION, group = 1),
      SyntaxRule(operatorRule, TokenType.OPERATOR),
      SyntaxRule(punctuationRule, TokenType.PUNCTUATION),
    ),
  )

  private val allGrammars: List<SyntaxGrammar> = listOf(
    kotlinGrammar,
    javaGrammar,
    jsTsGrammar,
    pythonGrammar,
    cCppGrammar,
    rustGrammar,
    goGrammar,
    htmlXmlGrammar,
    cssGrammar,
    jsonGrammar,
    yamlTomlGrammar,
    markdownGrammar,
    shellGrammar,
    sqlGrammar,
  )

  fun forFileName(fileName: String): SyntaxGrammar? {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) {
      return when (fileName.lowercase()) {
        "dockerfile" -> shellGrammar
        "makefile" -> shellGrammar
        ".bashrc", ".zshrc", ".profile" -> shellGrammar
        else -> null
      }
    }
    return allGrammars.firstOrNull { ext in it.extensions }
  }
}
