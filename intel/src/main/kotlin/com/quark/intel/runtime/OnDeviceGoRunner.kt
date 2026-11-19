package com.quark.intel.runtime

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 100% on-device pure-Kotlin Go compiler & AST evaluator supporting:
 * - `package main`, `import "fmt"` / `import (...)`
 * - `func main()` & typed functions (including multi-parameter shorthand & multi-return tuples)
 * - `:=` short declarations, `var`, `const`, slices (`[]int{...}`), maps, `append`, `len`
 * - `for i := 0; i < n; i++`, `for _, v := range items`, `for cond {}`, `if / else`, `switch`
 * - `fmt.Println`, `fmt.Print`, `fmt.Printf`, `fmt.Sprintf`, `strings.*`, `math.*`, `strconv.*`
 */
object OnDeviceGoRunner {

  private const val MAX_STEPS = 1_000_000

  private enum class GK {
    EOF, NEWLINE, IDENT, INT_LIT, DOUBLE_LIT, STRING_LIT,
    KW_PACKAGE, KW_IMPORT, KW_FUNC, KW_VAR, KW_CONST, KW_TYPE, KW_STRUCT,
    KW_IF, KW_ELSE, KW_FOR, KW_RANGE, KW_SWITCH, KW_CASE, KW_DEFAULT,
    KW_RETURN, KW_BREAK, KW_CONTINUE, KW_TRUE, KW_FALSE, KW_NIL,
    PLUS, MINUS, STAR, SLASH, PERCENT, DECLARE, EQ, EQ_EQ, BANG_EQ, LT, LT_EQ, GT, GT_EQ,
    AND_AND, OR_OR, BANG, PLUS_EQ, MINUS_EQ, STAR_EQ, SLASH_EQ, PLUS_PLUS, MINUS_MINUS,
    DOT, COLON, SEMI, COMMA, LPAREN, RPAREN, LBRACE, RBRACE, LBRACK, RBRACK
  }

  private data class GTok(val kind: GK, val text: String, val line: Int, val col: Int)
  private class GCompileErr(file: String, line: Int, col: Int, msg: String) :
    RuntimeException("$file:$line:$col: error: $msg")

  private class ReturnSig(val values: List<Any?>) : RuntimeException(null, null, false, false)
  private object BreakSig : RuntimeException(null, null, false, false)
  private object ContinueSig : RuntimeException(null, null, false, false)

  private data class GFun(val name: String, val params: List<String>, val body: List<GStmt>)

  private sealed interface GStmt {
    val line: Int
    data class ShortDecl(override val line: Int, val names: List<String>, val values: List<GExpr>) : GStmt
    data class VarDecl(override val line: Int, val name: String, val init: GExpr?) : GStmt
    data class Assign(override val line: Int, val targets: List<GExpr>, val op: String, val values: List<GExpr>) : GStmt
    data class Block(override val line: Int, val stmts: List<GStmt>) : GStmt
    data class If(override val line: Int, val init: GStmt?, val cond: GExpr, val thenBlk: Block, val elseBlk: GStmt?) : GStmt
    data class For(override val line: Int, val init: GStmt?, val cond: GExpr?, val step: GStmt?, val body: Block) : GStmt
    data class RangeFor(override val line: Int, val kVar: String?, val vVar: String?, val iter: GExpr, val body: Block) : GStmt
    data class Switch(override val line: Int, val target: GExpr?, val cases: List<Pair<List<GExpr>?, List<GStmt>>>) : GStmt
    data class Return(override val line: Int, val values: List<GExpr>) : GStmt
    data class Break(override val line: Int) : GStmt
    data class Continue(override val line: Int) : GStmt
    data class ExprS(override val line: Int, val expr: GExpr) : GStmt
  }

  private sealed interface GExpr {
    val line: Int
    data class Lit(override val line: Int, val value: Any?) : GExpr
    data class Ident(override val line: Int, val name: String) : GExpr
    data class SliceLit(override val line: Int, val items: List<GExpr>) : GExpr
    data class MapLit(override val line: Int, val entries: List<Pair<GExpr, GExpr>>) : GExpr
    data class Unary(override val line: Int, val op: String, val inner: GExpr, val postfix: Boolean = false) : GExpr
    data class Binary(override val line: Int, val op: String, val l: GExpr, val r: GExpr) : GExpr
    data class Call(override val line: Int, val callee: GExpr, val args: List<GExpr>) : GExpr
    data class Member(override val line: Int, val recv: GExpr, val name: String) : GExpr
    data class Index(override val line: Int, val recv: GExpr, val idx: GExpr) : GExpr
  }

  private class Env(val parent: Env? = null) {
    val vars = LinkedHashMap<String, Any?>()
    fun def(name: String, v: Any?) { if (name != "_") vars[name] = v }
    fun get(name: String): Pair<Boolean, Any?> {
      var cur: Env? = this
      while (cur != null) {
        if (cur.vars.containsKey(name)) return true to cur.vars[name]
        cur = cur.parent
      }
      return false to null
    }
    fun set(name: String, v: Any?): Boolean {
      var cur: Env? = this
      while (cur != null) {
        if (cur.vars.containsKey(name)) { cur.vars[name] = v; return true }
        cur = cur.parent
      }
      return false
    }
  }

