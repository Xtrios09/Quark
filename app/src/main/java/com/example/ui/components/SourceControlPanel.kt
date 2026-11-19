package com.example.ui.components

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.icons.FileTypeBadgeIcon
import com.example.ui.icons.QuarkIcons
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalQuarkTheme
import com.quark.git.DiffLineKind
import com.quark.git.FileDiffResult
import com.quark.git.GitChangeType
import com.quark.git.GitFileChange
import com.quark.git.GitRepoSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SourceControlPanel(
  snapshot: GitRepoSnapshot,
  onInitRepo: () -> Unit,
  onRefresh: () -> Unit,
  onStage: (String) -> Unit,
  onStageAll: () -> Unit,
  onUnstage: (String) -> Unit,
  onDiscard: (String) -> Unit,
  onCommit: (String) -> Unit,
  onOpenDiff: (String, Boolean) -> Unit,
  onCheckoutBranch: (String) -> Unit,
  onRequestNewBranch: () -> Unit,
) {
  val theme = LocalQuarkTheme.current
  var commitMsg by remember { mutableStateOf("") }
  var branchMenuOpen by remember { mutableStateOf(false) }

  Column(modifier = Modifier.fillMaxSize().background(theme.ui.sidePanelBg)) {
    // Header
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "SOURCE CONTROL",
        color = theme.ui.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.weight(1f),
      )
      if (snapshot.isRepository) {
        IconButton(onClick = onStageAll, modifier = Modifier.size(34.dp).testTag("git_stage_all")) {
          Icon(Icons.Default.Add, contentDescription = "Stage All", tint = theme.ui.textSecondary, modifier = Modifier.size(18.dp))
        }
      }
      IconButton(onClick = onRefresh, modifier = Modifier.size(34.dp).testTag("git_refresh")) {
        Icon(Icons.Default.Refresh, contentDescription = "Refresh Git Status", tint = theme.ui.textSecondary, modifier = Modifier.size(18.dp))
      }
    }
    HorizontalDivider(color = theme.ui.border)

    if (!snapshot.isSupportedBackend) {
      Box(modifier = Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
        Text(
          text = "Git source control runs directly on local App Private projects. Open or create a local workspace to use Git.",
          color = theme.ui.textMuted,
          fontSize = 12.sp,
          lineHeight = 18.sp,
        )
      }
      return
    }

    if (!snapshot.isRepository) {
      Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = "This workspace does not have a Git repository initialized yet.",
          color = theme.ui.textSecondary,
          fontSize = 12.sp,
          lineHeight = 18.sp,
        )
        Button(
          onClick = onInitRepo,
          modifier = Modifier.fillMaxWidth().height(44.dp).testTag("git_init_btn"),
          colors = ButtonDefaults.buttonColors(containerColor = theme.ui.accent, contentColor = theme.ui.onAccent),
        ) {
          Icon(QuarkIcons.GitBranch, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(8.dp))
          Text("Initialize Repository", fontSize = 13.sp)
        }
      }
      return
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      // Branch selector row
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Box(modifier = Modifier.weight(1f)) {
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(theme.ui.editorBg)
                .border(0.8.dp, theme.ui.border, RoundedCornerShape(6.dp))
                .clickable { branchMenuOpen = true }
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .testTag("git_branch_selector"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(QuarkIcons.GitBranch, contentDescription = null, tint = theme.ui.accent, modifier = Modifier.size(15.dp))
            Text(
              text = snapshot.currentBranch,
              color = theme.ui.textPrimary,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          DropdownMenu(expanded = branchMenuOpen, onDismissRequest = { branchMenuOpen = false }) {
            for (b in snapshot.branches) {
              DropdownMenuItem(
                text = {
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(b.name, fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp)
                    if (b.isCurrent) Icon(Icons.Default.Check, contentDescription = null, tint = theme.ui.accent, modifier = Modifier.size(14.dp))
                  }
                },
                onClick = {
                  branchMenuOpen = false
                  if (!b.isCurrent) onCheckoutBranch(b.name)
                },
              )
            }
          }
        }
        OutlinedButton(
          onClick = onRequestNewBranch,
          modifier = Modifier.height(34.dp).testTag("git_new_branch"),
        ) {
          Text("+ Branch", fontSize = 11.sp)
        }
      }

      // Commit box
      OutlinedTextField(
        value = commitMsg,
        onValueChange = { commitMsg = it },
        placeholder = { Text("Commit message...", fontSize = 12.sp) },
        maxLines = 3,
        modifier = Modifier.fillMaxWidth().testTag("git_commit_input"),
      )
      Button(
        onClick = {
          if (commitMsg.isNotBlank()) {
            onCommit(commitMsg.trim())
            commitMsg = ""
          }
        },
        enabled = snapshot.stagedChanges.isNotEmpty() && commitMsg.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(40.dp).testTag("git_commit_btn"),
        colors = ButtonDefaults.buttonColors(containerColor = theme.ui.accent, contentColor = theme.ui.onAccent),
      ) {
        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Commit (${snapshot.stagedChanges.size} staged)", fontSize = 12.sp)
      }

      LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Staged Changes
        item {
          SectionLabel("STAGED CHANGES (${snapshot.stagedChanges.size})")
        }
        if (snapshot.stagedChanges.isEmpty()) {
          item {
            Text("No staged changes", color = theme.ui.textMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
          }
        } else {
          items(snapshot.stagedChanges, key = { "staged_${it.path}" }) { ch ->
            GitChangeRow(
              change = ch,
              primaryActionIcon = Icons.Default.Remove,
              primaryActionDesc = "Unstage ${ch.path}",
              onPrimaryAction = { onUnstage(ch.path) },
              onDiscard = null,
              onClick = { onOpenDiff(ch.path, true) },
            )
          }
        }

        // Working Changes
        item {
          Spacer(Modifier.height(6.dp))
          SectionLabel("CHANGES (${snapshot.unstagedChanges.size})")
        }
        if (snapshot.unstagedChanges.isEmpty()) {
          item {
            Text("Working tree clean", color = theme.ui.textMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
          }
        } else {
          items(snapshot.unstagedChanges, key = { "work_${it.path}" }) { ch ->
            GitChangeRow(
              change = ch,
              primaryActionIcon = Icons.Default.Add,
              primaryActionDesc = "Stage ${ch.path}",
              onPrimaryAction = { onStage(ch.path) },
              onDiscard = { onDiscard(ch.path) },
              onClick = { onOpenDiff(ch.path, false) },
            )
          }
        }

        // Commit History
        if (snapshot.commits.isNotEmpty()) {
          item {
            Spacer(Modifier.height(8.dp))
            SectionLabel("COMMITS (${snapshot.commits.size})")
          }
          items(snapshot.commits, key = { it.sha }) { commit ->
            val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
            Column(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(6.dp))
                  .background(theme.ui.editorBg)
                  .border(0.5.dp, theme.ui.border, RoundedCornerShape(6.dp))
                  .padding(8.dp)
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  text = commit.shortSha,
                  color = theme.ui.accent,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  text = commit.message,
                  color = theme.ui.textPrimary,
                  fontSize = 12.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f),
                )
              }
              Text(
                text = "${commit.authorName} · ${fmt.format(Date(commit.timestampSec * 1000L))}",
                color = theme.ui.textMuted,
                fontSize = 10.sp,
              )
            }
            Spacer(Modifier.height(4.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  val theme = LocalQuarkTheme.current
  Text(
    text = text,
    color = theme.ui.textSecondary,
    fontSize = 10.5.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.6.sp,
    modifier = Modifier.padding(vertical = 3.dp),
  )
}

@Composable
private fun GitChangeRow(
  change: GitFileChange,
  primaryActionIcon: androidx.compose.ui.graphics.vector.ImageVector,
  primaryActionDesc: String,
  onPrimaryAction: () -> Unit,
  onDiscard: (() -> Unit)?,
  onClick: () -> Unit,
) {
  val theme = LocalQuarkTheme.current
  val (badge, badgeColor) = when (change.type) {
    GitChangeType.ADDED -> "A" to theme.ui.success
    GitChangeType.MODIFIED -> "M" to theme.ui.warning
    GitChangeType.DELETED -> "D" to theme.ui.danger
    GitChangeType.UNTRACKED -> "U" to theme.ui.success
  }
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(36.dp)
        .clip(RoundedCornerShape(5.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    FileTypeBadgeIcon(fileName = change.path.substringAfterLast('/'), size = 15.dp)
    Text(
      text = change.path,
      color = theme.ui.textPrimary,
      fontSize = 12.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (onDiscard != null) {
      IconButton(onClick = onDiscard, modifier = Modifier.size(28.dp)) {
        Icon(Icons.Default.Undo, contentDescription = "Discard changes", tint = theme.ui.textMuted, modifier = Modifier.size(15.dp))
      }
    }
    IconButton(onClick = onPrimaryAction, modifier = Modifier.size(28.dp)) {
      Icon(primaryActionIcon, contentDescription = primaryActionDesc, tint = theme.ui.textSecondary, modifier = Modifier.size(16.dp))
    }
    Text(
      text = badge,
      color = badgeColor,
      fontFamily = JetBrainsMonoFontFamily,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(end = 4.dp),
    )
  }
}

@Composable
fun DiffViewerDialog(
  diff: FileDiffResult,
  onDismiss: () -> Unit,
) {
  val theme = LocalQuarkTheme.current
  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = theme.ui.sidePanelBg,
      tonalElevation = 8.dp,
      modifier =
        Modifier
          .widthIn(min = 340.dp, max = 840.dp)
          .fillMaxWidth()
          .heightIn(max = 580.dp)
          .border(1.dp, theme.ui.border, RoundedCornerShape(12.dp)),
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FileTypeBadgeIcon(fileName = diff.path.substringAfterLast('/'), size = 16.dp)
            Text(
              text = "Diff: ${diff.path}",
              color = theme.ui.textPrimary,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
            )
          }
          IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close Diff", tint = theme.ui.textSecondary)
          }
        }
        HorizontalDivider(color = theme.ui.border)

        LazyColumn(
          modifier =
            Modifier
              .fillMaxSize()
              .background(theme.ui.editorBg)
              .horizontalScroll(rememberScrollState())
              .padding(vertical = 8.dp),
        ) {
          items(diff.lines) { line ->
            val bg = when (line.kind) {
              DiffLineKind.ADDED -> theme.ui.success.copy(alpha = 0.14f)
              DiffLineKind.REMOVED -> theme.ui.danger.copy(alpha = 0.14f)
              DiffLineKind.HEADER -> theme.ui.accent.copy(alpha = 0.12f)
              DiffLineKind.CONTEXT -> Color.Transparent
            }
            val fg = when (line.kind) {
              DiffLineKind.ADDED -> theme.ui.success
              DiffLineKind.REMOVED -> theme.ui.danger
              DiffLineKind.HEADER -> theme.ui.accent
              DiffLineKind.CONTEXT -> theme.ui.textSecondary
            }
            val prefix = when (line.kind) {
              DiffLineKind.ADDED -> "+"
              DiffLineKind.REMOVED -> "-"
              DiffLineKind.HEADER -> " "
              DiffLineKind.CONTEXT -> " "
            }
            Row(
              modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 10.dp, vertical = 1.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "${line.oldLineNo?.toString()?.padStart(4) ?: "    "} ${line.newLineNo?.toString()?.padStart(4) ?: "    "} ",
                color = theme.ui.textMuted,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
              )
              Text(
                text = "$prefix ${line.text}",
                color = fg,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 12.sp,
              )
            }
          }
        }
      }
    }
  }
}
