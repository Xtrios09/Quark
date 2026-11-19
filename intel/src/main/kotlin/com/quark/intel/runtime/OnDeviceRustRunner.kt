package com.quark.intel.runtime

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * 100% on-device pure-Kotlin Rust compiler & AST evaluator supporting:
 * - `fn main()` & typed functions with implicit tail-expression return or explicit `return`
 * - `let` (immutable) & `let mut` (mutable) with compile-time mutability checking
 * - `struct` declarations + struct literals + field access
 * - `vec![...]`, arrays `[a, b]`, tuples, ranges `0..n` & `0..=n`
 * - `for ... in ...`, `while`, `loop` + `break`/`continue`, `if`/`else` expressions, `match`
 * - `println!`, `print!`, `eprintln!`, `format!` with `{}` and `{:?}` formatting
 */
object OnDeviceRustRunner {

  private const val MAX_STEPS = 1_000_000

  private enum class RK {
    EOF, IDENT, INT_LIT, DOUBLE_LIT, STRING_LIT, CHAR_LIT,
    KW_FN, KW_LET, KW_MUT, KW_CONST, KW_STRUCT, KW_IMPL, KW_USE,
    KW_IF, KW_ELSE, KW_FOR, KW_IN, KW_WHILE, KW_LOOP, KW_MATCH,
    KW_RETURN, KW_BREAK, KW_CONTINUE, KW_TRUE, KW_FALSE, KW_AS,
    PLUS, MINUS, STAR, SLASH, PERCENT, EQ, EQ_EQ, BANG_EQ, LT, LT_EQ, GT, GT_EQ,
    AND_AND, OR_OR, BANG, PLUS_EQ, MINUS_EQ, STAR_EQ, SLASH_EQ,
    DOT, DOT_DOT, DOT_DOT_EQ, ARROW, FAT_ARROW, COLON, SCOPE, SEMI, COMMA,
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACK, RBRACK
  }

  private data class RTok(val kind: RK, val text: String, val line: Int, val col: Int)
  private class RCompileErr(file: String, line: Int, col: Int, msg: String) :
    RuntimeException("$file:$line:$col: error: $msg")

  private class ReturnSig(val value: Any?) : RuntimeException(null, null, false, false)
  private object BreakSig : RuntimeException(null, null, false, false)
  private object ContinueSig : RuntimeException(null, null, false, false)

  private data class RFun(val name: String, val params: List<String>, val body: List<RStmt>, val tailExpr: RExpr?)
  private class RStructInst(val name: String, val fields: LinkedHashMap<String, Any?>) {
    override fun toString(): String = "$name { ${fields.entries.joinToString(", ") { "${it.key}: ${it.value}" }} }"
  }

  private sealed interface RStmt {
    val line: Int
    data class Let(override val line: Int, val name: String, val mutable: Boolean, val init: RExpr) : RStmt
    data class ExprS(override val line: Int, val expr: RExpr) : RStmt
    data class Return(override val line: Int, val expr: RExpr?) : RStmt
    data class Break(override val line: Int) : RStmt
    data class Continue(override val line: Int) : RStmt
    data class While(override val line: Int, val cond: RExpr, val body: RExpr.Block) : RStmt
    data class Loop(override val line: Int, val body: RExpr.Block) : RStmt
    data class For(override val line: Int, val varName: String, val iter: RExpr, val body: RExpr.Block) : RStmt
  }

  private sealed interface RExpr {
    val line: Int
    data class Lit(override val line: Int, val value: Any?) : RExpr
    data class Ident(override val line: Int, val name: String) : RExpr
    data class VecLit(override val line: Int, val items: List<RExpr>) : RExpr
    data class TupleLit(override val line: Int, val items: List<RExpr>) : RExpr
    data class StructLit(override val line: Int, val name: String, val fields: List<Pair<String, RExpr>>) : RExpr
    data class Block(override val line: Int, val stmts: List<RStmt>, val tail: RExpr?) : RExpr
    data class If(override val line: Int, val cond: RExpr, val thenBlk: Block, val elseBlk: RExpr?) : RExpr
    data class Match(override val line: Int, val subject: RExpr, val arms: List<Pair<RExpr?, RExpr>>) : RExpr
    data class Range(override val line: Int, val start: RExpr, val end: RExpr, val inclusive: Boolean) : RExpr
    data class Unary(override val line: Int, val op: String, val inner: RExpr) : RExpr
    data class Binary(override val line: Int, val op: String, val l: RExpr, val r: RExpr) : RExpr
    data class Assign(override val line: Int, val target: RExpr, val op: String, val value: RExpr) : RExpr
    data class MacroCall(override val line: Int, val name: String, val args: List<RExpr>) : RExpr
    data class Call(override val line: Int, val callee: RExpr, val args: List<RExpr>) : RExpr
    data class Member(override val line: Int, val recv: RExpr, val name: String) : RExpr
    data class Index(override val line: Int, val recv: RExpr, val idx: RExpr) : RExpr
  }

