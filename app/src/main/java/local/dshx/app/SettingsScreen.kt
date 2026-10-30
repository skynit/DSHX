package local.dshx.app

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Upload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsContent(
    page: AppPage, engine: EngineState, theme: String, onTheme: (String) -> Unit, onStop: () -> Unit,
    assistant: AssistantStore, voice: VoiceControl, state: VoiceControl.State, action: (String) -> Unit,
    phone: PhoneControl, phoneRevision: Int, permission: (String) -> Unit,
    backupPicking: Boolean, onBackup: (Boolean) -> Unit,
    onPage: (AppPage) -> Unit, onLibrary: () -> Unit,
    onHostingSession: (String) -> Unit,
    onApps: (String, JSONObject) -> JSONObject,
    onCreateApp: (JSONObject) -> JSONObject,
) {
    when (page) {
        AppPage.Settings -> {
            Section("助手")
            Panel(padding = 0) {
                LinkRow("助手与资料", "名字、身份、提示词与知识库", { onPage(AppPage.Assistant) })
                Divider()
                LinkRow("语音交互", "${if (state.active) state.status else "唤醒未开启"} · ${outputName(state.output.provider)} 播报", { onPage(AppPage.VoiceOptions) })
            }
            Section("能力")
            Panel(padding = 0) {
                LinkRow("任务分工", "${if (voice.local) "已开启" else "未开启"} · 简单语音任务与截图识别", { onPage(AppPage.Tasks) })
                Divider()
                LinkRow("手机操作与权限", "屏幕操作、悬浮提示与通知", { onPage(AppPage.Permissions) })
                Divider()
                LinkRow("实验体", "培养指令与后台探索", { onPage(AppPage.Hosting) })
                Divider()
                LinkRow("微应用", "添加功能、配置与运行记录", { onPage(AppPage.Apps) })
            }
            Section("应用")
            Panel(padding = 0) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsTitle("外观")
                    SettingsOptions(theme, listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), onSelect = onTheme)
                }
                Divider()
                LinkRow("备份与恢复", "会话与附件的导出、恢复", { onPage(AppPage.Backup) })
            }
        }
        AppPage.Assistant -> {
            Note("设置助手的称呼与回答方式。修改后下次对话生效。")
            Section("助手人设")
            AssistantSettings(assistant)
            Section("资料管理")
            Panel(padding = 0) { LinkRow("知识库", "管理资料、记忆、文件和技能", onLibrary) }
        }
        AppPage.VoiceOptions -> VoiceSettings(voice, state, action)
        AppPage.Tasks -> SmallModelSettings(voice, state, action)
        AppPage.Permissions -> PhoneSettings(phone, phoneRevision, permission)
        AppPage.Apps -> AppsSettings(engine.url != null, phoneRevision, onApps, onCreateApp, onHostingSession, permission,
            back = { onPage(AppPage.Settings) })
        AppPage.Backup -> BackupSettings(engine, backupPicking, onStop, onBackup)
        else -> Unit
    }
}

@Composable
private fun SettingsTitle(text: String) = Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.semantics { heading() })

internal fun hostingSettings(enabled: Boolean, probability: String, session: String, prompt: String): JSONObject {
    val chance = probability.trim().toIntOrNull()
    require(chance != null && chance in 0..100) { "触发概率请填写 0 至 100 的整数。" }
    val target = session.trim()
    require(target.isEmpty() || target.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "目标会话编号无效，可留空使用独立会话。" }
    val task = prompt.trim()
    require(task.length <= 4000) { "培养指令不能超过 4000 个 UTF-16 单位。" }
    require(!enabled || task.isNotEmpty()) { "请先填写培养指令，再启用实验体。" }
    return JSONObject().put("enabled", enabled).put("probability", chance).put("session", target).put("prompt", task)
}

internal fun hostingTime(time: Long): String = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault())
    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

