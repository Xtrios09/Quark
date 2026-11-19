package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.BuildConfig
import com.example.ide.PaletteCommand
import com.example.settings.AppSettings
import com.example.ui.icons.FileTypeBadgeIcon
import com.example.ui.icons.QuarkIcons
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalQuarkTheme
import com.example.ui.theme.QuarkThemes
import com.example.ui.theme.SpaceGroteskFontFamily
import com.quark.editor.FindState
import com.quark.fs.FuzzyFileMatch
import com.quark.fs.ProjectTemplate
import com.quark.fs.StorageBackend
import com.quark.fs.WorkspaceRoot

/**
 * Tablet-first Welcome landing screen for creating, opening, or importing projects.
 */
@Composable
fun WelcomeScreen(
  privateProjects: List<WorkspaceRoot>,
  recentWorkspaces: List<WorkspaceRoot>,
  canDismiss: Boolean,
  onCreateProject: (String, ProjectTemplate) -> Unit,
  onOpenWorkspace: (WorkspaceRoot) -> Unit,
  onOpenSafFolder: () -> Unit,
  onImportZip: () -> Unit,
  onOpenThemes: () -> Unit,
  onDismiss: () -> Unit,
) {
  val theme = LocalQuarkTheme.current
  var selectedTemplate by remember { mutableStateOf(ProjectTemplate.KOTLIN) }
  var projectName by remember { mutableStateOf("") }

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .background(theme.ui.editorBg)
        .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier =
        Modifier
          .widthIn(max = 880.dp)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Box(
            modifier =
              Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(theme.ui.accent.copy(alpha = 0.18f))
                .border(1.dp, theme.ui.accent, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(QuarkIcons.TerminalPrompt, contentDescription = null, tint = theme.ui.accent, modifier = Modifier.size(24.dp))
          }
          Column {
            Text(
              text = "Quark",
              fontFamily = SpaceGroteskFontFamily,
              fontSize = 28.sp,
              fontWeight = FontWeight.Bold,
              color = theme.ui.textPrimary,
            )
            Text(
              text = "On-device hackable tablet editor  |  ${BuildConfig.FLAVOR_NAME.uppercase()} (SDK ${BuildConfig.TARGET_SDK_INT})",
              color = theme.ui.textSecondary,
              fontSize = 13.sp,
            )
          }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = onOpenThemes,
            modifier = Modifier.height(40.dp).testTag("welcome_themes_btn"),
          ) {
            Icon(QuarkIcons.Palette, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(theme.displayName, fontSize = 12.sp)
          }
          if (canDismiss) {
            TextButton(onClick = onDismiss) { Text("Back to Editor") }
          }
        }
      }

      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val twoColumn = maxWidth >= 600.dp
        val newProjectCard: @Composable (Modifier) -> Unit = { cardMod ->
          Column(
            modifier =
              cardMod
                .clip(RoundedCornerShape(12.dp))
                .background(theme.ui.sidePanelBg)
                .border(1.dp, theme.ui.border, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text("New Project", color = theme.ui.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
              value = projectName,
              onValueChange = { projectName = it },
              placeholder = { Text("Project name (e.g. hello-quark)") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("welcome_project_name"),
            )
            Text("Starter Template", color = theme.ui.textSecondary, fontSize = 12.sp)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              ProjectTemplate.entries.forEach { tpl ->
                FilterChip(
                  selected = selectedTemplate == tpl,
                  onClick = { selectedTemplate = tpl },
                  leadingIcon = { FileTypeBadgeIcon(fileName = "main.${tpl.extension}", size = 16.dp) },
                  label = { Text(tpl.displayName, fontSize = 12.sp) },
                )
              }
            }

            Button(
              onClick = {
                val name = projectName.ifBlank { "${selectedTemplate.displayName.lowercase().replace(" / ", "_")}_app" }
                onCreateProject(name, selectedTemplate)
              },
              modifier = Modifier.fillMaxWidth().height(48.dp).testTag("welcome_create_project"),
              colors = ButtonDefaults.buttonColors(containerColor = theme.ui.accent, contentColor = theme.ui.onAccent),
            ) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(8.dp))
              Text("Create ${selectedTemplate.displayName} Project")
            }

            HorizontalDivider(color = theme.ui.border)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
              OutlinedButton(
                onClick = onOpenSafFolder,
                modifier = Modifier.weight(1f).height(48.dp).testTag("welcome_open_saf"),
              ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open Folder", fontSize = 12.sp)
              }
              OutlinedButton(
                onClick = onImportZip,
                modifier = Modifier.weight(1f).height(48.dp).testTag("welcome_import_zip"),
              ) {
                Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import Zip", fontSize = 12.sp)
              }
            }
          }
        }

        val recentsCard: @Composable (Modifier) -> Unit = { cardMod ->
          Column(
            modifier =
              cardMod
                .clip(RoundedCornerShape(12.dp))
                .background(theme.ui.sidePanelBg)
                .border(1.dp, theme.ui.border, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text("Recent & Local Projects", color = theme.ui.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val combined = remember(recentWorkspaces, privateProjects) {
              val map = LinkedHashMap<String, WorkspaceRoot>()
              for (r in recentWorkspaces) map[r.id] = r
              for (p in privateProjects) map.putIfAbsent(p.id, p)
              map.values.toList()
            }
            if (combined.isEmpty()) {
              Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Text(
                  "Create a project or open a folder via SAF to start coding.",
                  color = theme.ui.textMuted,
                  fontSize = 13.sp,
                )
              }
            } else {
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (ws in combined.take(8)) {
                  Row(
                    modifier =
                      Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.ui.editorBg)
                        .border(0.5.dp, theme.ui.border, RoundedCornerShape(8.dp))
                        .clickable { onOpenWorkspace(ws) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                  ) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = theme.ui.accent, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                      Text(ws.name, color = theme.ui.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                      Text(
                        text = if (ws.backend == StorageBackend.SAF_EXTERNAL) "External SAF Folder" else "App Private Project",
                        color = theme.ui.textMuted,
                        fontSize = 11.sp,
                      )
                    }
                  }
                }
              }
            }
          }
        }

        if (twoColumn) {
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            newProjectCard(Modifier.weight(1.1f))
            recentsCard(Modifier.weight(0.9f))
          }
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            newProjectCard(Modifier.fillMaxWidth())
            recentsCard(Modifier.fillMaxWidth())
          }
        }
      }
    }
  }
}

