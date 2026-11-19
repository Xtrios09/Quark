package com.quark.intel.runtime

internal enum class KtTokKind {
  EOF,
  IDENT,
  INT_LIT,
  LONG_LIT,
  DOUBLE_LIT,
  STRING_LIT,
  CHAR_LIT,
  // Keywords
  KW_PACKAGE,
  KW_IMPORT,
  KW_FUN,
  KW_VAL,
  KW_VAR,
  KW_CLASS,
  KW_DATA,
  KW_OBJECT,
  KW_IF,
  KW_ELSE,
  KW_WHEN,
  KW_FOR,
  KW_WHILE,
  KW_DO,
  KW_IN,
  KW_IS,
  KW_AS,
  KW_RETURN,
  KW_BREAK,
  KW_CONTINUE,
  KW_TRY,
  KW_CATCH,
  KW_FINALLY,
  KW_THROW,
  KW_TRUE,
  KW_FALSE,
  KW_NULL,
  KW_THIS,
  // Operators & punctuation
  PLUS,
  MINUS,
  STAR,
  SLASH,
  PERCENT,
  EQ,
  EQ_EQ,
  BANG_EQ,
  LT,
  LT_EQ,
  GT,
  GT_EQ,
  AND_AND,
  OR_OR,
  BANG,
  ELVIS,
  SAFE_DOT,
  DOT,
  DOT_DOT,
  PLUS_EQ,
  MINUS_EQ,
  STAR_EQ,
  SLASH_EQ,
  PLUS_PLUS,
  MINUS_MINUS,
  ARROW,
  COLON,
  COMMA,
  SEMI,
  NEWLINE,
  LPAREN,
  RPAREN,
  LBRACE,
  RBRACE,
  LBRACK,
  RBRACK,
  QUESTION,
  AT,
}

internal data class KtTok(
  val kind: KtTokKind,
  val text: String,
  val line: Int,
  val col: Int,
)

internal class KtCompileException(
  val fileName: String,
  val line: Int,
  val col: Int,
  override val message: String,
) : RuntimeException("$fileName:$line:$col: error: $message")

internal class KtLexer(private val fileName: String, private val src: String) {
  private var pos = 0
  private var line = 1
  private var col = 1

