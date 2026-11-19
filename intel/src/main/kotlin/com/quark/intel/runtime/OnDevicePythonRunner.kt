package com.quark.intel.runtime

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 100% on-device pure-Kotlin Python subset evaluator supporting indentation blocks,
 * `def`, `if/elif/else`, `for ... in ...`, `while`, f-strings, lists/dicts, `range`,
 * `enumerate`, `len`, `sum`, `sorted`, and `print`.
 */
object OnDevicePythonRunner {

  private class PyReturn(val value: Any?) : RuntimeException(null, null, false, false)
  private object PyBreak : RuntimeException(null, null, false, false)
  private object PyContinue : RuntimeException(null, null, false, false)

  private data class PyFun(val name: String, val params: List<String>, val body: List<PyLine>)
  private data class PyLine(val lineNo: Int, val indent: Int, val code: String)

  fun run(
    fileName: String,
    source: String,
    onOutputLine: (String) -> Unit = {},
  ): ScriptExecutionOutput {
    val stdout = ArrayList<String>()
    fun emitLine(msg: String) {
      for (part in msg.split('\n')) {
        stdout.add(part)
        onOutputLine(part)
      }
    }

    return try {
      val rawLines = preprocessLines(source)
      val globalVars = LinkedHashMap<String, Any?>()
      globalVars["__name__"] = "__main__"
      globalVars["True"] = true
      globalVars["False"] = false
      globalVars["None"] = null
      val funs = HashMap<String, PyFun>()
      var steps = 0

      fun tick() {
        if (++steps > 1_000_000) throw IllegalStateException("Execution step limit exceeded")
      }

      fun execBlock(lines: List<PyLine>, scope: MutableMap<String, Any?>): Any? {
        var i = 0
        while (i < lines.size) {
          tick()
          val line = lines[i]
          val code = line.code

          when {
            code.startsWith("import ") || code.startsWith("from ") || code == "pass" -> i++
            code.startsWith("def ") -> {
              val m = Regex("""^def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\).*:$""").find(code)
                ?: error("Line ${line.lineNo}: invalid def syntax")
              val fnName = m.groupValues[1]
              val params = m.groupValues[2].split(',').mapNotNull {
                it.trim().substringBefore(':').substringBefore('=').trim().takeIf { p -> p.isNotEmpty() }
              }
              val body = collectChildBlock(lines, i + 1, line.indent)
              funs[fnName] = PyFun(fnName, params, body)
              i += 1 + body.size
            }
            code.startsWith("if ") && code.endsWith(":") -> {
              var nextIdx = i
              var executed = false
              var first = true
              while (nextIdx < lines.size) {
                val cur = lines[nextIdx]
                if (cur.indent != line.indent) break
                val isIf = first && cur.code.startsWith("if ")
                val isElif = !first && cur.code.startsWith("elif ")
                val isElse = !first && cur.code.startsWith("else:")
                if (!isIf && !isElif && !isElse) break
                val child = collectChildBlock(lines, nextIdx + 1, cur.indent)
                if (!executed) {
                  val take = when {
                    isIf -> isTruthy(evalExpr(cur.code.removePrefix("if ").dropLast(1).trim(), scope, funs, ::emitLine, ::execBlock))
                    isElif -> isTruthy(evalExpr(cur.code.removePrefix("elif ").dropLast(1).trim(), scope, funs, ::emitLine, ::execBlock))
                    else -> true
                  }
                  if (take) {
                    execBlock(child, scope)
                    executed = true
                  }
                }
                first = false
                nextIdx += 1 + child.size
                if (isElse) break
              }
              i = nextIdx
            }
            code.startsWith("for ") && code.endsWith(":") -> {
              val m = Regex("""^for\s+([A-Za-z_][A-Za-z0-9_,\s]+?)\s+in\s+(.+):$""").find(code)
                ?: error("Line ${line.lineNo}: invalid for loop")
              val targets = m.groupValues[1].split(',').map { it.trim() }
              val iterExpr = m.groupValues[2].trim()
              val iterable = evalExpr(iterExpr, scope, funs, ::emitLine, ::execBlock) as? Iterable<*>
                ?: error("Line ${line.lineNo}: object is not iterable")
              val body = collectChildBlock(lines, i + 1, line.indent)
              for (item in iterable) {
                tick()
                if (targets.size == 1) {
                  scope[targets[0]] = item
                } else if (item is List<*>) {
                  targets.forEachIndexed { idx, t -> scope[t] = item.getOrNull(idx) }
                }
                try {
                  execBlock(body, scope)
                } catch (_: PyContinue) {
                  continue
                } catch (_: PyBreak) {
                  break
                }
              }
              i += 1 + body.size
            }
            code.startsWith("while ") && code.endsWith(":") -> {
              val condExpr = code.removePrefix("while ").dropLast(1).trim()
              val body = collectChildBlock(lines, i + 1, line.indent)
              while (isTruthy(evalExpr(condExpr, scope, funs, ::emitLine, ::execBlock))) {
                tick()
                try {
                  execBlock(body, scope)
                } catch (_: PyContinue) {
                  continue
                } catch (_: PyBreak) {
                  break
                }
              }
              i += 1 + body.size
            }
            code == "return" -> throw PyReturn(null)
            code.startsWith("return ") -> {
              val valExpr = code.removePrefix("return ").trim()
              throw PyReturn(evalExpr(valExpr, scope, funs, ::emitLine, ::execBlock))
            }
            code == "break" -> throw PyBreak
            code == "continue" -> throw PyContinue
            else -> {
              val eqIdx = findTopLevelAssign(code)
              if (eqIdx > 0) {
                val opEnd = if (code[eqIdx - 1] in listOf('+', '-', '*', '/')) eqIdx - 1 else eqIdx
                val target = code.substring(0, opEnd).trim()
                val rhs = evalExpr(code.substring(eqIdx + 1).trim(), scope, funs, ::emitLine, ::execBlock)
                if (opEnd < eqIdx) {
                  val op = code[opEnd]
                  scope[target] = applyBin(op.toString(), scope[target], rhs)
                } else {
                  scope[target] = rhs
                }
              } else {
                evalExpr(code, scope, funs, ::emitLine, ::execBlock)
              }
              i++
            }
          }
        }
        return null
      }

      execBlock(rawLines, globalVars)
      ScriptExecutionOutput(0, stdout)
    } catch (t: Throwable) {
      val msg = "$fileName: ${t.message ?: t::class.simpleName}"
      emitLine(msg)
      ScriptExecutionOutput(1, stdout, msg)
    }
  }