@Composable
fun FindReplaceBar(
  findState: FindState,
  showReplace: Boolean,
  onQueryChange: (String, Boolean, Boolean, Boolean) -> Unit,
  onFindNext: () -> Unit,
  onFindPrev: () -> Unit,
  onReplaceOne: (String) -> Unit,
  onReplaceAll: (String) -> Unit,
  onClose: () -> Unit,
) {
  val theme = LocalQuarkTheme.current
  var query by remember(findState.query) { mutableStateOf(findState.query) }
  var replacement by remember { mutableStateOf("") }

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .background(theme.ui.tabBarBg)
        .border(0.5.dp, theme.ui.border)
        .padding(horizontal = 10.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      OutlinedTextField(
        value = query,
        onValueChange = {
          query = it
          onQueryChange(it, findState.isRegex, findState.caseSensitive, findState.wholeWord)
        },
        placeholder = { Text("Find...", fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.weight(1f).testTag("find_input"),
      )
      FilterChip(
        selected = findState.isRegex,
        onClick = { onQueryChange(query, !findState.isRegex, findState.caseSensitive, findState.wholeWord) },
        label = { Text(".*", fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp) },
      )
      FilterChip(
        selected = findState.caseSensitive,
        onClick = { onQueryChange(query, findState.isRegex, !findState.caseSensitive, findState.wholeWord) },
        label = { Text("Aa", fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp) },
      )
      Text(
        text = if (findState.matches.isEmpty()) "0/0" else "${findState.activeIndex + 1}/${findState.matches.size}",
        color = theme.ui.textSecondary,
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 11.sp,
      )
      IconButton(onClick = onFindPrev, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous Match", tint = theme.ui.textPrimary)
      }
      IconButton(onClick = onFindNext, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next Match", tint = theme.ui.textPrimary)
      }
      IconButton(onClick = onClose, modifier = Modifier.size(36.dp).testTag("find_close")) {
        Icon(Icons.Default.Close, contentDescription = "Close Find", tint = theme.ui.textSecondary)
      }
    }

    if (showReplace) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
          value = replacement,
          onValueChange = { replacement = it },
          placeholder = { Text("Replace with...", fontSize = 12.sp) },
          singleLine = true,
          modifier = Modifier.weight(1f).testTag("replace_input"),
        )
        OutlinedButton(onClick = { onReplaceOne(replacement) }) {
          Text("Replace", fontSize = 12.sp)
        }
        OutlinedButton(onClick = { onReplaceAll(replacement) }) {
          Text("All", fontSize = 12.sp)
        }
      }
    }
  }
}