internal fun hostingResult(row: JSONObject): String = when (row.optString("outcome")) {
    "completed" -> "本轮已结束"
    "failed" -> "本轮执行失败"
    "unknown" -> "操作结果未知，未重放"
    else -> when (row.optString("reason")) {
        "probability" -> "本次未命中"
        "locked" -> "设备锁屏，已跳过"
        "screen_off" -> "设备息屏，已跳过"
        "phone_disabled" -> "手机操作已关闭，已跳过"
        "accessibility_offline" -> "无障碍未连接，已跳过"
        "busy" -> "已有任务或输入，已跳过"
        "not_configured" -> "任务配置未完成"
        "settings_changed" -> "设置已更改，本次已取消"
        "user_input" -> "用户输入已接管"
        "timeout" -> "本轮超时，已取消"
        "stopped", "session_disposed" -> "本轮已停止"
        "child_session" -> "目标不是普通会话，已跳过"
        else -> if (row.optString("event") == "start") "概率命中，开始执行" else "本次未完成，请查看会话结果"
    }
}

/** 折叠只控制内容是否显示；表单状态由调用者持有，收起不会丢弃草稿。 */
@Composable
internal fun SettingsFold(
    title: String, summary: String, expanded: MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .semantics(mergeDescendants = true) { stateDescription = if (expanded.value) "已展开" else "已折叠" }
            .clickable(role = Role.Button) { expanded.value = !expanded.value }.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Note(summary)
            }
            Icon(if (expanded.value) Lucide.ChevronDown else Lucide.ChevronRight, contentDescription = null,
                modifier = Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (expanded.value) {
            Divider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun SettingsOptions(
    selected: String, options: List<Pair<String, String>>, enabled: Boolean = true, onSelect: (String) -> Unit,
) {
    FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val shape = RoundedCornerShape(8.dp)
            Box(Modifier.heightIn(min = 48.dp).clip(shape)
                .background(if (selected == value) MiuixTheme.colorScheme.secondary else Color.Transparent)
                .border(1.dp, if (selected == value) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline, shape)
                .selectable(selected == value, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(value) })
                .padding(12.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 13.sp, color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun SettingsSelect(
    title: String, selected: String, options: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(8.dp))
        .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, role = Role.Button) { open = true }.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), fontSize = 14.sp,
                color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Icon(Lucide.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    if (open && enabled) MemoryDialog(title, close = { open = false }) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                VoiceChoice(label, "", selected == value, true) { onSelect(value); open = false }
            }
        }
    }
}

private fun outputName(provider: String): String = when (provider) {
    "volc" -> "火山引擎"
    "volc-audio" -> "火山音频生成 1.0"
    else -> "Edge TTS"
}

