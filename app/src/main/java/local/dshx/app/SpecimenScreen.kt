package local.dshx.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Zap
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.ScrollText
import com.composables.icons.lucide.Settings2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 主画面和培养设置共用草稿与实际回执；页面切换不创建第二份后台状态。 */
internal class SpecimenState(
    private val scope: CoroutineScope,
    val probability: TextFieldState,
    val prompt: TextFieldState,
    val session: TextFieldState,
    private val initialized: MutableState<Boolean>,
    private val call: (String, JSONObject) -> JSONObject,
) {
    var status by mutableStateOf<JSONObject?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var detail by mutableStateOf<JSONObject?>(null)
        private set
    private var task: Job? = null

    val enabled: Boolean get() = status?.optJSONObject("settings")?.optBoolean("enabled") == true
    val target: String? get() = status?.optJSONObject("settings")?.optString("session")?.takeIf(String::isNotBlank)
        ?: status?.optString("session")?.takeIf { it.isNotBlank() && it != "null" }

    fun request(settings: JSONObject? = null, quiet: Boolean = false, preserveDraft: Boolean = false, done: () -> Unit = {}) {
        if (loading) return
        val data = JSONObject()
        if (settings != null) data.put("expectedRevision", status?.getLong("revision") ?: return).put("settings", settings)
        loading = true
        if (!quiet) { error = null; message = null }
        task = scope.launch {
            try {
                val result = runInterruptible(Dispatchers.IO) { call(if (settings == null) "status" else "settings", data) }
                val saved = result.getJSONObject("settings")
                if (!initialized.value || settings != null && !preserveDraft) {
                    probability.setTextAndPlaceCursorAtEnd(saved.getInt("probability").toString())
                    prompt.setTextAndPlaceCursorAtEnd(saved.getString("prompt"))
                    session.setTextAndPlaceCursorAtEnd(saved.getString("session"))
                    initialized.value = true
                }
                status = result
                error = null
                if (settings != null) message = if (saved.getBoolean("enabled")) "已保存，约每 5 分钟检查。" else "已保存，实验体未启用。"
                done()
            } catch (cause: kotlinx.coroutines.CancellationException) { throw cause }
            catch (cause: Exception) { error = cause.message?.take(220) ?: "请求未完成，请刷新核实。" }
            finally { loading = false }
        }
    }

    fun save(enabled: Boolean = this.enabled, stop: Boolean = false, done: () -> Unit = {}) {
        if (loading || status == null) return
        try {
            // 关闭使用已保存配置，即使草稿暂时无效，也能立即请求停止。
            val settings = if (stop) JSONObject(status!!.getJSONObject("settings").toString()).put("enabled", false)
                else JSONObject(status!!.getJSONObject("settings").toString()).also { saved ->
                    val draft = hostingSettings(enabled, probability.text.toString(), session.text.toString(), prompt.text.toString())
                    draft.keys().forEach { saved.put(it, draft.get(it)) }
                }
            request(settings, done = done)
        } catch (cause: Exception) { error = cause.message ?: "请检查培养设置。" }
    }

    fun manage(action: String, data: JSONObject) {
        if (loading) return
        loading = true; error = null
        task = scope.launch {
            try {
                val result = runInterruptible(Dispatchers.IO) { call(action, data) }
                if (action in setOf("evidence", "skill")) detail = result
                else {
                    status = runInterruptible(Dispatchers.IO) { call("status", JSONObject()) }
                    detail = null
                    message = when (action) { "adopt" -> "方法已采用；后续任务会重新核对适用条件。"; "discard" -> "候选已丢弃。"; else -> "已提交。" }
                }
            } catch (cause: kotlinx.coroutines.CancellationException) { throw cause }
            catch (cause: Exception) { error = cause.message?.take(220) ?: "操作未完成，请刷新核实。" }
            finally { loading = false }
        }
    }

    suspend fun leave(clear: Boolean) {
        task?.cancel(); task?.join(); task = null; loading = false
        if (clear) status = null
    }
}

