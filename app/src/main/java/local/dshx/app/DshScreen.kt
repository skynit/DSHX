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
import androidx.compose.foundation.text.BasicSecureTextField
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
import com.composables.icons.lucide.X
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
    memoryStore: MemoryStore, onMemory: (String, org.json.JSONObject) -> org.json.JSONObject,
    knowledgeStore: KnowledgeStore, onKnowledge: (String, org.json.JSONObject) -> org.json.JSONObject,
    knowledgePicking: Boolean, onKnowledgeFile: (String, org.json.JSONObject) -> Unit,
    backupPicking: Boolean, onBackup: (Boolean) -> Unit,
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
    val active = running || state.busy && !state.backup.busy
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
                            savedPages.SaveableStateProvider("memory") { MemorySettings(memoryStore, running, onMemory) }
                            savedPages.SaveableStateProvider("knowledge") {
                                KnowledgeSettings(knowledgeStore, running, state.knowledgeFiles, knowledgePicking, onKnowledge, onKnowledgeFile)
                            }
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
                            Section("备份与恢复")
                            Panel(padding = 0) {
                                val enabled = !active && !state.busy && !backupPicking
                                SettingsRow("备份会话", "保存会话与附件，不包含独立记忆") {
                                    Action("备份", onClick = { onBackup(false) }, enabled = enabled, icon = Lucide.Save)
                                }
                                Divider()
                                SettingsRow("恢复会话", "自动跳过已有会话") {
                                    Action("恢复", onClick = { onBackup(true) }, enabled = enabled, icon = Lucide.RotateCcw)
                                }
                                val message = when {
                                    state.backup.busy -> "${state.phase}…"
                                    active -> "请先停止服务"
                                    backupPicking -> "请选择文件或保存位置"
                                    else -> state.backup.message
                                }
                                message?.let {
                                    Divider()
                                    Row(Modifier.fillMaxWidth().padding(16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (state.backup.busy) InfiniteProgressIndicator(Modifier.size(16.dp))
                                        Text(it, fontSize = 12.sp, lineHeight = 18.sp,
                                            color = if (!active && state.backup.error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                                        if (running) Action("停止服务", onClick = onStop, enabled = !state.busy, icon = Lucide.Square)
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
private fun SettingsSwitch(title: String, summary: String, checked: Boolean, enabled: Boolean = true, stateText: String? = null, onChange: (Boolean) -> Unit) {
    SettingsRow(title, summary) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = title
                stateDescription = stateText ?: if (checked) "已开启" else "已关闭"
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
private fun MemorySettings(store: MemoryStore, running: Boolean, call: (String, org.json.JSONObject) -> org.json.JSONObject) {
    var saved by remember { mutableStateOf<MemoryStore.Settings?>(null) }
    var status by remember { mutableStateOf<MemoryStore.Status?>(null) }
    var rows by remember { mutableStateOf<List<MemoryStore.Entry>?>(null) }
    var listRevision by remember { mutableStateOf<Long?>(null) }
    var managing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<MemoryStore.Entry?>(null) }
    var editRevision by remember { mutableStateOf(0L) }
    var type by remember { mutableStateOf("fact") }
    var workspaceScope by remember { mutableStateOf(false) }
    val workspace = rememberTextFieldState()
    val topic = rememberTextFieldState()
    val summary = rememberTextFieldState()
    val content = rememberTextFieldState()
    val expiry = rememberTextFieldState()
    val search = rememberTextFieldState()
    var confirmation by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var task by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    fun accept(value: org.json.JSONObject) {
        val next = MemoryStore.status(value)
        status = next
        saved = next.settings
    }
    suspend fun refresh() {
        // 读取失败时先撤销旧的可写状态，不能拿旧修订号继续操作。
        status = null
        saved = null
        rows = null
        listRevision = null
        if (running) {
            accept(runInterruptible(Dispatchers.IO) { call("status", org.json.JSONObject()) })
            if (managing) {
                val (entries, revision) = MemoryStore.entries(runInterruptible(Dispatchers.IO) { call("list", org.json.JSONObject()) })
                rows = entries
                listRevision = revision
            }
        } else saved = runInterruptible(Dispatchers.IO) { store.readSettings() }
    }
    fun request(action: String? = null, data: org.json.JSONObject = org.json.JSONObject(), done: (() -> Unit)? = null) {
        if (loading) return
        loading = true
        failure = null
        message = null
        task = scope.launch {
            var confirmed = false
            try {
                if (action != null) {
                    val result = runInterruptible(Dispatchers.IO) { call(action, data) }
                    accept(result)
                    if (action == "save") MemoryStore.read(result)
                    confirmed = true
                    message = when (action) {
                        "settings" -> "设置已保存。"
                        "save" -> "记忆已保存。"
                        "remove" -> "记忆已删除。"
                        "clear" -> "记忆、候选和待整理任务已清空。"
                        else -> "已提交继续整理请求，请查看任务状态。"
                    }
                    done?.invoke()
                }
                refresh()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                status = null
                saved = null
                rows = null
                listRevision = null
                failure = error.message?.take(220)?.takeIf(String::isNotBlank) ?: "长期记忆请求失败，请重新读取。"
                if (action != null && !confirmed) message = "尚未确认实际结果，请重新读取；编辑内容仍保留。"
            } finally {
                loading = false
            }
        }
    }
    fun cancel() {
        task?.cancel()
        status = null
        saved = null
        rows = null
        listRevision = null
        message = "已取消等待。操作可能已经保存，请重新读取确认实际结果。"
    }
    fun closeManager() {
        if (loading) cancel()
        managing = false
        editing = false
        confirmation = null
    }
    fun fill(entry: MemoryStore.Entry?, revision: Long) {
        current = entry
        editRevision = revision
        type = entry?.type ?: "fact"
        workspaceScope = entry?.scope?.getString("kind") == "workspace"
        workspace.setTextAndPlaceCursorAtEnd(entry?.scope?.optString("workspace").orEmpty())
        topic.setTextAndPlaceCursorAtEnd(entry?.key.orEmpty())
        summary.setTextAndPlaceCursorAtEnd(entry?.summary.orEmpty())
        content.setTextAndPlaceCursorAtEnd(entry?.content.orEmpty())
        expiry.setTextAndPlaceCursorAtEnd(entry?.expiresAt?.let(::memoryTime).orEmpty())
        editing = true
        failure = null
        message = null
    }
    fun read(entry: MemoryStore.Entry) {
        if (loading) return
        loading = true
        failure = null
        task = scope.launch {
            try {
                val (value, revision) = MemoryStore.read(runInterruptible(Dispatchers.IO) {
                    call("read", org.json.JSONObject().put("id", entry.id))
                })
                fill(value, revision)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error.message?.take(220) ?: "读取记忆详情失败，请重试。"
            } finally { loading = false }
        }
    }
    fun change(settings: MemoryStore.Settings) {
        val revision = status?.revision ?: return
        request("settings", settings.json().put("revision", revision))
    }
    fun save() {
        try {
            val memoryScope = org.json.JSONObject().put("kind", if (workspaceScope) "workspace" else "user")
            if (workspaceScope) memoryScope.put("workspace", workspace.text.toString().trim())
            val time = expiry.text.toString().trim()
            val expiresAt = if (time.isBlank()) null else if (time == current?.expiresAt?.let(::memoryTime)) current?.expiresAt
                else try {
                    java.time.LocalDateTime.parse(time, java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                        .withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (_: Exception) { throw IllegalArgumentException("失效时间请填写为年-月-日 时:分，例如 2026-12-31 18:00。") }
            val data = MemoryStore.save(editRevision, current?.id, type, memoryScope, topic.text.toString(),
                summary.text.toString(), content.text.toString(), expiresAt)
            request("save", data) { editing = false }
        } catch (error: Exception) { failure = error.message ?: "请检查记忆内容。" }
    }

    LaunchedEffect(running) {
        task?.cancelAndJoin()
        loading = false
        confirmation = null
        if (!running) { editing = false; managing = false }
        request()
    }
    val available = running && status?.ready == true && !loading
    val statusText = when {
        loading -> "正在处理长期记忆…"
        saved == null -> "长期记忆状态未知，请重新读取。"
        !running -> "显示本机已保存设置；启动 dsh 后可管理记忆。"
        status?.ready != true -> "本地记忆尚未就绪。"
        status?.active == true -> "已启用 · ${status!!.count} 条记忆 · ${status!!.pending} 条待确认"
        saved?.enabled == true -> "已保存启用设置，当前尚未生效；已有记忆仍可管理。"
        else -> "已关闭，已有记忆仍可管理。"
    }
    val feedback: @Composable () -> Unit = {
        Note(statusText)
        status?.message?.let { Note(it) }
        message?.let { Note(it) }
        failure?.let { Text(it, fontSize = 13.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (loading) Action("取消等待", onClick = ::cancel)
        else if (failure != null || saved == null) Action("重新读取", onClick = { request() })
    }
    Section("长期记忆")
    Panel(padding = 0) {
        SettingsSwitch("长期记忆", "保存于本机，开启后可在新会话使用；关闭后仍可管理已有记忆。",
            saved?.enabled ?: false, available, if (saved == null) "状态未知" else null) { enabled ->
            saved?.let { change(it.copy(enabled = enabled)) }
        }
        Divider()
        SettingsSwitch("自动整理", "调用当前配置的模型。使用云端模型时，参与整理的对话片段会发送至该模型。",
            saved?.autoSave ?: false, available && (saved?.autoSave == true || saved?.enabled == true),
            if (saved == null) "状态未知" else null) { enabled ->
            if (enabled) confirmation = "auto" else saved?.let { change(it.copy(autoSave = false)) }
        }
        Divider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            feedback()
            if (saved?.enabled == true && saved?.autoSave == true && status?.autoReady == false) Note("自动整理模型尚未就绪；本地记忆管理仍可使用。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("管理记忆", onClick = { managing = true; request() }, enabled = available)
                Action("刷新状态", onClick = { request() }, enabled = !loading)
            }
            Note("记忆保存在本机，不含独立记忆备份或跨设备同步；会话备份仅包含会话与附件。卸载应用或清除应用数据会删除本地记忆。")
        }
    }

    if (managing && !editing) MemoryDialog("管理记忆", ::closeManager, scroll = false) {
        val query = search.text.toString().trim()
        val filtered = rows?.filter { query.isEmpty() || it.summary.contains(query, true) || it.content.contains(query, true) ||
            it.key.contains(query, true) || it.scopeText.contains(query, true) }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "filter") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    feedback()
                    AssistantField("筛选记忆", search, 160, false, 1, "搜索摘要、正文或工作区")
                }
            }
            if (filtered != null && filtered.isEmpty()) item { Note(if (query.isBlank()) "还没有记忆，可新增一条。" else "没有匹配的记忆。") }
            items(filtered?.size ?: 0, key = { filtered!![it].id }) { index ->
                val entry = filtered!![index]
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = available, role = Role.Button, onClick = { read(entry) })
                    .heightIn(min = 64.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.summary, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                    Note("${entry.typeText} · ${entry.scopeText}")
                    Note(when {
                        entry.state == "pending" -> "待确认 · 确认后才会用于对话"
                        entry.expiresAt?.let { it <= System.currentTimeMillis() } == true -> "已过期"
                        entry.locked -> "已确认 · 自动整理不会覆盖"
                        else -> "已保存"
                    })
                }
                Divider()
            }
            status?.jobs?.forEach { job ->
                item(key = "job:${job.optString("id")}") {
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val state = job.getString("state")
                        Text(when (state) { "running" -> "整理中"; "failed" -> "整理失败"; else -> "待继续整理" }, fontSize = 14.sp)
                        job.optString("message").takeIf(String::isNotBlank)?.let { Note(it) }
                        if (!job.optBoolean("modelReady", false)) Note("来源模型当前未配置，请先恢复原模型配置。")
                        if (job.getInt("attempts") >= 2) Note("本次任务已重试，请检查模型配置后重新开启自动整理。")
                        Action("继续整理", onClick = { request("retry", org.json.JSONObject().put("id", job.getString("id"))) },
                            enabled = available && saved?.enabled == true && saved?.autoSave == true && status?.autoReady == true &&
                                job.optBoolean("modelReady", false) && state != "running" && job.getInt("attempts") < 2 && job.optString("id").isNotBlank())
                    }
                    Divider()
                }
            }
        }
        Divider()
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("新增", onClick = { listRevision?.let { fill(null, it) } }, enabled = available && listRevision != null, primary = true)
            Action("刷新", onClick = { request() }, enabled = !loading)
            Action("清空", onClick = { confirmation = "clear" }, enabled = available && listRevision != null, danger = true)
        }
    }
    if (managing && editing) MemoryDialog(if (current == null) "新增记忆" else if (current?.state == "pending") "确认记忆候选" else "编辑记忆", {
        if (loading) cancel()
        editing = false
    }) {
        if (current?.state == "pending") Note("这是待确认候选。核对或修改正文后保存，才会用于后续对话；删除可拒绝此候选。")
        MemoryChoice("类型", listOf("preference" to "偏好", "fact" to "事实", "experience" to "经验"), type, !loading) { type = it }
        MemoryChoice("适用范围", listOf("user" to "用户通用", "workspace" to "指定工作区"), if (workspaceScope) "workspace" else "user", !loading) { workspaceScope = it == "workspace" }
        if (workspaceScope) AssistantField("工作区", workspace, 2048, loading, 2, "填写实际工作区路径", codePoints = true)
        AssistantField("主题键", topic, 160, loading, 1, "同一范围内用于识别主题，例如 reply-language", codePoints = true)
        AssistantField("摘要", summary, 160, loading, 3, codePoints = true)
        AssistantField("正文", content, 2000, loading, 8, codePoints = true)
        AssistantField("失效时间（可选）", expiry, 16, loading, 1, "年-月-日 时:分，留空表示长期有效")
        current?.let { entry ->
            Note("来源：${if (entry.source.getString("kind") == "manual") "手动保存" else "自动整理"}；${if (entry.locked) "已锁定，自动整理不会覆盖" else "未锁定"}")
            entry.source.optString("session").takeIf(String::isNotBlank)?.let { Note("来源会话：$it") }
            if (entry.source.has("events")) Note("来源事件：${entry.source.opt("events")}")
            Note("创建：${memoryTime(entry.createdAt)}；更新：${memoryTime(entry.updatedAt)}")
        }
        Note("手动保存后视为已确认，并锁定以防止自动整理覆盖。")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(if (current?.state == "pending") "确认并保存" else "保存", onClick = ::save, enabled = available, primary = true)
            if (current != null) {
                Action("删除", onClick = { confirmation = "remove" }, enabled = available, danger = true)
                Action("重新加载详情", onClick = { read(current!!) }, enabled = !loading && running)
            }
            Action("刷新状态", onClick = { request() }, enabled = !loading)
        }
        feedback()
    }
    confirmation?.let { action ->
        MemoryDialog(when (action) { "auto" -> "开启自动整理"; "remove" -> "删除这条记忆"; else -> "清空全部记忆" }, { confirmation = null }) {
            Note(when (action) {
                "auto" -> "开启后，将使用当前已配置模型整理新对话。若使用云端模型，参与整理的对话片段会发送给该模型。"
                "remove" -> "删除后不再用于后续对话，并阻止从原来源重新提取。此操作无法撤销。"
                else -> "将删除全部记忆、待确认候选和旧整理任务。开关保持原值。此操作无法撤销，会话备份不能恢复记忆。"
            })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("取消", onClick = { confirmation = null })
                Action(if (action == "auto") "开启" else "确认${if (action == "remove") "删除" else "清空"}", onClick = {
                    confirmation = null
                    when (action) {
                        "auto" -> saved?.let { change(it.copy(autoSave = true)) }
                        "remove" -> current?.let { request("remove", org.json.JSONObject().put("revision", editRevision).put("id", it.id)) { editing = false } }
                        else -> listRevision?.let { request("clear", org.json.JSONObject().put("revision", it)) }
                    }
                }, enabled = available, primary = action == "auto", danger = action != "auto")
            }
        }
    }
}

