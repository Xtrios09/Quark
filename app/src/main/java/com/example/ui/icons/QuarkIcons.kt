package com.example.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalQuarkTheme

/**
 * Custom Lucide/Feather-inspired open-source vector icons crafted directly in Kotlin
 * with zero external icon-pack dependencies.
 */
object QuarkIcons {

  val FolderTree: ImageVector by lazy {
    strokeIcon("FolderTree") {
      // Top folder outline
      moveTo(3f, 5f)
      horizontalLineTo(9f)
      lineTo(11f, 7f)
      horizontalLineTo(20f)
      arcToRelative(1f, 1f, 0f, false, true, 1f, 1f)
      verticalLineTo(18f)
      arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
      horizontalLineTo(4f)
      arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
      close()
    }
  }

  val SearchGlass: ImageVector by lazy {
    strokeIcon("SearchGlass") {
      moveTo(18f, 11f)
      arcToRelative(7f, 7f, 0f, true, true, -14f, 0f)
      arcToRelative(7f, 7f, 0f, true, true, 14f, 0f)
      moveTo(21f, 21f)
      lineTo(16.2f, 16.2f)
    }
  }

  val Palette: ImageVector by lazy {
    strokeIcon("Palette") {
      moveTo(12f, 3f)
      arcToRelative(9f, 9f, 0f, true, false, 0f, 18f)
      horizontalLineTo(13.5f)
      arcToRelative(2.2f, 2.2f, 0f, false, false, 0f, -4.4f)
      horizontalLineTo(12.2f)
      arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f)
      curveTo(10.7f, 14.3f, 11.4f, 13.6f, 12.2f, 13.6f)
      horizontalLineTo(15f)
      arcToRelative(6f, 6f, 0f, false, false, 6f, -6f)
      curveTo(21f, 4.9f, 17f, 3f, 12f, 3f)
      close()
      // Swatch dots
      moveTo(7.5f, 10.5f); lineTo(7.6f, 10.5f)
      moveTo(10.5f, 7.2f); lineTo(10.6f, 7.2f)
      moveTo(14.8f, 7.5f); lineTo(14.9f, 7.5f)
    }
  }

  val CpuChip: ImageVector by lazy {
    strokeIcon("CpuChip") {
      moveTo(7f, 7f); horizontalLineTo(17f); verticalLineTo(17f); horizontalLineTo(7f); close()
      moveTo(10f, 10f); horizontalLineTo(14f); verticalLineTo(14f); horizontalLineTo(10f); close()
      moveTo(9f, 3f); verticalLineTo(7f)
      moveTo(15f, 3f); verticalLineTo(7f)
      moveTo(9f, 17f); verticalLineTo(21f)
      moveTo(15f, 17f); verticalLineTo(21f)
      moveTo(3f, 9f); horizontalLineTo(7f)
      moveTo(3f, 15f); horizontalLineTo(7f)
      moveTo(17f, 9f); horizontalLineTo(21f)
      moveTo(17f, 15f); horizontalLineTo(21f)
    }
  }

  val Sliders: ImageVector by lazy {
    strokeIcon("Sliders") {
      moveTo(4f, 21f); verticalLineTo(14f)
      moveTo(4f, 10f); verticalLineTo(3f)
      moveTo(12f, 21f); verticalLineTo(12f)
      moveTo(12f, 8f); verticalLineTo(3f)
      moveTo(20f, 21f); verticalLineTo(16f)
      moveTo(20f, 12f); verticalLineTo(3f)
      moveTo(2f, 14f); horizontalLineTo(6f)
      moveTo(10f, 8f); horizontalLineTo(14f)
      moveTo(18f, 16f); horizontalLineTo(22f)
    }
  }

  val CompassHome: ImageVector by lazy {
    strokeIcon("CompassHome") {
      moveTo(12f, 3f)
      arcToRelative(9f, 9f, 0f, true, true, 0f, 18f)
      arcToRelative(9f, 9f, 0f, true, true, 0f, -18f)
      moveTo(15.5f, 8.5f)
      lineTo(13.8f, 13.8f)
      lineTo(8.5f, 15.5f)
      lineTo(10.2f, 10.2f)
      close()
    }
  }

  val PlayTriangle: ImageVector by lazy {
    ImageVector.Builder(
      name = "PlayTriangle",
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    ).apply {
      path(
        fill = SolidColor(Color.Black),
        pathFillType = PathFillType.NonZero,
      ) {
        moveTo(6f, 4.5f)
        lineTo(19.5f, 12f)
        lineTo(6f, 19.5f)
        close()
      }
    }.build()
  }

  val QuickOpenFile: ImageVector by lazy {
    strokeIcon("QuickOpenFile") {
      moveTo(14f, 3f)
      horizontalLineTo(7f)
      arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
      verticalLineTo(19f)
      arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
      horizontalLineTo(17f)
      arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
      verticalLineTo(8f)
      close()
      moveTo(14f, 3f); verticalLineTo(8f); horizontalLineTo(19f)
      moveTo(9.5f, 14.5f); lineTo(11.5f, 12.5f); lineTo(9.5f, 10.5f)
      moveTo(13f, 15.5f); horizontalLineTo(15.5f)
    }
  }

  val TerminalPrompt: ImageVector by lazy {
    strokeIcon("TerminalPrompt") {
      moveTo(4f, 5f)
      horizontalLineTo(20f)
      arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
      verticalLineTo(17f)
      arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
      horizontalLineTo(4f)
      arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
      verticalLineTo(7f)
      arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
      close()
      moveTo(6.5f, 10f); lineTo(9.5f, 12.5f); lineTo(6.5f, 15f)
      moveTo(11.5f, 15f); horizontalLineTo(16.5f)
    }
  }

  val SplitPane: ImageVector by lazy {
    strokeIcon("SplitPane") {
      moveTo(3f, 5f)
      horizontalLineTo(21f)
      verticalLineTo(19f)
      horizontalLineTo(3f)
      close()
      moveTo(12f, 5f); verticalLineTo(19f)
    }
  }

  val SaveDisk: ImageVector by lazy {
    strokeIcon("SaveDisk") {
      moveTo(5f, 4f); horizontalLineTo(16f); lineTo(20f, 8f); verticalLineTo(20f); horizontalLineTo(4f); verticalLineTo(5f); close()
      moveTo(8f, 4f); verticalLineTo(9f); horizontalLineTo(15f); verticalLineTo(4f)
      moveTo(8f, 20f); verticalLineTo(14f); horizontalLineTo(16f); verticalLineTo(20f)
    }
  }

  val UndoArrow: ImageVector by lazy {
    strokeIcon("UndoArrow") {
      moveTo(9f, 14f); lineTo(4f, 9f); lineTo(9f, 4f)
      moveTo(4f, 9f); horizontalLineTo(14.5f)
      arcToRelative(5.5f, 5.5f, 0f, false, true, 0f, 11f)
      horizontalLineTo(11f)
    }
  }

  val RedoArrow: ImageVector by lazy {
    strokeIcon("RedoArrow") {
      moveTo(15f, 14f); lineTo(20f, 9f); lineTo(15f, 4f)
      moveTo(20f, 9f); horizontalLineTo(9.5f)
      arcToRelative(5.5f, 5.5f, 0f, false, false, 0f, 11f)
      horizontalLineTo(13f)
    }
  }

  val GitBranch: ImageVector by lazy {
    strokeIcon("GitBranch") {
      moveTo(6f, 3f); verticalLineTo(15f)
      moveTo(18f, 6f)
      arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
      arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
      moveTo(9f, 18f)
      arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
      arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
      moveTo(15f, 9f)
      arcToRelative(9f, 9f, 0f, false, true, -9f, 9f)
    }
  }

  private fun strokeIcon(
    name: String,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
  ): ImageVector =
    ImageVector.Builder(
      name = name,
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    ).apply {
      path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.9f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
      )
    }.build()
}

