package local.dshx.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.Compass
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Infinity
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.UserRound
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Zap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixIndication
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TunnelChatScreen(
    runtime: TunnelChatRuntime, runtimeState: TunnelChatRuntimeState, visible: Boolean,
    selectedConversationId: String?, onSelect: (String?) -> Unit, onSettings: () -> Unit,
) {
    val settings = runtime.settings()
    var discover by rememberSaveable { mutableStateOf(false) }
    val drafts = rememberSaveableStateHolder()
    val listState = key(settings.platformOrigin, settings.endpointId) { rememberLazyListState() }
    val conversations = runtimeState.conversations
    val selected = conversations.firstOrNull { it.conversationId == selectedConversationId }
    val closeDiscovery = { discover = false }
    val closeConversation = { onSelect(null) }
    BackHandler(visible && (discover || selected != null), onBack = if (discover) closeDiscovery else closeConversation)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = if (maxWidth >= 600.dp) 32.dp else 16.dp
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 680.dp).fillMaxSize().padding(horizontal = gutter)) {
            if (!discover && selected != null) {
                key(settings.platformOrigin, settings.endpointId) {
                    drafts.SaveableStateProvider(selected.conversationId) {
                        TunnelConversationPanel(runtime, selected, closeConversation, onSettings)
                    }
                }
                return@Column
            }
            TunnelChatHeader(runtimeState, onSettings, onBack = closeDiscovery.takeIf { discover })
            if (!discover) {
                if (conversations.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        LibraryEmpty(Lucide.MessagesSquare, "暂无会话",
                            if (settings.enabled) "从端点目录发起聊天，或等待访客联系。" else "穿透聊天尚未开启。") {
                            if (!settings.enabled) Action("打开聊天设置", onSettings, primary = true, icon = Lucide.Settings2)
                        }
                    }
                } else {
                    val zone = ZoneId.systemDefault()
                    val today = LocalDate.now(zone)
                    Section("会话 · ${conversations.size}")
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                        contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(conversations, key = { it.conversationId }) { conversation ->
                            TunnelConversationRow(conversation, zone, today) { onSelect(conversation.conversationId) }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), contentAlignment = Alignment.CenterEnd) {
                    VoiceButton("发现端点", Lucide.Plus, { discover = true }, size = 56)
                }
            } else {
                key(settings.platformOrigin) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TunnelDirectory(runtime, settings, visible, onSettings) { conversationId ->
                            onSelect(conversationId)
                            discover = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TunnelChatHeader(state: TunnelChatRuntimeState, onSettings: () -> Unit, onBack: (() -> Unit)?) {
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
            if (onBack != null) LibraryIcon(Lucide.ChevronLeft, "返回会话", onBack, iconSize = 18)
            Text(if (onBack != null) "发现" else "聊天", fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold,
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
    val context = LocalContext.current
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
                    if ("web-share" in endpoint.capabilities) {
                        Action("查看网页", {
                            runCatching { openWebShare(context, settings.platformOrigin, endpoint.endpointId) }
                                .onFailure { error = "网页入口无法打开。" }
                        }, enabled = endpoint.online)
                    }
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
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val draft = rememberTextFieldState()
    val view = LocalView.current
    val transferring = conversation.sendingAttachment || conversation.receivingAttachment
    DisposableEffect(view, transferring) {
        val previous = view.keepScreenOn
        if (transferring) view.keepScreenOn = true
        onDispose { if (transferring) view.keepScreenOn = previous }
    }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var selectedAttachment by rememberSaveable { mutableStateOf<Uri?>(null) }
    var submittedAttachment by rememberSaveable { mutableStateOf<String?>(null) }
    var exportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var exportSender by rememberSaveable { mutableStateOf<String?>(null) }
    val resolver = LocalContext.current.contentResolver
    val selectedName by produceState<String?>(null, selectedAttachment, resolver) {
        val uri = selectedAttachment
        value = if (uri == null) null else withContext(Dispatchers.IO) { runCatching { resolver.attachmentName(uri) }.getOrNull() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedAttachment = uri
            submittedAttachment = null
            failure = null
        }
    }
    val delivery = conversation.attachmentDelivery?.takeIf { it.messageId == submittedAttachment }
    LaunchedEffect(delivery) {
        if (delivery?.delivered == true) {
            selectedAttachment = null
            submittedAttachment = null
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
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = exportMessage
        val sender = exportSender
        exportMessage = null
        exportSender = null
        if (uri != null && id != null && sender != null) {
            run({ runtime.exportAttachment(conversation.conversationId, id, sender, uri) })
        }
    }
    Column(Modifier.fillMaxSize()) {
        TunnelConversationHeader(conversation, onBack, onMenu = { menu = true }) {
            if (menu) {
                val colors = MiuixTheme.colorScheme
                val offset = with(LocalDensity.current) { IntOffset(0, 52.dp.roundToPx()) }
                Popup(alignment = Alignment.TopEnd, offset = offset, properties = PopupProperties(focusable = true),
                    onDismissRequest = { menu = false; confirmEnd = false }) {
                    val shape = RoundedCornerShape(10.dp)
                    Column(Modifier.width(184.dp).background(colors.surfaceContainerHigh, shape)
                        .border(1.dp, colors.outline, shape).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (confirmEnd && !conversation.closed) {
                            Action("确认结束", {
                                menu = false
                                confirmEnd = false
                                run({ runtime.endConversation(conversation.conversationId) })
                            }, Modifier.fillMaxWidth(), enabled = !busy, danger = true)
                            Action("取消", { confirmEnd = false }, Modifier.fillMaxWidth())
                        } else {
                            if (!conversation.closed) {
                                if (!conversation.connected) Action(if (conversation.connecting) "连接中…" else "重新连接", {
                                    menu = false
                                    run({ runtime.reconnectConversation(conversation.conversationId) })
                                }, Modifier.fillMaxWidth(), enabled = !busy && !conversation.connecting, icon = Lucide.RotateCcw)
                                if (!conversation.outgoing && conversation.mode == ConversationMode.HUMAN_HANDOVER) {
                                    Action("恢复 AI 接待", {
                                        menu = false
                                        run({ runtime.resumeAi(conversation.conversationId) })
                                    }, Modifier.fillMaxWidth(), enabled = !busy)
                                }
                                Action("结束聊天", { confirmEnd = true }, Modifier.fillMaxWidth(), enabled = !busy, danger = true)
                            }
                            Action("聊天设置", { menu = false; onSettings() }, Modifier.fillMaxWidth(), icon = Lucide.Settings2)
                        }
                    }
                }
            }
        }
        Divider()
        TunnelTimeline(runtime, conversation, Modifier.weight(1f), canExport = !busy && exportMessage == null) { message ->
            val attachment = runCatching { message.attachment() }.getOrNull() ?: return@TunnelTimeline
            try {
                exportMessage = message.messageId
                exportSender = message.senderEndpointId
                exportPicker.launch(attachment.name)
            } catch (_: android.content.ActivityNotFoundException) {
                exportMessage = null
                exportSender = null
                failure = "无法打开文件保存选择器。"
            }
        }
        failure?.let { Text(it, Modifier.padding(bottom = 8.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
        if (!conversation.closed) Column(Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val uri = selectedAttachment
            if (uri != null) {
                selectedName?.let { name ->
                    Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val attachmentStatus = when {
                    conversation.sendingAttachment -> "正在发送附件…"
                    delivery?.delivered == false -> "附件未确认送达，可重试。"
                    !conversation.connected -> "连接后可发送附件。"
                    selectedName == null -> "已选择附件"
                    else -> null
                }
                attachmentStatus?.let { Note(it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(if (delivery?.delivered == false) "重试发送" else "发送附件", {
                        var id: String? = null
                        run({ id = runtime.sendAttachment(conversation.conversationId, uri, delivery?.takeUnless { it.delivered }?.messageId) },
                            { submittedAttachment = id })
                    }, enabled = !busy && conversation.canSendAttachment && !conversation.sendingAttachment, icon = Lucide.Paperclip)
                    Action("移除附件", { selectedAttachment = null; submittedAttachment = null; failure = null },
                        enabled = !busy && !conversation.sendingAttachment, icon = Lucide.X)
                }
            }
            val pickAttachment: (() -> Unit)? = if (conversation.canSendAttachment && uri == null) ({
                try { picker.launch(tunnelAttachmentTypes) } catch (_: android.content.ActivityNotFoundException) {
                    failure = "无法打开附件选择器。"
                }
            }) else null
            TunnelComposer(draft, busy, conversation.connected, pickAttachment, canPickAttachment = !conversation.sendingAttachment) {
                val text = draft.text.toString().trim()
                if (text.isNotBlank() && text.length <= tunnelDraftLimit) run({
                    if (conversation.outgoing) runtime.sendText(conversation.conversationId, text)
                    else runtime.sendHumanFormalText(conversation.conversationId, text)
                }, { draft.setTextAndPlaceCursorAtEnd("") })
            }
        }
    }
}

@Composable
private fun TunnelConversationHeader(
    conversation: TunnelChatConversationSnapshot, onBack: () -> Unit, onMenu: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LibraryIcon(Lucide.ChevronLeft, "返回会话", onBack)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (conversation.outgoing) "公开端点" else "访客", Modifier.weight(1f, fill = false).semantics { heading() },
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (conversation.connected) TunnelLinkBadge(conversation)
            }
            if (!conversation.connected) {
                Text(listOfNotNull(conversation.remoteEndpointId.takeLast(8), conversation.connectionLabel, conversation.receptionLabel)
                    .joinToString(" · "), fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center)
            }
        }
        Box {
            LibraryIcon(Lucide.Ellipsis, "会话操作", onMenu)
            menu()
        }
    }
}

@Composable
private fun TunnelLinkBadge(conversation: TunnelChatConversationSnapshot) {
    val colors = MiuixTheme.colorScheme
    val link = conversation.link?.collectAsState()?.value
    val path = link?.path
    val (label, icon) = when (path) {
        TunnelChatLink.Path.DIRECT -> "直连" to Lucide.Zap
        TunnelChatLink.Path.RELAY -> "中继" to Lucide.Cloud
        null -> "路径待确认" to Lucide.CircleHelp
    }
    val roundTripMs = link?.roundTripMs
    val latency = tunnelRoundTripLabel(roundTripMs)
    val grade = tunnelLatencyGrade(roundTripMs)
    val tone = when (grade) {
        TunnelLatencyGrade.UNKNOWN -> colors.onSurfaceVariantSummary
        TunnelLatencyGrade.LOW -> LocalStatusColor.current
        TunnelLatencyGrade.ELEVATED -> LocalWarningColor.current
        TunnelLatencyGrade.HIGH -> colors.error
    }
    val background = tone.copy(alpha = 0.10f).compositeOver(colors.surface)
    val foreground = lerp(tone, colors.onSurface, 0.20f)
    var expanded by remember(conversation.conversationId) { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(CircleShape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(role = Role.Button, onClickLabel = "查看连接详情", interactionSource = null,
            indication = remember(colors.onSurface) { MiuixIndication(colors.onSurface) }, onClick = { expanded = true })
        .semantics { stateDescription = grade.label },
        contentAlignment = Alignment.Center) {
        Box {
            val ring = if (focused) Modifier.border(2.dp, colors.primary, CircleShape) else Modifier
            Row(Modifier.widthIn(min = 104.dp).background(background, CircleShape).then(ring)
                .padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = foreground)
                Text(if (roundTripMs == null) "$label ·" else "$label · $latency", fontSize = 12.sp, lineHeight = 18.sp, color = foreground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (roundTripMs == null) {
                    Icon(Lucide.Infinity, contentDescription = null, modifier = Modifier.size(14.dp), tint = foreground)
                }
            }
            if (expanded) {
                val endpoint = conversation.remoteEndpointId.let { if (it.length > 8) "…${it.takeLast(8)}" else it }
                TunnelLinkDetails(endpoint, if (path == TunnelChatLink.Path.DIRECT) "P2P 直连" else label,
                    latency, conversation.receptionLabel, onDismiss = { expanded = false })
            }
        }
    }
}

@Composable
private fun TunnelLinkDetails(endpoint: String, path: String, latency: String, reception: String?, onDismiss: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Popup(TunnelLinkPopupPosition, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(Modifier.padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(width = 14.dp, height = 7.dp)) {
                val tip = Offset(size.width / 2, 0f)
                drawPath(Path().apply {
                    moveTo(0f, size.height)
                    lineTo(tip.x, tip.y)
                    lineTo(size.width, size.height)
                    close()
                }, colors.surfaceContainerHigh)
                drawLine(colors.outline, Offset(0f, size.height), tip, 1.dp.toPx())
                drawLine(colors.outline, tip, Offset(size.width, size.height), 1.dp.toPx())
            }
            Column(Modifier.background(colors.surfaceContainerHigh, shape).border(1.dp, colors.outline, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                listOfNotNull("端点 ID：$endpoint", "链路类型：$path", "往返延迟：$latency", reception).forEach { line ->
                    Text(line, fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurface)
                }
            }
        }
    }
}

private object TunnelLinkPopupPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val left = anchorBounds.center.x - popupContentSize.width / 2
        return IntOffset(left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), anchorBounds.bottom)
    }
}

internal fun tunnelRoundTripLabel(milliseconds: Long?): String = when (milliseconds) {
    null -> "暂无读数"
    0L -> "< 1 ms"
    else -> "$milliseconds ms"
}

internal enum class TunnelLatencyGrade(val label: String) {
    UNKNOWN("暂无延迟读数"), LOW("低延迟"), ELEVATED("延迟偏高"), HIGH("高延迟"),
}

internal fun tunnelLatencyGrade(milliseconds: Long?): TunnelLatencyGrade = when {
    milliseconds == null -> TunnelLatencyGrade.UNKNOWN
    milliseconds < 100 -> TunnelLatencyGrade.LOW
    milliseconds < 200 -> TunnelLatencyGrade.ELEVATED
    else -> TunnelLatencyGrade.HIGH
}

private val ChatMessage.timelineKey: String get() = "$senderEndpointId/$messageId"

internal fun tunnelMessageStartsGroup(previous: Long?, current: Long, zone: ZoneId): Boolean {
    if (previous == null || current - previous >= 5 * 60_000L) return true
    return Instant.ofEpochMilli(previous).atZone(zone).toLocalDate() != Instant.ofEpochMilli(current).atZone(zone).toLocalDate()
}

@Composable
private fun TunnelTimeline(runtime: TunnelChatRuntime, conversation: TunnelChatConversationSnapshot, modifier: Modifier,
    canExport: Boolean, onExport: (ChatMessage) -> Unit,
) {
    val messages = remember(conversation.messages) { conversation.messages.takeLast(40).asReversed() }
    val state = rememberLazyListState()
    val latest = messages.firstOrNull()?.timelineKey
    LaunchedEffect(latest) {
        // 列表在插入消息后可能暂时保留上一条为首项；读历史时不拉回到底部。
        if (latest != null && state.firstVisibleItemIndex <= 1 && state.firstVisibleItemScrollOffset == 0) state.scrollToItem(0)
    }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val bubbleWidth = minOf(maxWidth * 0.78f, 440.dp)
        LazyColumn(Modifier.fillMaxSize(), state = state, reverseLayout = true,
            contentPadding = PaddingValues(top = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top)) {
            items(messages.size, key = { messages[it].timelineKey }) { index ->
                val message = messages[index]
                val time = conversationTime(message.createdAt, zone, today)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tunnelMessageStartsGroup(messages.getOrNull(index + 1)?.createdAt, message.createdAt, zone)) {
                        TunnelTimelineNote(time)
                    }
                    TunnelMessageBubble(runtime, message, own = message.senderEndpointId != conversation.remoteEndpointId,
                        time = time, maxWidth = bubbleWidth, canExport = canExport, onExport = { onExport(message) })
                }
            }
            item(key = "status") {
                TunnelTimelineNote(when {
                    conversation.connected && conversation.outgoing -> "已连接至公开端点"
                    conversation.connected -> "已连接访客"
                    else -> conversation.connectionLabel
                }, pill = true)
            }
        }
    }
}

@Composable
private fun TunnelTimelineNote(text: String, pill: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, modifier = if (pill) Modifier.border(1.dp, colors.outline, CircleShape)
            .padding(horizontal = 12.dp, vertical = 5.dp) else Modifier,
            fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariantSummary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TunnelMessageBubble(runtime: TunnelChatRuntime, message: ChatMessage, own: Boolean, time: String,
    maxWidth: Dp, canExport: Boolean, onExport: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val text = message.payload.optString("text").ifBlank {
        message.payload.optJSONObject("attachment")?.optString("name")?.let { "附件：$it" } ?: message.kind.wireName
    }
    if (message.kind == ChatMessageKind.CONVERSATION_EVENT) {
        TunnelTimelineNote(text, pill = true)
        return
    }
    val suggestion = message.kind == ChatMessageKind.AI_SUGGESTION
    val sent = own && !suggestion
    val dark = colors.background.luminance() < 0.5f
    val fill = when {
        suggestion -> colors.secondary
        sent && dark -> colors.secondary
        sent -> colors.onSurface
        else -> colors.surfaceVariant
    }
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, if (sent) Alignment.End else Alignment.Start)) {
        if (!sent) Box(Modifier.size(32.dp).background(colors.secondary, CircleShape), contentAlignment = Alignment.Center) {
            if (message.senderRole == ChatSenderRole.AI) Text("AI", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            else Icon(Lucide.UserRound, contentDescription = if (message.senderRole == ChatSenderRole.VISITOR) "访客" else "人工",
                modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariantSummary)
        }
        Column(Modifier.widthIn(max = maxWidth), horizontalAlignment = if (sent) Alignment.End else Alignment.Start) {
            Column(Modifier.background(fill, shape)
                .then(if (!sent || dark) Modifier.border(1.dp, colors.outline, shape) else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (suggestion) Text("AI 内部建议", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                val tint = if (sent && !dark) colors.background else colors.onSurface
                when (message.kind) {
                    ChatMessageKind.IMAGE -> TunnelImage(runtime, message)
                    ChatMessageKind.FILE -> {
                        Text(text, fontSize = 15.sp, lineHeight = 22.sp, color = tint, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Action("保存文件", onExport, Modifier.padding(top = 8.dp), enabled = canExport, icon = Lucide.Download)
                    }
                    else -> Text(text, fontSize = 15.sp, lineHeight = 22.sp, color = tint)
                }
            }
            if (sent) Text(if (message.senderRole == ChatSenderRole.AI) "AI · $time" else time,
                Modifier.padding(top = 4.dp), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
        }
    }
}

private const val tunnelDraftLimit = 4_000

@Composable
private fun TunnelComposer(draft: TextFieldState, busy: Boolean, connected: Boolean,
    pickAttachment: (() -> Unit)?, canPickAttachment: Boolean, onSend: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    val text = draft.text.trim()
    val tooLong = text.length > tunnelDraftLimit
    val enabled = !busy && text.isNotEmpty() && !tooLong
    var focused by remember { mutableStateOf(false) }
    val hint = if (connected) "发送消息" else "离线 · 排队发送"
    val issue = "消息不能超过 $tunnelDraftLimit 个字符。"
    Row(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh, shape)
        .border(if (focused || tooLong) 2.dp else 1.dp, when {
            tooLong -> colors.error
            focused -> colors.primary
            else -> colors.outline
        }, shape).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (pickAttachment != null) LibraryIcon(Lucide.Paperclip, "选择附件", pickAttachment, enabled = canPickAttachment && !busy, iconSize = 18)
        val style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = colors.onSurface)
        BasicTextField(state = draft, readOnly = busy, textStyle = style, cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .onFocusChanged { focused = it.isFocused }.semantics {
                    contentDescription = "消息"
                    if (tooLong) error(issue)
                }, lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
            decorator = TextFieldDecorator { inner ->
                Box(Modifier.padding(start = if (pickAttachment == null) 12.dp else 2.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                    contentAlignment = Alignment.CenterStart) {
                    if (draft.text.isEmpty()) Text(hint, fontSize = style.fontSize, lineHeight = style.lineHeight,
                        color = colors.onSurfaceVariantSummary)
                    inner()
                }
            })
        var buttonFocused by remember { mutableStateOf(false) }
        val tint = if (enabled) colors.background else colors.onSurfaceVariantSummary
        Button(onClick = onSend, enabled = enabled, modifier = Modifier.size(48.dp).onFocusChanged { buttonFocused = it.isFocused }
            .border(2.dp, if (buttonFocused) colors.primary else Color.Transparent, CircleShape),
            minWidth = 48.dp, minHeight = 48.dp, cornerRadius = 24.dp, insideMargin = PaddingValues(0.dp),
            indication = remember(tint) { MiuixIndication(tint) }, colors = ButtonDefaults.buttonColors(
                color = Color.Transparent, disabledColor = Color.Transparent)) {
            Box(Modifier.size(36.dp).background(if (enabled) colors.onSurface else colors.secondary, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Lucide.ArrowUp, if (connected) "发送消息" else "排队发送", modifier = Modifier.size(20.dp), tint = tint)
            }
        }
    }
    if (tooLong) Text(issue, Modifier.padding(start = 12.dp), fontSize = 12.sp, color = colors.error)
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