  fun run(
    fileName: String,
    source: String,
    onOutputLine: (String) -> Unit = {},
  ): ScriptExecutionOutput {
    val stdout = ArrayList<String>()
    val curLine = StringBuilder()

    fun flushText(s: String, newline: Boolean) {
      for (ch in s) {
        if (ch == '\n') {
          val l = curLine.toString(); stdout.add(l); onOutputLine(l); curLine.setLength(0)
        } else curLine.append(ch)
      }
      if (newline) {
        val l = curLine.toString(); stdout.add(l); onOutputLine(l); curLine.setLength(0)
      }
    }

    return try {
      val tokens = lex(fileName, source)
      val parser = GParser(fileName, tokens)
      val (pkgName, funs, globals) = parser.parseProgram()
      if (pkgName.isEmpty()) throw GCompileErr(fileName, 1, 1, "expected 'package main'")
      if ("main" !in funs) throw GCompileErr(fileName, 1, 1, "function main is undeclared in package $pkgName")

      val global = Env()
      var steps = 0
      fun tick() { if (++steps > MAX_STEPS) error("Execution step limit exceeded") }

      fun toNum(v: Any?) = (v as? Number)?.toDouble() ?: 0.0
      fun toInt(v: Any?) = (v as? Number)?.toInt() ?: 0
      fun truthy(v: Any?) = (v as? Boolean) ?: false

      lateinit var eval: (GExpr, Env) -> Any?
      lateinit var exec: (GStmt, Env) -> Unit

      fun formatGoPrintf(fmt: String, args: List<Any?>): String {
        val sb = StringBuilder()
        var i = 0; var aIdx = 0
        while (i < fmt.length) {
          val c = fmt[i]
          if (c == '%' && i + 1 < fmt.length) {
            if (fmt[i + 1] == '%') { sb.append('%'); i += 2; continue }
            var j = i + 1
            while (j < fmt.length && (fmt[j].isDigit() || fmt[j] == '.' || fmt[j] == '-' || fmt[j] == '+')) j++
            if (j < fmt.length) {
              val spec = fmt[j]
              val arg = args.getOrNull(aIdx++)
              val text = when (spec) {
                'v', 'T', 'q', 's' -> if (spec == 'q') "\"$arg\"" else arg?.toString() ?: ""
                'd', 'x', 'X' -> String.format(Locale.US, fmt.substring(i, j + 1), (arg as? Number)?.toLong() ?: 0L)
                'f', 'g' -> String.format(Locale.US, fmt.substring(i, j + 1), (arg as? Number)?.toDouble() ?: 0.0)
                't' -> (arg as? Boolean)?.toString() ?: "false"
                else -> arg?.toString() ?: ""
              }
              sb.append(text); i = j + 1; continue
            }
          }
          sb.append(c); i++
        }
        return sb.toString()
      }

      fun bin(op: String, l: Any?, r: Any?): Any =
        if (op == "+" && (l is String || r is String)) "${l ?: ""}${r ?: ""}"
        else if (l is Double || r is Double) {
          val a = toNum(l); val b = toNum(r)
          when (op) {
            "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> a / b; "%" -> a % b
            "<" -> a < b; "<=" -> a <= b; ">" -> a > b; ">=" -> a >= b
            else -> error("op $op")
          }
        } else {
          val a = toInt(l); val b = toInt(r)
          when (op) {
            "+" -> a + b; "-" -> a - b; "*" -> a * b
            "/" -> if (b == 0) error("integer divide by zero") else a / b
            "%" -> if (b == 0) error("integer divide by zero") else a % b
            "<" -> a < b; "<=" -> a <= b; ">" -> a > b; ">=" -> a >= b
            else -> error("op $op")
          }
        }

      fun callFn(name: String, args: List<Any?>, line: Int): Any? {
        funs[name]?.let { fn ->
          val scope = Env(global)
          fn.params.forEachIndexed { idx, p -> scope.def(p, args.getOrNull(idx)) }
          return try {
            for (st in fn.body) exec(st, scope)
            null
          } catch (r: ReturnSig) {
            if (r.values.size <= 1) r.values.firstOrNull() else r.values
          }
        }
        return when (name) {
          "len" -> when (val x = args[0]) {
            is Collection<*> -> x.size
            is Map<*, *> -> x.size
            is String -> x.length
            else -> 0
          }
          "cap" -> (args[0] as? Collection<*>)?.size ?: 0
          "append" -> {
            val base = (args[0] as? List<*>)?.toMutableList() ?: mutableListOf()
            for (k in 1 until args.size) base.add(args[k])
            base
          }
          "println" -> { flushText(args.joinToString(" ") { it.toString() }, newline = true); null }
          "print" -> { flushText(args.joinToString(" ") { it.toString() }, newline = false); null }
          "fmt.Println" -> { flushText(args.joinToString(" ") { it.toString() }, newline = true); null }
          "fmt.Print" -> { flushText(args.joinToString("") { it.toString() }, newline = false); null }
          "fmt.Printf" -> { flushText(formatGoPrintf(args.firstOrNull()?.toString() ?: "", args.drop(1)), newline = false); null }
          "fmt.Sprintf" -> formatGoPrintf(args.firstOrNull()?.toString() ?: "", args.drop(1))
          "strings.ToUpper" -> args[0].toString().uppercase()
          "strings.ToLower" -> args[0].toString().lowercase()
          "strings.TrimSpace" -> args[0].toString().trim()
          "strings.Contains" -> args[0].toString().contains(args[1].toString())
          "strings.Split" -> args[0].toString().split(args[1].toString()).toMutableList()
          "strings.Join" -> (args[0] as Iterable<*>).joinToString(args[1].toString())
          "strings.ReplaceAll" -> args[0].toString().replace(args[1].toString(), args[2].toString())
          "math.Sqrt" -> sqrt(toNum(args[0]))
          "math.Pow" -> toNum(args[0]).pow(toNum(args[1]))
          "math.Abs" -> abs(toNum(args[0]))
          "math.Max" -> max(toNum(args[0]), toNum(args[1]))
          "math.Min" -> min(toNum(args[0]), toNum(args[1]))
          "math.Floor" -> floor(toNum(args[0]))
          "math.Ceil" -> ceil(toNum(args[0]))
          "strconv.Itoa" -> args[0].toString()
          "strconv.Atoi" -> {
            val parsed = args[0].toString().trim().toIntOrNull()
            if (parsed != null) listOf(parsed, null) else listOf(0, "strconv.Atoi: invalid syntax")
          }
          "sort.Ints" -> {
            @Suppress("UNCHECKED_CAST")
            (args[0] as? MutableList<Any?>)?.sortBy { toInt(it) }
            null
          }
          else -> throw GCompileErr(fileName, line, 1, "undefined: $name")
        }
      }

      eval = { expr, env ->
        tick()
        when (expr) {
          is GExpr.Lit -> expr.value
          is GExpr.Ident -> {
            if (expr.name == "math.Pi") PI
            else {
              val (ok, v) = env.get(expr.name)
              if (!ok) throw GCompileErr(fileName, expr.line, 1, "undefined: ${expr.name}")
              v
            }
          }
          is GExpr.SliceLit -> expr.items.map { eval(it, env) }.toMutableList()
          is GExpr.MapLit -> {
            val m = LinkedHashMap<Any?, Any?>()
            for ((k, v) in expr.entries) m[eval(k, env)] = eval(v, env)
            m
          }
          is GExpr.Unary -> {
            if (expr.op == "++" || expr.op == "--") {
              val id = expr.inner as? GExpr.Ident ?: throw GCompileErr(fileName, expr.line, 1, "invalid increment")
              val cur = env.get(id.name).second
              val next = if (expr.op == "++") toInt(cur) + 1 else toInt(cur) - 1
              env.set(id.name, next)
              if (expr.postfix) cur else next
            } else {
              val v = eval(expr.inner, env)
              when (expr.op) {
                "-" -> if (v is Double) -v else -toInt(v)
                "!" -> !truthy(v)
                else -> v
              }
            }
          }
          is GExpr.Binary -> when (expr.op) {
            "&&" -> truthy(eval(expr.l, env)) && truthy(eval(expr.r, env))
            "||" -> truthy(eval(expr.l, env)) || truthy(eval(expr.r, env))
            "==" -> {
              val a = eval(expr.l, env); val b = eval(expr.r, env)
              if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
            }
            "!=" -> {
              val a = eval(expr.l, env); val b = eval(expr.r, env)
              if (a is Number && b is Number) a.toDouble() != b.toDouble() else a != b
            }
            else -> bin(expr.op, eval(expr.l, env), eval(expr.r, env))
          }
          is GExpr.Member -> {
            val r = expr.recv
            if (r is GExpr.Ident && r.name == "math" && expr.name == "Pi") PI
            else null
          }
          is GExpr.Index -> {
            val recv = eval(expr.recv, env); val k = eval(expr.idx, env)
            when (recv) {
              is List<*> -> recv[toInt(k)]
              is Map<*, *> -> recv[k]
              is String -> recv[toInt(k)].code
              else -> null
            }
          }
          is GExpr.Call -> {
            val args = expr.args.map { eval(it, env) }
            when (val c = expr.callee) {
              is GExpr.Ident -> callFn(c.name, args, expr.line)
              is GExpr.Member -> {
                val pkg = (c.recv as? GExpr.Ident)?.name
                if (pkg != null) callFn("$pkg.${c.name}", args, expr.line)
                else throw GCompileErr(fileName, expr.line, 1, "invalid selector call")
              }
              else -> throw GCompileErr(fileName, expr.line, 1, "cannot call non-function")
            }
          }
        }
      }

      exec = { st, env ->
        tick()
        when (st) {
          is GStmt.ShortDecl -> {
            val rhsVals = if (st.values.size == 1 && st.names.size > 1) {
              val multi = eval(st.values[0], env)
              (multi as? List<*>) ?: listOf(multi)
            } else st.values.map { eval(it, env) }
            st.names.forEachIndexed { idx, name -> env.def(name, rhsVals.getOrNull(idx)) }
          }
          is GStmt.VarDecl -> env.def(st.name, st.init?.let { eval(it, env) } ?: 0)
          is GStmt.Assign -> {
            val rhsVals = if (st.values.size == 1 && st.targets.size > 1) {
              val multi = eval(st.values[0], env)
              (multi as? List<*>) ?: listOf(multi)
            } else st.values.map { eval(it, env) }
            st.targets.forEachIndexed { idx, target ->
              val v = rhsVals.getOrNull(idx)
              when (target) {
                is GExpr.Ident -> {
                  if (target.name != "_") {
                    val cur = env.get(target.name)
                    if (!cur.first) throw GCompileErr(fileName, st.line, 1, "undefined: ${target.name}")
                    val next = when (st.op) {
                      "+=" -> bin("+", cur.second, v); "-=" -> bin("-", cur.second, v)
                      "*=" -> bin("*", cur.second, v); "/=" -> bin("/", cur.second, v)
                      else -> v
                    }
                    env.set(target.name, next)
                  }
                }
                is GExpr.Index -> {
                  val recv = eval(target.recv, env); val k = eval(target.idx, env)
                  @Suppress("UNCHECKED_CAST")
                  if (recv is MutableList<*>) (recv as MutableList<Any?>)[toInt(k)] = v
                  else if (recv is MutableMap<*, *>) (recv as MutableMap<Any?, Any?>)[k] = v
                }
                else -> throw GCompileErr(fileName, st.line, 1, "invalid assignment target")
              }
            }
          }
          is GStmt.Block -> {
            val child = Env(env)
            for (s in st.stmts) exec(s, child)
          }
          is GStmt.If -> {
            val ifEnv = Env(env)
            st.init?.let { exec(it, ifEnv) }
            if (truthy(eval(st.cond, ifEnv))) exec(st.thenBlk, ifEnv)
            else st.elseBlk?.let { exec(it, ifEnv) }
          }
          is GStmt.For -> {
            val forEnv = Env(env)
            st.init?.let { exec(it, forEnv) }
            while (st.cond == null || truthy(eval(st.cond, forEnv))) {
              tick()
              try { exec(st.body, forEnv) } catch (_: ContinueSig) {} catch (_: BreakSig) { break }
              st.step?.let { exec(it, forEnv) }
            }
          }
          is GStmt.RangeFor -> {
            val iterVal = eval(st.iter, env)
            val pairs: List<Pair<Any?, Any?>> = when (iterVal) {
              is Iterable<*> -> iterVal.mapIndexed { idx, v -> idx to v }
              is Map<*, *> -> iterVal.entries.map { it.key to it.value }
              is String -> iterVal.mapIndexed { idx, c -> idx to c.code }
              else -> throw GCompileErr(fileName, st.line, 1, "cannot range over $iterVal")
            }
            for ((k, v) in pairs) {
              tick()
              val rEnv = Env(env)
              st.kVar?.let { rEnv.def(it, k) }
              st.vVar?.let { rEnv.def(it, v) }
              try { exec(st.body, rEnv) } catch (_: ContinueSig) {} catch (_: BreakSig) { break }
            }
          }
          is GStmt.Switch -> {
            val target = st.target?.let { eval(it, env) }
            var matched = false
            for ((exprs, body) in st.cases) {
              val hit = if (exprs == null) !matched else exprs.any {
                val cv = eval(it, env)
                if (st.target == null) truthy(cv) else cv == target
              }
              if (hit) {
                matched = true
                for (s in body) exec(s, env)
                break
              }
            }
          }
          is GStmt.Return -> throw ReturnSig(st.values.map { eval(it, env) })
          is GStmt.Break -> throw BreakSig
          is GStmt.Continue -> throw ContinueSig
          is GStmt.ExprS -> eval(st.expr, env)
        }
      }

      for (g in globals) exec(g, global)
      callFn("main", emptyList(), 1)
      if (curLine.isNotEmpty()) {
        val rem = curLine.toString(); stdout.add(rem); onOutputLine(rem)
      }
      ScriptExecutionOutput(0, stdout)
    } catch (e: GCompileErr) {
      val msg = e.message ?: "Go compile error"
      stdout.add(msg); onOutputLine(msg)
      ScriptExecutionOutput(1, stdout, msg)
    } catch (t: Throwable) {
      val msg = "$fileName: ${t.message ?: t::class.simpleName}"
      stdout.add(msg); onOutputLine(msg)
      ScriptExecutionOutput(1, stdout, msg)
    }
  }