private fun memoryTime(value: Long): String = java.time.Instant.ofEpochMilli(value).atZone(java.time.ZoneId.systemDefault())
    .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm"))

@Composable
private fun MemoryChoice(title: String, options: List<Pair<String, String>>, selected: String, enabled: Boolean, change: (String) -> Unit) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (value == selected) MiuixTheme.colorScheme.secondary else Color.Transparent)
                .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .selectable(selected == value, enabled = enabled, role = Role.RadioButton, onClick = { change(value) })
                .heightIn(min = 48.dp).padding(12.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 14.sp, color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun MemoryDialog(title: String, close: () -> Unit, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = close,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect { window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        BoxWithConstraints(Modifier.fillMaxWidth().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth()
                .then(if (scroll) Modifier.heightIn(max = maxHeight) else Modifier.height(maxHeight * 0.85f))
                .clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.surfaceVariant)
                .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(10.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                    var focused by remember { mutableStateOf(false) }
                    Box(Modifier.size(48.dp).onFocusChanged { focused = it.isFocused }
                        .clip(RoundedCornerShape(8.dp))
                        .border(if (focused) 2.dp else 0.dp, if (focused) MiuixTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = close), contentAlignment = Alignment.Center) {
                        Icon(Lucide.X, contentDescription = "关闭", modifier = Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                    }
                }
                if (scroll) Column(Modifier.weight(1f, fill = false).verticalScroll(remember { ScrollState(0) }),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                else content()
            }
        }
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
private fun AssistantField(label: String, state: TextFieldState, limit: Int, disabled: Boolean, lines: Int, hint: String = "", secret: Boolean = false, codePoints: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val value = state.text.toString().trim()
    val size = if (codePoints) value.codePointCount(0, value.length) else value.length
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
        val style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = if (disabled) colors.onSurfaceVariantSummary else colors.onSurface)
        val fieldModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label; if (invalid) error("$label 超出长度限制") }
                .clip(shape).background(if (disabled) colors.secondary else colors.surfaceContainerHigh)
                .border(if (focused || invalid) 2.dp else 1.dp, border, shape)
                .padding(12.dp)
        val decorator = androidx.compose.foundation.text.input.TextFieldDecorator { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (state.text.isEmpty() && hint.isNotEmpty()) Text(hint, fontSize = 14.sp, lineHeight = 21.sp, color = colors.onSurfaceVariantSummary)
                inner()
            }
        }
        if (secret) BasicSecureTextField(state = state, enabled = !disabled, textStyle = style,
            cursorBrush = SolidColor(colors.primary), modifier = fieldModifier, decorator = decorator)
        else BasicTextField(state = state, enabled = !disabled, textStyle = style,
            cursorBrush = SolidColor(colors.primary), modifier = fieldModifier, decorator = decorator,
            lineLimits = if (lines == 1) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(maxHeightInLines = lines))
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

/** 延续现有记忆弹层和主题，原生仅保留编辑草稿与已经校验的状态。 */
@Composable
private fun KnowledgeSettings(store: KnowledgeStore, running: Boolean, transfer: KnowledgeStore.Transfer, picking: Boolean,
                              call: (String, org.json.JSONObject) -> org.json.JSONObject,
                              file: (String, org.json.JSONObject) -> Unit) {
    var status by remember { mutableStateOf<KnowledgeStore.Status?>(null) }
    var catalog by remember { mutableStateOf<KnowledgeStore.Catalog?>(null) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("list") }
    var selected by rememberSaveable { mutableStateOf("") }
    var current by remember { mutableStateOf<KnowledgeStore.Source?>(null) }
    var original by remember { mutableStateOf("") }
    var nextOffset by remember { mutableStateOf<Long?>(null) }
    val savedDraft = remember(store) { store.draft }
    var hasDraft by remember { mutableStateOf(savedDraft != null) }
    var draftSource by remember { mutableStateOf(savedDraft?.source) }
    var draftOriginal by remember { mutableStateOf(savedDraft?.original.orEmpty()) }
    var draftCollection by remember { mutableStateOf(savedDraft?.collectionId.orEmpty()) }
    var draftOperation by remember { mutableStateOf(savedDraft?.operationId) }
    var reference by remember { mutableStateOf<KnowledgeStore.RefPage?>(null) }
    var foundJob by remember { mutableStateOf<KnowledgeStore.Job?>(null) }
    val taskQuery = rememberTextFieldState()
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingData by rememberSaveable { mutableStateOf<String?>(null) }
    var searchResults by remember { mutableStateOf<List<org.json.JSONObject>?>(null) }
    var searchMode by remember { mutableStateOf("") }
    var task by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var foreground by remember { mutableStateOf(true) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var rebuildKind by rememberSaveable { mutableStateOf("keyword") }
    val title = rememberTextFieldState(savedDraft?.title.orEmpty())
    val note = rememberTextFieldState(savedDraft?.note.orEmpty())
    val body = rememberTextFieldState(savedDraft?.text.orEmpty())
    val query = rememberTextFieldState()
    val savedCollection = remember(store) { store.collectionDraft }
    val collectionTitle = rememberTextFieldState(savedCollection?.optString("title").orEmpty())
    var collectionEditing by remember { mutableStateOf(savedCollection?.optJSONObject("editing")?.let { KnowledgeStore.collection(it) }) }
    var parentId by remember { mutableStateOf(savedCollection?.optString("parentId").orEmpty()) }
    var collectionWorkspace by remember { mutableStateOf(savedCollection?.optBoolean("workspace") ?: false) }
    val scope = rememberCoroutineScope()
    fun draft() = KnowledgeStore.Draft(draftSource, draftOriginal, title.text.toString(), note.text.toString(),
        body.text.toString(), draftCollection, draftOperation)
    val retainDraft = rememberUpdatedState {
        store.draft = if (hasDraft) draft() else null
        store.collectionDraft = org.json.JSONObject().put("title", collectionTitle.text.toString()).put("parentId", parentId)
            .put("workspace", collectionWorkspace).apply { collectionEditing?.let { value ->
                put("editing", org.json.JSONObject().put("id", value.id).put("parentId", value.parentId ?: org.json.JSONObject.NULL)
                    .put("title", value.title).put("scope", value.scope).put("revision", value.revision))
            } }
    }
    DisposableEffect(store) { onDispose { retainDraft.value.invoke() } }
    val lifecycle = (LocalView.current.context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle) {
        foreground = lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) != false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) foreground = true
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { retainDraft.value.invoke(); foreground = false; task?.cancel() }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); task?.cancel() }
    }

    suspend fun refresh(filter: org.json.JSONObject = org.json.JSONObject()) {
        val request = org.json.JSONObject(filter.toString()).apply {
            if (!has("operationId")) draftOperation?.let { put("operationId", it) }
        }
        val next = KnowledgeStore.status(runInterruptible(Dispatchers.IO) { call("status", request) })
        status = next
        draftOperation?.let { operationId ->
            val job = next.jobs.firstOrNull { it.operationId == operationId }
                ?: next.raw.optJSONObject("job")?.let { KnowledgeStore.job(it) }?.takeIf { it.operationId == operationId }
            val operation = next.raw.optJSONObject("operation")?.takeIf { it.optString("operationId") == operationId }
            if (job?.effect == "published" || operation?.optString("effect") == "published") {
                hasDraft = false; draftOperation = null; store.draft = null
            } else if (job?.state in setOf("failed", "waitingRetry", "cancelled")) {
                draftOperation = null
                message = "资料尚未发布，编辑草稿已保留。"
            }
        }
        foundJob?.let { previous ->
            foundJob = next.jobs.firstOrNull { it.id == previous.id } ?: if (next.raw.optJSONObject("job")?.optString("id") == previous.id)
                KnowledgeStore.job(next.raw.getJSONObject("job")) else previous
        }
        if (managing) {
            catalog = runInterruptible(Dispatchers.IO) { KnowledgeStore.loadCatalog(call) }
            if (selected.isNotBlank() && catalog?.collections?.none { it.id == selected } == true) selected = ""
            if (page == "detail") current?.let { previous ->
                val latest = catalog?.sources?.firstOrNull { it.id == previous.id && it.state != "deleted" }
                if (latest == null || latest.version != previous.version || latest.revision != previous.revision) {
                    current = null
                    original = ""
                    nextOffset = null
                    page = "list"
                    searchResults = null
                    message = if (latest == null) "资料已删除，请查看最新目录。" else "资料已更新，请重新打开查看最新原文。"
                }
            }
        }
    }
    suspend fun execute(action: String, data: org.json.JSONObject): org.json.JSONObject = runInterruptible(Dispatchers.IO) {
        // 大正文采用与系统选择器相同的票据流程，不把 UTF-16 长度当成连接字节数。
        val request = org.json.JSONObject(data.toString())
        val text = if (action == "import") request.opt("text") as? String
            else if (action == "update") request.optJSONObject("patch")?.opt("text") as? String else null
        if (text != null && text.toByteArray(Charsets.UTF_8).size > 96 * 1024) {
            val ticket = text.byteInputStream(Charsets.UTF_8).use {
                store.stage(it, request.optString("title", draftSource?.title ?: "资料"), request.optString("format", draftSource?.format ?: "txt"), request)
            }
            call("ticket", ticket.json())
            if (action == "import") request.remove("text") else request.getJSONObject("patch").remove("text")
            request.put("ticket", ticket.id)
            // 受理不确定后保留原始请求及票据，重试不会创建另一份文件操作。
            pendingData = request.toString()
        }
        call(action, request)
    }
    fun request(action: String? = null, data: org.json.JSONObject = org.json.JSONObject(), done: ((org.json.JSONObject) -> Unit)? = null) {
        if (loading || !running) return
        loading = true
        failure = null
        if (action != null && action !in setOf("status", "list", "read", "search")) {
            pendingAction = action
            pendingData = data.toString()
        }
        task = scope.launch {
            try {
                if (action != null && action != "status") {
                    val result = execute(action, data)
                    if (action !in setOf("list", "read", "search")) {
                        val job = KnowledgeStore.accepted(result, action, data)
                        message = job?.let { "已受理，请查看任务；${it.effectText}。" } ?: "操作已确认，请查看最新状态。"
                        pendingAction = null
                        pendingData = null
                    }
                    done?.invoke(result)
                }
                refresh(if (action == "status") data else org.json.JSONObject())
                if (action == "status") status?.raw?.let { done?.invoke(it) }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) {
                if (action != "read") { status = null; catalog = null }
                if (action in setOf("import", "update") && error is KnowledgeStore.Error) {
                    draftOperation = null; pendingAction = null; pendingData = null
                }
                failure = "${if (error is KnowledgeStore.Error) "${error.code}：" else ""}${error.message?.take(260) ?: "知识请求失败，请刷新重试。"}"
                if (pendingAction != null) message = "尚未确认实际结果，草稿已保留；先核实原操作，再决定重试。"
            } finally { loading = false }
        }
    }
    fun close() {
        retainDraft.value.invoke()
        task?.cancel()
        managing = false
        confirmation = null
        message = "已停止等待；已受理任务继续执行，可重新打开查看。"
    }
    fun read(source: KnowledgeStore.Source, append: Boolean = false) {
        if (!append) {
            current = source; original = ""; nextOffset = null; page = "detail"
            if (source.state != "active") { failure = "原文不可用，可替换正文或删除资料。"; return }
        }
        val offset = if (append) nextOffset ?: return else 0
        request("read", org.json.JSONObject().put("sourceId", source.id).put("offset", offset).put("maxChars", 3000)) { result ->
            val value = KnowledgeStore.page(result)
            require(value.source.id == source.id && value.source.version == source.version && value.source.revision == source.revision) { "资料已变化，请返回目录刷新。" }
            require(value.nextOffset == null || value.nextOffset > offset) { "原文分页位置无效。" }
            current = value.source
            original = if (append) original + value.text else value.text
            nextOffset = value.nextOffset
            page = "detail"
        }
    }
    fun readReference(ref: String, append: Boolean = false) {
        val offset = if (append) reference?.nextOffset ?: return else 0L
        request("read", org.json.JSONObject().put("ref", ref).put("offset", offset).put("maxChars", 3000)) { result ->
            val value = KnowledgeStore.reference(result)
            require(value.ref == ref) { "知识引用响应不匹配。" }
            reference = if (append) value.copy(text = reference!!.text + value.text) else value
            page = "reference"
        }
    }
    fun edit(source: KnowledgeStore.Source?) {
        if (hasDraft) {
            page = "edit"
            message = "已保留原草稿，请先保存或放弃。"
            return
        }
        if (source == null) {
            draftSource = null
            title.setTextAndPlaceCursorAtEnd("")
            note.setTextAndPlaceCursorAtEnd("")
            body.setTextAndPlaceCursorAtEnd("")
            hasDraft = true
            draftOriginal = ""
            draftCollection = selected
            draftOperation = null
            page = "edit"
            return
        }
        if (loading) return
        loading = true
        failure = null
        task = scope.launch {
            try {
                val text = runInterruptible(Dispatchers.IO) {
                    val buffer = StringBuilder()
                    var offset = 0L
                    var bytes = 0L
                    do {
                        val value = KnowledgeStore.page(call("read", org.json.JSONObject().put("sourceId", source.id)
                            .put("offset", offset).put("maxChars", 3000)))
                        require(value.source.id == source.id && value.source.revision == source.revision && value.source.version == source.version) { "资料已变化，请重新打开详情。" }
                        bytes += value.text.toByteArray(Charsets.UTF_8).size
                        require(bytes <= KnowledgeStore.MAX_SOURCE) { "正文超过编辑上限，请替换文件。" }
                        buffer.append(value.text)
                        val next = value.nextOffset
                        require(next == null || next > offset) { "原文分页位置无效。" }
                        offset = next ?: -1
                    } while (offset >= 0)
                    buffer.toString()
                }
                draftSource = source
                title.setTextAndPlaceCursorAtEnd(source.title)
                note.setTextAndPlaceCursorAtEnd(source.note)
                body.setTextAndPlaceCursorAtEnd(text)
                draftOriginal = text
                draftCollection = source.collectionId
                draftOperation = null
                hasDraft = true
                page = "edit"
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { failure = error.message ?: "无法读取完整正文。" }
            finally { loading = false }
        }
    }
    fun save() {
        try {
            val (action, data) = draft().request()
            draftOperation = data.getString("operationId")
            retainDraft.value.invoke()
            request(action, data) { result ->
                page = "list"; searchResults = null
                val job = KnowledgeStore.accepted(result, action, data)
                if (job == null || job.effect == "published") { hasDraft = false; draftOperation = null; store.draft = null }
            }
        } catch (error: Exception) { failure = error.message }
    }
    val available = running && status?.ready == true && !loading && !transfer.busy && !picking
    val collections = catalog?.collections ?: status?.collections.orEmpty()
    val sources = catalog?.sources ?: status?.sources.orEmpty()
    LaunchedEffect(running, foreground) {
        if (running && foreground) request() else { status = null; catalog = null; task?.cancel() }
    }
    LaunchedEffect(managing, foreground, running, transfer.revision) {
        if (managing && foreground && running) {
            if (!loading) request()
            while (true) {
                kotlinx.coroutines.delay(6000)
                if (!loading && (draftOperation != null || status?.jobs?.any { it.active } == true)) request()
            }
        }
    }

    @Composable fun feedback() {
        if (loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfiniteProgressIndicator(Modifier.size(16.dp)); Note("正在读取或提交…")
        }
        failure?.let { Text(it, fontSize = 13.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        message?.let { Note(it) }
        if (transfer.message.isNotBlank()) Text(transfer.message, fontSize = 13.sp, lineHeight = 20.sp,
            color = if (transfer.error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (picking) Note("请选择知识文件或保存位置。")
        if (pendingAction != null && pendingData != null) {
            val pending = org.json.JSONObject(pendingData!!)
            Note("待核实操作：${pending.getString("operationId")}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("核实原操作", onClick = { request("status", org.json.JSONObject().put("operationId", pending.getString("operationId"))) }, enabled = !loading && running)
                Action("重试原请求", onClick = { request(pendingAction, pending) }, enabled = !loading && running)
            }
            status?.operations?.firstOrNull { it.getString("operationId") == pending.getString("operationId") }?.let { operation ->
                Note("已登记原操作${if (operation.isNull("jobId")) "" else "，任务 ${operation.getString("jobId")}"}；请到任务页查看实际结果。")
            }
        }
    }

    Section("知识库")
    Panel(padding = 0) {
        SettingsSwitch("用于对话", "原文保存在本机；关闭后仍可管理资料，长期记忆使用独立开关。",
            status?.enabled ?: false, available, if (!running) "先启动 dsh" else if (status == null) "状态未知" else null) { enabled ->
            status?.let { request("settings", KnowledgeStore.change(it.settingsRevision).put("settings", org.json.JSONObject().put("enabled", enabled))) }
        }
        Divider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Note(status?.let { "${it.raw.optJSONObject("counts")?.optInt("collections", it.collections.size) ?: it.collections.size} 个集合 · ${it.raw.optJSONObject("counts")?.optInt("sources", it.sources.size) ?: it.sources.size} 份资料" } ?: if (running) "知识状态尚未读取。" else "启动后可浏览、搜索与管理本机资料。")
            feedback()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("管理知识", onClick = { managing = true; page = "list"; request() }, enabled = running, icon = Lucide.MessageSquare)
                Action("刷新状态", onClick = { request() }, enabled = running && !loading)
            }
            Note("知识归档不含长期记忆、会话和模型密钥。")
        }
    }
    if (managing) MemoryDialog(when (page) { "detail" -> "资料详情"; "reference" -> "搜索原文"; "edit" -> if (draftSource == null) "粘贴新资料" else "编辑资料";
        "collections" -> "集合管理"; "tasks" -> "知识任务"; "settings" -> "知识设置"; else -> "管理知识" }, ::close, scroll = false) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(remember(page) { ScrollState(0) }), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            feedback()
            if (page != "list") Action("返回目录", onClick = { task?.cancel(); page = "list"; request() }, enabled = !loading)
            when (page) {
                "list" -> {
                    MemoryChoice("集合", listOf("" to "全部") + collections.map { it.id to "${it.title} · ${it.scopeText}" }, selected, !loading) { selected = it; searchResults = null }
                    AssistantField("手动搜索", query, 1000, loading, 2, "搜索本机文档和有效记忆")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("搜索", onClick = {
                            val data = org.json.JSONObject().put("query", query.text.toString().trim()).put("limit", 6).put("kind", "all")
                            collections.firstOrNull { it.id == selected }?.let { data.put("withinRef", "kb:collection:${it.id}:r${it.revision}") }
                            request("search", data) { result ->
                                searchResults = KnowledgeStore.search(result)
                                searchMode = if (result.getString("mode") == "hybrid") "关键词与语义检索" else "关键词检索"
                                result.optString("degradedReason").takeIf { it.isNotBlank() }?.let { message = it }
                            }
                        }, enabled = available && query.text.isNotBlank(), primary = true)
                        Action("清除搜索", onClick = { searchResults = null }, enabled = !loading)
                    }
                    val matches = searchResults
                    if (matches != null) {
                        Note("$searchMode · ${matches.size} 条结果")
                        matches.forEach { match ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(match.getString("title"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Note(match.getString("snippet"))
                                Note(if (match.getString("kind") == "memory") "来源：有效记忆" else "来源：本机资料")
                                val source = sources.firstOrNull { it.id == match.optString("sourceId") }
                                if (source != null) {
                                    Action("查看原文", onClick = { read(source) }, enabled = available && source.version == match.getLong("sourceVersion"))
                                    if (source.version != match.getLong("sourceVersion")) Note("资料已更新，请重新搜索。")
                                } else if (match.getString("kind") == "memory") Action("查看记忆原文", onClick = { readReference(match.getString("ref")) }, enabled = available)
                            }
                            Divider()
                        }
                        if (matches.isEmpty()) Note("没有匹配内容，可换一个关键词。")
                    } else {
                        val rows = sources.filter { it.state != "deleted" && (selected.isBlank() || it.collectionId == selected) }
                        if (rows.isEmpty()) Note("还没有资料，可导入文件或粘贴文本。")
                        rows.forEach { source ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = available, role = Role.Button) { read(source) }
                                .heightIn(min = 64.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(source.title, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                                Note("${source.bytes} 字节 · 版本 ${source.version} · ${collections.firstOrNull { it.id == source.collectionId }?.title ?: "集合不可用"}")
                                if (source.state == "unavailable") Note("原文不可用，可替换或删除")
                            }
                            Divider()
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("多文件导入", onClick = { file("import", org.json.JSONObject().put("collectionId", selected)) }, enabled = available && selected.isNotBlank(), icon = Lucide.Download)
                        Action("粘贴文本", onClick = { edit(null) }, enabled = available && selected.isNotBlank())
                    }
                    if (selected.isBlank()) Note("导入前先选择目标集合。")
                    if (hasDraft) Action("继续编辑草稿", onClick = { page = "edit" }, enabled = !loading)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("集合管理", onClick = { page = "collections" }, enabled = available)
                        Action("任务", onClick = { page = "tasks"; request() }, enabled = running && !loading)
                        Action("设置", onClick = { page = "settings" }, enabled = available)
                        Action("生成知识归档", onClick = { confirmation = "export" }, enabled = available, icon = Lucide.Save)
                        Action("恢复知识归档", onClick = { confirmation = "restore" }, enabled = available, icon = Lucide.RotateCcw)
                    }
                }
                "reference" -> reference?.let { value ->
                    Text(value.title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    SelectionContainer { Text(value.text, fontSize = 14.sp, lineHeight = 22.sp) }
                    if (value.nextOffset != null) Action("继续阅读", onClick = { readReference(value.ref, true) }, enabled = available)
                }
                "detail" -> current?.let { source ->
                    Text(source.title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Note("${source.bytes} 字节 · 版本 ${source.version} · 修订 ${source.revision}")
                    if (source.note.isNotBlank()) Note(source.note)
                    SelectionContainer { Text(original, fontSize = 14.sp, lineHeight = 22.sp) }
                    if (nextOffset != null) Action("继续阅读", onClick = { read(source, true) }, enabled = available)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("编辑资料", onClick = { edit(source) }, enabled = available && source.state == "active")
                        Action("替换正文文件", onClick = { file("update", org.json.JSONObject().put("sourceId", source.id).put("expectedRevision", source.revision)) }, enabled = available)
                        Action("删除资料", onClick = { confirmation = "remove" }, enabled = available, danger = true)
                    }
                    MemoryChoice("重建内容", listOf("keyword" to "关键词", "semantic" to "语义索引", "summary" to "集合摘要", "chunks" to "重新分块"), rebuildKind, available) { rebuildKind = it }
                    Action("重建", onClick = { confirmation = "rebuild" }, enabled = available)
                }
                "edit" -> {
                    AssistantField("标题", title, 160, loading || draftOperation != null, 2, codePoints = true)
                    MemoryChoice("所属集合", collections.map { it.id to "${it.title} · ${it.scopeText}" }, draftCollection, available && draftOperation == null) { draftCollection = it }
                    if (draftSource != null) AssistantField("备注", note, 2000, loading || draftOperation != null, 3, codePoints = true)
                    AssistantField("正文", body, KnowledgeStore.MAX_SOURCE.toInt(), loading || draftOperation != null, 12, "可直接粘贴 Markdown 或纯文本")
                    Note("正文最多 2 MiB。修订冲突或等待中断会保留当前编辑。")
                    if (draftOperation != null) Note("正在核实提交结果，发布前保留草稿。")
                    Action("保存资料", onClick = ::save, enabled = available && hasDraft && draftOperation == null, primary = true, icon = Lucide.Save)
                    Action("放弃草稿", onClick = { confirmation = "draftDiscard" }, enabled = !loading && draftOperation == null, danger = true)
                }
                "collections" -> {
                    collections.forEach { collection ->
                        Text("${collection.title} · ${collection.scopeText}", fontSize = 14.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Action("编辑集合", onClick = { collectionEditing = collection; collectionTitle.setTextAndPlaceCursorAtEnd(collection.title); parentId = collection.parentId.orEmpty(); collectionWorkspace = collection.scope.getString("kind") == "workspace" }, enabled = available)
                            Action("删除空集合", onClick = { collectionEditing = collection; confirmation = "collectionRemove" }, enabled = available, danger = true)
                        }
                        Divider()
                    }
                    Text(if (collectionEditing == null) "新建集合" else "编辑 ${collectionEditing!!.title}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    AssistantField("集合名称", collectionTitle, 160, loading, 2, codePoints = true)
                    MemoryChoice("上级集合", listOf("" to "根集合") + collections.filter { it.id != collectionEditing?.id }.map { it.id to it.title }, parentId, available) { parentId = it }
                    if (parentId.isBlank() && collectionEditing == null) MemoryChoice("适用范围", listOf("user" to "用户通用", "workspace" to "当前工作区"), if (collectionWorkspace) "workspace" else "user", available) { collectionWorkspace = it == "workspace" }
                    Note(if (parentId.isBlank()) "工作区范围由应用当前实际工作目录确定。" else "子集合继承上级范围，最多三层。")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("保存集合", onClick = {
                            val old = collectionEditing
                            val data = KnowledgeStore.change(old?.revision ?: status!!.catalogRevision).put("type", if (old == null) "create" else "update")
                                .put("title", collectionTitle.text.toString().trim()).put("parentId", parentId.takeIf { it.isNotBlank() } ?: org.json.JSONObject.NULL)
                            if (old != null) data.put("id", old.id)
                            else if (parentId.isBlank()) data.put("scope", org.json.JSONObject().put("kind", if (collectionWorkspace) "workspace" else "user").apply { if (collectionWorkspace) put("workspace", store.workspace) })
                            request("collection", data) { collectionEditing = null; collectionTitle.setTextAndPlaceCursorAtEnd(""); parentId = "" }
                        }, enabled = available && collectionTitle.text.isNotBlank(), primary = true)
                        if (collectionEditing != null) Action("改为新建", onClick = { collectionEditing = null; collectionTitle.setTextAndPlaceCursorAtEnd(""); parentId = "" }, enabled = !loading)
                    }
                }
                "tasks" -> {
                    AssistantField("操作或任务编号", taskQuery, 256, loading, 2, "可粘贴先前回执或错误提示中的编号")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("operationId" to "查询操作", "jobId" to "查询任务").forEach { (key, label) ->
                            Action(label, onClick = {
                                request("status", org.json.JSONObject().put(key, taskQuery.text.toString().trim())) { result ->
                                    foundJob = if (result.isNull("job")) null else KnowledgeStore.job(result.getJSONObject("job"))
                                    if (foundJob == null && !result.isNull("operation")) {
                                        val operation = result.getJSONObject("operation")
                                        message = "原操作已登记：${operation.optString("effect")}。"
                                    } else if (foundJob == null) message = "没有找到对应记录，请核对编号或重试期限。"
                                }
                            }, enabled = !loading && running && taskQuery.text.isNotBlank())
                        }
                    }
                    if (status?.raw?.optJSONObject("hasMore")?.optBoolean("jobs") == true) Note("当前只展示最近或活动任务；更早任务可按编号查询。")
                    val jobs = (listOfNotNull(foundJob) + status?.jobs.orEmpty()).distinctBy { it.id }
                    if (jobs.isEmpty()) Note("暂无知识任务。")
                    jobs.forEach { job ->
                        Text("${job.label} · ${job.stageText}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Note("${job.effectText}${if (job.progress.isBlank()) "" else " · ${job.progress}"}")
                        Note("任务 ${job.id}")
                        job.errorCode?.let { Note("$it：检查来源或模型配置后继续。") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (job.state in setOf("waitingRetry", "failed")) Action("继续任务", onClick = { request("retry", KnowledgeStore.change().put("jobId", job.id)) }, enabled = available)
                            if (job.active || job.state == "waitingRetry") Action("取消未完成部分", onClick = { request("cancel", KnowledgeStore.change().put("jobId", job.id)) }, enabled = available, danger = true)
                            if (job.type == "export" && job.state == "succeeded") {
                                val result = job.result ?: status?.operations?.firstOrNull { it.optString("operationId") == job.operationId }?.optJSONObject("result")
                                if (result != null) Action("保存知识归档", onClick = { file("export", org.json.JSONObject().put("operationId", job.operationId).put("result", result)) }, enabled = available, icon = Lucide.Save)
                                else Action("保存知识归档", onClick = {
                                    request("status", org.json.JSONObject().put("jobId", job.id)) { value ->
                                        val finished = KnowledgeStore.job(value.getJSONObject("job"))
                                        require(finished.id == job.id && finished.state == "succeeded" && finished.operationId == job.operationId) { "知识归档尚未准备完成。" }
                                        val archive = finished.result ?: throw IllegalStateException("知识归档结果尚未返回，请稍后重试。")
                                        file("export", org.json.JSONObject().put("operationId", job.operationId).put("result", archive))
                                    }
                                }, enabled = available, icon = Lucide.Save)
                            }
                        }
                        Divider()
                    }
                    Note("关闭此窗口只停止等待和刷新；取消只停止尚未完成的部分，已经生效的操作不会回退。")
                }
                "settings" -> KnowledgeOptions(store, status, available) { revision, settings, done ->
                    request("settings", KnowledgeStore.change(revision).put("settings", settings), done)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Divider()
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) Action("停止等待", onClick = { task?.cancel(); message = "已停止等待，请刷新核实实际结果。" })
            Action("刷新", onClick = { request() }, enabled = running && !loading)
            Action("关闭", onClick = ::close)
        }
    }
    confirmation?.let { action ->
        MemoryDialog(when (action) { "draftDiscard" -> "放弃草稿"; "remove" -> "删除资料"; "collectionRemove" -> "删除空集合"; "rebuild" -> "确认重建"; "export" -> "导出知识库"; else -> "合并恢复知识库" }, { confirmation = null }) {
            Note(when (action) {
                "draftDiscard" -> "放弃尚未提交的编辑内容，正式资料不变。"
                "remove" -> "将删除“${current?.title}”。删除生效后不可撤销，文件清理状态请查看任务。"
                "collectionRemove" -> "仅删除空集合“${collectionEditing?.title}”；含资料或子集合时会拒绝。"
                "rebuild" -> when (rebuildKind) {
                    "chunks" -> "重新分块会创建新的资料版本，旧的片段引用将失效。"
                    "semantic" -> "将使用已配置的嵌入服务处理这份资料（${current?.bytes ?: 0} 字节）。使用云端服务时会发送正文。"
                    "summary" -> "将使用已配置的摘要服务处理当前集合的 ${sources.count { it.collectionId == current?.collectionId }} 份资料，云端服务会接收参与摘要的正文。"
                    else -> "从当前资料重新建立本机关键词索引，不改变原文版本。"
                }
                "export" -> "生成全部有效文档与集合的知识归档，完成后在任务页选择保存位置。不含记忆、会话和模型密钥。"
                else -> "选择本机知识归档后合并恢复，同范围同内容跳过、同名不同内容新增。归档中的工作区资料统一映射为用户通用，不覆盖现有资料。"
            })
            if (action == "rebuild" && rebuildKind in setOf("semantic", "summary")) {
                val config = if (rebuildKind == "semantic") status?.embedding else status?.summary
                Note("服务：${config?.optString("baseURL")?.takeIf { it.isNotBlank() } ?: config?.optString("provider")?.takeIf { it.isNotBlank() } ?: "尚未配置"}；模型：${config?.optString("model")?.takeIf { it.isNotBlank() } ?: "尚未配置"}")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("取消", onClick = { confirmation = null })
                Action("确认", onClick = {
                    confirmation = null
                    when (action) {
                        "draftDiscard" -> { hasDraft = false; draftOperation = null; store.draft = null; page = "list" }
                        "remove" -> current?.let { request("remove", KnowledgeStore.change(it.revision).put("sourceId", it.id)) { page = "tasks" } }
                        "collectionRemove" -> collectionEditing?.let { request("collection", KnowledgeStore.change(it.revision).put("type", "remove").put("id", it.id)) { collectionEditing = null } }
                        "rebuild" -> current?.let {
                            val revision = if (rebuildKind == "summary") collections.firstOrNull { collection -> collection.id == it.collectionId }?.revision ?: return@let else it.revision
                            val data = KnowledgeStore.change(revision).put("kind", rebuildKind)
                            if (rebuildKind == "summary") data.put("collectionId", it.collectionId) else data.put("sourceId", it.id)
                            request("rebuild", data) { page = "tasks" }
                        }
                        "export" -> request("export", KnowledgeStore.change(status?.catalogRevision)) { page = "tasks" }
                        else -> file("restore", org.json.JSONObject())
                    }
                }, enabled = available, primary = action !in setOf("remove", "collectionRemove"), danger = action in setOf("remove", "collectionRemove"))
            }
        }
    }
}

@Composable
private fun KnowledgeOptions(store: KnowledgeStore, status: KnowledgeStore.Status?, enabled: Boolean,
                             save: (Long, org.json.JSONObject, (org.json.JSONObject) -> Unit) -> Unit) {
    val saved = remember(store) { store.optionsDraft }
    var loaded by remember { mutableStateOf(saved != null) }
    var revision by remember { mutableStateOf(saved?.optLong("revision") ?: 0L) }
    var baseEmbedding by remember { mutableStateOf(saved?.optString("embedding") ?: "{}") }
    var baseSummary by remember { mutableStateOf(saved?.optString("summary") ?: "{}") }
    var semantic by remember { mutableStateOf(saved?.optBoolean("semantic") ?: false) }
    var summaries by remember { mutableStateOf(saved?.optBoolean("summaries") ?: false) }
    var kinds by remember { mutableStateOf(saved?.optString("kinds") ?: "document") }
    var confirm by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val provider = rememberTextFieldState(saved?.optString("provider").orEmpty())
    val model = rememberTextFieldState(saved?.optString("model").orEmpty())
    val dimensions = rememberTextFieldState(saved?.optString("dimensions").orEmpty())
    val summaryProvider = rememberTextFieldState(saved?.optString("summaryProvider").orEmpty())
    val summaryModel = rememberTextFieldState(saved?.optString("summaryModel").orEmpty())
    val retain = rememberUpdatedState {
        store.optionsDraft = if (!loaded) null else org.json.JSONObject().put("revision", revision)
            .put("embedding", baseEmbedding).put("summary", baseSummary).put("semantic", semantic).put("summaries", summaries)
            .put("kinds", kinds).put("provider", provider.text.toString()).put("model", model.text.toString())
            .put("dimensions", dimensions.text.toString()).put("summaryProvider", summaryProvider.text.toString()).put("summaryModel", summaryModel.text.toString())
    }
    DisposableEffect(store) { onDispose { retain.value.invoke() } }
    LaunchedEffect(status?.settingsRevision, loaded) {
        if (!loaded && status != null) {
            val embedding = status.embedding.optJSONObject("config") ?: status.embedding
            val summary = status.summary.optJSONObject("config") ?: status.summary
            semantic = embedding.optBoolean("enabled")
            summaries = summary.optBoolean("enabled")
            provider.setTextAndPlaceCursorAtEnd(embedding.optString("provider"))
            model.setTextAndPlaceCursorAtEnd(embedding.optString("model"))
            dimensions.setTextAndPlaceCursorAtEnd(if (embedding.has("dimensions")) embedding.opt("dimensions")?.toString().orEmpty() else "")
            summaryProvider.setTextAndPlaceCursorAtEnd(summary.optString("provider"))
            summaryModel.setTextAndPlaceCursorAtEnd(summary.optString("model"))
            val sourceKinds = embedding.optJSONArray("kinds") ?: embedding.optJSONArray("sourceKinds")
            kinds = if (sourceKinds?.length() == 2) "all" else sourceKinds?.optString(0) ?: "document"
            revision = status.settingsRevision
            baseEmbedding = embedding.toString()
            baseSummary = summary.toString()
            loaded = true
        }
    }
    Text("本机关键词", fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Note("不需要嵌入服务。语义未配置、覆盖不完整或调用失败时使用关键词搜索。")
    Divider()
    SettingsSwitch("语义检索", "通过专用嵌入模型查找含义相近的内容。", semantic, enabled) { semantic = it }
    if (semantic) {
        AssistantField("嵌入服务", provider, 256, !enabled, 2, "已配置服务的标识")
        AssistantField("嵌入模型", model, 256, !enabled, 2, "填写支持嵌入的模型名称")
        AssistantField("向量维度", dimensions, 5, !enabled, 1, "与嵌入模型实际输出一致")
        MemoryChoice("处理资料", listOf("document" to "文档", "memory" to "有效记忆", "all" to "文档与有效记忆"), kinds, enabled) { kinds = it }
    }
    Divider()
    SettingsSwitch("集合摘要", "按需生成集合概览，原文仍可直接搜索和阅读。", summaries, enabled) { summaries = it }
    if (summaries) {
        AssistantField("摘要服务", summaryProvider, 256, !enabled, 2, "已配置服务的标识")
        AssistantField("摘要模型", summaryModel, 256, !enabled, 2)
    }
    Note("服务地址与密钥沿用聊天中的模型服务配置。聊天模型和嵌入模型用途不同，请确认服务支持所选模型。")
    if (semantic) Note("语义状态：${status?.embedding?.optString("state")?.takeIf { it.isNotBlank() } ?: "以搜索返回的实际覆盖为准"}")
    if (summaries) Note("摘要状态：${status?.summary?.optString("state")?.takeIf { it.isNotBlank() } ?: "保存配置后可手动重建集合摘要"}")
    failure?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
    if (loaded && status != null && revision != status.settingsRevision) {
        Note("设置已在其他操作中变化，当前修改已保留。")
        Action("放弃修改并载入最新设置", onClick = { loaded = false }, enabled = enabled)
    }
    Action("保存知识设置", onClick = { confirm = true }, enabled = enabled && loaded, primary = true, icon = Lucide.Save)
    if (confirm) MemoryDialog("保存知识设置", { confirm = false }) {
        Note(if (semantic || summaries) "开启后，所选资料的正文会发送给对应模型服务，用于生成向量或摘要。服务配置会用于后续批处理；原文和结果仍保存在本机。"
            else "关闭语义与摘要加工，保留本机原文与关键词能力。")
        if (semantic) Note("嵌入服务：${provider.text}；模型：${model.text}；处理范围：${when (kinds) { "all" -> "文档与有效记忆"; "memory" -> "有效记忆"; else -> "文档" }}")
        if (summaries) Note("摘要服务：${summaryProvider.text}；模型：${summaryModel.text}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("取消", onClick = { confirm = false })
            Action("确认保存", onClick = {
                try {
                    val embedding = org.json.JSONObject()
                    val previousEmbedding = org.json.JSONObject(baseEmbedding)
                    val previousSummary = org.json.JSONObject(baseSummary)
                    previousEmbedding.keys().asSequence().filter { it in KnowledgeStore.configurationFields("embedding") }.forEach { embedding.put(it, previousEmbedding.get(it)) }
                    embedding.put("enabled", semantic)
                    if (semantic) {
                        require(provider.text.isNotBlank() && model.text.isNotBlank()) { "请填写嵌入服务和模型。" }
                        val size = dimensions.text.toString().toIntOrNull()
                        require(size != null && size in 1..8192) { "向量维度须为 1 至 8192 的整数。" }
                        embedding.put("provider", provider.text.toString().trim()).put("model", model.text.toString().trim()).put("dimensions", size)
                            .put("kinds", org.json.JSONArray(if (kinds == "all") listOf("document", "memory") else listOf(kinds)))
                    }
                    val summary = org.json.JSONObject()
                    previousSummary.keys().asSequence().filter { it in KnowledgeStore.configurationFields("summary") }.forEach { summary.put(it, previousSummary.get(it)) }
                    summary.put("enabled", summaries)
                    if (summaries) {
                        require(summaryProvider.text.isNotBlank() && summaryModel.text.isNotBlank()) { "请填写摘要服务和模型。" }
                        summary.put("provider", summaryProvider.text.toString().trim()).put("model", summaryModel.text.toString().trim())
                    }
                    failure = null
                    confirm = false
                    val settings = org.json.JSONObject()
                    KnowledgeStore.settingsPatch(previousEmbedding, embedding, "embedding")?.let { settings.put("embedding", it) }
                    KnowledgeStore.settingsPatch(previousSummary, summary, "summary")?.let { settings.put("summary", it) }
                    require(settings.length() > 0) { "没有要保存的修改。" }
                    save(revision, settings) { result ->
                        revision = KnowledgeStore.number(result, "settingsRevision")
                        baseEmbedding = (result.optJSONObject("embedding") ?: embedding).toString()
                        baseSummary = (result.optJSONObject("summary") ?: summary).toString()
                        retain.value.invoke()
                    }
                } catch (error: Exception) { confirm = false; failure = error.message }
            }, enabled = enabled, primary = true)
        }
    }
}