  fun tokenize(): List<KtTok> {
    val out = ArrayList<KtTok>(256)
    while (pos < src.length) {
      val c = src[pos]
      when {
        c == '\r' -> pos++
        c == '\n' -> {
          out.add(KtTok(KtTokKind.NEWLINE, "\n", line, col))
          pos++
          line++
          col = 1
        }
        c == ' ' || c == '\t' -> {
          pos++
          col++
        }
        c == '/' && peek(1) == '/' -> {
          while (pos < src.length && src[pos] != '\n') {
            pos++
            col++
          }
        }
        c == '/' && peek(1) == '*' -> skipBlockComment()
        c == '"' && peek(1) == '"' && peek(2) == '"' -> out.add(readTripleString())
        c == '"' -> out.add(readString())
        c == '\'' -> out.add(readChar())
        c.isDigit() -> out.add(readNumber())
        c.isLetter() || c == '_' -> out.add(readIdentOrKeyword())
        c == '@' -> {
          val sc = col
          pos++
          col++
          // Skip annotation identifier cleanly if present
          while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) {
            pos++
            col++
          }
          if (pos < src.length && src[pos] == '(') {
            var d = 1
            pos++
            col++
            while (pos < src.length && d > 0) {
              if (src[pos] == '(') d++
              else if (src[pos] == ')') d--
              else if (src[pos] == '\n') { line++; col = 0 }
              pos++
              col++
            }
          }
          // Skip trailing newlines/whitespace after annotation is handled naturally
          val _unused = sc
        }
        else -> out.add(readSymbol())
      }
    }
    out.add(KtTok(KtTokKind.EOF, "", line, col))
    return out
  }

  private fun peek(offset: Int): Char = if (pos + offset < src.length) src[pos + offset] else '\u0000'

  private fun skipBlockComment() {
    val startLine = line
    val startCol = col
    pos += 2
    col += 2
    var depth = 1
    while (pos < src.length && depth > 0) {
      if (src[pos] == '/' && peek(1) == '*') {
        depth++
        pos += 2
        col += 2
      } else if (src[pos] == '*' && peek(1) == '/') {
        depth--
        pos += 2
        col += 2
      } else if (src[pos] == '\n') {
        pos++
        line++
        col = 1
      } else {
        pos++
        col++
      }
    }
    if (depth > 0) throw KtCompileException(fileName, startLine, startCol, "Unclosed block comment")
  }

  private fun readTripleString(): KtTok {
    val sl = line
    val sc = col
    pos += 3
    col += 3
    val sb = StringBuilder()
    while (pos < src.length) {
      if (src[pos] == '"' && peek(1) == '"' && peek(2) == '"') {
        pos += 3
        col += 3
        return KtTok(KtTokKind.STRING_LIT, sb.toString(), sl, sc)
      }
      val ch = src[pos]
      sb.append(ch)
      pos++
      if (ch == '\n') { line++; col = 1 } else col++
    }
    throw KtCompileException(fileName, sl, sc, "Unterminated raw string literal")
  }

  private fun readString(): KtTok {
    val sl = line
    val sc = col
    pos++
    col++
    val sb = StringBuilder()
    while (pos < src.length) {
      val ch = src[pos]
      if (ch == '\n') throw KtCompileException(fileName, sl, sc, "Unterminated string literal")
      if (ch == '"') {
        pos++
        col++
        return KtTok(KtTokKind.STRING_LIT, sb.toString(), sl, sc)
      }
      if (ch == '\\') {
        pos++
        col++
        if (pos >= src.length) throw KtCompileException(fileName, sl, sc, "Unterminated escape")
        val esc = src[pos]
        pos++
        col++
        sb.append(
          when (esc) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            '\\' -> '\\'
            '"' -> '"'
            '\'' -> '\''
            '$' -> '$'
            else -> esc
          }
        )
      } else {
        sb.append(ch)
        pos++
        col++
      }
    }
    throw KtCompileException(fileName, sl, sc, "Unterminated string literal")
  }

  private fun readChar(): KtTok {
    val sl = line
    val sc = col
    pos++
    col++
    if (pos >= src.length) throw KtCompileException(fileName, sl, sc, "Unterminated char literal")
    val ch =
      if (src[pos] == '\\') {
        pos++
        col++
        val esc = src.getOrNull(pos) ?: throw KtCompileException(fileName, sl, sc, "Unterminated char")
        pos++
        col++
        when (esc) {
          'n' -> '\n'
          't' -> '\t'
          'r' -> '\r'
          '\\' -> '\\'
          '\'' -> '\''
          else -> esc
        }
      } else {
        val v = src[pos]
        pos++
        col++
        v
      }
    if (pos >= src.length || src[pos] != '\'') {
      throw KtCompileException(fileName, sl, sc, "Too many characters in a character literal")
    }
    pos++
    col++
    return KtTok(KtTokKind.CHAR_LIT, ch.toString(), sl, sc)
  }

  private fun readNumber(): KtTok {
    val sl = line
    val sc = col
    val start = pos
    if (src[pos] == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
      pos += 2; col += 2
      while (pos < src.length && (src[pos].isDigit() || src[pos] in 'a'..'f' || src[pos] in 'A'..'F' || src[pos] == '_')) {
        pos++; col++
      }
      val raw = src.substring(start, pos).replace("_", "")
      return KtTok(KtTokKind.INT_LIT, raw, sl, sc)
    }
    var isDouble = false
    while (pos < src.length && (src[pos].isDigit() || src[pos] == '_')) {
      pos++; col++
    }
    if (pos < src.length && src[pos] == '.' && peek(1).isDigit()) {
      isDouble = true
      pos++; col++
      while (pos < src.length && (src[pos].isDigit() || src[pos] == '_')) {
        pos++; col++
      }
    }
    if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
      isDouble = true
      pos++; col++
      if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) { pos++; col++ }
      while (pos < src.length && src[pos].isDigit()) { pos++; col++ }
    }
    var isLong = false
    if (pos < src.length && (src[pos] == 'L' || src[pos] == 'l')) {
      isLong = true
      pos++; col++
    } else if (pos < src.length && (src[pos] == 'f' || src[pos] == 'F' || src[pos] == 'd' || src[pos] == 'D')) {
      isDouble = true
      pos++; col++
    }
    val raw = src.substring(start, pos).replace("_", "").trimEnd('L', 'l', 'f', 'F', 'd', 'D')
    val kind = when {
      isDouble -> KtTokKind.DOUBLE_LIT
      isLong -> KtTokKind.LONG_LIT
      else -> KtTokKind.INT_LIT
    }
    return KtTok(kind, raw, sl, sc)
  }

  private fun readIdentOrKeyword(): KtTok {
    val sl = line
    val sc = col
    val start = pos
    while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) {
      pos++; col++
    }
    val word = src.substring(start, pos)
    val kind =
      when (word) {
        "package" -> KtTokKind.KW_PACKAGE
        "import" -> KtTokKind.KW_IMPORT
        "fun" -> KtTokKind.KW_FUN
        "val" -> KtTokKind.KW_VAL
        "var" -> KtTokKind.KW_VAR
        "class" -> KtTokKind.KW_CLASS
        "data" -> KtTokKind.KW_DATA
        "object" -> KtTokKind.KW_OBJECT
        "if" -> KtTokKind.KW_IF
        "else" -> KtTokKind.KW_ELSE
        "when" -> KtTokKind.KW_WHEN
        "for" -> KtTokKind.KW_FOR
        "while" -> KtTokKind.KW_WHILE
        "do" -> KtTokKind.KW_DO
        "in" -> KtTokKind.KW_IN
        "is" -> KtTokKind.KW_IS
        "as" -> KtTokKind.KW_AS
        "return" -> KtTokKind.KW_RETURN
        "break" -> KtTokKind.KW_BREAK
        "continue" -> KtTokKind.KW_CONTINUE
        "try" -> KtTokKind.KW_TRY
        "catch" -> KtTokKind.KW_CATCH
        "finally" -> KtTokKind.KW_FINALLY
        "throw" -> KtTokKind.KW_THROW
        "true" -> KtTokKind.KW_TRUE
        "false" -> KtTokKind.KW_FALSE
        "null" -> KtTokKind.KW_NULL
        "this" -> KtTokKind.KW_THIS
        else -> KtTokKind.IDENT
      }
    return KtTok(kind, word, sl, sc)
  }

  private fun readSymbol(): KtTok {
    val sl = line
    val sc = col
    val c = src[pos]
    val next = peek(1)
    fun take(k: KtTokKind, len: Int): KtTok {
      val s = src.substring(pos, pos + len)
      pos += len
      col += len
      return KtTok(k, s, sl, sc)
    }
    return when {
      c == '=' && next == '=' -> take(KtTokKind.EQ_EQ, 2)
      c == '!' && next == '=' -> take(KtTokKind.BANG_EQ, 2)
      c == '<' && next == '=' -> take(KtTokKind.LT_EQ, 2)
      c == '>' && next == '=' -> take(KtTokKind.GT_EQ, 2)
      c == '&' && next == '&' -> take(KtTokKind.AND_AND, 2)
      c == '|' && next == '|' -> take(KtTokKind.OR_OR, 2)
      c == '?' && next == ':' -> take(KtTokKind.ELVIS, 2)
      c == '?' && next == '.' -> take(KtTokKind.SAFE_DOT, 2)
      c == '.' && next == '.' -> take(KtTokKind.DOT_DOT, 2)
      c == '+' && next == '=' -> take(KtTokKind.PLUS_EQ, 2)
      c == '-' && next == '=' -> take(KtTokKind.MINUS_EQ, 2)
      c == '*' && next == '=' -> take(KtTokKind.STAR_EQ, 2)
      c == '/' && next == '=' -> take(KtTokKind.SLASH_EQ, 2)
      c == '+' && next == '+' -> take(KtTokKind.PLUS_PLUS, 2)
      c == '-' && next == '-' -> take(KtTokKind.MINUS_MINUS, 2)
      c == '-' && next == '>' -> take(KtTokKind.ARROW, 2)
      c == '+' -> take(KtTokKind.PLUS, 1)
      c == '-' -> take(KtTokKind.MINUS, 1)
      c == '*' -> take(KtTokKind.STAR, 1)
      c == '/' -> take(KtTokKind.SLASH, 1)
      c == '%' -> take(KtTokKind.PERCENT, 1)
      c == '=' -> take(KtTokKind.EQ, 1)
      c == '<' -> take(KtTokKind.LT, 1)
      c == '>' -> take(KtTokKind.GT, 1)
      c == '!' -> take(KtTokKind.BANG, 1)
      c == '.' -> take(KtTokKind.DOT, 1)
      c == ':' -> take(KtTokKind.COLON, 1)
      c == ',' -> take(KtTokKind.COMMA, 1)
      c == ';' -> take(KtTokKind.SEMI, 1)
      c == '(' -> take(KtTokKind.LPAREN, 1)
      c == ')' -> take(KtTokKind.RPAREN, 1)
      c == '{' -> take(KtTokKind.LBRACE, 1)
      c == '}' -> take(KtTokKind.RBRACE, 1)
      c == '[' -> take(KtTokKind.LBRACK, 1)
      c == ']' -> take(KtTokKind.RBRACK, 1)
      c == '?' -> take(KtTokKind.QUESTION, 1)
      else -> throw KtCompileException(fileName, sl, sc, "Unexpected character '$c'")
    }
  }
}