@Composable
fun QuickOpenDialog(
  query: String,
  matches: List<FuzzyFileMatch>,
  onQueryChange: (String) -> Unit,
  onSelect: (FuzzyFileMatch) -> Unit,
  onDismiss: () -> Unit,
) {
  val theme = LocalQuarkTheme.current
  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = theme.ui.sidePanelBg,
      tonalElevation = 8.dp,
      modifier = Modifier.widthIn(min = 320.dp, max = 600.dp).fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = query,
          onValueChange = onQueryChange,
          placeholder = { Text("Quick Open file by path...") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("quick_open_input"),
        )
        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
          items(matches, key = { it.node.id }) { m ->
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(6.dp))
                  .clickable { onSelect(m) }
                  .padding(horizontal = 10.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              FileTypeBadgeIcon(fileName = m.node.name, size = 16.dp)
              val path = m.node.relativePath
              val matchSet = remember(m.matchedIndices) { m.matchedIndices.toSet() }
              Text(
                text = buildAnnotatedString {
                  for (i in path.indices) {
                    if (i in matchSet) {
                      withStyle(SpanStyle(color = theme.ui.accent, fontWeight = FontWeight.Bold)) {
                        append(path[i])
                      }
                    } else {
                      append(path[i])
                    }
                  }
                },
                color = theme.ui.textPrimary,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun CommandPaletteDialog(
  filterCommands: (String) -> List<PaletteCommand>,
  onExecute: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val theme = LocalQuarkTheme.current
  var query by remember { mutableStateOf("") }
  val itemsList = remember(query) { filterCommands(query) }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = theme.ui.sidePanelBg,
      tonalElevation = 8.dp,
      modifier = Modifier.widthIn(min = 340.dp, max = 620.dp).fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          placeholder = { Text("Type a command...") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("command_palette_input"),
        )
        LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
          items(itemsList, key = { it.id }) { cmd ->
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(6.dp))
                  .clickable { onExecute(cmd.id) }
                  .padding(horizontal = 10.dp, vertical = 9.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(cmd.title, color = theme.ui.textPrimary, fontSize = 13.sp)
              if (cmd.shortcut.isNotEmpty()) {
                Text(
                  text = cmd.shortcut,
                  color = theme.ui.textMuted,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 11.sp,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun GoToLineDialog(
  maxLine: Int,
  onConfirm: (Int, Int) -> Unit,
  onDismiss: () -> Unit,
) {
  var input by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Go to Line (1..$maxLine)") },
    text = {
      OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        placeholder = { Text("line or line:column") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("goto_line_input"),
      )
    },
    confirmButton = {
      Button(onClick = {
        val parts = input.trim().split(':')
        val line = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val col = parts.getOrNull(1)?.toIntOrNull() ?: 1
        onConfirm(line, col)
      }) {
        Text("Go")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

@Composable
fun TextPromptDialog(
  title: String,
  initialValue: String = "",
  placeholder: String = "",
  confirmLabel: String = "Confirm",
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var text by remember(initialValue) { mutableStateOf(initialValue) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      Button(onClick = { if (text.isNotBlank()) onConfirm(text) }) {
        Text(confirmLabel)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

@Composable
fun ThemePickerDialog(
  settings: AppSettings,
  onSelectTheme: (String) -> Unit,
  onToggleFollowSystem: (Boolean) -> Unit,
  onDismiss: () -> Unit,
) {
  val theme = LocalQuarkTheme.current
  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = theme.ui.sidePanelBg,
      tonalElevation = 8.dp,
      modifier =
        Modifier
          .widthIn(min = 340.dp, max = 560.dp)
          .fillMaxWidth()
          .border(1.dp, theme.ui.border, RoundedCornerShape(14.dp)),
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(QuarkIcons.Palette, contentDescription = null, tint = theme.ui.accent, modifier = Modifier.size(20.dp))
            Text("Color Theme", color = theme.ui.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
          }
          IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close Theme Picker", tint = theme.ui.textSecondary)
          }
        }

        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(theme.ui.editorBg)
              .border(0.5.dp, theme.ui.border, RoundedCornerShape(8.dp))
              .padding(horizontal = 12.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Follow System Dark/Light", color = theme.ui.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Auto-switch between One Dark and One Light", color = theme.ui.textMuted, fontSize = 11.sp)
          }
          Switch(checked = settings.followSystemTheme, onCheckedChange = onToggleFollowSystem)
        }

        LazyColumn(
          modifier = Modifier.heightIn(max = 360.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          items(QuarkThemes.all, key = { it.id }) { candidate ->
            val selected = candidate.id == settings.themeId
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .background(if (selected) theme.ui.selectionHighlight else theme.ui.editorBg)
                  .border(1.dp, if (selected) theme.ui.accent else theme.ui.border, RoundedCornerShape(8.dp))
                  .clickable { onSelectTheme(candidate.id) }
                  .padding(horizontal = 12.dp, vertical = 10.dp)
                  .testTag("theme_picker_${candidate.id}"),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column {
                Text(candidate.displayName, color = theme.ui.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(if (candidate.isDark) "Dark Palette" else "Light Palette", color = theme.ui.textMuted, fontSize = 11.sp)
              }
              Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(Color(candidate.editor.background), CircleShape).border(0.5.dp, theme.ui.border, CircleShape))
                Box(Modifier.size(14.dp).background(candidate.ui.accent, CircleShape))
                Box(Modifier.size(14.dp).background(Color(candidate.terminal.ansi16[2]), CircleShape))
                Box(Modifier.size(14.dp).background(Color(candidate.terminal.ansi16[5]), CircleShape))
              }
            }
          }
        }
      }
    }
  }
}

