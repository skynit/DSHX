package local.dshx.app

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.widget.Toast
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Download
import top.yukonga.miuix.kmp.basic.*
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
    val primary = if (dark) Color(0xFF78A7EF) else Color(0xFF2F6CC4)
    val input = if (dark) Color(0xFF252925) else Color.White
    val selected = if (dark) Color(0xFF2A2E2A) else Color(0xFFF0F1EF)
    val danger = if (dark) Color(0xFFE3848A) else Color(0xFFB33D48)
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
            surfaceContainerHigh = input, secondary = selected, onSecondary = text,
            error = danger, onError = text,
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
    assistantStore: AssistantStore,
    phone: PhoneControl, phoneRevision: Int, onPhonePermission: (String) -> Unit,
    voice: VoiceControl, voiceState: VoiceControl.State, onVoice: (String) -> Unit,
    chat: @Composable (Modifier) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var logs by rememberSaveable { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    val scroll = remember(tab, logs) { ScrollState(0) }
    val savedPages = rememberSaveableStateHolder()
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
                if (!(tab in 1..2 && !logs && keyboardOpen)) Column(Modifier.background(MiuixTheme.colorScheme.surfaceVariant)) {
                    Divider()
                    Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Triple("工作台", Lucide.House, 0), Triple("聊天", Lucide.MessageSquare, 1), Triple("设置", Lucide.Settings2, 2)).forEach { (label, icon, index) ->
                            val selected = tab == index && !logs
                            val background by animateColorAsState(
                                if (selected) MiuixTheme.colorScheme.secondary else Color.Transparent,
                                animationSpec = tween(160), label = "navigation selection")
                            Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                                .background(background)
                                .selectable(selected, role = Role.Tab, onClick = { tab = index; logs = false })
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                                val color = if (selected) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary
                                if (LocalDensity.current.fontScale < 1.5f) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
                                Text(label, fontSize = 13.sp, color = color, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                            }
                        }
                    }
                }
            },
        ) { padding ->
            if (tab == 1 && !logs && running && webReady && pageError == null) {
                Column(Modifier.padding(padding).fillMaxSize()) {
                    chat(Modifier.weight(1f).fillMaxWidth())
                    Divider()
                    VoicePanel(voice, voiceState, onVoice)
                }
            } else {
                BoxWithConstraints(Modifier.padding(padding).fillMaxSize().verticalScroll(scroll)) {
                  val gutter = if (maxWidth >= 600.dp) 32.dp else 16.dp
                  Column(
                    Modifier.align(Alignment.TopCenter).widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = gutter, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                  ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                            Text(if (logs) "运行诊断" else listOf("DSHX", "聊天", "设置")[tab], fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() })
                            if (logs) Note("最近 120 行 · 已脱敏")
                        }
                        if (tab == 0 && !logs) Text("开发版", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(6.dp)).padding(8.dp, 5.dp))
                    }
                    when {
                        logs -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Action("返回", onClick = { logs = false })
                                Action("刷新日志", onClick = { logText = diagnostics() })
                            }
                            Panel {
                                SelectionContainer { Text(logText.ifBlank { "暂无日志" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp) }
                            }
                        }
                        tab == 0 -> {
                            Panel {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (state.busy) InfiniteProgressIndicator(Modifier.size(18.dp))
                                    Text(state.phase, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        color = if (running) LocalStatusColor.current else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(if (running) "工作区已就绪" else if (state.busy) "正在准备工作区" else "本机工作区", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                                Note(state.message)
                                Spacer(Modifier.height(16.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Action(if (running) "进入聊天" else if (state.busy) "请稍候…" else "启动 dsh",
                                        onClick = { if (running) tab = 1 else onStart() }, enabled = !state.busy, primary = true,
                                        icon = if (running) Lucide.MessageSquare else Lucide.Play)
                                    if (active) Action(if (running) "停止服务" else "取消启动", onClick = onStop,
                                        enabled = state.phase != "正在停止", icon = Lucide.Square, danger = true)
                                }
                            }
                            Section("开始使用")
                            Panel(padding = 0) {
                                LinkRow("模型与会话", if (running) "配置模型、继续对话" else "启动后可用", onClick = { tab = 1 })
                                Divider()
                                LinkRow("运行诊断", "查看本机日志", onClick = ::openLogs)
                            }
                            Section("这台设备")
                            Panel {
                                Info("dsh", bundle.dsh)
                                Divider()
                                Info("网页组件", if (webReady) "可用 · ${webVersion?.substringBefore('.')}" else "需升级至 126 或更新版本")
                                Divider()
                                Info("运行环境", "${bundle.abi} · ${bundle.unpackedSize / 1024 / 1024} MiB")
                            }
                            Note("数据保存在本机，模型请求发送至你配置的服务商。")
                        }
                        tab == 1 -> Panel {
                            Text(if (!running) "先启动工作区" else if (!webReady) "需要更新网页组件" else "聊天暂时不可用", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(12.dp))
                            Note(if (!running) "在工作台启动 dsh 后，即可聊天和配置模型。" else if (!webReady) "请将 Android System WebView 更新至 126 或更新版本。" else pageError.orEmpty())
                            Spacer(Modifier.height(20.dp))
                            if (running && webReady) Action("重新加载", onClick = onReload)
                            Action("返回工作台", onClick = { tab = 0 })
                        }
                        else -> {
                            savedPages.SaveableStateProvider("assistant") { AssistantSettings(assistantStore) }
                            VoiceSettings(voice, voiceState, onVoice)
                            PhoneSettings(phone, phoneRevision, onPhonePermission)
                            Section("工具与权限")
                            Panel {
                                Text("完全权限", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                                Note("可读写应用可访问的文件、执行已适配命令；不增加系统权限。")
                            }
                            Section("外观")
                            Panel(padding = 4) {
                                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                                        Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                                            .background(if (theme == value) MiuixTheme.colorScheme.secondary else Color.Transparent)
                                            .border(1.dp, if (theme == value) MiuixTheme.colorScheme.outline else Color.Transparent, RoundedCornerShape(8.dp))
                                            .selectable(theme == value, role = Role.RadioButton, onClick = { onTheme(value) }).padding(8.dp),
                                            contentAlignment = Alignment.Center) {
                                            Text(label, fontSize = 13.sp, fontWeight = if (theme == value) FontWeight.SemiBold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                  }
                }
            }
        }
    }
}

@Composable
private fun VoicePanel(voice: VoiceControl, state: VoiceControl.State, action: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceVariant).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Note(state.status)
        state.error?.let { Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.error) }
        if (state.draft.isNotEmpty()) {
            val draft = rememberTextFieldState(state.draft)
            LaunchedEffect(state.draft) { if (draft.text.toString() != state.draft) draft.setTextAndPlaceCursorAtEnd(state.draft) }
            LaunchedEffect(draft.text) { voice.draft(draft.text.toString()) }
            AssistantField("语音文字", draft, 8000, state.active, 3)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.active) Action("停止语音", onClick = { action("stop") }, danger = true)
            else {
                Action("语音输入", onClick = { action("input") }, enabled = state.speechReady)
                Action("语音对话", onClick = { action("talk") }, enabled = state.speechReady)
                if (state.draft.isNotBlank()) Action("发送文字", onClick = { action("send") }, primary = true)
            }
        }
        if (!state.speechReady) Note("先在设置中下载本地语音识别模型。")
    }
}

