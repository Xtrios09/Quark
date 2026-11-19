package com.quark.intel.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceRunnersTest {

  @Test
  fun executesKotlinAppTemplateOnDevice() {
    val templateSource =
      """
      package com.quark.sample

      fun greet(target: String): String {
        return "Hello from Quark, ${'$'}target!"
      }

      fun main() {
        val items = listOf("Editor", "Buffer", "Terminal", "Storage")
        items.forEachIndexed { idx, item ->
          println("${'$'}{idx + 1}. ${'$'}{greet(item)}")
        }
      }
      """.trimIndent()

    val res = OnDeviceKotlinRunner.run("src/Main.kt", templateSource)
    assertEquals("Errors: ${res.errorMessage}", 0, res.exitCode)
    assertEquals(
      listOf(
        "1. Hello from Quark, Editor!",
        "2. Hello from Quark, Buffer!",
        "3. Hello from Quark, Terminal!",
        "4. Hello from Quark, Storage!",
      ),
      res.stdoutLines,
    )
  }

  @Test
  fun executesKotlinMultiFileDataClassRecursionAndControlFlow() {
    val helperSrc =
      """
      data class Task(val title: String, val points: Int)

      fun fib(n: Int): Int {
        if (n <= 1) return n
        return fib(n - 1) + fib(n - 2)
      }
      """.trimIndent()

    val mainSrc =
      """
      fun main() {
        val t = Task("Build", 10)
        println(t.title + ":" + t.points)
        val nums = listOf(1, 2, 3, 4, 5)
        val evens = nums.filter { it % 2 == 0 }.map { it * 10 }
        println(evens.joinToString("-"))
        var acc = 0
        for (i in 1..4) {
          acc += i
        }
        println("sum=" + acc + ",fib6=" + fib(6))
      }
      """.trimIndent()

    val res = OnDeviceKotlinRunner.run(
      entryFileName = "src/Main.kt",
      entrySource = mainSrc,
      siblingSources = mapOf("src/Helpers.kt" to helperSrc),
    )
    assertEquals("Errors: ${res.errorMessage}", 0, res.exitCode)
    assertEquals(
      listOf(
        "Build:10",
        "20-40",
        "sum=10,fib6=8",
      ),
      res.stdoutLines,
    )
  }

  @Test
  fun executesPythonScriptWithFunctionsLoopsAndFStrings() {
    val py =
      """
      def fact(n):
          if n <= 1:
              return 1
          return n * fact(n - 1)

      items = ["a", "b"]
      for idx, ch in enumerate(items):
          print(f"{idx}:{ch.upper()}")
      print("fact5=", fact(5))
      """.trimIndent()

    val res = OnDevicePythonRunner.run("main.py", py)
    assertEquals("Errors: ${res.errorMessage}", 0, res.exitCode)
    assertEquals(
      listOf("0:A", "1:B", "fact5= 120"),
      res.stdoutLines,
    )
  }

  @Test
  fun executesJavaProgramWithoutExternalJavac() {
    val javaSrc =
      """
      public class Main {
        public static int add(int a, int b) {
          return a + b;
        }
        public static void main(String[] args) {
          int total = add(19, 23);
          System.out.println("Total=" + total);
          for (int i = 0; i < 3; i++) {
            System.out.println("step " + i);
          }
        }
      }
      """.trimIndent()

    val res = OnDeviceJavaRunner.run("Main.java", javaSrc)
    assertEquals("Errors: ${res.errorMessage}", 0, res.exitCode)
    assertTrue(res.stdoutLines.contains("Total=42"))
    assertTrue(res.stdoutLines.contains("step 2"))
  }
}
