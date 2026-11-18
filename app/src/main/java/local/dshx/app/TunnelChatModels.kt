package local.dshx.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/** DSHX 客户端与官方穿透平台之间的聊天协议常量。 */
object TunnelChatProtocol {
    const val CHAT = "dshx-chat/1"
    const val SIGNALING = "cwnas-webrtc-signaling"
    const val DEFAULT_PLATFORM_ORIGIN = "https://cwnas.net"
    const val SIGNALING_PATH = "/api/v1/signaling"
    const val REGISTER_PATH = "/api/v1/dshx/chat/endpoints/register"
    const val DIRECTORY_PATH = "/api/v1/dshx/chat/endpoints"
    const val MAX_SIGNALING_BYTES = 64 * 1024
    const val MAX_MESSAGE_BYTES = 256 * 1024
    const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    const val MAX_DOCUMENT_BYTES = 20L * 1024 * 1024
    const val MAX_CONVERSATION_ATTACHMENT_BYTES = 100L * 1024 * 1024

    fun message(message: ChatMessage): JSONObject = message.toJson()

    fun parseMessage(value: String): ChatMessage = ChatMessage.fromJson(JSONObject(value))

    fun ack(ack: ChatAck): JSONObject = ack.toJson()

    fun parseAck(value: String): ChatAck = ChatAck.fromJson(JSONObject(value))

    fun frameType(value: JSONObject): String {
        require(value.optString("protocol") == CHAT) { "聊天协议版本不受支持。" }
        return value.optString("type").also { require(it.isNotBlank()) { "聊天消息类型不能为空。" } }
    }
}

enum class TunnelPublication { PUBLISHED, HIDDEN }

enum class TunnelAcceptance { ACCEPTING, PAUSED }

enum class ChatSenderRole { VISITOR, ENDPOINT_USER, AI }

enum class ChatMessageKind {
    TEXT, IMAGE, FILE, AI_FORMAL, HUMAN_FORMAL, AI_SUGGESTION,
    TOOL_REQUEST, TOOL_CONFIRMATION, TOOL_RESULT, CONVERSATION_EVENT;

    val wireName: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): ChatMessageKind = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("不支持的聊天消息类型。")
    }
}

enum class ConversationMode { AI, HUMAN_HANDOVER }

/** 交给端点 AI 的请求；文本来自外部端点，只能作为不可信用户输入。 */
data class TunnelChatAiRequest(
    val sessionKey: String,
    val remoteEndpointId: String,
    val text: String,
    val formal: Boolean,
)