private data class DeviconSpec(
  val drawableRes: Int,
  val adaptiveTintDark: Boolean = false,
  val darkModeTint: Color? = null,
)

private fun resolveDevicon(fileName: String): DeviconSpec? {
  val lower = fileName.lowercase()
  if (lower == "build.gradle" || lower == "build.gradle.kts" || lower == "settings.gradle" || lower == "settings.gradle.kts" || lower.endsWith(".gradle")) {
    return DeviconSpec(R.drawable.ic_devicon_gradle, darkModeTint = Color(0xFF43C5E2))
  }
  if (lower == "androidmanifest.xml") {
    return DeviconSpec(R.drawable.ic_devicon_android)
  }
  if (lower == "cmakelists.txt" || lower.endsWith(".cmake")) {
    return DeviconSpec(R.drawable.ic_devicon_cmake)
  }
  if (lower == "dockerfile" || lower.startsWith("dockerfile.") || lower == "docker-compose.yml" || lower == "docker-compose.yaml") {
    return DeviconSpec(R.drawable.ic_devicon_docker)
  }
  if (lower == ".gitignore" || lower == ".gitattributes" || lower == ".gitmodules") {
    return DeviconSpec(R.drawable.ic_devicon_git)
  }

  return when (lower.substringAfterLast('.', "")) {
    "kt", "kts" -> DeviconSpec(R.drawable.ic_devicon_kotlin)
    "java" -> DeviconSpec(R.drawable.ic_devicon_java)
    "js", "mjs", "cjs" -> DeviconSpec(R.drawable.ic_devicon_javascript)
    "ts" -> DeviconSpec(R.drawable.ic_devicon_typescript)
    "jsx", "tsx" -> DeviconSpec(R.drawable.ic_devicon_react)
    "py", "pyw" -> DeviconSpec(R.drawable.ic_devicon_python)
    "c", "h" -> DeviconSpec(R.drawable.ic_devicon_c)
    "cpp", "cc", "cxx", "hpp", "hh" -> DeviconSpec(R.drawable.ic_devicon_cplusplus)
    "rs" -> DeviconSpec(R.drawable.ic_devicon_rust, adaptiveTintDark = true)
    "go" -> DeviconSpec(R.drawable.ic_devicon_go)
    "html", "htm" -> DeviconSpec(R.drawable.ic_devicon_html5)
    "css", "scss", "sass" -> DeviconSpec(R.drawable.ic_devicon_css3)
    "json", "jsonc" -> DeviconSpec(R.drawable.ic_devicon_json, darkModeTint = Color(0xFFE5C07B))
    "xml", "svg" -> DeviconSpec(R.drawable.ic_devicon_xml)
    "yaml", "yml" -> DeviconSpec(R.drawable.ic_devicon_yaml)
    "md", "markdown", "mdx" -> DeviconSpec(R.drawable.ic_devicon_markdown, adaptiveTintDark = true)
    "sh", "bash", "zsh" -> DeviconSpec(R.drawable.ic_devicon_bash)
    "sql", "sqlite", "db" -> DeviconSpec(R.drawable.ic_devicon_sqlite)
    "toml" -> DeviconSpec(R.drawable.ic_devicon_toml)
    "lua" -> DeviconSpec(R.drawable.ic_devicon_lua)
    "rb" -> DeviconSpec(R.drawable.ic_devicon_ruby)
    "php" -> DeviconSpec(R.drawable.ic_devicon_php)
    "swift" -> DeviconSpec(R.drawable.ic_devicon_swift)
    "dart" -> DeviconSpec(R.drawable.ic_devicon_dart)
    else -> null
  }
}

