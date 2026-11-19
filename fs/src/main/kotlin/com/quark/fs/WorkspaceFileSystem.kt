package com.quark.fs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Unified Virtual File System supporting both app-private project directories and external
 * folders granted via Android's Storage Access Framework (SAF).
 */
class WorkspaceFileSystem(private val context: Context) {

  val projectsDir: File by lazy {
    File(context.filesDir, "projects").apply { mkdirs() }
  }

  /** Lists all locally created or imported projects in app-private storage. */
  suspend fun listPrivateProjects(): List<WorkspaceRoot> =
    withContext(Dispatchers.IO) {
      val dirs = projectsDir.listFiles { f -> f.isDirectory } ?: return@withContext emptyList()
      dirs
        .sortedByDescending { it.lastModified() }
        .map { dir ->
          WorkspaceRoot(
            id = "local:${dir.absolutePath}",
            name = dir.name,
            rootUriOrPath = dir.absolutePath,
            backend = StorageBackend.APP_PRIVATE,
            lastOpenedMs = dir.lastModified(),
          )
        }
    }

  /** Creates a new starter project in app-private storage with runnable template files. */
  suspend fun createStarterProject(
    name: String,
    template: ProjectTemplate,
  ): WorkspaceRoot =
    withContext(Dispatchers.IO) {
      val sanitized = name.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "MyProject" }
      var dir = File(projectsDir, sanitized)
      var suffix = 1
      while (dir.exists()) {
        dir = File(projectsDir, "${sanitized}_$suffix")
        suffix++
      }
      dir.mkdirs()

      // Create .editor directory for hackable config
      File(dir, ".editor").mkdirs()
      File(dir, ".editor/settings.json").writeText("{\n  \"tabSize\": 4,\n  \"insertSpaces\": true\n}\n")