  private fun evalExpr(
    raw: String,
    scope: MutableMap<String, Any?>,
    funs: Map<String, PyFun>,
    emitLine: (String) -> Unit,
    blockRunner: (List<PyLine>, MutableMap<String, Any?>) -> Any?,
  ): Any? {
    val expr = raw.trim()
    if (expr.isEmpty()) return null

    // Parenthesized whole expression
    if (expr.startsWith("(") && expr.endsWith(")") && matchingParenEnd(expr, 0) == expr.lastIndex) {
      return evalExpr(expr.substring(1, expr.lastIndex), scope, funs, emitLine, blockRunner)
    }

    // List literal
    if (expr.startsWith("[") && expr.endsWith("]") && matchingBracketEnd(expr, 0) == expr.lastIndex) {
      val inner = expr.substring(1, expr.lastIndex).trim()
      if (inner.isEmpty()) return mutableListOf<Any?>()
      return splitTopLevel(inner, ',').map { evalExpr(it, scope, funs, emitLine, blockRunner) }.toMutableList()
    }

    // Dict literal
    if (expr.startsWith("{") && expr.endsWith("}") && matchingBraceEnd(expr, 0) == expr.lastIndex) {
      val inner = expr.substring(1, expr.lastIndex).trim()
      val map = LinkedHashMap<Any?, Any?>()
      if (inner.isNotEmpty()) {
        for (entry in splitTopLevel(inner, ',')) {
          val colon = findTopLevelChar(entry, ':')
          if (colon > 0) {
            val k = evalExpr(entry.substring(0, colon), scope, funs, emitLine, blockRunner)
            val v = evalExpr(entry.substring(colon + 1), scope, funs, emitLine, blockRunner)
            map[k] = v
          }
        }
      }
      return map
    }

    // Boolean or / and
    findTopLevelKeyword(expr, " or ").takeIf { it >= 0 }?.let { idx ->
      val l = evalExpr(expr.substring(0, idx), scope, funs, emitLine, blockRunner)
      return if (isTruthy(l)) l else evalExpr(expr.substring(idx + 4), scope, funs, emitLine, blockRunner)
    }
    findTopLevelKeyword(expr, " and ").takeIf { it >= 0 }?.let { idx ->
      val l = evalExpr(expr.substring(0, idx), scope, funs, emitLine, blockRunner)
      return if (!isTruthy(l)) l else evalExpr(expr.substring(idx + 5), scope, funs, emitLine, blockRunner)
    }

    // Comparisons
    for (op in listOf("==", "!=", "<=", ">=", "<", ">", " in ")) {
      val idx = findTopLevelOp(expr, op)
      if (idx > 0) {
        val l = evalExpr(expr.substring(0, idx), scope, funs, emitLine, blockRunner)
        val r = evalExpr(expr.substring(idx + op.length), scope, funs, emitLine, blockRunner)
        return when (op) {
          "==" -> l == r
          "!=" -> l != r
          "<" -> cmp(l, r) < 0
          "<=" -> cmp(l, r) <= 0
          ">" -> cmp(l, r) > 0
          ">=" -> cmp(l, r) >= 0
          " in " -> when (r) {
            is Iterable<*> -> l in r
            is Map<*, *> -> l in r
            is String -> l?.toString()?.let { r.contains(it) } ?: false
            else -> false
          }
          else -> false
        }
      }
    }

    // Additive & Multiplicative
    val addIdx = findLastTopLevelOps(expr, listOf("+", "-"))
    if (addIdx > 0) {
      val op = expr[addIdx].toString()
      return applyBin(op, evalExpr(expr.substring(0, addIdx), scope, funs, emitLine, blockRunner), evalExpr(expr.substring(addIdx + 1), scope, funs, emitLine, blockRunner))
    }
    val mulIdx = findLastTopLevelOps(expr, listOf("*", "//", "/", "%"))
    if (mulIdx > 0) {
      val op = if (expr.startsWith("//", mulIdx)) "//" else expr[mulIdx].toString()
      return applyBin(op, evalExpr(expr.substring(0, mulIdx), scope, funs, emitLine, blockRunner), evalExpr(expr.substring(mulIdx + op.length), scope, funs, emitLine, blockRunner))
    }

    if (expr.startsWith("not ")) return !isTruthy(evalExpr(expr.removePrefix("not "), scope, funs, emitLine, blockRunner))
    if (expr.startsWith("-") && expr.length > 1) {
      val v = evalExpr(expr.substring(1), scope, funs, emitLine, blockRunner) as Number
      return if (v is Double) -v else -v.toInt()
    }

    // F-string
    if ((expr.startsWith("f\"") && expr.endsWith("\"")) || (expr.startsWith("f'") && expr.endsWith("'"))) {
      val q = expr[1]
      val inner = expr.substring(2, expr.length - 1)
      return formatFString(inner, scope, funs, emitLine, q, blockRunner)
    }
    // Normal string literal
    if ((expr.startsWith("\"") && expr.endsWith("\"")) || (expr.startsWith("'") && expr.endsWith("'"))) {
      return expr.substring(1, expr.length - 1)
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\'", "'")
    }

    // Numbers
    expr.toIntOrNull()?.let { return it }
    expr.toDoubleOrNull()?.let { return it }

    // Function or method call
    val callOpen = findTopLevelChar(expr, '(')
    if (callOpen > 0 && expr.endsWith(")")) {
      val targetStr = expr.substring(0, callOpen).trim()
      val argsStr = expr.substring(callOpen + 1, expr.lastIndex).trim()
      val args = if (argsStr.isEmpty()) emptyList() else splitTopLevel(argsStr, ',').map { evalExpr(it, scope, funs, emitLine, blockRunner) }

      val dotIdx = targetStr.lastIndexOf('.')
      if (dotIdx > 0) {
        val recv = evalExpr(targetStr.substring(0, dotIdx), scope, funs, emitLine, blockRunner)
        val mName = targetStr.substring(dotIdx + 1).trim()
        return invokePyMethod(recv, mName, args)
      }

      funs[targetStr]?.let { fn ->
        val local = LinkedHashMap<String, Any?>(scope)
        fn.params.forEachIndexed { idx, p -> local[p] = args.getOrNull(idx) }
        return try {
          blockRunner(fn.body, local)
        } catch (r: PyReturn) {
          r.value
        }
      }
      return invokePyBuiltin(targetStr, args, emitLine)
    }

    // Indexing x[i]
    val brIdx = expr.lastIndexOf('[')
    if (brIdx > 0 && expr.endsWith("]")) {
      val recv = evalExpr(expr.substring(0, brIdx), scope, funs, emitLine, blockRunner)
      val key = evalExpr(expr.substring(brIdx + 1, expr.lastIndex), scope, funs, emitLine, blockRunner)
      return when (recv) {
        is List<*> -> recv[(key as Number).toInt()]
        is Map<*, *> -> recv[key]
        is String -> recv[(key as Number).toInt()].toString()
        else -> null
      }
    }

    if (scope.containsKey(expr)) return scope[expr]
    error("NameError: name '$expr' is not defined")
  }

