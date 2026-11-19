package com.quark.git

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.Inflater

class PureGitRepositoryTest {

  private fun createTempDir(): File = Files.createTempDirectory("quark_git_test").toFile()

  private fun inflateGitObject(gitDir: File, sha: String): String {
    val objFile = File(gitDir, "objects/${sha.substring(0, 2)}/${sha.substring(2)}")
    assertTrue("Expected loose git object at ${objFile.absolutePath}", objFile.isFile)
    val compressed = objFile.readBytes()
    val inflater = Inflater()
    inflater.setInput(compressed)
    val bos = ByteArrayOutputStream()
    val buf = ByteArray(1024)
    while (!inflater.finished()) {
      val n = inflater.inflate(buf)
      if (n == 0 && inflater.needsInput()) break
      if (n > 0) bos.write(buf, 0, n)
    }
    inflater.end()
    return bos.toByteArray().toString(Charsets.UTF_8)
  }

  @Test
  fun initStageCommitCreatesAuthenticObjectsAndHistory() = runBlocking {
    val dir = createTempDir()
    try {
      File(dir, ".gitignore").writeText("build/\n*.log\n")
      File(dir, "build").mkdirs()
      File(dir, "build/out.txt").writeText("ignored artifact")
      File(dir, "debug.log").writeText("ignored log")
      File(dir, "Main.kt").writeText("fun main() {\n  println(\"v1\")\n}\n")

      val repo = PureGitRepository(dir)
      assertFalse(repo.isRepository())

      val initRes = repo.init("main")
      assertTrue(initRes.isSuccess)
      assertTrue(repo.isRepository())

      val initialSnap = repo.snapshot()
      assertEquals("main", initialSnap.currentBranch)
      // Only .gitignore and Main.kt should appear; build/out.txt and debug.log must be ignored
      val unstagedPaths = initialSnap.unstagedChanges.map { it.path }.sorted()
      assertEquals(listOf(".gitignore", "Main.kt"), unstagedPaths)

      assertEquals(2, repo.add(".").getOrThrow())
      val stagedSnap = repo.snapshot()
      assertEquals(2, stagedSnap.stagedChanges.size)
      assertTrue(stagedSnap.unstagedChanges.isEmpty())

      val commit = repo.commit("Initial commit", "Ada", "ada@example.com").getOrThrow()
      assertEquals(40, commit.sha.length)
      assertEquals("Initial commit", commit.message)

      // Verify loose SHA-1 zlib-compressed commit object in .git/objects
      val commitRaw = inflateGitObject(File(dir, ".git"), commit.sha)
      assertTrue(commitRaw.startsWith("commit "))
      assertTrue(commitRaw.contains("tree ${commit.treeSha}"))
      assertTrue(commitRaw.contains("Ada <ada@example.com>"))

      val afterCommitSnap = repo.snapshot()
      assertTrue(afterCommitSnap.stagedChanges.isEmpty())
      assertTrue(afterCommitSnap.unstagedChanges.isEmpty())
      assertEquals(1, afterCommitSnap.commits.size)
      assertEquals(commit.sha, afterCommitSnap.headSha)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun diffUnstageDiscardAndBranchCheckoutWorkEndToEnd() = runBlocking {
    val dir = createTempDir()
    try {
      val repo = PureGitRepository(dir)
      repo.init("main").getOrThrow()

      val file = File(dir, "app.txt")
      file.writeText("alpha\nbeta\ngamma\n")
      repo.add("app.txt").getOrThrow()
      val c1 = repo.commit("c1", "Dev", "dev@quark.dev").getOrThrow()

      // Modify line 2 and add line 4
      file.writeText("alpha\nbeta_edited\ngamma\ndelta\n")
      val gutter = repo.workingGutterDiff("app.txt", file.readText())
      assertTrue(gutter.modifiedLines0Based.contains(1))
      assertTrue(gutter.addedLines0Based.contains(3))

      // Stage then unstage
      repo.add("app.txt").getOrThrow()
      assertEquals(1, repo.snapshot().stagedChanges.size)
      repo.unstage("app.txt").getOrThrow()
      assertTrue(repo.snapshot().stagedChanges.isEmpty())
      assertEquals(1, repo.snapshot().unstagedChanges.size)

      // Discard restores committed content
      repo.discard("app.txt").getOrThrow()
      assertEquals("alpha\nbeta\ngamma\n", file.readText())
      assertTrue(repo.snapshot().unstagedChanges.isEmpty())

      // Create & checkout branch, make second commit
      repo.createBranch("feature/terminal", checkoutAfter = true).getOrThrow()
      assertEquals("feature/terminal", repo.snapshot().currentBranch)

      val extra = File(dir, "feature.txt")
      extra.writeText("feature code\n")
      repo.add(".").getOrThrow()
      val c2 = repo.commit("Add feature", "Dev", "dev@quark.dev").getOrThrow()
      assertEquals(c1.sha, c2.parentSha)

      val log = repo.log()
      assertEquals(2, log.size)
      assertEquals(c2.sha, log[0].sha)

      // Checkout main removes feature.txt from working tree
      repo.checkout("main").getOrThrow()
      assertEquals("main", repo.snapshot().currentBranch)
      assertFalse(extra.exists())
      assertTrue(file.exists())
      assertNotNull(repo.snapshot().headSha)
      assertEquals(c1.sha, repo.snapshot().headSha)
    } finally {
      dir.deleteRecursively()
    }
  }
}