      when (template) {
        ProjectTemplate.KOTLIN -> {
          val src = File(dir, "src").apply { mkdirs() }
          File(src, "Main.kt").writeText(
            """
            package app

            fun greet(target: String): String {
                return "Hello from Quark, ${'$'}target!"
            }

            fun main() {
                val items = listOf("Editor", "Buffer", "Terminal")
                items.forEachIndexed { idx, item ->
                    println("${'$'}{idx + 1}. ${'$'}{greet(item)}")
                }
            }
            """.trimIndent() + "\n"
          )
          File(dir, "README.md").writeText("# ${dir.name}\n\nKotlin starter project created in Quark.\n")
        }
        ProjectTemplate.JAVA -> {
          val src = File(dir, "src").apply { mkdirs() }
          File(src, "Main.java").writeText(
            """
            public class Main {
                public static void main(String[] args) {
                    System.out.println("Hello from ${dir.name}!");
                }
            }
            """.trimIndent() + "\n"
          )
        }
        ProjectTemplate.JAVASCRIPT -> {
          File(dir, "index.js").writeText(
            """
            function fibonacci(n) {
              if (n <= 1) return n;
              return fibonacci(n - 1) + fibonacci(n - 2);
            }

            for (let i = 0; i < 10; i++) {
              console.log(`fib(${'$'}{i}) = ${'$'}{fibonacci(i)}`);
            }
            """.trimIndent() + "\n"
          )
          File(dir, "package.json").writeText(
            "{\n  \"name\": \"${dir.name.lowercase()}\",\n  \"version\": \"1.0.0\",\n  \"main\": \"index.js\"\n}\n"
          )
        }
        ProjectTemplate.TYPESCRIPT -> {
          File(dir, "main.ts").writeText(
            """
            interface Task {
              id: number;
              title: string;
              done: boolean;
            }

            function summarize(tasks: Task[]): string {
              const completed = tasks.filter(t => t.done).length;
              return `${'$'}{completed}/${'$'}{tasks.length} tasks done`;
            }

            console.log(summarize([{ id: 1, title: "Explore Quark", done: true }]));
            """.trimIndent() + "\n"
          )
        }
        ProjectTemplate.PYTHON -> {
          File(dir, "main.py").writeText(
            """
            def greet(name: str) -> str:
                return f"Hello, {name}!"

            if __name__ == "__main__":
                for lang in ["Python", "Kotlin", "Shell"]:
                    print(greet(lang))
            """.trimIndent() + "\n"
          )
        }
        ProjectTemplate.C -> {
          val src = File(dir, "src").apply { mkdirs() }
          File(src, "main.c").writeText(
            """
            #include <stdio.h>

            int fib(int n) {
                if (n <= 1) return n;
                return fib(n - 1) + fib(n - 2);
            }

            int main() {
                printf("Hello from %s (C)!\n", "${dir.name}");
                for (int i = 0; i < 6; i++) {
                    printf("fib(%d) = %d\n", i, fib(i));
                }
                return 0;
            }
            """.trimIndent() + "\n"
          )
        }
        ProjectTemplate.CPP -> {
          val src = File(dir, "src").apply { mkdirs() }
          File(src, "main.cpp").writeText(
            """
            #include <iostream>
            #include <vector>

            int main() {
                std::cout << "Hello from ${dir.name} (C++)!" << std::endl;
                std::vector<int> nums = {10, 20, 30};
                int sum = 0;
                for (auto x : nums) {
                    sum += x;
                }
                std::cout << "Sum = " << sum << std::endl;
                return 0;
            }
            """.trimIndent() + "\n"
          )
        }
        ProjectTemplate.RUST -> {
          val src = File(dir, "src").apply { mkdirs() }
          File(src, "main.rs").writeText(
            """
            fn square(x: i32) -> i32 {
                x * x
            }

            fn main() {
                println!("Hello from ${dir.name} (Rust)!");
                for i in 1..=5 {
                    println!("{}^2 = {}", i, square(i));
                }
            }
            """.trimIndent() + "\n"
          )
        }
        ProjectTemplate.GO -> {
          File(dir, "main.go").writeText(
            """
            package main

            import "fmt"

            func greet(name string) string {
                return "Hello from " + name + " (Go)!"
            }

            func main() {
                fmt.Println(greet("${dir.name}"))
                for i := 1; i <= 3; i++ {
                    fmt.Printf("step %d\n", i)
                }
            }
            """.trimIndent() + "\n"
          )
        }
        ProjectTemplate.SQL -> {
          File(dir, "queries.sql").writeText(
            """
            CREATE TABLE projects (id INTEGER PRIMARY KEY, name TEXT, lang TEXT);
            INSERT INTO projects VALUES (1, '${dir.name}', 'SQL');
            INSERT INTO projects VALUES (2, 'Quark', 'Kotlin');
            SELECT id, name, lang FROM projects ORDER BY id;
            """.trimIndent() + "\n"
          )
        }
        ProjectTemplate.HTML -> {
          File(dir, "index.html").writeText(
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <title>${dir.name}</title>
              <link rel="stylesheet" href="style.css" />
            </head>
            <body>
              <main class="card">
                <h1>${dir.name}</h1>
                <p id="status">Ready to edit and preview.</p>
              </main>
              <script src="app.js"></script>
            </body>
            </html>
            """.trimIndent() + "\n"
          )
          File(dir, "style.css").writeText(
            """
            body {
              font-family: system-ui, sans-serif;
              background: #1e222a;
              color: #e6edf3;
              display: grid;
              place-items: center;
              min-height: 100vh;
              margin: 0;
            }
            .card {
              padding: 2rem;
              border-radius: 12px;
              background: #282c34;
              border: 1px solid #3e4451;
            }
            """.trimIndent() + "\n"
          )
          File(dir, "app.js").writeText(
            "console.log('${dir.name} loaded');\n"
          )
        }
      }

      WorkspaceRoot(
        id = "local:${dir.absolutePath}",
        name = dir.name,
        rootUriOrPath = dir.absolutePath,
        backend = StorageBackend.APP_PRIVATE,
        lastOpenedMs = System.currentTimeMillis(),
      )
    }

  /** Persists SAF tree permissions and wraps the URI as a [WorkspaceRoot]. */
  fun openSafWorkspace(treeUri: Uri): WorkspaceRoot {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    try {
      context.contentResolver.takePersistableUriPermission(treeUri, flags)
    } catch (_: SecurityException) {
      // Read-only grant fallback
      try {
        context.contentResolver.takePersistableUriPermission(
          treeUri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
      } catch (_: SecurityException) {}
    }
    val docId = DocumentsContract.getTreeDocumentId(treeUri)
    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    var displayName = docId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "External" }
    context.contentResolver.query(
      docUri,
      arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
      null,
      null,
      null,
    )?.use { cursor ->
      if (cursor.moveToFirst()) {
        val name = cursor.getString(0)
        if (!name.isNullOrBlank()) displayName = name
      }
    }
    return WorkspaceRoot(
      id = "saf:$treeUri",
      name = displayName,
      rootUriOrPath = treeUri.toString(),
      backend = StorageBackend.SAF_EXTERNAL,
    )
  }

  /** Returns the root [VfsNode] corresponding to [root]. */
  fun rootNode(root: WorkspaceRoot): VfsNode {
    return if (root.backend == StorageBackend.APP_PRIVATE) {
      val f = File(root.rootUriOrPath)
      VfsNode(
        id = f.absolutePath,
        name = root.name,
        relativePath = "",
        isDirectory = true,
        sizeBytes = 0L,
        lastModifiedMs = f.lastModified(),
        backend = StorageBackend.APP_PRIVATE,
        absolutePath = f.absolutePath,
      )
    } else {
      val uri = Uri.parse(root.rootUriOrPath)
      val docId = DocumentsContract.getTreeDocumentId(uri)
      VfsNode(
        id = "$uri|$docId",
        name = root.name,
        relativePath = "",
        isDirectory = true,
        sizeBytes = 0L,
        lastModifiedMs = 0L,
        backend = StorageBackend.SAF_EXTERNAL,
        treeUri = root.rootUriOrPath,
        documentId = docId,
      )
    }
  }

  /** Lazily lists direct children of [directoryNode], sorted directories-first then alphabetically. */
  suspend fun listChildren(directoryNode: VfsNode): List<VfsNode> =
    withContext(Dispatchers.IO) {
      if (!directoryNode.isDirectory) return@withContext emptyList()
      if (directoryNode.backend == StorageBackend.APP_PRIVATE) {
        val dir = File(directoryNode.absolutePath ?: return@withContext emptyList())
        val children = dir.listFiles() ?: return@withContext emptyList()
        children
          .map { f ->
            val rel = if (directoryNode.relativePath.isEmpty()) f.name else "${directoryNode.relativePath}/${f.name}"
            VfsNode(
              id = f.absolutePath,
              name = f.name,
              relativePath = rel,
              isDirectory = f.isDirectory,
              sizeBytes = if (f.isFile) f.length() else 0L,
              lastModifiedMs = f.lastModified(),
              backend = StorageBackend.APP_PRIVATE,
              absolutePath = f.absolutePath,
              parentId = directoryNode.id,
            )
          }
          .sortedWith(compareBy<VfsNode>({ !it.isDirectory }, { it.name.lowercase() }))
      } else {
        val treeUri = Uri.parse(directoryNode.treeUri ?: return@withContext emptyList())
        val parentDocId = directoryNode.documentId ?: return@withContext emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
          DocumentsContract.Document.COLUMN_DOCUMENT_ID,
          DocumentsContract.Document.COLUMN_DISPLAY_NAME,
          DocumentsContract.Document.COLUMN_MIME_TYPE,
          DocumentsContract.Document.COLUMN_SIZE,
          DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<VfsNode>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
          val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
          val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
          val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
          val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
          val modCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
          while (c.moveToNext()) {
            val docId = c.getString(idCol) ?: continue
            val name = c.getString(nameCol) ?: "untitled"
            val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
            val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
            val size = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L
            val mod = if (modCol >= 0 && !c.isNull(modCol)) c.getLong(modCol) else 0L
            val rel = if (directoryNode.relativePath.isEmpty()) name else "${directoryNode.relativePath}/$name"
            result.add(
              VfsNode(
                id = "$treeUri|$docId",
                name = name,
                relativePath = rel,
                isDirectory = isDir,
                sizeBytes = size,
                lastModifiedMs = mod,
                backend = StorageBackend.SAF_EXTERNAL,
                treeUri = directoryNode.treeUri,
                documentId = docId,
                parentId = directoryNode.id,
              )
            )
          }
        }
        result.sortedWith(compareBy<VfsNode>({ !it.isDirectory }, { it.name.lowercase() }))
      }
    }

  /** Reads the full UTF-8 content of [fileNode]. */
  suspend fun readText(fileNode: VfsNode): String =
    withContext(Dispatchers.IO) {
      if (fileNode.backend == StorageBackend.APP_PRIVATE) {
        val f = File(fileNode.absolutePath ?: error("Missing path"))
        f.readText(Charsets.UTF_8)
      } else {
        val treeUri = Uri.parse(fileNode.treeUri ?: error("Missing treeUri"))
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, fileNode.documentId!!)
        context.contentResolver.openInputStream(docUri)?.use { input ->
          input.bufferedReader(Charsets.UTF_8).readText()
        } ?: error("Unable to open input stream for ${fileNode.name}")
      }
    }

  /** Writes [content] atomically (on local FS) or truncated stream (on SAF) to [fileNode]. */
  suspend fun writeText(fileNode: VfsNode, content: String): Boolean =
    withContext(Dispatchers.IO) {
      if (fileNode.backend == StorageBackend.APP_PRIVATE) {
        val target = File(fileNode.absolutePath ?: return@withContext false)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
          target.writeText(content, Charsets.UTF_8)
          tmp.delete()
        }
        true
      } else {
        val treeUri = Uri.parse(fileNode.treeUri ?: return@withContext false)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, fileNode.documentId!!)
        context.contentResolver.openOutputStream(docUri, "wt")?.use { out ->
          out.write(content.toByteArray(Charsets.UTF_8))
          out.flush()
          true
        } ?: false
      }
    }

  private fun parseNestedSegments(rawName: String): List<String>? {
    val segments = rawName.trim().replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
    if (segments.isEmpty() || segments.any { it == ".." }) return null
    return segments
  }

  private suspend fun ensureSingleDirectory(parentDir: VfsNode, dirName: String): VfsNode? =
    withContext(Dispatchers.IO) {
      if (!parentDir.isDirectory || dirName.isBlank()) return@withContext null
      if (parentDir.backend == StorageBackend.APP_PRIVATE) {
        val dir = File(parentDir.absolutePath!!, dirName)
        if (dir.exists()) {
          if (!dir.isDirectory) return@withContext null
        } else if (!dir.mkdirs()) {
          return@withContext null
        }
        val rel = if (parentDir.relativePath.isEmpty()) dirName else "${parentDir.relativePath}/$dirName"
        VfsNode(
          id = dir.absolutePath,
          name = dirName,
          relativePath = rel,
          isDirectory = true,
          sizeBytes = 0L,
          lastModifiedMs = dir.lastModified(),
          backend = StorageBackend.APP_PRIVATE,
          absolutePath = dir.absolutePath,
          parentId = parentDir.id,
        )
      } else {
        val existing = listChildren(parentDir).firstOrNull { it.isDirectory && it.name == dirName }
        if (existing != null) return@withContext existing
        val treeUri = Uri.parse(parentDir.treeUri!!)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDir.documentId!!)
        val createdUri =
          DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            dirName,
          ) ?: return@withContext null
        val newDocId = DocumentsContract.getDocumentId(createdUri)
        val rel = if (parentDir.relativePath.isEmpty()) dirName else "${parentDir.relativePath}/$dirName"
        VfsNode(
          id = "$treeUri|$newDocId",
          name = dirName,
          relativePath = rel,
          isDirectory = true,
          sizeBytes = 0L,
          lastModifiedMs = System.currentTimeMillis(),
          backend = StorageBackend.SAF_EXTERNAL,
          treeUri = parentDir.treeUri,
          documentId = newDocId,
          parentId = parentDir.id,
        )
      }
    }

  /** Creates a new empty file inside [parentDir], automatically creating nested folders when [name] contains '/'. */
  suspend fun createFile(parentDir: VfsNode, name: String): VfsNode? =
    withContext(Dispatchers.IO) {
      if (!parentDir.isDirectory) return@withContext null
      val segments = parseNestedSegments(name) ?: return@withContext null
      var curParent = parentDir
      for (i in 0 until segments.lastIndex) {
        curParent = ensureSingleDirectory(curParent, segments[i]) ?: return@withContext null
      }
      val leafName = segments.last()
      if (curParent.backend == StorageBackend.APP_PRIVATE) {
        val file = File(curParent.absolutePath!!, leafName)
        if (file.exists()) return@withContext null
        file.parentFile?.mkdirs()
        file.createNewFile()
        val rel = if (curParent.relativePath.isEmpty()) leafName else "${curParent.relativePath}/$leafName"
        VfsNode(
          id = file.absolutePath,
          name = leafName,
          relativePath = rel,
          isDirectory = false,
          sizeBytes = 0L,
          lastModifiedMs = file.lastModified(),
          backend = StorageBackend.APP_PRIVATE,
          absolutePath = file.absolutePath,
          parentId = curParent.id,
        )
      } else {
        val treeUri = Uri.parse(curParent.treeUri!!)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, curParent.documentId!!)
        val createdUri = DocumentsContract.createDocument(context.contentResolver, parentUri, "text/plain", leafName)
          ?: return@withContext null
        val newDocId = DocumentsContract.getDocumentId(createdUri)
        val rel = if (curParent.relativePath.isEmpty()) leafName else "${curParent.relativePath}/$leafName"
        VfsNode(
          id = "$treeUri|$newDocId",
          name = leafName,
          relativePath = rel,
          isDirectory = false,
          sizeBytes = 0L,
          lastModifiedMs = System.currentTimeMillis(),
          backend = StorageBackend.SAF_EXTERNAL,
          treeUri = curParent.treeUri,
          documentId = newDocId,
          parentId = curParent.id,
        )
      }
    }

  /** Creates a new directory inside [parentDir], supporting nested paths like `a/b/c`. */
  suspend fun createDirectory(parentDir: VfsNode, name: String): VfsNode? =
    withContext(Dispatchers.IO) {
      if (!parentDir.isDirectory) return@withContext null
      val segments = parseNestedSegments(name) ?: return@withContext null
      var curParent = parentDir
      for (i in 0 until segments.lastIndex) {
        curParent = ensureSingleDirectory(curParent, segments[i]) ?: return@withContext null
      }
      val leafName = segments.last()
      if (curParent.backend == StorageBackend.APP_PRIVATE) {
        val dir = File(curParent.absolutePath!!, leafName)
        if (dir.exists()) return@withContext null
        if (!dir.mkdirs()) return@withContext null
        val rel = if (curParent.relativePath.isEmpty()) leafName else "${curParent.relativePath}/$leafName"
        VfsNode(
          id = dir.absolutePath,
          name = leafName,
          relativePath = rel,
          isDirectory = true,
          sizeBytes = 0L,
          lastModifiedMs = dir.lastModified(),
          backend = StorageBackend.APP_PRIVATE,
          absolutePath = dir.absolutePath,
          parentId = curParent.id,
        )
      } else {
        if (listChildren(curParent).any { it.name == leafName }) return@withContext null
        ensureSingleDirectory(curParent, leafName)
      }
    }

  /** Renames [node] to [newName]. */
  suspend fun rename(node: VfsNode, newName: String): VfsNode? =
    withContext(Dispatchers.IO) {
      if (newName.isBlank()) return@withContext null
      if (node.backend == StorageBackend.APP_PRIVATE) {
        val src = File(node.absolutePath ?: return@withContext null)
        val dst = File(src.parentFile, newName)
        if (dst.exists() || !src.renameTo(dst)) return@withContext null
        val parentRel = node.relativePath.substringBeforeLast('/', "")
        val newRel = if (parentRel.isEmpty()) newName else "$parentRel/$newName"
        node.copy(
          id = dst.absolutePath,
          name = newName,
          relativePath = newRel,
          absolutePath = dst.absolutePath,
          lastModifiedMs = dst.lastModified(),
        )
      } else {
        val treeUri = Uri.parse(node.treeUri ?: return@withContext null)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId!!)
        val renamedUri = DocumentsContract.renameDocument(context.contentResolver, docUri, newName)
          ?: return@withContext null
        val newDocId = DocumentsContract.getDocumentId(renamedUri)
        val parentRel = node.relativePath.substringBeforeLast('/', "")
        val newRel = if (parentRel.isEmpty()) newName else "$parentRel/$newName"
        node.copy(
          id = "$treeUri|$newDocId",
          name = newName,
          relativePath = newRel,
          documentId = newDocId,
        )
      }
    }

  /** Deletes [node] (recursively if directory). */
  suspend fun delete(node: VfsNode): Boolean =
    withContext(Dispatchers.IO) {
      if (node.backend == StorageBackend.APP_PRIVATE) {
        val f = File(node.absolutePath ?: return@withContext false)
        f.deleteRecursively()
      } else {
        val treeUri = Uri.parse(node.treeUri ?: return@withContext false)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId!!)
        DocumentsContract.deleteDocument(context.contentResolver, docUri)
      }
    }

  /** Copies [node] (recursively if directory) into [targetParentDir]. */
  suspend fun copy(node: VfsNode, targetParentDir: VfsNode): VfsNode? =
    withContext(Dispatchers.IO) {
      if (!targetParentDir.isDirectory) return@withContext null
      if (!node.isDirectory) {
        val content = readText(node)
        val created = createFile(targetParentDir, node.name) ?: return@withContext null
        writeText(created, content)
        created
      } else {
        val createdDir = createDirectory(targetParentDir, node.name) ?: return@withContext null
        for (child in listChildren(node)) {
          copy(child, createdDir)
        }
        createdDir
      }
    }

  /** Returns true if [targetDir] is the same node as [source] or nested inside [source]. */
  fun isSameOrDescendant(source: VfsNode, targetDir: VfsNode): Boolean {
    if (source.id == targetDir.id) return true
    if (!source.isDirectory) return false
    if (source.relativePath.isNotEmpty() &&
      (targetDir.relativePath == source.relativePath || targetDir.relativePath.startsWith("${source.relativePath}/"))
    ) {
      return true
    }
    if (source.absolutePath != null && targetDir.absolutePath != null) {
      val sPath = runCatching { File(source.absolutePath).canonicalPath }.getOrNull() ?: source.absolutePath
      val tPath = runCatching { File(targetDir.absolutePath).canonicalPath }.getOrNull() ?: targetDir.absolutePath
      if (tPath == sPath || tPath.startsWith("$sPath${File.separator}")) return true
    }
    return false
  }

  /** Moves [node] into [targetParentDir], guarding against same-parent, name collisions, and cycles. */
  suspend fun move(node: VfsNode, targetParentDir: VfsNode): VfsNode? =
    withContext(Dispatchers.IO) {
      if (!targetParentDir.isDirectory) return@withContext null
      if (node.parentId == targetParentDir.id) return@withContext null
      if (isSameOrDescendant(node, targetParentDir)) return@withContext null
      if (node.backend == StorageBackend.APP_PRIVATE && targetParentDir.backend == StorageBackend.APP_PRIVATE) {
        val src = File(node.absolutePath ?: return@withContext null)
        val dst = File(targetParentDir.absolutePath!!, node.name)
        if (dst.exists()) return@withContext null
        if (src.renameTo(dst)) {
          val rel = if (targetParentDir.relativePath.isEmpty()) node.name else "${targetParentDir.relativePath}/${node.name}"
          return@withContext node.copy(
            id = dst.absolutePath,
            relativePath = rel,
            absolutePath = dst.absolutePath,
            parentId = targetParentDir.id,
          )
        }
        return@withContext null
      }
      if (listChildren(targetParentDir).any { it.name == node.name }) return@withContext null
      val copied = copy(node, targetParentDir) ?: return@withContext null
      if (delete(node)) copied else null
    }

  /** Collects the workspace root plus all subdirectories sorted by relative path. */
  suspend fun collectAllDirectories(root: WorkspaceRoot): List<VfsNode> =
    withContext(Dispatchers.IO) {
      val rootN = rootNode(root)
      val out = ArrayList<VfsNode>(64)
      out.add(rootN)
      val queue = ArrayDeque<VfsNode>()
      queue.addLast(rootN)
      while (queue.isNotEmpty() && out.size < 2000) {
        val dir = queue.removeFirst()
        for (child in listChildren(dir)) {
          if (child.isDirectory && child.name !in IGNORED_DIRS) {
            out.add(child)
            queue.addLast(child)
          }
        }
      }
      out
    }

  /** Traverses [root] skipping ignored build/vcs folders to collect indexable files. */
  suspend fun collectAllFiles(root: WorkspaceRoot, maxFiles: Int = 15_000): List<VfsNode> =
    withContext(Dispatchers.IO) {
      val collected = ArrayList<VfsNode>(256)
      val queue = ArrayDeque<VfsNode>()
      queue.addLast(rootNode(root))
      while (queue.isNotEmpty() && collected.size < maxFiles) {
        val dir = queue.removeFirst()
        val children = listChildren(dir)
        for (child in children) {
          if (child.isDirectory) {
            if (child.name !in IGNORED_DIRS) {
              queue.addLast(child)
            }
          } else {
            collected.add(child)
            if (collected.size >= maxFiles) break
          }
        }
      }
      collected
    }

  /** Fuzzy searches [files] by relative path and ranks best matches first. */
  fun fuzzySearchFiles(files: List<VfsNode>, query: String, limit: Int = 60): List<FuzzyFileMatch> {
    if (query.isBlank()) {
      return files.take(limit).map { FuzzyFileMatch(it, 0, IntArray(0)) }
    }
    val matches = ArrayList<FuzzyFileMatch>(limit)
    for (node in files) {
      val res = FuzzyMatcher.match(query, node.relativePath) ?: continue
      matches.add(FuzzyFileMatch(node, res.score, res.matchedIndices))
    }
    matches.sortByDescending { it.score }
    return if (matches.size > limit) matches.subList(0, limit) else matches
  }

  /**
   * Parallel streaming regex/literal search across all text files in [root].
   * Emits [GlobalSearchFileResult] as each file finishes matching.
   */
  fun globalSearchStream(
    root: WorkspaceRoot,
    query: String,
    isRegex: Boolean,
    caseSensitive: Boolean,
    pathFilter: String = "",
  ): Flow<GlobalSearchFileResult> =
    channelFlow {
      if (query.isEmpty()) return@channelFlow
      val regex =
        try {
          val expr = if (isRegex) query else Regex.escape(query)
          val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
          Regex(expr, opts)
        } catch (_: Throwable) {
          return@channelFlow
        }

      val files = collectAllFiles(root)
      val filterLower = pathFilter.trim().lowercase()
      val candidates =
        files.filter { node ->
          node.sizeBytes <= MAX_SEARCH_FILE_BYTES &&
            !isLikelyBinaryExtension(node.name) &&
            (filterLower.isEmpty() || node.relativePath.lowercase().contains(filterLower))
        }

      val concurrency = Semaphore(6)
      coroutineScope {
        candidates.map { node ->
          async {
            concurrency.withPermit {
              val matches = searchSingleFile(node, regex)
              if (matches.isNotEmpty()) {
                send(GlobalSearchFileResult(node, matches))
              }
            }
          }
        }.awaitAll()
      }
    }.flowOn(Dispatchers.IO)

  private suspend fun searchSingleFile(
    node: VfsNode,
    regex: Regex,
    maxMatchesPerFile: Int = 200,
  ): List<GlobalSearchLineMatch> {
    val text =
      try {
        readText(node)
      } catch (_: Throwable) {
        return emptyList()
      }
    if (text.indexOf('\u0000') >= 0) return emptyList() // Binary guard
    val matches = ArrayList<GlobalSearchLineMatch>()
    val lines = text.lineSequence()
    var lineNum = 1
    for (line in lines) {
      val m = regex.find(line)
      if (m != null) {
        val trimmed = if (line.length > 160) line.substring(0, 160) else line
        val startInPrev = m.range.first.coerceAtMost(trimmed.length)
        val endInPrev = (m.range.last + 1).coerceIn(startInPrev, trimmed.length)
        matches.add(
          GlobalSearchLineMatch(
            lineNumber1Based = lineNum,
            column1Based = m.range.first + 1,
            linePreview = trimmed,
            matchStartInPreview = startInPrev,
            matchEndInPreview = endInPrev,
          )
        )
        if (matches.size >= maxMatchesPerFile) break
      }
      lineNum++
    }
    return matches
  }

  companion object {
    private const val MAX_SEARCH_FILE_BYTES = 2L * 1024L * 1024L
    private val IGNORED_DIRS = setOf(".git", "build", ".gradle", "node_modules", ".idea", "__pycache__")
    private val BINARY_EXTS = setOf(
      "png", "jpg", "jpeg", "webp", "gif", "ico", "pdf", "zip", "jar", "apk", "aab", "so", "ttf", "otf", "woff", "woff2"
    )

    fun isLikelyBinaryExtension(name: String): Boolean {
      val ext = name.substringAfterLast('.', "").lowercase()
      return ext in BINARY_EXTS
    }
  }
}
