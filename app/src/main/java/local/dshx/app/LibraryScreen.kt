package local.dshx.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.ChevronRight

@Composable
internal fun LibraryScreen(
    modifier: Modifier, close: () -> Unit, running: Boolean, starting: Boolean, start: () -> Unit,
    memory: MemoryStore, onMemory: (String, JSONObject) -> JSONObject,
    knowledge: KnowledgeStore, transfer: KnowledgeStore.Transfer, picking: Boolean,
    onKnowledge: (String, JSONObject) -> JSONObject, onKnowledgeFile: (String, JSONObject) -> Unit,
    store: LibraryStore, revision: Int, busy: Boolean, message: String?, file: (String, String?) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf("documents") }
    val pages = rememberSaveableStateHolder()
    val colors = MiuixTheme.colorScheme
    Column(modifier.background(colors.surface).safeDrawingPadding().imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.widthIn(max = 680.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LibraryIcon(Lucide.ArrowLeft, "返回", close)
            Text("知识库", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Row(Modifier.widthIn(max = 680.dp).fillMaxWidth().selectableGroup()) {
            listOf("documents" to "资料", "memory" to "记忆", "files" to "文件", "skills" to "技能").forEach { (id, title) ->
                val selected = tab == id
                Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .background(if (selected) colors.secondary else Color.Transparent)
                    .selectable(selected, role = Role.Tab, onClick = { tab = id })
                    .heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) colors.onSurface else colors.onSurfaceVariantSummary)
                    Box(Modifier.width(16.dp).height(2.dp).background(if (selected) colors.primary else Color.Transparent))
                }
            }
        }
        Divider()
        if (!running && tab in setOf("documents", "memory")) {
            Row(Modifier.widthIn(max = 680.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.secondary).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (starting) "正在启动工作区…" else "启动工作区后可管理${if (tab == "memory") "记忆" else "资料"}",
                    fontSize = 13.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                Action("启动", onClick = start, enabled = !starting)
            }
        }
        Box(Modifier.widthIn(max = 680.dp).fillMaxWidth().weight(1f)) {
            pages.SaveableStateProvider(tab) {
                when (tab) {
                    "documents" -> KnowledgeSettings(knowledge, running, true, transfer, picking, onKnowledge, onKnowledgeFile)
                    "memory" -> MemorySettings(memory, running, true, onMemory)
                    else -> LibraryFiles(store, tab == "skills", true, revision, busy, message, file, running, transfer, onKnowledge, onKnowledgeFile)
                }
            }
        }
    }
}

@Composable
internal fun LibraryIcon(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = MiuixTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.size(48.dp).onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(8.dp))
        .border(if (focused) 2.dp else 0.dp, if (focused) colors.primary else Color.Transparent, RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp),
            tint = if (enabled) colors.onSurface else colors.onSurfaceVariantSummary)
    }
}

@Composable
internal fun LibrarySearch(state: TextFieldState, label: String, submit: (() -> Unit)? = null) {
    val colors = MiuixTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerHigh)
        .border(1.dp, if (focused) colors.primary else colors.outline, RoundedCornerShape(8.dp))
        .heightIn(min = 48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.onSurfaceVariantSummary)
        BasicTextField(state, modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label }.padding(horizontal = 10.dp, vertical = 12.dp),
            lineLimits = TextFieldLineLimits.SingleLine, cursorBrush = SolidColor(colors.primary),
            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = colors.onSurface),
            onKeyboardAction = { submit?.invoke() },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            decorator = { inner -> Box { if (state.text.isBlank()) Text(label, fontSize = 14.sp, color = colors.onSurfaceVariantSummary); inner() } })
        if (submit != null) LibraryIcon(Lucide.ArrowRight, "搜索", submit, state.text.isNotBlank())
        else if (state.text.isNotBlank()) LibraryIcon(Lucide.X, "清除筛选", { state.setTextAndPlaceCursorAtEnd("") })
        else Spacer(Modifier.width(12.dp))
    }
}

@Composable
internal fun LibraryEmpty(icon: ImageVector, title: String, summary: String, action: (@Composable () -> Unit)? = null) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(colors.secondary), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = colors.onSurfaceVariantSummary)
        }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(summary, fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurfaceVariantSummary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

@Composable
internal fun LibraryBar(title: String, summary: String?, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            summary?.let { Note(it) }
        }
        actions()
    }
}

