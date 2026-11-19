package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ide.CompletionPopupState
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalQuarkTheme
import com.quark.intel.CompletionItem
import com.quark.intel.SymbolKind
import kotlin.math.roundToInt

/**
 * Anchored floating IntelliSense auto-completion & signature-help overlay positioned near the caret.
 */
@Composable
fun CompletionOverlay(
  state: CompletionPopupState,
  editorWidth: Dp,
  editorHeight: Dp,
  onAcceptItem: (CompletionItem) -> Unit,
  onDismiss: () -> Unit,
) {
  if (!state.visible && state.signatureHint == null) return

  val theme = LocalQuarkTheme.current
  val density = LocalDensity.current
  val popupWidthDp = 310.dp.coerceAtMost((editorWidth - 16.dp).coerceAtLeast(180.dp))
  val popupMaxHeightDp = 210.dp
  val widthPx = with(density) { editorWidth.toPx() }
  val heightPx = with(density) { editorHeight.toPx() }
  val cardWidthPx = with(density) { popupWidthDp.toPx() }
  val cardMaxHeightPx = with(density) { popupMaxHeightDp.toPx() }
  val marginPx = with(density) { 8.dp.toPx() }

  val targetX = state.caretXPx.coerceIn(marginPx, (widthPx - cardWidthPx - marginPx).coerceAtLeast(marginPx))
  val belowY = state.caretYPx + state.lineHeightPx + with(density) { 4.dp.toPx() }
  val showAbove = belowY + cardMaxHeightPx > heightPx && state.caretYPx > cardMaxHeightPx * 0.6f
  val targetY =
    if (showAbove) {
      (state.caretYPx - cardMaxHeightPx).coerceAtLeast(marginPx)
    } else {
      belowY.coerceIn(marginPx, (heightPx - cardMaxHeightPx * 0.45f).coerceAtLeast(marginPx))
    }

  val listState = rememberLazyListState()
  LaunchedEffect(state.selectedIndex, state.visible) {
    if (state.visible && state.selectedIndex in state.items.indices) {
      listState.animateScrollToItem(state.selectedIndex)
    }
  }

  Column(
    modifier =
      Modifier
        .offset { IntOffset(targetX.roundToInt(), targetY.roundToInt()) }
        .width(popupWidthDp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    val sig = state.signatureHint
    if (sig != null) {
      Surface(
        shape = RoundedCornerShape(6.dp),
        color = theme.ui.tabBarBg,
        tonalElevation = 8.dp,
        modifier = Modifier.border(1.dp, theme.ui.border, RoundedCornerShape(6.dp)).testTag("intellisense_signature_hint"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = sig.signature,
            color = theme.ui.textPrimary,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          if (sig.doc.isNotEmpty()) {
            Text(
              text = sig.doc,
              color = theme.ui.accent,
              fontSize = 10.sp,
            )
          }
        }
      }
    }

    if (state.visible && state.items.isNotEmpty()) {
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = theme.ui.sidePanelBg,
        tonalElevation = 10.dp,
        shadowElevation = 8.dp,
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(max = popupMaxHeightDp)
            .border(1.dp, theme.ui.border, RoundedCornerShape(8.dp))
            .testTag("intellisense_completion_popup"),
      ) {
        Column {
          Row(
            modifier = Modifier.fillMaxWidth().background(theme.ui.tabBarBg).padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "IntelliSense (${state.items.size})",
              color = theme.ui.textMuted,
              fontSize = 10.sp,
              fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(22.dp)) {
              Icon(Icons.Default.Close, contentDescription = "Close Completions", tint = theme.ui.textMuted, modifier = Modifier.size(13.dp))
            }
          }
          HorizontalDivider(color = theme.ui.border)
          LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = popupMaxHeightDp - 28.dp),
          ) {
            itemsIndexed(state.items, key = { _, it -> it.id }) { idx, item ->
              val selected = idx == state.selectedIndex
              val badgeColor = symbolBadgeColor(item.kind, theme.ui.accent, theme.ui.success, theme.ui.warning)
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .background(if (selected) theme.ui.selectionHighlight else Color.Transparent)
                    .clickable { onAcceptItem(item) }
                    .padding(horizontal = 8.dp, vertical = 7.dp)
                    .testTag("completion_item_${item.label}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Box(
                  modifier =
                    Modifier
                      .size(20.dp)
                      .clip(RoundedCornerShape(4.dp))
                      .background(badgeColor.copy(alpha = 0.20f))
                      .border(0.5.dp, badgeColor.copy(alpha = 0.7f), RoundedCornerShape(4.dp)),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    text = item.kind.badge,
                    color = badgeColor,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                  )
                }
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = item.label,
                    color = if (selected) theme.ui.textPrimary else theme.ui.textSecondary,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    text = item.detail,
                    color = theme.ui.textMuted,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                Text(
                  text = item.sourceLabel,
                  color = theme.ui.textMuted,
                  fontSize = 10.sp,
                  maxLines = 1,
                )
              }
            }
          }
        }
      }
    }
  }
}

private fun symbolBadgeColor(kind: SymbolKind, accent: Color, green: Color, amber: Color): Color =
  when (kind) {
    SymbolKind.FUNCTION, SymbolKind.METHOD -> accent
    SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM, SymbolKind.OBJECT -> amber
    SymbolKind.VARIABLE, SymbolKind.PARAMETER, SymbolKind.PROPERTY, SymbolKind.CONSTANT -> green
    SymbolKind.SNIPPET -> Color(0xFFC678DD)
    SymbolKind.KEYWORD, SymbolKind.IMPORT, SymbolKind.TAG -> Color(0xFF56B6C2)
  }
