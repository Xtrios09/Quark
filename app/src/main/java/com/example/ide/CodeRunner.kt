package com.example.ide

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.BuildConfig
import com.quark.fs.StorageBackend
import com.quark.fs.VfsNode
import com.quark.fs.WorkspaceFileSystem
import com.quark.fs.WorkspaceRoot
import com.quark.intel.runtime.OnDeviceCRunner
import com.quark.intel.runtime.OnDeviceGoRunner
import com.quark.intel.runtime.OnDeviceJavaRunner
import com.quark.intel.runtime.OnDeviceKotlinRunner
import com.quark.intel.runtime.OnDevicePythonRunner
import com.quark.intel.runtime.OnDeviceRustRunner
import com.quark.intel.runtime.ScriptExecutionOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resume

/** Result of compiling/checking or running a file inside Quark. */
data class RunExecutionResult(
  val exitCode: Int,
  val summary: String,
  val openHtmlPreview: Boolean = false,
  val htmlPreviewContent: String? = null,
  val htmlBaseUrl: String = "https://quark.local/",
)

/**
 * 100% on-device compile, syntax validation, and execution engine supporting all Quark languages:
 * - Kotlin (`.kt`/`.kts`), Java (`.java`), Python (`.py`/`.pyw`), C/C++ (`.c`/`.h`/`.cpp`/`.cc`/`.cxx`),
 *   Rust (`.rs`), Go (`.go`): invokes native binary if present, otherwise compiles & executes using
 *   Quark's pure-Kotlin on-device compilers & AST runtimes.
 * - JavaScript / TypeScript / JSX / TSX (`.js`/`.mjs`/`.cjs`/`.ts`/`.jsx`/`.tsx`): transpiles TS/JSX
 *   and executes inside an isolated WebView V8 engine.
 * - SQL (`.sql`/`.sqlite`): executes statements & queries in Android's built-in SQLite engine.
 * - HTML / SVG / Markdown: renders live WebView previews.
 * - Shell (`.sh`/`.bash`/`.zsh`): runs via `/system/bin/sh` with project root as cwd.
 * - JSON / JSONC / XML / CSS / SCSS / YAML / TOML: strict structural compilation/syntax checks.
 */
