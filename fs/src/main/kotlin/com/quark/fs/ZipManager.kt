package com.quark.fs

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Imports and exports workspace projects as standard `.zip` archives using `java.util.zip`,
 * with Zip Slip path-traversal protection.
 */
object ZipManager {

  /**
   * Extracts [zipUri] into a new project folder under [WorkspaceFileSystem.projectsDir].
   */
  suspend fun importZip(
    context: Context,
    fs: WorkspaceFileSystem,
    zipUri: Uri,
    projectNameHint: String,
  ): WorkspaceRoot =
    withContext(Dispatchers.IO) {
      val sanitized =
        projectNameHint
          .substringBeforeLast('.')
          .replace(Regex("[^a-zA-Z0-9._-]"), "_")
          .ifEmpty { "ImportedProject" }
      var destDir = File(fs.projectsDir, sanitized)
      var suffix = 1
      while (destDir.exists()) {
        destDir = File(fs.projectsDir, "${sanitized}_$suffix")
        suffix++
      }
      destDir.mkdirs()
      val canonicalDest = destDir.canonicalPath

      context.contentResolver.openInputStream(zipUri)?.use { rawIn ->
        ZipInputStream(BufferedInputStream(rawIn)).use { zis ->
          val buffer = ByteArray(8192)
          var entry: ZipEntry? = zis.nextEntry
          while (entry != null) {
            val outFile = File(destDir, entry.name)
            val canonicalOut = outFile.canonicalPath
            if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
              throw SecurityException("Blocked unsafe zip entry: ${entry.name}")
            }
            if (entry.isDirectory) {
              outFile.mkdirs()
            } else {
              outFile.parentFile?.mkdirs()
              outFile.outputStream().use { fos ->
                var read = zis.read(buffer)
                while (read != -1) {
                  fos.write(buffer, 0, read)
                  read = zis.read(buffer)
                }
              }
            }
            zis.closeEntry()
            entry = zis.nextEntry
          }
        }
      } ?: error("Unable to open zip archive")

      // If zip wrapped everything inside a single top-level directory, unwrap it cleanly
      val topEntries = destDir.listFiles()
      if (topEntries != null && topEntries.size == 1 && topEntries[0].isDirectory) {
        val inner = topEntries[0]
        inner.listFiles()?.forEach { child ->
          child.renameTo(File(destDir, child.name))
        }
        inner.delete()
      }

      WorkspaceRoot(
        id = "local:${destDir.absolutePath}",
        name = destDir.name,
        rootUriOrPath = destDir.absolutePath,
        backend = StorageBackend.APP_PRIVATE,
        lastOpenedMs = System.currentTimeMillis(),
      )
    }

  /**
   * Exports all files from [root] into [destUri] as a `.zip` archive.
   */
  suspend fun exportZip(
    context: Context,
    fs: WorkspaceFileSystem,
    root: WorkspaceRoot,
    destUri: Uri,
  ): Int =
    withContext(Dispatchers.IO) {
      val files = fs.collectAllFiles(root)
      var count = 0
      context.contentResolver.openOutputStream(destUri)?.use { rawOut ->
        ZipOutputStream(BufferedOutputStream(rawOut)).use { zos ->
          for (node in files) {
            val entryName = node.relativePath.ifEmpty { node.name }
            zos.putNextEntry(ZipEntry(entryName))
            if (node.backend == StorageBackend.APP_PRIVATE) {
              val f = File(node.absolutePath!!)
              f.inputStream().use { it.copyTo(zos) }
            } else {
              val treeUri = Uri.parse(node.treeUri!!)
              val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId!!)
              context.contentResolver.openInputStream(docUri)?.use { it.copyTo(zos) }
            }
            zos.closeEntry()
            count++
          }
        }
      } ?: error("Unable to open destination stream for zip export")
      count
    }
}
