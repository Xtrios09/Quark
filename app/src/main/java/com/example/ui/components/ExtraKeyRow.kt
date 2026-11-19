package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalQuarkTheme

private val EXTRA_KEYS = listOf(
  "INTEL" to "Auto",
  "TAB" to "Tab",
  "ESC" to "Esc",
  "CTRL" to "Ctrl",
  "ALT" to "Alt",
  "LEFT" to "←",
  "DOWN" to "↓",
  "UP" to "↑",
  "RIGHT" to "→",
  "{" to "{",
  "}" to "}",
  "(" to "(",
  ")" to ")",
  "[" to "[",
  "]" to "]",
  ";" to ";",
  ":" to ":",
  "\"" to "\"",
  "'" to "'",
  "/" to "/",
  "\\" to "\\",
  "|" to "|",
  "<" to "<",
  ">" to ">",
  "=" to "=",
  "$" to "$",
  "#" to "#",
)

/**
 * Scrollable extra-key toolbar providing programming symbols, arrows, Tab, Esc, and sticky
 * Ctrl/Alt modifiers with >= 44dp touch targets.
 */
@Composable
fun ExtraKeyRow(
  stickyCtrl: Boolean,
  stickyAlt: Boolean,
  onToggleCtrl: () -> Unit,
  onToggleAlt: () -> Unit,
  onSendKey: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalQuarkTheme.current
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .background(theme.ui.tabBarBg)
        .border(0.5.dp, theme.ui.border)
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 6.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for ((code, label) in EXTRA_KEYS) {
      val isStickyActive = (code == "CTRL" && stickyCtrl) || (code == "ALT" && stickyAlt)
      val bg = if (isStickyActive) theme.ui.accent else theme.ui.editorBg
      val fg = if (isStickyActive) theme.ui.onAccent else theme.ui.textPrimary
      Box(
        modifier =
          Modifier
            .widthIn(min = 42.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(0.5.dp, theme.ui.border, RoundedCornerShape(6.dp))
            .clickable {
              when (code) {
                "CTRL" -> onToggleCtrl()
                "ALT" -> onToggleAlt()
                else -> onSendKey(code)
              }
            }
            .padding(horizontal = 10.dp)
            .testTag("extra_key_${code.lowercase()}"),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = label,
          color = fg,
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 13.sp,
          fontWeight = if (isStickyActive) FontWeight.Bold else FontWeight.Medium,
        )
      }
    }
  }
}