@Composable
private fun VoiceSettings(voice: VoiceControl, state: VoiceControl.State, action: (String) -> Unit) {
    val wake = rememberTextFieldState(voice.wakeWord)
    var mirror by remember { mutableStateOf(voice.mirror) }
    var local by remember { mutableStateOf(voice.local) }
    var interrupt by remember { mutableStateOf(voice.interrupt) }
    var message by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val echoSupported = voice.echoSupported

    Section("本地语音")
    Note("录音在本机识别，识别后的文字发送至当前聊天模型。")
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Mic, contentDescription = null, modifier = Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text("声音唤醒", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.active) InfiniteProgressIndicator(Modifier.size(16.dp))
            Text(state.status, fontSize = 13.sp, lineHeight = 20.sp,
                color = if (state.active) LocalStatusColor.current else MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Spacer(Modifier.height(16.dp))
        AssistantField("唤醒短语", wake, 40, state.active, 1)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.active) Action(if (state.downloading) "取消下载" else "停止语音", onClick = { action("stop") }, danger = true, icon = Lucide.Square)
            else Action("开启声音唤醒", onClick = { action("wake") }, enabled = state.speechReady, primary = true, icon = Lucide.Mic)
            Action("保存唤醒词", onClick = {
                runCatching { voice.wakeWord = wake.text.toString() }
                    .onSuccess { message = "唤醒词已保存"; saveError = null }
                    .onFailure { saveError = it.message ?: "保存失败，请重试。"; message = null }
            }, enabled = !state.active && wake.text.isNotBlank() && wake.text.length <= 40)
        }
        if (message != null || saveError != null) {
            Spacer(Modifier.height(8.dp))
            Text(saveError ?: message.orEmpty(), fontSize = 12.sp, lineHeight = 18.sp,
                color = if (saveError != null) MiuixTheme.colorScheme.error else LocalStatusColor.current,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Spacer(Modifier.height(12.dp))
        Note(if (!state.speechReady) "先下载下方语音识别模型，即可开启唤醒。" else if (state.downloading) "模型准备期间，语音设置暂不可修改。" else if (state.active) "停止语音后可修改设置。后台监听会显示持续通知。" else "说出已保存的短语唤醒助手，后台监听会显示持续通知。")
    }
    Panel(padding = 0) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Download, contentDescription = null, modifier = Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("模型资源", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
            Text("下载来源", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "Hugging Face 官方", true to "HF Mirror 镜像").forEach { (value, label) ->
                    Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (mirror == value) MiuixTheme.colorScheme.secondary else Color.Transparent)
                        .border(1.dp, if (mirror == value) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .selectable(mirror == value, enabled = !state.active, role = Role.RadioButton, onClick = { mirror = value; voice.mirror = value })
                        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, fontSize = 13.sp, color = if (state.active) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface)
                    }
                }
            }
            Note("仅用于下载模型文件，不发送录音。")
        }
        Divider()
        VoiceModelRow("语音识别", "本机识别录音 · 约 229 MiB", state.speechReady, !state.active) { action("download") }
        Divider()
        VoiceModelRow("本地小模型", "参与简单任务分工 · 约 379 MiB", state.localReady, !state.active) { action("download-local") }
        Divider()
        VoiceModelRow("离线播报", "无系统离线中文声音时使用 · 约 58 MiB", state.outputReady, !state.active) { action("download-output") }
    }
    Panel(padding = 0) {
        Text("高级选项", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp).semantics { heading() })
        SettingsSwitch("小模型分工", if (state.localReady) "让本地小模型处理简单任务，减少云端模型用量。" else "下载本地小模型后可开启。",
            local, !state.active && state.localReady) { local = it; voice.local = it }
        Divider()
        SettingsSwitch("播报中唤醒 · 实验功能", if (echoSupported) "播报时也可唤醒，默认关闭。依赖设备回声消除，可能误唤醒。" else "当前设备不支持回声消除，暂不可开启。",
            interrupt, !state.active && (interrupt || echoSupported)) { interrupt = it; voice.interrupt = it }
    }
}