  private class Env(val parent: Env? = null) {
    val vars = LinkedHashMap<String, Any?>()
    val mutable = HashMap<String, Boolean>()
    fun def(name: String, v: Any?, mut: Boolean) { vars[name] = v; mutable[name] = mut }
    fun get(name: String): Pair<Boolean, Any?> {
      var cur: Env? = this
      while (cur != null) {
        if (cur.vars.containsKey(name)) return true to cur.vars[name]
        cur = cur.parent
      }
      return false to null
    }
    fun set(name: String, v: Any?, line: Int, file: String) {
      var cur: Env? = this
      while (cur != null) {
        if (cur.vars.containsKey(name)) {
          if (cur.mutable[name] == false) throw RCompileErr(file, line, 1, "cannot assign twice to immutable variable '$name' (add `mut`)")
          cur.vars[name] = v
          return
        }
        cur = cur.parent
      }
      throw RCompileErr(file, line, 1, "cannot find value '$name' in this scope")
    }
  }

  fun run(
    fileName: String,
    source: String,
    onOutputLine: (String) -> Unit = {},
  ): ScriptExecutionOutput {
    val stdout = ArrayList<String>()
    val curLine = StringBuilder()

    fun emitText(s: String, newline: Boolean) {
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
      val parser = RParser(fileName, tokens)
      val (funs, consts) = parser.parseProgram()

      val mainFn = funs["main"]
        ?: throw RCompileErr(fileName, 1, 1, "`main` function not found in crate `$fileName`")

      val global = Env()
      for ((cName, cExpr) in consts) {
        // evaluate literals or simple consts later once evaluator initialized
        global.def(cName, 0, false)
      }

      var steps = 0
      fun tick() { if (++steps > MAX_STEPS) error("Execution step limit exceeded") }

      fun truthy(v: Any?): Boolean = (v as? Boolean) ?: false
      fun toNum(v: Any?): Double = (v as? Number)?.toDouble() ?: 0.0
      fun toInt(v: Any?): Int = (v as? Number)?.toInt() ?: 0

      lateinit var eval: (RExpr, Env) -> Any?
      lateinit var execBlock: (RExpr.Block, Env) -> Any?

      fun debugStr(v: Any?): String = when (v) {
        is List<*> -> "[${v.joinToString(", ") { debugStr(it) }}]"
        is String -> "\"$v\""
        else -> v?.toString() ?: "()"
      }

      fun formatRust(fmt: String, args: List<Any?>, env: Env): String {
        val sb = StringBuilder()
        var i = 0
        var argIdx = 0
        while (i < fmt.length) {
          val c = fmt[i]
          if (c == '{') {
            if (i + 1 < fmt.length && fmt[i + 1] == '{') { sb.append('{'); i += 2; continue }
            val end = fmt.indexOf('}', i + 1)
            if (end > i) {
              val inside = fmt.substring(i + 1, end)
              if (inside.isEmpty()) {
                sb.append(args.getOrNull(argIdx++)?.toString() ?: "")
              } else if (inside == ":?") {
                sb.append(debugStr(args.getOrNull(argIdx++)))
              } else {
                val (ok, v) = env.get(inside.substringBefore(':'))
                sb.append(if (ok) v.toString() else args.getOrNull(argIdx++)?.toString() ?: "")
              }
              i = end + 1
              continue
            }
          } else if (c == '}' && i + 1 < fmt.length && fmt[i + 1] == '}') {
            sb.append('}'); i += 2; continue
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
            "/" -> if (b == 0) error("attempt to divide by zero") else a / b
            "%" -> if (b == 0) error("attempt to divide by zero") else a % b
            "<" -> a < b; "<=" -> a <= b; ">" -> a > b; ">=" -> a >= b
            else -> error("op $op")
          }
        }

      fun invokeFn(name: String, args: List<Any?>, line: Int): Any? {
        funs[name]?.let { fn ->
          val scope = Env(global)
          fn.params.forEachIndexed { idx, p -> scope.def(p, args.getOrNull(idx), mut = true) }
          return try {
            var res: Any? = null
            for (s in fn.body) {
              when (s) {
                is RStmt.Let -> scope.def(s.name, eval(s.init, scope), s.mutable)
                is RStmt.ExprS -> eval(s.expr, scope)
                is RStmt.Return -> throw ReturnSig(s.expr?.let { eval(it, scope) })
                is RStmt.While -> while (truthy(eval(s.cond, scope))) {
                  tick()
                  try { execBlock(s.body, scope) } catch (_: ContinueSig) {} catch (_: BreakSig) { break }
                }
                is RStmt.Loop -> while (true) {
                  tick()
                  try { execBlock(s.body, scope) } catch (_: ContinueSig) {} catch (_: BreakSig) { break }
                }
                is RStmt.For -> {
                  val seq = eval(s.iter, scope) as? Iterable<*>
                    ?: throw RCompileErr(fileName, s.line, 1, "for-loop target is not iterable")
                  for (it in seq) {
                    tick()
                    val itemEnv = Env(scope)
                    itemEnv.def(s.varName, it, mut = false)
                    try { execBlock(s.body, itemEnv) } catch (_: ContinueSig) {} catch (_: BreakSig) { break }
                  }
                }
                is RStmt.Break -> throw BreakSig
                is RStmt.Continue -> throw ContinueSig
              }
            }
            if (fn.tailExpr != null) res = eval(fn.tailExpr, scope)
            res
          } catch (r: ReturnSig) {
            r.value
          }
        }
        return when (name) {
          "abs" -> if (args[0] is Double) abs(toNum(args[0])) else abs(toInt(args[0]))
          "min" -> min(toInt(args[0]), toInt(args[1]))
          "max" -> max(toInt(args[0]), toInt(args[1]))
          else -> throw RCompileErr(fileName, line, 1, "cannot find function '$name' in this scope")
        }
      }

      execBlock = { blk, env ->
        val local = Env(env)
        for (s in blk.stmts) {
          tick()
          when (s) {
            is RStmt.Let -> local.def(s.name, eval(s.init, local), s.mutable)
            is RStmt.ExprS -> eval(s.expr, local)
            is RStmt.Return -> throw ReturnSig(s.expr?.let { eval(it, local) })
            is RStmt.Break -> throw BreakSig
            is RStmt.Continue -> throw ContinueSig
            is RStmt.While -> while (truthy(eval(s.cond, local))) {
              tick()
              try { execBlock(s.body, local) } catch (_: ContinueSig) {} catch (_: BreakSig) { break }
            }
            is RStmt.Loop -> while (true) {
              tick()
              try { execBlock(s.body, local) } catch (_: ContinueSig) {} catch (_: BreakSig) { break }
            }
            is RStmt.For -> {
              val seq = eval(s.iter, local) as? Iterable<*>
                ?: throw RCompileErr(fileName, s.line, 1, "for target is not iterable")
              for (item in seq) {
                tick()
                val itScope = Env(local)
                itScope.def(s.varName, item, mut = false)
                try { execBlock(s.body, itScope) } catch (_: ContinueSig) {} catch (_: BreakSig) { break }
              }
            }
          }
        }
        if (blk.tail != null) eval(blk.tail, local) else null
      }

      eval = { expr, env ->
        tick()
        when (expr) {
          is RExpr.Lit -> expr.value
          is RExpr.Ident -> {
            val (ok, v) = env.get(expr.name)
            if (!ok) throw RCompileErr(fileName, expr.line, 1, "cannot find value '${expr.name}' in this scope")
            v
          }
          is RExpr.VecLit -> expr.items.map { eval(it, env) }.toMutableList()
          is RExpr.TupleLit -> expr.items.map { eval(it, env) }
          is RExpr.StructLit -> {
            val map = LinkedHashMap<String, Any?>()
            for ((k, v) in expr.fields) map[k] = eval(v, env)
            RStructInst(expr.name, map)
          }
          is RExpr.Block -> execBlock(expr, env)
          is RExpr.If -> {
            if (truthy(eval(expr.cond, env))) execBlock(expr.thenBlk, env)
            else expr.elseBlk?.let { eval(it, env) }
          }
          is RExpr.Match -> {
            val subj = eval(expr.subject, env)
            var res: Any? = null
            for ((pat, body) in expr.arms) {
              if (pat == null || eval(pat, env) == subj) {
                res = eval(body, env)
                break
              }
            }
            res
          }
          is RExpr.Range -> {
            val a = toInt(eval(expr.start, env)); val b = toInt(eval(expr.end, env))
            if (expr.inclusive) (a..b).toList() else (a until b).toList()
          }
          is RExpr.Unary -> {
            val v = eval(expr.inner, env)
            when (expr.op) {
              "-" -> if (v is Double) -v else -toInt(v)
              "!" -> !truthy(v)
              else -> v
            }
          }
          is RExpr.Binary -> when (expr.op) {
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
          is RExpr.Assign -> {
            val rhs = eval(expr.value, env)
            val id = expr.target as? RExpr.Ident
              ?: throw RCompileErr(fileName, expr.line, 1, "invalid assignment target")
            val cur = env.get(id.name).second
            val next = when (expr.op) {
              "+=" -> bin("+", cur, rhs); "-=" -> bin("-", cur, rhs)
              "*=" -> bin("*", cur, rhs); "/=" -> bin("/", cur, rhs)
              else -> rhs
            }
            env.set(id.name, next, expr.line, fileName)
            next
          }
          is RExpr.MacroCall -> {
            val args = expr.args.map { eval(it, env) }
            when (expr.name) {
              "vec" -> args.toMutableList()
              "println", "eprintln" -> {
                if (args.isEmpty()) emitText("", newline = true)
                else emitText(formatRust(args[0]?.toString() ?: "", args.drop(1), env), newline = true)
                null
              }
              "print" -> {
                if (args.isNotEmpty()) emitText(formatRust(args[0]?.toString() ?: "", args.drop(1), env), newline = false)
                null
              }
              "format" -> if (args.isEmpty()) "" else formatRust(args[0]?.toString() ?: "", args.drop(1), env)
              "assert" -> {
                if (!truthy(args.firstOrNull())) error("assertion failed on line ${expr.line}")
                null
              }
              "assert_eq" -> {
                if (args.getOrNull(0) != args.getOrNull(1)) error("assertion failed: `${args.getOrNull(0)} != ${args.getOrNull(1)}`")
                null
              }
              else -> throw RCompileErr(fileName, expr.line, 1, "cannot find macro '${expr.name}!'")
            }
          }
          is RExpr.Index -> {
            val r = eval(expr.recv, env); val i = toInt(eval(expr.idx, env))
            if (r is List<*>) r[i] else if (r is String) r[i].toString() else null
          }
          is RExpr.Member -> {
            val r = eval(expr.recv, env)
            if (r is RStructInst) r.fields[expr.name] else null
          }
          is RExpr.Call -> {
            val c = expr.callee
            if (c is RExpr.Member) {
              val recv = eval(c.recv, env)
              val args = expr.args.map { eval(it, env) }
              @Suppress("UNCHECKED_CAST")
              when (recv) {
                is MutableList<*> -> {
                  val list = recv as MutableList<Any?>
                  when (c.name) {
                    "push" -> { list.add(args[0]); null }
                    "pop" -> if (list.isEmpty()) null else list.removeAt(list.lastIndex)
                    "len" -> list.size
                    "is_empty" -> list.isEmpty()
                    "contains" -> args[0] in list
                    "join" -> list.joinToString(args.firstOrNull()?.toString() ?: "")
                    "sum" -> if (list.any { it is Double }) list.sumOf { toNum(it) } else list.sumOf { toInt(it) }
                    "iter" -> list
                    else -> throw RCompileErr(fileName, expr.line, 1, "no method named '${c.name}' found on Vec")
                  }
                }
                is String -> when (c.name) {
                  "len", "count" -> recv.length
                  "is_empty" -> recv.isEmpty()
                  "to_string" -> recv
                  "to_uppercase" -> recv.uppercase()
                  "to_lowercase" -> recv.lowercase()
                  "trim" -> recv.trim()
                  "chars" -> recv.toList().map { it.toString() }.toMutableList()
                  "contains" -> recv.contains(args[0].toString())
                  else -> throw RCompileErr(fileName, expr.line, 1, "no method named '${c.name}' found on str")
                }
                is Number -> when (c.name) {
                  "abs" -> if (recv is Double) abs(recv) else abs(recv.toInt())
                  "sqrt" -> sqrt(recv.toDouble())
                  "pow" -> recv.toDouble().pow(toNum(args[0]))
                  "floor" -> floor(recv.toDouble())
                  "ceil" -> ceil(recv.toDouble())
                  "round" -> round(recv.toDouble())
                  "to_string" -> recv.toString()
                  else -> throw RCompileErr(fileName, expr.line, 1, "no method named '${c.name}' found on number")
                }
                else -> throw RCompileErr(fileName, expr.line, 1, "method '${c.name}' not supported on $recv")
              }
            } else if (c is RExpr.Ident) {
              invokeFn(c.name, expr.args.map { eval(it, env) }, expr.line)
            } else throw RCompileErr(fileName, expr.line, 1, "invalid call target")
          }
        }
      }

      for ((cName, cExpr) in consts) global.def(cName, eval(cExpr, global), false)
      invokeFn("main", emptyList(), 1)
      if (curLine.isNotEmpty()) {
        val rem = curLine.toString(); stdout.add(rem); onOutputLine(rem)
      }
      ScriptExecutionOutput(0, stdout)
    } catch (e: RCompileErr) {
      val msg = e.message ?: "Rust compile error"
      stdout.add(msg); onOutputLine(msg)
      ScriptExecutionOutput(1, stdout, msg)
    } catch (t: Throwable) {
      val msg = "$fileName: ${t.message ?: t::class.simpleName}"
      stdout.add(msg); onOutputLine(msg)
      ScriptExecutionOutput(1, stdout, msg)
    }
  }

  private fun lex(fileName: String, src: String): List<RTok> {
    val out = ArrayList<RTok>(256)
    var i = 0; var line = 1; var col = 1
    fun peek(off: Int = 1) = if (i + off < src.length) src[i + off] else '\u0000'
    while (i < src.length) {
      val c = src[i]
      when {
        c == '\r' -> i++
        c == '\n' -> { i++; line++; col = 1 }
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
          if (!closed) throw RCompileErr(fileName, sl, sc, "unterminated block comment")
        }
        c == '"' -> {
          val sl = line; val sc = col; i++; col++
          val sb = StringBuilder(); var closed = false
          while (i < src.length) {
            val ch = src[i]
            if (ch == '"') { i++; col++; closed = true; break }
            if (ch == '\n') { sb.append('\n'); i++; line++; col = 1; continue }
            if (ch == '\\' && i + 1 < src.length) {
              val n = src[i + 1]
              sb.append(when (n) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '\\' -> '\\'; '"' -> '"'; else -> n })
              i += 2; col += 2
            } else { sb.append(ch); i++; col++ }
          }
          if (!closed) throw RCompileErr(fileName, sl, sc, "unterminated double quote string")
          out.add(RTok(RK.STRING_LIT, sb.toString(), sl, sc))
        }
        c.isDigit() -> {
          val sc = col; val start = i; var hasDot = false
          while (i < src.length && (src[i].isDigit() || src[i] == '_' || (src[i] == '.' && !hasDot && peek() != '.' && peek().isDigit()))) {
            if (src[i] == '.') hasDot = true
            i++; col++
          }
          // Skip Rust type suffix like 10i32
          if (i < src.length && (src[i] == 'i' || src[i] == 'u' || src[i] == 'f')) {
            while (i < src.length && (src[i].isLetterOrDigit())) { i++; col++ }
          }
          val cleaned = src.substring(start, i).replace("_", "").substringBefore('i').substringBefore('u').substringBefore('f')
          out.add(RTok(if (hasDot) RK.DOUBLE_LIT else RK.INT_LIT, cleaned, line, sc))
        }
        c.isLetter() || c == '_' -> {
          val sc = col; val start = i
          while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) { i++; col++ }
          val w = src.substring(start, i)
          val k = when (w) {
            "fn" -> RK.KW_FN; "let" -> RK.KW_LET; "mut" -> RK.KW_MUT; "const" -> RK.KW_CONST
            "struct" -> RK.KW_STRUCT; "impl" -> RK.KW_IMPL; "use" -> RK.KW_USE
            "if" -> RK.KW_IF; "else" -> RK.KW_ELSE; "for" -> RK.KW_FOR; "in" -> RK.KW_IN
            "while" -> RK.KW_WHILE; "loop" -> RK.KW_LOOP; "match" -> RK.KW_MATCH
            "return" -> RK.KW_RETURN; "break" -> RK.KW_BREAK; "continue" -> RK.KW_CONTINUE
            "true" -> RK.KW_TRUE; "false" -> RK.KW_FALSE; "as" -> RK.KW_AS
            else -> RK.IDENT
          }
          out.add(RTok(k, w, line, sc))
        }
        else -> {
          val sc = col; val n = peek(); val n2 = peek(2)
          when {
            c == '.' && n == '.' && n2 == '=' -> { out.add(RTok(RK.DOT_DOT_EQ, "..=", line, sc)); i += 3; col += 3 }
            "$c$n" == ".." -> { out.add(RTok(RK.DOT_DOT, "..", line, sc)); i += 2; col += 2 }
            "$c$n" == "==" -> { out.add(RTok(RK.EQ_EQ, "==", line, sc)); i += 2; col += 2 }
            "$c$n" == "!=" -> { out.add(RTok(RK.BANG_EQ, "!=", line, sc)); i += 2; col += 2 }
            "$c$n" == "<=" -> { out.add(RTok(RK.LT_EQ, "<=", line, sc)); i += 2; col += 2 }
            "$c$n" == ">=" -> { out.add(RTok(RK.GT_EQ, ">=", line, sc)); i += 2; col += 2 }
            "$c$n" == "&&" -> { out.add(RTok(RK.AND_AND, "&&", line, sc)); i += 2; col += 2 }
            "$c$n" == "||" -> { out.add(RTok(RK.OR_OR, "||", line, sc)); i += 2; col += 2 }
            "$c$n" == "+=" -> { out.add(RTok(RK.PLUS_EQ, "+=", line, sc)); i += 2; col += 2 }
            "$c$n" == "-=" -> { out.add(RTok(RK.MINUS_EQ, "-=", line, sc)); i += 2; col += 2 }
            "$c$n" == "*=" -> { out.add(RTok(RK.STAR_EQ, "*=", line, sc)); i += 2; col += 2 }
            "$c$n" == "/=" -> { out.add(RTok(RK.SLASH_EQ, "/=", line, sc)); i += 2; col += 2 }
            "$c$n" == "->" -> { out.add(RTok(RK.ARROW, "->", line, sc)); i += 2; col += 2 }
            "$c$n" == "=>" -> { out.add(RTok(RK.FAT_ARROW, "=>", line, sc)); i += 2; col += 2 }
            "$c$n" == "::" -> { out.add(RTok(RK.SCOPE, "::", line, sc)); i += 2; col += 2 }
            else -> {
              val k = when (c) {
                '+' -> RK.PLUS; '-' -> RK.MINUS; '*' -> RK.STAR; '/' -> RK.SLASH; '%' -> RK.PERCENT
                '=' -> RK.EQ; '<' -> RK.LT; '>' -> RK.GT; '!' -> RK.BANG
                '.' -> RK.DOT; ':' -> RK.COLON; ';' -> RK.SEMI; ',' -> RK.COMMA
                '(' -> RK.LPAREN; ')' -> RK.RPAREN; '{' -> RK.LBRACE; '}' -> RK.RBRACE
                '[' -> RK.LBRACK; ']' -> RK.RBRACK
                else -> throw RCompileErr(fileName, line, col, "unexpected character '$c'")
              }
              out.add(RTok(k, c.toString(), line, sc)); i++; col++
            }
          }
        }
      }
    }
    out.add(RTok(RK.EOF, "", line, col))
    return out
  }

  private class RParser(private val fileName: String, private val tokens: List<RTok>) {
    private var idx = 0
    private fun cur() = tokens[idx.coerceAtMost(tokens.lastIndex)]
    private fun peek(off: Int = 1) = tokens[(idx + off).coerceAtMost(tokens.lastIndex)]
    private fun match(k: RK): Boolean { if (cur().kind == k) { idx++; return true }; return false }
    private fun expect(k: RK, msg: String): RTok {
      val t = cur()
      if (t.kind != k) throw RCompileErr(fileName, t.line, t.col, msg)
      idx++; return t
    }

    fun parseProgram(): Pair<Map<String, RFun>, Map<String, RExpr>> {
      val funs = LinkedHashMap<String, RFun>()
      val consts = LinkedHashMap<String, RExpr>()
      while (cur().kind != RK.EOF) {
        if (cur().kind == RK.IDENT && cur().text == "pub") idx++
        when (cur().kind) {
          RK.KW_USE -> { while (cur().kind != RK.EOF && cur().kind != RK.SEMI) idx++; match(RK.SEMI) }
          RK.KW_STRUCT -> {
            idx++; expect(RK.IDENT, "expected struct name"); expect(RK.LBRACE, "expected '{'")
            var d = 1
            while (d > 0 && cur().kind != RK.EOF) {
              if (cur().kind == RK.LBRACE) d++ else if (cur().kind == RK.RBRACE) d--
              idx++
            }
          }
          RK.KW_CONST -> {
            idx++
            val cName = expect(RK.IDENT, "expected const name").text
            if (match(RK.COLON)) skipTypeAnnotation()
            expect(RK.EQ, "expected '='")
            consts[cName] = parseExpr()
            expect(RK.SEMI, "expected ';'")
          }
          RK.KW_FN -> {
            val fn = parseFn()
            funs[fn.name] = fn
          }
          else -> throw RCompileErr(fileName, cur().line, cur().col, "expected item (fn/struct/const/use), found '${cur().text}'")
        }
      }
      return funs to consts
    }

    private fun skipTypeAnnotation() {
      var depth = 0
      while (cur().kind != RK.EOF) {
        when (cur().kind) {
          RK.LPAREN, RK.LBRACK, RK.LT -> depth++
          RK.RPAREN, RK.RBRACK, RK.GT -> if (depth > 0) depth-- else return
          RK.EQ, RK.COMMA, RK.LBRACE, RK.SEMI -> if (depth == 0) return
          else -> {}
        }
        idx++
      }
    }

    private fun parseFn(): RFun {
      expect(RK.KW_FN, "expected fn")
      val name = expect(RK.IDENT, "expected function name").text
      expect(RK.LPAREN, "expected '('")
      val params = ArrayList<String>()
      if (cur().kind != RK.RPAREN) {
        while (true) {
          if (cur().kind == RK.KW_MUT) idx++
          val p = expect(RK.IDENT, "expected param name").text
          if (match(RK.COLON)) skipTypeAnnotation()
          params.add(p)
          if (!match(RK.COMMA)) break
          if (cur().kind == RK.RPAREN) break
        }
      }
      expect(RK.RPAREN, "expected ')'")
      if (match(RK.ARROW)) skipTypeAnnotation()
      val blk = parseBlock()
      return RFun(name, params, blk.stmts, blk.tail)
    }

    private fun parseBlock(): RExpr.Block {
      val open = expect(RK.LBRACE, "expected '{'")
      val stmts = ArrayList<RStmt>()
      var tail: RExpr? = null
      while (cur().kind != RK.EOF && cur().kind != RK.RBRACE) {
        val t = cur()
        when (t.kind) {
          RK.KW_LET -> {
            idx++
            val mut = match(RK.KW_MUT)
            val vName = expect(RK.IDENT, "expected variable name").text
            if (match(RK.COLON)) skipTypeAnnotation()
            expect(RK.EQ, "expected '='")
            val init = parseExpr()
            expect(RK.SEMI, "expected ';' after let statement")
            stmts.add(RStmt.Let(t.line, vName, mut, init))
          }
          RK.KW_RETURN -> {
            idx++
            val e = if (cur().kind != RK.SEMI) parseExpr() else null
            expect(RK.SEMI, "expected ';'")
            stmts.add(RStmt.Return(t.line, e))
          }
          RK.KW_BREAK -> { idx++; match(RK.SEMI); stmts.add(RStmt.Break(t.line)) }
          RK.KW_CONTINUE -> { idx++; match(RK.SEMI); stmts.add(RStmt.Continue(t.line)) }
          RK.KW_WHILE -> {
            idx++
            val cond = parseExprNoStruct()
            val body = parseBlock()
            stmts.add(RStmt.While(t.line, cond, body))
          }
          RK.KW_LOOP -> {
            idx++
            stmts.add(RStmt.Loop(t.line, parseBlock()))
          }
          RK.KW_FOR -> {
            idx++
            val varName = expect(RK.IDENT, "expected loop variable").text
            expect(RK.KW_IN, "expected 'in'")
            val iter = parseExprNoStruct()
            val body = parseBlock()
            stmts.add(RStmt.For(t.line, varName, iter, body))
          }
          else -> {
            val e = parseExpr()
            if (match(RK.SEMI)) {
              stmts.add(RStmt.ExprS(t.line, e))
            } else if (cur().kind == RK.RBRACE) {
              tail = e
            } else if (e is RExpr.If || e is RExpr.Match || e is RExpr.Block) {
              stmts.add(RStmt.ExprS(t.line, e))
            } else {
              throw RCompileErr(fileName, cur().line, cur().col, "expected ';' or '}'")
            }
          }
        }
      }
      expect(RK.RBRACE, "expected '}'")
      return RExpr.Block(open.line, stmts, tail)
    }

    private var allowStructLit = true
    private fun parseExprNoStruct(): RExpr {
      val prev = allowStructLit
      allowStructLit = false
      return try { parseExpr() } finally { allowStructLit = prev }
    }

    private fun parseExpr(): RExpr = parseAssign()
    private fun parseAssign(): RExpr {
      val l = parseRange()
      if (cur().kind in setOf(RK.EQ, RK.PLUS_EQ, RK.MINUS_EQ, RK.STAR_EQ, RK.SLASH_EQ)) {
        val op = cur().text; val ln = cur().line; idx++
        return RExpr.Assign(ln, l, op, parseAssign())
      }
      return l
    }

    private fun parseRange(): RExpr {
      val s = parseOr()
      return when (cur().kind) {
        RK.DOT_DOT -> { idx++; RExpr.Range(s.line, s, parseOr(), inclusive = false) }
        RK.DOT_DOT_EQ -> { idx++; RExpr.Range(s.line, s, parseOr(), inclusive = true) }
        else -> s
      }
    }

    private fun parseOr(): RExpr {
      var l = parseAnd()
      while (match(RK.OR_OR)) l = RExpr.Binary(l.line, "||", l, parseAnd())
      return l
    }
    private fun parseAnd(): RExpr {
      var l = parseEq()
      while (match(RK.AND_AND)) l = RExpr.Binary(l.line, "&&", l, parseEq())
      return l
    }
    private fun parseEq(): RExpr {
      var l = parseRel()
      while (cur().kind == RK.EQ_EQ || cur().kind == RK.BANG_EQ) {
        val op = cur().text; idx++; l = RExpr.Binary(l.line, op, l, parseRel())
      }
      return l
    }
    private fun parseRel(): RExpr {
      var l = parseAdd()
      while (cur().kind in setOf(RK.LT, RK.LT_EQ, RK.GT, RK.GT_EQ)) {
        val op = cur().text; idx++; l = RExpr.Binary(l.line, op, l, parseAdd())
      }
      return l
    }
    private fun parseAdd(): RExpr {
      var l = parseMul()
      while (cur().kind == RK.PLUS || cur().kind == RK.MINUS) {
        val op = cur().text; idx++; l = RExpr.Binary(l.line, op, l, parseMul())
      }
      return l
    }
    private fun parseMul(): RExpr {
      var l = parseUnary()
      while (cur().kind in setOf(RK.STAR, RK.SLASH, RK.PERCENT)) {
        val op = cur().text; idx++; l = RExpr.Binary(l.line, op, l, parseUnary())
      }
      return l
    }
    private fun parseUnary(): RExpr {
      val t = cur()
      if (t.kind == RK.MINUS || t.kind == RK.BANG) {
        idx++; return RExpr.Unary(t.line, t.text, parseUnary())
      }
      return parsePostfix()
    }
    private fun parsePostfix(): RExpr {
      var e = parsePrimary()
      while (true) {
        when (cur().kind) {
          RK.LPAREN -> {
            idx++
            val args = ArrayList<RExpr>()
            if (cur().kind != RK.RPAREN) {
              while (true) {
                args.add(parseExpr())
                if (!match(RK.COMMA)) break
                if (cur().kind == RK.RPAREN) break
              }
            }
            expect(RK.RPAREN, "expected ')'")
            e = RExpr.Call(e.line, e, args)
          }
          RK.DOT -> {
            idx++
            val m = expect(RK.IDENT, "expected field or method").text
            e = RExpr.Member(e.line, e, m)
          }
          RK.LBRACK -> {
            idx++
            val iExpr = parseExpr()
            expect(RK.RBRACK, "expected ']'")
            e = RExpr.Index(e.line, e, iExpr)
          }
          RK.KW_AS -> {
            idx++; skipTypeAnnotation()
          }
          else -> return e
        }
      }
    }

    private fun parsePrimary(): RExpr {
      val t = cur()
      return when (t.kind) {
        RK.INT_LIT -> { idx++; RExpr.Lit(t.line, t.text.toIntOrNull() ?: 0) }
        RK.DOUBLE_LIT -> { idx++; RExpr.Lit(t.line, t.text.toDoubleOrNull() ?: 0.0) }
        RK.STRING_LIT -> { idx++; RExpr.Lit(t.line, t.text) }
        RK.KW_TRUE -> { idx++; RExpr.Lit(t.line, true) }
        RK.KW_FALSE -> { idx++; RExpr.Lit(t.line, false) }
        RK.KW_IF -> parseIf(t.line)
        RK.KW_MATCH -> parseMatch(t.line)
        RK.LBRACE -> parseBlock()
        RK.LPAREN -> {
          idx++
          if (match(RK.RPAREN)) return RExpr.TupleLit(t.line, emptyList())
          val first = parseExpr()
          if (match(RK.COMMA)) {
            val items = mutableListOf(first)
            if (cur().kind != RK.RPAREN) {
              while (true) {
                items.add(parseExpr())
                if (!match(RK.COMMA)) break
                if (cur().kind == RK.RPAREN) break
              }
            }
            expect(RK.RPAREN, "expected ')'")
            RExpr.TupleLit(t.line, items)
          } else {
            expect(RK.RPAREN, "expected ')'")
            first
          }
        }
        RK.LBRACK -> {
          idx++
          val items = ArrayList<RExpr>()
          if (cur().kind != RK.RBRACK) {
            while (true) {
              items.add(parseExpr())
              if (!match(RK.COMMA)) break
              if (cur().kind == RK.RBRACK) break
            }
          }
          expect(RK.RBRACK, "expected ']'")
          RExpr.VecLit(t.line, items)
        }
        RK.IDENT -> {
          idx++
          if (cur().kind == RK.BANG && (peek().kind == RK.LPAREN || peek().kind == RK.LBRACK)) {
            idx++
            val closeKind = if (cur().kind == RK.LBRACK) RK.RBRACK else RK.RPAREN
            idx++
            val args = ArrayList<RExpr>()
            if (cur().kind != closeKind) {
              while (true) {
                args.add(parseExpr())
                if (!match(RK.COMMA)) break
                if (cur().kind == closeKind) break
              }
            }
            expect(closeKind, "expected closing delimiter for macro!")
            return RExpr.MacroCall(t.line, t.text, args)
          }
          if (allowStructLit && cur().kind == RK.LBRACE && peek().kind == RK.IDENT && peek(2).kind == RK.COLON) {
            idx++
            val fields = ArrayList<Pair<String, RExpr>>()
            while (cur().kind != RK.RBRACE && cur().kind != RK.EOF) {
              val fName = expect(RK.IDENT, "expected field name").text
              expect(RK.COLON, "expected ':'")
              fields.add(fName to parseExpr())
              if (!match(RK.COMMA)) break
            }
            expect(RK.RBRACE, "expected '}'")
            return RExpr.StructLit(t.line, t.text, fields)
          }
          RExpr.Ident(t.line, t.text)
        }
        else -> throw RCompileErr(fileName, t.line, t.col, "unexpected token '${t.text}'")
      }
    }

    private fun parseIf(line: Int): RExpr.If {
      expect(RK.KW_IF, "expected if")
      val cond = parseExprNoStruct()
      val thenBlk = parseBlock()
      val elseBlk = if (match(RK.KW_ELSE)) {
        if (cur().kind == RK.KW_IF) parseIf(cur().line) else parseBlock()
      } else null
      return RExpr.If(line, cond, thenBlk, elseBlk)
    }

    private fun parseMatch(line: Int): RExpr.Match {
      expect(RK.KW_MATCH, "expected match")
      val subj = parseExprNoStruct()
      expect(RK.LBRACE, "expected '{'")
      val arms = ArrayList<Pair<RExpr?, RExpr>>()
      while (cur().kind != RK.EOF && cur().kind != RK.RBRACE) {
        val pat = if (cur().kind == RK.IDENT && cur().text == "_") { idx++; null } else parseExprNoStruct()
        expect(RK.FAT_ARROW, "expected '=>'")
        val body = if (cur().kind == RK.LBRACE) parseBlock() else parseExpr()
        match(RK.COMMA)
        arms.add(pat to body)
      }
      expect(RK.RBRACE, "expected '}'")
      return RExpr.Match(line, subj, arms)
    }
  }
}
