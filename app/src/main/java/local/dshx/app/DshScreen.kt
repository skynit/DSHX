package local.dshx.app

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Settings2
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperRadioButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

data class BundleInfo(val dsh: String, val node: String, val abi: String, val unpackedSize: Long)

private val LocalStatusColor = staticCompositionLocalOf { Color(0xFF23845F) }

/** One semantic palette is shared by the Miuix controls and product surfaces. */
@Composable
private fun AppTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || theme == "system" && isSystemInDarkTheme()
    val background = if (dark) Color(0xFF171917) else Color(0xFFF7F7F5)
    val surface = if (dark) Color(0xFF202320) else Color(0xFFFCFCFB)
    val text = if (dark) Color(0xFFECEFEB) else Color(0xFF202220)
    val muted = if (dark) Color(0xFFA6ACA5) else Color(0xFF6D726D)
    val border = if (dark) Color(0xFF363B36) else Color(0xFFE4E5E2)
    val primary = if (dark) Color(0xFF78A7EF) else Color(0xFF3F7EDB)
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    val view = LocalView.current
    @Suppress("DEPRECATION")
    SideEffect {
        (view.context as? Activity)?.window?.apply {
            statusBarColor = background.toArgb()
            navigationBarColor = surface.toArgb()
            decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
    CompositionLocalProvider(LocalStatusColor provides if (dark) Color(0xFF63B58B) else Color(0xFF23845F)) {
        MiuixTheme(colors = colors.copy(
            primary = primary, onPrimary = if (dark) Color(0xFF172336) else Color.White,
            surface = background, onSurface = text, surfaceVariant = surface,
            onSurfaceSecondary = text, onSurfaceVariantSummary = muted,
            onSurfaceVariantActions = muted, surfaceContainer = surface,
            onSurfaceContainer = text, onSurfaceContainerVariant = muted,
            outline = border, dividerLine = border,
            secondaryVariant = border, onSecondaryVariant = text,
            disabledPrimaryButton = border, disabledOnPrimaryButton = muted,
            background = background, onBackground = text,
        ), content = content)
    }
}

@Composable
fun DshScreen(
    state: EngineState, bundle: BundleInfo, webVersion: String?, theme: String,
    onTheme: (String) -> Unit, onStart: () -> Unit, onStop: () -> Unit,
    diagnostics: () -> String, pageError: String?, onReload: () -> Unit,
    chat: @Composable (Modifier) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var logs by rememberSaveable { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    val scroll = remember(tab, logs) { ScrollState(0) }
    val running = state.url != null
    val active = running || state.busy
    val webReady = (webVersion?.substringBefore('.')?.toIntOrNull() ?: 0) >= 126
    var keyboardOpen by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(view) {
        val visible = Rect()
        // The target-28 window resizes for the keyboard before Compose receives
        // its insets. Observe the visible window instead of consumed IME insets.
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            view.getWindowVisibleDisplayFrame(visible)
            keyboardOpen = view.rootView.height - visible.bottom > view.rootView.height / 5
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    fun openLogs() { logText = diagnostics(); logs = true }
    BackHandler(logs || tab != 0) { if (logs) logs = false else tab = 0 }
    AppTheme(theme) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!(tab == 1 && !logs && keyboardOpen)) NavigationBar(defaultWindowInsetsPadding = false) {
                    listOf(Triple("工作台", Lucide.House, 0), Triple("聊天", Lucide.MessageSquare, 1), Triple("设置", Lucide.Settings2, 2)).forEach { (label, icon, index) ->
                        NavigationBarItem(selected = tab == index && !logs, onClick = { tab = index; logs = false }, icon = icon, label = label)
                    }
                }
            },
        ) { padding ->
            if (tab == 1 && !logs && running && webReady && pageError == null) {
                chat(Modifier.padding(padding).fillMaxSize())
            } else {
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                            Text(if (logs) "运行诊断" else listOf("DSHX", "聊天", "设置")[tab], fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                            Note(if (logs) "最近 120 行 · 已隐藏常见凭据" else listOf("你的 dsh，在 Android 上", "连接本机 dsh 工作区", "按你的习惯使用")[tab])
                        }
                        if (tab == 0 && !logs) Text("开发版", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(6.dp)).padding(8.dp, 5.dp))
                    }
                    when {
                        logs -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton("返回", onClick = { logs = false }, minHeight = 48.dp)
                                TextButton("刷新日志", onClick = { logText = diagnostics() }, minHeight = 48.dp)
                            }
                            Panel {
                                SelectionContainer { Text(logText.ifBlank { "还没有运行日志。启动 dsh 后，诊断信息会显示在这里。" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp) }
                            }
                        }
                        tab == 0 -> {
                            Panel {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (state.busy) InfiniteProgressIndicator(Modifier.size(18.dp))
                                    Text(state.phase, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        color = if (running) LocalStatusColor.current else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                Spacer(Modifier.height(14.dp))
                                Text(if (running) "工作区已就绪" else if (state.busy) state.phase else "从一次对话开始", fontSize = 23.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                                Note(state.message)
                                Spacer(Modifier.height(22.dp))
                                Button(onClick = { if (running) tab = 1 else onStart() }, enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth(), minHeight = 52.dp, cornerRadius = 12.dp,
                                    colors = ButtonDefaults.buttonColorsPrimary()) {
                                    Text(if (running) "进入聊天" else if (state.busy) "请稍候…" else "启动 dsh", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                }
                                if (active) {
                                    Spacer(Modifier.height(8.dp))
                                    TextButton(if (running) "停止服务" else "取消启动", onClick = onStop,
                                        enabled = state.phase != "正在停止", modifier = Modifier.fillMaxWidth(), minHeight = 48.dp)
                                }
                            }
                            Section("开始使用")
                            Panel(padding = 0) {
                                SuperArrow(title = "模型与会话", summary = if (running) "在原版 dsh 页面配置模型、继续对话" else "启动后，在聊天页配置模型并创建会话", onClick = { tab = 1 })
                                Divider()
                                SuperArrow(title = "运行诊断", summary = "启动遇到问题时，查看本机日志", onClick = ::openLogs)
                            }
                            Section("这台设备")
                            Panel {
                                Info("dsh", bundle.dsh)
                                Divider()
                                Info("网页组件", if (webReady) "可用 · ${webVersion?.substringBefore('.')}" else "需升级至 126 或更新版本")
                                Divider()
                                Info("运行环境", "${bundle.abi} · ${bundle.unpackedSize / 1024 / 1024} MB")
                            }
                            Note("会话与模型配置保存在应用内。模型请求会发往你配置的服务商。默认完全权限，可使用本应用能够访问的文件与前台命令；不提供额外的 Android 系统权限。")
                        }
                        tab == 1 -> Panel {
                            Text(if (!running) "先启动你的工作区" else if (!webReady) "需要更新网页组件" else "聊天页面暂时不可用", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(12.dp))
                            Note(if (!running) "返回工作台启动 dsh。就绪后，你可以在这里使用原版聊天、模型配置和会话记录。" else if (!webReady) "当前版本 ${webVersion ?: "未知"}。请在系统中更新 Android System WebView 至 126 或更新版本。" else pageError.orEmpty())
                            Spacer(Modifier.height(20.dp))
                            if (running && webReady) TextButton("重新加载", onClick = onReload, minHeight = 48.dp)
                            TextButton("返回工作台", onClick = { tab = 0 }, minHeight = 48.dp)
                        }
                        else -> {
                            Section("工具与权限")
                            Panel {
                                Text("完全权限 · 默认", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                                Note("助手可读写本应用能够访问的文件并执行已适配的前台命令。当前不提供会话沙箱管理，也不增加手机 root 或其他系统权限。")
                            }
                            Section("管理界面外观")
                            Panel(padding = 0) {
                                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEachIndexed { index, (value, label) ->
                                    if (index > 0) Divider()
                                    SuperRadioButton(title = label, selected = theme == value, onClick = { onTheme(value) })
                                }
                            }
                            Section("关于运行环境")
                            Panel {
                                Info("dsh", bundle.dsh); Divider()
                                Info("Node.js", bundle.node); Divider()
                                Info("架构", bundle.abi); Divider()
                                Info("界面组件", "Miuix 0.8.8")
                            }
                            Panel(padding = 0) { SuperArrow(title = "运行诊断", summary = "查看已脱敏日志", onClick = ::openLogs) }
                            Note("DSHX 是 dsh 的 Android 移植。应用负责运行环境、启动与停止；聊天、模型和会话沿用原版 dsh，聊天页外观可在 dsh 内设置。工具与手机操作能力仍在移植中。")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun Panel(padding: Int = 20, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
        cornerRadius = 18.dp, insideMargin = PaddingValues(padding.dp), content = content)
}

@Composable
private fun Note(text: String) = Text(text, fontSize = 14.sp, lineHeight = 22.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)

@Composable
private fun Section(text: String) = Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)

@Composable
private fun Divider() = Spacer(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.dividerLine))

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 14.sp, modifier = Modifier.weight(1.6f))
    }
}