@Composable
internal fun rememberSpecimenState(running: Boolean, visible: Boolean, options: Boolean, call: (String, JSONObject) -> JSONObject): SpecimenState {
    val scope = rememberCoroutineScope()
    val probability = rememberTextFieldState("20")
    val prompt = rememberTextFieldState()
    val session = rememberTextFieldState()
    val initialized = rememberSaveable { mutableStateOf(false) }
    val latest by rememberUpdatedState(call)
    val state = remember(scope, probability, prompt, session, initialized) {
        SpecimenState(scope, probability, prompt, session, initialized) { action, data -> latest(action, data) }
    }
    val lifecycle = (LocalView.current.context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
    var foreground by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        foreground = lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) != false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) foreground = true
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) foreground = false
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(running, visible, foreground, options) {
        if (!running || !visible) state.leave(clear = !running)
        if (running && visible && foreground) {
            state.request(quiet = true)
            while (!options) {
                kotlinx.coroutines.delay(10_000)
                state.request(quiet = true)
            }
        }
    }
    return state
}

internal fun specimenStatus(running: Boolean, loading: Boolean, status: JSONObject?): String = when {
    !running -> "请先启动 dsh"
    loading && status == null -> "正在读取或保存…"
    status == null -> "状态未知"
    status.optBoolean("running") -> "正在执行"
    status.optJSONObject("learning")?.optJSONArray("runs")?.let { rows ->
        (0 until rows.length()).any { rows.getJSONObject(it).optBoolean("uncertain") }
    } == true -> "等待核实上轮结果"
    status.optJSONObject("learning")?.optJSONObject("goal")?.optString("state") == "paused" -> "培养目标已暂停"
    status.optJSONObject("learning")?.optJSONObject("goal")?.optString("state") == "completed" -> "目标观察条件已满足"
    status.optJSONObject("settings")?.optBoolean("enabled") == true -> "等待下一次机会"
    else -> "未启用"
}

internal fun specimenLogs(status: JSONObject?, filter: String): List<JSONObject> {
    val recent = status?.optJSONArray("recent") ?: return emptyList()
    return (0 until recent.length()).mapNotNull { recent.optJSONObject(it) }
        .sortedByDescending { it.optLong("at") }
        .distinctBy { "${it.optString("id")}:${it.optString("event")}:${it.optLong("at")}" }
        .filter { row -> when (filter) {
            "trigger" -> row.optString("event") == "trigger"
            "skipped" -> row.optString("outcome") == "skipped"
            "execution" -> row.optString("event") in setOf("start", "finish")
            else -> true
        } }
}