@Composable
private fun PhoneSettings(phone: PhoneControl, revision: Int, permission: (String) -> Unit) {
    val status = remember(revision) { phone.status() }
    val context = LocalView.current.context
    val capabilities = status.getJSONObject("capabilities")
    Section("手机操作")
    Panel(padding = 0) {
        SettingsRow("无障碍设置", if (status.optBoolean("accessibility")) "服务已连接，可读取屏幕并执行点击。" else "尚未授权，屏幕操作需要开启 DSHX 无障碍服务。") {
            Action("打开设置", onClick = {
                try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: RuntimeException) { Toast.makeText(context, "无法打开系统设置，请手动进入无障碍设置。", Toast.LENGTH_SHORT).show() }
            })
        }
        Divider()
        SettingsRow("悬浮提示", if (capabilities.optBoolean("overlayPermission")) "系统已允许，可在其他应用上方显示操作提示。" else "系统尚未授权，需允许显示在其他应用上层。") {
            Action("打开设置", onClick = { permission("overlay") })
        }
        Divider()
        SettingsRow("通知权限", if (capabilities.optBoolean("notificationsPermission")) "已允许，用于后台语音与运行状态提示。" else "尚未允许，后台语音与运行状态通知可能不可见。") {
            Action("打开设置", onClick = { permission("notifications") })
        }
    }
}

@Composable
private fun SettingsRow(title: String, summary: String, control: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
        val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.3f
        val label: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Note(summary)
            }
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            label()
            control()
        } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { label() }
            control()
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, summary: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingsRow(title, summary) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = title
                stateDescription = if (checked) "已开启" else "已关闭"
            })
    }
}

@Composable
private fun VoiceModelRow(title: String, summary: String, ready: Boolean, enabled: Boolean, onClick: () -> Unit) {
    SettingsRow(title, "${if (ready) "已就绪" else "未下载"} · $summary") {
        Action(if (ready) "重新校验" else "下载", onClick = onClick, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "${if (ready) "重新校验" else "下载"}$title" })
    }
}

