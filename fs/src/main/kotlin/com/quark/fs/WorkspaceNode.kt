package com.quark.fs

/** Identifies whether a workspace lives in app-private storage or an external SAF folder. */
enum class StorageBackend {
  APP_PRIVATE,
  SAF_EXTERNAL,
}

/**
 * Immutable descriptor of a project workspace root.
 */
data class WorkspaceRoot(
  val id: String,
  val name: String,
  val rootUriOrPath: String,
  val backend: StorageBackend,
  val lastOpenedMs: Long = System.currentTimeMillis(),
)

/**
 * Immutable virtual file system node representing a file or directory in either app-private
 * storage or a Storage Access Framework (SAF) tree.
 */
data class VfsNode(
  val id: String,
  val name: String,
  val relativePath: String,
  val isDirectory: Boolean,
  val sizeBytes: Long,
  val lastModifiedMs: Long,
  val backend: StorageBackend,
  val absolutePath: String? = null,
  val treeUri: String? = null,
  val documentId: String? = null,
  val parentId: String? = null,
)

/**
 * Flattened row item for the lazy virtualized file tree.
 */
data class VisibleTreeItem(
  val node: VfsNode,
  val depth: Int,
  val isExpanded: Boolean,
  val isLoading: Boolean = false,
)

/**
 * Ranked fuzzy file search result.
 */
data class FuzzyFileMatch(
  val node: VfsNode,
  val score: Int,
  val matchedIndices: IntArray,
)

/** Single line match within a file for global regex search. */
data class GlobalSearchLineMatch(
  val lineNumber1Based: Int,
  val column1Based: Int,
  val linePreview: String,
  val matchStartInPreview: Int,
  val matchEndInPreview: Int,
)

/** Streaming search result grouped per file. */
data class GlobalSearchFileResult(
  val node: VfsNode,
  val matches: List<GlobalSearchLineMatch>,
)

/** Starter project language templates available on the Welcome screen. */
enum class ProjectTemplate(val displayName: String, val extension: String) {
  KOTLIN("Kotlin", "kt"),
  JAVA("Java", "java"),
  JAVASCRIPT("JavaScript", "js"),
  TYPESCRIPT("TypeScript", "ts"),
  PYTHON("Python", "py"),
  C("C", "c"),
  CPP("C++", "cpp"),
  RUST("Rust", "rs"),
  GO("Go", "go"),
  SQL("SQL (SQLite)", "sql"),
  HTML("HTML / Web", "html"),
}
