package com.quark.fs

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Persisted tab descriptor for session restore and crash recovery. */
data class SavedTabSession(
  val node: VfsNode,
  val cursorOffset: Int,
  val hasUnsavedRecovery: Boolean,
  val recoveredText: String? = null,
)

/** Full restored workspace session. */
data class RestoredWorkspaceSession(
  val workspace: WorkspaceRoot?,
  val tabs: List<SavedTabSession>,
  val activeTabId: String?,
  val recentWorkspaces: List<WorkspaceRoot>,
)

/**
 * Manages workspace session restore, recent projects history, and crash recovery of unsaved
 * dirty buffers in app-private storage (`filesDir/recovery`).
 */
class SessionRecoveryManager(private val context: Context) {

  private val recoveryDir: File by lazy {
    File(context.filesDir, "recovery").apply { mkdirs() }
  }

  private val sessionFile: File by lazy {
    File(recoveryDir, "session.json")
  }

  private val recentsFile: File by lazy {
    File(recoveryDir, "recents.json")
  }

  /** Persists the current open workspace, tabs, and cursor positions atomically. */
  suspend fun saveSession(
    workspace: WorkspaceRoot?,
    tabs: List<Pair<VfsNode, Int>>,
    activeTabId: String?,
  ) = withContext(Dispatchers.IO) {
    val rootObj = JSONObject()
    if (workspace != null) {
      rootObj.put("workspace", encodeWorkspace(workspace))
    }
    rootObj.put("activeTabId", activeTabId ?: JSONObject.NULL)
    val tabsArr = JSONArray()
    for ((node, cursor) in tabs) {
      tabsArr.put(encodeNode(node).put("cursor", cursor))
    }
    rootObj.put("tabs", tabsArr)
    writeAtomic(sessionFile, rootObj.toString())
  }

  /** Persists an unsaved dirty buffer snapshot for crash recovery. */
  suspend fun saveRecoverySnapshot(nodeId: String, content: String) =
    withContext(Dispatchers.IO) {
      val f = snapshotFileFor(nodeId)
      val obj = JSONObject().put("nodeId", nodeId).put("content", content)
      writeAtomic(f, obj.toString())
    }

  /** Deletes the crash-recovery snapshot once a file is saved or cleanly closed. */
  suspend fun clearRecoverySnapshot(nodeId: String) =
    withContext(Dispatchers.IO) {
      snapshotFileFor(nodeId).delete()
    }

  /** Records [workspace] at the top of the recent workspaces list. */
  suspend fun recordRecentWorkspace(workspace: WorkspaceRoot) =
    withContext(Dispatchers.IO) {
      val existing = loadRecentsInternal().filter { it.id != workspace.id }.toMutableList()
      existing.add(0, workspace.copy(lastOpenedMs = System.currentTimeMillis()))
      val trimmed = existing.take(12)
      val arr = JSONArray()
      for (w in trimmed) arr.put(encodeWorkspace(w))
      writeAtomic(recentsFile, arr.toString())
    }

  /** Loads recent workspaces list. */
  suspend fun loadRecentWorkspaces(): List<WorkspaceRoot> =
    withContext(Dispatchers.IO) { loadRecentsInternal() }

  /** Restores the previous session including any unrecovered dirty buffer snapshots. */
  suspend fun restoreSession(): RestoredWorkspaceSession =
    withContext(Dispatchers.IO) {
      val recents = loadRecentsInternal()
      if (!sessionFile.exists()) {
        return@withContext RestoredWorkspaceSession(null, emptyList(), null, recents)
      }
      try {
        val obj = JSONObject(sessionFile.readText())
        val ws = if (obj.has("workspace") && !obj.isNull("workspace")) decodeWorkspace(obj.getJSONObject("workspace")) else null
        val activeId = if (obj.has("activeTabId") && !obj.isNull("activeTabId")) obj.getString("activeTabId") else null
        val tabsArr = obj.optJSONArray("tabs") ?: JSONArray()
        val tabs = ArrayList<SavedTabSession>(tabsArr.length())
        for (i in 0 until tabsArr.length()) {
          val tObj = tabsArr.getJSONObject(i)
          val node = decodeNode(tObj)
          val cursor = tObj.optInt("cursor", 0)
          val snapFile = snapshotFileFor(node.id)
          val recovered =
            if (snapFile.exists()) {
              try {
                JSONObject(snapFile.readText()).optString("content")
              } catch (_: Throwable) {
                null
              }
            } else {
              null
            }
          tabs.add(
            SavedTabSession(
              node = node,
              cursorOffset = cursor,
              hasUnsavedRecovery = recovered != null,
              recoveredText = recovered,
            )
          )
        }
        RestoredWorkspaceSession(ws, tabs, activeId, recents)
      } catch (_: Throwable) {
        RestoredWorkspaceSession(null, emptyList(), null, recents)
      }
    }

