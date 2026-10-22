package local.dshx.app

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.widget.Toast
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
                            Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (selected) MiuixTheme.colorScheme.secondary else Color.Transparent)
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
                chat(Modifier.padding(padding).fillMaxSize())
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
                                        onClick = { if (running) tab = 1 else onStart() }, enabled = !state.busy, primary = true)
                                    if (active) Action(if (running) "停止服务" else "取消启动", onClick = onStop, enabled = state.phase != "正在停止")
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
                            Section("运行环境")
                            Panel {
                                Info("dsh", bundle.dsh); Divider()
                                Info("Node.js", bundle.node); Divider()
                                Info("架构", bundle.abi); Divider()
                                Info("界面组件", "Miuix 0.8.8")
                            }
                            Panel(padding = 0) { LinkRow("运行诊断", "已脱敏", onClick = ::openLogs) }
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
private fun PhoneSettings(phone: PhoneControl, revision: Int, permission: (String) -> Unit) {
    var status by remember(revision) { mutableStateOf(phone.status()) }
    val context = LocalView.current.context
    Section("手机操作")
    Panel {
        val enabled = status.optBoolean("enabled")
        val connected = status.optBoolean("accessibility")
        Text(if (enabled && connected) "设备与屏幕能力已开启" else if (enabled) "设备能力已开启，屏幕操作待授权" else "手机操作已暂停", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Note("提供设备信息、应用、屏幕操作、文件、交互和感知共 23 项能力，不含虚拟屏幕。屏幕操作需要无障碍服务；下面的敏感能力可分别开启。读取的内容会提供给你配置的模型服务。")
        Spacer(Modifier.height(12.dp))
        Action(if (enabled) "暂停手机操作" else "开启手机操作", onClick = {
            phone.setEnabled(!enabled)
            status = phone.status()
        }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Action("打开无障碍设置", onClick = {
            try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (_: RuntimeException) { Toast.makeText(context, "无法打开系统设置，请手动进入无障碍设置。", Toast.LENGTH_SHORT).show() }
        }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Action("刷新状态", onClick = { status = phone.status() }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Note(if (status.optBoolean("screenshotSupported")) "当前系统支持截图；截图需要支持图片的模型。" else "当前使用控件操作，截图需要 Android 11 或更高版本。")
        Spacer(Modifier.height(12.dp))
        val capabilities = status.getJSONObject("capabilities")
        val labels = linkedMapOf("clipboard" to "剪贴板", "files" to "文件访问", "location" to "位置", "sensors" to "传感器", "torch" to "手电筒", "overlay" to "悬浮提示")
        for ((name, label) in labels) {
            val allowed = capabilities.optBoolean(name)
            Action("${if (allowed) "关闭" else "开启"}$label", onClick = {
                phone.setAllowed(name, !allowed); status = phone.status()
            }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
        }
        Note("剪贴板仅在 DSHX 窗口位于前台时读写。文件仅限应用工作区和下方授权目录；关闭文件访问会撤销目录授权。位置、相机、步数和悬浮提示还需要系统允许。")
        Spacer(Modifier.height(8.dp))
        Note("系统权限：位置${if (capabilities.optBoolean("locationPermission")) "已允许" else "未允许"} · 相机${if (capabilities.optBoolean("cameraPermission")) "已允许" else "未允许"} · 步数${if (capabilities.optBoolean("stepsPermission")) "已允许" else "未允许"} · 通知${if (capabilities.optBoolean("notificationsPermission")) "已允许" else "未允许"} · 悬浮提示${if (capabilities.optBoolean("overlayPermission")) "已允许" else "未允许"}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, label) in linkedMapOf("location" to "位置权限", "torch" to "相机权限", "steps" to "步数权限", "notifications" to "通知设置", "overlay" to "悬浮提示权限")) {
                Action(label, onClick = { permission(name) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Note(if (capabilities.optBoolean("directoryReady")) "手机目录已授权${if (capabilities.optBoolean("directoryWritable")) "，可读取与导出" else "，仅可读取"}。" else "尚未授权手机目录；应用工作区仍可在开启文件访问后读取。")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("选择手机文件目录", onClick = { permission("directory") }, enabled = capabilities.optBoolean("files"))
            Action("撤销目录授权", onClick = { permission("revoke"); status = phone.status() }, enabled = capabilities.optBoolean("directoryReady"))
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
                enabled = !loading && !saving, primary = true)
            Action("恢复默认", onClick = { save(AssistantStore.Settings(), true) }, enabled = !loading && !saving,
                modifier = Modifier.semantics { contentDescription = "恢复上游默认设置" })
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (focused || invalid) Text("$size / $limit", fontSize = 12.sp, color = if (invalid) colors.error else colors.onSurfaceVariantSummary)
        }
        BasicTextField(state = state, enabled = !disabled,
            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = if (disabled) colors.onSurfaceVariantSummary else colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            lineLimits = if (lines == 1) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(maxHeightInLines = lines),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label; if (invalid) error("$label 超出长度限制") }
                .clip(shape).background(if (disabled) colors.secondary else colors.surfaceContainerHigh)
                .border(if (focused) 2.dp else 1.dp, if (invalid) colors.error else if (focused) colors.primary else colors.outline, shape)
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
private fun Action(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, minHeight = 48.dp, cornerRadius = 8.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
