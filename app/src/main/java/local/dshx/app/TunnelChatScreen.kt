package local.dshx.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Compass
import com.composables.icons.lucide.ImagePlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.UserRound
import com.composables.icons.lucide.X
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TunnelChatScreen(
    runtime: TunnelChatRuntime, runtimeState: TunnelChatRuntimeState, visible: Boolean, onSettings: () -> Unit,
) {
    val settings = runtime.settings()
    var discover by rememberSaveable { mutableStateOf(false) }
    var selectedConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    val drafts = rememberSaveableStateHolder()
    val listState = key(settings.platformOrigin, settings.endpointId) { rememberLazyListState() }
    val conversations = runtimeState.conversations
    val selected = conversations.firstOrNull { it.conversationId == selectedConversationId }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = if (maxWidth >= 600.dp) 32.dp else 16.dp
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 680.dp).fillMaxSize().padding(horizontal = gutter)) {
            TunnelChatHeader(runtimeState, onSettings)
            Row(Modifier.fillMaxWidth().selectableGroup().clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.secondary).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val inboxLabel = if (conversations.isEmpty()) "会话" else "会话 ${conversations.size}"
                listOf(Triple(false, inboxLabel, Lucide.MessagesSquare), Triple(true, "发现", Lucide.Compass)).forEach { (value, label, icon) ->
                    val active = discover == value
                    val colors = MiuixTheme.colorScheme
                    Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (active) colors.surfaceVariant else Color.Transparent)
                        .selectable(active, role = Role.Tab) { discover = value },
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariantSummary)
                        Text(label, fontSize = 14.sp, color = colors.onSurface, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
            if (!discover && selected == null && conversations.isNotEmpty()) {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                    contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(conversations, key = { it.conversationId }) { conversation ->
                        TunnelConversationRow(conversation, zone, today) { selectedConversationId = conversation.conversationId }
                    }
                }
            } else {
                key(discover, settings.platformOrigin, selected?.conversationId.takeUnless { discover }) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (discover) {
                            TunnelDirectory(runtime, settings, visible, onSettings) { conversationId ->
                                selectedConversationId = conversationId
                                discover = false
                            }
                        } else if (selected != null) {
                            Action("所有会话", { selectedConversationId = null })
                            Panel {
                                Text("${selected.peerLabel} · ${selected.remoteEndpointId}", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Note(listOfNotNull(selected.connectionLabel, selected.receptionLabel, selected.preview()?.text).joinToString(" · "))
                                Spacer(Modifier.height(12.dp))
                                drafts.SaveableStateProvider(selected.conversationId) { TunnelConversationPanel(runtime, selected) }
                            }
                        } else {
                            LibraryEmpty(Lucide.MessagesSquare, "暂无会话",
                                if (settings.enabled) "从端点目录发起聊天，或等待访客联系。" else "穿透聊天尚未开启。") {
                                if (settings.enabled) Action("发现端点", { discover = true }, icon = Lucide.Compass)
                                else Action("打开聊天设置", onSettings, primary = true, icon = Lucide.Settings2)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TunnelChatHeader(state: TunnelChatRuntimeState, onSettings: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val label = when (state.status) {
        Status.DISABLED -> "未开启"
        Status.STARTING -> "正在启动"
        Status.ONLINE -> "信令在线"
        Status.OFFLINE -> "信令离线"
        Status.FAILED -> "信令异常"
    }
    val tint = when (state.status) {
        Status.ONLINE -> LocalStatusColor.current
        Status.STARTING -> colors.primary
        Status.FAILED -> colors.error
        else -> colors.onSurfaceVariantSummary
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("聊天", fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Box(Modifier.weight(1f)) {
                Row(Modifier.background(colors.secondary, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    TunnelStatus(label, tint)
                }
            }
            VoiceButton("聊天设置", Lucide.Settings2, onSettings, iconSize = 18)
        }
        if (state.status != Status.DISABLED) Note(state.message)
    }
}

@Composable
private fun TunnelDirectory(
    runtime: TunnelChatRuntime, settings: TunnelChatSettings, visible: Boolean, onSettings: () -> Unit, onOpen: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val query = rememberTextFieldState()
    var submittedQuery by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf<TunnelDirectoryPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }

    fun load(cursor: String? = null) {
        if (loading) return
        val filter = if (cursor == null) query.text.toString().trim().take(80) else submittedQuery
        loading = true
        error = null
        scope.launch {
            try {
                val next = withContext(Dispatchers.IO) {
                    runtime.listPublicEndpoints(settings.platformOrigin, filter.ifBlank { null }, cursor)
                }
                directory = if (cursor == null) next else
                    next.copy(items = (directory?.items.orEmpty() + next.items).distinctBy { it.endpointId })
                submittedQuery = filter
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "公共端点目录暂时不可用。"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(visible) { if (visible && directory == null) load() }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            LibrarySearch(query, "搜索公开端点", submit = { load() })
        }
        LibraryIcon(Lucide.RotateCcw, "刷新端点目录", { load() }, enabled = !loading)
    }
    if (!settings.enabled) Panel(padding = 0) {
        LinkRow("穿透聊天未开启", "开启后可联系公开端点", onSettings)
    }
    error?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
    val items = directory?.items.orEmpty()
    if (items.isEmpty()) {
        if (loading) Note("正在读取端点目录…")
        else if (error == null) LibraryEmpty(Lucide.Compass,
            if (submittedQuery.isBlank()) "暂无公开端点" else "没有匹配的端点",
            if (submittedQuery.isBlank()) "隐身端点不会出现在目录中。" else "试试其他名称或身份介绍。")
    } else {
        Panel {
            items.forEachIndexed { index, endpoint ->
                if (index > 0) Divider()
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isSelf = endpoint.endpointId == settings.endpointId
                    Text(endpoint.displayName.ifBlank { endpoint.endpointId }, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Note(when {
                        isSelf -> "本端"
                        endpoint.acceptance == TunnelAcceptance.PAUSED -> "暂停新聊天"
                        endpoint.online -> "在线"
                        else -> "离线"
                    })
                    if (endpoint.publicIntroduction.isNotBlank()) Note(endpoint.publicIntroduction)
                    if (!isSelf) Action(if (opening == endpoint.endpointId) "正在发起…" else "发起聊天", {
                        if (opening != null) return@Action
                        opening = endpoint.endpointId
                        error = null
                        scope.launch {
                            try {
                                val id = withContext(Dispatchers.IO) { runtime.startConversation(endpoint, settings.platformOrigin) }
                                onOpen(id)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "聊天连接未能发起。"
                            } finally {
                                opening = null
                            }
                        }
                    }, enabled = opening == null && endpoint.acceptance == TunnelAcceptance.ACCEPTING && settings.enabled,
                        icon = Lucide.Send)
                }
            }
        }
    }
    directory?.nextCursor?.let { cursor -> Action("加载更多", { load(cursor) }, enabled = !loading) }
}

@Composable
private fun TunnelConversationRow(
    conversation: TunnelChatConversationSnapshot, zone: ZoneId, today: LocalDate, onOpen: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    var focused by remember { mutableStateOf(false) }
    val preview = remember(conversation.messages) { conversation.preview() }
    val id = conversation.remoteEndpointId.let { if (it.length <= 12) it else "…${it.takeLast(8)}" }
    val subtitle = listOfNotNull(conversation.receptionLabel, preview?.text ?: "暂无消息").joinToString(" · ")
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(shape).background(colors.surfaceVariant)
        .border(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.outline, shape)
        .onFocusChanged { focused = it.isFocused }.clickable(role = Role.Button, onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(colors.secondary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (conversation.outgoing) Lucide.MessagesSquare else Lucide.UserRound, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = colors.onSurfaceVariantSummary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(conversation.peerLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(id, fontSize = 12.sp, color = colors.onSurface, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.background(colors.secondary, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
                }
                TunnelStatus(conversation.connectionLabel, when {
                    conversation.closed -> colors.onSurfaceVariantSummary
                    conversation.connected -> LocalStatusColor.current
                    conversation.connecting -> colors.primary
                    else -> colors.onSurfaceVariantSummary
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(subtitle, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp,
                    color = colors.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (preview != null) {
                    Text(conversationTime(preview.createdAt, zone, today), fontSize = 12.sp,
                        color = colors.onSurfaceVariantSummary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TunnelStatus(label: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Spacer(Modifier.size(6.dp).background(tint, CircleShape))
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = MiuixTheme.colorScheme.onSurface)
    }
}

private val TunnelChatConversationSnapshot.peerLabel: String get() = if (outgoing) "端点" else "访客"
private val TunnelChatConversationSnapshot.receptionLabel: String? get() = if (outgoing || closed) null else mode.label()
private val TunnelChatConversationSnapshot.connectionLabel: String
    get() = when {
        closed -> "已结束"
        connected -> "已连接"
        connecting -> "连接中"
        else -> "未连接"
    }

internal data class TunnelConversationPreview(val text: String, val createdAt: Long)

internal fun TunnelChatConversationSnapshot.preview(): TunnelConversationPreview? =
    messages.asReversed().firstNotNullOfOrNull { message ->
        val text = when (message.kind) {
            ChatMessageKind.TEXT, ChatMessageKind.AI_FORMAL, ChatMessageKind.HUMAN_FORMAL ->
                message.payload.optString("text")
            ChatMessageKind.IMAGE, ChatMessageKind.FILE -> message.payload.optJSONObject("attachment")?.optString("name")
            else -> null
        }
        val line = text?.trim()?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
        val snippet = if (line.length <= 60) line else "${line.take(59).trimEnd()}…"
        TunnelConversationPreview(snippet, message.createdAt)
    }

private val conversationHour = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val conversationDay = DateTimeFormatter.ofPattern("M/d", Locale.ROOT)
private val conversationYear = DateTimeFormatter.ofPattern("uuuu/M/d", Locale.ROOT)

internal fun conversationTime(createdAt: Long, zone: ZoneId, today: LocalDate): String {
    val time = Instant.ofEpochMilli(createdAt).atZone(zone)
    return when {
        time.toLocalDate() == today -> time.format(conversationHour)
        time.toLocalDate() == today.minusDays(1) -> "昨天"
        time.year == today.year -> time.format(conversationDay)
        else -> time.format(conversationYear)
    }
}

@Composable
private fun TunnelConversationPanel(
    runtime: TunnelChatRuntime,
    conversation: TunnelChatConversationSnapshot,
) {
    val scope = rememberCoroutineScope()
    val draft = rememberTextFieldState()
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    var selectedImage by rememberSaveable { mutableStateOf<Uri?>(null) }
    var submittedImage by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedImage = uri
            submittedImage = null
            failure = null
        }
    }
    val delivery = conversation.imageDelivery?.takeIf { it.messageId == submittedImage }
    LaunchedEffect(delivery) {
        if (delivery?.delivered == true) {
            selectedImage = null
            submittedImage = null
        }
    }
    fun run(action: () -> Unit, onSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                runCatching { withContext(Dispatchers.IO) { action() } }.onSuccess {
                    failure = null
                    onSuccess()
                }.onFailure { failure = it.message ?: "聊天操作未完成。" }
            } finally {
                busy = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        conversation.messages.takeLast(40).forEach { message ->
            val text = message.payload.optString("text").ifBlank {
                message.payload.optJSONObject("attachment")?.optString("name")?.let { "附件：$it" } ?: message.kind.wireName
            }
            val role = when (message.kind) {
                ChatMessageKind.AI_SUGGESTION -> "AI 内部建议"
                ChatMessageKind.AI_FORMAL -> "AI"
                ChatMessageKind.HUMAN_FORMAL -> "人工"
                ChatMessageKind.TEXT -> if (conversation.outgoing) "我" else "访客"
                ChatMessageKind.IMAGE -> if ((message.senderRole == ChatSenderRole.VISITOR) == conversation.outgoing) "我" else "对方"
                ChatMessageKind.CONVERSATION_EVENT -> "会话状态"
                else -> "对方"
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(role, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (message.kind == ChatMessageKind.IMAGE) {
                    key(message.senderEndpointId, message.messageId) { TunnelImage(runtime, message) }
                } else Text(text, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
        if (conversation.closed) return@Column
        AssistantField("发送消息", draft, 4_000, busy, 4, "输入文本")
        failure?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
        if (selectedImage != null) {
            Note(when {
                conversation.sendingImage -> "正在发送图片…"
                delivery?.delivered == false -> "图片未确认送达，可重试。"
                !conversation.connected -> "连接后可发送图片。"
                else -> "已选择图片"
            })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!conversation.connected) {
                Action(if (conversation.connecting) "正在连接…" else "重连",
                    { run({ runtime.reconnectConversation(conversation.conversationId) }) },
                    enabled = !busy && !conversation.connecting, icon = Lucide.RotateCcw)
            }
            Action(if (conversation.connected) "发送" else "排队发送", {
                val text = draft.text.toString().trim()
                if (text.isNotBlank()) run({
                    if (conversation.outgoing) runtime.sendText(conversation.conversationId, text)
                    else runtime.sendHumanFormalText(conversation.conversationId, text)
                }, { draft.setTextAndPlaceCursorAtEnd("") })
            }, enabled = !busy && draft.text.isNotBlank(), primary = true, icon = Lucide.Send)
            if (conversation.canSendImage || selectedImage != null) {
                val uri = selectedImage
                if (uri == null) Action("选图", {
                    try { picker.launch(tunnelImageTypes) } catch (_: android.content.ActivityNotFoundException) {
                        failure = "无法打开图片选择器。"
                    }
                }, enabled = !busy && !conversation.sendingImage, icon = Lucide.ImagePlus)
                else {
                    Action("发送图片", {
                        var id: String? = null
                        run({ id = runtime.sendImage(conversation.conversationId, uri, delivery?.takeUnless { it.delivered }?.messageId) },
                            { submittedImage = id })
                    }, enabled = !busy && conversation.canSendImage && !conversation.sendingImage, icon = Lucide.ImagePlus)
                    Action("移除图片", { selectedImage = null; submittedImage = null; failure = null },
                        enabled = !busy && !conversation.sendingImage, icon = Lucide.X)
                }
            }
            if (!conversation.outgoing && conversation.mode == ConversationMode.HUMAN_HANDOVER) {
                Action("恢复 AI", { run({ runtime.resumeAi(conversation.conversationId) }) }, enabled = !busy)
            }
            if (confirmEnd) {
                Action("确认结束", { run({ runtime.endConversation(conversation.conversationId) }) }, enabled = !busy, icon = Lucide.X)
                Action("取消", { confirmEnd = false }, enabled = !busy)
            } else {
                Action("结束聊天", { confirmEnd = true }, enabled = !busy, icon = Lucide.X)
            }
        }
    }
}

@Composable
private fun TunnelImage(runtime: TunnelChatRuntime, message: ChatMessage) {
    val name = message.payload.optJSONObject("attachment")?.optString("name").orEmpty().ifBlank { "聊天图片" }
    val preview by produceState<Pair<Boolean, Bitmap?>>(false to null, runtime, message.messageId) {
        value = true to withContext(Dispatchers.IO) { runtime.imagePreview(message) }
    }
    val image = preview.second
    if (image == null) {
        Note(if (preview.first) "图片不可用" else "正在读取图片…")
    } else {
        Image(remember(image) { image.asImageBitmap() }, contentDescription = name,
            modifier = Modifier.padding(top = 4.dp).widthIn(max = 280.dp).heightIn(max = 220.dp)
                .clip(RoundedCornerShape(8.dp)))
    }
}

private fun ConversationMode.label(): String = when (this) {
    ConversationMode.AI -> "AI 接待"
    ConversationMode.HUMAN_HANDOVER -> "人工接管"
}
