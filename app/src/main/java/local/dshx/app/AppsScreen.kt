package local.dshx.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Trash2
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AppsSettings(running: Boolean, phoneRevision: Int, call: (String, JSONObject) -> JSONObject,
    create: (JSONObject) -> JSONObject, openSession: (String) -> Unit, permission: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var opening by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var draft by remember { mutableStateOf(JSONObject()) }
    var draftDirty by remember(selected) { mutableStateOf(false) }
    var draftRevision by remember(selected) { mutableLongStateOf(-1L) }
    var events by remember { mutableStateOf<JSONArray?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var packing by remember { mutableStateOf(false) }
    var pendingApk by rememberSaveable { mutableStateOf<String?>(null) }
    val host = (context.applicationContext as DshApp).appHost
    LaunchedEffect(host) { host.start() }
    val prompt = rememberTextFieldState()
    val query = rememberTextFieldState()
    var createId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var createText by rememberSaveable { mutableStateOf("") }
    var createSession by rememberSaveable { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        status = runInterruptible(Dispatchers.IO) { call("status", JSONObject()) }
        if (selected != null) events = runInterruptible(Dispatchers.IO) { call("logs", JSONObject().put("id", selected)) }.getJSONArray("events")
    }
    fun work(action: suspend () -> Unit) {
        if (loading) return
        loading = true; failure = null; message = null
        scope.launch {
            try { action() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { failure = error.message?.take(220) ?: "操作未确认，请刷新核实。" }
            finally { loading = false }
        }
    }
    val saveApk = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        val file = pendingApk?.let(::File); pendingApk = null
        if (uri == null) {
            file?.parentFile?.deleteRecursively()
            message = "已取消保存 APK。"
        } else if (file != null) work {
            try {
                runInterruptible(Dispatchers.IO) {
                    require(file.isFile) { "临时 APK 已失效，请重新打包。" }
                    file.inputStream().use { input -> (context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("无法写入所选位置。")).use { input.copyTo(it) } }
                }
                message = "APK 已保存，可在其他安卓手机上独立安装。"
            } catch (error: Exception) {
                runInterruptible(Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                throw error
            } finally { file.parentFile?.deleteRecursively() }
        }
    }
    fun exportApk(id: String) = work {
        packing = true
        try {
            val result = runInterruptible(Dispatchers.IO) { AppApk(context.applicationContext, (context.applicationContext as DshApp).apps).build(id) }
            pendingApk = result.file.absolutePath
            try { saveApk.launch(result.name) }
            catch (error: Exception) { result.file.parentFile?.deleteRecursively(); pendingApk = null; throw error }
        } finally { packing = false }
    }
    fun mutate(action: String, data: JSONObject) = work {
        if (action == "enable") {
            val list = status!!.getJSONArray("apps")
            val current = (0 until list.length()).map { list.getJSONObject(it) }.first { it.getString("id") == data.getString("id") }
            require(draftRevision == current.getLong("revision")) { "应用设置已变化，请重新读取设置后再保存。" }
        }
        val request = JSONObject(data.toString()).put("operationId", UUID.randomUUID().toString()).put("expectedRevision", status!!.getLong("revision"))
        runInterruptible(Dispatchers.IO) { call(action, request) }
        if (action == "enable") draftDirty = false
        message = when (action) { "enable" -> "已保存启用设置，运行状态见下方。"; "disable" -> "已停用。"; "remove" -> "已移除。"; else -> "已保存。" }
        if (action == "remove") { selected = null; deleting = false }
        refresh()
    }
    DisposableEffect(host) {
        val listener: () -> Unit = { scope.launch { runCatching { refresh() } } }
        host.observe(listener)
        onDispose { host.remove(listener) }
    }
    LaunchedEffect(phoneRevision) {
        try { refresh() } catch (error: Exception) { failure = error.message ?: "无法读取微应用状态。" }
    }
    val rows = status?.optJSONArray("apps")?.let { list -> (0 until list.length()).map { list.getJSONObject(it) } }.orEmpty()
    val app = rows.find { it.optString("id") == selected }
    val colors = MiuixTheme.colorScheme
    LaunchedEffect(selected, app?.optString("version"), app?.optLong("revision"), draftDirty) {
        if (!draftDirty) {
            draft = app?.optJSONObject("settings")?.let { JSONObject(it.toString()) } ?: JSONObject()
            draftRevision = app?.optLong("revision") ?: -1L
        }
    }
    LaunchedEffect(selected, app?.optString("version"), status?.optLong("revision")) {
        events = null
        if (selected != null) try {
            events = runInterruptible(Dispatchers.IO) { call("logs", JSONObject().put("id", selected)) }.getJSONArray("events")
        } catch (error: Exception) { failure = error.message }
    }
    LaunchedEffect(picker) {
        if (picker != null) available = runInterruptible(Dispatchers.IO) {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo?.enabled == true && it.activityInfo.applicationInfo.enabled }
                .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
                .distinctBy { it.first }.sortedBy { it.second }
        }
    }

    Row(Modifier.fillMaxWidth().offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
        LibraryIcon(Lucide.ChevronLeft, "返回设置总览", back, iconSize = 16)
        Text("微应用", modifier = Modifier.weight(1f).semantics { heading() }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        LibraryIcon(Lucide.RotateCcw, if (loading) "正在刷新微应用" else "刷新微应用", { work { refresh() } }, enabled = !loading)
    }
    if (app == null) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("我的微应用", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                color = colors.onSurfaceVariantSummary, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.weight(1f))
            if (status != null) Note("${rows.size} 个应用")
        }
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minimum = when { fontScale >= 1.8f -> 144.dp; fontScale >= 1.5f -> 104.dp; else -> 76.dp }
            val columns = ((maxWidth + 8.dp) / (minimum + 8.dp)).toInt().coerceIn(1, 6)
            val width = (maxWidth - 8.dp * (columns - 1)) / columns
            // 页面外层已经负责纵向滚动，网格只负责排布，避免两层滚动互相争抢。
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                maxItemsInEachRow = columns) {
                rows.forEach { row -> key(row.getString("id")) {
                    AppTile(row.optString("name"), Modifier.width(width), state = appState(row, status),
                        open = { selected = row.getString("id") }) {
                        AppSymbol(row.getString("id"), size = 68.dp)
                    }
                } }
                AppTile("添加应用", Modifier.width(width), enabled = !loading, open = { adding = true }) {
                    Box(Modifier.size(68.dp).drawBehind {
                        drawRoundRect(colors.onSurfaceVariantSummary.copy(alpha = .3f), cornerRadius = CornerRadius(18.dp.toPx()),
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))))
                    }, contentAlignment = Alignment.Center) {
                        Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(24.dp), tint = colors.onSurfaceVariantSummary)
                    }
                }
            }
        }
        if (status == null) Note(if (failure == null) "正在读取微应用…" else "请点击刷新重试。")
        else if (rows.isEmpty()) Note("点击添加应用，让 dsh 创建功能或安装示例。")
        else Text("点击应用查看配置与运行记录", fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp), textAlign = TextAlign.Center)
    } else {
        Action("返回列表", { selected = null; picker = null }, enabled = !loading, icon = Lucide.ArrowLeft)
        Panel(padding = 0) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppSymbol(app.optString("id"))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(app.optString("name"), fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { heading() })
                        Note("版本 ${app.optString("version")}")
                    }
                }
                Text(app.optString("description"), fontSize = 14.sp, lineHeight = 22.sp, color = colors.onSurfaceVariantSummary)
                AppStatus(appState(app, status))
                app.optString("error").takeIf(String::isNotBlank)?.let { AppNotice(it, error = true) }
                status?.optJSONObject("runtime")?.optJSONObject(selected)?.takeUnless { it.isNull("error") }?.optString("error")?.takeIf(String::isNotBlank)?.let { AppNotice(it, error = true) }
            }
            Divider()
            val enabled = app.optBoolean("enabled")
            SettingsSwitch("启用", "在本机运行，可随时关闭。", enabled, !loading && !app.has("error")) { on ->
                mutate(if (on) "enable" else "disable", JSONObject().put("id", selected).apply { if (on) put("settings", draft) })
            }
            if (app.optJSONObject("package")?.getJSONArray("capabilities")?.let { values -> (0 until values.length()).any { values.getString(it).startsWith("screen.") } } == true && status?.optJSONObject("phone")?.optBoolean("accessibility") != true) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { Action("开启无障碍服务", { permission("accessibility") }) }
            }
        }
        val packageInfo = app.optJSONObject("package")
        val capabilities = packageInfo?.optJSONArray("capabilities")?.let { values -> (0 until values.length()).map { values.getString(it) } }.orEmpty()
        Panel { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("应用能力", fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                capabilities.map { when (it) { "storage" -> "独立存储"; "screen.observe" -> "读取选定应用界面"; "screen.click" -> "点击选定应用控件"; else -> it } }
                    .ifEmpty { listOf("页面与本地计算") }.forEach { title ->
                        Text(title, fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariantSummary,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.secondary).padding(horizontal = 8.dp, vertical = 5.dp))
                    }
            }
            if (packageInfo?.has("background") == true) Note("启用后运行后台入口，关闭页面仍可处理事件。")
            packageInfo?.optJSONArray("network")?.takeIf { it.length() > 0 }?.let { Note("允许联网：" + (0 until it.length()).joinToString("、") { index -> it.getString(index) }) }
            Action("打开应用", { opening = app.getString("id") }, modifier = Modifier.fillMaxWidth(),
                enabled = app.optBoolean("enabled") && !loading && !app.has("error"), primary = true, icon = Lucide.Play)
            if (!app.optBoolean("enabled")) Note("启用后即可打开。")
            Action(if (packing) "正在打包…" else "打包 APK", { exportApk(app.getString("id")) },
                modifier = Modifier.fillMaxWidth(), enabled = !loading && pendingApk == null && !app.has("error"), icon = Lucide.Package)
            Note("导出后可独立安装，不包含已有使用数据。")
            if (packageInfo?.has("background") == true) Action("重启后台入口", { host.retry(app.getString("id")); message = "已请求重新启动后台入口。" }, enabled = app.optBoolean("enabled") && !loading, icon = Lucide.RotateCcw)
        } }
        val definitions = app.optJSONObject("package")?.optJSONArray("settings")
        if (definitions != null && definitions.length() > 0) {
            if (draftDirty && draftRevision != app.getLong("revision")) {
                AppNotice("应用设置已变化，尚未保存的输入已保留。")
                Action("重新读取设置", { draftDirty = false })
            }
            Section("功能设置")
            Panel(padding = 0) {
                for (index in 0 until definitions.length()) {
                    val field = definitions.getJSONObject(index); val key = field.getString("id")
                    if (index > 0) Divider()
                    when (field.getString("type")) {
                        "app-list" -> LinkRow(field.getString("label"), "已选择 ${draft.optJSONArray(key)?.length() ?: 0} 个应用") { picker = key }
                        "switch" -> SettingsSwitch(field.getString("label"), "", draft.optBoolean(key), !loading) { on -> draftDirty = true; draft = JSONObject(draft.toString()).put(key, on) }
                        "text", "number" -> AppSettingField(field, draft, !loading) { value -> draftDirty = true; draft = JSONObject(draft.toString()).put(key, value) }
                    }
                }
                Column(Modifier.padding(16.dp)) {
                    Action("保存并启用", { mutate("enable", JSONObject().put("id", selected).put("settings", draft)) }, enabled = !loading, primary = true, icon = Lucide.Save)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("让 dsh 修改", { prompt.setTextAndPlaceCursorAtEnd("修改微应用 ${app.getString("id")}："); createSession = null; createId = UUID.randomUUID().toString(); adding = true }, enabled = running && !loading, icon = Lucide.Pencil)
            Action("刷新记录", { work { refresh() } }, enabled = !loading, icon = Lucide.RotateCcw)
            Action("删除", { deleting = true }, enabled = !loading, danger = true, icon = Lucide.Trash2)
        }
        Section("最近结果")
        Panel { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val recent = events
            if (recent == null || recent.length() == 0) Note("暂无运行记录。启用后，有页面和后台入口的应用按各自逻辑运行。")
            else for (index in 0 until minOf(recent.length(), 12)) {
                if (index > 0) Divider()
                val event = recent.getJSONObject(index)
                Text(appEvent(event), fontSize = 13.sp, lineHeight = 20.sp)
            }
        } }
    }
    message?.let { AppNotice(it) }; failure?.let { AppNotice(it, error = true) }
    status?.optString("runtimeError")?.takeIf(String::isNotBlank)?.let { AppNotice(it, error = true) }

    opening?.let { id -> MicroAppDialog(id, host, exporting = packing,
        export = if (!loading && pendingApk == null) ({ exportApk(id) }) else null,
        exportMessage = failure ?: message, exportFailed = failure != null) { opening = null } }

    if (adding) MemoryDialog("添加微应用", { if (!loading) adding = false }) {
        AssistantField("功能需求", prompt, 4000, loading, 5, "例如：为选定应用添加开屏广告跳过功能")
        if (!running) Note("启动 dsh 后可按需求创建；示例可以直接添加。")
        failure?.let { AppNotice(it, error = true) }
        Action(if (loading) "正在处理…" else "让 dsh 创建", {
            work {
                val text = prompt.text.toString().trim()
                require(text.isNotEmpty()) { "请填写功能需求。" }
                if (text != createText) { createId = UUID.randomUUID().toString(); createSession = null; createText = text }
                val result = runInterruptible(Dispatchers.IO) {
                    create(JSONObject().put("operationId", createId).put("prompt", text).apply { createSession?.let { put("session", it) } })
                }
                createSession = result.getString("session"); adding = false
                openSession(result.getString("session"))
            }
        }, enabled = running && !loading, primary = true, icon = Lucide.Plus)
        Divider()
        Section("从示例开始")
        Action("添加便签示例", {
            work {
                runInterruptible(Dispatchers.IO) {
                    val value = context.assets.open("skills/create-dshx-app/assets/quick-notes.json").bufferedReader().use { JSONObject(it.readText()) }
                    val check = call("check", JSONObject().put("package", value))
                    call("install", JSONObject().put("checkId", check.getString("checkId")).put("hash", check.getString("hash"))
                        .put("operationId", UUID.randomUUID().toString()).put("expectedRevision", call("status", JSONObject()).getLong("revision")))
                }
                adding = false; refresh(); selected = "quick-notes"
                message = "便签已添加，启用后可打开使用。"
            }
        }, enabled = !loading && rows.none { it.optString("id") == "quick-notes" }, modifier = Modifier.fillMaxWidth())
        Action("添加广告跳过示例", {
            work {
                val result = runInterruptible(Dispatchers.IO) {
                    val value = context.assets.open("skills/create-dshx-app/assets/skip-startup-ads.json").bufferedReader().use { JSONObject(it.readText()) }
                    val check = call("check", JSONObject().put("package", value))
                    call("install", JSONObject().put("checkId", check.getString("checkId")).put("hash", check.getString("hash"))
                        .put("operationId", UUID.randomUUID().toString()).put("expectedRevision", call("status", JSONObject()).getLong("revision")))
                }
                require(result.optBoolean("known"))
                adding = false; refresh(); selected = "skip-startup-ads"
                message = "示例已添加，请查看能力并启用。"
            }
        }, enabled = !loading && rows.none { it.optString("id") == "skip-startup-ads" }, modifier = Modifier.fillMaxWidth())
    }
    if (picker != null) MemoryDialog("选择适用应用", { picker = null }) {
        AssistantField("搜索应用", query, 200, false, 1)
        val field = picker!!
        val selectedApps = draft.optJSONArray(field)?.let { values -> (0 until values.length()).map { values.getString(it) }.toSet() }.orEmpty()
        val filtered = available.filter { query.text.isBlank() || it.first.contains(query.text, true) || it.second.contains(query.text, true) }
        if (filtered.isEmpty()) Note("没有匹配的可见应用。")
        filtered.forEach { (name, label) -> SettingsSwitch(label, name, name in selectedApps, !loading) { on ->
            val next = if (on) selectedApps + name else selectedApps - name
            draftDirty = true
            draft = JSONObject(draft.toString()).put(field, JSONArray(next.sorted()))
        } }
        Action("完成", { picker = null })
    }
    if (deleting && app != null) MemoryDialog("删除微应用", { if (!loading) deleting = false }) {
        Note("停止并移除该功能，保留已安装版本和运行记录。")
        Action("删除", { mutate("remove", JSONObject().put("id", selected).put("deleteData", false)) }, enabled = !loading, danger = true)
    }
}

