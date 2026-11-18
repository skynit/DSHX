package local.dshx.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TunnelChatDirectoryAndConversations(runtime: TunnelChatRuntime, runtimeState: TunnelChatRuntimeState, platformOrigin: String) {
    val scope = rememberCoroutineScope()
    val query = rememberTextFieldState()
    val draft = rememberTextFieldState()
    var directory by remember { mutableStateOf<TunnelDirectoryPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedConversationId by rememberSaveable { mutableStateOf<String?>(null) }

    fun load(cursor: String? = null, append: Boolean = false) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    runtime.listPublicEndpoints(platformOrigin, query.text.toString().trim().takeIf { it.isNotBlank() }, cursor)
                }
            }.onSuccess { next ->
                directory = if (append && directory != null) {
                    TunnelDirectoryPage((directory!!.items + next.items).distinctBy { it.endpointId }, next.nextCursor)
                } else next
            }
                .onFailure { error = it.message ?: "公共端点目录暂时不可用。" }
            loading = false
        }
    }

    fun refresh() = load()

    LaunchedEffect(platformOrigin) { refresh() }

    Section("公共端点目录")
    Panel {
        AssistantField("搜索端点", query, 80, loading, 1, "按公开名称或介绍搜索")
        Action("刷新", ::refresh, enabled = !loading, icon = Lucide.RotateCcw)
        error?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
        val items = directory?.items.orEmpty().filter { item ->
            val filter = query.text.toString().trim()
            filter.isBlank() || item.displayName.contains(filter, true) || item.publicIntroduction.contains(filter, true)
        }
        if (items.isEmpty()) {
            Note(if (loading) "正在读取公共端点…" else "暂时没有可显示的公开端点。隐身端点不会出现在这里。")
        } else {
            items.forEachIndexed { index, endpoint ->
                if (index > 0) Divider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val isSelf = endpoint.endpointId == runtime.settings().endpointId
                    Text(endpoint.displayName.ifBlank { endpoint.endpointId }, fontSize = 15.sp)
                    Note(endpoint.publicIntroduction.ifBlank { "未填写公开身份介绍。" })
                    Note("${if (isSelf) "本端" else if (endpoint.online) "在线" else "离线"} · ${if (endpoint.acceptance == TunnelAcceptance.ACCEPTING) "接受新聊天" else "暂停新聊天"} · ${endpoint.endpointId}")
                    Action("发起聊天", {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    runtime.startConversation(endpoint, platformOrigin)
                                }
                            }
                                .onSuccess { selectedConversationId = it }
                                .onFailure { error = it.message ?: "聊天连接未能发起。" }
                        }
                    }, enabled = !isSelf && endpoint.acceptance == TunnelAcceptance.ACCEPTING && runtime.settings().enabled,
                        icon = Lucide.Send)
                }
            }
            directory?.nextCursor?.let { cursor ->
                Spacer(Modifier.height(8.dp))
                Action("加载更多", { load(cursor, append = true) }, enabled = !loading, icon = Lucide.RotateCcw)
            }
        }
    }

    if (runtimeState.conversations.isNotEmpty()) {
        Section("聊天会话")
        Panel {
            runtimeState.conversations.forEachIndexed { index, conversation ->
                if (index > 0) Divider()
                val selected = selectedConversationId == conversation.conversationId
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (conversation.outgoing) "访客 · ${conversation.remoteEndpointId}" else "端点访客 · ${conversation.remoteEndpointId}", fontSize = 14.sp)
                        Note("${if (conversation.connected) "已连接" else "等待连接"} · ${conversation.mode.label()} · ${conversation.messages.size} 条消息")
                    }
                    Action(if (selected) "收起" else "查看", { selectedConversationId = if (selected) null else conversation.conversationId }, icon = if (selected) Lucide.X else Lucide.Send)
                }
                if (selected) {
                    Spacer(Modifier.height(8.dp))
                    TunnelConversationPanel(runtime, conversation, draft)
                }
            }
        }
    }
}

@Composable
private fun TunnelConversationPanel(
    runtime: TunnelChatRuntime,
    conversation: TunnelChatConversationSnapshot,
    draft: androidx.compose.foundation.text.input.TextFieldState,
) {
    val scope = rememberCoroutineScope()
    var sending by remember(conversation.conversationId) { mutableStateOf(false) }
    var failure by remember(conversation.conversationId) { mutableStateOf<String?>(null) }
    val canSend = runtime.settings().enabled && (conversation.outgoing || conversation.connected)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        conversation.messages.takeLast(40).forEach { message ->
            val text = message.payload.optString("text").ifBlank {
                message.payload.optJSONObject("attachment")?.optString("name")?.let { "附件：$it" } ?: message.kind.wireName
            }
            val role = when (message.kind) {
                ChatMessageKind.AI_SUGGESTION -> "AI 内部建议"
                ChatMessageKind.AI_FORMAL -> "AI"
                ChatMessageKind.HUMAN_FORMAL -> "人工"
                ChatMessageKind.TEXT -> if (message.senderRole == ChatSenderRole.VISITOR) "访客" else "对方"
                else -> message.senderRole.name.lowercase()
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(role, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(text, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
        AssistantField("发送消息", draft, 4_000, !canSend || sending, 4, "输入文本")
        failure?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (conversation.outgoing && !conversation.connected) {
                Action("重连", { scope.launch {
                    runCatching { withContext(Dispatchers.IO) { runtime.reconnectConversation(conversation.conversationId) } }
                        .onSuccess { failure = null }
                        .onFailure { failure = it.message ?: "无法重连。" }
                } }, icon = Lucide.RotateCcw)
            }
            Action(if (conversation.connected) "发送" else "排队发送", {
                val text = draft.text.toString().trim()
                if (text.isNotBlank()) scope.launch {
                    sending = true
                    runCatching {
                        withContext(Dispatchers.IO) {
                            if (conversation.outgoing) runtime.sendText(conversation.conversationId, text)
                            else runtime.sendHumanFormalText(conversation.conversationId, text)
                        }
                    }.onSuccess {
                        draft.setTextAndPlaceCursorAtEnd("")
                        failure = null
                    }.onFailure { failure = it.message ?: "消息未发送。" }
                    sending = false
                }
            }, enabled = canSend && !sending && draft.text.toString().trim().isNotBlank(), primary = true, icon = Lucide.Send)
            if (!conversation.outgoing && conversation.mode == ConversationMode.HUMAN_HANDOVER) {
                Action("恢复 AI", { runtime.resumeAi(conversation.conversationId) })
            }
        }
    }
}

private fun ConversationMode.label(): String = when (this) {
    ConversationMode.AI -> "AI 接待"
    ConversationMode.HUMAN_HANDOVER -> "人工接管"
}