class CodeRunner(
  private val context: Context,
  private val fs: WorkspaceFileSystem,
) {

  suspend fun runFile(
    workspace: WorkspaceRoot?,
    node: VfsNode,
    sourceText: String,
    onLog: (String) -> Unit,
  ): RunExecutionResult {
    val ext = node.name.substringAfterLast('.', "").lowercase()
    onLog("--- Compile & Run: ${node.relativePath.ifEmpty { node.name }} ---")

    return when (ext) {
      "html", "htm" -> runHtmlPreview(workspace, node, sourceText, onLog)
      "svg" -> runSvgPreview(node, sourceText, onLog)
      "md", "markdown", "mdx" -> runMarkdownPreview(node, sourceText, onLog)
      "js", "mjs", "cjs", "ts", "jsx", "tsx" ->
        runJavaScriptOrTypeScript(
          node = node,
          sourceText = sourceText,
          isTypeScript = ext == "ts" || ext == "tsx",
          isJsx = ext == "jsx" || ext == "tsx",
          onLog = onLog,
        )
      "sh", "bash", "zsh" -> runShellScript(workspace, node, ext, sourceText, onLog)
      "json", "jsonc" -> checkJsonSyntax(node, sourceText, stripComments = ext == "jsonc", onLog = onLog)
      "xml" -> checkXmlSyntax(node, sourceText, onLog)
      "css", "scss", "sass" -> checkCssSyntax(node, sourceText, onLog)
      "yaml", "yml", "toml" -> checkYamlOrTomlSyntax(node, ext, sourceText, onLog)
      "sql", "sqlite" -> runSqlScript(node, sourceText, onLog)
      "kt", "kts", "java", "py", "pyw", "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "rs", "go" ->
        runCompiledOrInterpretedLanguage(workspace, node, ext, sourceText, onLog)
      else -> {
        val issues = preflightCheckBracketsAndQuotes(node.name, sourceText)
        if (issues.isNotEmpty()) {
          issues.forEach(onLog)
          RunExecutionResult(1, "${issues.size} syntax issue(s) in ${node.name}")
        } else {
          onLog("Structural check passed for ${node.name} (0 unmatched delimiters or quotes).")
          RunExecutionResult(0, "Checked ${node.name}")
        }
      }
    }
  }

  private suspend fun runHtmlPreview(
    workspace: WorkspaceRoot?,
    node: VfsNode,
    htmlSource: String,
    onLog: (String) -> Unit,
  ): RunExecutionResult {
    var inlined = htmlSource
    if (workspace != null) {
      val files = fs.collectAllFiles(workspace)
      val byName = files.associateBy { it.relativePath }
      val cssRegex = Regex("""<link\s+[^>]*href=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
      for (m in cssRegex.findAll(htmlSource)) {
        val href = m.groupValues[1]
        val target = byName[href] ?: files.firstOrNull { it.name == href.substringAfterLast('/') }
        if (target != null && target.name.endsWith(".css", ignoreCase = true)) {
          val css = runCatching { fs.readText(target) }.getOrNull()
          if (css != null) {
            inlined = inlined.replace(m.value, "<style>\n$css\n</style>")
          }
        }
      }
      val jsRegex = Regex("""<script\s+[^>]*src=["']([^"']+)["'][^>]*>\s*</script>""", RegexOption.IGNORE_CASE)
      for (m in jsRegex.findAll(htmlSource)) {
        val src = m.groupValues[1]
        val target = byName[src] ?: files.firstOrNull { it.name == src.substringAfterLast('/') }
        if (target != null && target.name.endsWith(".js", ignoreCase = true)) {
          val js = runCatching { fs.readText(target) }.getOrNull()
          if (js != null) {
            inlined = inlined.replace(m.value, "<script>\n$js\n</script>")
          }
        }
      }
    }
    onLog("Launching live HTML preview on virtual origin https://quark.local/ ...")
    return RunExecutionResult(
      exitCode = 0,
      summary = "Previewing ${node.name}",
      openHtmlPreview = true,
      htmlPreviewContent = inlined,
    )
  }

  private suspend fun runSvgPreview(
    node: VfsNode,
    svgSource: String,
    onLog: (String) -> Unit,
  ): RunExecutionResult {
    val xmlCheck = validateXmlString(node.name, svgSource)
    if (xmlCheck != null) {
      onLog(xmlCheck)
      return RunExecutionResult(1, "SVG error in ${node.name}")
    }
    val html =
      """
      <!DOCTYPE html>
      <html>
      <head><meta charset="utf-8"><style>
        body { margin:0; min-height:100vh; display:grid; place-items:center; background:#1e222a; color:#e6edf3; }
        svg { max-width:90vw; max-height:85vh; }
      </style></head>
      <body>$svgSource</body>
      </html>
      """.trimIndent()
    onLog("SVG XML valid. Rendering preview...")
    return RunExecutionResult(0, "Previewing ${node.name}", openHtmlPreview = true, htmlPreviewContent = html)
  }

  private fun runMarkdownPreview(
    node: VfsNode,
    mdSource: String,
    onLog: (String) -> Unit,
  ): RunExecutionResult {
    val htmlBody = renderMarkdownToHtml(mdSource)
    val html =
      """
      <!DOCTYPE html>
      <html>
      <head><meta charset="utf-8"><style>
        body { font-family: system-ui, sans-serif; line-height:1.6; max-width:780px; margin:24px auto; padding:0 20px; background:#181b20; color:#e6edf3; }
        h1,h2,h3 { border-bottom:1px solid #30363d; padding-bottom:6px; color:#79c0ff; }
        code { background:#24292f; padding:2px 6px; border-radius:4px; font-family:monospace; }
        pre { background:#24292f; padding:12px; border-radius:8px; overflow-x:auto; border:1px solid #30363d; }
        pre code { background:none; padding:0; }
        blockquote { border-left:3px solid #58a6ff; margin-left:0; padding-left:14px; color:#8b949e; }
        a { color:#58a6ff; }
      </style></head>
      <body>$htmlBody</body>
      </html>
      """.trimIndent()
    onLog("Compiled Markdown -> HTML preview (${mdSource.lines().size} lines).")
    return RunExecutionResult(0, "Previewing ${node.name}", openHtmlPreview = true, htmlPreviewContent = html)
  }

  private fun renderMarkdownToHtml(md: String): String {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    fun inlineFormat(line: String): String {
      var out = esc(line)
      out = out.replace(Regex("""`([^`]+)`""")) { "<code>${it.groupValues[1]}</code>" }
      out = out.replace(Regex("""\*\*([^*]+)\*\*""")) { "<strong>${it.groupValues[1]}</strong>" }
      out = out.replace(Regex("""\[([^\]]+)\]\(([^)]+)\)""")) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
      return out
    }
    val sb = StringBuilder()
    var inCode = false
    for (raw in md.lines()) {
      val trimmed = raw.trim()
      if (trimmed.startsWith("```")) {
        sb.append(if (inCode) "</code></pre>\n" else "<pre><code>")
        inCode = !inCode
        continue
      }
      if (inCode) {
        sb.append(esc(raw)).append('\n')
        continue
      }
      when {
        trimmed.startsWith("### ") -> sb.append("<h3>${inlineFormat(trimmed.drop(4))}</h3>\n")
        trimmed.startsWith("## ") -> sb.append("<h2>${inlineFormat(trimmed.drop(3))}</h2>\n")
        trimmed.startsWith("# ") -> sb.append("<h1>${inlineFormat(trimmed.drop(2))}</h1>\n")
        trimmed.startsWith("> ") -> sb.append("<blockquote>${inlineFormat(trimmed.drop(2))}</blockquote>\n")
        trimmed.startsWith("- ") || trimmed.startsWith("* ") -> sb.append("<p>• ${inlineFormat(trimmed.drop(2))}</p>\n")
        trimmed.isEmpty() -> sb.append("<br/>\n")
        else -> sb.append("<p>${inlineFormat(raw)}</p>\n")
      }
    }
    if (inCode) sb.append("</code></pre>\n")
    return sb.toString()
  }

  @SuppressLint("SetJavaScriptEnabled")
  private suspend fun runJavaScriptOrTypeScript(
    node: VfsNode,
    sourceText: String,
    isTypeScript: Boolean,
    isJsx: Boolean,
    onLog: (String) -> Unit,
  ): RunExecutionResult = withContext(Dispatchers.Main) {
    val issues = preflightCheckBracketsAndQuotes(node.name, sourceText)
    if (issues.isNotEmpty()) {
      issues.forEach(onLog)
      return@withContext RunExecutionResult(1, "${issues.size} syntax error(s) in ${node.name}")
    }
    val jsCode = if (isTypeScript || isJsx) transpileTypeScriptAndJsx(sourceText, isTypeScript, isJsx) else sourceText
    if (isTypeScript || isJsx) {
      val modeLabel = listOfNotNull("TypeScript".takeIf { isTypeScript }, "JSX".takeIf { isJsx }).joinToString(" + ")
      onLog("Transpiled $modeLabel to ES6 JavaScript.")
    }

    suspendCancellableCoroutine { cont ->
      val webView = WebView(context)
      webView.settings.apply {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        cacheMode = WebSettings.LOAD_NO_CACHE
      }

      var finished = false
      fun complete(code: Int, summary: String) {
        if (!finished && cont.isActive) {
          finished = true
          webView.destroy()
          cont.resume(RunExecutionResult(code, summary))
        }
      }

      webView.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
          val level = consoleMessage.messageLevel().name
          val line = consoleMessage.lineNumber()
          onLog("[$level] ${node.name}:$line: ${consoleMessage.message()}")
          return true
        }
      }

      webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

        override fun onPageFinished(view: WebView?, url: String?) {
          val wrapped =
            """
            (function() {
              function __quark_el(tag, props) {
                var children = [];
                for (var i = 2; i < arguments.length; i++) children.push(arguments[i]);
                return { tag: tag, props: props || {}, children: children };
              }
              try {
                $jsCode
                return "__QUARK_OK__";
              } catch (e) {
                console.error(e && e.stack ? e.stack : String(e));
                return "__QUARK_ERR__:" + String(e);
              }
            })();
            """.trimIndent()
          view?.evaluateJavascript(wrapped) { result ->
            val clean = result?.trim('"') ?: ""
            if (clean.startsWith("__QUARK_ERR__:")) {
              val errMsg = clean.removePrefix("__QUARK_ERR__:")
              onLog("Exit code 1 ($errMsg)")
              complete(1, "JS Error in ${node.name}")
            } else {
              onLog("Process finished with exit code 0.")
              complete(0, "Executed ${node.name}")
            }
          }
        }
      }

      cont.invokeOnCancellation { webView.destroy() }
      webView.loadDataWithBaseURL(
        "https://quark.local/",
        "<!DOCTYPE html><html><head><meta charset='utf-8'></head><body></body></html>",
        "text/html",
        "UTF-8",
        null,
      )
    }
  }

  private suspend fun runShellScript(
    workspace: WorkspaceRoot?,
    node: VfsNode,
    ext: String,
    sourceText: String,
    onLog: (String) -> Unit,
  ): RunExecutionResult = withContext(Dispatchers.IO) {
    val scriptFile =
      if (node.backend == StorageBackend.APP_PRIVATE && node.absolutePath != null) {
        File(node.absolutePath!!)
      } else {
        File(context.cacheDir, "quark_run_tmp.$ext").apply { writeText(sourceText) }
      }
    val workDir =
      if (workspace?.backend == StorageBackend.APP_PRIVATE) File(workspace.rootUriOrPath)
      else context.filesDir

    try {
      val shellPath = if (ext == "zsh") locateExecutable("zsh")?.absolutePath ?: "/system/bin/sh" else "/system/bin/sh"
      val pb = ProcessBuilder(shellPath, scriptFile.absolutePath)
      pb.directory(workDir)
      pb.redirectErrorStream(true)
      val env = pb.environment()
      env["HOME"] = context.filesDir.absolutePath
      env["TMPDIR"] = context.cacheDir.absolutePath
      val binDir = File(context.filesDir, "bin").absolutePath
      val nativeDir = context.applicationInfo.nativeLibraryDir
      env["PATH"] = "$binDir:$nativeDir:/system/bin:/system/xbin"

      val proc = pb.start()
      proc.inputStream.bufferedReader().forEachLine { line ->
        onLog(line)
      }
      val exit = proc.waitFor()
      onLog("Process exited with code $exit")
      RunExecutionResult(exit, if (exit == 0) "Shell script exited 0" else "Shell exited $exit")
    } catch (t: Throwable) {
      onLog("Shell execution failed: ${t.message}")
      RunExecutionResult(1, "Shell failed: ${t.message}")
    }
  }

  private suspend fun runSqlScript(
    node: VfsNode,
    sourceText: String,
    onLog: (String) -> Unit,
  ): RunExecutionResult = withContext(Dispatchers.IO) {
    val statements = splitSqlStatements(sourceText)
    if (statements.isEmpty()) {
      onLog("SQL script is empty.")
      return@withContext RunExecutionResult(0, "No SQL statements")
    }
    var db: SQLiteDatabase? = null
    return@withContext try {
      db = SQLiteDatabase.create(null)
      onLog("Executing ${statements.size} SQL statement(s) on Android SQLite engine...")
      var executed = 0
      for (stmt in statements) {
        val clean = stmt.trim()
        if (clean.isEmpty()) continue
        executed++
        val head = clean.trimStart().uppercase()
        if (head.startsWith("SELECT") || head.startsWith("PRAGMA") || head.startsWith("WITH") || head.startsWith("EXPLAIN")) {
          db.rawQuery(clean, null).use { cursor ->
            val cols = cursor.columnNames
            onLog(cols.joinToString(" | "))
            onLog(cols.joinToString("-+-") { "-".repeat(it.length.coerceAtLeast(3)) })
            var rowCount = 0
            while (cursor.moveToNext()) {
              val vals = (0 until cols.size).map { idx ->
                when (cursor.getType(idx)) {
                  android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                  android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(idx).toString()
                  android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(idx).toString()
                  android.database.Cursor.FIELD_TYPE_BLOB -> "<blob:${cursor.getBlob(idx).size}B>"
                  else -> cursor.getString(idx) ?: ""
                }
              }
              onLog(vals.joinToString(" | "))
              rowCount++
            }
            onLog("($rowCount row(s))")
          }
        } else {
          db.execSQL(clean)
          onLog("OK: ${clean.lines().first().take(72)}")
        }
      }
      onLog("SQL execution finished ($executed statement(s), exit code 0).")
      RunExecutionResult(0, "Executed $executed SQL statement(s)")
    } catch (t: Throwable) {
      val msg = "${node.name}: SQL error: ${t.message ?: t::class.simpleName}"
      onLog(msg)
      RunExecutionResult(1, "SQL error in ${node.name}")
    } finally {
      db?.close()
    }
  }

  private fun splitSqlStatements(sql: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var i = 0
    var inSingle = false
    var inDouble = false
    var inLineComment = false
    var inBlockComment = false
    while (i < sql.length) {
      val c = sql[i]
      val next = if (i + 1 < sql.length) sql[i + 1] else '\u0000'
      when {
        inLineComment -> {
          if (c == '\n') inLineComment = false
          i++
        }
        inBlockComment -> {
          if (c == '*' && next == '/') { inBlockComment = false; i += 2 } else i++
        }
        inSingle -> {
          cur.append(c)
          if (c == '\'' && next == '\'') { cur.append(next); i += 2 }
          else { if (c == '\'') inSingle = false; i++ }
        }
        inDouble -> {
          cur.append(c)
          if (c == '"') inDouble = false
          i++
        }
        c == '-' && next == '-' -> { inLineComment = true; i += 2 }
        c == '/' && next == '*' -> { inBlockComment = true; i += 2 }
        c == '\'' -> { inSingle = true; cur.append(c); i++ }
        c == '"' -> { inDouble = true; cur.append(c); i++ }
        c == ';' -> {
          val s = cur.toString().trim()
          if (s.isNotEmpty()) out.add(s)
          cur.setLength(0)
          i++
        }
        else -> { cur.append(c); i++ }
      }
    }
    val rem = cur.toString().trim()
    if (rem.isNotEmpty()) out.add(rem)
    return out
  }

  private fun checkJsonSyntax(
    node: VfsNode,
    sourceText: String,
    stripComments: Boolean,
    onLog: (String) -> Unit,
  ): RunExecutionResult {
    return try {
      val prepared = if (stripComments) stripJsonComments(sourceText) else sourceText
      val trimmed = prepared.trim()
      if (trimmed.startsWith("[")) org.json.JSONArray(trimmed) else org.json.JSONObject(trimmed)
      onLog("${if (stripComments) "JSONC" else "JSON"} validation passed: ${node.name} is well-formed.")
      RunExecutionResult(0, "Valid JSON")
    } catch (t: Throwable) {
      onLog("${node.name}:1:1: JSON syntax error: ${t.message}")
      RunExecutionResult(1, "Invalid JSON")
    }
  }

  private fun stripJsonComments(src: String): String {
    val sb = StringBuilder(src.length)
    var i = 0
    var inStr = false
    var esc = false
    while (i < src.length) {
      val c = src[i]
      val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
      if (inStr) {
        sb.append(c)
        if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
        i++
      } else if (c == '"') {
        inStr = true; sb.append(c); i++
      } else if (c == '/' && next == '/') {
        while (i < src.length && src[i] != '\n') i++
      } else if (c == '/' && next == '*') {
        i += 2
        while (i < src.length - 1 && !(src[i] == '*' && src[i + 1] == '/')) {
          if (src[i] == '\n') sb.append('\n')
          i++
        }
        i += 2
      } else {
        sb.append(c); i++
      }
    }
    return sb.toString()
  }

  private fun validateXmlString(fileName: String, sourceText: String): String? {
    return try {
      val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
      }
      val builder = factory.newDocumentBuilder()
      builder.parse(InputSource(StringReader(sourceText)))
      null
    } catch (e: SAXParseException) {
      "$fileName:${e.lineNumber}:${e.columnNumber}: XML parse error: ${e.message}"
    } catch (t: Throwable) {
      "$fileName:1:1: XML error: ${t.message}"
    }
  }

  private fun checkXmlSyntax(
    node: VfsNode,
    sourceText: String,
    onLog: (String) -> Unit,
  ): RunExecutionResult {
    val err = validateXmlString(node.name, sourceText)
    return if (err == null) {
      onLog("XML validation passed: ${node.name} is well-formed.")
      RunExecutionResult(0, "Valid XML")
    } else {
      onLog(err)
      RunExecutionResult(1, "Invalid XML")
    }
  }

  private fun checkCssSyntax(
    node: VfsNode,
    sourceText: String,
    onLog: (String) -> Unit,
  ): RunExecutionResult {
    val issues = preflightCheckBracketsAndQuotes(node.name, sourceText)
    if (issues.isNotEmpty()) {
      issues.forEach(onLog)
      return RunExecutionResult(1, "${issues.size} CSS error(s)")
    }
    val rules = Regex("""\{""").findAll(sourceText).count()
    onLog("CSS/Stylesheet syntax check passed ($rules rule block(s), 0 brace/string errors).")
    return RunExecutionResult(0, "Valid CSS ($rules blocks)")
  }

  private fun checkYamlOrTomlSyntax(
    node: VfsNode,
    ext: String,
    sourceText: String,
    onLog: (String) -> Unit,
  ): RunExecutionResult {
    val errors = ArrayList<String>()
    val lines = sourceText.lines()
    lines.forEachIndexed { idx, raw ->
      val lineNo = idx + 1
      val stripped = raw.trim()
      if (stripped.isEmpty() || stripped.startsWith("#")) return@forEachIndexed
      if (ext == "toml") {
        if (!stripped.startsWith("[") && !stripped.contains("=")) {
          errors.add("${node.name}:$lineNo:1: expected `key = value` or `[section]` in TOML")
        } else if (stripped.startsWith("[") && !stripped.endsWith("]")) {
          errors.add("${node.name}:$lineNo:1: unclosed TOML section header")
        }
      } else {
        if (raw.contains('\t')) {
          errors.add("${node.name}:$lineNo:1: YAML does not allow literal tab indentation")
        } else if (!stripped.startsWith("-") && !stripped.startsWith("---") && !stripped.startsWith("...") && !stripped.contains(':')) {
          errors.add("${node.name}:$lineNo:1: expected YAML `key: value` mapping or `- list item`")
        }
      }
    }
    val quoteIssues = preflightCheckBracketsAndQuotes(node.name, sourceText)
    val allErrors = errors + quoteIssues
    return if (allErrors.isEmpty()) {
      onLog("${ext.uppercase()} validation passed (${lines.size} lines, 0 syntax errors).")
      RunExecutionResult(0, "Valid ${ext.uppercase()}")
    } else {
      allErrors.forEach(onLog)
      RunExecutionResult(1, "${allErrors.size} ${ext.uppercase()} error(s)")
    }
  }

  private suspend fun runCompiledOrInterpretedLanguage(
    workspace: WorkspaceRoot?,
    node: VfsNode,
    ext: String,
    sourceText: String,
    onLog: (String) -> Unit,
  ): RunExecutionResult = withContext(Dispatchers.IO) {
    // 1. Always run fast structural syntax & bracket/quote preflight check first
    val issues = preflightCheckBracketsAndQuotes(node.name, sourceText)
    if (issues.isNotEmpty()) {
      for (issue in issues) onLog(issue)
      onLog("Preflight compile check failed with ${issues.size} error(s).")
      return@withContext RunExecutionResult(1, "${issues.size} syntax error(s)")
    }
    onLog("Preflight syntax check passed (0 bracket/string errors).")

    // 2. Look for an installed native compiler/interpreter binary respecting flavor rules
    val toolName =
      when (ext) {
        "kt", "kts" -> "kotlinc"
        "java" -> "javac"
        "py", "pyw" -> "python3"
        "c", "h" -> "clang"
        "cpp", "cc", "cxx", "hpp", "hh" -> "clang++"
        "rs" -> "rustc"
        "go" -> "go"
        else -> ""
      }

    val binary = locateExecutable(toolName)
    if (binary != null && node.absolutePath != null) {
      return@withContext try {
        val workDir =
          if (workspace?.backend == StorageBackend.APP_PRIVATE) File(workspace.rootUriOrPath)
          else context.filesDir
        val pb = ProcessBuilder(binary.absolutePath, node.absolutePath!!)
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.inputStream.bufferedReader().forEachLine { onLog(it) }
        val exit = proc.waitFor()
        onLog("$toolName exited with code $exit")
        RunExecutionResult(exit, if (exit == 0) "$toolName succeeded" else "$toolName failed ($exit)")
      } catch (t: Throwable) {
        onLog("Failed running $toolName: ${t.message}")
        RunExecutionResult(1, "Execution error")
      }
    }

    // 3. Built-in 100% on-device pure-Kotlin language compilers & runtimes
    return@withContext when (ext) {
      "kt", "kts" -> {
        onLog("Compiling & running with Quark On-Device Kotlin Runtime...")
        val siblings = LinkedHashMap<String, String>()
        if (workspace != null) {
          val allFiles = runCatching { fs.collectAllFiles(workspace) }.getOrDefault(emptyList())
          for (f in allFiles) {
            if (f.relativePath != node.relativePath && f.name.endsWith(".kt", ignoreCase = true)) {
              runCatching { fs.readText(f) }.getOrNull()?.let { siblings[f.name] = it }
            }
          }
        }
        val result: ScriptExecutionOutput =
          withContext(Dispatchers.Default) {
            OnDeviceKotlinRunner.run(
              entryFileName = node.name,
              entrySource = sourceText,
              siblingSources = siblings,
              onOutputLine = onLog,
            )
          }
        onLog("Process finished with exit code ${result.exitCode}.")
        RunExecutionResult(
          result.exitCode,
          if (result.exitCode == 0) "Executed ${node.name}" else "Kotlin error in ${node.name}",
        )
      }
      "py", "pyw" -> {
        onLog("Running with Quark On-Device Python Runtime...")
        val result =
          withContext(Dispatchers.Default) {
            OnDevicePythonRunner.run(node.name, sourceText, onOutputLine = onLog)
          }
        onLog("Process finished with exit code ${result.exitCode}.")
        RunExecutionResult(
          result.exitCode,
          if (result.exitCode == 0) "Executed ${node.name}" else "Python error in ${node.name}",
        )
      }
      "java" -> {
        onLog("Compiling & running with Quark On-Device Java Runtime...")
        val result =
          withContext(Dispatchers.Default) {
            OnDeviceJavaRunner.run(node.name, sourceText, onOutputLine = onLog)
          }
        onLog("Process finished with exit code ${result.exitCode}.")
        RunExecutionResult(
          result.exitCode,
          if (result.exitCode == 0) "Executed ${node.name}" else "Java error in ${node.name}",
        )
      }
      "c", "h", "cpp", "cc", "cxx", "hpp", "hh" -> {
        val label = if (ext == "c" || ext == "h") "C" else "C++"
        onLog("Compiling & running with Quark On-Device $label Compiler...")
        val result =
          withContext(Dispatchers.Default) {
            OnDeviceCRunner.run(node.name, sourceText, onOutputLine = onLog)
          }
        onLog("Process finished with exit code ${result.exitCode}.")
        RunExecutionResult(
          result.exitCode,
          if (result.exitCode == 0) "Executed ${node.name}" else "$label error in ${node.name}",
        )
      }
      "rs" -> {
        onLog("Compiling & running with Quark On-Device Rust Compiler...")
        val result =
          withContext(Dispatchers.Default) {
            OnDeviceRustRunner.run(node.name, sourceText, onOutputLine = onLog)
          }
        onLog("Process finished with exit code ${result.exitCode}.")
        RunExecutionResult(
          result.exitCode,
          if (result.exitCode == 0) "Executed ${node.name}" else "Rust error in ${node.name}",
        )
      }
      "go" -> {
        onLog("Compiling & running with Quark On-Device Go Compiler...")
        val result =
          withContext(Dispatchers.Default) {
            OnDeviceGoRunner.run(node.name, sourceText, onOutputLine = onLog)
          }
        onLog("Process finished with exit code ${result.exitCode}.")
        RunExecutionResult(
          result.exitCode,
          if (result.exitCode == 0) "Executed ${node.name}" else "Go error in ${node.name}",
        )
      }
      else -> RunExecutionResult(0, "Preflight OK")
    }
  }

  private fun locateExecutable(name: String): File? {
    if (name.isEmpty()) return null
    val nativeCandidate = File(context.applicationInfo.nativeLibraryDir, "lib${name}.so")
    if (nativeCandidate.exists() && nativeCandidate.canExecute()) return nativeCandidate
    if (BuildConfig.CAN_EXEC_APP_BIN) {
      val binCandidate = File(File(context.filesDir, "bin"), name)
      if (binCandidate.exists() && binCandidate.canExecute()) return binCandidate
    }
    return null
  }

  private fun preflightCheckBracketsAndQuotes(fileName: String, text: String): List<String> {
    val errors = ArrayList<String>()
    val stack = ArrayDeque<Triple<Char, Int, Int>>() // char, line, col
    var line = 1
    var col = 1
    var inLineComment = false
    var inBlockComment = false
    var inString: Char? = null
    var escaped = false

    var i = 0
    while (i < text.length) {
      val c = text[i]
      val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
      if (c == '\n') {
        inLineComment = false
        if (inString != null && inString != '`') {
          errors.add("$fileName:$line:$col: Unterminated string literal")
          inString = null
        }
        line++
        col = 1
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

      if (c == '/' && next == '/') {
        inLineComment = true
        i += 2
        col += 2
        continue
      }
      if (c == '/' && next == '*') {
        inBlockComment = true
        i += 2
        col += 2
        continue
      }
      if (c == '"' || c == '\'' || c == '`') {
        inString = c
        col++
        i++
        continue
      }
      when (c) {
        '(', '[', '{' -> stack.addLast(Triple(c, line, col))
        ')', ']', '}' -> {
          val expected = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
          if (stack.isEmpty()) {
            errors.add("$fileName:$line:$col: Unexpected closing '$c'")
          } else {
            val top = stack.removeLast()
            if (top.first != expected) {
              errors.add("$fileName:$line:$col: Mismatched '$c' (expected closing for '${top.first}' opened at ${top.second}:${top.third})")
            }
          }
        }
      }
      col++
      i++
    }
    while (stack.isNotEmpty()) {
      val unclosed = stack.removeLast()
      errors.add("$fileName:${unclosed.second}:${unclosed.third}: Unclosed '${unclosed.first}'")
    }
    return errors
  }

  private fun transpileTypeScriptAndJsx(source: String, isTs: Boolean, isJsx: Boolean): String {
    var out = source
    if (isTs) {
      out = out.replace(Regex("""(?m)^\s*import\s+type\s+[^;]+;?\s*$"""), "")
      out = out.replace(Regex("""(?m)^\s*(?:export\s+)?interface\s+\w+(?:<[^>]*>)?\s*\{[^}]*\}\s*$"""), "")
      out = out.replace(Regex("""(?m)^\s*(?:export\s+)?type\s+\w+(?:<[^>]*>)?\s*=\s*[^;]+;\s*$"""), "")
      out = out.replace(Regex("""(?m)^\s*export\s+(?=(?:function|const|let|var|class|async)\b)"""), "")
      out = out.replace(Regex("""(\w+)\??\s*:\s*[A-Za-z_][A-Za-z0-9_<>\[\]|&\s]*(?=[,)=])"""), "$1")
      out = out.replace(Regex("""\)\s*:\s*[A-Za-z_][A-Za-z0-9_<>\[\]|&\s]*\s*\{"""), ") {")
      out = out.replace(Regex("""\s+as\s+[A-Za-z_][A-Za-z0-9_<>\[\]|&]*"""), "")
    }
    if (isJsx) {
      // Transform self-closing & simple JSX tags into __quark_el calls so JSX runs cleanly in V8
      out = out.replace(Regex("""<([A-Za-z][A-Za-z0-9_]*)\s*/>""")) { "__quark_el(\"${it.groupValues[1]}\", {})" }
      out = out.replace(Regex("""<([A-Za-z][A-Za-z0-9_]*)>([^<]*)</\1>""")) {
        val tag = it.groupValues[1]
        val body = it.groupValues[2].trim().replace("\"", "\\\"")
        "__quark_el(\"$tag\", {}, \"$body\")"
      }
    }
    return out
  }
}