// --- AST Nodes ---

internal sealed class KtDecl
internal data class KtParamDecl(val name: String, val isMutableProp: Boolean = false, val defaultExpr: KtExpr? = null)
internal data class KtFunDecl(
  val name: String,
  val params: List<KtParamDecl>,
  val body: List<KtStmt>,
  val singleExpr: KtExpr? = null,
) : KtDecl()
internal data class KtVarDecl(
  val name: String,
  val isMutable: Boolean,
  val initExpr: KtExpr?,
  val line: Int,
  val col: Int,
) : KtDecl()
internal data class KtClassDecl(
  val name: String,
  val isData: Boolean,
  val isObject: Boolean,
  val primaryParams: List<KtParamDecl>,
  val properties: List<KtVarDecl>,
  val methods: List<KtFunDecl>,
) : KtDecl()

internal sealed class KtStmt
internal data class KtVarStmt(val decl: KtVarDecl) : KtStmt()
internal data class KtExprStmt(val expr: KtExpr) : KtStmt()
internal data class KtAssignStmt(val target: KtExpr, val op: KtTokKind, val value: KtExpr, val line: Int, val col: Int) : KtStmt()
internal data class KtReturnStmt(val expr: KtExpr?) : KtStmt()
internal object KtBreakStmt : KtStmt()
internal object KtContinueStmt : KtStmt()
internal data class KtForStmt(val varName: String, val iterable: KtExpr, val body: List<KtStmt>) : KtStmt()
internal data class KtWhileStmt(val cond: KtExpr, val body: List<KtStmt>, val isDoWhile: Boolean = false) : KtStmt()
internal data class KtThrowStmt(val expr: KtExpr) : KtStmt()
internal data class KtTryStmt(val tryBody: List<KtStmt>, val catchVar: String?, val catchBody: List<KtStmt>?, val finallyBody: List<KtStmt>?) : KtStmt()
internal data class KtLocalFunStmt(val decl: KtFunDecl) : KtStmt()