@Composable
private fun SettingsFeedback(message: String?, error: String? = null) {
    (error ?: message)?.let {
        Text(it, fontSize = 12.sp, lineHeight = 19.sp,
            color = if (error != null) MiuixTheme.colorScheme.error else LocalStatusColor.current,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun BackupSettings(state: EngineState, picking: Boolean, stop: () -> Unit, backup: (Boolean) -> Unit) {
    val active = state.url != null || state.busy && !state.backup.busy
    val enabled = !active && !state.busy && !picking
    Note("备份会话与附件。独立记忆不包含在此备份中。")
    if (active) Panel {
        SettingsTitle("备份与恢复前需停止服务")
        Spacer(Modifier.height(8.dp))
        Note("先停止正在运行的服务，再选择文件。")
        Spacer(Modifier.height(12.dp))
        Action("停止服务", stop, enabled = state.url != null && !state.busy, icon = Lucide.Square)
    }
    Section("会话数据")
    Panel(padding = 0) {
        SettingsRow("备份会话", "导出会话与附件") { Action("备份", { backup(false) }, enabled = enabled, icon = Lucide.Save) }
        Divider()
        SettingsRow("恢复会话", "从备份文件恢复，自动跳过已有会话") { Action("恢复", { backup(true) }, enabled = enabled, icon = Lucide.RotateCcw) }
    }
    val message = when {
        state.backup.busy -> "${state.phase}…"
        picking -> "请选择文件或保存位置"
        else -> state.backup.message
    }
    SettingsFeedback(message, state.backup.message.takeIf { state.backup.error })
    Panel(padding = 0) {
        SettingsFold("备份包含什么", "会话、附件；不包含独立记忆") {
            Note("恢复会自动跳过已有会话。知识库与独立记忆不会自动加入此备份。")
        }
    }
}

@Composable
private fun AssistantSettings(store: AssistantStore) {
    val name = rememberTextFieldState()
    val identity = rememberTextFieldState()
    val prompt = rememberTextFieldState()
    val details = rememberSaveable { mutableStateOf(false) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var saved by remember { mutableStateOf<AssistantStore.Settings?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var settingsError by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
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
            result.onSuccess { saved = it; if (!initialized) fill(it) }
                .onFailure { settingsError = "读取设置失败：${it.message ?: "请重试"}。可重试读取，或编辑后保存、恢复默认。" }
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
                message = if (defaults) "已恢复默认人设。" else "已保存，下次对话生效。"
            }.onFailure {
                settingsError = "保存设置失败：${it.message ?: "请重试"}"
                details.value = true
            }
            saving = false
        }
    }
    LaunchedEffect(store) { readSettings() }
    val draft = AssistantStore.Settings(name.text.toString(), identity.text.toString(), prompt.text.toString())
    val dirty = saved?.let { it.displayed() != draft } ?: initialized
    Panel(padding = 0) {
        Column(Modifier.padding(16.dp)) {
            AssistantField("名字", name, 80, loading || saving, 1, "DS 默认，无固定名字")
        }
        Divider()
        SettingsFold("身份与提示词", "自定义助手身份、回答规则", details) {
            AssistantField("身份", identity, 2000, loading || saving, 3)
            AssistantField("自定义提示词", prompt, 16000, loading || saving, 5)
            Note("留空时沿用默认内容。")
            Action("恢复默认", { save(AssistantStore.Settings(), true) }, enabled = !loading && !saving, icon = Lucide.RotateCcw)
        }
        if (dirty || loading || saving || settingsError != null || message != null) {
            Divider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dirty && !loading || saving) Action(if (saving) "保存中…" else "保存人设", { save(draft, false) },
                    enabled = !loading && !saving, primary = true, icon = Lucide.Save)
                if (loading) Note("读取中…")
                if (dirty && !loading) Note("有未保存的修改。")
                SettingsFeedback(message, settingsError)
                if (settingsError != null) Action("重试读取设置", ::readSettings, enabled = !loading && !saving)
            }
        }
    }
}

