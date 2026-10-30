package local.dshx.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

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

@Composable
internal fun SpecimenScreen(
    state: SpecimenState, running: Boolean, options: Boolean, active: Boolean, back: () -> Unit,
    onOptions: () -> Unit, onSaved: () -> Unit, openSession: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val title = specimenStatus(running, state.loading, state.status)
    if (options) {
        SpecimenOptions(state, running, active, title, back, onSaved, openSession)
    } else Box(Modifier.fillMaxSize().clipToBounds().background(colors.surface)) {
        SpecimenArt(Modifier.fillMaxSize())
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryIcon(Lucide.ChevronLeft, "返回设置总览", back, iconSize = 16)
            Text("实验体", modifier = Modifier.weight(1f).semantics { heading() }, fontSize = 22.sp,
                fontWeight = FontWeight.Medium, color = colors.onSurface)
            // 复用已有圆形控件的按压反馈和焦点环。
            VoiceButton("打开培养设置", Lucide.Settings2, onOptions)
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                color = if (running && state.enabled) LocalStatusColor.current else colors.onSurface)
            if (state.error != null) Text(state.error!!, fontSize = 12.sp, lineHeight = 18.sp, color = colors.error)
            else state.message?.let { Note(it) } ?: Note(when {
                !running -> "启动工作区后可读取培养状态"
                state.loading || state.status == null -> "培养状态以实际读取结果为准"
                state.enabled -> "约每 5 分钟检查一次机会"
                state.status?.optJSONObject("settings")?.optString("prompt").isNullOrBlank() -> "培养指令待设置"
                else -> "培养指令已设置"
            })
        }
    }
}

/** 中段保持确认后的纤细比例；上下段独立延伸到边界，缩放与系统字号无关。 */
@Composable
private fun SpecimenArt(modifier: Modifier) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val top = ImageBitmap.imageResource(if (dark) R.drawable.specimen_top_dark else R.drawable.specimen_top)
    val middle = ImageBitmap.imageResource(if (dark) R.drawable.specimen_middle_dark else R.drawable.specimen_middle)
    val bottom = ImageBitmap.imageResource(if (dark) R.drawable.specimen_bottom_dark else R.drawable.specimen_bottom)
    Canvas(modifier.semantics { contentDescription = "彩铅实验体，上下细丝连接屏幕边缘" }) {
        val layoutWidth = minOf(size.width, size.height * 0.58f)
        val width = (layoutWidth * 0.60f).roundToInt().coerceAtLeast(1)
        val height = (layoutWidth * middle.height / middle.width).roundToInt().coerceAtMost(size.height.roundToInt())
        val remaining = (size.height.roundToInt() - height).coerceAtLeast(0)
        val upper = remaining * 2 / 3
        val x = ((size.width - width) / 2).roundToInt()
        if (upper > 0) drawImage(top, dstOffset = IntOffset(x, 0), dstSize = IntSize(width, upper))
        drawImage(middle, dstOffset = IntOffset(x, upper), dstSize = IntSize(width, height))
        if (remaining > upper) drawImage(bottom, dstOffset = IntOffset(x, upper + height), dstSize = IntSize(width, remaining - upper))
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
                SettingsFold("最近记录", "查看实际检查与执行结果") {
                    val recent = state.status?.optJSONArray("recent")
                    if (recent == null || recent.length() == 0) Note("暂无检查记录。")
                    else for (index in 0 until minOf(recent.length(), 8)) {
                        val row = recent.getJSONObject(index)
                        Text("${hostingTime(row.optLong("at"))} · ${hostingResult(row)}", fontSize = 13.sp, lineHeight = 21.sp,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
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