internal sealed class KtExpr
internal data class KtLiteral(val value: Any?) : KtExpr()
internal data class KtStringTemplate(val raw: String, val line: Int, val col: Int) : KtExpr()
internal data class KtIdent(val name: String, val line: Int, val col: Int) : KtExpr()
internal object KtThisExpr : KtExpr()
internal data class KtUnaryExpr(val op: KtTokKind, val target: KtExpr, val isPrefix: Boolean) : KtExpr()
internal data class KtBinaryExpr(val op: String, val left: KtExpr, val right: KtExpr, val line: Int, val col: Int) : KtExpr()
internal data class KtRangeExpr(val left: KtExpr, val right: KtExpr, val inclusive: Boolean, val down: Boolean, val stepExpr: KtExpr? = null) : KtExpr()
internal data class KtMemberExpr(val receiver: KtExpr, val member: String, val safeCall: Boolean = false) : KtExpr()
internal data class KtIndexExpr(val receiver: KtExpr, val index: KtExpr) : KtExpr()
internal data class KtCallExpr(val callee: KtExpr, val args: List<Pair<String?, KtExpr>>, val trailingLambda: KtLambdaExpr? = null, val line: Int, val col: Int) : KtExpr()
internal data class KtLambdaExpr(val params: List<String>, val body: List<KtStmt>) : KtExpr()
internal data class KtIfExpr(val cond: KtExpr, val thenBody: List<KtStmt>, val elseBody: List<KtStmt>?) : KtExpr()
internal data class KtWhenBranch(val conditions: List<KtExpr>, val isElse: Boolean, val body: List<KtStmt>)
internal data class KtWhenExpr(val subject: KtExpr?, val branches: List<KtWhenBranch>) : KtExpr()
internal data class KtBlockExpr(val body: List<KtStmt>) : KtExpr()

internal data class KtProgramUnit(
  val fileName: String,
  val declarations: List<KtDecl>,
  val topLevelStatements: List<KtStmt>,
)

internal class KtParser(private val fileName: String, private val tokens: List<KtTok>) {
  private var idx = 0

  fun parseUnit(): KtProgramUnit {
    val decls = ArrayList<KtDecl>()
    val topStmts = ArrayList<KtStmt>()
    skipSeps()
    while (!atEof()) {
      when (cur().kind) {
        KtTokKind.KW_PACKAGE -> {
          advance()
          while (!atEof() && cur().kind != KtTokKind.NEWLINE && cur().kind != KtTokKind.SEMI) advance()
          skipSeps()
        }
        KtTokKind.KW_IMPORT -> {
          advance()
          while (!atEof() && cur().kind != KtTokKind.NEWLINE && cur().kind != KtTokKind.SEMI) advance()
          skipSeps()
        }
        KtTokKind.IDENT -> {
          if (cur().text in VISIBILITY_OR_MODIFIERS && peekKind(1) != KtTokKind.LPAREN && peekKind(1) != KtTokKind.DOT && peekKind(1) != KtTokKind.EQ) {
            advance()
            continue
          }
          topStmts.add(parseStmt())
          skipSeps()
        }
        KtTokKind.KW_FUN -> decls.add(parseFunDecl())
        KtTokKind.KW_DATA, KtTokKind.KW_CLASS, KtTokKind.KW_OBJECT -> decls.add(parseClassOrObjectDecl())
        KtTokKind.KW_VAL, KtTokKind.KW_VAR -> {
          val vd = parseVarDecl()
          decls.add(vd)
          topStmts.add(KtVarStmt(vd))
        }
        else -> topStmts.add(parseStmt())
      }
      skipSeps()
    }
    return KtProgramUnit(fileName, decls, topStmts)
  }

  private fun parseFunDecl(): KtFunDecl {
    expect(KtTokKind.KW_FUN)
    skipSeps()
    // Optional generic <T>
    if (cur().kind == KtTokKind.LT) skipBalanced(KtTokKind.LT, KtTokKind.GT)
    val nameTok = expect(KtTokKind.IDENT)
    skipSeps()
    expect(KtTokKind.LPAREN)
    val params = parseParamList()
    expect(KtTokKind.RPAREN)
    skipHorizontalSeps()
    if (cur().kind == KtTokKind.COLON) {
      advance()
      skipTypeExpr()
    }
    skipSeps()
    return if (cur().kind == KtTokKind.EQ) {
      advance()
      skipSeps()
      val expr = parseExpr()
      KtFunDecl(nameTok.text, params, emptyList(), singleExpr = expr)
    } else if (cur().kind == KtTokKind.LBRACE) {
      val body = parseBlock()
      KtFunDecl(nameTok.text, params, body, singleExpr = null)
    } else {
      KtFunDecl(nameTok.text, params, emptyList())
    }
  }

  private fun parseClassOrObjectDecl(): KtClassDecl {
    var isData = false
    if (cur().kind == KtTokKind.KW_DATA) {
      isData = true
      advance()
      skipSeps()
    }
    val isObject = cur().kind == KtTokKind.KW_OBJECT
    if (cur().kind == KtTokKind.KW_CLASS || cur().kind == KtTokKind.KW_OBJECT) {
      advance()
    } else {
      throw err("Expected 'class' or 'object'")
    }
    skipSeps()
    val name = expect(KtTokKind.IDENT).text
    skipHorizontalSeps()
    if (cur().kind == KtTokKind.LT) skipBalanced(KtTokKind.LT, KtTokKind.GT)
    var primaryParams = emptyList<KtParamDecl>()
    if (cur().kind == KtTokKind.LPAREN) {
      advance()
      primaryParams = parseParamList()
      expect(KtTokKind.RPAREN)
    }
    skipHorizontalSeps()
    // Skip optional `: Super(...)`
    if (cur().kind == KtTokKind.COLON) {
      advance()
      while (!atEof() && cur().kind != KtTokKind.LBRACE && cur().kind != KtTokKind.NEWLINE) advance()
    }
    skipSeps()
    val props = ArrayList<KtVarDecl>()
    val methods = ArrayList<KtFunDecl>()
    if (cur().kind == KtTokKind.LBRACE) {
      advance()
      skipSeps()
      while (!atEof() && cur().kind != KtTokKind.RBRACE) {
        when (cur().kind) {
          KtTokKind.KW_FUN -> methods.add(parseFunDecl())
          KtTokKind.KW_VAL, KtTokKind.KW_VAR -> props.add(parseVarDecl())
          KtTokKind.IDENT -> {
            if (cur().text in VISIBILITY_OR_MODIFIERS) advance() else advance()
          }
          else -> advance()
        }
        skipSeps()
      }
      expect(KtTokKind.RBRACE)
    }
    return KtClassDecl(name, isData, isObject, primaryParams, props, methods)
  }