@Composable
private fun VoiceSettings(voice: VoiceControl, state: VoiceControl.State, action: (String) -> Unit) {
    val wake = rememberTextFieldState(voice.wakeWord)
    val wordOpen = rememberSaveable { mutableStateOf(false) }
    var interrupt by rememberSaveable { mutableStateOf(voice.interrupt) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Section("语音输入")
    Panel(padding = 0) {
        SettingsRow("声音唤醒", if (state.active) state.status else "说出唤醒短语后开始收音") {
            if (state.active) Action(if (state.downloading) "取消下载" else "停止", { action("stop") }, icon = Lucide.Square)
            else Action("开启", { action("wake") }, enabled = state.speechAvailable, primary = true, icon = Lucide.Mic)
        }
        if (state.error != null) Column(Modifier.padding(horizontal = 16.dp)) { SettingsFeedback(null, state.error) }
        Divider()
        SettingsFold("唤醒短语", voice.wakeWord, wordOpen) {
            AssistantField("唤醒短语", wake, 40, state.active, 1)
            if (wake.text.toString().trim() != voice.wakeWord) {
                Action("保存唤醒词", {
                    runCatching { voice.wakeWord = wake.text.toString() }
                        .onSuccess { message = "唤醒词已保存"; error = null }
                        .onFailure { error = it.message ?: "保存失败，请重试。"; message = null }
                }, enabled = !state.active && wake.text.isNotBlank() && wake.text.length <= 40)
            }
            SettingsFeedback(message, error)
            Note(if (state.active) "停止语音后可修改。后台监听会显示持续通知。" else "说出短语，进入收音状态后再说问题。")
        }
        Divider()
        SettingsFold("系统语音识别", if (state.speechAvailable) "使用手机配置的识别服务" else "系统识别服务不可用") {
            Note(if (state.speechAvailable) "由手机系统服务把声音转成文字，无需下载识别模型。是否联网取决于该服务。"
                else "手机没有可用的语音识别服务，请先在系统设置中安装或启用。")
            Action("打开系统语音设置", { action("speech-settings") }, enabled = !state.active, icon = Lucide.Settings2)
        }
    }
    if (!state.speechAvailable) Note("先在系统语音设置中启用识别服务，即可开启唤醒。")
    Section("回答播报")
    VoiceOutputSettings(voice, state, action)
    Section("对话体验")
    Panel(padding = 0) {
        SettingsSwitch("语音打断", "回答或播报时可直接说话打断；也可点麦克风插话。", interrupt, !state.active) {
            interrupt = it; voice.interrupt = it
        }
    }
    Panel(padding = 0) {
        SettingsFold("语音数据如何使用", "识别、聊天和播报由不同服务处理") {
            Note("声音交给手机系统识别；识别后的对话文字交给聊天模型；回答文字交给播报服务。播报失败时保留文字回答。")
        }
    }
}

@Composable
private fun SmallModelSettings(voice: VoiceControl, state: VoiceControl.State, action: (String) -> Unit) {
    var local by rememberSaveable { mutableStateOf(voice.local) }
    var mirror by rememberSaveable { mutableStateOf(voice.mirror) }
    var source by rememberSaveable(state.smallModel.source) { mutableStateOf(state.smallModel.source) }
    val provider = rememberTextFieldState(state.smallModel.provider)
    val model = rememberTextFieldState(state.smallModel.model)
    val imageProvider = rememberTextFieldState(state.smallModel.imageProvider)
    val imageModel = rememberTextFieldState(state.smallModel.imageModel)
    val simpleOpen = rememberSaveable { mutableStateOf(false) }
    val imageOpen = rememberSaveable { mutableStateOf(false) }
    val overrideOpen = rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val draft = SmallModel.Settings(source, provider.text.toString().trim(), model.text.toString().trim(),
        imageProvider.text.toString().trim(), imageModel.text.toString().trim())
    val changed = draft != state.smallModel
    val paired = imageProvider.text.isBlank() == imageModel.text.isBlank()
    val current = when (source) {
        "online" -> "在线服务 · ${model.text.ifBlank { "待配置" }}"
        "custom" -> "导入本地 · ${state.customModel.ifBlank { "未导入" }}"
        else -> "默认本地 · ${if (state.localReady) "已下载" else "未下载"}"
    }
    Note("为简单语音任务与截图识别指定处理模型。复杂任务仍使用当前聊天模型。")
    Panel(padding = 0) {
        SettingsSwitch("开启任务分工", if (local) "已开启，使用下方配置" else "未开启，可先准备下方配置", local, !state.active) {
            local = it; voice.local = it
        }
    }
    Section("按任务配置")
    Panel(padding = 0) {
        SettingsFold("简单语音任务", current, simpleOpen) {
            SettingsOptions(source, listOf("default" to "默认本地", "custom" to "导入本地", "online" to "在线服务"), !state.active) {
                source = it; message = null; error = null; attempted = false
            }
            when (source) {
                "online" -> {
                    Note("先在聊天的模型设置中配置服务地址和密钥，再填写该服务标识。简单任务的上下文将发送至这个服务。")
                    AssistantField("服务标识", provider, 256, state.active, 1, "已配置服务的标识",
                        issue = if (attempted && provider.text.isBlank()) "请填写服务标识" else null)
                    AssistantField("模型名称", model, 256, state.active, 2, "该服务提供的模型名称",
                        issue = if (attempted && model.text.isBlank()) "请填写模型名称" else null)
                }
                "custom" -> {
                    SettingsTitle("导入本地模型")
                    Note(state.customModel.ifBlank { "尚未导入模型。" })
                    Note("选择包含对话模板的 GGUF 文件，在手机本地运行。GGUF 是本地模型文件格式。")
                    Action(if (state.customModel.isBlank()) "选择模型文件" else "更换模型", { action("model-import") },
                        enabled = !state.active, icon = Lucide.Upload)
                }
                else -> {
                    SettingsRow("Qwen3 0.6B · 本地", "简单语音任务模型 · 约 379 MiB · ${if (state.localReady) "已下载" else "未下载"}") {
                        Action(if (state.localReady) "重新校验" else "下载模型", { action("download-local") }, enabled = !state.active, icon = Lucide.Download)
                    }
                    Note("处理语音识别后的文字任务，仅支持文字输入。")
                    Panel(padding = 0) {
                        SettingsFold("模型文件下载来源", if (mirror) "HF Mirror 镜像" else "Hugging Face 官方") {
                            SettingsOptions(if (mirror) "mirror" else "official", listOf("official" to "Hugging Face 官方", "mirror" to "HF Mirror 镜像"), !state.active) {
                                mirror = it == "mirror"; voice.mirror = mirror
                            }
                            Note("仅用于下载此模型文件，不发送录音。")
                        }
                    }
                }
            }
        }
        Divider()
        SettingsFold("截图识别", if (imageProvider.text.isNotBlank() && imageModel.text.isNotBlank()) "独立模型 · ${imageModel.text}" else "未指定独立图片模型", imageOpen) {
            Note("需要支持图片输入的模型。未指定时先沿用简单任务的模型；该模型仅支持文字时，由支持图片的当前聊天模型处理。")
            Panel(padding = 0) {
                SettingsFold("指定独立图片模型", if (imageProvider.text.isNotBlank() && imageModel.text.isNotBlank()) imageModel.text.toString() else "默认不指定，按上方规则处理", overrideOpen) {
                    AssistantField("图片服务标识", imageProvider, 256, state.active, 1, "与模型名称同时填写",
                        issue = if (attempted && !paired && imageProvider.text.isBlank()) "请同时填写图片服务标识" else null)
                    AssistantField("图片模型名称", imageModel, 256, state.active, 2, "支持图片输入的模型",
                        issue = if (attempted && !paired && imageModel.text.isBlank()) "请同时填写图片模型名称" else null)
                    Note("两项同时留空即恢复默认处理规则。")
                }
            }
        }
    }
    if (changed) {
        Action("保存任务配置", {
            attempted = true
            try {
                voice.saveSmallModel(draft)
                error = null; message = "已保存，下次任务生效。"
            } catch (failure: Exception) {
                message = null; error = failure.message ?: "保存失败。"
                if (!paired || draft.imageProvider == "dshx-local" ||
                    listOf(draft.imageProvider, draft.imageModel).any { it.length > 256 || it.any(Char::isISOControl) }) {
                    imageOpen.value = true; overrideOpen.value = true
                } else {
                    simpleOpen.value = true
                    if (listOf(draft.provider, draft.model).any { it.length > 256 || it.any(Char::isISOControl) }) source = "online"
                }
            }
        }, enabled = !state.active, primary = true, icon = Lucide.Save)
        Note("有未保存的修改。")
    }
    val failure = error ?: state.error
    SettingsFeedback(message ?: if (state.downloading) state.status else null, failure)
    if (state.downloading) Action("取消", { action("stop") }, icon = Lucide.Square)
    Note(if (state.active) "语音运行中，停止后可修改任务配置。" else "仅保存模型来源与任务配置；下载或导入文件不代表已开启分工。")
}

@Composable
private fun VoiceOutputSettings(voice: VoiceControl, state: VoiceControl.State, action: (String) -> Unit) {
    var provider by rememberSaveable { mutableStateOf(state.output.provider) }
    var auth by rememberSaveable { mutableStateOf(state.output.auth) }
    val apiKey = rememberTextFieldState(state.output.apiKey)
    val edgeVoice = rememberTextFieldState(state.output.edgeVoice)
    val appId = rememberTextFieldState(state.output.appId)
    val accessKey = rememberTextFieldState(state.output.accessKey)
    val resource = rememberTextFieldState(state.output.resource)
    val volcVoice = rememberTextFieldState(state.output.volcVoice)
    val audioVoice = rememberTextFieldState(state.output.audioVoice)
    val edgeOpen = rememberSaveable { mutableStateOf(false) }
    val resourceOpen = rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val draft = VoiceOutput.Settings(provider, edgeVoice.text.toString().trim(), appId.text.toString().trim(),
        accessKey.text.toString().trim(), resource.text.toString().trim(), volcVoice.text.toString().trim(), auth, apiKey.text.toString().trim(),
        audioVoice.text.toString().trim())
    val changed = draft != state.output
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsSelect("播报服务", provider, listOf("edge" to "Edge TTS", "volc" to "火山引擎", "volc-audio" to "火山音频生成 1.0"), !state.active) {
                provider = it; error = null; message = null; attempted = false
            }
            if (provider == "edge") {
                Note("联网播报，无需密钥，也无需下载模型。")
                SettingsTitle("常用音色")
                SettingsOptions(edgeVoice.text.toString(), listOf("zh-CN-XiaoxiaoNeural" to "晓晓 · 女声",
                    "zh-CN-YunxiNeural" to "云希 · 男声", "zh-TW-HsiaoChenNeural" to "曉臻 · 台湾女声"), !state.active) {
                    edgeVoice.setTextAndPlaceCursorAtEnd(it)
                }
                Panel(padding = 0) {
                    SettingsFold("自定义音色标识", edgeVoice.text.toString(), edgeOpen) {
                        AssistantField("Edge 音色标识", edgeVoice, 120, state.active, 1)
                        Note("知道具体音色标识时再修改。")
                    }
                }
            } else {
                Note("使用你自己的服务凭据和已开通音色。")
                SettingsTitle("服务凭据")
                SettingsOptions(auth, listOf("key" to "API Key", "token" to "App ID 与 Token"), !state.active) { auth = it; attempted = false; error = null }
                if (auth == "key") AssistantField("API Key", apiKey, 500, state.active, 1, secret = true,
                    issue = if (attempted && apiKey.text.isBlank()) "请填写服务密钥" else null)
                else {
                    AssistantField("App ID", appId, 200, state.active, 1,
                        issue = if (attempted && appId.text.isBlank()) "请填写 App ID" else null)
                    AssistantField("Access Token", accessKey, 500, state.active, 1, secret = true,
                        issue = if (attempted && accessKey.text.isBlank()) "请填写 Access Token" else null)
                }
                if (provider == "volc-audio") {
                    AssistantField("音色标识", audioVoice, 160, state.active, 2)
                    Note("默认小何 2.0，需开通音频生成服务；与语音合成分别授权。")
                } else {
                    AssistantField("音色标识", volcVoice, 160, state.active, 2)
                    Panel(padding = 0) {
                        SettingsFold("火山服务资源标识", resource.text.toString(), resourceOpen) {
                            AssistantField("资源标识", resource, 160, state.active, 1)
                            Note("来自火山引擎语音控制台，用于选择已开通的服务资源。")
                        }
                    }
                }
                Note("凭据来自火山引擎语音控制台，需开通对应音色与资源。")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (changed) Action("保存播报设置", {
                    attempted = true
                    runCatching { voice.saveOutput(draft) }
                        .onSuccess { message = "已保存，可以试听。"; error = null }
                        .onFailure {
                            error = it.message ?: "保存失败，请重试。"; message = null
                            // 服务切换会保留草稿；校验到已隐藏的字段时，带回对应服务让用户修正。
                            when {
                                !draft.edgeVoice.matches(Regex("[A-Za-z0-9-]{1,120}")) -> {
                                    provider = "edge"; edgeOpen.value = true
                                }
                                !draft.volcVoice.matches(Regex("[A-Za-z0-9_-]{1,160}")) -> provider = "volc"
                                !draft.audioVoice.matches(Regex("[A-Za-z0-9_-]{1,160}")) -> provider = "volc-audio"
                                !draft.resource.matches(Regex("[A-Za-z0-9._-]{1,160}")) -> {
                                    provider = "volc"; resourceOpen.value = true
                                }
                                listOf(draft.appId to 200, draft.accessKey to 500).any { (value, limit) ->
                                    value.length > limit || value.any { it.code !in 32..126 }
                                } -> { provider = "volc"; auth = "token" }
                                draft.apiKey.length > 500 || draft.apiKey.any { it.code !in 32..126 } -> {
                                    provider = "volc"; auth = "key"
                                }
                            }
                        }
                }, enabled = !state.active, primary = true, icon = Lucide.Save)
                Action("试听", { action("preview") }, enabled = !state.active && !changed, icon = Lucide.Play)
                if (state.active) Action("停止", { action("stop") }, icon = Lucide.Square)
            }
            Note(if (changed) "有未保存的修改，保存后可试听。" else "回答文字会发送至所选播报服务。")
            SettingsFeedback(message, error)
            if (state.active || state.status == "试听已结束" || state.error != null) SettingsFeedback(state.status, state.error)
        }
    }
}

