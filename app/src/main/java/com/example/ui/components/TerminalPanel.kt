package com.example.ui.components

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalQuarkTheme
import com.quark.terminal.QuarkTerminalView
import com.quark.terminal.TerminalSession
import com.quark.terminal.TerminalViewPalette

/**
 * Integrated Terminal panel hosting a custom [QuarkTerminalView] backed by [TerminalSession],
 * multi-session switcher bar, clear/interrupt actions, and quick touch keys.
 */
@Composable
fun TerminalPanel(
  session: TerminalSession?,
  tabCount: Int,
  activeIndex: Int,
  typeface: Typeface,
  fontSizeSp: Float,
  onSelectSession: (Int) -> Unit,
  onNewSession: () -> Unit,
  onCloseSession: (Int) -> Unit,
) {
  val theme = LocalQuarkTheme.current
  val termPalette = remember(theme) {
    TerminalViewPalette(
      background = theme.terminal.background,
      foreground = theme.terminal.foreground,
      cursorColor = theme.terminal.cursor,
      ansi16 = theme.terminal.ansi16,
    )
  }

  Column(modifier = Modifier.fillMaxSize().background(Color(theme.terminal.background))) {
    // Session switcher & control bar
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(34.dp)
          .background(theme.ui.tabBarBg)
          .padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        for (idx in 0 until tabCount) {
          val selected = idx == activeIndex
          Row(
            modifier =
              Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(if (selected) theme.ui.selectionHighlight else Color.Transparent)
                .border(0.5.dp, if (selected) theme.ui.accent else theme.ui.border, RoundedCornerShape(5.dp))
                .clickable { onSelectSession(idx) }
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .testTag("terminal_tab_$idx"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              text = "sh ${idx + 1}",
              color = if (selected) theme.ui.textPrimary else theme.ui.textSecondary,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 11.sp,
            )
            if (tabCount > 1) {
              Icon(
                Icons.Default.Close,
                contentDescription = "Close Terminal ${idx + 1}",
                tint = theme.ui.textMuted,
                modifier = Modifier.size(13.dp).clickable { onCloseSession(idx) },
              )
            }
          }
        }
        IconButton(onClick = onNewSession, modifier = Modifier.size(28.dp).testTag("terminal_new_tab")) {
          Icon(Icons.Default.Add, contentDescription = "New Terminal", tint = theme.ui.textSecondary, modifier = Modifier.size(15.dp))
        }
      }

      Text(
        text = "^C",
        color = theme.ui.danger,
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier =
          Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(theme.ui.danger.copy(alpha = 0.14f))
            .clickable { session?.interrupt() }
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("terminal_ctrl_c"),
      )
      IconButton(
        onClick = { session?.clearScreen() },
        modifier = Modifier.size(28.dp).testTag("terminal_clear"),
      ) {
        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Terminal", tint = theme.ui.textSecondary, modifier = Modifier.size(16.dp))
      }
    }

    HorizontalDivider(color = theme.ui.border)

    // Custom Canvas Terminal View
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      AndroidView(
        factory = { ctx ->
          QuarkTerminalView(ctx).apply {
            applyPalette(termPalette)
            setTerminalTypeface(typeface)
            setFontSizeSp(fontSizeSp)
            this.session = session
          }
        },
        update = { view ->
          if (view.session !== session) {
            view.session = session
          }
          view.applyPalette(termPalette)
          view.setTerminalTypeface(typeface)
          view.setFontSizeSp(fontSizeSp)
        },
        modifier = Modifier.fillMaxSize().testTag("quark_terminal_view"),
      )
    }

    // Touch-friendly Terminal Shortcut Strip
    TerminalShortcutStrip(
      onSpecialKey = { key -> session?.handleSpecialKey(key) },
      onInsertText = { txt -> session?.sendInputText(txt) },
    )
  }
}

@Composable
private fun TerminalShortcutStrip(
  onSpecialKey: (String) -> Unit,
  onInsertText: (String) -> Unit,
) {
  val theme = LocalQuarkTheme.current
  val keys = remember {
    listOf(
      "TAB" to "Tab",
      "CTRL_C" to "^C",
      "CTRL_L" to "^L",
      "UP" to "↑",
      "DOWN" to "↓",
      "LEFT" to "←",
      "RIGHT" to "→",
      "|" to "|",
      "/" to "/",
      "-" to "-",
      "HOME" to "Home",
      "END" to "End",
    )
  }
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .background(theme.ui.tabBarBg)
        .border(0.5.dp, theme.ui.border)
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 6.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for ((code, label) in keys) {
      Box(
        modifier =
          Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(theme.ui.editorBg)
            .border(0.5.dp, theme.ui.border, RoundedCornerShape(5.dp))
            .clickable {
              if (code.length == 1) onInsertText(code) else onSpecialKey(code)
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = label,
          color = theme.ui.textPrimary,
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 11.5.sp,
          fontWeight = FontWeight.Medium,
        )
      }
    }
  }
}