  private fun parseParamList(): List<KtParamDecl> {
    val list = ArrayList<KtParamDecl>()
    skipSeps()
    while (!atEof() && cur().kind != KtTokKind.RPAREN) {
      if (cur().kind == KtTokKind.IDENT && cur().text == "vararg") advance()
      val isMut = if (cur().kind == KtTokKind.KW_VAL || cur().kind == KtTokKind.KW_VAR) {
        val m = cur().kind == KtTokKind.KW_VAR
        advance()
        m
      } else false
      val pName = expect(KtTokKind.IDENT).text
      skipSeps()
      if (cur().kind == KtTokKind.COLON) {
        advance()
        skipTypeExpr()
      }
      skipSeps()
      val defExpr = if (cur().kind == KtTokKind.EQ) {
        advance()
        skipSeps()
        parseExpr()
      } else null
      list.add(KtParamDecl(pName, isMut, defExpr))
      skipSeps()
      if (cur().kind == KtTokKind.COMMA) {
        advance()
        skipSeps()
      } else break
    }
    return list
  }

  private fun parseVarDecl(): KtVarDecl {
    val tok = cur()
    val isMut = tok.kind == KtTokKind.KW_VAR
    advance()
    skipSeps()
    val name = expect(KtTokKind.IDENT).text
    skipHorizontalSeps()
    if (cur().kind == KtTokKind.COLON) {
      advance()
      skipTypeExpr()
    }
    skipHorizontalSeps()
    val initExpr = if (cur().kind == KtTokKind.EQ) {
      advance()
      skipSeps()
      parseExpr()
    } else null
    return KtVarDecl(name, isMut, initExpr, tok.line, tok.col)
  }

  private fun parseBlock(): List<KtStmt> {
    expect(KtTokKind.LBRACE)
    skipSeps()
    val stmts = ArrayList<KtStmt>()
    while (!atEof() && cur().kind != KtTokKind.RBRACE) {
      stmts.add(parseStmt())
      skipSeps()
    }
    expect(KtTokKind.RBRACE)
    return stmts
  }

  private fun parseStmt(): KtStmt {
    skipSeps()
    return when (cur().kind) {
      KtTokKind.KW_VAL, KtTokKind.KW_VAR -> KtVarStmt(parseVarDecl())
      KtTokKind.KW_FUN -> KtLocalFunStmt(parseFunDecl())
      KtTokKind.KW_RETURN -> {
        advance()
        val hasExpr = !atEof() && cur().kind != KtTokKind.NEWLINE && cur().kind != KtTokKind.SEMI && cur().kind != KtTokKind.RBRACE
        KtReturnStmt(if (hasExpr) parseExpr() else null)
      }
      KtTokKind.KW_BREAK -> { advance(); KtBreakStmt }
      KtTokKind.KW_CONTINUE -> { advance(); KtContinueStmt }
      KtTokKind.KW_THROW -> { advance(); KtThrowStmt(parseExpr()) }
      KtTokKind.KW_FOR -> parseForStmt()
      KtTokKind.KW_WHILE -> parseWhileStmt()
      KtTokKind.KW_DO -> parseDoWhileStmt()
      KtTokKind.KW_TRY -> parseTryStmt()
      else -> {
        val tok = cur()
        val expr = parseExpr()
        skipHorizontalSeps()
        when (cur().kind) {
          KtTokKind.EQ, KtTokKind.PLUS_EQ, KtTokKind.MINUS_EQ, KtTokKind.STAR_EQ, KtTokKind.SLASH_EQ -> {
            val op = cur().kind
            advance()
            skipSeps()
            KtAssignStmt(expr, op, parseExpr(), tok.line, tok.col)
          }
          else -> KtExprStmt(expr)
        }
      }
    }
  }

  private fun parseForStmt(): KtForStmt {
    expect(KtTokKind.KW_FOR)
    skipSeps()
    expect(KtTokKind.LPAREN)
    skipSeps()
    val vName = expect(KtTokKind.IDENT).text
    skipSeps()
    expect(KtTokKind.KW_IN)
    skipSeps()
    val iter = parseExpr()
    skipSeps()
    expect(KtTokKind.RPAREN)
    skipSeps()
    val body = if (cur().kind == KtTokKind.LBRACE) parseBlock() else listOf(parseStmt())
    return KtForStmt(vName, iter, body)
  }

