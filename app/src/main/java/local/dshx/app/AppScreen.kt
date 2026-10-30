package local.dshx.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.StickyNote
import com.composables.icons.lucide.SkipForward
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Package
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MicroAppDialog(id: String, host: AppHost, exporting: Boolean = false, export: (() -> Unit)? = null,
    exportMessage: String? = null, exportFailed: Boolean = false, close: () -> Unit) {
    var page by remember(id) { mutableStateOf<AppHost.Page?>(null) }
    var failure by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { try { page = host.open(id) } catch (error: Exception) { failure = error.message } }
    val current = page
    DisposableEffect(current) {
        current?.onClosed = { failure = "应用已停止，请关闭页面后重新打开。"; page = null }
        onDispose { current?.onClosed = null; current?.close() }
    }
    val colors = MiuixTheme.colorScheme
    val theme = remember(colors) { JSONObject().put("--bg", cssColor(colors.surface.toArgb())).put("--fg", cssColor(colors.onSurface.toArgb()))
        .put("--surface", cssColor(colors.surfaceVariant.toArgb())).put("--line", cssColor(colors.outline.toArgb()))
        .put("--muted", cssColor(colors.onSurfaceVariantSummary.toArgb())).put("--accent", cssColor(colors.primary.toArgb()))
        .put("--input", cssColor(colors.surfaceContainerHigh.toArgb()))
        .put("color-scheme", if (colors.surface.luminance() > .5f) "light" else "dark") }
    SideEffect { current?.appearance(theme, colors.surface.toArgb()) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = colors.surface.luminance() > .5f
                    isAppearanceLightNavigationBars = colors.surface.luminance() > .5f
                }
            }
        }
        Column(Modifier.fillMaxSize().background(colors.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppSymbol(id)
                Text(current?.installed?.app?.name ?: "打开应用", modifier = Modifier.weight(1f).semantics { heading() },
                    fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LibraryIcon(Lucide.Package, "打包 APK", { export?.invoke() }, enabled = export != null && !exporting)
                LibraryIcon(Lucide.X, "关闭微应用", close)
            }
            Divider()
            if (exporting || exportMessage != null) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                AppNotice(if (exporting) "正在打包…" else exportMessage!!, error = exportFailed)
            }
            if (failure != null) Column(Modifier.padding(16.dp)) { AppNotice(failure!!, error = true) }
            else if (current != null) AndroidView(factory = { current.view }, modifier = Modifier.fillMaxWidth().weight(1f))
            else LibraryEmpty(Lucide.LayoutGrid, "正在打开…", "应用准备好后将显示在这里。")
        }
    }
}

@Composable
internal fun AppSettingField(field: JSONObject, draft: JSONObject, enabled: Boolean, change: (Any) -> Unit) {
    val key = field.getString("id")
    val label = field.getString("label")
    val number = field.getString("type") == "number"
    val colors = MiuixTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
        BasicTextField(value = draft.opt(key)?.toString().orEmpty(), onValueChange = { text ->
            change(if (number) text.toDoubleOrNull() ?: text else text)
        }, enabled = enabled, textStyle = androidx.compose.ui.text.TextStyle(
            color = if (enabled) colors.onSurface else colors.onSurfaceVariantSummary, fontSize = 16.sp, lineHeight = 24.sp),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label }.clip(RoundedCornerShape(8.dp))
                .background(if (enabled) colors.surfaceContainerHigh else colors.secondary)
                .border(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.outline, RoundedCornerShape(8.dp))
                .padding(12.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (number) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text))
    }
}

@Composable
internal fun AppSymbol(id: String? = null, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val dark = colors.surface.luminance() < .5f
    val palette = remember(dark) { AppColors(dark) }
    val (icon, background, foreground) = when (id) {
        "quick-notes" -> Triple(Lucide.StickyNote, Color(palette.noteBackground), Color(palette.noteForeground))
        "skip-startup-ads" -> Triple(Lucide.SkipForward, Color(palette.screenBackground), Color(palette.screenForeground))
        else -> Triple(Lucide.LayoutGrid, colors.secondary, colors.onSurfaceVariantSummary)
    }
    Box(modifier.size(size).clip(RoundedCornerShape(if (size >= 68.dp) 18.dp else 8.dp)).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(size * .55f), tint = foreground)
    }
}

@Composable
internal fun AppNotice(text: String, error: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    val tint = if (error) colors.error else colors.onSurfaceVariantSummary
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(if (error) colors.error.copy(alpha = .08f) else colors.secondary)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(if (error) Lucide.CircleAlert else Lucide.Info, contentDescription = null,
            modifier = Modifier.padding(top = 2.dp).size(16.dp), tint = tint)
        Text(text, modifier = Modifier.weight(1f), fontSize = 13.sp, lineHeight = 20.sp, color = tint)
    }
}

private fun cssColor(color: Int) = "#%06x".format(color and 0xffffff)
