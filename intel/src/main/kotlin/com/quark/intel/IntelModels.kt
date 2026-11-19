package com.quark.intel

enum class SymbolKind(val badge: String) {
  CLASS("C"),
  INTERFACE("I"),
  ENUM("E"),
  OBJECT("O"),
  FUNCTION("F"),
  METHOD("M"),
  PROPERTY("P"),
  VARIABLE("V"),
  PARAMETER("p"),
  IMPORT("Im"),
  KEYWORD("K"),
  SNIPPET("S"),
  TAG("T"),
  CONSTANT("Co"),
}

enum class DiagSeverity {
  ERROR,
  WARNING,
  INFO,
  HINT,
}

data class IndexedSymbol(
  val name: String,
  val kind: SymbolKind,
  val signature: String,
  val returnOrValueType: String? = null,
  val filePath: String = "",
  val line0Based: Int = 0,
  val col0Based: Int = 0,
  val endCol0Based: Int = 0,
  val scopeDepth: Int = 0,
  val containerName: String? = null,
)

data class ParsedFileSymbols(
  val filePath: String,
  val languageId: String,
  val symbols: List<IndexedSymbol>,
  val imports: List<IndexedImport>,
)

data class IndexedImport(
  val rawLine: String,
  val importedName: String,
  val line0Based: Int,
  val startCol: Int,
  val endCol: Int,
)

data class CompletionItem(
  val id: String,
  val label: String,
  val insertText: String,
  val kind: SymbolKind,
  val detail: String,
  val sourceLabel: String,
  val score: Int,
  val replaceStartOffset: Int,
  val replaceEndOffset: Int,
  val cursorBackstep: Int = 0,
)

data class SignatureHint(
  val functionName: String,
  val signature: String,
  val activeParamIndex: Int,
  val doc: String = "",
)

data class DocumentDiagnostic(
  val line0Based: Int,
  val startCol: Int,
  val endCol: Int,
  val severity: DiagSeverity,
  val message: String,
)