  private fun loadRecentsInternal(): List<WorkspaceRoot> {
    if (!recentsFile.exists()) return emptyList()
    return try {
      val arr = JSONArray(recentsFile.readText())
      val list = ArrayList<WorkspaceRoot>(arr.length())
      for (i in 0 until arr.length()) {
        val w = decodeWorkspace(arr.getJSONObject(i))
        // Verify private directories still exist on disk
        if (w.backend == StorageBackend.APP_PRIVATE && !File(w.rootUriOrPath).exists()) continue
        list.add(w)
      }
      list
    } catch (_: Throwable) {
      emptyList()
    }
  }

  private fun snapshotFileFor(nodeId: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(nodeId.toByteArray())
    val hex = digest.joinToString("") { "%02x".format(it) }
    return File(recoveryDir, "buf_$hex.json")
  }

  private fun writeAtomic(target: File, text: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, ".${target.name}.tmp")
    tmp.writeText(text, Charsets.UTF_8)
    if (!tmp.renameTo(target)) {
      target.writeText(text, Charsets.UTF_8)
      tmp.delete()
    }
  }

  private fun encodeWorkspace(w: WorkspaceRoot): JSONObject =
    JSONObject()
      .put("id", w.id)
      .put("name", w.name)
      .put("rootUriOrPath", w.rootUriOrPath)
      .put("backend", w.backend.name)
      .put("lastOpenedMs", w.lastOpenedMs)

  private fun decodeWorkspace(o: JSONObject): WorkspaceRoot =
    WorkspaceRoot(
      id = o.getString("id"),
      name = o.getString("name"),
      rootUriOrPath = o.getString("rootUriOrPath"),
      backend = StorageBackend.valueOf(o.getString("backend")),
      lastOpenedMs = o.optLong("lastOpenedMs", 0L),
    )

  private fun encodeNode(n: VfsNode): JSONObject =
    JSONObject()
      .put("id", n.id)
      .put("name", n.name)
      .put("relativePath", n.relativePath)
      .put("isDirectory", n.isDirectory)
      .put("sizeBytes", n.sizeBytes)
      .put("lastModifiedMs", n.lastModifiedMs)
      .put("backend", n.backend.name)
      .put("absolutePath", n.absolutePath ?: JSONObject.NULL)
      .put("treeUri", n.treeUri ?: JSONObject.NULL)
      .put("documentId", n.documentId ?: JSONObject.NULL)
      .put("parentId", n.parentId ?: JSONObject.NULL)

  private fun decodeNode(o: JSONObject): VfsNode =
    VfsNode(
      id = o.getString("id"),
      name = o.getString("name"),
      relativePath = o.getString("relativePath"),
      isDirectory = o.getBoolean("isDirectory"),
      sizeBytes = o.optLong("sizeBytes", 0L),
      lastModifiedMs = o.optLong("lastModifiedMs", 0L),
      backend = StorageBackend.valueOf(o.getString("backend")),
      absolutePath = if (o.isNull("absolutePath")) null else o.optString("absolutePath"),
      treeUri = if (o.isNull("treeUri")) null else o.optString("treeUri"),
      documentId = if (o.isNull("documentId")) null else o.optString("documentId"),
      parentId = if (o.isNull("parentId")) null else o.optString("parentId"),
    )
}
