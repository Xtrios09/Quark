package com.quark.intel.runtime

/**
 * Lightweight 100% on-device Java source evaluator transforming standard Java classes (`main`,
 * `System.out.println`, typed variables, `for`/`while`/`if`) into our pure-Kotlin runtime.
 */
object OnDeviceJavaRunner {

  fun run(
    fileName: String,
    source: String,
    onOutputLine: (String) -> Unit = {},
  ): ScriptExecutionOutput {
    val translated = translateJavaToKotlinScript(source)
    return OnDeviceKotlinRunner.run(
      entryFileName = fileName,
      entrySource = translated,
      onOutputLine = onOutputLine,
    )
  }

  private fun translateJavaToKotlinScript(javaSrc: String): String {
    val lines = javaSrc.lines()
    val out = StringBuilder(javaSrc.length)
    var skippedOuterClassBrace = false

    for (raw in lines) {
      var line = raw.trimEnd()
      val trimmed = line.trim()

      // Skip Java package/imports
      if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
        continue
      }
      // Drop outermost class declaration header while preserving inner methods
      if (!skippedOuterClassBrace && Regex("""^(?:public\s+|final\s+|abstract\s+)*class\s+[A-Za-z_][A-Za-z0-9_]*.*\{$""").matches(trimmed)) {
        skippedOuterClassBrace = true
        continue
      }

      // Translate `public static void main(String[] args) {` -> `fun main() {`
      line = line.replace(
        Regex("""(?:public\s+)?(?:static\s+)?void\s+main\s*\([^)]*\)\s*\{"""),
        "fun main() {",
      )

      // Translate typed method declarations: `public static int add(int a, int b) {` -> `fun add(a: Int, b: Int): Int {`
      line = line.replace(
        Regex("""^(\s*)(?:(?:public|private|protected|static|final)\s+)*([A-Za-z_][A-Za-z0-9_<>\[\]]*)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*\{$"""),
      ) { m ->
        val indent = m.groupValues[1]
        val rawRet = m.groupValues[2]
        val name = m.groupValues[3]
        if (rawRet == "fun" || name in setOf("if", "for", "while", "switch", "catch")) {
          m.value
        } else {
          val ret = mapJavaType(rawRet)
          val params = m.groupValues[4].trim()
          val ktParams =
            if (params.isEmpty()) ""
            else params.split(',').joinToString(", ") { part ->
              val parts = part.trim().split(Regex("\\s+")).filter { it != "final" }
              if (parts.size >= 2) "${parts.last()}: ${mapJavaType(parts[parts.size - 2])}" else part
            }
          if (ret == "Unit") "${indent}fun $name($ktParams) {" else "${indent}fun $name($ktParams): $ret {"
        }
      }

      // `System.out.println(...)` / `System.out.print(...)`
      line = line.replace("System.out.println(", "println(")
      line = line.replace("System.out.print(", "print(")
      line = line.replace("Math.max(", "max(")
      line = line.replace("Math.min(", "min(")
      line = line.replace("Math.abs(", "abs(")
      line = line.replace("Math.sqrt(", "sqrt(")
      line = line.replace("Math.pow(", "pow(")
      line = line.replace("Math.PI", "PI")

      // Simple local variable declarations: `int x = 5;` -> `var x = 5`
      line = line.replace(
        Regex("""^\s*(?:final\s+)?(int|long|double|float|boolean|String|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*="""),
        "var $2 =",
      )
      line = line.replace(
        Regex("""^\s*for\s*\(\s*(?:int|long|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([^;]+);\s*\1\s*<\s*([^;]+);\s*\1\+\+\s*\)\s*\{"""),
        "for ($1 in ($2) until ($3)) {",
      )

      // Drop trailing semicolons at end of line
      if (line.trimEnd().endsWith(";")) {
        val idx = line.lastIndexOf(';')
        line = line.removeRange(idx, idx + 1)
      }

      out.append(line).append('\n')
    }

    // Remove the closing `}` of the outer class if we removed its opening `{`
    var result = out.toString()
    if (skippedOuterClassBrace) {
      val lastBrace = result.lastIndexOf('}')
      if (lastBrace >= 0) {
        result = result.removeRange(lastBrace, lastBrace + 1)
      }
    }
    return result
  }

  private fun mapJavaType(t: String): String =
    when (t) {
      "void" -> "Unit"
      "int" -> "Int"
      "long" -> "Long"
      "double", "float" -> "Double"
      "boolean" -> "Boolean"
      else -> t
    }
}