@Composable
private fun AssistantSettings(store: AssistantStore) {
    val name = rememberTextFieldState()
    val identity = rememberTextFieldState()
    val prompt = rememberTextFieldState()
    var initialized by rememberSaveable { mutableStateOf(false) }
    var saved by remember { mutableStateOf<AssistantStore.Settings?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun fill(value: AssistantStore.Settings) {
        val displayed = value.displayed()
        name.setTextAndPlaceCursorAtEnd(displayed.name)
        identity.setTextAndPlaceCursorAtEnd(displayed.identity)
        prompt.setTextAndPlaceCursorAtEnd(displayed.prompt)
        initialized = true
    }
    fun readSettings() {
        loading = true
        settingsError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.readSettings() } }
            result.onSuccess {
                saved = it
                if (!initialized) fill(it)
            }.onFailure { settingsError = "读取设置失败：${it.message ?: "请重试"}。可重试读取，或编辑后保存、恢复默认。" }
            loading = false
        }
    }
    fun save(value: AssistantStore.Settings, defaults: Boolean) {
        saving = true
        message = null
        settingsError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.saveSettings(value.custom()) } }
            result.onSuccess {
                saved = it
                fill(it)
                message = if (defaults) "已恢复上游默认设置。" else "助手设置已保存。"
            }.onFailure { settingsError = "保存设置失败：${it.message ?: "请重试"}" }
            saving = false
        }
    }
    LaunchedEffect(store) { readSettings() }

    Panel {
        Text("助手人设", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(4.dp))
        Note("默认沿用 DS，修改后下次对话生效。")
        Spacer(Modifier.height(16.dp))
        AssistantField("名字", name, 80, loading || saving, 1, "DS 默认，无固定名字")
        AssistantField("身份", identity, 2000, loading || saving, 3)
        AssistantField("自定义提示词", prompt, 16000, loading || saving, 5)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(if (loading) "读取中…" else if (saving) "保存中…" else "保存人设",
                onClick = { save(AssistantStore.Settings(name.text.toString(), identity.text.toString(), prompt.text.toString()), false) },
                enabled = !loading && !saving, primary = true, icon = Lucide.Save)
            Action("恢复默认", onClick = { save(AssistantStore.Settings(), true) }, enabled = !loading && !saving,
                modifier = Modifier.semantics { contentDescription = "恢复上游默认设置" }, icon = Lucide.RotateCcw)
        }
        if (settingsError != null) {
            Spacer(Modifier.height(8.dp))
            Text(settingsError!!, fontSize = 12.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.error)
            Action("重试读取设置", onClick = ::readSettings, enabled = !loading && !saving)
        }
        val dirty = saved?.let { it.displayed() != AssistantStore.Settings(name.text.toString(), identity.text.toString(), prompt.text.toString()) } ?: initialized
        if (dirty || message != null) {
            Spacer(Modifier.height(8.dp))
            Note(if (dirty) "有未保存的编辑。" else message.orEmpty())
        }
    }
}

@Composable
private fun AssistantField(label: String, state: TextFieldState, limit: Int, disabled: Boolean, lines: Int, hint: String = "") {
    var focused by remember { mutableStateOf(false) }
    val size = state.text.toString().trim().length
    val invalid = size > limit
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val border by animateColorAsState(
        if (invalid) colors.error else if (focused) colors.primary else colors.outline,
        animationSpec = tween(160), label = "field focus")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (focused || invalid) Text("$size / $limit", fontSize = 12.sp, color = if (invalid) colors.error else colors.onSurfaceVariantSummary)
        }
        BasicTextField(state = state, enabled = !disabled,
            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = if (disabled) colors.onSurfaceVariantSummary else colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            lineLimits = if (lines == 1) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(maxHeightInLines = lines),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label; if (invalid) error("$label 超出长度限制") }
                .clip(shape).background(if (disabled) colors.secondary else colors.surfaceContainerHigh)
                .border(if (focused || invalid) 2.dp else 1.dp, border, shape)
                .padding(12.dp),
            decorator = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty() && hint.isNotEmpty()) Text(hint, fontSize = 14.sp, lineHeight = 21.sp, color = colors.onSurfaceVariantSummary)
                    inner()
                }
            })
        if (invalid) Text("超出长度限制，请缩短后保存。", fontSize = 12.sp, color = colors.error)
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Panel(padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.surfaceVariant)
        .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(10.dp)).padding(padding.dp), content = content)
}

@Composable
private fun Action(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    primary: Boolean = false, icon: ImageVector? = null, danger: Boolean = false,
) {
    val colors = MiuixTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Button(onClick = onClick, modifier = modifier.onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 1.dp,
            if (focused) colors.primary else if (primary) Color.Transparent else colors.outline, RoundedCornerShape(8.dp)),
        enabled = enabled, minHeight = 48.dp, cornerRadius = 8.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(
            color = if (danger) colors.error.copy(alpha = 0.08f) else colors.surfaceContainerHigh,
            disabledColor = colors.secondary,
            contentColor = if (danger) colors.error else colors.onSurface,
            disabledContentColor = colors.onSurfaceVariantSummary)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null && LocalDensity.current.fontScale < 1.5f) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp),
                    tint = if (!enabled) colors.onSurfaceVariantSummary else if (primary) colors.onPrimary else if (danger) colors.error else colors.onSurface)
            }
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun LinkRow(title: String, summary: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 64.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Note(summary)
        }
        Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun Note(text: String) = Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)

@Composable
private fun Section(text: String) = Text(text, modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)

@Composable
private fun Divider() = Spacer(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.dividerLine))

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 13.sp, modifier = Modifier.weight(1.6f))
    }
}
