package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import com.example.ui.icons.FileTypeBadgeIcon
import com.example.ui.icons.QuarkIcons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.ide.IdeUiState
import com.example.ide.SidePanelTab
import com.example.settings.AppSettings
import com.example.settings.EditorFontChoice
import com.example.settings.KeybindingPreset
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalQuarkTheme
import com.example.ui.theme.QuarkThemes
import com.quark.fs.VfsNode
import java.io.File

@Composable
fun ActivityRail(
  activeTab: SidePanelTab,
  sidePanelVisible: Boolean,
  gitChangeCount: Int = 0,
  onSelectTab: (SidePanelTab) -> Unit,
  onOpenWelcome: () -> Unit,
  onOpenThemePicker: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalQuarkTheme.current
  Column(
    modifier =
      modifier
        .width(52.dp)
        .fillMaxHeight()
        .background(theme.ui.activityRailBg)
        .border(0.5.dp, theme.ui.border)
        .padding(vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    RailIconButton(
      icon = QuarkIcons.FolderTree,
      label = "Explorer",
      selected = sidePanelVisible && activeTab == SidePanelTab.EXPLORER,
      testTag = "rail_explorer",
      onClick = { onSelectTab(SidePanelTab.EXPLORER) },
    )
    RailIconButton(
      icon = QuarkIcons.SearchGlass,
      label = "Search",
      selected = sidePanelVisible && activeTab == SidePanelTab.SEARCH,
      testTag = "rail_search",
      onClick = { onSelectTab(SidePanelTab.SEARCH) },
    )
    RailIconButton(
      icon = QuarkIcons.GitBranch,
      label = "Source Control (Git)",
      selected = sidePanelVisible && activeTab == SidePanelTab.GIT,
      badgeCount = gitChangeCount,
      testTag = "rail_git",
      onClick = { onSelectTab(SidePanelTab.GIT) },
    )
    RailIconButton(
      icon = QuarkIcons.CpuChip,
      label = "Runtime Capabilities",
      selected = sidePanelVisible && activeTab == SidePanelTab.RUNTIME,
      testTag = "rail_runtime",
      onClick = { onSelectTab(SidePanelTab.RUNTIME) },
    )
    Spacer(modifier = Modifier.weight(1f))
    RailIconButton(
      icon = QuarkIcons.Palette,
      label = "Color Themes",
      selected = false,
      testTag = "rail_themes",
      onClick = onOpenThemePicker,
    )
    RailIconButton(
      icon = QuarkIcons.CompassHome,
      label = "Welcome & Projects",
      selected = false,
      testTag = "rail_welcome",
      onClick = onOpenWelcome,
    )
    RailIconButton(
      icon = QuarkIcons.Sliders,
      label = "Settings",
      selected = sidePanelVisible && activeTab == SidePanelTab.SETTINGS,
      testTag = "rail_settings",
      onClick = { onSelectTab(SidePanelTab.SETTINGS) },
    )
  }
}

@Composable
private fun RailIconButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  selected: Boolean,
  testTag: String,
  badgeCount: Int = 0,
  onClick: () -> Unit,
) {
  val theme = LocalQuarkTheme.current
  Box(
    modifier =
      Modifier
        .size(48.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(if (selected) theme.ui.selectionHighlight else Color.Transparent)
        .clickable(onClick = onClick)
        .testTag(testTag),
    contentAlignment = Alignment.Center,
  ) {
    if (selected) {
      Box(
        modifier =
          Modifier
            .align(Alignment.CenterStart)
            .width(3.dp)
            .height(24.dp)
            .background(theme.ui.accent, RoundedCornerShape(2.dp))
      )
    }
    Icon(
      imageVector = icon,
      contentDescription = label,
      tint = if (selected) theme.ui.textPrimary else theme.ui.textMuted,
      modifier = Modifier.size(22.dp),
    )
    if (badgeCount > 0) {
      Box(
        modifier =
          Modifier
            .align(Alignment.TopEnd)
            .padding(top = 6.dp, end = 6.dp)
            .clip(CircleShape)
            .background(theme.ui.accent)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = if (badgeCount > 99) "99+" else badgeCount.toString(),
          color = theme.ui.onAccent,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

@Composable
fun ExplorerPanel(
  state: IdeUiState,
  onNodeClick: (VfsNode) -> Unit,
  onCreateFile: (VfsNode?) -> Unit,
  onCreateFolder: (VfsNode?) -> Unit,
  onRenameNode: (VfsNode) -> Unit,
  onDeleteNode: (VfsNode) -> Unit,
  onDuplicateNode: (VfsNode) -> Unit,
  onRefresh: () -> Unit,
  onExportZip: () -> Unit,
  onOpenSafFolder: () -> Unit,
) {
  val theme = LocalQuarkTheme.current
  Column(modifier = Modifier.fillMaxSize().background(theme.ui.sidePanelBg)) {
    // Header
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = state.workspace?.name?.uppercase() ?: "NO WORKSPACE",
        color = theme.ui.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      IconButton(
        onClick = { onCreateFile(null) },
        modifier = Modifier.size(36.dp).testTag("explorer_new_file"),
      ) {
        Icon(Icons.Default.Add, contentDescription = "New File", tint = theme.ui.textSecondary, modifier = Modifier.size(18.dp))
      }
      IconButton(
        onClick = { onCreateFolder(null) },
        modifier = Modifier.size(36.dp).testTag("explorer_new_folder"),
      ) {
        Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder", tint = theme.ui.textSecondary, modifier = Modifier.size(18.dp))
      }
      IconButton(
        onClick = onRefresh,
        modifier = Modifier.size(36.dp).testTag("explorer_refresh"),
      ) {
        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = theme.ui.textSecondary, modifier = Modifier.size(18.dp))
      }
      IconButton(
        onClick = onExportZip,
        modifier = Modifier.size(36.dp).testTag("explorer_export_zip"),
      ) {
        Icon(Icons.Default.Archive, contentDescription = "Export Zip", tint = theme.ui.textSecondary, modifier = Modifier.size(18.dp))
      }
    }

    HorizontalDivider(color = theme.ui.border)

    if (state.workspace == null) {
      Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("No folder opened", color = theme.ui.textSecondary, fontSize = 13.sp)
          OutlinedButton(onClick = onOpenSafFolder) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open External Folder")
          }
        }
      }
    } else if (state.visibleTreeItems.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text("Empty workspace. Tap + to create a file.", color = theme.ui.textMuted, fontSize = 12.sp)
      }
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.visibleTreeItems, key = { it.node.id }) { item ->
          FileTreeRow(
            node = item.node,
            depth = item.depth,
            isExpanded = item.isExpanded,
            isActive = item.node.id == state.activeTabId,
            onClick = { onNodeClick(item.node) },
            onCreateFileInside = { onCreateFile(item.node) },
            onCreateFolderInside = { onCreateFolder(item.node) },
            onRename = { onRenameNode(item.node) },
            onDelete = { onDeleteNode(item.node) },
            onDuplicate = { onDuplicateNode(item.node) },
          )
        }
      }
    }
  }
}