/** 端点用户能在客户端设置的公共端点和远端能力开关。 */
data class TunnelChatSettings(
    val enabled: Boolean = false,
    val platformOrigin: String = TunnelChatProtocol.DEFAULT_PLATFORM_ORIGIN,
    val endpointId: String = UUID.randomUUID().toString(),
    val displayName: String = "",
    val publicIntroduction: String = "",
    val publication: TunnelPublication = TunnelPublication.PUBLISHED,
    val acceptance: TunnelAcceptance = TunnelAcceptance.ACCEPTING,
    val newConversationIntervalMs: Long = 5_000L,
    val maxActiveConversations: Int = 8,
    val maxAiReplies: Int = 10,
    val remoteMemoryEnabled: Boolean = false,
    val remoteKnowledgeEnabled: Boolean = false,
    val remoteFilesEnabled: Boolean = false,
    val phoneControlEnabled: Boolean = false,
    val queueMaxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
    val queueMaxMessages: Int = 100,
    val queueMaxBytes: Long = 100L * 1024 * 1024,
) {
    fun validate(): TunnelChatSettings {
        require(platformOrigin.trim().matches(Regex("https://[^/?#]+"))) { "穿透平台地址必须是 HTTPS Origin。" }
        require(endpointId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "端点编号格式无效。" }
        require(displayName.length <= 80 && !displayName.contains('\u0000')) { "端点名称不能超过 80 个 UTF-16 单位。" }
        require(publicIntroduction.length <= 2_000 && !publicIntroduction.contains('\u0000')) { "公开身份介绍不能超过 2000 个 UTF-16 单位。" }
        require(newConversationIntervalMs in 1_000L..24L * 60 * 60 * 1000) { "新聊天限频必须在 1 秒到 24 小时之间。" }
        require(maxActiveConversations in 1..64) { "活跃聊天数必须在 1 到 64 之间。" }
        require(maxAiReplies in 0..1_000) { "AI 正式回复上限必须在 0 到 1000 之间。" }
        require(queueMaxAgeMs in 60_000L..30L * 24 * 60 * 60 * 1000) { "离线消息保存期限无效。" }
        require(queueMaxMessages in 1..10_000) { "离线消息数量上限无效。" }
        require(queueMaxBytes in 1L..1024L * 1024 * 1024) { "离线附件总量上限无效。" }
        return copy(
            platformOrigin = platformOrigin.trim().removeSuffix("/"),
            displayName = displayName.trim(),
            publicIntroduction = publicIntroduction.trim(),
        )
    }

    fun descriptor(online: Boolean): TunnelEndpointDescriptor = TunnelEndpointDescriptor(
        endpointId = endpointId,
        displayName = displayName,
        publicIntroduction = publicIntroduction,
        publication = publication,
        acceptance = acceptance,
        online = online,
        maxAiReplies = maxAiReplies,
        // 公共目录只声明协议能力，不泄露本地记忆、文件或手机工具开关。
        capabilities = listOf("text"),
    )

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("platformOrigin", platformOrigin)
        .put("endpointId", endpointId)
        .put("displayName", displayName)
        .put("publicIntroduction", publicIntroduction)
        .put("publication", publication.name.lowercase())
        .put("acceptance", acceptance.name.lowercase())
        .put("newConversationIntervalMs", newConversationIntervalMs)
        .put("maxActiveConversations", maxActiveConversations)
        .put("maxAiReplies", maxAiReplies)
        .put("remoteMemoryEnabled", remoteMemoryEnabled)
        .put("remoteKnowledgeEnabled", remoteKnowledgeEnabled)
        .put("remoteFilesEnabled", remoteFilesEnabled)
        .put("phoneControlEnabled", phoneControlEnabled)
        .put("queueMaxAgeMs", queueMaxAgeMs)
        .put("queueMaxMessages", queueMaxMessages)
        .put("queueMaxBytes", queueMaxBytes)

    companion object {
        fun fromJson(value: JSONObject): TunnelChatSettings = TunnelChatSettings(
            enabled = value.optBoolean("enabled", false),
            platformOrigin = value.optString("platformOrigin", TunnelChatProtocol.DEFAULT_PLATFORM_ORIGIN),
            endpointId = value.optString("endpointId", UUID.randomUUID().toString()),
            displayName = value.optString("displayName"),
            publicIntroduction = value.optString("publicIntroduction"),
            publication = when (value.optString("publication", "published")) {
                "published" -> TunnelPublication.PUBLISHED
                "hidden" -> TunnelPublication.HIDDEN
                else -> throw IllegalArgumentException("端点发布状态无效。")
            },
            acceptance = when (value.optString("acceptance", "accepting")) {
                "accepting" -> TunnelAcceptance.ACCEPTING
                "paused" -> TunnelAcceptance.PAUSED
                else -> throw IllegalArgumentException("端点接收状态无效。")
            },
            newConversationIntervalMs = value.optLong("newConversationIntervalMs", 5_000L),
            maxActiveConversations = value.optInt("maxActiveConversations", 8),
            maxAiReplies = value.optInt("maxAiReplies", 10),
            remoteMemoryEnabled = value.optBoolean("remoteMemoryEnabled", false),
            remoteKnowledgeEnabled = value.optBoolean("remoteKnowledgeEnabled", false),
            remoteFilesEnabled = value.optBoolean("remoteFilesEnabled", false),
            phoneControlEnabled = value.optBoolean("phoneControlEnabled", false),
            queueMaxAgeMs = value.optLong("queueMaxAgeMs", 7L * 24 * 60 * 60 * 1000),
            queueMaxMessages = value.optInt("queueMaxMessages", 100),
            queueMaxBytes = value.optLong("queueMaxBytes", 100L * 1024 * 1024),
        ).validate()
    }
}