  private fun lex(fileName: String, src: String): List<GTok> {
    val out = ArrayList<GTok>(256)
    var i = 0; var line = 1; var col = 1
    fun peek(off: Int = 1) = if (i + off < src.length) src[i + off] else '\u0000'
    while (i < src.length) {
      val c = src[i]
      when {
        c == '\r' -> i++
        c == '\n' -> {
          val lastKind = out.lastOrNull()?.kind
          if (lastKind in setOf(GK.IDENT, GK.INT_LIT, GK.DOUBLE_LIT, GK.STRING_LIT, GK.KW_TRUE, GK.KW_FALSE,
              GK.KW_NIL, GK.KW_RETURN, GK.KW_BREAK, GK.KW_CONTINUE, GK.PLUS_PLUS, GK.MINUS_MINUS,
              GK.RPAREN, GK.RBRACK, GK.RBRACE)
          ) {
            out.add(GTok(GK.SEMI, ";", line, col))
          }
          i++; line++; col = 1
        }
        c.isWhitespace() -> { i++; col++ }
        c == '/' && peek() == '/' -> while (i < src.length && src[i] != '\n') { i++; col++ }
        c == '/' && peek() == '*' -> {
          val sl = line; val sc = col; i += 2; col += 2
          var closed = false
          while (i < src.length) {
            if (src[i] == '*' && peek() == '/') { i += 2; col += 2; closed = true; break }
            if (src[i] == '\n') { line++; col = 1 } else col++
            i++
          }
          if (!closed) throw GCompileErr(fileName, sl, sc, "unterminated block comment")
        }
        c == '"' || c == '`' -> {
          val quote = c; val sl = line; val sc = col; i++; col++
          val sb = StringBuilder(); var closed = false
          while (i < src.length) {
            val ch = src[i]
            if (ch == quote) { i++; col++; closed = true; break }
            if (ch == '\n' && quote == '"') throw GCompileErr(fileName, sl, sc, "unterminated string literal")
            if (ch == '\n') { sb.append('\n'); i++; line++; col = 1; continue }
            if (quote == '"' && ch == '\\' && i + 1 < src.length) {
              val n = src[i + 1]
              sb.append(when (n) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '\\' -> '\\'; '"' -> '"'; else -> n })
              i += 2; col += 2
            } else { sb.append(ch); i++; col++ }
          }
          if (!closed) throw GCompileErr(fileName, sl, sc, "unterminated string literal")
          out.add(GTok(GK.STRING_LIT, sb.toString(), sl, sc))
        }
        c.isDigit() -> {
          val sc = col; val start = i; var hasDot = false
          while (i < src.length && (src[i].isDigit() || (src[i] == '.' && !hasDot && peek().isDigit()))) {
            if (src[i] == '.') hasDot = true
            i++; col++
          }
          out.add(GTok(if (hasDot) GK.DOUBLE_LIT else GK.INT_LIT, src.substring(start, i), line, sc))
        }
        c.isLetter() || c == '_' -> {
          val sc = col; val start = i
          while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) { i++; col++ }
          val w = src.substring(start, i)
          val k = when (w) {
            "package" -> GK.KW_PACKAGE; "import" -> GK.KW_IMPORT; "func" -> GK.KW_FUNC
            "var" -> GK.KW_VAR; "const" -> GK.KW_CONST; "type" -> GK.KW_TYPE; "struct" -> GK.KW_STRUCT
            "if" -> GK.KW_IF; "else" -> GK.KW_ELSE; "for" -> GK.KW_FOR; "range" -> GK.KW_RANGE
            "switch" -> GK.KW_SWITCH; "case" -> GK.KW_CASE; "default" -> GK.KW_DEFAULT
            "return" -> GK.KW_RETURN; "break" -> GK.KW_BREAK; "continue" -> GK.KW_CONTINUE
            "true" -> GK.KW_TRUE; "false" -> GK.KW_FALSE; "nil" -> GK.KW_NIL
            else -> GK.IDENT
          }
          out.add(GTok(k, w, line, sc))
        }
        else -> {
          val sc = col; val two = "$c${peek()}"
          val m2 = when (two) {
            ":=" -> GK.DECLARE; "==" -> GK.EQ_EQ; "!=" -> GK.BANG_EQ
            "<=" -> GK.LT_EQ; ">=" -> GK.GT_EQ; "&&" -> GK.AND_AND; "||" -> GK.OR_OR
            "+=" -> GK.PLUS_EQ; "-=" -> GK.MINUS_EQ; "*=" -> GK.STAR_EQ; "/=" -> GK.SLASH_EQ
            "++" -> GK.PLUS_PLUS; "--" -> GK.MINUS_MINUS
            else -> null
          }
          if (m2 != null) { out.add(GTok(m2, two, line, sc)); i += 2; col += 2 }
          else {
            val k = when (c) {
              '+' -> GK.PLUS; '-' -> GK.MINUS; '*' -> GK.STAR; '/' -> GK.SLASH; '%' -> GK.PERCENT
              '=' -> GK.EQ; '<' -> GK.LT; '>' -> GK.GT; '!' -> GK.BANG
              '.' -> GK.DOT; ':' -> GK.COLON; ';' -> GK.SEMI; ',' -> GK.COMMA
              '(' -> GK.LPAREN; ')' -> GK.RPAREN; '{' -> GK.LBRACE; '}' -> GK.RBRACE
              '[' -> GK.LBRACK; ']' -> GK.RBRACK
              else -> throw GCompileErr(fileName, line, col, "unexpected character '$c'")
            }
            out.add(GTok(k, c.toString(), line, sc)); i++; col++
          }
        }
      }
    }
    out.add(GTok(GK.EOF, "", line, col))
    return out
  }

  private class GParser(private val fileName: String, private val tokens: List<GTok>) {
    private var idx = 0
    private fun cur() = tokens[idx.coerceAtMost(tokens.lastIndex)]
    private fun match(k: GK): Boolean { if (cur().kind == k) { idx++; return true }; return false }
    private fun expect(k: GK, msg: String): GTok {
      val t = cur()
      if (t.kind != k) throw GCompileErr(fileName, t.line, t.col, msg)
      idx++; return t
    }
    private fun eatSemis() { while (match(GK.SEMI)) {} }

    fun parseProgram(): Triple<String, Map<String, GFun>, List<GStmt>> {
      var pkgName = ""
      val funs = LinkedHashMap<String, GFun>()
      val globals = ArrayList<GStmt>()
      eatSemis()
      if (match(GK.KW_PACKAGE)) {
        pkgName = expect(GK.IDENT, "expected package name").text
        eatSemis()
      }
      while (cur().kind != GK.EOF) {
        when (cur().kind) {
          GK.KW_IMPORT -> {
            idx++
            if (match(GK.LPAREN)) {
              while (cur().kind != GK.EOF && cur().kind != GK.RPAREN) idx++
              expect(GK.RPAREN, "expected ')'")
            } else if (cur().kind == GK.STRING_LIT) {
              idx++
            } else {
              while (cur().kind != GK.EOF && cur().kind != GK.SEMI) idx++
            }
            eatSemis()
          }
          GK.KW_FUNC -> {
            val fn = parseFunc()
            funs[fn.name] = fn
            eatSemis()
          }
          GK.KW_VAR, GK.KW_CONST -> {
            val ln = cur().line; idx++
            val name = expect(GK.IDENT, "expected var name").text
            if (cur().kind != GK.EQ && cur().kind != GK.SEMI) skipGoType()
            val init = if (match(GK.EQ)) parseExpr() else null
            globals.add(GStmt.VarDecl(ln, name, init))
            eatSemis()
          }
          GK.KW_TYPE -> {
            while (cur().kind != GK.EOF && cur().kind != GK.LBRACE && cur().kind != GK.SEMI) idx++
            if (match(GK.LBRACE)) {
              var d = 1
              while (d > 0 && cur().kind != GK.EOF) {
                if (cur().kind == GK.LBRACE) d++ else if (cur().kind == GK.RBRACE) d--
                idx++
              }
            }
            eatSemis()
          }
          GK.SEMI -> idx++
          else -> throw GCompileErr(fileName, cur().line, cur().col, "unexpected top-level token '${cur().text}'")
        }
      }
      return Triple(pkgName, funs, globals)
    }

    private fun skipGoType() {
      when (cur().kind) {
        GK.LBRACK -> { idx++; match(GK.RBRACK); skipGoType() }
        GK.IDENT -> {
          val w = cur().text; idx++
          if (w == "map" && match(GK.LBRACK)) {
            while (cur().kind != GK.RBRACK && cur().kind != GK.EOF) idx++
            match(GK.RBRACK)
            skipGoType()
          }
        }
        GK.LPAREN -> {
          idx++; var d = 1
          while (d > 0 && cur().kind != GK.EOF) {
            if (cur().kind == GK.LPAREN) d++ else if (cur().kind == GK.RPAREN) d--
            idx++
          }
        }
        else -> {}
      }
    }

    private fun parseFunc(): GFun {
      expect(GK.KW_FUNC, "expected func")
      val name = expect(GK.IDENT, "expected function name").text
      expect(GK.LPAREN, "expected '('")
      val params = ArrayList<String>()
      if (cur().kind != GK.RPAREN) {
        val pendingNames = ArrayList<String>()
        while (cur().kind != GK.RPAREN && cur().kind != GK.EOF) {
          val pName = expect(GK.IDENT, "expected param name").text
          pendingNames.add(pName)
          if (match(GK.COMMA)) continue
          // Otherwise next is type
          skipGoType()
          params.addAll(pendingNames)
          pendingNames.clear()
          if (!match(GK.COMMA)) break
        }
        params.addAll(pendingNames)
      }
      expect(GK.RPAREN, "expected ')'")
      if (cur().kind != GK.LBRACE) skipGoType()
      val blk = parseBlock()
      return GFun(name, params, blk.stmts)
    }

    private fun parseBlock(): GStmt.Block {
      val open = expect(GK.LBRACE, "expected '{'")
      val stmts = ArrayList<GStmt>()
      eatSemis()
      while (cur().kind != GK.EOF && cur().kind != GK.RBRACE) {
        stmts.add(parseStmt())
        eatSemis()
      }
      expect(GK.RBRACE, "expected '}'")
      return GStmt.Block(open.line, stmts)
    }

    private fun parseStmt(): GStmt {
      val t = cur()
      return when (t.kind) {
        GK.LBRACE -> parseBlock()
        GK.KW_VAR -> {
          idx++
          val n = expect(GK.IDENT, "expected variable name").text
          if (cur().kind != GK.EQ && cur().kind != GK.SEMI) skipGoType()
          val init = if (match(GK.EQ)) parseExpr() else null
          GStmt.VarDecl(t.line, n, init)
        }
        GK.KW_IF -> parseIf(t.line)
        GK.KW_FOR -> parseFor(t.line)
        GK.KW_SWITCH -> parseSwitch(t.line)
        GK.KW_RETURN -> {
          idx++
          val vals = ArrayList<GExpr>()
          if (cur().kind != GK.SEMI && cur().kind != GK.RBRACE) {
            while (true) {
              vals.add(parseExpr())
              if (!match(GK.COMMA)) break
            }
          }
          GStmt.Return(t.line, vals)
        }
        GK.KW_BREAK -> { idx++; GStmt.Break(t.line) }
        GK.KW_CONTINUE -> { idx++; GStmt.Continue(t.line) }
        else -> parseSimpleStmt()
      }
    }

    private fun parseSimpleStmt(): GStmt {
      val t = cur()
      val lhs = mutableListOf(parseExpr())
      while (match(GK.COMMA)) lhs.add(parseExpr())

      return when (cur().kind) {
        GK.DECLARE -> {
          idx++
          val names = lhs.map { (it as? GExpr.Ident)?.name ?: throw GCompileErr(fileName, t.line, t.col, "non-name on left side of :=") }
          val vals = mutableListOf(parseExpr())
          while (match(GK.COMMA)) vals.add(parseExpr())
          GStmt.ShortDecl(t.line, names, vals)
        }
        GK.EQ, GK.PLUS_EQ, GK.MINUS_EQ, GK.STAR_EQ, GK.SLASH_EQ -> {
          val op = cur().text; idx++
          val vals = mutableListOf(parseExpr())
          while (match(GK.COMMA)) vals.add(parseExpr())
          GStmt.Assign(t.line, lhs, op, vals)
        }
        else -> GStmt.ExprS(t.line, lhs.first())
      }
    }

    private fun parseIf(line: Int): GStmt.If {
      expect(GK.KW_IF, "expected if")
      val first = parseSimpleStmt()
      var init: GStmt? = null
      val cond: GExpr
      if (match(GK.SEMI)) {
        init = first
        cond = parseExpr()
      } else {
        cond = (first as? GStmt.ExprS)?.expr ?: throw GCompileErr(fileName, line, 1, "expected condition in if")
      }
      val thenBlk = parseBlock()
      val elseBlk = if (match(GK.KW_ELSE)) {
        if (cur().kind == GK.KW_IF) parseIf(cur().line) else parseBlock()
      } else null
      return GStmt.If(line, init, cond, thenBlk, elseBlk)
    }

    private fun parseFor(line: Int): GStmt {
      expect(GK.KW_FOR, "expected for")
      if (cur().kind == GK.LBRACE) {
        return GStmt.For(line, null, null, null, parseBlock())
      }
      if (match(GK.KW_RANGE)) {
        val iter = parseExpr()
        return GStmt.RangeFor(line, null, null, iter, parseBlock())
      }
      val saveIdx = idx
      val lhsNames = ArrayList<String>(2)
      if (cur().kind == GK.IDENT) {
        lhsNames.add(cur().text); idx++
        if (match(GK.COMMA) && cur().kind == GK.IDENT) {
          lhsNames.add(cur().text); idx++
        }
        if ((cur().kind == GK.DECLARE || cur().kind == GK.EQ) && tokens.getOrNull(idx + 1)?.kind == GK.KW_RANGE) {
          idx += 2
          val iter = parseExpr()
          val kVar = lhsNames.getOrNull(0)?.takeIf { it != "_" }
          val vVar = lhsNames.getOrNull(1)?.takeIf { it != "_" }
          return GStmt.RangeFor(line, kVar, vVar, iter, parseBlock())
        }
      }
      idx = saveIdx

      val first = parseSimpleStmt()
      if (match(GK.SEMI)) {
        val cond = if (cur().kind != GK.SEMI) parseExpr() else null
        expect(GK.SEMI, "expected ';'")
        val step = if (cur().kind != GK.LBRACE) parseSimpleStmt() else null
        return GStmt.For(line, first, cond, step, parseBlock())
      }
      val condExpr = (first as? GStmt.ExprS)?.expr ?: throw GCompileErr(fileName, line, 1, "expected condition or ';' in for")
      return GStmt.For(line, null, condExpr, null, parseBlock())
    }

    private fun parseSwitch(line: Int): GStmt.Switch {
      expect(GK.KW_SWITCH, "expected switch")
      val target = if (cur().kind != GK.LBRACE) parseExpr() else null
      expect(GK.LBRACE, "expected '{'")
      eatSemis()
      val cases = ArrayList<Pair<List<GExpr>?, List<GStmt>>>()
      while (cur().kind != GK.EOF && cur().kind != GK.RBRACE) {
        val exprs: List<GExpr>? = when (cur().kind) {
          GK.KW_CASE -> {
            idx++
            val list = mutableListOf(parseExpr())
            while (match(GK.COMMA)) list.add(parseExpr())
            expect(GK.COLON, "expected ':'")
            list
          }
          GK.KW_DEFAULT -> { idx++; expect(GK.COLON, "expected ':'"); null }
          else -> throw GCompileErr(fileName, cur().line, cur().col, "expected case or default")
        }
        eatSemis()
        val body = ArrayList<GStmt>()
        while (cur().kind != GK.EOF && cur().kind != GK.RBRACE && cur().kind != GK.KW_CASE && cur().kind != GK.KW_DEFAULT) {
          body.add(parseStmt())
          eatSemis()
        }
        cases.add(exprs to body)
      }
      expect(GK.RBRACE, "expected '}'")
      return GStmt.Switch(line, target, cases)
    }

    private fun parseExpr(): GExpr = parseOr()
    private fun parseOr(): GExpr {
      var l = parseAnd()
      while (match(GK.OR_OR)) l = GExpr.Binary(l.line, "||", l, parseAnd())
      return l
    }
    private fun parseAnd(): GExpr {
      var l = parseEq()
      while (match(GK.AND_AND)) l = GExpr.Binary(l.line, "&&", l, parseEq())
      return l
    }
    private fun parseEq(): GExpr {
      var l = parseRel()
      while (cur().kind == GK.EQ_EQ || cur().kind == GK.BANG_EQ) {
        val op = cur().text; idx++; l = GExpr.Binary(l.line, op, l, parseRel())
      }
      return l
    }
    private fun parseRel(): GExpr {
      var l = parseAdd()
      while (cur().kind in setOf(GK.LT, GK.LT_EQ, GK.GT, GK.GT_EQ)) {
        val op = cur().text; idx++; l = GExpr.Binary(l.line, op, l, parseAdd())
      }
      return l
    }
    private fun parseAdd(): GExpr {
      var l = parseMul()
      while (cur().kind == GK.PLUS || cur().kind == GK.MINUS) {
        val op = cur().text; idx++; l = GExpr.Binary(l.line, op, l, parseMul())
      }
      return l
    }
    private fun parseMul(): GExpr {
      var l = parseUnary()
      while (cur().kind in setOf(GK.STAR, GK.SLASH, GK.PERCENT)) {
        val op = cur().text; idx++; l = GExpr.Binary(l.line, op, l, parseUnary())
      }
      return l
    }
    private fun parseUnary(): GExpr {
      val t = cur()
      if (t.kind == GK.MINUS || t.kind == GK.BANG) {
        idx++; return GExpr.Unary(t.line, t.text, parseUnary())
      }
      return parsePostfix()
    }
    private fun parsePostfix(): GExpr {
      var e = parsePrimary()
      while (true) {
        when (cur().kind) {
          GK.LPAREN -> {
            idx++
            val args = ArrayList<GExpr>()
            if (cur().kind != GK.RPAREN) {
              while (true) {
                args.add(parseExpr())
                if (!match(GK.COMMA)) break
                if (cur().kind == GK.RPAREN) break
              }
            }
            expect(GK.RPAREN, "expected ')'")
            e = GExpr.Call(e.line, e, args)
          }
          GK.DOT -> {
            idx++
            e = GExpr.Member(e.line, e, expect(GK.IDENT, "expected field/func").text)
          }
          GK.LBRACK -> {
            idx++
            val iExpr = parseExpr()
            expect(GK.RBRACK, "expected ']'")
            e = GExpr.Index(e.line, e, iExpr)
          }
          GK.PLUS_PLUS, GK.MINUS_MINUS -> {
            val op = cur().text; idx++
            e = GExpr.Unary(e.line, op, e, postfix = true)
          }
          else -> return e
        }
      }
    }
    private fun parsePrimary(): GExpr {
      val t = cur()
      return when (t.kind) {
        GK.INT_LIT -> { idx++; GExpr.Lit(t.line, t.text.toIntOrNull() ?: 0) }
        GK.DOUBLE_LIT -> { idx++; GExpr.Lit(t.line, t.text.toDoubleOrNull() ?: 0.0) }
        GK.STRING_LIT -> { idx++; GExpr.Lit(t.line, t.text) }
        GK.KW_TRUE -> { idx++; GExpr.Lit(t.line, true) }
        GK.KW_FALSE -> { idx++; GExpr.Lit(t.line, false) }
        GK.KW_NIL -> { idx++; GExpr.Lit(t.line, null) }
        GK.LBRACK -> {
          idx++; expect(GK.RBRACK, "expected ']'")
          expect(GK.IDENT, "expected slice element type")
          expect(GK.LBRACE, "expected '{'")
          val items = ArrayList<GExpr>()
          if (cur().kind != GK.RBRACE) {
            while (true) {
              items.add(parseExpr())
              if (!match(GK.COMMA)) break
              if (cur().kind == GK.RBRACE) break
            }
          }
          expect(GK.RBRACE, "expected '}'")
          GExpr.SliceLit(t.line, items)
        }
        GK.IDENT -> {
          if (t.text == "map") {
            idx++
            expect(GK.LBRACK, "expected '['"); while (cur().kind != GK.RBRACK && cur().kind != GK.EOF) idx++; expect(GK.RBRACK, "expected ']'")
            skipGoType(); expect(GK.LBRACE, "expected '{'")
            val entries = ArrayList<Pair<GExpr, GExpr>>()
            while (cur().kind != GK.RBRACE && cur().kind != GK.EOF) {
              val k = parseExpr(); expect(GK.COLON, "expected ':'"); val v = parseExpr()
              entries.add(k to v)
              if (!match(GK.COMMA)) break
            }
            expect(GK.RBRACE, "expected '}'")
            return GExpr.MapLit(t.line, entries)
          }
          idx++
          GExpr.Ident(t.line, t.text)
        }
        GK.LPAREN -> {
          idx++; val inner = parseExpr(); expect(GK.RPAREN, "expected ')'"); inner
        }
        GK.KW_RANGE -> {
          idx++
          parseExpr()
        }
        else -> throw GCompileErr(fileName, t.line, t.col, "unexpected token '${t.text}'")
      }
    }
  }
}
