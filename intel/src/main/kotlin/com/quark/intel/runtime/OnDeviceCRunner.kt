package com.quark.intel.runtime

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 100% on-device pure-Kotlin C / C++ compiler & AST evaluator supporting:
 * - Preprocessor `#include` / `#define` object macros and `using namespace std;`
 * - Primitive types (`int`, `long`, `double`, `float`, `bool`, `char`, `string`/`std::string`, `auto`, `void`)
 * - Arrays (`int arr[] = {1, 2, 3};`), `std::vector<T>` (`push_back`, `pop_back`, `size`, `empty`, indexing)
 * - Structs (`struct Point { int x; int y; };`), recursion & helper functions
 * - Control flow (`if`/`else if`/`else`, `for`, range-for `for (auto x : v)`, `while`, `do-while`, `switch`/`case`)
 * - I/O (`printf`, `puts`, `putchar`, `std::cout << ... << std::endl`, `cout << ...`) & `<cmath>`/`<cstring>` helpers
 * - Real compile diagnostics with `fileName:line:col: error: ...`
 */
object OnDeviceCRunner {

  private const val MAX_STEPS = 1_000_000

  private enum class CK {
    EOF, IDENT, INT_LIT, DOUBLE_LIT, STRING_LIT, CHAR_LIT,
    KW_RETURN, KW_IF, KW_ELSE, KW_FOR, KW_WHILE, KW_DO, KW_SWITCH, KW_CASE, KW_DEFAULT,
    KW_BREAK, KW_CONTINUE, KW_STRUCT, KW_CONST, KW_TRUE, KW_FALSE, KW_SIZEOF,
    PLUS, MINUS, STAR, SLASH, PERCENT, EQ, EQ_EQ, BANG_EQ, LT, LT_EQ, GT, GT_EQ,
    AND_AND, OR_OR, BANG, AMP, PIPE, CARET, LSHIFT, RSHIFT,
    PLUS_EQ, MINUS_EQ, STAR_EQ, SLASH_EQ, PLUS_PLUS, MINUS_MINUS,
    QUESTION, COLON, SEMI, COMMA, DOT, ARROW, SCOPE, LPAREN, RPAREN, LBRACE, RBRACE, LBRACK, RBRACK
  }

  private data class CTok(val kind: CK, val text: String, val line: Int, val col: Int)
  private class CCompileErr(file: String, line: Int, col: Int, msg: String) :
    RuntimeException("$file:$line:$col: error: $msg")

  private class ReturnSig(val value: Any?) : RuntimeException(null, null, false, false)
  private object BreakSig : RuntimeException(null, null, false, false)
  private object ContinueSig : RuntimeException(null, null, false, false)

  private data class CParam(val typeName: String, val name: String)
  private data class CFun(val name: String, val retType: String, val params: List<CParam>, val body: List<CStmt>, val line: Int)
  private data class CStruct(val name: String, val fields: List<Pair<String, String>>)
  private class CStructInst(val typeName: String, val fields: LinkedHashMap<String, Any?>) {
    override fun toString(): String = "$typeName(${fields.entries.joinToString(", ") { "${it.key}=${it.value}" }})"
  }

  private sealed interface CStmt {
    val line: Int
    data class VarDecl(override val line: Int, val name: String, val isArray: Boolean, val init: CExpr?) : CStmt
    data class Block(override val line: Int, val stmts: List<CStmt>) : CStmt
    data class If(override val line: Int, val cond: CExpr, val thenBr: CStmt, val elseBr: CStmt?) : CStmt
    data class While(override val line: Int, val cond: CExpr, val body: CStmt, val isDoWhile: Boolean = false) : CStmt
    data class For(override val line: Int, val init: CStmt?, val cond: CExpr?, val step: CExpr?, val body: CStmt) : CStmt
    data class RangeFor(override val line: Int, val varName: String, val iterable: CExpr, val body: CStmt) : CStmt
    data class Switch(override val line: Int, val expr: CExpr, val cases: List<Pair<CExpr?, List<CStmt>>>) : CStmt
    data class Return(override val line: Int, val expr: CExpr?) : CStmt
    data class Break(override val line: Int) : CStmt
    data class Continue(override val line: Int) : CStmt
    data class ExprS(override val line: Int, val expr: CExpr) : CStmt
  }

  private sealed interface CExpr {
    val line: Int
    data class Lit(override val line: Int, val value: Any?) : CExpr
    data class Ident(override val line: Int, val name: String) : CExpr
    data class InitList(override val line: Int, val items: List<CExpr>) : CExpr
    data class Unary(override val line: Int, val op: String, val operand: CExpr, val postfix: Boolean = false) : CExpr
    data class Binary(override val line: Int, val op: String, val left: CExpr, val right: CExpr) : CExpr
    data class Ternary(override val line: Int, val cond: CExpr, val a: CExpr, val b: CExpr) : CExpr
    data class Assign(override val line: Int, val target: CExpr, val op: String, val value: CExpr) : CExpr
    data class Call(override val line: Int, val callee: CExpr, val args: List<CExpr>) : CExpr
    data class Member(override val line: Int, val recv: CExpr, val name: String) : CExpr
    data class Index(override val line: Int, val recv: CExpr, val index: CExpr) : CExpr
    data class Cast(override val line: Int, val targetType: String, val inner: CExpr) : CExpr
    data class CoutChain(override val line: Int, val parts: List<CExpr>) : CExpr
  }

  private class Env(val parent: Env? = null) {
    val vars = LinkedHashMap<String, Any?>()
    fun def(name: String, v: Any?) { vars[name] = v }
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
        if (cur.vars.containsKey(name)) {
          cur.vars[name] = v
          return true
        }
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
    val lineBuf = StringBuilder()

    fun flushChars(s: String, newline: Boolean = false) {
      for (ch in s) {
        if (ch == '\n') {
          val line = lineBuf.toString()
          stdout.add(line)
          onOutputLine(line)
          lineBuf.setLength(0)
        } else {
          lineBuf.append(ch)
        }
      }
      if (newline) {
        val line = lineBuf.toString()
        stdout.add(line)
        onOutputLine(line)
        lineBuf.setLength(0)
      }
    }