/** 公共端点目录项。目录项不是身份凭证，必须在 WebRTC 会话内再次确认身份。 */
data class TunnelEndpointDescriptor(
    val endpointId: String,
    val displayName: String,
    val publicIntroduction: String,
    val publication: TunnelPublication,
    val acceptance: TunnelAcceptance,
    val online: Boolean,
    val maxAiReplies: Int,
    val capabilities: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("endpointId", endpointId)
        .put("displayName", displayName)
        .put("publicIntroduction", publicIntroduction)
        .put("publication", publication.name.lowercase())
        .put("acceptance", acceptance.name.lowercase())
        .put("online", online)
        .put("capabilities", JSONArray(capabilities))

    companion object {
        fun fromJson(value: JSONObject): TunnelEndpointDescriptor {
            val capabilities = value.optJSONArray("capabilities")?.let { array ->
                (0 until array.length()).map { index ->
                    array.getString(index).also { require(it.matches(Regex("[a-z][a-z0-9_]{0,63}"))) { "端点能力标识无效。" } }
                }
            } ?: emptyList()
            val acceptance = value.optString("acceptance").takeIf { it.isNotBlank() }
                ?: if (value.optBoolean("accepting", false)) "accepting" else "paused"
            return TunnelEndpointDescriptor(
                endpointId = value.getString("endpointId"),
                displayName = value.optString("displayName"),
                publicIntroduction = value.optString("publicIntroduction"),
                publication = when (value.optString("publication")) {
                    "published" -> TunnelPublication.PUBLISHED
                    "hidden" -> TunnelPublication.HIDDEN
                    else -> throw IllegalArgumentException("目录发布状态无效。")
                },
                acceptance = when (acceptance) {
                    "accepting" -> TunnelAcceptance.ACCEPTING
                    "paused" -> TunnelAcceptance.PAUSED
                    else -> throw IllegalArgumentException("目录接收状态无效。")
                },
                online = value.optBoolean("online"),
                maxAiReplies = value.optInt("maxAiReplies", 10).also { require(it in 0..1_000) { "目录 AI 回复上限无效。" } },
                capabilities = capabilities,
            ).also {
                require(it.endpointId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "目录端点编号无效。" }
                require(it.displayName.length <= 80 && it.publicIntroduction.length <= 2_000) { "目录介绍过长。" }
            }
        }
    }
}

data class TunnelDirectoryPage(val items: List<TunnelEndpointDescriptor>, val nextCursor: String?) {
    companion object {
        fun fromJson(value: JSONObject): TunnelDirectoryPage {
            val array = value.optJSONArray("items") ?: value.optJSONArray("endpoints") ?: JSONArray()
            return TunnelDirectoryPage(
                items = (0 until array.length()).map { TunnelEndpointDescriptor.fromJson(array.getJSONObject(it)) },
                nextCursor = value.optString("nextCursor").takeIf { it.isNotBlank() },
            )
        }
    }
}

data class TunnelChatConversationSnapshot(
    val conversationId: String,
    val remoteEndpointId: String,
    val outgoing: Boolean,
    val connected: Boolean,
    val connecting: Boolean,
    val closed: Boolean,
    val mode: ConversationMode,
    val aiRepliesSent: Int,
    val messages: List<ChatMessage>,
)

internal fun ChatMessage.isEnd() = kind == ChatMessageKind.CONVERSATION_EVENT && payload.optString("event") == "ended"