@Composable
private fun AppTile(name: String, modifier: Modifier = Modifier, state: String? = null, enabled: Boolean = true,
    open: () -> Unit, symbol: @Composable () -> Unit) {
    val colors = MiuixTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Column(modifier.heightIn(min = 132.dp).clip(RoundedCornerShape(10.dp)).onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused && enabled) colors.primary else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = open).padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        symbol()
        Text(name, fontSize = 13.sp, lineHeight = 20.sp, color = if (enabled && state != null) colors.onSurface else colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        state?.let { AppStatus(it) }
    }
}

@Composable
private fun AppStatus(label: String) {
    val colors = MiuixTheme.colorScheme
    val active = label == "已启用" || label == "正在监听"
    val error = label == "应用包需要修复" || label == "运行失败"
    val tint = when { error -> colors.error; active -> LocalStatusColor.current; else -> colors.onSurfaceVariantSummary }
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(when { error -> Lucide.CircleAlert; active -> Lucide.Check; label == "已停用" -> Lucide.Square; else -> Lucide.Clock },
            contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
        Text(label, fontSize = 12.sp, lineHeight = 18.sp, color = tint)
    }
}

internal fun appState(app: JSONObject, status: JSONObject?): String = when {
    app.has("error") -> "应用包需要修复"
    status?.optJSONObject("runtime")?.optJSONObject(app.optString("id"))?.let { !it.isNull("error") } == true -> "运行失败"
    !app.optBoolean("enabled") -> "已停用"
    !status?.optString("runtimeError").isNullOrBlank() -> "文件发生变化，已暂停"
    app.optJSONObject("package")?.has("background") == true && status?.optJSONObject("runtime")?.optJSONObject(app.optString("id"))?.optBoolean("background") != true -> "后台启动中"
    app.optJSONObject("package")?.getJSONArray("capabilities")?.let { values -> (0 until values.length()).any { values.getString(it).startsWith("screen.") } } != true -> "已启用"
    status?.optJSONObject("phone")?.optBoolean("accessibility") != true -> "等待无障碍权限"
    status?.optJSONObject("phone")?.optBoolean("ready") != true -> "暂时无法操作手机"
    else -> "正在监听"
}

internal fun appEvent(event: JSONObject): String {
    val result = when (event.optString("outcome")) {
        "confirmed" -> "已确认完成"; "completed" -> "点击已返回，结果未确认"; "unknown" -> "结果待确认，未重放"
        "failed" -> "执行失败"; else -> "本次未执行"
    }
    val reason = when (event.optString("reason")) {
        "multiple-targets" -> "候选不唯一"; "observation-incomplete" -> "观察不完整"; "no-match" -> "未找到符合条件的入口"
        "phone-task", "busy" -> "手机任务优先"; "settings-changed" -> "设置已变化"; "package-changed" -> "包文件发生变化"
        "disabled" -> "手机操作已关闭"; "locked" -> "设备锁屏"; "screen_off" -> "设备息屏"; "cooldown" -> "暂不重复操作"
        "not-confirmed" -> "未取得成功证据"; "app-changed" -> "已离开目标应用"; else -> ""
    }
    return if (reason.isBlank()) result else "$result · $reason"
}