  private fun invokePyBuiltin(name: String, args: List<Any?>, emitLine: (String) -> Unit): Any? =
    when (name) {
      "print" -> {
        emitLine(args.joinToString(" ") { pyStr(it) })
        null
      }
      "len" -> when (val x = args[0]) {
        is Collection<*> -> x.size
        is Map<*, *> -> x.size
        is String -> x.length
        else -> 0
      }
      "range" -> when (args.size) {
        1 -> (0 until (args[0] as Number).toInt()).toList()
        2 -> ((args[0] as Number).toInt() until (args[1] as Number).toInt()).toList()
        3 -> {
          val a = (args[0] as Number).toInt()
          val b = (args[1] as Number).toInt()
          val step = (args[2] as Number).toInt()
          if (step > 0) (a until b step step).toList() else (a downTo b + 1 step -step).toList()
        }
        else -> emptyList<Int>()
      }
      "enumerate" -> (args[0] as Iterable<*>).mapIndexed { i, v -> listOf(i, v) }
      "sum" -> {
        val seq = args[0] as Iterable<*>
        if (seq.any { it is Double }) seq.sumOf { (it as Number).toDouble() } else seq.sumOf { (it as Number).toInt() }
      }
      "min" -> (args[0] as Iterable<*>).minWith { a, b -> cmp(a, b) }
      "max" -> (args[0] as Iterable<*>).maxWith { a, b -> cmp(a, b) }
      "sorted" -> (args[0] as Iterable<*>).sortedWith { a, b -> cmp(a, b) }
      "str" -> pyStr(args.firstOrNull())
      "int" -> (args[0] as? Number)?.toInt() ?: args[0].toString().toInt()
      "float" -> (args[0] as? Number)?.toDouble() ?: args[0].toString().toDouble()
      "abs" -> abs((args[0] as Number).toDouble()).let { if (args[0] is Int) it.toInt() else it }
      "round" -> round((args[0] as Number).toDouble()).toInt()
      else -> error("NameError: function '$name' is not defined")
    }