data class ChatAttachment(
    val attachmentId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
) {
    fun validate(): ChatAttachment {
        require(attachmentId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "附件编号无效。" }
        require(name.isNotBlank() && name.length <= 240 && !name.contains('\u0000') &&
            !name.contains('/') && !name.contains('\\') && name != "." && name != "..") { "附件名称无效。" }
        require(size >= 0) { "附件大小不能为负数。" }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "附件摘要无效。" }
        val image = mimeType.lowercase() in setOf("image/jpeg", "image/png", "image/webp")
        val document = mimeType.lowercase() in setOf(
            "application/pdf", "text/plain", "text/markdown",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
        require(image || document) { "附件类型不在聊天允许范围内。" }
        require(size <= if (image) TunnelChatProtocol.MAX_IMAGE_BYTES else TunnelChatProtocol.MAX_DOCUMENT_BYTES) {
            "附件超过聊天大小限制。"
        }
        return copy(name = name.trim(), mimeType = mimeType.lowercase(), sha256 = sha256.lowercase())
    }

    fun toJson(): JSONObject = JSONObject()
        .put("attachmentId", attachmentId)
        .put("name", name)
        .put("mimeType", mimeType)
        .put("size", size)
        .put("sha256", sha256)

    companion object {
        fun fromJson(value: JSONObject): ChatAttachment = ChatAttachment(
            attachmentId = value.getString("attachmentId"),
            name = value.getString("name"),
            mimeType = value.getString("mimeType"),
            size = value.getLong("size"),
            sha256 = value.getString("sha256"),
        ).validate()
    }
}