  private fun parseWhileStmt(): KtWhileStmt {
    expect(KtTokKind.KW_WHILE)
    skipSeps()
    expect(KtTokKind.LPAREN)
    skipSeps()
    val cond = parseExpr()
    skipSeps()
    expect(KtTokKind.RPAREN)
    skipSeps()
    val body = if (cur().kind == KtTokKind.LBRACE) parseBlock() else listOf(parseStmt())
    return KtWhileStmt(cond, body, isDoWhile = false)
  }

  private fun parseDoWhileStmt(): KtWhileStmt {
    expect(KtTokKind.KW_DO)
    skipSeps()
    val body = parseBlock()
    skipSeps()
    expect(KtTokKind.KW_WHILE)
    skipSeps()
    expect(KtTokKind.LPAREN)
    skipSeps()
    val cond = parseExpr()
    skipSeps()
    expect(KtTokKind.RPAREN)
    return KtWhileStmt(cond, body, isDoWhile = true)
  }

  private fun parseTryStmt(): KtTryStmt {
    expect(KtTokKind.KW_TRY)
    skipSeps()
    val tryBody = parseBlock()
    skipSeps()
    var catchVar: String? = null
    var catchBody: List<KtStmt>? = null
    if (cur().kind == KtTokKind.KW_CATCH) {
      advance()
      skipSeps()
      expect(KtTokKind.LPAREN)
      skipSeps()
      catchVar = expect(KtTokKind.IDENT).text
      if (cur().kind == KtTokKind.COLON) { advance(); skipTypeExpr() }
      skipSeps()
      expect(KtTokKind.RPAREN)
      skipSeps()
      catchBody = parseBlock()
      skipSeps()
    }
    var finallyBody: List<KtStmt>? = null
    if (cur().kind == KtTokKind.KW_FINALLY) {
      advance()
      skipSeps()
      finallyBody = parseBlock()
    }
    return KtTryStmt(tryBody, catchVar, catchBody, finallyBody)
  }

  // --- Expressions (precedence climbing) ---

  internal fun parseExpr(): KtExpr = parseElvis()

  private fun parseElvis(): KtExpr {
    var left = parseOr()
    while (cur().kind == KtTokKind.ELVIS) {
      val t = cur()
      advance()
      skipSeps()
      left = KtBinaryExpr("?:", left, parseOr(), t.line, t.col)
    }
    return left
  }

  private fun parseOr(): KtExpr {
    var left = parseAnd()
    while (cur().kind == KtTokKind.OR_OR) {
      val t = cur()
      advance()
      skipSeps()
      left = KtBinaryExpr("||", left, parseAnd(), t.line, t.col)
    }
    return left
  }

  private fun parseAnd(): KtExpr {
    var left = parseEquality()
    while (cur().kind == KtTokKind.AND_AND) {
      val t = cur()
      advance()
      skipSeps()
      left = KtBinaryExpr("&&", left, parseEquality(), t.line, t.col)
    }
    return left
  }

  private fun parseEquality(): KtExpr {
    var left = parseComparison()
    while (cur().kind == KtTokKind.EQ_EQ || cur().kind == KtTokKind.BANG_EQ) {
      val t = cur()
      advance()
      skipSeps()
      left = KtBinaryExpr(t.text, left, parseComparison(), t.line, t.col)
    }
    return left
  }

  private fun parseComparison(): KtExpr {
    var left = parseInfixToAndTypeCheck()
    while (cur().kind in setOf(KtTokKind.LT, KtTokKind.LT_EQ, KtTokKind.GT, KtTokKind.GT_EQ)) {
      val t = cur()
      advance()
      skipSeps()
      left = KtBinaryExpr(t.text, left, parseInfixToAndTypeCheck(), t.line, t.col)
    }
    return left
  }

  private fun parseInfixToAndTypeCheck(): KtExpr {
    var left = parseRange()
    while (cur().kind == KtTokKind.KW_IN || cur().kind == KtTokKind.KW_IS || cur().kind == KtTokKind.KW_AS || (cur().kind == KtTokKind.IDENT && cur().text == "to")) {
      val t = cur()
      advance()
      skipSeps()
      if (t.kind == KtTokKind.KW_IS || t.kind == KtTokKind.KW_AS) {
        val typeName = expect(KtTokKind.IDENT).text
        if (cur().kind == KtTokKind.QUESTION) advance()
        left = KtBinaryExpr(t.text, left, KtLiteral(typeName), t.line, t.col)
      } else {
        left = KtBinaryExpr(t.text, left, parseRange(), t.line, t.col)
      }
    }
    return left
  }

  private fun parseRange(): KtExpr {
    var left = parseAdditive()
    while (cur().kind == KtTokKind.DOT_DOT || (cur().kind == KtTokKind.IDENT && (cur().text == "until" || cur().text == "downTo"))) {
      val kind = cur()
      advance()
      skipSeps()
      val right = parseAdditive()
      var stepExpr: KtExpr? = null
      if (cur().kind == KtTokKind.IDENT && cur().text == "step") {
        advance()
        skipSeps()
        stepExpr = parseAdditive()
      }
      left = KtRangeExpr(left, right, inclusive = kind.kind == KtTokKind.DOT_DOT, down = kind.text == "downTo", stepExpr = stepExpr)
    }
    return left
  }

  private fun parseAdditive(): KtExpr {
    var left = parseMultiplicative()
    while (cur().kind == KtTokKind.PLUS || cur().kind == KtTokKind.MINUS) {
      val t = cur()
      advance()
      skipSeps()
      left = KtBinaryExpr(t.text, left, parseMultiplicative(), t.line, t.col)
    }
    return left
  }

