package com.quark.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Interactive terminal session pairing a VT100 [TerminalBuffer] + [AnsiParser] with:
 * - Line editing, command history (Up/Down), Tab filename/command completion, Ctrl+C/Ctrl+L
 * - Stateful cwd, env vars, shell redirection (`>`, `>>`), `&&` / `;` chaining
 * - Built-in POSIX utilities & Pure-Kotlin `git` CLI
 * - Real `/system/bin/sh` subprocess streaming for external commands & scripts
 */
class TerminalSession(
  val id: String,
  initialWorkDir: File,
  private val homeDir: File,
  private val binDirs: List<File>,
  private val authorNameProvider: () -> String = { "Quark User" },
  private val authorEmailProvider: () -> String = { "user@quark.local" },
  private val onWorkspaceMutated: () -> Unit = {},
) {

  val buffer = TerminalBuffer(initialCols = 96, initialRows = 28)
  private val parser = AnsiParser(buffer)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _isRunningCommand = MutableStateFlow(false)
  val isRunningCommand: StateFlow<Boolean> = _isRunningCommand.asStateFlow()

  private val _cwdFlow = MutableStateFlow(initialWorkDir)
  val cwdFlow: StateFlow<File> = _cwdFlow.asStateFlow()

  var onDirty: (() -> Unit)? = null

  private val envVars = LinkedHashMap<String, String>().apply {
    put("HOME", homeDir.absolutePath)
    put("TERM", "xterm-256color")
    put("USER", "quark")
    put("SHELL", "/system/bin/sh")
    put("PWD", initialWorkDir.absolutePath)
    val pathPrefix = binDirs.joinToString(":") { it.absolutePath }
    put("PATH", "$pathPrefix:/system/bin:/system/xbin")
  }

  private val history = ArrayList<String>()
  private var historyIdx = -1
  private var savedDraft = ""

  private val lineBuf = StringBuilder()
  private var caretInLine = 0

  @Volatile private var activeProcess: Process? = null
  private var activeJob: Job? = null
  private val dirtyScheduled = AtomicBoolean(false)

  init {
    writeRaw(
      "\u001B[1;36mQuark Terminal\u001B[0m (\u001B[32mxterm-256color\u001B[0m)  Type '\u001B[33mhelp\u001B[0m' or '\u001B[33mgit status\u001B[0m'\r\n"
    )
    printPrompt()
  }

  fun setWorkingDirectory(dir: File) {
    if (dir.isDirectory) {
      _cwdFlow.value = dir
      envVars["PWD"] = dir.absolutePath
      if (!_isRunningCommand.value) {
        writeRaw("\r\n")
        printPrompt()
      }
    }
  }

  fun resize(cols: Int, rows: Int) {
    buffer.resize(cols, rows)
    requestRepaintImmediate()
  }

  fun clearScreen() {
    writeRaw("\u001B[2J\u001B[H")
    if (!_isRunningCommand.value) {
      printPrompt()
      if (lineBuf.isNotEmpty()) {
        writeRaw(lineBuf.toString())
      }
    }
  }

  fun interrupt() {
    val proc = activeProcess
    if (proc != null) {
      runCatching { proc.destroyForcibly() }
      activeProcess = null
      activeJob?.cancel()
      _isRunningCommand.value = false
      writeRaw("^C\r\n")
      printPrompt()
    } else {
      lineBuf.clear()
      caretInLine = 0
      historyIdx = -1
      writeRaw("^C\r\n")
      printPrompt()
    }
  }

  fun sendInputText(text: String) {
    if (_isRunningCommand.value) {
      val proc = activeProcess
      if (proc != null) {
        scope.launch {
          runCatching {
            proc.outputStream.write(text.toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
          }
        }
      }
      return
    }
    for (ch in text) {
      when (ch) {
        '\n', '\r' -> submitLine()
        '\b', '\u007F' -> backspaceChar()
        '\t' -> tabComplete()
        else -> if (!ch.isISOControl()) insertChar(ch)
      }
    }
  }

  fun handleSpecialKey(key: String) {
    when (key) {
      "ENTER" -> {
        if (_isRunningCommand.value) sendInputText("\n") else submitLine()
      }
      "BACKSPACE" -> {
        if (_isRunningCommand.value) sendInputText("\b") else backspaceChar()
      }
      "TAB" -> {
        if (_isRunningCommand.value) sendInputText("\t") else tabComplete()
      }
      "UP" -> navigateHistory(-1)
      "DOWN" -> navigateHistory(1)
      "LEFT" -> moveCaret(-1)
      "RIGHT" -> moveCaret(1)
      "HOME" -> {
        if (caretInLine > 0) moveCaret(-caretInLine)
      }
      "END" -> {
        if (caretInLine < lineBuf.length) moveCaret(lineBuf.length - caretInLine)
      }
      "CTRL_C" -> interrupt()
      "CTRL_L" -> clearScreen()
      "CTRL_D" -> {
        if (_isRunningCommand.value) {
          runCatching { activeProcess?.outputStream?.close() }
        } else if (lineBuf.isEmpty()) {
          writeRaw("\r\n(exit ignored in main tab; use Ctrl+C to cancel commands)\r\n")
          printPrompt()
        }
      }
    }
  }

  fun runExternalCommand(cmd: String) {
    if (_isRunningCommand.value) interrupt()
    if (!_isRunningCommand.value) {
      lineBuf.clear()
      lineBuf.append(cmd)
      caretInLine = lineBuf.length
      writeRaw(cmd)
      submitLine()
    }
  }

  fun dispose() {
    runCatching { activeProcess?.destroyForcibly() }
    scope.cancel()
  }

  // --- Prompt & Line Editor ---

  private fun promptPrefix(): String {
    val cwd = _cwdFlow.value
    val name = if (cwd.absolutePath == homeDir.absolutePath) "~" else cwd.name.ifEmpty { "/" }
    return "\u001B[1;36mquark\u001B[0m:\u001B[1;34m$name\u001B[0m$ "
  }

  private fun printPrompt() {
    writeRaw(promptPrefix())
  }

  private fun insertChar(ch: Char) {
    if (caretInLine == lineBuf.length) {
      lineBuf.append(ch)
      caretInLine++
      writeRaw(ch.toString())
    } else {
      lineBuf.insert(caretInLine, ch)
      caretInLine++
      redrawInputSuffix()
    }
  }

  private fun backspaceChar() {
    if (caretInLine <= 0) return
    lineBuf.deleteCharAt(caretInLine - 1)
    caretInLine--
    writeRaw("\b")
    redrawInputSuffix(erasedOne = true)
  }

  private fun moveCaret(delta: Int) {
    val next = (caretInLine + delta).coerceIn(0, lineBuf.length)
    val diff = next - caretInLine
    if (diff < 0) {
      writeRaw("\u001B[${-diff}D")
    } else if (diff > 0) {
      writeRaw("\u001B[${diff}C")
    }
    caretInLine = next
  }

  private fun replaceCurrentLine(newText: String) {
    if (caretInLine > 0) {
      writeRaw("\u001B[${caretInLine}D")
    }
    writeRaw("\u001B[K")
    lineBuf.clear()
    lineBuf.append(newText)
    caretInLine = lineBuf.length
    writeRaw(newText)
  }

  private fun redrawInputSuffix(erasedOne: Boolean = false) {
    val tail = lineBuf.substring(caretInLine)
    writeRaw(tail)
    if (erasedOne) writeRaw(" ")
    writeRaw("\u001B[K")
    val back = tail.length + if (erasedOne) 1 else 0
    if (back > 0) writeRaw("\u001B[${back}D")
  }

  private fun navigateHistory(dir: Int) {
    if (_isRunningCommand.value || history.isEmpty()) return
    if (historyIdx == -1) {
      if (dir >= 0) return
      savedDraft = lineBuf.toString()
      historyIdx = history.lastIndex
    } else {
      historyIdx += dir
      if (historyIdx < 0) historyIdx = 0
      if (historyIdx >= history.size) {
        historyIdx = -1
        replaceCurrentLine(savedDraft)
        return
      }
    }
    replaceCurrentLine(history[historyIdx])
  }

  private fun tabComplete() {
    val prefixText = lineBuf.substring(0, caretInLine)
    val lastSpace = prefixText.lastIndexOf(' ')
    val token = if (lastSpace >= 0) prefixText.substring(lastSpace + 1) else prefixText
    val cwd = _cwdFlow.value

    val candidates: List<String> =
      if (lastSpace < 0 && !token.contains('/')) {
        val cmds = listOf(
          "git", "ls", "cd", "pwd", "cat", "echo", "mkdir", "touch", "rm", "cp", "mv",
          "head", "tail", "grep", "wc", "find", "seq", "export", "env", "clear", "history", "which", "help",
        )
        val local = cwd.listFiles()?.map { if (it.isDirectory) "${it.name}/" else it.name }.orEmpty()
        (cmds + local).distinct().filter { it.startsWith(token) }.sorted()
      } else {
        val dirPart = token.substringBeforeLast('/', "")
        val basePart = token.substringAfterLast('/')
        val searchDir = if (dirPart.isEmpty()) cwd else resolvePath(cwd, dirPart)
        searchDir.listFiles()?.mapNotNull { f ->
          if (f.name.startsWith(basePart)) {
            val suffix = if (f.isDirectory) "/" else ""
            if (dirPart.isEmpty()) "${f.name}$suffix" else "$dirPart/${f.name}$suffix"
          } else null
        }?.sorted().orEmpty()
      }

    if (candidates.size == 1) {
      val completion = candidates[0].removePrefix(token)
      if (completion.isNotEmpty()) {
        for (c in completion) insertChar(c)
      }
    } else if (candidates.size > 1) {
      writeRaw("\r\n${candidates.joinToString("  ")}\r\n")
      printPrompt()
      writeRaw(lineBuf.toString())
      val back = lineBuf.length - caretInLine
      if (back > 0) writeRaw("\u001B[${back}D")
    }
  }

  private fun submitLine() {
    val cmd = lineBuf.toString()
    lineBuf.clear()
    caretInLine = 0
    historyIdx = -1
    writeRaw("\r\n")

    val trimmed = cmd.trim()
    if (trimmed.isEmpty()) {
      printPrompt()
      return
    }
    if (history.lastOrNull() != trimmed) {
      history.add(trimmed)
      if (history.size > 300) history.removeAt(0)
    }

    _isRunningCommand.value = true
    activeJob = scope.launch {
      try {
        executeCommandLine(trimmed)
      } catch (t: Throwable) {
        writeRaw("\u001B[31merror: ${t.message ?: "Command aborted"}\u001B[0m\r\n")
      } finally {
        activeProcess = null
        _isRunningCommand.value = false
        printPrompt()
      }
    }
  }

  // --- Command Dispatch & Built-ins ---

  private suspend fun executeCommandLine(rawCmd: String) {
    // Support sequential ; and && chaining for built-ins cleanly
    val segments = splitTopLevelChains(rawCmd)
    if (segments.size > 1 && segments.none { it.first.contains('|') }) {
      var lastExit = 0
      for ((seg, op) in segments) {
        if (op == "&&" && lastExit != 0) break
        lastExit = executeSingleCommand(seg.trim())
      }
      return
    }
    executeSingleCommand(rawCmd)
  }

  private suspend fun executeSingleCommand(raw: String): Int {
    if (raw.isEmpty()) return 0
    val tokens = tokenizeShellArgs(expandVariables(raw))
    if (tokens.isEmpty()) return 0

    // Detect output redirection (`>` / `>>`) when no pipe is present
    var redirectFile: File? = null
    var redirectAppend = false
    val cleanTokens = ArrayList<String>(tokens.size)
    var i = 0
    val hasPipe = tokens.contains("|")
    while (i < tokens.size && !hasPipe) {
      val t = tokens[i]
      if ((t == ">" || t == ">>") && i + 1 < tokens.size) {
        redirectAppend = (t == ">>")
        redirectFile = resolvePath(_cwdFlow.value, tokens[i + 1])
        i += 2
        continue
      }
      cleanTokens.add(t)
      i++
    }
    val effectiveTokens = if (hasPipe) tokens else cleanTokens
    if (effectiveTokens.isEmpty()) return 0

    val cmd = effectiveTokens[0]
    val args = effectiveTokens.drop(1)

    val outSink: (String) -> Unit =
      if (redirectFile != null) {
        val sb = StringBuilder()
        val rf = redirectFile
        // Collect into file and flush after built-in completes
        val targetSink: (String) -> Unit = { s -> sb.append(s.replace("\r\n", "\n")) }
        val exit = runBuiltinIfAny(cmd, args, hasPipe, targetSink)
        if (exit != null) {
          rf.parentFile?.mkdirs()
          if (redirectAppend) rf.appendText(sb.toString()) else rf.writeText(sb.toString())
          onWorkspaceMutated()
          return exit
        }
        return runSystemShell(raw)
      } else {
        { s -> writeRaw(s) }
      }

    val builtinExit = runBuiltinIfAny(cmd, args, hasPipe, outSink)
    if (builtinExit != null) return builtinExit

    return runSystemShell(raw)
  }

  private suspend fun runBuiltinIfAny(
    cmd: String,
    args: List<String>,
    hasPipe: Boolean,
    out: (String) -> Unit,
  ): Int? {
    if (hasPipe) return null
    val cwd = _cwdFlow.value

    return when (cmd) {
      "clear" -> {
        writeRaw("\u001B[2J\u001B[H")
        0
      }

      "help" -> {
        out(
          """
          |Built-in commands:
          |  cd, pwd, ls, cat, echo, mkdir, touch, rm, cp, mv, head, tail,
          |  grep, wc, find, seq, export, env, which, history, clear, date,
          |  git (init, status, add, reset, commit, log, diff, branch, checkout)
          |Other commands execute via /system/bin/sh.
          |
          """.trimMargin().replace("\n", "\r\n")
        )
        0
      }

      "pwd" -> {
        out("${cwd.absolutePath}\r\n")
        0
      }

      "cd" -> {
        val dest = args.firstOrNull() ?: homeDir.absolutePath
        val target = resolvePath(cwd, dest).canonicalFile
        if (!target.exists() || !target.isDirectory) {
          out("\u001B[31mcd: $dest: No such directory\u001B[0m\r\n")
          1
        } else {
          _cwdFlow.value = target
          envVars["PWD"] = target.absolutePath
          0
        }
      }

      "echo" -> {
        val noNl = args.firstOrNull() == "-n"
        val items = if (noNl) args.drop(1) else args
        out(items.joinToString(" "))
        if (!noNl) out("\r\n")
        0
      }

      "ls" -> {
        val showAll = args.any { it.startsWith("-") && it.contains('a') }
        val longFmt = args.any { it.startsWith("-") && it.contains('l') }
        val pathArg = args.firstOrNull { !it.startsWith("-") }
        val target = if (pathArg != null) resolvePath(cwd, pathArg) else cwd
        if (!target.exists()) {
          out("\u001B[31mls: $pathArg: No such file or directory\u001B[0m\r\n")
          return 1
        }
        val list = if (target.isFile) listOf(target) else target.listFiles()?.toList().orEmpty()
        val filtered = list
          .filter { showAll || !it.name.startsWith(".") }
          .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        for (f in filtered) {
          val colored = if (f.isDirectory) "\u001B[1;34m${f.name}/\u001B[0m" else f.name
          if (longFmt) {
            val typeCh = if (f.isDirectory) "d" else "-"
            val perm = "${typeCh}rwxr-xr-x"
            out(String.format(Locale.US, "%s %8d %s\r\n", perm, f.length(), colored))
          } else {
            out("$colored  ")
          }
        }
        if (!longFmt && filtered.isNotEmpty()) out("\r\n")
        0
      }

      "cat" -> {
        if (args.isEmpty()) return 1
        var exit = 0
        for (a in args) {
          val f = resolvePath(cwd, a)
          if (!f.isFile) {
            out("\u001B[31mcat: $a: No such file\u001B[0m\r\n")
            exit = 1
          } else {
            val text = f.readText(Charsets.UTF_8).replace("\r\n", "\n").replace("\n", "\r\n")
            out(text)
            if (text.isNotEmpty() && !text.endsWith("\n")) out("\r\n")
          }
        }
        exit
      }

      "touch" -> {
        for (a in args) {
          val f = resolvePath(cwd, a)
          f.parentFile?.mkdirs()
          if (!f.exists()) f.createNewFile() else f.setLastModified(System.currentTimeMillis())
        }
        onWorkspaceMutated()
        0
      }

      "mkdir" -> {
        val dirs = args.filter { !it.startsWith("-") }
        for (d in dirs) resolvePath(cwd, d).mkdirs()
        onWorkspaceMutated()
        0
      }

      "rm" -> {
        val recursive = args.any { it.startsWith("-") && (it.contains('r') || it.contains('R')) }
        val force = args.any { it.startsWith("-") && it.contains('f') }
        val targets = args.filter { !it.startsWith("-") }
        var exit = 0
        for (t in targets) {
          val f = resolvePath(cwd, t)
          if (!f.exists()) {
            if (!force) {
              out("\u001B[31mrm: $t: No such file or directory\u001B[0m\r\n")
              exit = 1
            }
          } else if (f.isDirectory && !recursive) {
            out("\u001B[31mrm: $t: is a directory\u001B[0m\r\n")
            exit = 1
          } else {
            f.deleteRecursively()
          }
        }
        onWorkspaceMutated()
        exit
      }

      "cp", "mv" -> {
        val srcs = args.filter { !it.startsWith("-") }
        if (srcs.size < 2) {
          out("\u001B[31musage: $cmd <src> <dst>\u001B[0m\r\n")
          return 1
        }
        val src = resolvePath(cwd, srcs[0])
        var dst = resolvePath(cwd, srcs[1])
        if (!src.exists()) {
          out("\u001B[31m$cmd: ${srcs[0]}: No such file\u001B[0m\r\n")
          return 1
        }
        if (dst.isDirectory) dst = File(dst, src.name)
        if (cmd == "cp") {
          src.copyRecursively(dst, overwrite = true)
        } else {
          if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
          }
        }
        onWorkspaceMutated()
        0
      }

      "head", "tail" -> {
        val count = args.firstOrNull { it.startsWith("-n") }?.removePrefix("-n")?.toIntOrNull() ?: 10
        val fileArg = args.lastOrNull { !it.startsWith("-") } ?: return 1
        val f = resolvePath(cwd, fileArg)
        if (!f.isFile) {
          out("\u001B[31m$cmd: $fileArg: No such file\u001B[0m\r\n")
          return 1
        }
        val lines = f.readLines(Charsets.UTF_8)
        val picked = if (cmd == "head") lines.take(count) else lines.takeLast(count)
        for (l in picked) out("$l\r\n")
        0
      }

      "grep" -> {
        val positional = args.filter { !it.startsWith("-") }
        if (positional.size < 2) {
          out("\u001B[31musage: grep <pattern> <file>\u001B[0m\r\n")
          return 1
        }
        val ignoreCase = args.any { it == "-i" }
        val regex = Regex(positional[0], if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        val f = resolvePath(cwd, positional[1])
        if (!f.isFile) return 1
        var matched = false
        f.readLines(Charsets.UTF_8).forEachIndexed { idx, line ->
          if (regex.containsMatchIn(line)) {
            matched = true
            out("\u001B[35m${idx + 1}\u001B[0m:$line\r\n")
          }
        }
        if (matched) 0 else 1
      }

      "wc" -> {
        val fileArg = args.lastOrNull { !it.startsWith("-") } ?: return 1
        val f = resolvePath(cwd, fileArg)
        if (!f.isFile) return 1
        val text = f.readText(Charsets.UTF_8)
        val lines = text.count { it == '\n' }
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        out("  $lines  $words  ${f.length()} $fileArg\r\n")
        0
      }

      "find" -> {
        val base = args.firstOrNull { !it.startsWith("-") }?.let { resolvePath(cwd, it) } ?: cwd
        base.walkTopDown().take(500).forEach { f ->
          if (f.name != ".git") {
            val rel = f.relativeTo(cwd).invariantSeparatorsPath.ifEmpty { "." }
            out("$rel\r\n")
          }
        }
        0
      }

      "seq" -> {
        val start = if (args.size >= 2) args[0].toLongOrNull() ?: 1L else 1L
        val end = args.lastOrNull()?.toLongOrNull() ?: 10L
        val sb = StringBuilder(8192)
        var i = start
        var batch = 0
        while (i <= end && scope.isActive) {
          sb.append(i).append("\r\n")
          batch++
          if (batch >= 2000) {
            out(sb.toString())
            sb.clear()
            batch = 0
          }
          i++
        }
        if (sb.isNotEmpty()) out(sb.toString())
        0
      }

      "env" -> {
        for ((k, v) in envVars) out("$k=$v\r\n")
        0
      }

      "export" -> {
        for (a in args) {
          val eq = a.indexOf('=')
          if (eq > 0) envVars[a.substring(0, eq)] = a.substring(eq + 1)
        }
        0
      }

      "which" -> {
        val target = args.firstOrNull() ?: return 1
        val builtins = setOf("cd", "pwd", "ls", "cat", "echo", "mkdir", "touch", "rm", "git", "clear", "seq", "help")
        if (target in builtins) {
          out("$target: Quark built-in\r\n")
          0
        } else {
          val found = envVars["PATH"]?.split(':')?.firstNotNullOfOrNull { dir ->
            File(dir, target).takeIf { it.exists() && it.canExecute() }
              ?: File(dir, "lib$target.so").takeIf { it.exists() && it.canExecute() }
          }
          if (found != null) {
            out("${found.absolutePath}\r\n")
            0
          } else {
            out("$target not found\r\n")
            1
          }
        }
      }

      "history" -> {
        history.forEachIndexed { idx, h -> out(String.format(Locale.US, "%4d  %s\r\n", idx + 1, h)) }
        0
      }

      "date" -> {
        out("${Date()}\r\n")
        0
      }

      "whoami" -> {
        out("${envVars["USER"] ?: "quark"}\r\n")
        0
      }

      "uname" -> {
        out("Linux Quark Android arm64/x86_64\r\n")
        0
      }

      "git" -> {
        val repoRoot = findGitRepoRoot(cwd) ?: cwd
        TerminalGitCli.execute(
          args = args,
          workTree = repoRoot,
          authorName = authorNameProvider(),
          authorEmail = authorEmailProvider(),
          out = out,
          onMutated = onWorkspaceMutated,
        )
      }

      else -> null
    }
  }

  private suspend fun runSystemShell(command: String): Int = withContext(Dispatchers.IO) {
    try {
      val pb = ProcessBuilder("/system/bin/sh", "-c", command)
      pb.directory(_cwdFlow.value)
      pb.redirectErrorStream(true)
      pb.environment().putAll(envVars)
      val proc = pb.start()
      activeProcess = proc

      val buf = ByteArray(16 * 1024)
      val stream = proc.inputStream
      while (true) {
        val n = stream.read(buf)
        if (n <= 0) break
        val chunk = String(buf, 0, n, Charsets.UTF_8).replace("\r\n", "\n").replace("\n", "\r\n")
        writeRaw(chunk)
      }
      val exit = proc.waitFor()
      onWorkspaceMutated()
      exit
    } catch (t: Throwable) {
      writeRaw("\u001B[31msh: ${t.message}\u001B[0m\r\n")
      127
    }
  }

  private fun writeRaw(text: String) {
    parser.feed(text)
    scheduleThrottledDirtyNotify()
  }

  private fun scheduleThrottledDirtyNotify() {
    if (dirtyScheduled.compareAndSet(false, true)) {
      scope.launch(Dispatchers.Main) {
        kotlinx.coroutines.delay(16L)
        dirtyScheduled.set(false)
        onDirty?.invoke()
      }
    }
  }

  private fun requestRepaintImmediate() {
    onDirty?.invoke()
  }

  private fun findGitRepoRoot(start: File): File? {
    var cur: File? = start
    while (cur != null) {
      if (File(cur, ".git").isDirectory) return cur
      if (cur.absolutePath == homeDir.absolutePath) break
      cur = cur.parentFile
    }
    return null
  }

  private fun resolvePath(cwd: File, path: String): File =
    when {
      path == "~" -> homeDir
      path.startsWith("~/") -> File(homeDir, path.removePrefix("~/"))
      path.startsWith("/") -> File(path)
      else -> File(cwd, path)
    }

  private fun expandVariables(input: String): String {
    var result = input
    for ((k, v) in envVars) {
      result = result.replace("\$$k", v).replace("\${$k}", v)
    }
    return result
  }

  private fun tokenizeShellArgs(input: String): List<String> {
    val tokens = ArrayList<String>()
    val sb = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var hasToken = false

    for (ch in input) {
      if (escaped) {
        sb.append(ch)
        escaped = false
        hasToken = true
        continue
      }
      if (ch == '\\' && quote != '\'') {
        escaped = true
        continue
      }
      if (quote != null) {
        if (ch == quote) quote = null else { sb.append(ch); hasToken = true }
        continue
      }
      if (ch == '"' || ch == '\'') {
        quote = ch
        hasToken = true
        continue
      }
      if (ch.isWhitespace()) {
        if (hasToken || sb.isNotEmpty()) {
          tokens.add(sb.toString())
          sb.clear()
          hasToken = false
        }
      } else {
        sb.append(ch)
        hasToken = true
      }
    }
    if (hasToken || sb.isNotEmpty()) tokens.add(sb.toString())
    return tokens
  }

  private fun splitTopLevelChains(input: String): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    var quote: Char? = null
    var start = 0
    var i = 0
    var nextOp = ""
    while (i < input.length) {
      val c = input[i]
      if (quote != null) {
        if (c == quote) quote = null
      } else if (c == '"' || c == '\'') {
        quote = c
      } else if (c == ';') {
        out.add(input.substring(start, i) to nextOp)
        nextOp = ";"
        start = i + 1
      } else if (c == '&' && i + 1 < input.length && input[i + 1] == '&') {
        out.add(input.substring(start, i) to nextOp)
        nextOp = "&&"
        start = i + 2
        i += 2
        continue
      }
      i++
    }
    out.add(input.substring(start) to nextOp)
    return out
  }
}