  @Suppress("UNCHECKED_CAST")
  private fun invokePyMethod(recv: Any?, m: String, args: List<Any?>): Any? {
    if (recv is String) {
      return when (m) {
        "upper" -> recv.uppercase()
        "lower" -> recv.lowercase()
        "strip" -> recv.trim()
        "split" -> if (args.isEmpty()) recv.trim().split(Regex("\\s+")) else recv.split(args[0].toString())
        "join" -> (args[0] as Iterable<*>).joinToString(recv) { pyStr(it) }
        "replace" -> recv.replace(args[0].toString(), args[1].toString())
        else -> error("AttributeError: str has no method '$m'")
      }
    }
    if (recv is MutableList<*>) {
      val list = recv as MutableList<Any?>
      return when (m) {
        "append" -> { list.add(args[0]); null }
        "pop" -> if (list.isEmpty()) null else list.removeAt(list.lastIndex)
        "sort" -> { list.sortWith { a, b -> cmp(a, b) }; null }
        else -> error("AttributeError: list has no method '$m'")
      }
    }
    error("AttributeError: '$m' not supported on $recv")
  }

  private fun formatFString(
    inner: String,
    scope: MutableMap<String, Any?>,
    funs: Map<String, PyFun>,
    emitLine: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") quote: Char,
    blockRunner: (List<PyLine>, MutableMap<String, Any?>) -> Any?,
  ): String {
    val sb = StringBuilder()
    var i = 0
    while (i < inner.length) {
      if (inner[i] == '{') {
        val end = inner.indexOf('}', i + 1)
        if (end > i) {
          val exprText = inner.substring(i + 1, end)
          sb.append(pyStr(evalExpr(exprText, scope, funs, emitLine, blockRunner)))
          i = end + 1
          continue
        }
      }
      sb.append(inner[i])
      i++
    }
    return sb.toString()
  }

