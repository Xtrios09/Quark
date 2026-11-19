package com.example.settings

import android.content.Context
import com.quark.editor.EditorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

enum class KeybindingPreset(val label: String) {
  VS_CODE("VS Code"),
  ATOM("Atom"),
}

enum class EditorFontChoice(val id: String, val label: String) {
  JETBRAINS_MONO("jetbrains_mono", "JetBrains Mono"),
  SYSTEM_MONO("system_mono", "System Monospace"),
}

/** Persistent user settings for the editor, UI theme, and keybindings. */
data class AppSettings(
  val themeId: String = "one_dark",
  val followSystemTheme: Boolean = false,
  val fontChoice: EditorFontChoice = EditorFontChoice.JETBRAINS_MONO,
  val fontSizeSp: Float = 14f,
  val ligaturesEnabled: Boolean = true,
  val tabSize: Int = 4,
  val insertSpaces: Boolean = true,
  val wordWrap: Boolean = false,
  val showLineNumbers: Boolean = true,
  val showIndentGuides: Boolean = true,
  val rulerColumn: Int = 80,
  val autoSave: Boolean = true,
  val autoSaveDelayMs: Long = 1500L,
  val formatOnSave: Boolean = false,
  val keybindingPreset: KeybindingPreset = KeybindingPreset.VS_CODE,
  val showExtraKeyRow: Boolean = true,
  val autoCloseBrackets: Boolean = true,
  val gitAuthorName: String = "Quark Developer",
  val gitAuthorEmail: String = "dev@quark.local",
) {
  fun toEditorConfig(): EditorConfig =
    EditorConfig(
      fontSizeSp = fontSizeSp,
      tabSize = tabSize,
      insertSpaces = insertSpaces,
      wordWrap = wordWrap,
      showLineNumbers = showLineNumbers,
      showIndentGuides = showIndentGuides,
      rulerColumn = rulerColumn,
      ligaturesEnabled = ligaturesEnabled,
      autoCloseBrackets = autoCloseBrackets,
    )
}

/** Atomic JSON persistence for [AppSettings]. */
class SettingsRepository(private val context: Context) {
  private val settingsFile: File by lazy {
    File(context.filesDir, "settings.json")
  }

  private val _settings = MutableStateFlow(loadSync())
  val settings: StateFlow<AppSettings> = _settings.asStateFlow()

  suspend fun update(transform: (AppSettings) -> AppSettings) {
    val updated = transform(_settings.value)
    _settings.value = updated
    saveAsync(updated)
  }

  private fun loadSync(): AppSettings {
    if (!settingsFile.exists()) return AppSettings()
    return try {
      val o = JSONObject(settingsFile.readText(Charsets.UTF_8))
      AppSettings(
        themeId = o.optString("themeId", "one_dark"),
        followSystemTheme = o.optBoolean("followSystemTheme", false),
        fontChoice =
          runCatching { EditorFontChoice.valueOf(o.optString("fontChoice", "JETBRAINS_MONO")) }
            .getOrDefault(EditorFontChoice.JETBRAINS_MONO),
        fontSizeSp = o.optDouble("fontSizeSp", 14.0).toFloat().coerceIn(10f, 28f),
        ligaturesEnabled = o.optBoolean("ligaturesEnabled", true),
        tabSize = o.optInt("tabSize", 4).coerceIn(2, 8),
        insertSpaces = o.optBoolean("insertSpaces", true),
        wordWrap = o.optBoolean("wordWrap", false),
        showLineNumbers = o.optBoolean("showLineNumbers", true),
        showIndentGuides = o.optBoolean("showIndentGuides", true),
        rulerColumn = o.optInt("rulerColumn", 80).coerceIn(0, 160),
        autoSave = o.optBoolean("autoSave", true),
        autoSaveDelayMs = o.optLong("autoSaveDelayMs", 1500L),
        formatOnSave = o.optBoolean("formatOnSave", false),
        keybindingPreset =
          runCatching { KeybindingPreset.valueOf(o.optString("keybindingPreset", "VS_CODE")) }
            .getOrDefault(KeybindingPreset.VS_CODE),
        showExtraKeyRow = o.optBoolean("showExtraKeyRow", true),
        autoCloseBrackets = o.optBoolean("autoCloseBrackets", true),
        gitAuthorName = o.optString("gitAuthorName", "Quark Developer"),
        gitAuthorEmail = o.optString("gitAuthorEmail", "dev@quark.local"),
      )
    } catch (_: Throwable) {
      AppSettings()
    }
  }

  private suspend fun saveAsync(s: AppSettings) =
    withContext(Dispatchers.IO) {
      val o =
        JSONObject()
          .put("themeId", s.themeId)
          .put("followSystemTheme", s.followSystemTheme)
          .put("fontChoice", s.fontChoice.name)
          .put("fontSizeSp", s.fontSizeSp.toDouble())
          .put("ligaturesEnabled", s.ligaturesEnabled)
          .put("tabSize", s.tabSize)
          .put("insertSpaces", s.insertSpaces)
          .put("wordWrap", s.wordWrap)
          .put("showLineNumbers", s.showLineNumbers)
          .put("showIndentGuides", s.showIndentGuides)
          .put("rulerColumn", s.rulerColumn)
          .put("autoSave", s.autoSave)
          .put("autoSaveDelayMs", s.autoSaveDelayMs)
          .put("formatOnSave", s.formatOnSave)
          .put("keybindingPreset", s.keybindingPreset.name)
          .put("showExtraKeyRow", s.showExtraKeyRow)
          .put("autoCloseBrackets", s.autoCloseBrackets)
          .put("gitAuthorName", s.gitAuthorName)
          .put("gitAuthorEmail", s.gitAuthorEmail)
      val tmp = File(settingsFile.parentFile, ".settings.json.tmp")
      tmp.writeText(o.toString(2), Charsets.UTF_8)
      if (!tmp.renameTo(settingsFile)) {
        settingsFile.writeText(o.toString(2), Charsets.UTF_8)
        tmp.delete()
      }
    }
}