@Composable
internal fun SpecimenScreen(
    state: SpecimenState, running: Boolean, options: Boolean, active: Boolean, back: () -> Unit,
    onOptions: () -> Unit, onSaved: () -> Unit, openSession: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val title = specimenStatus(running, state.loading, state.status)
    if (options) {
        SpecimenOptions(state, running, active, title, back, onSaved, openSession)
        return
    }
    var filter by rememberSaveable { mutableStateOf("all") }
    val allRows = remember(state.status) { specimenLogs(state.status, "all") }
    val rows = remember(state.status, filter) { specimenLogs(state.status, filter) }
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
            .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryIcon(Lucide.ChevronLeft, "返回设置总览", back, iconSize = 18)
            Text("实验体", modifier = Modifier.weight(1f).semantics { heading() }, fontSize = 22.sp,
                fontWeight = FontWeight.Medium)
            VoiceButton("打开培养设置", Lucide.Settings2, onOptions)
        }
        LazyColumn(Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 32.dp)) {
            item(key = "status") {
                SpecimenSummary(state, running, title, openSession)
                state.error?.let { Text(it, fontSize = 13.sp, lineHeight = 21.sp, color = colors.error,
                    modifier = Modifier.padding(top = 12.dp).semantics { error(it) }) }
                state.message?.let { Text(it, fontSize = 12.sp, lineHeight = 18.sp,
                    color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(24.dp))
            }
            item(key = "filters") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("运行日志", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    Text("${allRows.size} 条", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                    LibraryIcon(Lucide.RefreshCw, if (state.loading) "正在刷新日志" else "刷新日志",
                        { state.request() }, enabled = running && !state.loading, iconSize = 16)
                }
                LibraryFilters(listOf("all" to "全部", "trigger" to "触发", "skipped" to "跳过", "execution" to "执行"), filter) { filter = it }
                Spacer(Modifier.height(12.dp))
                Divider()
            }
            if (rows.isEmpty()) item(key = "empty") {
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Lucide.ScrollText, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(20.dp),
                        tint = colors.onSurfaceVariantSummary)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (filter == "all") "暂无事件记录" else "暂无此类事件", fontSize = 14.sp,
                            lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                        Note(if (filter == "all") "启用后，检查与执行事件会显示在这里。" else "切换分类可查看其他记录。")
                    }
                }
            }
            items(rows, key = { "${it.optString("id")}:${it.optString("event")}:${it.optLong("at")}" }) { row ->
                SpecimenLog(row, running, openSession)
            }
            item(key = "footer") {
                Spacer(Modifier.height(16.dp))
                Divider()
                Text("显示最近 100 条 · 前台每 10 秒刷新", fontSize = 12.sp, lineHeight = 18.sp,
                    color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Composable
private fun SpecimenSummary(state: SpecimenState, running: Boolean, title: String, openSession: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val statusColor = if (running && state.enabled) LocalStatusColor.current else colors.onSurfaceVariantSummary
    var focused by remember { mutableStateOf(false) }
    val nextCheck = state.status?.optLong("nextCheckAt")?.takeIf { running && state.enabled && it > 0 }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.secondary)
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(statusColor, androidx.compose.foundation.shape.CircleShape))
            Text(title, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
        }
        val metrics: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("下次检查", fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariantSummary)
                Text(nextCheck?.let(::hostingTime) ?: "—", fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("触发概率", fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariantSummary)
                Text(state.status?.optJSONObject("settings")?.let { "${it.optInt("probability")}%" } ?: "—",
                    fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (LocalDensity.current.fontScale >= 1.5f) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { metrics() }
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { metrics() }
        if (!running) Note("启动工作区后可读取本机日志。")
        state.target?.let { target ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) colors.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
                .clickable(enabled = running, role = Role.Button, onClick = { openSession(target) })
                .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("查看培养会话", fontSize = 13.sp, color = if (running) colors.primary else colors.onSurfaceVariantSummary)
                Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun SpecimenLog(row: JSONObject, running: Boolean, openSession: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val event = row.optString("event")
    var expanded by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val failed = row.optString("outcome") in setOf("failed", "unknown")
    val label = when (event) { "trigger" -> "触发命中"; "start" -> "开始执行"; "finish" -> "执行结束"; "check" -> "检查跳过"; else -> "事件记录" }
    val tint = when { failed -> colors.error; event == "trigger" || event == "start" -> colors.primary; else -> colors.onSurfaceVariantSummary }
    val icon = when { failed -> Lucide.CircleAlert; event == "trigger" -> Lucide.Zap; event == "start" -> Lucide.Play; event == "finish" -> Lucide.Square; else -> Lucide.Minus }
    val time = java.time.Instant.ofEpochMilli(row.optLong("at")).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
    val lineColor = colors.outline
    Row(Modifier.fillMaxWidth().drawBehind {
        drawLine(lineColor, Offset(14.dp.toPx(), 38.dp.toPx()), Offset(14.dp.toPx(), size.height), 1.dp.toPx())
    }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 14.dp).size(28.dp).background(colors.secondary, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
        }
        Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) colors.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
                .clickable(role = Role.Button, onClickLabel = if (expanded) "收起事件详情" else "展开事件详情") { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .heightIn(min = 48.dp).padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Icon(if (expanded) Lucide.ChevronUp else Lucide.ChevronDown, contentDescription = null,
                        modifier = Modifier.size(14.dp), tint = colors.onSurfaceVariantSummary)
                }
                Text(hostingResult(row), fontSize = 13.sp, lineHeight = 20.sp,
                    color = if (failed) colors.error else colors.onSurfaceVariantSummary)
                Text(time, fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariantSummary)
            }
            if (expanded) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.secondary)
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.has("probability") && row.has("roll")) Note("触发概率 ${row.optInt("probability")}% · 抽样值 ${row.optInt("roll")}（0～99）")
                if (row.has("durationMs")) Note("耗时 ${"%.1f".format(java.util.Locale.ROOT, row.optLong("durationMs") / 1000.0)} 秒")
                row.optJSONObject("tools")?.let { tools -> Note("手机工具：完成 ${tools.optInt("completed")} · 失败 ${tools.optInt("failed")} · 未知 ${tools.optInt("unknown")}") }
                row.optString("id").takeIf(String::isNotBlank)?.let { Note("机会编号 $it") }
                row.optString("session").takeIf { it.isNotBlank() && it != "null" }?.let { session ->
                    Action("查看会话", { openSession(session) }, enabled = running)
                }
                if (!row.has("id") && !row.has("durationMs") && !row.has("probability")) Note("此事件没有其他详情。")
            }
        }
    }
}