  private fun pyStr(v: Any?): String =
    when (v) {
      null -> "None"
      true -> "True"
      false -> "False"
      else -> v.toString()
    }

  private fun isTruthy(v: Any?): Boolean =
    when (v) {
      null, false, 0, 0.0, "" -> false
      is Collection<*> -> v.isNotEmpty()
      is Map<*, *> -> v.isNotEmpty()
      else -> true
    }

  private fun applyBin(op: String, l: Any?, r: Any?): Any =
    when (op) {
      "+" -> if (l is String || r is String) "${pyStr(l)}${pyStr(r)}"
      else if (l is List<*> && r is List<*>) (l + r).toMutableList()
      else if (l is Double || r is Double) (l as Number).toDouble() + (r as Number).toDouble()
      else (l as Number).toInt() + (r as Number).toInt()
      "-" -> if (l is Double || r is Double) (l as Number).toDouble() - (r as Number).toDouble() else (l as Number).toInt() - (r as Number).toInt()
      "*" -> if (l is String && r is Number) l.repeat(r.toInt())
      else if (l is Double || r is Double) (l as Number).toDouble() * (r as Number).toDouble()
      else (l as Number).toInt() * (r as Number).toInt()
      "/" -> (l as Number).toDouble() / (r as Number).toDouble()
      "//" -> (l as Number).toInt() / (r as Number).toInt()
      "%" -> (l as Number).toInt() % (r as Number).toInt()
      else -> error("Unsupported op $op")
    }

  @Suppress("UNCHECKED_CAST")
  private fun cmp(a: Any?, b: Any?): Int =
    when {
      a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
      a is Comparable<*> && b != null -> (a as Comparable<Any>).compareTo(b)
      else -> 0
    }

  private fun preprocessLines(source: String): List<PyLine> {
    val out = ArrayList<PyLine>()
    source.split('\n').forEachIndexed { idx, raw ->
      val clean = stripPyComment(raw).trimEnd()
      if (clean.isBlank()) return@forEachIndexed
      val indent = clean.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
      out.add(PyLine(idx + 1, indent, clean.trim()))
    }
    return out
  }

  private fun stripPyComment(line: String): String {
    var inStr: Char? = null
    for (i in line.indices) {
      val c = line[i]
      if (inStr != null) {
        if (c == inStr && (i == 0 || line[i - 1] != '\\')) inStr = null
      } else {
        if (c == '"' || c == '\'') inStr = c
        else if (c == '#') return line.substring(0, i)
      }
    }
    return line
  }

  private fun collectChildBlock(lines: List<PyLine>, startIdx: Int, parentIndent: Int): List<PyLine> {
    val out = ArrayList<PyLine>()
    var j = startIdx
    while (j < lines.size && lines[j].indent > parentIndent) {
      out.add(lines[j])
      j++
    }
    return out
  }