data class ChatMessage(
    val messageId: String,
    val conversationId: String,
    val seq: Long,
    val senderRole: ChatSenderRole,
    val senderEndpointId: String,
    val kind: ChatMessageKind,
    val createdAt: Long,
    val replyTo: String? = null,
    val payload: JSONObject = JSONObject(),
) {
    init {
        require(messageId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "消息编号无效。" }
        require(conversationId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "会话编号无效。" }
        require(seq >= 0) { "消息顺序号不能为负数。" }
        require(senderEndpointId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "发送端点编号无效。" }
        require(createdAt > 0) { "消息时间无效。" }
        require(payload.toString().toByteArray(StandardCharsets.UTF_8).size <= TunnelChatProtocol.MAX_MESSAGE_BYTES) { "聊天消息过大。" }
        when (kind) {
            ChatMessageKind.TEXT, ChatMessageKind.AI_FORMAL, ChatMessageKind.HUMAN_FORMAL, ChatMessageKind.AI_SUGGESTION ->
                require(payload.optString("text").isNotEmpty()) { "文本消息不能为空。" }
            ChatMessageKind.IMAGE, ChatMessageKind.FILE -> ChatAttachment.fromJson(payload.getJSONObject("attachment"))
            else -> Unit
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("protocol", TunnelChatProtocol.CHAT)
        .put("type", "message")
        .put("messageId", messageId)
        .put("conversationId", conversationId)
        .put("seq", seq)
        .put("sender", JSONObject().put("role", senderRole.name.lowercase()).put("endpointId", senderEndpointId))
        .put("kind", kind.wireName)
        .put("createdAt", createdAt)
        .put("replyTo", replyTo ?: JSONObject.NULL)
        .put("payload", JSONObject(payload.toString()))

    companion object {
        fun fromJson(value: JSONObject): ChatMessage {
            require(value.optString("protocol") == TunnelChatProtocol.CHAT) { "聊天协议版本不受支持。" }
            require(value.optString("type") == "message") { "不是聊天消息。" }
            val sender = value.getJSONObject("sender")
            val role = when (sender.getString("role")) {
                "visitor" -> ChatSenderRole.VISITOR
                "endpoint_user" -> ChatSenderRole.ENDPOINT_USER
                "ai" -> ChatSenderRole.AI
                else -> throw IllegalArgumentException("发送者角色无效。")
            }
            return ChatMessage(
                messageId = value.getString("messageId"),
                conversationId = value.getString("conversationId"),
                seq = value.getLong("seq"),
                senderRole = role,
                senderEndpointId = sender.getString("endpointId"),
                kind = ChatMessageKind.fromWire(value.getString("kind")),
                createdAt = value.getLong("createdAt"),
                replyTo = if (value.has("replyTo") && !value.isNull("replyTo")) value.getString("replyTo") else null,
                payload = JSONObject(value.optJSONObject("payload")?.toString() ?: "{}"),
            )
        }

        fun text(conversationId: String, seq: Long, role: ChatSenderRole, endpointId: String, text: String, now: Long = System.currentTimeMillis()): ChatMessage {
            require(text.isNotBlank()) { "文本消息不能为空。" }
            return ChatMessage(UUID.randomUUID().toString(), conversationId, seq, role, endpointId, ChatMessageKind.TEXT, now, payload = JSONObject().put("text", text))
        }
    }
}

data class ChatAck(
    val messageId: String,
    val conversationId: String,
    val status: Status,
    val nextExpectedSeq: Long? = null,
) {
    enum class Status { RECEIVED, DUPLICATE, REJECTED }

    init {
        require(messageId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "确认消息编号无效。" }
        require(conversationId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "确认会话编号无效。" }
        require(nextExpectedSeq == null || nextExpectedSeq >= 0) { "确认顺序号无效。" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("protocol", TunnelChatProtocol.CHAT)
        .put("type", "ack")
        .put("messageId", messageId)
        .put("conversationId", conversationId)
        .put("status", status.name.lowercase())
        .put("nextExpectedSeq", nextExpectedSeq ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject): ChatAck {
            require(value.optString("protocol") == TunnelChatProtocol.CHAT && value.optString("type") == "ack") { "不是聊天确认。" }
            return ChatAck(
                messageId = value.getString("messageId"),
                conversationId = value.getString("conversationId"),
                status = when (value.getString("status")) {
                    "received" -> Status.RECEIVED
                    "duplicate" -> Status.DUPLICATE
                    "rejected" -> Status.REJECTED
                    else -> throw IllegalArgumentException("聊天确认状态无效。")
                },
                nextExpectedSeq = value.optLong("nextExpectedSeq").takeIf { value.has("nextExpectedSeq") && !value.isNull("nextExpectedSeq") },
            )
        }
    }
}

/** 为所有新会话提供全局限频和并发上限；已有会话的消息不走此闸门。 */
class TunnelConversationAdmission(
    private val settings: () -> TunnelChatSettings,
    loadLastAcceptedAt: () -> Long? = { null },
    private val saveLastAcceptedAt: (Long) -> Unit = {},
) {
    constructor(settings: () -> TunnelChatSettings) : this(settings, { null }, {})

    private val active = LinkedHashMap<String, Long>()
    private var lastAcceptedAt: Long? = loadLastAcceptedAt()

    @Synchronized
    fun open(conversationId: String, now: Long = System.currentTimeMillis(), reconnect: Boolean = false): AdmissionResult {
        require(conversationId.isNotBlank()) { "会话编号不能为空。" }
        val config = settings().validate()
        if (active.containsKey(conversationId)) return AdmissionResult.Accepted(existing = true)
        if (config.publication == TunnelPublication.HIDDEN || (!reconnect && config.acceptance == TunnelAcceptance.PAUSED)) {
            return AdmissionResult.Rejected("endpoint_not_accepting")
        }
        if (reconnect) {
            if (active.size >= config.maxActiveConversations) return AdmissionResult.Rejected("conversation_capacity")
            active[conversationId] = now
            return AdmissionResult.Accepted(existing = true)
        }
        val previous = lastAcceptedAt
        if (previous != null) {
            val elapsed = now - previous
            if (elapsed < config.newConversationIntervalMs) {
                return AdmissionResult.RateLimited((config.newConversationIntervalMs - elapsed).coerceAtLeast(1L))
            }
        }
        if (active.size >= config.maxActiveConversations) return AdmissionResult.Rejected("conversation_capacity")
        saveLastAcceptedAt(now)
        lastAcceptedAt = now
        active[conversationId] = now
        return AdmissionResult.Accepted(existing = false)
    }

    @Synchronized
    fun close(conversationId: String): Boolean = active.remove(conversationId) != null

    @Synchronized
    fun activeConversationIds(): Set<String> = active.keys.toSet()

    @Synchronized
    fun clear() {
        active.clear()
    }

    sealed class AdmissionResult {
        data class Accepted(val existing: Boolean) : AdmissionResult()
        data class RateLimited(val retryAfterMs: Long) : AdmissionResult()
        data class Rejected(val code: String) : AdmissionResult()
    }
}

/** 端点用户正式发言后进入人工接管；恢复 AI 前不会发送正式 AI 消息。 */
class TunnelConversationState(private val maxAiReplies: () -> Int) {
    @Volatile var mode: ConversationMode = ConversationMode.AI
        private set
    @Volatile var aiRepliesSent: Int = 0
        private set
    private var waitingHumanNotified = false

    @Synchronized
    fun recordFormalMessage(kind: ChatMessageKind, senderRole: ChatSenderRole) {
        if (kind == ChatMessageKind.HUMAN_FORMAL && senderRole == ChatSenderRole.ENDPOINT_USER) mode = ConversationMode.HUMAN_HANDOVER
    }

    @Synchronized
    fun resumeAi() { mode = ConversationMode.AI; waitingHumanNotified = false }

    @Synchronized
    fun recordAiFormalReply(save: () -> Unit): Boolean {
        if (mode != ConversationMode.AI || aiRepliesSent >= maxAiReplies().coerceIn(0, 1_000)) return false
        save()
        aiRepliesSent++
        return true
    }

    @Synchronized
    fun canGenerateAiFormalReply(): Boolean = mode == ConversationMode.AI && aiRepliesSent < maxAiReplies().coerceIn(0, 1_000)

    @Synchronized
    fun markWaitingHumanIfNeeded(): Boolean {
        if (mode != ConversationMode.AI || aiRepliesSent < maxAiReplies().coerceIn(0, 1_000) || waitingHumanNotified) return false
        waitingHumanNotified = true
        return true
    }
}

/** 有界消息 ID 集合，保证重试同一 message_id 时只处理一次正文。 */
class TunnelMessageDeduplicator(private val maxIds: Int = 10_000) {
    private val ids = LinkedHashMap<String, Unit>()

    @Synchronized
    fun accept(messageId: String): Boolean {
        if (ids.containsKey(messageId)) return false
        ids[messageId] = Unit
        while (ids.size > maxIds) ids.remove(ids.keys.first())
        return true
    }
}

/** SharedPreferences 只保存设置，不保存聊天正文和附件。 */
class TunnelChatSettingsStore(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences("tunnel-chat", Context.MODE_PRIVATE)

    @Synchronized
    fun read(): TunnelChatSettings {
        val raw = preferences.getString("settings", null)
        return if (raw == null) save(TunnelChatSettings()) else TunnelChatSettings.fromJson(JSONObject(raw))
    }

    @Synchronized
    fun save(settings: TunnelChatSettings, publishedOrigins: Set<String> = publishedOrigins()): TunnelChatSettings {
        val clean = settings.validate()
        check(preferences.edit().putString("settings", clean.toJson().toString())
            .putStringSet("publishedOrigins", publishedOrigins).commit()) { "穿透聊天设置保存失败。" }
        return clean
    }

    fun publishedOrigins(): Set<String> = preferences.getStringSet("publishedOrigins", null)?.toSet() ?: emptySet()

    fun lastAcceptedAt(): Long? = if (preferences.contains("lastAcceptedAt")) preferences.getLong("lastAcceptedAt", 0L) else null

    fun saveLastAcceptedAt(value: Long) {
        check(preferences.edit().putLong("lastAcceptedAt", value).commit()) { "穿透聊天限频状态保存失败。" }
    }
}
