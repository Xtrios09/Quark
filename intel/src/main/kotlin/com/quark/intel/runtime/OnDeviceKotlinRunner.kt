package com.quark.intel.runtime

import kotlin.math.PI
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

data class ScriptExecutionOutput(
  val exitCode: Int,
  val stdoutLines: List<String>,
  val errorMessage: String? = null,
)

/**
 * 100% on-device pure-Kotlin evaluator executing Kotlin files and multi-file projects
 * without requiring an external `kotlinc` toolchain binary.
 */
object OnDeviceKotlinRunner {

  private const val MAX_EXEC_STEPS = 1_500_000

  private class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)
  private object BreakSignal : RuntimeException(null, null, false, false)
  private object ContinueSignal : RuntimeException(null, null, false, false)
  private class UserThrowSignal(val thrown: Any?) : RuntimeException(thrown?.toString() ?: "Exception")

  private class KtInstance(
    val klass: KtClassDecl,
    val fields: LinkedHashMap<String, Any?>,
    val methods: Map<String, KtFunDecl>,
  ) {
    override fun toString(): String {
      if (klass.isData) {
        val args = klass.primaryParams.joinToString(", ") { "${it.name}=${fields[it.name]}" }
        return "${klass.name}($args)"
      }
      return "${klass.name}@${System.identityHashCode(this).toString(16)}"
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is KtInstance || klass.name != other.klass.name) return false
      return if (klass.isData) fields == other.fields else false
    }

    override fun hashCode(): Int = if (klass.isData) fields.hashCode() else System.identityHashCode(this)
  }

  private class KtClosure(
    val params: List<String>,
    val body: List<KtStmt>,
    val env: Env,
  )

  private class Env(val parent: Env? = null) {
    val vars = LinkedHashMap<String, Any?>()
    val mutFlags = HashMap<String, Boolean>()
    val funs = HashMap<String, KtFunDecl>()
    val classes = HashMap<String, KtClassDecl>()

    fun defineVar(name: String, value: Any?, mutable: Boolean) {
      vars[name] = value
      mutFlags[name] = mutable
    }

    fun hasLocal(name: String): Boolean = vars.containsKey(name)

    fun getVar(name: String): Pair<Boolean, Any?> {
      var cur: Env? = this
      while (cur != null) {
        if (cur.vars.containsKey(name)) return true to cur.vars[name]
        cur = cur.parent
      }
      return false to null
    }

    fun assignVar(name: String, value: Any?): Boolean {
      var cur: Env? = this
      while (cur != null) {
        if (cur.vars.containsKey(name)) {
          if (cur.mutFlags[name] == false) {
            throw IllegalStateException("Val cannot be reassigned: $name")
          }
          cur.vars[name] = value
          return true
        }
        cur = cur.parent
      }
      return false
    }

    fun findFun(name: String): KtFunDecl? {
      var cur: Env? = this
      while (cur != null) {
        cur.funs[name]?.let { return it }
        cur = cur.parent
      }
      return null
    }

    fun findClass(name: String): KtClassDecl? {
      var cur: Env? = this
      while (cur != null) {
        cur.classes[name]?.let { return it }
        cur = cur.parent
      }
      return null
    }
  }

  fun run(
    entryFileName: String,
    entrySource: String,
    siblingSources: Map<String, String> = emptyMap(),
    onOutputLine: (String) -> Unit = {},
  ): ScriptExecutionOutput {
    val outputLines = ArrayList<String>()
    val lineBuf = StringBuilder()

    fun emitPrint(text: String, newline: Boolean) {
      lineBuf.append(text)
      if (newline) {
        val full = lineBuf.toString()
        lineBuf.clear()
        for (part in full.split('\n')) {
          outputLines.add(part)
          onOutputLine(part)
        }
      }
    }

    fun flushPartial() {
      if (lineBuf.isNotEmpty()) {
        val full = lineBuf.toString()
        lineBuf.clear()
        outputLines.add(full)
        onOutputLine(full)
      }
    }

    return try {
      val globalEnv = Env()
      val parsedUnits = ArrayList<KtProgramUnit>()

      for ((sibName, sibCode) in siblingSources) {
        val sibTokens = KtLexer(sibName, sibCode).tokenize()
        parsedUnits.add(KtParser(sibName, sibTokens).parseUnit())
      }
      val entryTokens = KtLexer(entryFileName, entrySource).tokenize()
      val entryUnit = KtParser(entryFileName, entryTokens).parseUnit()
      parsedUnits.add(entryUnit)

      // Register all classes & functions first
      for (unit in parsedUnits) {
        for (decl in unit.declarations) {
          when (decl) {
            is KtFunDecl -> globalEnv.funs[decl.name] = decl
            is KtClassDecl -> {
              globalEnv.classes[decl.name] = decl
              if (decl.isObject) {
                val fields = LinkedHashMap<String, Any?>()
                val interp = Interpreter(globalEnv, ::emitPrint)
                for (p in decl.properties) {
                  fields[p.name] = p.initExpr?.let { interp.evalExpr(it, globalEnv) }
                }
                globalEnv.defineVar(decl.name, KtInstance(decl, fields, decl.methods.associateBy { it.name }), mutable = false)
              }
            }
            is KtVarDecl -> {}
          }
        }
      }

      val interp = Interpreter(globalEnv, ::emitPrint)

      // Initialize top-level statements in sibling units first
      for (unit in parsedUnits) {
        if (unit !== entryUnit) {
          interp.execStatements(unit.topLevelStatements, globalEnv)
        }
      }

      val mainFun = entryUnit.declarations.filterIsInstance<KtFunDecl>().firstOrNull { it.name == "main" }
        ?: globalEnv.findFun("main")

      // Execute top-level val/var initializers + scripts in entry unit
      interp.execStatements(entryUnit.topLevelStatements, globalEnv)

      if (mainFun != null) {
        val mainArgs = if (mainFun.params.isNotEmpty()) listOf(emptyList<String>()) else emptyList()
        interp.invokeFun(mainFun, mainArgs, thisObj = null, callerLine = 1, callerCol = 1)
      }

      flushPartial()
      ScriptExecutionOutput(exitCode = 0, stdoutLines = outputLines)
    } catch (ce: KtCompileException) {
      flushPartial()
      outputLines.add(ce.message)
      onOutputLine(ce.message)
      ScriptExecutionOutput(exitCode = 1, stdoutLines = outputLines, errorMessage = ce.message)
    } catch (uts: UserThrowSignal) {
      flushPartial()
      val msg = "$entryFileName: runtime exception: ${uts.thrown}"
      outputLines.add(msg)
      onOutputLine(msg)
      ScriptExecutionOutput(exitCode = 1, stdoutLines = outputLines, errorMessage = msg)
    } catch (t: Throwable) {
      flushPartial()
      val msg = "$entryFileName: runtime error: ${t.message ?: t::class.simpleName}"
      outputLines.add(msg)
      onOutputLine(msg)
      ScriptExecutionOutput(exitCode = 1, stdoutLines = outputLines, errorMessage = msg)
    }
  }

  private class Interpreter(
    private val globalEnv: Env,
    private val emit: (String, Boolean) -> Unit,
  ) {
    private var steps = 0

    private fun tick() {
      if (++steps > MAX_EXEC_STEPS) {
        throw IllegalStateException("Execution step limit ($MAX_EXEC_STEPS) exceeded — possible infinite loop")
      }
    }

    fun execStatements(stmts: List<KtStmt>, env: Env): Any? {
      var last: Any? = Unit
      for (stmt in stmts) {
        last = execStmt(stmt, env)
      }
      return last
    }

    private fun execStmt(stmt: KtStmt, env: Env): Any? {
      tick()
      return when (stmt) {
        is KtVarStmt -> {
          val v = stmt.decl.initExpr?.let { evalExpr(it, env) }
          env.defineVar(stmt.decl.name, v, stmt.decl.isMutable)
          Unit
        }
        is KtLocalFunStmt -> {
          env.funs[stmt.decl.name] = stmt.decl
          Unit
        }
        is KtExprStmt -> evalExpr(stmt.expr, env)
        is KtAssignStmt -> execAssign(stmt, env)
        is KtReturnStmt -> throw ReturnSignal(stmt.expr?.let { evalExpr(it, env) } ?: Unit)
        KtBreakStmt -> throw BreakSignal
        KtContinueStmt -> throw ContinueSignal
        is KtThrowStmt -> throw UserThrowSignal(evalExpr(stmt.expr, env))
        is KtForStmt -> {
          val iterVal = evalExpr(stmt.iterable, env)
          val seq: Iterable<Any?> =
            when (iterVal) {
              is Iterable<*> -> iterVal
              is CharSequence -> iterVal.toList()
              is Array<*> -> iterVal.toList()
              else -> throw IllegalStateException("Cannot iterate over $iterVal")
            }
          for (elem in seq) {
            tick()
            val loopEnv = Env(env)
            loopEnv.defineVar(stmt.varName, elem, mutable = false)
            try {
              execStatements(stmt.body, loopEnv)
            } catch (_: ContinueSignal) {
              continue
            } catch (_: BreakSignal) {
              break
            }
          }
          Unit
        }
        is KtWhileStmt -> {
          if (stmt.isDoWhile) {
            do {
              tick()
              val loopEnv = Env(env)
              try {
                execStatements(stmt.body, loopEnv)
              } catch (_: ContinueSignal) {
                // fall through to condition check
              } catch (_: BreakSignal) {
                break
              }
            } while (evalExpr(stmt.cond, env) == true)
          } else {
            while (evalExpr(stmt.cond, env) == true) {
              tick()
              val loopEnv = Env(env)
              try {
                execStatements(stmt.body, loopEnv)
              } catch (_: ContinueSignal) {
                continue
              } catch (_: BreakSignal) {
                break
              }
            }
          }
          Unit
        }
        is KtTryStmt -> {
          try {
            execStatements(stmt.tryBody, Env(env))
          } catch (r: ReturnSignal) {
            throw r
          } catch (b: BreakSignal) {
            throw b
          } catch (c: ContinueSignal) {
            throw c
          } catch (t: Throwable) {
            val cb = stmt.catchBody
            if (cb != null) {
              val cEnv = Env(env)
              val thrownObj = if (t is UserThrowSignal) t.thrown else t
              if (stmt.catchVar != null) cEnv.defineVar(stmt.catchVar, thrownObj, mutable = false)
              execStatements(cb, cEnv)
            } else {
              throw t
            }
          } finally {
            stmt.finallyBody?.let { execStatements(it, Env(env)) }
          }
        }
      }
    }

    private fun execAssign(stmt: KtAssignStmt, env: Env): Any? {
      val rhs = evalExpr(stmt.value, env)
      when (val t = stmt.target) {
        is KtIdent -> {
          val (found, curVal) = env.getVar(t.name)
          if (!found) throw IllegalStateException("Unresolved reference: ${t.name} at line ${stmt.line}")
          val next = computeCompound(stmt.op, curVal, rhs)
          if (!env.assignVar(t.name, next)) throw IllegalStateException("Cannot assign '${t.name}'")
        }
        is KtMemberExpr -> {
          val recv = evalExpr(t.receiver, env)
          if (recv is KtInstance) {
            val curVal = recv.fields[t.member]
            recv.fields[t.member] = computeCompound(stmt.op, curVal, rhs)
          } else {
            throw IllegalStateException("Cannot assign property '${t.member}' on $recv")
          }
        }
        is KtIndexExpr -> {
          val recv = evalExpr(t.receiver, env)
          val key = evalExpr(t.index, env)
          @Suppress("UNCHECKED_CAST")
          when (recv) {
            is MutableList<*> -> {
              val idx = (key as Number).toInt()
              val list = recv as MutableList<Any?>
              list[idx] = computeCompound(stmt.op, list[idx], rhs)
            }
            is MutableMap<*, *> -> {
              val map = recv as MutableMap<Any?, Any?>
              map[key] = computeCompound(stmt.op, map[key], rhs)
            }
            else -> throw IllegalStateException("Indexed assignment not supported on $recv")
          }
        }
        else -> throw IllegalStateException("Invalid assignment target")
      }
      return Unit
    }

    private fun computeCompound(op: KtTokKind, cur: Any?, rhs: Any?): Any? =
      when (op) {
        KtTokKind.EQ -> rhs
        KtTokKind.PLUS_EQ -> evalAdd(cur, rhs)
        KtTokKind.MINUS_EQ -> evalSub(cur, rhs)
        KtTokKind.STAR_EQ -> evalMul(cur, rhs)
        KtTokKind.SLASH_EQ -> evalDiv(cur, rhs)
        else -> rhs
      }

    fun evalExpr(expr: KtExpr, env: Env): Any? {
      tick()
      return when (expr) {
        is KtLiteral -> expr.value
        KtThisExpr -> {
          val (ok, v) = env.getVar("this")
          if (ok) v else throw IllegalStateException("'this' is not defined in current scope")
        }
        is KtIdent -> {
          when (expr.name) {
            "PI" -> PI
            "E" -> E
            else -> {
              val (ok, v) = env.getVar(expr.name)
              if (ok) v else throw IllegalStateException("Unresolved reference: ${expr.name} (line ${expr.line})")
            }
          }
        }
        is KtStringTemplate -> evaluateTemplate(expr.raw, env)
        is KtLambdaExpr -> KtClosure(expr.params, expr.body, env)
        is KtBlockExpr -> execStatements(expr.body, Env(env))
        is KtUnaryExpr -> {
          when (expr.op) {
            KtTokKind.BANG -> !(evalExpr(expr.target, env) as Boolean)
            KtTokKind.MINUS -> negateNum(evalExpr(expr.target, env))
            KtTokKind.PLUS_PLUS, KtTokKind.MINUS_MINUS -> {
              val delta = if (expr.op == KtTokKind.PLUS_PLUS) 1 else -1
              val ident = expr.target as? KtIdent ?: throw IllegalStateException("++/-- requires variable")
              val (_, oldVal) = env.getVar(ident.name)
              val next = evalAdd(oldVal, delta)
              env.assignVar(ident.name, next)
              if (expr.isPrefix) next else oldVal
            }
            else -> evalExpr(expr.target, env)
          }
        }
        is KtBinaryExpr -> evalBinary(expr, env)
        is KtRangeExpr -> {
          val a = (evalExpr(expr.left, env) as Number).toInt()
          val b = (evalExpr(expr.right, env) as Number).toInt()
          val step = expr.stepExpr?.let { (evalExpr(it, env) as Number).toInt() } ?: 1
          when {
            expr.down -> (a downTo b step step).toList()
            expr.inclusive -> (a..b step step).toList()
            else -> (a until b step step).toList()
          }
        }
        is KtIfExpr -> {
          if (evalExpr(expr.cond, env) == true) execStatements(expr.thenBody, Env(env))
          else expr.elseBody?.let { execStatements(it, Env(env)) } ?: Unit
        }
        is KtWhenExpr -> evalWhen(expr, env)
        is KtMemberExpr -> evalMemberAccess(expr, env)
        is KtIndexExpr -> evalIndex(evalExpr(expr.receiver, env), evalExpr(expr.index, env))
        is KtCallExpr -> evalCall(expr, env)
      }
    }

    private fun evalWhen(w: KtWhenExpr, env: Env): Any? {
      val subj = w.subject?.let { evalExpr(it, env) }
      for (b in w.branches) {
        if (b.isElse) return execStatements(b.body, Env(env))
        val matched =
          if (w.subject != null) {
            b.conditions.any { cond ->
              val cv = evalExpr(cond, env)
              if (cv is Iterable<*> && subj !is Iterable<*>) subj in cv else cv == subj
            }
          } else {
            b.conditions.any { evalExpr(it, env) == true }
          }
        if (matched) return execStatements(b.body, Env(env))
      }
      return Unit
    }

    private fun evalBinary(b: KtBinaryExpr, env: Env): Any? {
      if (b.op == "&&") return (evalExpr(b.left, env) == true) && (evalExpr(b.right, env) == true)
      if (b.op == "||") return (evalExpr(b.left, env) == true) || (evalExpr(b.right, env) == true)
      if (b.op == "?:") return evalExpr(b.left, env) ?: evalExpr(b.right, env)

      val l = evalExpr(b.left, env)
      val r = evalExpr(b.right, env)
      return when (b.op) {
        "+" -> evalAdd(l, r)
        "-" -> evalSub(l, r)
        "*" -> evalMul(l, r)
        "/" -> evalDiv(l, r)
        "%" -> evalMod(l, r)
        "==" -> l == r
        "!=" -> l != r
        "<" -> compareVals(l, r) < 0
        "<=" -> compareVals(l, r) <= 0
        ">" -> compareVals(l, r) > 0
        ">=" -> compareVals(l, r) >= 0
        "to" -> Pair(l, r)
        "in" -> when (r) {
          is Iterable<*> -> l in r
          is Map<*, *> -> l in r
          is CharSequence -> l?.toString()?.let { r.contains(it) } ?: false
          else -> false
        }
        "is" -> checkIsType(l, r as String)
        "as" -> l
        else -> throw IllegalStateException("Unsupported operator '${b.op}'")
      }
    }

    private fun checkIsType(v: Any?, typeName: String): Boolean =
      when (typeName) {
        "String" -> v is String
        "Int" -> v is Int
        "Long" -> v is Long
        "Double", "Float" -> v is Double || v is Float
        "Boolean" -> v is Boolean
        "List", "MutableList" -> v is List<*>
        "Map", "MutableMap" -> v is Map<*, *>
        else -> v is KtInstance && v.klass.name == typeName
      }

    private fun evalMemberAccess(expr: KtMemberExpr, env: Env): Any? {
      val recv = evalExpr(expr.receiver, env)
      if (recv == null) {
        if (expr.safeCall) return null
        throw NullPointerException("Attempted to access '.${expr.member}' on null")
      }
      return when {
        recv is KtInstance -> recv.fields[expr.member]
          ?: throw IllegalStateException("Property '${expr.member}' not found on ${recv.klass.name}")
        recv is CharSequence && expr.member == "length" -> recv.length
        recv is Collection<*> && expr.member == "size" -> recv.size
        recv is Map<*, *> && expr.member == "size" -> recv.size
        recv is Map<*, *> && expr.member == "keys" -> recv.keys
        recv is Map<*, *> && expr.member == "values" -> recv.values
        recv is Map<*, *> && expr.member == "entries" -> recv.entries
        recv is Pair<*, *> && expr.member == "first" -> recv.first
        recv is Pair<*, *> && expr.member == "second" -> recv.second
        else -> throw IllegalStateException("Unknown property '.${expr.member}' on $recv")
      }
    }

    private fun evalIndex(recv: Any?, index: Any?): Any? =
      when (recv) {
        is List<*> -> recv[(index as Number).toInt()]
        is Map<*, *> -> recv[index]
        is CharSequence -> recv[(index as Number).toInt()]
        else -> throw IllegalStateException("Cannot index into $recv")
      }

    private fun evalCall(call: KtCallExpr, env: Env): Any? {
      val argValues = call.args.map { evalExpr(it.second, env) }.toMutableList()
      if (call.trailingLambda != null) {
        argValues.add(KtClosure(call.trailingLambda.params, call.trailingLambda.body, env))
      }

      when (val callee = call.callee) {
        is KtIdent -> {
          val fnName = callee.name
          // 1. User-defined function
          env.findFun(fnName)?.let { funDecl ->
            return invokeFun(funDecl, argValues, thisObj = null, call.line, call.col)
          }
          // 2. Class instantiation
          env.findClass(fnName)?.let { classDecl ->
            return instantiateClass(classDecl, argValues, env)
          }
          // 3. Built-in global functions
          return invokeBuiltinGlobal(fnName, argValues)
        }
        is KtMemberExpr -> {
          val recv = evalExpr(callee.receiver, env)
          if (recv == null) {
            if (callee.safeCall) return null
            throw NullPointerException("Called '.${callee.member}()' on null")
          }
          if (recv is KtInstance) {
            val method = recv.methods[callee.member]
            if (method != null) {
              return invokeFun(method, argValues, thisObj = recv, call.line, call.col)
            }
          }
          return invokeMemberMethod(recv, callee.member, argValues)
        }
        else -> {
          val target = evalExpr(callee, env)
          if (target is KtClosure) {
            return invokeClosure(target, argValues)
          }
          throw IllegalStateException("Cannot invoke $target")
        }
      }
    }

    fun invokeFun(decl: KtFunDecl, args: List<Any?>, thisObj: KtInstance?, callerLine: Int, callerCol: Int): Any? {
      val fnEnv = Env(globalEnv)
      if (thisObj != null) {
        fnEnv.defineVar("this", thisObj, mutable = false)
        for ((k, v) in thisObj.fields) {
          fnEnv.defineVar(k, v, mutable = true)
        }
      }
      for (i in decl.params.indices) {
        val p = decl.params[i]
        val valArg =
          if (i < args.size) args[i]
          else p.defaultExpr?.let { evalExpr(it, fnEnv) }
            ?: throw IllegalStateException("Missing argument '${p.name}' in call to ${decl.name}() (line $callerLine)")
        fnEnv.defineVar(p.name, valArg, mutable = true)
      }
      val res = try {
        if (decl.singleExpr != null) evalExpr(decl.singleExpr, fnEnv)
        else execStatements(decl.body, fnEnv)
      } catch (r: ReturnSignal) {
        r.value
      }
      if (thisObj != null) {
        for (k in thisObj.fields.keys.toList()) {
          if (fnEnv.hasLocal(k)) thisObj.fields[k] = fnEnv.vars[k]
        }
      }
      return res
    }

    private fun instantiateClass(decl: KtClassDecl, args: List<Any?>, env: Env): KtInstance {
      val fields = LinkedHashMap<String, Any?>()
      val initEnv = Env(env)
      for (i in decl.primaryParams.indices) {
        val p = decl.primaryParams[i]
        val v = if (i < args.size) args[i] else p.defaultExpr?.let { evalExpr(it, initEnv) }
        fields[p.name] = v
        initEnv.defineVar(p.name, v, mutable = true)
      }
      for (prop in decl.properties) {
        val v = prop.initExpr?.let { evalExpr(it, initEnv) }
        fields[prop.name] = v
        initEnv.defineVar(prop.name, v, mutable = prop.isMutable)
      }
      return KtInstance(decl, fields, decl.methods.associateBy { it.name })
    }

    private fun invokeClosure(closure: KtClosure, args: List<Any?>): Any? {
      val cEnv = Env(closure.env)
      if (closure.params.isEmpty() && args.isNotEmpty()) {
        cEnv.defineVar("it", args[0], mutable = false)
      } else {
        for (i in closure.params.indices) {
          cEnv.defineVar(closure.params[i], args.getOrNull(i), mutable = false)
        }
      }
      return try {
        execStatements(closure.body, cEnv)
      } catch (r: ReturnSignal) {
        r.value
      }
    }

    private fun invokeBuiltinGlobal(name: String, args: List<Any?>): Any? =
      when (name) {
        "println" -> {
          emit(args.firstOrNull()?.toString() ?: if (args.isEmpty()) "" else "null", true)
          Unit
        }
        "print" -> {
          emit(args.firstOrNull()?.toString() ?: "", false)
          Unit
        }
        "listOf" -> args.toList()
        "mutableListOf", "arrayListOf", "ArrayList" -> args.toMutableList()
        "emptyList" -> emptyList<Any?>()
        "setOf" -> args.toSet()
        "mutableSetOf" -> args.toMutableSet()
        "mapOf" -> {
          val m = LinkedHashMap<Any?, Any?>()
          for (a in args) {
            if (a is Pair<*, *>) m[a.first] = a.second
          }
          m
        }
        "mutableMapOf", "HashMap" -> {
          val m = LinkedHashMap<Any?, Any?>()
          for (a in args) {
            if (a is Pair<*, *>) m[a.first] = a.second
          }
          m
        }
        "arrayOf" -> args.toMutableList()
        "Pair" -> Pair(args.getOrNull(0), args.getOrNull(1))
        "StringBuilder" -> StringBuilder(args.firstOrNull()?.toString() ?: "")
        "abs" -> when (val x = args[0]) {
          is Int -> abs(x)
          is Long -> abs(x)
          else -> abs((x as Number).toDouble())
        }
        "max" -> if (args[0] is Double || args[1] is Double) max((args[0] as Number).toDouble(), (args[1] as Number).toDouble()) else max((args[0] as Number).toInt(), (args[1] as Number).toInt())
        "min" -> if (args[0] is Double || args[1] is Double) min((args[0] as Number).toDouble(), (args[1] as Number).toDouble()) else min((args[0] as Number).toInt(), (args[1] as Number).toInt())
        "sqrt" -> sqrt((args[0] as Number).toDouble())
        "pow" -> (args[0] as Number).toDouble().pow((args[1] as Number).toDouble())
        "round" -> round((args[0] as Number).toDouble())
        "floor" -> floor((args[0] as Number).toDouble())
        "ceil" -> ceil((args[0] as Number).toDouble())
        "repeat" -> {
          val times = (args[0] as Number).toInt()
          val fn = args[1] as KtClosure
          for (i in 0 until times) invokeClosure(fn, listOf(i))
          Unit
        }
        "require" -> {
          if (args[0] != true) throw IllegalArgumentException(args.getOrNull(1)?.toString() ?: "Requirement failed")
          Unit
        }
        "check" -> {
          if (args[0] != true) throw IllegalStateException(args.getOrNull(1)?.toString() ?: "Check failed")
          Unit
        }
        else -> throw IllegalStateException("Unresolved function: $name()")
      }

    @Suppress("UNCHECKED_CAST")
    private fun invokeMemberMethod(recv: Any, method: String, args: List<Any?>): Any? {
      if (method == "toString") return recv.toString()
      if (method == "let") return invokeClosure(args[0] as KtClosure, listOf(recv))
      if (method == "also") { invokeClosure(args[0] as KtClosure, listOf(recv)); return recv }
      if (method == "toInt") return when (recv) { is Number -> recv.toInt(); else -> recv.toString().toInt() }
      if (method == "toDouble") return when (recv) { is Number -> recv.toDouble(); else -> recv.toString().toDouble() }
      if (method == "toLong") return when (recv) { is Number -> recv.toLong(); else -> recv.toString().toLong() }

      if (recv is StringBuilder) {
        return when (method) {
          "append" -> recv.append(args.firstOrNull())
          "clear" -> recv.clear()
          else -> throw IllegalStateException("Unknown StringBuilder.$method")
        }
      }

      if (recv is String) {
        return when (method) {
          "uppercase" -> recv.uppercase()
          "lowercase" -> recv.lowercase()
          "trim" -> recv.trim()
          "split" -> recv.split(args[0].toString())
          "replace" -> recv.replace(args[0].toString(), args[1].toString())
          "startsWith" -> recv.startsWith(args[0].toString())
          "endsWith" -> recv.endsWith(args[0].toString())
          "contains" -> recv.contains(args[0].toString())
          "substring" -> if (args.size >= 2) recv.substring((args[0] as Number).toInt(), (args[1] as Number).toInt()) else recv.substring((args[0] as Number).toInt())
          "isEmpty" -> recv.isEmpty()
          "isNotEmpty" -> recv.isNotEmpty()
          "reversed" -> recv.reversed()
          "take" -> recv.take((args[0] as Number).toInt())
          "drop" -> recv.drop((args[0] as Number).toInt())
          else -> throw IllegalStateException("Unknown String.$method()")
        }
      }

      if (recv is Iterable<*>) {
        return when (method) {
          "forEach" -> {
            val fn = args[0] as KtClosure
            for (x in recv) invokeClosure(fn, listOf(x))
            Unit
          }
          "forEachIndexed" -> {
            val fn = args[0] as KtClosure
            recv.forEachIndexed { idx, x -> invokeClosure(fn, listOf(idx, x)) }
            Unit
          }
          "map" -> {
            val fn = args[0] as KtClosure
            recv.map { invokeClosure(fn, listOf(it)) }
          }
          "mapIndexed" -> {
            val fn = args[0] as KtClosure
            recv.mapIndexed { idx, x -> invokeClosure(fn, listOf(idx, x)) }
          }
          "filter" -> {
            val fn = args[0] as KtClosure
            recv.filter { invokeClosure(fn, listOf(it)) == true }
          }
          "any" -> {
            val fn = args.firstOrNull() as? KtClosure
            if (fn == null) recv.any() else recv.any { invokeClosure(fn, listOf(it)) == true }
          }
          "all" -> {
            val fn = args[0] as KtClosure
            recv.all { invokeClosure(fn, listOf(it)) == true }
          }
          "count" -> {
            val fn = args.firstOrNull() as? KtClosure
            if (fn == null) recv.count() else recv.count { invokeClosure(fn, listOf(it)) == true }
          }
          "find", "firstOrNull" -> {
            val fn = args.firstOrNull() as? KtClosure
            if (fn == null) recv.firstOrNull() else recv.firstOrNull { invokeClosure(fn, listOf(it)) == true }
          }
          "first" -> recv.first()
          "last" -> recv.last()
          "sum" -> if (recv.any { it is Double }) recv.sumOf { (it as Number).toDouble() } else recv.sumOf { (it as Number).toInt() }
          "average" -> recv.map { (it as Number).toDouble() }.average()
          "sorted" -> recv.sortedWith { a, b -> compareVals(a, b) }
          "sortedDescending" -> recv.sortedWith { a, b -> compareVals(b, a) }
          "reversed" -> recv.reversed()
          "joinToString" -> recv.joinToString(args.firstOrNull()?.toString() ?: ", ")
          "contains" -> recv.contains(args[0])
          "isEmpty" -> !recv.iterator().hasNext()
          "isNotEmpty" -> recv.iterator().hasNext()
          "take" -> recv.take((args[0] as Number).toInt())
          "drop" -> recv.drop((args[0] as Number).toInt())
          "toList" -> recv.toList()
          "toMutableList" -> recv.toMutableList()
          "add" -> (recv as MutableCollection<Any?>).add(args[0])
          "remove" -> (recv as MutableCollection<Any?>).remove(args[0])
          "clear" -> { (recv as MutableCollection<Any?>).clear(); Unit }
          else -> throw IllegalStateException("Unknown collection method .$method()")
        }
      }

      if (recv is MutableMap<*, *>) {
        val m = recv as MutableMap<Any?, Any?>
        return when (method) {
          "get" -> m[args[0]]
          "put" -> m.put(args[0], args[1])
          "containsKey" -> m.containsKey(args[0])
          "remove" -> m.remove(args[0])
          "isEmpty" -> m.isEmpty()
          "isNotEmpty" -> m.isNotEmpty()
          else -> throw IllegalStateException("Unknown Map.$method()")
        }
      }

      if (recv is Number && method == "toDouble") return recv.toDouble()
      if (recv is Number && method == "toInt") return recv.toInt()
      throw IllegalStateException("Unknown method '$method()' on $recv")
    }

    private fun evaluateTemplate(raw: String, env: Env): String {
      if (!raw.contains('$')) return raw
      val sb = StringBuilder(raw.length + 16)
      var i = 0
      while (i < raw.length) {
        val c = raw[i]
        if (c == '$') {
          if (i + 1 < raw.length && raw[i + 1] == '{') {
            var depth = 1
            var j = i + 2
            while (j < raw.length && depth > 0) {
              if (raw[j] == '{') depth++
              else if (raw[j] == '}') depth--
              j++
            }
            val exprText = raw.substring(i + 2, (j - 1).coerceAtLeast(i + 2))
            val tokens = KtLexer("<template>", exprText).tokenize()
            val expr = KtParser("<template>", tokens).parseExpr()
            sb.append(evalExpr(expr, env)?.toString() ?: "null")
            i = j
          } else if (i + 1 < raw.length && (raw[i + 1].isLetter() || raw[i + 1] == '_')) {
            var j = i + 1
            while (j < raw.length && (raw[j].isLetterOrDigit() || raw[j] == '_')) j++
            val name = raw.substring(i + 1, j)
            val (ok, v) = env.getVar(name)
            if (!ok) throw IllegalStateException("Unresolved variable \$$name in string template")
            sb.append(v?.toString() ?: "null")
            i = j
          } else {
            sb.append('$')
            i++
          }
        } else {
          sb.append(c)
          i++
        }
      }
      return sb.toString()
    }

    private fun evalAdd(a: Any?, b: Any?): Any? {
      if (a is String || b is String) return "${a ?: "null"}${b ?: "null"}"
      if (a is Double || b is Double) return (a as Number).toDouble() + (b as Number).toDouble()
      if (a is Long || b is Long) return (a as Number).toLong() + (b as Number).toLong()
      return (a as Number).toInt() + (b as Number).toInt()
    }

    private fun evalSub(a: Any?, b: Any?): Any =
      when {
        a is Double || b is Double -> (a as Number).toDouble() - (b as Number).toDouble()
        a is Long || b is Long -> (a as Number).toLong() - (b as Number).toLong()
        else -> (a as Number).toInt() - (b as Number).toInt()
      }

    private fun evalMul(a: Any?, b: Any?): Any =
      when {
        a is Double || b is Double -> (a as Number).toDouble() * (b as Number).toDouble()
        a is Long || b is Long -> (a as Number).toLong() * (b as Number).toLong()
        else -> (a as Number).toInt() * (b as Number).toInt()
      }

    private fun evalDiv(a: Any?, b: Any?): Any =
      when {
        a is Double || b is Double -> (a as Number).toDouble() / (b as Number).toDouble()
        a is Long || b is Long -> (a as Number).toLong() / (b as Number).toLong()
        else -> (a as Number).toInt() / (b as Number).toInt()
      }

    private fun evalMod(a: Any?, b: Any?): Any =
      when {
        a is Double || b is Double -> (a as Number).toDouble() % (b as Number).toDouble()
        a is Long || b is Long -> (a as Number).toLong() % (b as Number).toLong()
        else -> (a as Number).toInt() % (b as Number).toInt()
      }

    private fun negateNum(v: Any?): Any =
      when (v) {
        is Double -> -v
        is Long -> -v
        is Int -> -v
        else -> throw IllegalStateException("Cannot negate $v")
      }

    @Suppress("UNCHECKED_CAST")
    private fun compareVals(a: Any?, b: Any?): Int {
      if (a is Number && b is Number) {
        return a.toDouble().compareTo(b.toDouble())
      }
      if (a is Comparable<*> && b != null) {
        return (a as Comparable<Any>).compareTo(b)
      }
      return 0
    }
  }
}