@Composable
internal fun LibraryFilters(options: List<Pair<String, String>>, selected: String, select: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (id, title) ->
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (selected == id) colors.secondary else Color.Transparent)
                .selectable(id == selected, role = Role.RadioButton, onClick = { select(id) })
                .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(title, fontSize = 13.sp, color = if (selected == id) colors.onSurface else colors.onSurfaceVariantSummary,
                    fontWeight = if (selected == id) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

@Composable
internal fun LibraryRow(icon: ImageVector, title: String, summary: String, detail: String? = null, enabled: Boolean = true, open: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = open)
        .heightIn(min = 76.dp).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(colors.secondary), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.onSurfaceVariantSummary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
            Note(summary)
            detail?.let { Note(it) }
        }
        Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariantSummary)
    }
}

@Composable
private fun LibraryFiles(
    store: LibraryStore, skills: Boolean, visible: Boolean, revision: Int, busy: Boolean, message: String?,
    file: (String, String?) -> Unit, running: Boolean, transfer: KnowledgeStore.Transfer,
    call: (String, JSONObject) -> JSONObject, knowledgeFile: (String, JSONObject) -> Unit,
) {
    var rows by remember { mutableStateOf<List<LibraryStore.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<LibraryStore.Entry?>(null) }
    var current by remember { mutableStateOf<LibraryStore.Skill?>(null) }
    var editing by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var collections by remember { mutableStateOf<List<KnowledgeStore.Collection>>(emptyList()) }
    var collection by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    val query = rememberTextFieldState()
    val name = rememberTextFieldState()
    val description = rememberTextFieldState()
    val body = rememberTextFieldState()
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        rows = runInterruptible(Dispatchers.IO) { if (skills) store.skills() else store.files() }
    }
    LaunchedEffect(visible, revision) {
        if (visible) {
            loading = true; failure = null
            try { refresh() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { rows = emptyList(); failure = error.message }
            finally { loading = false }
        }
    }
    fun open(entry: LibraryStore.Entry) {
        if (loading || busy) return
        loading = true; failure = null; preview = null
        scope.launch {
            try {
                if (skills) {
                    val value = runInterruptible(Dispatchers.IO) { store.readSkill(entry) }
                    current = value; name.setTextAndPlaceCursorAtEnd(entry.name); body.setTextAndPlaceCursorAtEnd(value.text)
                    editing = true
                } else {
                    if (entry.name.substringAfterLast('.').lowercase() in setOf("txt", "md", "markdown", "json", "csv", "log")) {
                        preview = runInterruptible(Dispatchers.IO) {
                            store.file(entry.id).reader().use { reader ->
                                val buffer = CharArray(8000)
                                val count = reader.read(buffer)
                                if (count < 0) "空文件" else String(buffer, 0, count) + if (reader.read() >= 0) "\n…仅预览前 8000 字" else ""
                            }
                        }
                    }
                    selected = entry
                    if (running && entry.name.substringAfterLast('.').lowercase() in setOf("txt", "md", "markdown")) {
                        collections = runInterruptible(Dispatchers.IO) { KnowledgeStore.loadCatalog(call).collections }
                        if (collections.none { it.id == collection }) collection = collections.firstOrNull()?.id.orEmpty()
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { failure = error.message }
            finally { loading = false }
        }
    }
    val available = !loading && !busy
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LibraryBar(if (skills) "我的技能" else "全部文件", "${rows.size} ${if (skills) "个技能" else "个文件"}") {
            if (skills) Action("新建", onClick = {
                current = null; name.setTextAndPlaceCursorAtEnd(""); description.setTextAndPlaceCursorAtEnd("")
                body.setTextAndPlaceCursorAtEnd(""); editing = true; failure = null
            }, enabled = available, primary = true, icon = Lucide.Plus)
            else Action("上传", onClick = { file("upload", null) }, enabled = available, primary = true, icon = Lucide.Plus)
            LibraryIcon(Lucide.RotateCcw, "刷新列表", {
                loading = true
                scope.launch { try { refresh(); failure = null } catch (error: Exception) { failure = error.message } finally { loading = false } }
            }, available)
        }
        LibrarySearch(query, if (skills) "搜索技能名称" else "搜索文件名称")
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (loading || busy) Note("正在处理…")
                failure?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 13.sp) }
                notice?.let { Note(it) }
                message?.let { Note(it) }
                if (!skills && transfer.message.isNotBlank()) Note(transfer.message)
            }
            val filtered = rows.filter { query.text.isBlank() || it.name.contains(query.text, true) || it.source.contains(query.text, true) }
            if (filtered.isEmpty() && !loading && failure == null) item {
                LibraryEmpty(if (skills) Lucide.Sparkles else Lucide.FileText,
                    if (query.text.isNotBlank()) "没有匹配结果" else if (skills) "把常用流程变成技能" else "文件集中收好，随时取用",
                    if (query.text.isNotBlank()) "换个关键词试试。" else if (skills) "写下操作步骤，让助手在需要时重复使用。" else "上传原件统一保存，文本文件还可加入资料。")
            }
            items(filtered.size, key = { filtered[it].id }) { index ->
                val entry = filtered[index]
                LibraryRow(if (skills) Lucide.Sparkles else Lucide.FileText, entry.name,
                    "${entry.source} · ${fileSize(entry.bytes)}", enabled = available) { open(entry) }
                Divider()
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (editing && visible) MemoryDialog(if (current == null) "新建技能" else "编辑技能", { if (!loading) editing = false }) {
        failure?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 13.sp) }
        if (current == null) {
            AssistantField("技能名称", name, 100, loading, 1, "小写英文、数字和短横线，例如 meeting-notes")
            AssistantField("用途", description, 1000, loading, 3, "说明何时使用这个技能")
        } else Note("保留文件顶部的 name 和 description；可继续引用同目录下的资源。")
        AssistantField(if (current == null) "操作指令" else "技能文件", body, LibraryStore.MAX_SKILL, loading, 12,
            "写明适用场景、执行步骤和输出要求")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("保存技能", onClick = {
                loading = true; failure = null
                scope.launch {
                    try {
                        val text = if (current == null) "---\nname: ${name.text.toString().trim()}\ndescription: ${JSONObject.quote(description.text.toString().trim())}\n---\n\n${body.text}" else body.text.toString()
                        require(current != null || description.text.isNotBlank()) { "请填写技能用途。" }
                        runInterruptible(Dispatchers.IO) { store.saveSkill(name.text.toString().trim(), text, current) }
                        refresh(); editing = false; notice = "技能已保存。"
                    } catch (error: Exception) { failure = error.message }
                    finally { loading = false }
                }
            }, enabled = available && body.text.isNotBlank(), primary = true)
            if (current != null) Action("删除技能", onClick = { remove = true }, enabled = available, danger = true)
        }
    }
    if (selected != null && visible) MemoryDialog("文件详情", { if (!loading) selected = null }) {
        val entry = selected!!
        Text(entry.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Note("${entry.source} · ${fileSize(entry.bytes)}")
        message?.let { Note(it) }
        failure?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 13.sp) }
        if (preview != null) SelectionContainer { Text(preview!!, fontSize = 14.sp, lineHeight = 22.sp) }
        Action("导出原件", onClick = { file("export", entry.id) }, enabled = available)
        val textFile = entry.name.substringAfterLast('.').lowercase() in setOf("txt", "md", "markdown")
        if (textFile) {
            MemoryChoice("加入集合", collections.map { it.id to it.title }, collection, available && running) { collection = it }
            Action("加入资料", onClick = {
                knowledgeFile("library", JSONObject().put("fileId", entry.id).put("collectionId", collection))
            }, enabled = available && running && collection.isNotBlank() && entry.bytes <= KnowledgeStore.MAX_SOURCE && !transfer.busy, primary = true)
            if (!running) Note("启动工作区后可加入资料。")
            else if (collections.isEmpty()) Note("请先在资料标签的管理中创建集合。")
            if (entry.bytes > KnowledgeStore.MAX_SOURCE) Note("资料单份最多 2 MiB，请先拆分文件。")
            if (transfer.message.isNotBlank()) Note(transfer.message)
        }
        if (entry.removable) Action("删除文件", onClick = { remove = true }, enabled = available, danger = true)
        else Note("聊天附件保留在历史会话中，可导出原件。")
    }
    if (remove && visible) MemoryDialog(if (skills) "删除技能" else "删除文件", { remove = false }) {
        Note(if (skills) "删除技能指令文件，同目录的辅助资源会保留。此操作无法撤销。" else "删除上传原件。已加入资料的独立副本会保留。此操作无法撤销。")
        failure?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 13.sp) }
        Action("确认删除", onClick = {
            loading = true; failure = null
            scope.launch {
                try {
                    val entry = if (skills) current!!.entry else selected!!
                    runInterruptible(Dispatchers.IO) { store.remove(entry, if (skills) current else null) }
                    refresh(); remove = false; editing = false; selected = null; notice = "已删除。"
                } catch (error: Exception) { failure = error.message }
                finally { loading = false }
            }
        }, enabled = available, danger = true)
    }
}

internal fun fileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1024.0 / 1024)
    bytes >= 1024 -> String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes 字节"
}