@Composable
private fun FileTreeRow(
  node: VfsNode,
  depth: Int,
  isExpanded: Boolean,
  isActive: Boolean,
  onClick: () -> Unit,
  onCreateFileInside: () -> Unit,
  onCreateFolderInside: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
  onDuplicate: () -> Unit,
) {
  val theme = LocalQuarkTheme.current
  var menuOpen by remember { mutableStateOf(false) }

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(38.dp)
        .background(if (isActive) theme.ui.selectionHighlight else Color.Transparent)
        .clickable(onClick = onClick)
        .padding(start = (8 + depth * 14).dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (node.isDirectory) {
      Icon(
        imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
        contentDescription = null,
        tint = theme.ui.textSecondary,
        modifier = Modifier.size(16.dp),
      )
      Spacer(Modifier.width(4.dp))
      Icon(
        imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
        contentDescription = null,
        tint = theme.ui.accent,
        modifier = Modifier.size(16.dp),
      )
    } else {
      Spacer(Modifier.width(12.dp))
      FileTypeBadgeIcon(fileName = node.name)
    }
    Spacer(Modifier.width(8.dp))
    Text(
      text = node.name,
      color = if (isActive) theme.ui.textPrimary else theme.ui.textSecondary,
      fontSize = 13.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )

    Box {
      IconButton(
        onClick = { menuOpen = true },
        modifier = Modifier.size(32.dp),
      ) {
        Icon(
          Icons.Default.MoreVert,
          contentDescription = "File actions",
          tint = theme.ui.textMuted,
          modifier = Modifier.size(16.dp),
        )
      }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        if (node.isDirectory) {
          DropdownMenuItem(
            text = { Text("New File") },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
            onClick = {
              menuOpen = false
              onCreateFileInside()
            },
          )
          DropdownMenuItem(
            text = { Text("New Folder") },
            leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
            onClick = {
              menuOpen = false
              onCreateFolderInside()
            },
          )
        } else {
          DropdownMenuItem(
            text = { Text("Duplicate") },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
            onClick = {
              menuOpen = false
              onDuplicate()
            },
          )
        }
        DropdownMenuItem(
          text = { Text("Rename") },
          leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
          onClick = {
            menuOpen = false
            onRename()
          },
        )
        DropdownMenuItem(
          text = { Text("Delete", color = theme.ui.danger) },
          leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = theme.ui.danger) },
          onClick = {
            menuOpen = false
            onDelete()
          },
        )
      }
    }
  }
}