@Composable
private fun SpecimenOptions(
    state: SpecimenState, running: Boolean, active: Boolean, title: String, back: () -> Unit,
    onSaved: () -> Unit, openSession: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val available = running && state.status != null && !state.loading
    // 离开本次设置访问后，旧回执只更新状态，不再驱动后续页面的导航。
    val visit = remember(active) { mutableStateOf(active) }
    DisposableEffect(visit) { onDispose { visit.value = false } }
    val latestSaved by rememberUpdatedState(onSaved)
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        Row(Modifier.widthIn(max = 608.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
            .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryIcon(Lucide.ChevronLeft, "返回实验体", back, iconSize = 16)
            Text("培养设置", fontSize = 22.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).semantics { heading() })
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.widthIn(max = 608.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                .padding(horizontal = 28.dp, vertical = 8.dp)) {
                SettingsSwitch("启用实验体", "按指令探索，随时可以停止。", state.enabled, available,
                    if (state.status == null) "状态未知" else null) { state.save(it, stop = !it) }
                Divider()
                Spacer(Modifier.height(24.dp))
                SpecimenChance(state.probability, available)
                Note("约每 5 分钟检查，每次机会独立判断。")
                Spacer(Modifier.height(24.dp))
                AssistantField("培养指令", state.prompt, 4000, !available, 6,
                    "希望它探索什么？\n写下任务、允许的操作和完成标准。", minLines = 5)
                Note("决定每次触发后执行的内容。")
                Spacer(Modifier.height(16.dp))
                SettingsSwitch("整理操作方法", "将本轮必要的操作与回执交给来源模型，整理为候选技能。",
                    state.status?.optJSONObject("settings")?.optBoolean("learningEnabled") == true, available) { enabled ->
                    state.status?.optJSONObject("settings")?.let { saved ->
                        state.request(JSONObject(saved.toString()).put("learningEnabled", enabled), preserveDraft = true)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Divider()
                SettingsFold("目标会话", "默认使用独立会话") {
                    AssistantField("已有会话编号（可留空）", state.session, 128, !available, 2)
                    Note("首次触发后自动创建；填入编号时沿用该对话的模型和上下文。")
                }
                Divider()
                Spacer(Modifier.height(16.dp))
                Text("当前状态", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                Text(title, fontSize = 15.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 4.dp))
                state.status?.optLong("nextCheckAt")?.takeIf { state.enabled && it > 0 }?.let { Note("下次检查约 ${hostingTime(it)}") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 12.dp)) {
                    Action("刷新状态", { state.request() }, enabled = running && !state.loading)
                    state.target?.let { target -> Action("查看会话", { openSession(target) }, enabled = available) }
                }
                Divider()
                SpecimenLearning(state, available)
                Divider()
                Note("锁屏、息屏时跳过；恢复后不补跑。")
                Spacer(Modifier.height(24.dp))
            }
        }
        Divider()
        Column(Modifier.widthIn(max = 608.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
            .padding(horizontal = 28.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { Text(it, fontSize = 13.sp, lineHeight = 20.sp, color = colors.error,
                modifier = Modifier.semantics { error(it) }) }
            Action(if (state.loading) "正在读取或保存…" else "保存设置", {
                state.save(done = { if (visit.value) latestSaved() })
            }, Modifier.fillMaxWidth(), enabled = available, primary = true)
        }
    }
}

internal fun specimenResult(result: String): String = when (result) {
    "supported" -> "检查条件有依据"
    "failed" -> "执行未达成"
    "unknown" -> "结果未知"
    else -> "业务结果待核实"
}

@Composable
private fun SpecimenLearning(state: SpecimenState, available: Boolean) {
    val learning = state.status?.optJSONObject("learning")
    SpecimenGoal(state, learning, available)
    SettingsFold("培养结果", "查看实际结果与依据") {
        val runs = learning?.optJSONArray("runs")
        if (runs == null || runs.length() == 0) Note("暂无完整培养记录。")
        else for (index in 0 until runs.length()) {
            val row = runs.getJSONObject(index)
            Text("${hostingTime(row.optLong("startedAt"))} · ${specimenResult(row.optString("result"))}", fontSize = 13.sp, lineHeight = 21.sp)
            if (row.optString("execution") == "incomplete") Note("执行记录未完整保存。")
            Action("查看依据", { state.manage("evidence", JSONObject().put("id", row.getString("id"))) }, enabled = available)
            if (row.optBoolean("uncertain")) {
                Note("动作可能已经发生；请先查看会话并核实手机实际状态。")
                Action("已核实实际状态", {
                    state.manage("resolve", JSONObject().put("id", row.getString("id")).put("revision", learning.getLong("revision")))
                }, enabled = available)
            }
        }
        learning?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }?.let { Note(it) }
    }
    SettingsFold("操作方法", if (learning?.optBoolean("organizing") == true) "正在整理" else "候选技能与采用记录") {
        val skills = learning?.optJSONArray("skills")
        if (skills == null || skills.length() == 0) Note("暂无候选方法；没有可复用内容时不会生成。")
        else for (index in 0 until skills.length()) {
            val row = skills.getJSONObject(index)
            if (row.optString("state") == "retired") continue
            Text(row.getString("title"), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Note(if (row.optString("state") == "applied") "已采用，使用前仍需核对适用条件。" else "待采用；方法由模型整理，依据可追溯。")
            Action("查看方法", { state.manage("skill", JSONObject().put("id", row.getString("id"))) }, enabled = available)
        }
        val jobs = learning?.optJSONArray("jobs")
        if (jobs != null) for (index in 0 until jobs.length()) {
            val job = jobs.getJSONObject(index)
            if (job.optString("state") == "failed") {
                Note(job.optString("message", "方法整理未完成。"))
                if (job.optInt("attempts") < 2) Action("继续整理", {
                    state.manage("retry", JSONObject().put("id", job.getString("id")).put("revision", learning.getLong("revision")))
                }, enabled = available)
            }
        }
    }
    state.detail?.let { detail ->
        detail.optJSONObject("skill")?.let { skill ->
            SettingsFold("方法正文", skill.getString("title")) {
                androidx.compose.foundation.text.selection.SelectionContainer { Text(skill.getString("body"), fontSize = 13.sp, lineHeight = 21.sp) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(if (skill.optString("state") == "applied") "核对采用状态" else "采用技能", {
                        state.manage("adopt", JSONObject().put("id", skill.getString("id")).put("revision", skill.getLong("revision")))
                    }, enabled = available)
                    if (skill.optString("state") == "candidate" && !skill.has("operation")) Action("丢弃候选", {
                        state.manage("discard", JSONObject().put("id", skill.getString("id")).put("revision", skill.getLong("revision")))
                    }, enabled = available)
                }
                Note("采用后可在知识库的技能管理中编辑；采用不等于验证成功。")
            }
        }
        detail.optJSONArray("rows")?.let { rows ->
            SettingsFold("来源原文", "必要的任务、回答与工具回执") {
                if (detail.optJSONObject("coverage")?.optBoolean("complete") == false) Note("部分原文因敏感信息或容量限制未展示，不能视为完整依据。")
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    Text("事件 ${row.getInt("seq")}", fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(row.getString("text"), fontSize = 13.sp, lineHeight = 21.sp)
                }
            }
        }
    }
}

@Composable
private fun SpecimenGoal(state: SpecimenState, learning: JSONObject?, available: Boolean) {
    val goal = learning?.optJSONObject("goal")
    val key = goal?.optString("id") ?: "new"
    val text = rememberTextFieldState(goal?.optString("text").orEmpty())
    val check = rememberTextFieldState(goal?.optString("checkText").orEmpty())
    val count = rememberTextFieldState(goal?.optInt("maxRuns", 10)?.toString() ?: "10")
    var loaded by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(key) {
        if (loaded != key) {
            text.setTextAndPlaceCursorAtEnd(goal?.optString("text").orEmpty())
            check.setTextAndPlaceCursorAtEnd(goal?.optString("checkText").orEmpty())
            count.setTextAndPlaceCursorAtEnd(goal?.optInt("maxRuns", 10)?.toString() ?: "10")
            loaded = key
        }
    }
    SettingsFold("培养目标", when (goal?.optString("state")) {
        "active" -> "进行中 · ${goal.optInt("attempts")} / ${goal.optInt("maxRuns")} 次"
        "paused" -> "已暂停"
        "completed" -> "观察条件已满足"
        else -> "可选，默认沿用培养指令"
    }) {
        AssistantField("目标", text, 2000, !available, 4, "描述要完成的具体任务。", minLines = 3)
        AssistantField("核对文字（可留空）", check, 200, !available, 2, "最后一次页面观察中需出现的完整文字。")
        Note("只核对完整文字节点，不证明付款、发送或远端业务成功；留空时不会自动判定目标完成。")
        AssistantField("最多尝试次数", count, 3, !available, 1, "1～100，建议 10")
        goal?.optString("next")?.takeIf(String::isNotBlank)?.let { Note(it) }
        if (goal?.optString("reason") == "unknown_result") Note("上轮存在未知结果，核实后再继续。")
        else if (goal?.optString("reason") == "no_progress_or_budget") Note("已达到次数上限或连续三次未满足核对条件。")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(if (goal == null) "保存目标" else "建立新目标", {
                state.manage("goal", JSONObject().put("id", "current").put("mode", "save").put("text", text.text.toString())
                    .put("checkText", check.text.toString()).put("maxRuns", count.text.toString().toIntOrNull() ?: 0)
                    .put("revision", learning?.optLong("revision") ?: -1))
            }, enabled = available && text.text.isNotBlank())
            if (goal != null) {
                if (goal.optString("state") != "completed") Action(if (goal.optString("state") == "active") "暂停目标" else "继续目标", {
                    state.manage("goal", JSONObject().put("id", "current").put("mode", if (goal.optString("state") == "active") "pause" else "resume")
                        .put("revision", learning!!.getLong("revision")))
                }, enabled = available)
                Action("清除目标", {
                    state.manage("goal", JSONObject().put("id", "current").put("mode", "clear").put("revision", learning!!.getLong("revision")))
                }, enabled = available)
            }
        }
        Note("目标尝试次数跨重启保留。每轮最多 6 次模型请求、20 次工具调用；方法整理另有上限。")
    }
}

@Composable
private fun SpecimenChance(state: TextFieldState, enabled: Boolean) {
    val colors = MiuixTheme.colorScheme
    val scale = LocalDensity.current.fontScale
    var focused by remember { mutableStateOf(false) }
    val input: @Composable () -> Unit = {
        BasicTextField(state, enabled = enabled, lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(fontSize = 20.sp, color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.width(100.dp * scale.coerceAtLeast(1f)).heightIn(min = 48.dp)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "触发概率（%）" }
                .background(colors.surfaceContainerHigh, RoundedCornerShape(8.dp))
                .border(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.outline, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            decorator = TextFieldDecorator { inner ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) { inner() }
                    Text("%", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                }
            })
    }
    if (scale >= 1.3f) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("触发概率", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        input()
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("触发概率", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        input()
    }
}