/**
 * Devicon icon (https://github.com/devicons/devicon, MIT) rendered in the Explorer tree,
 * Editor tabs, and Quick Open results, with clean fallback badge for generic text files.
 */
@Composable
fun FileTypeBadgeIcon(
  fileName: String,
  modifier: Modifier = Modifier,
  size: Dp = 18.dp,
) {
  val theme = LocalQuarkTheme.current
  val spec = resolveDevicon(fileName)
  if (spec != null) {
    val tintColor =
      when {
        spec.adaptiveTintDark -> theme.ui.textPrimary
        theme.isDark && spec.darkModeTint != null -> spec.darkModeTint
        else -> null
      }
    Image(
      painter = painterResource(id = spec.drawableRes),
      contentDescription = "$fileName file icon",
      contentScale = ContentScale.Fit,
      colorFilter = tintColor?.let { ColorFilter.tint(it) },
      modifier = modifier.size(size),
    )
  } else {
    val ext = fileName.substringAfterLast('.', "").uppercase().take(3).ifEmpty { "TXT" }
    val badgeColor = theme.ui.textMuted
    Box(
      modifier =
        modifier
          .size(width = 20.dp, height = 16.dp)
          .clip(RoundedCornerShape(4.dp))
          .background(badgeColor.copy(alpha = 0.14f))
          .border(0.8.dp, badgeColor.copy(alpha = 0.45f), RoundedCornerShape(4.dp)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = ext,
        color = theme.ui.textSecondary,
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = if (ext.length > 2) 6.5.sp else 7.5.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
      )
    }
  }
}