    return try {
      val preprocessed = preprocess(source)
      val tokens = lex(fileName, preprocessed)
      val parser = CParser(fileName, tokens)
      val (funs, structs, globals) = parser.parseProgram()

      val mainFun = funs["main"]
        ?: throw CCompileErr(fileName, 1, 1, "undefined reference to 'main' (expected int main())")

      val globalEnv = Env()
      globalEnv.def("M_PI", PI)
      globalEnv.def("endl", "\n")
      globalEnv.def("std::endl", "\n")

      var steps = 0
      fun tick() {
        if (++steps > MAX_STEPS) error("RuntimeError: execution step limit exceeded")
      }

      fun toNum(v: Any?): Double = when (v) {
        is Double -> v
        is Float -> v.toDouble()
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Char -> v.code.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        else -> 0.0
      }

      fun toInt(v: Any?): Int = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> v.toInt()
        is Char -> v.code
        is Boolean -> if (v) 1 else 0
        else -> 0
      }

      fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is Char -> v.code != 0
        is String -> v.isNotEmpty()
        else -> true
      }

      lateinit var evalExpr: (CExpr, Env) -> Any?
      lateinit var execStmt: (CStmt, Env) -> Unit

      fun callFunction(rawName: String, args: List<Any?>, line: Int): Any? {
        val name = rawName.removePrefix("std::")
        funs[name]?.let { fn ->
          val local = Env(globalEnv)
          fn.params.forEachIndexed { idx, p ->
            val v = args.getOrNull(idx)
            local.def(p.name, if (v is MutableList<*>) v.toMutableList() else v)
          }
          return try {
            for (st in fn.body) execStmt(st, local)
            0
          } catch (r: ReturnSig) {
            r.value
          }
        }
        structs[name]?.let { st ->
          val map = LinkedHashMap<String, Any?>()
          st.fields.forEachIndexed { idx, (fName, _) -> map[fName] = args.getOrNull(idx) }
          return CStructInst(st.name, map)
        }
        return when (name) {
          "printf" -> {
            val fmt = args.firstOrNull()?.toString() ?: ""
            val formatted = formatPrintf(fmt, args.drop(1))
            flushChars(formatted)
            formatted.length
          }
          "puts" -> {
            flushChars(args.firstOrNull()?.toString() ?: "", newline = true)
            0
          }
          "putchar" -> {
            val ch = toInt(args.firstOrNull()).toChar()
            flushChars(ch.toString())
            ch.code
          }
          "strlen" -> (args.firstOrNull()?.toString() ?: "").length
          "strcmp" -> (args.getOrNull(0)?.toString() ?: "").compareTo(args.getOrNull(1)?.toString() ?: "")
          "abs", "fabs" -> {
            val a = args.firstOrNull()
            if (a is Double) abs(a) else abs(toInt(a))
          }
          "sqrt" -> sqrt(toNum(args.firstOrNull()))
          "pow" -> toNum(args.getOrNull(0)).pow(toNum(args.getOrNull(1)))
          "floor" -> floor(toNum(args.firstOrNull()))
          "ceil" -> ceil(toNum(args.firstOrNull()))
          "round" -> round(toNum(args.firstOrNull()))
          "sin" -> sin(toNum(args.firstOrNull()))
          "cos" -> cos(toNum(args.firstOrNull()))
          "tan" -> tan(toNum(args.firstOrNull()))
          "log" -> ln(toNum(args.firstOrNull()))
          "fmax", "max" -> {
            val a = args.getOrNull(0); val b = args.getOrNull(1)
            if (a is Double || b is Double) max(toNum(a), toNum(b)) else max(toInt(a), toInt(b))
          }
          "fmin", "min" -> {
            val a = args.getOrNull(0); val b = args.getOrNull(1)
            if (a is Double || b is Double) min(toNum(a), toNum(b)) else min(toInt(a), toInt(b))
          }
          "to_string" -> args.firstOrNull()?.toString() ?: ""
          "stoi" -> args.firstOrNull()?.toString()?.trim()?.toIntOrNull() ?: 0
          "stod" -> args.firstOrNull()?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
          "exit" -> throw ReturnSig(toInt(args.firstOrNull()))
          else -> throw CCompileErr(fileName, line, 1, "undeclared function '$name'")
        }
      }