  private fun parseMultiplicative(): KtExpr {
    var left = parseUnary()
    while (cur().kind == KtTokKind.STAR || cur().kind == KtTokKind.SLASH || cur().kind == KtTokKind.PERCENT) {
      val t = cur()
      advance()
      skipSeps()
      left = KtBinaryExpr(t.text, left, parseUnary(), t.line, t.col)
    }
    return left
  }

  private fun parseUnary(): KtExpr {
    return when (cur().kind) {
      KtTokKind.MINUS, KtTokKind.BANG, KtTokKind.PLUS_PLUS, KtTokKind.MINUS_MINUS -> {
        val k = cur().kind
        advance()
        KtUnaryExpr(k, parseUnary(), isPrefix = true)
      }
      else -> parsePostfix()
    }
  }

  private fun parsePostfix(): KtExpr {
    var expr = parsePrimary()
    while (true) {
      // Allow multiline chained `.foo`
      if (cur().kind == KtTokKind.NEWLINE && (peekNonNewlineKind() == KtTokKind.DOT || peekNonNewlineKind() == KtTokKind.SAFE_DOT)) {
        skipSeps()
      }
      when (cur().kind) {
        KtTokKind.DOT, KtTokKind.SAFE_DOT -> {
          val safe = cur().kind == KtTokKind.SAFE_DOT
          advance()
          skipSeps()
          val memberTok = expect(KtTokKind.IDENT)
          expr = KtMemberExpr(expr, memberTok.text, safe)
        }
        KtTokKind.LPAREN -> {
          val callTok = cur()
          advance()
          val args = parseCallArgs()
          expect(KtTokKind.RPAREN)
          skipHorizontalSeps()
          val trailing = if (cur().kind == KtTokKind.LBRACE) parseLambda() else null
          expr = KtCallExpr(expr, args, trailing, callTok.line, callTok.col)
        }
        KtTokKind.LBRACE -> {
          val callTok = cur()
          val trailing = parseLambda()
          expr = KtCallExpr(expr, emptyList(), trailing, callTok.line, callTok.col)
        }
        KtTokKind.LBRACK -> {
          advance()
          skipSeps()
          val indexExpr = parseExpr()
          skipSeps()
          expect(KtTokKind.RBRACK)
          expr = KtIndexExpr(expr, indexExpr)
        }
        KtTokKind.PLUS_PLUS, KtTokKind.MINUS_MINUS -> {
          val k = cur().kind
          advance()
          expr = KtUnaryExpr(k, expr, isPrefix = false)
        }
        else -> break
      }
    }
    return expr
  }

  private fun parseCallArgs(): List<Pair<String?, KtExpr>> {
    val args = ArrayList<Pair<String?, KtExpr>>()
    skipSeps()
    while (!atEof() && cur().kind != KtTokKind.RPAREN) {
      var named: String? = null
      if (cur().kind == KtTokKind.IDENT && peekKind(1) == KtTokKind.EQ) {
        named = cur().text
        advance()
        advance()
        skipSeps()
      }
      args.add(named to parseExpr())
      skipSeps()
      if (cur().kind == KtTokKind.COMMA) {
        advance()
        skipSeps()
      } else break
    }
    return args
  }

  private fun parseLambda(): KtLambdaExpr {
    expect(KtTokKind.LBRACE)
    skipSeps()
    // Check if there is an explicit `a, b ->` header before any statements
    val params = ArrayList<String>()
    val saved = idx
    var foundArrow = false
    while (!atEof() && cur().kind == KtTokKind.IDENT) {
      params.add(cur().text)
      advance()
      skipHorizontalSeps()
      if (cur().kind == KtTokKind.COLON) { advance(); skipTypeExpr(); skipHorizontalSeps() }
      if (cur().kind == KtTokKind.COMMA) {
        advance()
        skipSeps()
      } else if (cur().kind == KtTokKind.ARROW) {
        advance()
        skipSeps()
        foundArrow = true
        break
      } else {
        break
      }
    }
    if (!foundArrow) {
      idx = saved
      params.clear()
    }
    val body = ArrayList<KtStmt>()
    while (!atEof() && cur().kind != KtTokKind.RBRACE) {
      body.add(parseStmt())
      skipSeps()
    }
    expect(KtTokKind.RBRACE)
    return KtLambdaExpr(params, body)
  }

  private fun parsePrimary(): KtExpr {
    val t = cur()
    return when (t.kind) {
      KtTokKind.INT_LIT -> {
        advance()
        val v = if (t.text.startsWith("0x", true)) t.text.substring(2).toInt(16) else t.text.toIntOrNull() ?: t.text.toLong()
        KtLiteral(v)
      }
      KtTokKind.LONG_LIT -> { advance(); KtLiteral(t.text.toLong()) }
      KtTokKind.DOUBLE_LIT -> { advance(); KtLiteral(t.text.toDouble()) }
      KtTokKind.STRING_LIT -> { advance(); KtStringTemplate(t.text, t.line, t.col) }
      KtTokKind.CHAR_LIT -> { advance(); KtLiteral(t.text.firstOrNull() ?: ' ') }
      KtTokKind.KW_TRUE -> { advance(); KtLiteral(true) }
      KtTokKind.KW_FALSE -> { advance(); KtLiteral(false) }
      KtTokKind.KW_NULL -> { advance(); KtLiteral(null) }
      KtTokKind.KW_THIS -> { advance(); KtThisExpr }
      KtTokKind.IDENT -> { advance(); KtIdent(t.text, t.line, t.col) }
      KtTokKind.LPAREN -> {
        advance()
        skipSeps()
        val inner = parseExpr()
        skipSeps()
        expect(KtTokKind.RPAREN)
        inner
      }
      KtTokKind.KW_IF -> parseIfExpr()
      KtTokKind.KW_WHEN -> parseWhenExpr()
      KtTokKind.LBRACE -> parseLambda()
      else -> throw err("Unexpected token '${t.text.ifEmpty { t.kind.name }}'")
    }
  }

