package com.quark.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.TreeMap
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Pure Kotlin on-device Git engine implementing authentic `.git` loose SHA-1 zlib-compressed
 * objects (`blob`, `tree`, `commit`), branches (`refs/heads`), symbolic `HEAD`, staging index,
 * `.gitignore` filtering, diffs, commits, and branch switching.
 */
class PureGitRepository(val workTree: File) {

  private val gitDir: File get() = File(workTree, ".git")
  private val objectsDir: File get() = File(gitDir, "objects")
  private val headsDir: File get() = File(gitDir, "refs/heads")
  private val headFile: File get() = File(gitDir, "HEAD")
  private val indexFile: File get() = File(gitDir, "quark_index")

  fun isRepository(): Boolean = gitDir.isDirectory && headFile.isFile

  suspend fun init(defaultBranch: String = "main"): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
      if (!workTree.exists()) workTree.mkdirs()
      val reinit = isRepository()
      objectsDir.mkdirs()
      File(objectsDir, "info").mkdirs()
      File(objectsDir, "pack").mkdirs()
      headsDir.mkdirs()
      if (!headFile.exists()) {
        headFile.writeText("ref: refs/heads/$defaultBranch\n", Charsets.UTF_8)
      }
      val cfg = File(gitDir, "config")
      if (!cfg.exists()) {
        cfg.writeText(
          "[core]\n\trepositoryformatversion = 0\n\tfilemode = false\n\tbare = false\n",
          Charsets.UTF_8,
        )
      }
      if (!indexFile.exists()) {
        indexFile.writeText("", Charsets.UTF_8)
      }
      if (reinit) {
        "Reinitialized existing Git repository in ${gitDir.absolutePath}"
      } else {
        "Initialized empty Git repository in ${gitDir.absolutePath}"
      }
    }
  }

  suspend fun snapshot(commitLimit: Int = 40): GitRepoSnapshot = withContext(Dispatchers.IO) {
    if (!workTree.exists() || !isRepository()) {
      return@withContext GitRepoSnapshot(isRepository = false, isSupportedBackend = true)
    }
    try {
      val currentBranch = readCurrentBranchName()
      val headSha = resolveHeadSha()
      val headTree = if (headSha != null) readCommitTreeMap(headSha) else emptyMap()
      val indexMap = readIndexMap()
      val workingFiles = collectWorkingFiles()

      val staged = computeStagedChanges(headTree, indexMap)
      val unstaged = computeUnstagedChanges(indexMap, workingFiles)
      val branches = listBranchesInternal(currentBranch)
      val commits = readCommitHistory(headSha, commitLimit)

      GitRepoSnapshot(
        isRepository = true,
        isSupportedBackend = true,
        currentBranch = currentBranch,
        headSha = headSha,
        stagedChanges = staged,
        unstagedChanges = unstaged,
        branches = branches,
        commits = commits,
      )
    } catch (t: Throwable) {
      GitRepoSnapshot(
        isRepository = true,
        isSupportedBackend = true,
        lastError = t.message ?: "Git error",
      )
    }
  }

  suspend fun add(pathSpec: String): Result<Int> = withContext(Dispatchers.IO) {
    runCatching {
      check(isRepository()) { "Not a git repository (run 'git init' first)." }
      val indexMap = TreeMap<String, String>(readIndexMap())
      val workingFiles = collectWorkingFiles()
      var count = 0

      if (pathSpec == "." || pathSpec == "-A" || pathSpec == "--all") {
        // Update modified & added working files
        for ((rel, file) in workingFiles) {
          val content = file.readBytes()
          val sha = writeGitObject("blob", content)
          if (indexMap[rel] != sha) {
            indexMap[rel] = sha
            count++
          }
        }
        // Record deletions from index
        val deletedKeys = indexMap.keys.filter { it !in workingFiles }
        for (del in deletedKeys) {
          indexMap.remove(del)
          count++
        }
      } else {
        val clean = normalizePath(pathSpec)
        val matchedFiles = workingFiles.filterKeys { it == clean || it.startsWith("$clean/") }
        val matchedIndex = indexMap.keys.filter { it == clean || it.startsWith("$clean/") }
        if (matchedFiles.isEmpty() && matchedIndex.isEmpty()) {
          error("pathspec '$pathSpec' did not match any files")
        }
        for ((rel, file) in matchedFiles) {
          val sha = writeGitObject("blob", file.readBytes())
          indexMap[rel] = sha
          count++
        }
        for (idxPath in matchedIndex) {
          if (idxPath !in workingFiles) {
            indexMap.remove(idxPath)
            count++
          }
        }
      }

      writeIndexMap(indexMap)
      count
    }
  }

  suspend fun unstage(relativePath: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      check(isRepository()) { "Not a git repository." }
      val clean = normalizePath(relativePath)
      val headSha = resolveHeadSha()
      val headTree = if (headSha != null) readCommitTreeMap(headSha) else emptyMap()
      val indexMap = TreeMap<String, String>(readIndexMap())
      val headBlob = headTree[clean]
      if (headBlob != null) {
        indexMap[clean] = headBlob
      } else {
        indexMap.remove(clean)
      }
      writeIndexMap(indexMap)
    }
  }

  suspend fun discard(relativePath: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      check(isRepository()) { "Not a git repository." }
      val clean = normalizePath(relativePath)
      val indexMap = readIndexMap()
      val baseSha = indexMap[clean] ?: resolveHeadSha()?.let { readCommitTreeMap(it)[clean] }
      val targetFile = File(workTree, clean)
      if (baseSha == null) {
        if (targetFile.exists()) targetFile.delete()
      } else {
        val bytes = readBlobBytes(baseSha) ?: error("Missing object $baseSha")
        targetFile.parentFile?.mkdirs()
        targetFile.writeBytes(bytes)
      }
    }
  }

  suspend fun commit(
    message: String,
    authorName: String,
    authorEmail: String,
  ): Result<GitCommit> = withContext(Dispatchers.IO) {
    runCatching {
      check(isRepository()) { "Not a git repository." }
      val trimmedMsg = message.trim()
      require(trimmedMsg.isNotEmpty()) { "Commit message cannot be empty." }

      val indexMap = readIndexMap()
      val headSha = resolveHeadSha()
      val headTree = if (headSha != null) readCommitTreeMap(headSha) else emptyMap()
      val staged = computeStagedChanges(headTree, indexMap)
      require(staged.isNotEmpty()) { "Nothing staged to commit." }

      val treeSha = writeTreeFromPaths(indexMap)
      val timestampSec = System.currentTimeMillis() / 1000L
      val safeName = authorName.ifBlank { "Quark User" }
      val safeEmail = authorEmail.ifBlank { "user@quark.local" }

      val commitBody = buildString {
        append("tree ").append(treeSha).append('\n')
        if (headSha != null) {
          append("parent ").append(headSha).append('\n')
        }
        append("author ").append(safeName).append(" <").append(safeEmail).append("> ")
          .append(timestampSec).append(" +0000\n")
        append("committer ").append(safeName).append(" <").append(safeEmail).append("> ")
          .append(timestampSec).append(" +0000\n\n")
        append(trimmedMsg).append('\n')
      }
      val commitSha = writeGitObject("commit", commitBody.toByteArray(Charsets.UTF_8))

      val branch = readCurrentBranchName()
      val branchRef = File(headsDir, branch)
      branchRef.parentFile?.mkdirs()
      branchRef.writeText("$commitSha\n", Charsets.UTF_8)

      GitCommit(
        sha = commitSha,
        parentSha = headSha,
        treeSha = treeSha,
        message = trimmedMsg,
        authorName = safeName,
        authorEmail = safeEmail,
        timestampSec = timestampSec,
      )
    }
  }

  suspend fun log(limit: Int = 50): List<GitCommit> = withContext(Dispatchers.IO) {
    if (!isRepository()) return@withContext emptyList()
    readCommitHistory(resolveHeadSha(), limit)
  }

  suspend fun diff(relativePath: String? = null, staged: Boolean = false): List<FileDiffResult> =
    withContext(Dispatchers.IO) {
      if (!isRepository()) return@withContext emptyList()
      val headSha = resolveHeadSha()
      val headTree = if (headSha != null) readCommitTreeMap(headSha) else emptyMap()
      val indexMap = readIndexMap()
      val workingFiles = collectWorkingFiles()

      val targets: List<String> =
        if (relativePath != null) {
          listOf(normalizePath(relativePath))
        } else if (staged) {
          computeStagedChanges(headTree, indexMap).map { it.path }
        } else {
          computeUnstagedChanges(indexMap, workingFiles).map { it.path }
        }

      targets.map { p ->
        if (staged) {
          val oldStr = headTree[p]?.let { readBlobBytes(it)?.toString(Charsets.UTF_8) }
          val newStr = indexMap[p]?.let { readBlobBytes(it)?.toString(Charsets.UTF_8) }
          GitDiffEngine.diffText(oldStr, newStr, p)
        } else {
          val oldSha = indexMap[p] ?: headTree[p]
          val oldStr = oldSha?.let { readBlobBytes(it)?.toString(Charsets.UTF_8) }
          val file = File(workTree, p)
          val newStr = if (file.isFile) file.readText(Charsets.UTF_8) else null
          GitDiffEngine.diffText(oldStr, newStr, p)
        }
      }
    }

  suspend fun workingGutterDiff(relativePath: String, currentText: String): FileDiffResult =
    withContext(Dispatchers.IO) {
      val clean = normalizePath(relativePath)
      if (!isRepository()) {
        return@withContext FileDiffResult(clean, emptyList(), "", emptySet(), emptySet(), emptySet())
      }
      val headTree = resolveHeadSha()?.let { readCommitTreeMap(it) } ?: emptyMap()
      val indexMap = readIndexMap()
      val baseSha = indexMap[clean] ?: headTree[clean]
      if (baseSha == null) {
        return@withContext FileDiffResult(clean, emptyList(), "", emptySet(), emptySet(), emptySet())
      }
      val oldStr = readBlobBytes(baseSha)?.toString(Charsets.UTF_8)
      GitDiffEngine.diffText(oldStr, currentText, clean)
    }

  suspend fun createBranch(name: String, checkoutAfter: Boolean = false): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        check(isRepository()) { "Not a git repository." }
        val clean = name.trim().replace(" ", "-")
        require(clean.isNotEmpty() && !clean.startsWith("-") && !clean.contains("..")) {
          "Invalid branch name '$name'"
        }
        val tip = resolveHeadSha() ?: error("Cannot create branch before first commit.")
        val targetRef = File(headsDir, clean)
        require(!targetRef.exists()) { "Branch '$clean' already exists." }
        targetRef.parentFile?.mkdirs()
        targetRef.writeText("$tip\n", Charsets.UTF_8)
        if (checkoutAfter) {
          headFile.writeText("ref: refs/heads/$clean\n", Charsets.UTF_8)
        }
      }
    }

  suspend fun checkout(branchName: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      check(isRepository()) { "Not a git repository." }
      val ref = File(headsDir, branchName.trim())
      require(ref.isFile) { "Branch '${branchName.trim()}' does not exist." }
      val targetSha = ref.readText(Charsets.UTF_8).trim()
      val targetTree = if (targetSha.isNotEmpty()) readCommitTreeMap(targetSha) else emptyMap()

      val currentTree = resolveHeadSha()?.let { readCommitTreeMap(it) } ?: emptyMap()
      // Remove files tracked in current HEAD that don't exist in target branch
      for (oldPath in currentTree.keys) {
        if (oldPath !in targetTree) {
          val f = File(workTree, oldPath)
          if (f.exists()) f.delete()
        }
      }
      // Restore files from target tree
      for ((path, blobSha) in targetTree) {
        val bytes = readBlobBytes(blobSha) ?: continue
        val f = File(workTree, path)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
      }
      writeIndexMap(TreeMap(targetTree))
      headFile.writeText("ref: refs/heads/${branchName.trim()}\n", Charsets.UTF_8)
    }
  }

  // --- Internals: Loose Git Object Store ---

  private fun writeGitObject(type: String, content: ByteArray): String {
    val header = "$type ${content.size}\u0000".toByteArray(Charsets.UTF_8)
    val full = ByteArray(header.size + content.size)
    System.arraycopy(header, 0, full, 0, header.size)
    System.arraycopy(content, 0, full, header.size, content.size)

    val digest = MessageDigest.getInstance("SHA-1").digest(full)
    val hex = digest.joinToString("") { "%02x".format(it) }
    val objFile = File(File(objectsDir, hex.substring(0, 2)), hex.substring(2))
    if (!objFile.exists()) {
      objFile.parentFile?.mkdirs()
      val deflater = Deflater()
      deflater.setInput(full)
      deflater.finish()
      val bos = ByteArrayOutputStream(full.size)
      val buf = ByteArray(4096)
      while (!deflater.finished()) {
        val count = deflater.deflate(buf)
        if (count > 0) bos.write(buf, 0, count)
      }
      deflater.end()
      objFile.writeBytes(bos.toByteArray())
    }
    return hex
  }

  private fun readGitObject(sha: String): Pair<String, ByteArray>? {
    if (sha.length != 40) return null
    val objFile = File(File(objectsDir, sha.substring(0, 2)), sha.substring(2))
    if (!objFile.isFile) return null
    val compressed = objFile.readBytes()
    val inflater = Inflater()
    inflater.setInput(compressed)
    val bos = ByteArrayOutputStream(compressed.size * 2)
    val buf = ByteArray(4096)
    return try {
      while (!inflater.finished()) {
        val n = inflater.inflate(buf)
        if (n == 0 && inflater.needsInput()) break
        if (n > 0) bos.write(buf, 0, n)
      }
      val raw = bos.toByteArray()
      val nullIdx = raw.indexOf(0.toByte())
      if (nullIdx <= 0) return null
      val header = String(raw, 0, nullIdx, Charsets.UTF_8)
      val type = header.substringBefore(' ')
      val body = raw.copyOfRange(nullIdx + 1, raw.size)
      type to body
    } finally {
      inflater.end()
    }
  }

  private fun readBlobBytes(sha: String): ByteArray? {
    val obj = readGitObject(sha) ?: return null
    return if (obj.first == "blob") obj.second else null
  }

  private fun writeTreeFromPaths(files: Map<String, String>): String {
    data class TreeDir(
      val files: TreeMap<String, String> = TreeMap(),
      val dirs: TreeMap<String, TreeDir> = TreeMap(),
    )

    val root = TreeDir()
    for ((rel, sha) in files) {
      val parts = rel.split('/').filter { it.isNotEmpty() }
      if (parts.isEmpty()) continue
      var cur = root
      for (i in 0 until parts.lastIndex) {
        cur = cur.dirs.getOrPut(parts[i]) { TreeDir() }
      }
      cur.files[parts.last()] = sha
    }

    fun writeDir(dir: TreeDir): String {
      data class Entry(val sortKey: String, val mode: String, val name: String, val shaHex: String)
      val entries = ArrayList<Entry>(dir.files.size + dir.dirs.size)
      for ((name, sha) in dir.files) {
        entries.add(Entry(name, "100644", name, sha))
      }
      for ((name, sub) in dir.dirs) {
        val subSha = writeDir(sub)
        entries.add(Entry("$name/", "40000", name, subSha))
      }
      entries.sortBy { it.sortKey }
      val bos = ByteArrayOutputStream()
      for (e in entries) {
        bos.write("${e.mode} ${e.name}".toByteArray(Charsets.UTF_8))
        bos.write(0)
        bos.write(hexToBytes(e.shaHex))
      }
      return writeGitObject("tree", bos.toByteArray())
    }

    return writeDir(root)
  }

  private fun readCommitTreeMap(commitSha: String): Map<String, String> {
    val obj = readGitObject(commitSha) ?: return emptyMap()
    if (obj.first != "commit") return emptyMap()
    val text = obj.second.toString(Charsets.UTF_8)
    val treeSha = text.lineSequence().firstOrNull { it.startsWith("tree ") }?.removePrefix("tree ")?.trim()
      ?: return emptyMap()
    val result = TreeMap<String, String>()
    readTreeRecursive(treeSha, "", result)
    return result
  }

  private fun readTreeRecursive(treeSha: String, prefix: String, out: MutableMap<String, String>) {
    val obj = readGitObject(treeSha) ?: return
    if (obj.first != "tree") return
    val data = obj.second
    var pos = 0
    while (pos < data.size) {
      val spaceIdx = findByte(data, ' '.code.toByte(), pos)
      val nullIdx = findByte(data, 0.toByte(), spaceIdx + 1)
      if (spaceIdx < 0 || nullIdx < 0 || nullIdx + 21 > data.size) break
      val mode = String(data, pos, spaceIdx - pos, Charsets.UTF_8)
      val name = String(data, spaceIdx + 1, nullIdx - spaceIdx - 1, Charsets.UTF_8)
      val shaBytes = data.copyOfRange(nullIdx + 1, nullIdx + 21)
      val shaHex = shaBytes.joinToString("") { "%02x".format(it) }
      val fullPath = if (prefix.isEmpty()) name else "$prefix/$name"
      if (mode == "40000" || mode == "040000") {
        readTreeRecursive(shaHex, fullPath, out)
      } else {
        out[fullPath] = shaHex
      }
      pos = nullIdx + 21
    }
  }

  private fun readCommitHistory(startSha: String?, limit: Int): List<GitCommit> {
    val list = ArrayList<GitCommit>()
    var cur = startSha
    val visited = HashSet<String>()
    while (cur != null && list.size < limit && visited.add(cur)) {
      val commit = parseCommitObject(cur) ?: break
      list.add(commit)
      cur = commit.parentSha
    }
    return list
  }

  private fun parseCommitObject(sha: String): GitCommit? {
    val obj = readGitObject(sha) ?: return null
    if (obj.first != "commit") return null
    val raw = obj.second.toString(Charsets.UTF_8)
    val splitIdx = raw.indexOf("\n\n")
    val headerPart = if (splitIdx >= 0) raw.substring(0, splitIdx) else raw
    val msg = if (splitIdx >= 0) raw.substring(splitIdx + 2).trim() else ""
    var treeSha = ""
    var parentSha: String? = null
    var authorName = "Quark User"
    var authorEmail = "user@quark.local"
    var timestamp = 0L

    for (line in headerPart.lines()) {
      when {
        line.startsWith("tree ") -> treeSha = line.removePrefix("tree ").trim()
        line.startsWith("parent ") -> if (parentSha == null) parentSha = line.removePrefix("parent ").trim()
        line.startsWith("author ") -> {
          val rest = line.removePrefix("author ").trim()
          val lt = rest.indexOf('<')
          val gt = rest.indexOf('>')
          if (lt > 0 && gt > lt) {
            authorName = rest.substring(0, lt).trim()
            authorEmail = rest.substring(lt + 1, gt).trim()
            val tail = rest.substring(gt + 1).trim().split(' ')
            timestamp = tail.firstOrNull()?.toLongOrNull() ?: 0L
          }
        }
      }
    }
    return GitCommit(sha, parentSha, treeSha, msg, authorName, authorEmail, timestamp)
  }

  private fun readCurrentBranchName(): String {
    if (!headFile.isFile) return "main"
    val raw = headFile.readText(Charsets.UTF_8).trim()
    return if (raw.startsWith("ref: refs/heads/")) {
      raw.removePrefix("ref: refs/heads/").trim()
    } else {
      "HEAD (${raw.take(7)})"
    }
  }

  private fun resolveHeadSha(): String? {
    if (!headFile.isFile) return null
    val raw = headFile.readText(Charsets.UTF_8).trim()
    if (raw.startsWith("ref: ")) {
      val refPath = raw.removePrefix("ref: ").trim()
      val refFile = File(gitDir, refPath)
      return if (refFile.isFile) refFile.readText(Charsets.UTF_8).trim().takeIf { it.length == 40 } else null
    }
    return raw.takeIf { it.length == 40 }
  }

  private fun listBranchesInternal(currentBranch: String): List<GitBranch> {
    if (!headsDir.isDirectory) return listOf(GitBranch(currentBranch, null, true))
    val branches = ArrayList<GitBranch>()
    headsDir.walkTopDown().filter { it.isFile }.forEach { f ->
      val rel = f.relativeTo(headsDir).invariantSeparatorsPath
      val tip = f.readText(Charsets.UTF_8).trim().takeIf { it.length == 40 }
      branches.add(GitBranch(rel, tip, rel == currentBranch))
    }
    if (branches.none { it.name == currentBranch }) {
      branches.add(GitBranch(currentBranch, null, true))
    }
    return branches.sortedWith(compareByDescending<GitBranch> { it.isCurrent }.thenBy { it.name })
  }

  private fun readIndexMap(): Map<String, String> {
    if (!indexFile.isFile) return emptyMap()
    val map = TreeMap<String, String>()
    for (line in indexFile.readLines(Charsets.UTF_8)) {
      if (line.isBlank()) continue
      val tab = line.indexOf('\t')
      if (tab == 40) {
        val sha = line.substring(0, 40)
        val path = line.substring(41)
        map[path] = sha
      }
    }
    return map
  }

  private fun writeIndexMap(map: Map<String, String>) {
    val content = map.entries.joinToString("\n") { "${it.value}\t${it.key}" }
    indexFile.writeText(if (content.isEmpty()) "" else "$content\n", Charsets.UTF_8)
  }

  private fun computeStagedChanges(
    headTree: Map<String, String>,
    indexMap: Map<String, String>,
  ): List<GitFileChange> {
    val out = ArrayList<GitFileChange>()
    for ((path, idxSha) in indexMap) {
      val headSha = headTree[path]
      if (headSha == null) {
        out.add(GitFileChange(path, GitChangeType.ADDED, staged = true))
      } else if (headSha != idxSha) {
        out.add(GitFileChange(path, GitChangeType.MODIFIED, staged = true))
      }
    }
    for (path in headTree.keys) {
      if (path !in indexMap) {
        out.add(GitFileChange(path, GitChangeType.DELETED, staged = true))
      }
    }
    return out.sortedBy { it.path }
  }

  private fun computeUnstagedChanges(
    indexMap: Map<String, String>,
    workingFiles: Map<String, File>,
  ): List<GitFileChange> {
    val out = ArrayList<GitFileChange>()
    for ((path, file) in workingFiles) {
      val sha = sha1OfBlob(file.readBytes())
      val idxSha = indexMap[path]
      if (idxSha == null) {
        out.add(GitFileChange(path, GitChangeType.UNTRACKED, staged = false))
      } else if (idxSha != sha) {
        out.add(GitFileChange(path, GitChangeType.MODIFIED, staged = false))
      }
    }
    for (path in indexMap.keys) {
      if (path !in workingFiles) {
        out.add(GitFileChange(path, GitChangeType.DELETED, staged = false))
      }
    }
    return out.sortedBy { it.path }
  }

  private fun collectWorkingFiles(): Map<String, File> {
    val ignoreRules = readGitIgnoreRules()
    val result = TreeMap<String, File>()
    fun walk(dir: File, prefix: String) {
      val kids = dir.listFiles() ?: return
      for (k in kids) {
        val name = k.name
        if (name == ".git" || name == ".editor") continue
        val rel = if (prefix.isEmpty()) name else "$prefix/$name"
        if (isIgnored(rel, k.isDirectory, ignoreRules)) continue
        if (k.isDirectory) {
          walk(k, rel)
        } else if (k.isFile) {
          result[rel] = k
        }
      }
    }
    walk(workTree, "")
    return result
  }

  private fun readGitIgnoreRules(): List<Regex> {
    val gi = File(workTree, ".gitignore")
    if (!gi.isFile) return emptyList()
    return gi.readLines(Charsets.UTF_8).mapNotNull { raw ->
      val line = raw.trim()
      if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
      val pattern = line.removeSuffix("/").removePrefix("/")
      val regexStr = pattern
        .replace(".", "\\.")
        .replace("*", ".*")
        .replace("?", ".")
      if (line.contains('/')) Regex("^$regexStr(/.*)?$") else Regex("(^|.*/)$regexStr(/.*)?$")
    }
  }

  private fun isIgnored(rel: String, isDir: Boolean, rules: List<Regex>): Boolean =
    rules.any { it.containsMatchIn(rel) }

  private fun sha1OfBlob(content: ByteArray): String {
    val header = "blob ${content.size}\u0000".toByteArray(Charsets.UTF_8)
    val md = MessageDigest.getInstance("SHA-1")
    md.update(header)
    md.update(content)
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  private fun normalizePath(p: String): String =
    p.trim().removePrefix("./").removePrefix("/").replace('\\', '/')

  private fun hexToBytes(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
      out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return out
  }

  private fun findByte(data: ByteArray, target: Byte, start: Int): Int {
    for (i in start until data.size) if (data[i] == target) return i
    return -1
  }
}
