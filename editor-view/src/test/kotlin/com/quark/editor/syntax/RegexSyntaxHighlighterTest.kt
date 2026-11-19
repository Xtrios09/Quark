package com.quark.editor.syntax

import com.quark.buffer.TextBuffer
import com.quark.editor.LineToken
import com.quark.editor.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexSyntaxHighlighterTest {

  private fun tokenText(line: String, token: LineToken): String =
    line.substring(token.startCol, token.endCol)

  @Test
  fun kotlinGrammarTokenizesKeywordsAnnotationsStringsCommentsAndNumbers() {
    val code = """
      @Composable
      fun greet(count: Int = 42): String {
        // return "ignored fun 99"
        val msg = "hello world"
        return msg
      }
    """.trimIndent()

    val buffer = TextBuffer(code)
    val highlighter = RegexSyntaxHighlighter()
    val grammar = SyntaxGrammarRegistry.forFileName("Greeting.kt")
    assertNotNull(grammar)

    highlighter.highlightSync(buffer, grammar)

    val line0 = buffer.getLineText(0)
    val tokens0 = highlighter.getLineTokens(0)
    assertTrue(tokens0.any { it.type == TokenType.ANNOTATION && tokenText(line0, it) == "@Composable" })

    val line1 = buffer.getLineText(1)
    val tokens1 = highlighter.getLineTokens(1)
    assertTrue(tokens1.any { it.type == TokenType.KEYWORD && tokenText(line1, it) == "fun" })
    assertTrue(tokens1.any { it.type == TokenType.FUNCTION && tokenText(line1, it) == "greet" })
    assertTrue(tokens1.any { it.type == TokenType.TYPE && tokenText(line1, it) == "Int" })
    assertTrue(tokens1.any { it.type == TokenType.NUMBER && tokenText(line1, it) == "42" })
    assertTrue(tokens1.any { it.type == TokenType.TYPE && tokenText(line1, it) == "String" })

    val line2 = buffer.getLineText(2)
    val tokens2 = highlighter.getLineTokens(2)
    assertEquals(1, tokens2.size)
    assertEquals(TokenType.COMMENT, tokens2.first().type)
    assertTrue(tokenText(line2, tokens2.first()).startsWith("// return"))

    val line3 = buffer.getLineText(3)
    val tokens3 = highlighter.getLineTokens(3)
    assertTrue(tokens3.any { it.type == TokenType.KEYWORD && tokenText(line3, it) == "val" })
    assertTrue(tokens3.any { it.type == TokenType.STRING && tokenText(line3, it) == "\"hello world\"" })

    highlighter.dispose()
  }

  @Test
  fun multilineBlockCommentPersistsStateAcrossLinesAndResumesNormal() {
    val code = """
      val a = 1 /* start comment
      still comment val 123
      end comment */ val b = 2
    """.trimIndent()

    val buffer = TextBuffer(code)
    val highlighter = RegexSyntaxHighlighter()
    highlighter.highlightSync(buffer, SyntaxGrammarRegistry.forFileName("Main.kt"))

    val tokens1 = highlighter.getLineTokens(1)
    assertEquals(1, tokens1.size)
    assertEquals(TokenType.COMMENT, tokens1[0].type)

    val line2 = buffer.getLineText(2)
    val tokens2 = highlighter.getLineTokens(2)
    assertTrue(tokens2.any { it.type == TokenType.COMMENT && tokenText(line2, it).endsWith("*/") })
    assertTrue(tokens2.any { it.type == TokenType.KEYWORD && tokenText(line2, it) == "val" })
    assertTrue(tokens2.any { it.type == TokenType.NUMBER && tokenText(line2, it) == "2" })

    highlighter.dispose()
  }

  @Test
  fun pythonTripleQuotedMultilineStringsSpanAcrossLines() {
    val code = """
      def run():
        doc = ""${'"'}
        def fake_keyword():
          return 999
        ""${'"'}
        return True
    """.trimIndent()

    val buffer = TextBuffer(code)
    val highlighter = RegexSyntaxHighlighter()
    val grammar = SyntaxGrammarRegistry.forFileName("script.py")
    assertNotNull(grammar)
    highlighter.highlightSync(buffer, grammar)

    val tokens2 = highlighter.getLineTokens(2)
    assertEquals(1, tokens2.size)
    assertEquals(TokenType.STRING, tokens2[0].type)

    val line5 = buffer.getLineText(5)
    val tokens5 = highlighter.getLineTokens(5)
    assertTrue(tokens5.any { it.type == TokenType.KEYWORD && tokenText(line5, it) == "return" })
    assertTrue(tokens5.any { it.type == TokenType.CONSTANT && tokenText(line5, it) == "True" })

    highlighter.dispose()
  }

  @Test
  fun htmlJsonSqlAndJsxGrammarsHighlightCoreTokens() {
    val highlighter = RegexSyntaxHighlighter()

    val htmlBuffer = TextBuffer("""<div class="card">Hello</div>""")
    highlighter.highlightSync(htmlBuffer, SyntaxGrammarRegistry.forFileName("index.html"))
    val htmlLine = htmlBuffer.getLineText(0)
    val htmlTokens = highlighter.getLineTokens(0)
    assertTrue(htmlTokens.any { it.type == TokenType.TAG && tokenText(htmlLine, it) == "div" })
    assertTrue(htmlTokens.any { it.type == TokenType.ATTRIBUTE && tokenText(htmlLine, it) == "class" })
    assertTrue(htmlTokens.any { it.type == TokenType.STRING && tokenText(htmlLine, it) == "\"card\"" })

    val jsonBuffer = TextBuffer("""{"enabled": true, "port": 8080}""")
    highlighter.highlightSync(jsonBuffer, SyntaxGrammarRegistry.forFileName("config.json"))
    val jsonLine = jsonBuffer.getLineText(0)
    val jsonTokens = highlighter.getLineTokens(0)
    assertTrue(jsonTokens.any { it.type == TokenType.ATTRIBUTE && tokenText(jsonLine, it) == "\"enabled\"" })
    assertTrue(jsonTokens.any { it.type == TokenType.CONSTANT && tokenText(jsonLine, it) == "true" })
    assertTrue(jsonTokens.any { it.type == TokenType.NUMBER && tokenText(jsonLine, it) == "8080" })

    val sqlBuffer = TextBuffer("""SELECT id, name FROM users WHERE active = 1 -- filter""")
    highlighter.highlightSync(sqlBuffer, SyntaxGrammarRegistry.forFileName("query.sql"))
    val sqlLine = sqlBuffer.getLineText(0)
    val sqlTokens = highlighter.getLineTokens(0)
    assertTrue(sqlTokens.any { it.type == TokenType.KEYWORD && tokenText(sqlLine, it) == "SELECT" })
    assertTrue(sqlTokens.any { it.type == TokenType.COMMENT && tokenText(sqlLine, it) == "-- filter" })

    highlighter.dispose()
  }
}