      fun numericBin(op: String, l: Any?, r: Any?): Any {
        if (op == "+" && (l is String || r is String)) return "${l ?: ""}${r ?: ""}"
        val isFloat = l is Double || r is Double || l is Float || r is Float
        return if (isFloat) {
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
            "/" -> if (b == 0) error("Division by zero") else a / b
            "%" -> if (b == 0) error("Modulo by zero") else a % b
            "&" -> a and b; "|" -> a or b; "^" -> a xor b
            "<<" -> a shl b; ">>" -> a shr b
            "<" -> a < b; "<=" -> a <= b; ">" -> a > b; ">=" -> a >= b
            else -> error("op $op")
          }
        }
      }

      evalExpr = { expr, env ->
        tick()
        when (expr) {
          is CExpr.Lit -> expr.value
          is CExpr.Ident -> {
            val (ok, v) = env.get(expr.name)
            if (!ok) throw CCompileErr(fileName, expr.line, 1, "use of undeclared identifier '${expr.name}'")
            v
          }
          is CExpr.InitList -> expr.items.map { evalExpr(it, env) }.toMutableList()
          is CExpr.Cast -> {
            val v = evalExpr(expr.inner, env)
            when (expr.targetType) {
              "double", "float" -> toNum(v)
              "int", "long" -> toInt(v)
              "char" -> toInt(v).toChar()
              "bool" -> truthy(v)
              else -> v
            }
          }
          is CExpr.Unary -> {
            if (expr.op == "++" || expr.op == "--") {
              val id = expr.operand as? CExpr.Ident
                ?: throw CCompileErr(fileName, expr.line, 1, "increment/decrement requires assignable variable")
              val old = env.get(id.name).second
              val next = if (old is Double) {
                if (expr.op == "++") old + 1.0 else old - 1.0
              } else {
                if (expr.op == "++") toInt(old) + 1 else toInt(old) - 1
              }
              env.set(id.name, next)
              if (expr.postfix) old else next
            } else {
              val v = evalExpr(expr.operand, env)
              when (expr.op) {
                "-" -> if (v is Double) -v else -toInt(v)
                "+" -> v
                "!" -> !truthy(v)
                "~" -> toInt(v).inv()
                "sizeof" -> if (v is Collection<*>) v.size else if (v is String) v.length else 4
                else -> v
              }
            }
          }
          is CExpr.Binary -> when (expr.op) {
            "&&" -> truthy(evalExpr(expr.left, env)) && truthy(evalExpr(expr.right, env))
            "||" -> truthy(evalExpr(expr.left, env)) || truthy(evalExpr(expr.right, env))
            "==" -> {
              val l = evalExpr(expr.left, env); val r = evalExpr(expr.right, env)
              if (l is Number && r is Number) l.toDouble() == r.toDouble() else l == r
            }
            "!=" -> {
              val l = evalExpr(expr.left, env); val r = evalExpr(expr.right, env)
              if (l is Number && r is Number) l.toDouble() != r.toDouble() else l != r
            }
            else -> numericBin(expr.op, evalExpr(expr.left, env), evalExpr(expr.right, env))
          }
          is CExpr.Ternary ->
            if (truthy(evalExpr(expr.cond, env))) evalExpr(expr.a, env) else evalExpr(expr.b, env)
          is CExpr.Assign -> {
            val rhs = evalExpr(expr.value, env)
            when (val t = expr.target) {
              is CExpr.Ident -> {
                val cur = env.get(t.name)
                if (!cur.first) throw CCompileErr(fileName, expr.line, 1, "undeclared variable '${t.name}'")
                val next = when (expr.op) {
                  "=" -> rhs
                  "+=" -> numericBin("+", cur.second, rhs)
                  "-=" -> numericBin("-", cur.second, rhs)
                  "*=" -> numericBin("*", cur.second, rhs)
                  "/=" -> numericBin("/", cur.second, rhs)
                  else -> rhs
                }
                env.set(t.name, next)
                next
              }
              is CExpr.Index -> {
                val recv = evalExpr(t.recv, env)
                val idx = toInt(evalExpr(t.index, env))
                @Suppress("UNCHECKED_CAST")
                if (recv is MutableList<*>) {
                  val list = recv as MutableList<Any?>
                  val cur = list[idx]
                  val next = when (expr.op) {
                    "+=" -> numericBin("+", cur, rhs)
                    "-=" -> numericBin("-", cur, rhs)
                    "*=" -> numericBin("*", cur, rhs)
                    "/=" -> numericBin("/", cur, rhs)
                    else -> rhs
                  }
                  list[idx] = next
                  next
                } else rhs
              }
              is CExpr.Member -> {
                val recv = evalExpr(t.recv, env)
                if (recv is CStructInst) {
                  val cur = recv.fields[t.name]
                  val next = when (expr.op) {
                    "+=" -> numericBin("+", cur, rhs)
                    "-=" -> numericBin("-", cur, rhs)
                    "*=" -> numericBin("*", cur, rhs)
                    "/=" -> numericBin("/", cur, rhs)
                    else -> rhs
                  }
                  recv.fields[t.name] = next
                  next
                } else rhs
              }
              else -> throw CCompileErr(fileName, expr.line, 1, "invalid assignment target")
            }
          }
          is CExpr.Index -> {
            val recv = evalExpr(expr.recv, env)
            val idx = toInt(evalExpr(expr.index, env))
            when (recv) {
              is List<*> -> recv[idx]
              is String -> recv[idx]
              else -> null
            }
          }
          is CExpr.Member -> {
            val recv = evalExpr(expr.recv, env)
            when (recv) {
              is CStructInst -> recv.fields[expr.name]
              is Collection<*> -> when (expr.name) {
                "size" -> recv.size
                "empty" -> recv.isEmpty()
                else -> null
              }
              is String -> when (expr.name) {
                "size", "length" -> recv.length
                "empty" -> recv.isEmpty()
                else -> null
              }
              else -> null
            }
          }
          is CExpr.Call -> {
            val args = expr.args.map { evalExpr(it, env) }
            when (val c = expr.callee) {
              is CExpr.Ident -> callFunction(c.name, args, expr.line)
              is CExpr.Member -> {
                val recv = evalExpr(c.recv, env)
                @Suppress("UNCHECKED_CAST")
                when (recv) {
                  is MutableList<*> -> {
                    val list = recv as MutableList<Any?>
                    when (c.name) {
                      "push_back" -> { list.add(args[0]); null }
                      "pop_back" -> if (list.isNotEmpty()) list.removeAt(list.lastIndex) else null
                      "size" -> list.size
                      "empty" -> list.isEmpty()
                      "clear" -> { list.clear(); null }
                      "front" -> list.firstOrNull()
                      "back" -> list.lastOrNull()
                      else -> throw CCompileErr(fileName, expr.line, 1, "unknown vector method '${c.name}'")
                    }
                  }
                  is String -> when (c.name) {
                    "size", "length" -> recv.length
                    "empty" -> recv.isEmpty()
                    "substr" -> {
                      val start = toInt(args[0]).coerceIn(0, recv.length)
                      val len = if (args.size > 1) toInt(args[1]) else recv.length - start
                      recv.substring(start, (start + len).coerceIn(start, recv.length))
                    }
                    "c_str" -> recv
                    else -> throw CCompileErr(fileName, expr.line, 1, "unknown string method '${c.name}'")
                  }
                  else -> throw CCompileErr(fileName, expr.line, 1, "method call '${c.name}' on invalid receiver")
                }
              }
              else -> throw CCompileErr(fileName, expr.line, 1, "invalid call target")
            }
          }
          is CExpr.CoutChain -> {
            for (part in expr.parts) {
              val v = evalExpr(part, env)
              flushChars(v?.toString() ?: "")
            }
            null
          }
        }
      }

      execStmt = { stmt, env ->
        tick()
        when (stmt) {
          is CStmt.VarDecl -> {
            val initVal = stmt.init?.let { evalExpr(it, env) }
              ?: if (stmt.isArray) mutableListOf<Any?>() else 0
            env.def(stmt.name, initVal)
          }
          is CStmt.Block -> {
            val child = Env(env)
            for (s in stmt.stmts) execStmt(s, child)
          }
          is CStmt.If -> {
            if (truthy(evalExpr(stmt.cond, env))) {
              execStmt(stmt.thenBr, env)
            } else if (stmt.elseBr != null) {
              execStmt(stmt.elseBr, env)
            }
          }
          is CStmt.While -> {
            if (stmt.isDoWhile) {
              do {
                tick()
                try {
                  execStmt(stmt.body, env)
                } catch (_: ContinueSig) {
                  // continue
                } catch (_: BreakSig) {
                  break
                }
              } while (truthy(evalExpr(stmt.cond, env)))
            } else {
              while (truthy(evalExpr(stmt.cond, env))) {
                tick()
                try {
                  execStmt(stmt.body, env)
                } catch (_: ContinueSig) {
                  continue
                } catch (_: BreakSig) {
                  break
                }
              }
            }
          }
          is CStmt.For -> {
            val loopEnv = Env(env)
            stmt.init?.let { execStmt(it, loopEnv) }
            while (stmt.cond == null || truthy(evalExpr(stmt.cond, loopEnv))) {
              tick()
              try {
                execStmt(stmt.body, loopEnv)
              } catch (_: ContinueSig) {
                // run step
              } catch (_: BreakSig) {
                break
              }
              stmt.step?.let { evalExpr(it, loopEnv) }
            }
          }
          is CStmt.RangeFor -> {
            val items = evalExpr(stmt.iterable, env) as? Iterable<*>
              ?: throw CCompileErr(fileName, stmt.line, 1, "range-for target is not iterable")
            for (item in items) {
              tick()
              val itemEnv = Env(env)
              itemEnv.def(stmt.varName, item)
              try {
                execStmt(stmt.body, itemEnv)
              } catch (_: ContinueSig) {
                continue
              } catch (_: BreakSig) {
                break
              }
            }
          }
          is CStmt.Switch -> {
            val target = evalExpr(stmt.expr, env)
            var matched = false
            try {
              for ((caseExpr, body) in stmt.cases) {
                if (!matched) {
                  matched = caseExpr == null || target == evalExpr(caseExpr, env)
                }
                if (matched) {
                  for (s in body) execStmt(s, env)
                }
              }
            } catch (_: BreakSig) {
              // exit switch
            }
          }
          is CStmt.Return -> throw ReturnSig(stmt.expr?.let { evalExpr(it, env) })
          is CStmt.Break -> throw BreakSig
          is CStmt.Continue -> throw ContinueSig
          is CStmt.ExprS -> evalExpr(stmt.expr, env)
        }
      }

      for (g in globals) execStmt(g, globalEnv)
      val exitVal = try {
        for (st in mainFun.body) execStmt(st, Env(globalEnv))
        0
      } catch (r: ReturnSig) {
        toInt(r.value)
      }

      if (lineBuf.isNotEmpty()) {
        val rem = lineBuf.toString()
        stdout.add(rem)
        onOutputLine(rem)
      }
      ScriptExecutionOutput(exitVal, stdout)
    } catch (e: CCompileErr) {
      val msg = e.message ?: "C/C++ compile error"
      stdout.add(msg)
      onOutputLine(msg)
      ScriptExecutionOutput(1, stdout, msg)
    } catch (t: Throwable) {
      val msg = "$fileName: ${t.message ?: t::class.simpleName}"
      stdout.add(msg)
      onOutputLine(msg)
      ScriptExecutionOutput(1, stdout, msg)
    }
  }

  private fun formatPrintf(fmt: String, args: List<Any?>): String {
    val sb = StringBuilder(fmt.length + 16)
    var i = 0
    var argIdx = 0
    while (i < fmt.length) {
      val c = fmt[i]
      if (c == '%' && i + 1 < fmt.length) {
        if (fmt[i + 1] == '%') {
          sb.append('%')
          i += 2
          continue
        }
        var j = i + 1
        while (j < fmt.length && (fmt[j].isDigit() || fmt[j] == '.' || fmt[j] == '-' || fmt[j] == '+' || fmt[j] == 'l')) {
          j++
        }
        if (j < fmt.length) {
          val spec = fmt.substring(i, j + 1)
          val specChar = fmt[j]
          val arg = args.getOrNull(argIdx++)
          val cleanSpec = spec.replace("l", "")
          val formatted = runCatching {
            when (specChar) {
              'd', 'i', 'u' -> String.format(Locale.US, cleanSpec, (arg as? Number)?.toLong() ?: 0L)
              'x', 'X' -> String.format(Locale.US, cleanSpec, (arg as? Number)?.toLong() ?: 0L)
              'f', 'e', 'g' -> String.format(Locale.US, cleanSpec, (arg as? Number)?.toDouble() ?: 0.0)
              's' -> String.format(Locale.US, cleanSpec, arg?.toString() ?: "")
              'c' -> if (arg is Char) arg.toString() else ((arg as? Number)?.toInt() ?: 0).toChar().toString()
              else -> arg?.toString() ?: ""
            }
          }.getOrElse { arg?.toString() ?: "" }
          sb.append(formatted)
          i = j + 1
          continue
        }
      }
      sb.append(c)
      i++
    }
    return sb.toString()
  }

  /** Strips comments carefully for preprocessor while preserving line numbers; applies simple #define constants. */
  private fun preprocess(source: String): String {
    val defines = LinkedHashMap<String, String>()
    val sb = StringBuilder(source.length)
    for (raw in source.split('\n')) {
      val trimmed = raw.trim()
      if (trimmed.startsWith("#")) {
        if (trimmed.startsWith("#define ")) {
          val rest = trimmed.removePrefix("#define ").trim()
          val space = rest.indexOfFirst { it.isWhitespace() }
          if (space > 0) {
            val k = rest.substring(0, space).trim()
            val v = rest.substring(space + 1).trim()
            if (!k.contains('(') && v.isNotEmpty()) defines[k] = v
          }
        }
        sb.append('\n')
      } else {
        var line = raw
        if (defines.isNotEmpty()) {
          for ((k, v) in defines) {
            line = line.replace(Regex("""\b${Regex.escape(k)}\b"""), v)
          }
        }
        sb.append(line).append('\n')
      }
    }
    return sb.toString()
  }

  private fun lex(fileName: String, src: String): List<CTok> {
    val out = ArrayList<CTok>(256)
    var i = 0
    var line = 1
    var col = 1
    fun peek(off: Int = 1): Char = if (i + off < src.length) src[i + off] else '\u0000'

    while (i < src.length) {
      val c = src[i]
      when {
        c == '\r' -> i++
        c == '\n' -> { i++; line++; col = 1 }
        c.isWhitespace() -> { i++; col++ }
        c == '/' && peek() == '/' -> {
          while (i < src.length && src[i] != '\n') { i++; col++ }
        }
        c == '/' && peek() == '*' -> {
          val sl = line; val sc = col
          i += 2; col += 2
          var closed = false
          while (i < src.length) {
            if (src[i] == '*' && peek() == '/') { i += 2; col += 2; closed = true; break }
            if (src[i] == '\n') { line++; col = 1 } else col++
            i++
          }
          if (!closed) throw CCompileErr(fileName, sl, sc, "unterminated block comment")
        }
        c == '"' -> {
          val sl = line; val sc = col
          i++; col++
          val sb = StringBuilder()
          var closed = false
          while (i < src.length) {
            val ch = src[i]
            if (ch == '\n') throw CCompileErr(fileName, sl, sc, "unterminated string literal")
            if (ch == '"') { i++; col++; closed = true; break }
            if (ch == '\\' && i + 1 < src.length) {
              val n = src[i + 1]
              sb.append(when (n) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '\\' -> '\\'; '"' -> '"'; else -> n })
              i += 2; col += 2
            } else {
              sb.append(ch); i++; col++
            }
          }
          if (!closed) throw CCompileErr(fileName, sl, sc, "unterminated string literal")
          out.add(CTok(CK.STRING_LIT, sb.toString(), sl, sc))
        }
        c == '\'' -> {
          val sl = line; val sc = col
          i++; col++
          val ch = if (i < src.length && src[i] == '\\' && i + 1 < src.length) {
            val esc = when (src[i + 1]) { 'n' -> '\n'; 't' -> '\t'; '0' -> '\u0000'; else -> src[i + 1] }
            i += 2; col += 2; esc
          } else if (i < src.length) {
            val v = src[i]; i++; col++; v
          } else '\u0000'
          if (i >= src.length || src[i] != '\'') throw CCompileErr(fileName, sl, sc, "unterminated character literal")
          i++; col++
          out.add(CTok(CK.CHAR_LIT, ch.toString(), sl, sc))
        }
        c.isDigit() -> {
          val sc = col; val start = i
          var hasDot = false
          while (i < src.length && (src[i].isDigit() || (src[i] == '.' && !hasDot && peek().isDigit()))) {
            if (src[i] == '.') hasDot = true
            i++; col++
          }
          while (i < src.length && src[i] in listOf('f', 'F', 'l', 'L', 'u', 'U')) { i++; col++ }
          val raw = src.substring(start, i)
          out.add(CTok(if (hasDot) CK.DOUBLE_LIT else CK.INT_LIT, raw.trimEnd('f', 'F', 'l', 'L', 'u', 'U'), line, sc))
        }
        c.isLetter() || c == '_' -> {
          val sc = col; val start = i
          while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) { i++; col++ }
          val word = src.substring(start, i)
          val kind = when (word) {
            "return" -> CK.KW_RETURN; "if" -> CK.KW_IF; "else" -> CK.KW_ELSE
            "for" -> CK.KW_FOR; "while" -> CK.KW_WHILE; "do" -> CK.KW_DO
            "switch" -> CK.KW_SWITCH; "case" -> CK.KW_CASE; "default" -> CK.KW_DEFAULT
            "break" -> CK.KW_BREAK; "continue" -> CK.KW_CONTINUE
            "struct", "class" -> CK.KW_STRUCT; "const" -> CK.KW_CONST
            "true" -> CK.KW_TRUE; "false" -> CK.KW_FALSE; "sizeof" -> CK.KW_SIZEOF
            else -> CK.IDENT
          }
          out.add(CTok(kind, word, line, sc))
        }
        else -> {
          val sc = col; val n = peek()
          val two = "$c$n"
          val matched2 = when (two) {
            "==" -> CK.EQ_EQ; "!=" -> CK.BANG_EQ; "<=" -> CK.LT_EQ; ">=" -> CK.GT_EQ
            "&&" -> CK.AND_AND; "||" -> CK.OR_OR; "+=" -> CK.PLUS_EQ; "-=" -> CK.MINUS_EQ
            "*=" -> CK.STAR_EQ; "/=" -> CK.SLASH_EQ; "++" -> CK.PLUS_PLUS; "--" -> CK.MINUS_MINUS
            "<<" -> CK.LSHIFT; ">>" -> CK.RSHIFT; "->" -> CK.ARROW; "::" -> CK.SCOPE
            else -> null
          }
          if (matched2 != null) {
            out.add(CTok(matched2, two, line, sc))
            i += 2; col += 2
          } else {
            val k1 = when (c) {
              '+' -> CK.PLUS; '-' -> CK.MINUS; '*' -> CK.STAR; '/' -> CK.SLASH; '%' -> CK.PERCENT
              '=' -> CK.EQ; '<' -> CK.LT; '>' -> CK.GT; '!' -> CK.BANG
              '&' -> CK.AMP; '|' -> CK.PIPE; '^' -> CK.CARET
              '?' -> CK.QUESTION; ':' -> CK.COLON; ';' -> CK.SEMI; ',' -> CK.COMMA
              '.' -> CK.DOT; '(' -> CK.LPAREN; ')' -> CK.RPAREN
              '{' -> CK.LBRACE; '}' -> CK.RBRACE; '[' -> CK.LBRACK; ']' -> CK.RBRACK
              else -> throw CCompileErr(fileName, line, col, "unexpected character '$c'")
            }
            out.add(CTok(k1, c.toString(), line, sc))
            i++; col++
          }
        }
      }
    }
    out.add(CTok(CK.EOF, "", line, col))
    return out
  }

  private class CParser(private val fileName: String, private val tokens: List<CTok>) {
    private var idx = 0
    private fun cur(): CTok = tokens[idx.coerceAtMost(tokens.lastIndex)]
    private fun peek(off: Int = 1): CTok = tokens[(idx + off).coerceAtMost(tokens.lastIndex)]
    private fun match(k: CK): Boolean {
      if (cur().kind == k) { idx++; return true }
      return false
    }
    private fun expect(k: CK, msg: String): CTok {
      val t = cur()
      if (t.kind != k) throw CCompileErr(fileName, t.line, t.col, msg)
      idx++
      return t
    }

    private fun isTypeStart(): Boolean {
      val k = cur().kind
      if (k == CK.KW_CONST) return true
      if (k != CK.IDENT) return false
      val w = cur().text
      if (w in BASE_TYPES || w in structNames) return true
      // Check std::vector<T> or std::string
      if (w == "std" && peek(1).kind == CK.SCOPE) return true
      return false
    }

    private val structNames = HashSet<String>()

    fun parseProgram(): Triple<Map<String, CFun>, Map<String, CStruct>, List<CStmt>> {
      val funs = LinkedHashMap<String, CFun>()
      val structs = LinkedHashMap<String, CStruct>()
      val globals = ArrayList<CStmt>()

      while (cur().kind != CK.EOF) {
        if (cur().kind == CK.IDENT && cur().text == "using") {
          while (cur().kind != CK.EOF && cur().kind != CK.SEMI) idx++
          match(CK.SEMI)
          continue
        }
        if (cur().kind == CK.KW_STRUCT) {
          val st = parseStruct()
          structs[st.name] = st
          structNames.add(st.name)
          continue
        }
        if (cur().kind == CK.KW_CONST) match(CK.KW_CONST)
        val typeName = parseTypeName()
        val name = expect(CK.IDENT, "expected declaration identifier").text
        if (cur().kind == CK.LPAREN) {
          val fn = parseFunctionRest(typeName, name, cur().line)
          funs[fn.name] = fn
        } else {
          globals.add(parseVarDeclRest(cur().line, name))
          expect(CK.SEMI, "expected ';' after global declaration")
        }
      }
      return Triple(funs, structs, globals)
    }

    private fun parseStruct(): CStruct {
      expect(CK.KW_STRUCT, "expected struct")
      val name = expect(CK.IDENT, "expected struct name").text
      expect(CK.LBRACE, "expected '{'")
      val fields = ArrayList<Pair<String, String>>()
      while (cur().kind != CK.EOF && cur().kind != CK.RBRACE) {
        val fType = parseTypeName()
        val fName = expect(CK.IDENT, "expected field name").text
        fields.add(fName to fType)
        expect(CK.SEMI, "expected ';' after struct field")
      }
      expect(CK.RBRACE, "expected '}'")
      match(CK.SEMI)
      return CStruct(name, fields)
    }

    private fun parseTypeName(): String {
      if (match(CK.KW_CONST)) { /* skip */ }
      val sb = StringBuilder()
      val base = expect(CK.IDENT, "expected type name").text
      sb.append(base)
      if (base == "std" && match(CK.SCOPE)) {
        sb.append("::").append(expect(CK.IDENT, "expected identifier after std::").text)
      }
      while (cur().kind == CK.IDENT && cur().text in setOf("int", "long", "double", "short", "signed", "unsigned")) {
        sb.append(' ').append(cur().text)
        idx++
      }
      if (match(CK.LT)) {
        var d = 1
        while (d > 0 && cur().kind != CK.EOF) {
          if (cur().kind == CK.LT) d++ else if (cur().kind == CK.GT) d--
          idx++
        }
      }
      while (match(CK.STAR) || match(CK.AMP)) { /* pointer / ref marker consumed */ }
      return sb.toString()
    }

    private fun parseFunctionRest(retType: String, name: String, line: Int): CFun {
      expect(CK.LPAREN, "expected '('")
      val params = ArrayList<CParam>()
      if (cur().kind != CK.RPAREN && !(cur().kind == CK.IDENT && cur().text == "void" && peek().kind == CK.RPAREN)) {
        while (true) {
          if (cur().kind == CK.KW_CONST) idx++
          val pType = parseTypeName()
          val pName = expect(CK.IDENT, "expected parameter name").text
          if (match(CK.LBRACK)) { while (cur().kind != CK.RBRACK && cur().kind != CK.EOF) idx++; match(CK.RBRACK) }
          params.add(CParam(pType, pName))
          if (!match(CK.COMMA)) break
        }
      } else if (cur().kind == CK.IDENT && cur().text == "void") {
        idx++
      }
      expect(CK.RPAREN, "expected ')'")
      if (match(CK.SEMI)) {
        return CFun(name, retType, params, emptyList(), line) // forward prototype
      }
      val block = parseBlock()
      return CFun(name, retType, params, block.stmts, line)
    }

    private fun parseVarDeclRest(line: Int, name: String): CStmt.VarDecl {
      var isArr = false
      if (match(CK.LBRACK)) {
        isArr = true
        while (cur().kind != CK.EOF && cur().kind != CK.RBRACK) idx++
        expect(CK.RBRACK, "expected ']'")
      }
      val init = if (match(CK.EQ)) parseExpr() else null
      return CStmt.VarDecl(line, name, isArr, init)
    }

    private fun parseBlock(): CStmt.Block {
      val t = expect(CK.LBRACE, "expected '{'")
      val list = ArrayList<CStmt>()
      while (cur().kind != CK.EOF && cur().kind != CK.RBRACE) {
        list.add(parseStmt())
      }
      expect(CK.RBRACE, "expected '}'")
      return CStmt.Block(t.line, list)
    }

    private fun parseStmt(): CStmt {
      val t = cur()
      return when (t.kind) {
        CK.LBRACE -> parseBlock()
        CK.KW_IF -> {
          idx++; expect(CK.LPAREN, "expected '('")
          val cond = parseExpr(); expect(CK.RPAREN, "expected ')'")
          val th = parseStmt()
          val el = if (match(CK.KW_ELSE)) parseStmt() else null
          CStmt.If(t.line, cond, th, el)
        }
        CK.KW_WHILE -> {
          idx++; expect(CK.LPAREN, "expected '('")
          val cond = parseExpr(); expect(CK.RPAREN, "expected ')'")
          CStmt.While(t.line, cond, parseStmt(), isDoWhile = false)
        }
        CK.KW_DO -> {
          idx++
          val body = parseStmt()
          expect(CK.KW_WHILE, "expected 'while'"); expect(CK.LPAREN, "expected '('")
          val cond = parseExpr(); expect(CK.RPAREN, "expected ')'"); expect(CK.SEMI, "expected ';'")
          CStmt.While(t.line, cond, body, isDoWhile = true)
        }
        CK.KW_FOR -> parseFor(t.line)
        CK.KW_SWITCH -> parseSwitch(t.line)
        CK.KW_RETURN -> {
          idx++
          val e = if (cur().kind != CK.SEMI) parseExpr() else null
          expect(CK.SEMI, "expected ';' after return")
          CStmt.Return(t.line, e)
        }
        CK.KW_BREAK -> { idx++; expect(CK.SEMI, "expected ';'"); CStmt.Break(t.line) }
        CK.KW_CONTINUE -> { idx++; expect(CK.SEMI, "expected ';'"); CStmt.Continue(t.line) }
        else -> {
          if (isTypeStart() && isLocalVarDeclLookahead()) {
            parseTypeName()
            val vName = expect(CK.IDENT, "expected variable name").text
            val d = parseVarDeclRest(t.line, vName)
            expect(CK.SEMI, "expected ';'")
            d
          } else {
            val e = parseExpr()
            expect(CK.SEMI, "expected ';'")
            CStmt.ExprS(t.line, e)
          }
        }
      }
    }

    private fun isLocalVarDeclLookahead(): Boolean {
      var save = idx
      return try {
        if (cur().kind == CK.KW_CONST) idx++
        parseTypeName()
        cur().kind == CK.IDENT && (peek().kind in setOf(CK.EQ, CK.SEMI, CK.LBRACK))
      } catch (_: Throwable) {
        false
      } finally {
        idx = save
      }
    }

    private fun parseFor(line: Int): CStmt {
      expect(CK.KW_FOR, "expected for")
      expect(CK.LPAREN, "expected '('")
      // Range-based for: `for (auto x : vec)`
      val save = idx
      if (isTypeStart()) {
        try {
          parseTypeName()
          if (cur().kind == CK.IDENT && peek().kind == CK.COLON) {
            val varName = cur().text
            idx += 2
            val iter = parseExpr()
            expect(CK.RPAREN, "expected ')'")
            return CStmt.RangeFor(line, varName, iter, parseStmt())
          }
        } catch (_: Throwable) {
          // fallback
        }
      }
      idx = save

      val init = if (cur().kind != CK.SEMI) {
        if (isTypeStart()) {
          parseTypeName()
          val vn = expect(CK.IDENT, "expected loop var").text
          parseVarDeclRest(line, vn)
        } else {
          CStmt.ExprS(line, parseExpr())
        }
      } else null
      expect(CK.SEMI, "expected ';'")
      val cond = if (cur().kind != CK.SEMI) parseExpr() else null
      expect(CK.SEMI, "expected ';'")
      val step = if (cur().kind != CK.RPAREN) parseExpr() else null
      expect(CK.RPAREN, "expected ')'")
      return CStmt.For(line, init, cond, step, parseStmt())
    }

    private fun parseSwitch(line: Int): CStmt {
      expect(CK.KW_SWITCH, "expected switch"); expect(CK.LPAREN, "expected '('")
      val expr = parseExpr(); expect(CK.RPAREN, "expected ')'"); expect(CK.LBRACE, "expected '{'")
      val cases = ArrayList<Pair<CExpr?, List<CStmt>>>()
      while (cur().kind != CK.EOF && cur().kind != CK.RBRACE) {
        val cExpr = when (cur().kind) {
          CK.KW_CASE -> { idx++; val e = parseExpr(); expect(CK.COLON, "expected ':'"); e }
          CK.KW_DEFAULT -> { idx++; expect(CK.COLON, "expected ':'"); null }
          else -> throw CCompileErr(fileName, cur().line, cur().col, "expected case or default")
        }
        val stmts = ArrayList<CStmt>()
        while (cur().kind != CK.EOF && cur().kind != CK.RBRACE && cur().kind != CK.KW_CASE && cur().kind != CK.KW_DEFAULT) {
          stmts.add(parseStmt())
        }
        cases.add(cExpr to stmts)
      }
      expect(CK.RBRACE, "expected '}'")
      return CStmt.Switch(line, expr, cases)
    }

    private fun parseExpr(): CExpr = parseAssign()

    private fun parseAssign(): CExpr {
      val left = parseTernary()
      val k = cur().kind
      if (k in setOf(CK.EQ, CK.PLUS_EQ, CK.MINUS_EQ, CK.STAR_EQ, CK.SLASH_EQ)) {
        val op = cur().text; val ln = cur().line; idx++
        return CExpr.Assign(ln, left, op, parseAssign())
      }
      return left
    }

    private fun parseTernary(): CExpr {
      val cond = parseOr()
      if (match(CK.QUESTION)) {
        val a = parseExpr()
        expect(CK.COLON, "expected ':'")
        val b = parseTernary()
        return CExpr.Ternary(cond.line, cond, a, b)
      }
      return cond
    }

    private fun parseOr(): CExpr {
      var l = parseAnd()
      while (match(CK.OR_OR)) l = CExpr.Binary(l.line, "||", l, parseAnd())
      return l
    }

    private fun parseAnd(): CExpr {
      var l = parseEq()
      while (match(CK.AND_AND)) l = CExpr.Binary(l.line, "&&", l, parseEq())
      return l
    }

    private fun parseEq(): CExpr {
      var l = parseRel()
      while (cur().kind == CK.EQ_EQ || cur().kind == CK.BANG_EQ) {
        val op = cur().text; idx++
        l = CExpr.Binary(l.line, op, l, parseRel())
      }
      return l
    }

    private fun parseRel(): CExpr {
      var l = parseShift()
      while (cur().kind in setOf(CK.LT, CK.LT_EQ, CK.GT, CK.GT_EQ)) {
        val op = cur().text; idx++
        l = CExpr.Binary(l.line, op, l, parseShift())
      }
      return l
    }

    private fun parseShift(): CExpr {
      val first = parseAdd()
      val isCout = (first is CExpr.Ident && (first.name == "cout" || first.name == "std::cout"))
      if (isCout && cur().kind == CK.LSHIFT) {
        val parts = ArrayList<CExpr>()
        while (match(CK.LSHIFT)) {
          parts.add(parseAdd())
        }
        return CExpr.CoutChain(first.line, parts)
      }
      var l = first
      while (cur().kind == CK.LSHIFT || cur().kind == CK.RSHIFT) {
        val op = cur().text; idx++
        l = CExpr.Binary(l.line, op, l, parseAdd())
      }
      return l
    }

    private fun parseAdd(): CExpr {
      var l = parseMul()
      while (cur().kind == CK.PLUS || cur().kind == CK.MINUS) {
        val op = cur().text; idx++
        l = CExpr.Binary(l.line, op, l, parseMul())
      }
      return l
    }

    private fun parseMul(): CExpr {
      var l = parseUnary()
      while (cur().kind in setOf(CK.STAR, CK.SLASH, CK.PERCENT)) {
        val op = cur().text; idx++
        l = CExpr.Binary(l.line, op, l, parseUnary())
      }
      return l
    }

    private fun parseUnary(): CExpr {
      val t = cur()
      if (t.kind in setOf(CK.BANG, CK.MINUS, CK.PLUS, CK.PLUS_PLUS, CK.MINUS_MINUS)) {
        idx++
        return CExpr.Unary(t.line, t.text, parseUnary())
      }
      if (t.kind == CK.KW_SIZEOF) {
        idx++; expect(CK.LPAREN, "expected '('")
        val e = parseExpr(); expect(CK.RPAREN, "expected ')'")
        return CExpr.Unary(t.line, "sizeof", e)
      }
      // C cast `(double)x`
      if (t.kind == CK.LPAREN && peek(1).kind == CK.IDENT && peek(1).text in BASE_TYPES && peek(2).kind == CK.RPAREN) {
        val castType = peek(1).text
        idx += 3
        return CExpr.Cast(t.line, castType, parseUnary())
      }
      return parsePostfix()
    }

    private fun parsePostfix(): CExpr {
      var e = parsePrimary()
      while (true) {
        when (cur().kind) {
          CK.LPAREN -> {
            idx++
            val args = ArrayList<CExpr>()
            if (cur().kind != CK.RPAREN) {
              while (true) {
                args.add(parseExpr())
                if (!match(CK.COMMA)) break
              }
            }
            expect(CK.RPAREN, "expected ')'")
            e = CExpr.Call(e.line, e, args)
          }
          CK.DOT, CK.ARROW -> {
            idx++
            val name = expect(CK.IDENT, "expected member name").text
            e = CExpr.Member(e.line, e, name)
          }
          CK.LBRACK -> {
            idx++
            val iExpr = parseExpr()
            expect(CK.RBRACK, "expected ']'")
            e = CExpr.Index(e.line, e, iExpr)
          }
          CK.PLUS_PLUS, CK.MINUS_MINUS -> {
            val op = cur().text; idx++
            e = CExpr.Unary(e.line, op, e, postfix = true)
          }
          else -> return e
        }
      }
    }

    private fun parsePrimary(): CExpr {
      val t = cur()
      return when (t.kind) {
        CK.INT_LIT -> { idx++; CExpr.Lit(t.line, t.text.toIntOrNull() ?: 0) }
        CK.DOUBLE_LIT -> { idx++; CExpr.Lit(t.line, t.text.toDoubleOrNull() ?: 0.0) }
        CK.STRING_LIT -> { idx++; CExpr.Lit(t.line, t.text) }
        CK.CHAR_LIT -> { idx++; CExpr.Lit(t.line, t.text.firstOrNull() ?: '\u0000') }
        CK.KW_TRUE -> { idx++; CExpr.Lit(t.line, true) }
        CK.KW_FALSE -> { idx++; CExpr.Lit(t.line, false) }
        CK.IDENT -> {
          idx++
          if (t.text == "std" && match(CK.SCOPE)) {
            val member = expect(CK.IDENT, "expected identifier after std::").text
            CExpr.Ident(t.line, "std::$member")
          } else {
            CExpr.Ident(t.line, t.text)
          }
        }
        CK.LPAREN -> {
          idx++
          val inner = parseExpr()
          expect(CK.RPAREN, "expected ')'")
          inner
        }
        CK.LBRACE -> {
          idx++
          val items = ArrayList<CExpr>()
          if (cur().kind != CK.RBRACE) {
            while (true) {
              items.add(parseExpr())
              if (!match(CK.COMMA)) break
              if (cur().kind == CK.RBRACE) break
            }
          }
          expect(CK.RBRACE, "expected '}'")
          CExpr.InitList(t.line, items)
        }
        else -> throw CCompileErr(fileName, t.line, t.col, "unexpected token '${t.text}'")
      }
    }

    companion object {
      private val BASE_TYPES = setOf(
        "int", "long", "double", "float", "char", "bool", "void", "short",
        "unsigned", "signed", "auto", "size_t", "string", "vector",
      )
    }
  }
}