@Composable
fun GlobalSearchPanel(
  state: IdeUiState,
  onParamsChange: (String, Boolean, Boolean, String) -> Unit,
  onRunSearch: () -> Unit,
  onOpenMatch: (VfsNode, Int, Int) -> Unit,
) {
  val theme = LocalQuarkTheme.current
  Column(modifier = Modifier.fillMaxSize().background(theme.ui.sidePanelBg).padding(10.dp)) {
    Text(
      text = "GLOBAL SEARCH",
      color = theme.ui.textSecondary,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 0.8.sp,
    )
    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
      value = state.searchQuery,
      onValueChange = { onParamsChange(it, state.searchRegex, state.searchCaseSensitive, state.searchPathFilter) },
      placeholder = { Text("Search across files...", fontSize = 13.sp) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("global_search_input"),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = theme.ui.accent,
        unfocusedBorderColor = theme.ui.border,
        focusedTextColor = theme.ui.textPrimary,
        unfocusedTextColor = theme.ui.textPrimary,
      ),
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      FilterChip(
        selected = state.searchRegex,
        onClick = { onParamsChange(state.searchQuery, !state.searchRegex, state.searchCaseSensitive, state.searchPathFilter) },
        label = { Text(".*", fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = theme.ui.accent.copy(alpha = 0.25f)),
      )
      FilterChip(
        selected = state.searchCaseSensitive,
        onClick = { onParamsChange(state.searchQuery, state.searchRegex, !state.searchCaseSensitive, state.searchPathFilter) },
        label = { Text("Aa", fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = theme.ui.accent.copy(alpha = 0.25f)),
      )
      Spacer(Modifier.weight(1f))
      OutlinedButton(onClick = onRunSearch, modifier = Modifier.testTag("global_search_run")) {
        Text("Search", fontSize = 12.sp)
      }
    }
    OutlinedTextField(
      value = state.searchPathFilter,
      onValueChange = { onParamsChange(state.searchQuery, state.searchRegex, state.searchCaseSensitive, it) },
      placeholder = { Text("Filter path (e.g. src/)", fontSize = 12.sp) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )

    if (state.isSearchingGlobal) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
    } else {
      val totalHits = state.globalSearchResults.sumOf { it.matches.size }
      Text(
        text = "$totalHits match(es) in ${state.globalSearchResults.size} file(s)",
        color = theme.ui.textMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 6.dp),
      )
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
      for (fileResult in state.globalSearchResults) {
        item(key = "hdr_${fileResult.node.id}") {
          Row(
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            FileTypeBadgeIcon(fileName = fileResult.node.name, size = 15.dp)
            Text(
              text = fileResult.node.relativePath,
              color = theme.ui.textPrimary,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        items(fileResult.matches, key = { "${fileResult.node.id}_${it.lineNumber1Based}_${it.column1Based}" }) { m ->
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable { onOpenMatch(fileResult.node, m.lineNumber1Based, m.column1Based) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "${m.lineNumber1Based}:",
              color = theme.ui.textMuted,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 11.sp,
              modifier = Modifier.width(34.dp),
            )
            Text(
              text = buildAnnotatedString {
                val p = m.linePreview
                val s = m.matchStartInPreview.coerceIn(0, p.length)
                val e = m.matchEndInPreview.coerceIn(s, p.length)
                append(p.substring(0, s))
                withStyle(SpanStyle(background = theme.ui.accent.copy(alpha = 0.35f), fontWeight = FontWeight.Bold)) {
                  append(p.substring(s, e))
                }
                append(p.substring(e))
              },
              color = theme.ui.textSecondary,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 11.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

@Composable
fun RuntimeCapabilitiesPanel() {
  val theme = LocalQuarkTheme.current
  val context = LocalContext.current
  val nativeDir = context.applicationInfo.nativeLibraryDir
  val appBinDir = File(context.filesDir, "bin").absolutePath
  val isPlay = BuildConfig.FLAVOR_NAME == "play"

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(theme.ui.sidePanelBg)
        .verticalScroll(rememberScrollState())
        .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      text = "RUNTIME CAPABILITIES",
      color = theme.ui.textSecondary,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 0.8.sp,
    )

    CapabilityCard(
      title = "Active Build Mode: ${BuildConfig.FLAVOR_NAME.uppercase()}",
      body = "targetSdk = ${BuildConfig.TARGET_SDK_INT}, minSdk = 26.\n" +
        if (isPlay) {
          "Play mode targets API 35 (Android W^X enforcement active). Executables may ONLY run from the read-only jniLibs directory (nativeLibraryDir). The package manager installs scripts and data packages, and refuses native binaries. Build with -PquarkFlavor=sideload for API 28."
        } else {
          "Sideload mode targets API 28. Android permits execve() from writable app-private storage, so the package manager can install native CLI binaries into the app bin directory and expose them on PATH."
        },
      badgeColor = if (isPlay) theme.ui.warning else theme.ui.success,
    )

    CapabilityCard(
      title = "Executable Paths",
      body = "nativeLibraryDir:\n$nativeDir\n\nApp private bin:\n$appBinDir\n\nWritable exec allowed: ${BuildConfig.CAN_EXEC_APP_BIN}",
      badgeColor = theme.ui.accent,
    )

    CapabilityCard(
      title = "100% On-Device Runtimes & IntelliSense",
      body = "Zero telemetry, zero external APIs, zero cloud backend.\n• Built-in AST runners: Kotlin (.kt/.kts), Python (.py), Java (.java)\n• WebView V8 Engine: JavaScript & TypeScript\n• Live Preview: HTML/CSS/JS\n• Shell: /system/bin/sh + Pure-Kotlin Git CLI\n• On-device symbol index, completion & live diagnostics",
      badgeColor = theme.ui.success,
    )
  }
}

@Composable
private fun CapabilityCard(title: String, body: String, badgeColor: Color) {
  val theme = LocalQuarkTheme.current
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(theme.ui.editorBg)
        .border(1.dp, theme.ui.border, RoundedCornerShape(8.dp))
        .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Box(modifier = Modifier.size(8.dp).background(badgeColor, CircleShape))
      Text(title, color = theme.ui.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    Text(body, color = theme.ui.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
  }
}

@Composable
fun SettingsPanel(
  settings: AppSettings,
  onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
  val theme = LocalQuarkTheme.current
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(theme.ui.sidePanelBg)
        .verticalScroll(rememberScrollState())
        .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = "SETTINGS",
      color = theme.ui.textSecondary,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 0.8.sp,
    )

    // Themes
    Text("Theme (UI + Syntax + Terminal)", color = theme.ui.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    SettingSwitchRow("Follow System Dark/Light", settings.followSystemTheme) { v ->
      onUpdate { it.copy(followSystemTheme = v) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      for (t in QuarkThemes.all) {
        val selected = t.id == settings.themeId
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(if (selected) theme.ui.selectionHighlight else theme.ui.editorBg)
              .border(1.dp, if (selected) theme.ui.accent else theme.ui.border, RoundedCornerShape(8.dp))
              .clickable { onUpdate { it.copy(themeId = t.id, followSystemTheme = false) } }
              .padding(horizontal = 10.dp, vertical = 8.dp)
              .testTag("theme_${t.id}"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column {
            Text(t.displayName, color = theme.ui.textPrimary, fontSize = 13.sp)
            Text(if (t.isDark) "Dark" else "Light", color = theme.ui.textMuted, fontSize = 10.sp)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(12.dp).background(Color(t.editor.background), CircleShape).border(0.5.dp, theme.ui.border, CircleShape))
            Box(Modifier.size(12.dp).background(t.ui.accent, CircleShape))
            Box(Modifier.size(12.dp).background(Color(t.terminal.ansi16[2]), CircleShape))
            Box(Modifier.size(12.dp).background(Color(t.terminal.ansi16[5]), CircleShape))
          }
        }
      }
    }

    HorizontalDivider(color = theme.ui.border)

    // Font Family & Size
    Text("Editor Font", color = theme.ui.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      for (fc in EditorFontChoice.entries) {
        FilterChip(
          selected = settings.fontChoice == fc,
          onClick = { onUpdate { it.copy(fontChoice = fc) } },
          label = { Text(fc.label, fontSize = 11.sp) },
        )
      }
    }

    Column {
      Text("Font Size: ${settings.fontSizeSp.toInt()} sp", color = theme.ui.textSecondary, fontSize = 12.sp)
      Slider(
        value = settings.fontSizeSp,
        onValueChange = { v -> onUpdate { it.copy(fontSizeSp = v) } },
        valueRange = 10f..26f,
        steps = 15,
      )
    }

    SettingSwitchRow("Font Ligatures", settings.ligaturesEnabled) { v ->
      onUpdate { it.copy(ligaturesEnabled = v) }
    }

    // Tab Size
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Tab Size:", color = theme.ui.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
      for (ts in listOf(2, 4, 8)) {
        FilterChip(
          selected = settings.tabSize == ts,
          onClick = { onUpdate { it.copy(tabSize = ts) } },
          label = { Text("$ts", fontSize = 12.sp) },
        )
      }
    }

    SettingSwitchRow("Insert Spaces for Tabs", settings.insertSpaces) { v ->
      onUpdate { it.copy(insertSpaces = v) }
    }
    SettingSwitchRow("Word Wrap", settings.wordWrap) { v ->
      onUpdate { it.copy(wordWrap = v) }
    }
    SettingSwitchRow("Show Line Numbers", settings.showLineNumbers) { v ->
      onUpdate { it.copy(showLineNumbers = v) }
    }
    SettingSwitchRow("Show Indent Guides", settings.showIndentGuides) { v ->
      onUpdate { it.copy(showIndentGuides = v) }
    }
    SettingSwitchRow("Auto-Save", settings.autoSave) { v ->
      onUpdate { it.copy(autoSave = v) }
    }
    SettingSwitchRow("Format on Save", settings.formatOnSave) { v ->
      onUpdate { it.copy(formatOnSave = v) }
    }
    SettingSwitchRow("Auto-Close Brackets", settings.autoCloseBrackets) { v ->
      onUpdate { it.copy(autoCloseBrackets = v) }
    }
    SettingSwitchRow("Extra-Key Row", settings.showExtraKeyRow) { v ->
      onUpdate { it.copy(showExtraKeyRow = v) }
    }

    Text("Keybinding Preset", color = theme.ui.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (preset in KeybindingPreset.entries) {
        FilterChip(
          selected = settings.keybindingPreset == preset,
          onClick = { onUpdate { it.copy(keybindingPreset = preset) } },
          label = { Text(preset.label, fontSize = 12.sp) },
        )
      }
    }

    HorizontalDivider(color = theme.ui.border)

    Text("Git Identity", color = theme.ui.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    OutlinedTextField(
      value = settings.gitAuthorName,
      onValueChange = { v -> onUpdate { it.copy(gitAuthorName = v) } },
      label = { Text("Author Name", fontSize = 11.sp) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = settings.gitAuthorEmail,
      onValueChange = { v -> onUpdate { it.copy(gitAuthorEmail = v) } },
      label = { Text("Author Email", fontSize = 11.sp) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  val theme = LocalQuarkTheme.current
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(label, color = theme.ui.textSecondary, fontSize = 13.sp)
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}