  private fun findTopLevelAssign(code: String): Int {
    var depth = 0
    var inStr: Char? = null
    for (i in code.indices) {
      val c = code[i]
      if (inStr != null) { if (c == inStr) inStr = null; continue }
      if (c == '"' || c == '\'') { inStr = c; continue }
      if (c == '(' || c == '[' || c == '{') depth++
      else if (c == ')' || c == ']' || c == '}') depth--
      else if (c == '=' && depth == 0) {
        val prev = code.getOrNull(i - 1)
        val next = code.getOrNull(i + 1)
        if (prev != '=' && prev != '!' && prev != '<' && prev != '>' && next != '=') return i
      }
    }
    return -1
  }

  private fun splitTopLevel(s: String, delim: Char): List<String> {
    val res = ArrayList<String>()
    var depth = 0
    var inStr: Char? = null
    var start = 0
    for (i in s.indices) {
      val c = s[i]
      if (inStr != null) { if (c == inStr && s.getOrNull(i - 1) != '\\') inStr = null; continue }
      if (c == '"' || c == '\'') { inStr = c; continue }
      when (c) {
        '(', '[', '{' -> depth++
        ')', ']', '}' -> depth--
        delim -> if (depth == 0) { res.add(s.substring(start, i)); start = i + 1 }
      }
    }
    if (start <= s.length) res.add(s.substring(start))
    return res
  }

  private fun findTopLevelChar(s: String, target: Char): Int {
    var depth = 0
    var inStr: Char? = null
    for (i in s.indices) {
      val c = s[i]
      if (inStr != null) { if (c == inStr && s.getOrNull(i - 1) != '\\') inStr = null; continue }
      if (c == '"' || c == '\'') { inStr = c; continue }
      if (c == target && depth == 0) return i
      if (c == '(' || c == '[' || c == '{') depth++
      else if (c == ')' || c == ']' || c == '}') depth--
    }
    return -1
  }

  private fun findTopLevelKeyword(s: String, kw: String): Int {
    var depth = 0
    var inStr: Char? = null
    for (i in s.indices) {
      val c = s[i]
      if (inStr != null) { if (c == inStr) inStr = null; continue }
      if (c == '"' || c == '\'') { inStr = c; continue }
      if (c == '(' || c == '[' || c == '{') depth++
      else if (c == ')' || c == ']' || c == '}') depth--
      else if (depth == 0 && s.startsWith(kw, i)) return i
    }
    return -1
  }

  private fun findTopLevelOp(s: String, op: String): Int {
    var depth = 0
    var inStr: Char? = null
    for (i in s.indices) {
      val c = s[i]
      if (inStr != null) { if (c == inStr) inStr = null; continue }
      if (c == '"' || c == '\'') { inStr = c; continue }
      if (c == '(' || c == '[' || c == '{') depth++
      else if (c == ')' || c == ']' || c == '}') depth--
      else if (depth == 0 && s.startsWith(op, i)) return i
    }
    return -1
  }

  private fun findLastTopLevelOps(s: String, ops: List<String>): Int {
    var depth = 0
    var inStr: Char? = null
    var best = -1
    for (i in s.indices) {
      val c = s[i]
      if (inStr != null) { if (c == inStr) inStr = null; continue }
      if (c == '"' || c == '\'') { inStr = c; continue }
      if (c == '(' || c == '[' || c == '{') depth++
      else if (c == ')' || c == ']' || c == '}') depth--
      else if (depth == 0 && i > 0) {
        for (op in ops) {
          if (s.startsWith(op, i)) best = i
        }
      }
    }
    return best
  }

  private fun matchingParenEnd(s: String, start: Int): Int {
    var d = 0
    for (i in start until s.length) {
      if (s[i] == '(') d++ else if (s[i] == ')') { d--; if (d == 0) return i }
    }
    return -1
  }

  private fun matchingBracketEnd(s: String, start: Int): Int {
    var d = 0
    for (i in start until s.length) {
      if (s[i] == '[') d++ else if (s[i] == ']') { d--; if (d == 0) return i }
    }
    return -1
  }

  private fun matchingBraceEnd(s: String, start: Int): Int {
    var d = 0
    for (i in start until s.length) {
      if (s[i] == '{') d++ else if (s[i] == '}') { d--; if (d == 0) return i }
    }
    return -1
  }
}
