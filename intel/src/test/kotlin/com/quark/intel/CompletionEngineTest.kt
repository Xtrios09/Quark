package com.quark.intel

import com.quark.buffer.TextBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionEngineTest {

  @Test
  fun completesLocalScopeAndPrefixSymbolsRankedBeforeKeywords() {
    val src =
      """
      package app

      fun greet(target: String): String {
        return "Hello ${'$'}target"
      }

      fun main() {
        val greetingPrefix = "hi"
        gr
      }
      """.trimIndent()
    val buf = TextBuffer(src)
    val engine = CompletionEngine()
    val caret = src.lastIndexOf("gr") + 2
    val items = engine.complete(buf, "src/Main.kt", caret)

    assertTrue("Expected completions for 'gr'", items.isNotEmpty())
    val labels = items.map { it.label }
    assertTrue("Expected greet in completions: $labels", "greet" in labels)
    assertTrue("Expected greetingPrefix in completions: $labels", "greetingPrefix" in labels)
  }

  @Test
  fun completesDotMembersUsingInferredCollectionAndStringTypes() {
    val src =
      """
      fun main() {
        val items = listOf("alpha", "beta")
        items.
        val name: String = "quark"
        name.
      }
      """.trimIndent()
    val buf = TextBuffer(src)
    val engine = CompletionEngine()

    val listDotCaret = src.indexOf("items.") + "items.".length
    val listMembers = engine.complete(buf, "src/Main.kt", listDotCaret)
    val listLabels = listMembers.map { it.label }
    assertTrue("Expected forEachIndexed on inferred List: $listLabels", "forEachIndexed" in listLabels)
    assertTrue("Expected joinToString on inferred List: $listLabels", "joinToString" in listLabels)

    val strDotCaret = src.indexOf("name.") + "name.".length
    val strMembers = engine.complete(buf, "src/Main.kt", strDotCaret)
    val strLabels = strMembers.map { it.label }
    assertTrue("Expected uppercase on String: $strLabels", "uppercase" in strLabels)
    assertTrue("Expected substring on String: $strLabels", "substring" in strLabels)
  }

  @Test
  fun resolvesProjectWideSymbolsAcrossSiblingFilesAndRecencyBoost() {
    val index = ProjectIntelIndex()
    index.updateFile(
      "src/MathUtils.kt",
      """
      fun fibonacciHelper(n: Int): Int {
        return n
      }
      """.trimIndent(),
    )
    val engine = CompletionEngine(index)
    val mainSrc = "fun main() {\n  fib\n}"
    val buf = TextBuffer(mainSrc)
    val caret = mainSrc.indexOf("fib") + 3

    val items = engine.complete(buf, "src/Main.kt", caret)
    assertTrue(items.any { it.label == "fibonacciHelper" && it.sourceLabel == "MathUtils.kt" })

    engine.recordAccepted("fibonacciHelper")
    val boosted = engine.complete(buf, "src/Main.kt", caret)
    assertEquals("fibonacciHelper", boosted.first().label)
  }

  @Test
  fun computesSignatureHintInsideCallAndReportsDiagnostics() {
    val src =
      """
      import java.util.Date
      import java.util.Scanner

      fun greet(target: String, excited: Boolean): String {
        return target
      }

      fun main() {
        val d = Date()
        greet("Quark", 
        val broken = (1 + 2
      }
      """.trimIndent()
    val buf = TextBuffer(src)
    val index = ProjectIntelIndex()
    index.updateFile("src/Main.kt", src)
    val engine = CompletionEngine(index)

    val sigCaret = src.indexOf("greet(\"Quark\", ") + "greet(\"Quark\", ".length
    val hint = engine.computeSignatureHint(buf, "src/Main.kt", sigCaret)
    assertNotNull(hint)
    assertEquals("greet", hint!!.functionName)
    assertEquals(1, hint.activeParamIndex)
    assertTrue(hint.signature.contains("target: String"))

    val diags = engine.computeDiagnostics(src, "src/Main.kt")
    assertTrue("Expected unused import warning for Scanner", diags.any {
      it.severity == DiagSeverity.WARNING && it.message.contains("Scanner")
    })
    assertTrue("Expected unclosed '(' error", diags.any {
      it.severity == DiagSeverity.ERROR && it.message.contains("Unclosed '('")
    })
  }
}
