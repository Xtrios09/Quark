package com.quark.intel

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe project-wide symbol index caching parsed symbols per file path.
 */
class ProjectIntelIndex {

  private val files = ConcurrentHashMap<String, ParsedFileSymbols>()

  fun updateFile(filePath: String, content: String): ParsedFileSymbols {
    val parsed = SymbolParser.parse(content, filePath)
    files[filePath] = parsed
    return parsed
  }

  fun removeFile(filePath: String) {
    files.remove(filePath)
  }

  fun clear() {
    files.clear()
  }

  fun getFileSymbols(filePath: String): ParsedFileSymbols? = files[filePath]

  fun allWorkspaceSymbols(excludeFilePath: String? = null): List<IndexedSymbol> {
    val out = ArrayList<IndexedSymbol>(64)
    for ((path, parsed) in files) {
      if (excludeFilePath != null && path == excludeFilePath) continue
      for (sym in parsed.symbols) {
        if (sym.scopeDepth == 0 || sym.kind == SymbolKind.CLASS || sym.kind == SymbolKind.INTERFACE || sym.kind == SymbolKind.FUNCTION) {
          out.add(sym)
        }
      }
    }
    return out
  }

  fun findDefinition(symbolName: String, currentFilePath: String? = null): IndexedSymbol? {
    if (currentFilePath != null) {
      val local = files[currentFilePath]?.symbols?.firstOrNull { it.name == symbolName }
      if (local != null) return local
    }
    for (parsed in files.values) {
      val match = parsed.symbols.firstOrNull { it.name == symbolName }
      if (match != null) return match
    }
    return null
  }
}