@Composable
private fun VoiceChoice(title: String, summary: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(if (selected) colors.secondary else colors.surfaceContainerHigh)
        .border(1.dp, if (selected) colors.primary else colors.outline, RoundedCornerShape(8.dp))
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 64.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariantSummary)
            if (summary.isNotBlank()) Note(summary)
        }
        if (selected) Icon(Lucide.Check, contentDescription = "已选择", modifier = Modifier.size(18.dp),
            tint = if (enabled) colors.primary else colors.onSurfaceVariantSummary)
    }
}

@Composable
private fun PhoneSettings(phone: PhoneControl, revision: Int, permission: (String) -> Unit) {
    val status = remember(revision) { phone.status() }
    val context = LocalView.current.context
    val capabilities = status.getJSONObject("capabilities")
    Note("按需要开启系统权限。未授权项会说明影响，不要求一次全部开启。")
    Section("屏幕操作")
    Panel(padding = 0) {
        SettingsRow("无障碍服务", if (status.optBoolean("accessibility")) "服务已连接，可读取屏幕并执行点击。" else "尚未授权，用于读取屏幕并执行点击。") {
            Action("打开设置", {
                try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: RuntimeException) { Toast.makeText(context, "无法打开系统设置，请手动进入无障碍设置。", Toast.LENGTH_SHORT).show() }
            })
        }
        Divider()
        SettingsRow("悬浮提示", if (capabilities.optBoolean("overlayPermission")) "系统已允许，可在其他应用上方显示操作提示。" else "尚未授权，用于在其他应用上方显示操作提示。") {
            Action("打开设置", { permission("overlay") })
        }
    }
    Section("后台提示")
    Panel(padding = 0) {
        SettingsRow("通知权限", if (capabilities.optBoolean("notificationsPermission")) "已允许，用于后台语音与运行状态提示。" else "尚未允许，后台语音与运行状态通知可能不可见。") {
            Action("打开设置", { permission("notifications") })
        }
    }
    Panel(padding = 0) {
        SettingsFold("工具可操作的范围", "应用可访问的文件与已适配命令") {
            Note("可读写应用本身能够访问的文件、执行已适配命令；不增加 Android 系统权限，也不代表获得整台手机的全部权限。")
        }
    }
}
