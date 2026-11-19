package com.quark.git

enum class GitChangeType {
  ADDED,
  MODIFIED,
  DELETED,
  UNTRACKED,
}

data class GitFileChange(
  val path: String,
  val type: GitChangeType,
  val staged: Boolean,
)

data class GitCommit(
  val sha: String,
  val parentSha: String?,
  val treeSha: String,
  val message: String,
  val authorName: String,
  val authorEmail: String,
  val timestampSec: Long,
) {
  val shortSha: String get() = sha.take(7)
}

data class GitBranch(
  val name: String,
  val tipSha: String?,
  val isCurrent: Boolean,
)

enum class DiffLineKind {
  HEADER,
  CONTEXT,
  ADDED,
  REMOVED,
}

data class DiffLine(
  val kind: DiffLineKind,
  val oldLineNo: Int?,
  val newLineNo: Int?,
  val text: String,
)

data class FileDiffResult(
  val path: String,
  val lines: List<DiffLine>,
  val unifiedText: String,
  val addedLines0Based: Set<Int>,
  val modifiedLines0Based: Set<Int>,
  val deletedLines0Based: Set<Int>,
)

data class GitRepoSnapshot(
  val isRepository: Boolean = false,
  val isSupportedBackend: Boolean = true,
  val currentBranch: String = "main",
  val headSha: String? = null,
  val stagedChanges: List<GitFileChange> = emptyList(),
  val unstagedChanges: List<GitFileChange> = emptyList(),
  val branches: List<GitBranch> = emptyList(),
  val commits: List<GitCommit> = emptyList(),
  val lastError: String? = null,
)