  private fun parseIfExpr(): KtIfExpr {
    expect(KtTokKind.KW_IF)
    skipSeps()
    expect(KtTokKind.LPAREN)
    skipSeps()
    val cond = parseExpr()
    skipSeps()
    expect(KtTokKind.RPAREN)
    skipSeps()
    val thenBody = if (cur().kind == KtTokKind.LBRACE) parseBlock() else listOf(parseStmt())
    val saved = idx
    skipSeps()
    val elseBody =
      if (cur().kind == KtTokKind.KW_ELSE) {
        advance()
        skipSeps()
        if (cur().kind == KtTokKind.LBRACE) parseBlock() else listOf(parseStmt())
      } else {
        idx = saved
        null
      }
    return KtIfExpr(cond, thenBody, elseBody)
  }

  private fun parseWhenExpr(): KtWhenExpr {
    expect(KtTokKind.KW_WHEN)
    skipSeps()
    val subject =
      if (cur().kind == KtTokKind.LPAREN) {
        advance()
        skipSeps()
        val s = parseExpr()
        skipSeps()
        expect(KtTokKind.RPAREN)
        s
      } else null
    skipSeps()
    expect(KtTokKind.LBRACE)
    skipSeps()
    val branches = ArrayList<KtWhenBranch>()
    while (!atEof() && cur().kind != KtTokKind.RBRACE) {
      val isElse = cur().kind == KtTokKind.IDENT && cur().text == "else" || cur().kind == KtTokKind.KW_ELSE
      val conds = ArrayList<KtExpr>()
      if (isElse) {
        advance()
      } else {
        while (true) {
          conds.add(parseExpr())
          skipHorizontalSeps()
          if (cur().kind == KtTokKind.COMMA) { advance(); skipSeps() } else break
        }
      }
      skipSeps()
      expect(KtTokKind.ARROW)
      skipSeps()
      val body = if (cur().kind == KtTokKind.LBRACE) parseBlock() else listOf(parseStmt())
      branches.add(KtWhenBranch(conds, isElse, body))
      skipSeps()
    }
    expect(KtTokKind.RBRACE)
    return KtWhenExpr(subject, branches)
  }

  private fun skipTypeExpr() {
    skipHorizontalSeps()
    if (cur().kind == KtTokKind.IDENT) advance()
    if (cur().kind == KtTokKind.LT) skipBalanced(KtTokKind.LT, KtTokKind.GT)
    while (cur().kind == KtTokKind.DOT) {
      advance()
      if (cur().kind == KtTokKind.IDENT) advance()
      if (cur().kind == KtTokKind.LT) skipBalanced(KtTokKind.LT, KtTokKind.GT)
    }
    if (cur().kind == KtTokKind.QUESTION) advance()
  }

  private fun skipBalanced(open: KtTokKind, close: KtTokKind) {
    if (cur().kind != open) return
    advance()
    var depth = 1
    while (!atEof() && depth > 0) {
      if (cur().kind == open) depth++
      else if (cur().kind == close) depth--
      advance()
    }
  }

  private fun skipSeps() {
    while (!atEof() && (cur().kind == KtTokKind.NEWLINE || cur().kind == KtTokKind.SEMI)) advance()
  }

  private fun skipHorizontalSeps() {
    while (!atEof() && cur().kind == KtTokKind.SEMI) advance()
  }

  private fun peekNonNewlineKind(): KtTokKind {
    var i = idx
    while (i < tokens.size && tokens[i].kind == KtTokKind.NEWLINE) i++
    return tokens.getOrNull(i)?.kind ?: KtTokKind.EOF
  }

  private fun peekKind(offset: Int): KtTokKind = tokens.getOrNull(idx + offset)?.kind ?: KtTokKind.EOF
  private fun cur(): KtTok = tokens.getOrElse(idx) { tokens.last() }
  private fun atEof(): Boolean = cur().kind == KtTokKind.EOF
  private fun advance(): KtTok = cur().also { if (idx < tokens.lastIndex) idx++ }

  private fun expect(kind: KtTokKind): KtTok {
    val t = cur()
    if (t.kind != kind) {
      throw err("Expected ${kind.name} but found '${t.text.ifEmpty { t.kind.name }}'")
    }
    return advance()
  }

  private fun err(msg: String): KtCompileException {
    val t = cur()
    return KtCompileException(fileName, t.line, t.col, msg)
  }

  companion object {
    private val VISIBILITY_OR_MODIFIERS = setOf(
      "public", "private", "internal", "protected", "override", "open", "abstract",
      "const", "lateinit", "suspend", "inline", "tailrec", "operator", "infix",
    )
  }
}
