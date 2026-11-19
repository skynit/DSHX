package local.dshx.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 发送方离线队列使用独立密钥加密，平台不参与消息暂存。 */
interface TunnelChatQueueCipher {
    fun encrypt(value: ByteArray): ByteArray
    fun decrypt(value: ByteArray): ByteArray
}

/** Android Keystore-backed AES-GCM cipher. 仅在 Android 运行时创建。 */
class AndroidTunnelChatQueueCipher(private val alias: String = "dshx.tunnel-chat.queue") : TunnelChatQueueCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
                generateKey()
            }
        }
        return (store.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }

    override fun encrypt(value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(value)
    }

    override fun decrypt(value: ByteArray): ByteArray {
        require(value.size > 12) { "离线队列密文无效。" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, 12))) }
        return cipher.doFinal(value.copyOfRange(12, value.size))
    }
}

/** 测试用密文实现；产品运行时由 AndroidTunnelChatQueueCipher 替代。 */
class FixedTunnelChatQueueCipher(private val secret: ByteArray) : TunnelChatQueueCipher {
    private val key = javax.crypto.spec.SecretKeySpec(secret.copyOf(32), "AES")

    override fun encrypt(value: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + cipher.doFinal(value)
    }

    override fun decrypt(value: ByteArray): ByteArray {
        require(value.size > 12) { "离线队列密文无效。" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(0, 12))) }
        return cipher.doFinal(value.copyOfRange(12, value.size))
    }
}

data class TunnelQueuedMessage(
    val message: ChatMessage,
    val enqueuedAt: Long,
    val targetEndpointId: String,
    val platformOrigin: String,
    val attempts: Int = 0,
    val nextAttemptAt: Long = enqueuedAt,
) {
    fun belongsTo(conversation: TunnelConversation) =
        message.conversationId == conversation.id && message.senderEndpointId == conversation.localEndpointId &&
            platformOrigin == conversation.platformOrigin && targetEndpointId == conversation.remoteEndpointId

    fun toJson(): JSONObject = JSONObject()
        .put("message", message.toJson())
        .put("enqueuedAt", enqueuedAt)
        .put("targetEndpointId", targetEndpointId)
        .put("platformOrigin", platformOrigin)
        .put("attempts", attempts)
        .put("nextAttemptAt", nextAttemptAt)

    companion object {
        fun fromJson(value: JSONObject): TunnelQueuedMessage = TunnelQueuedMessage(
            message = ChatMessage.fromJson(value.getJSONObject("message")),
            enqueuedAt = value.getLong("enqueuedAt"),
            targetEndpointId = value.getString("targetEndpointId"),
            platformOrigin = value.getString("platformOrigin"),
            attempts = value.optInt("attempts", 0).also { require(it >= 0) { "离线消息重试次数无效。" } },
            nextAttemptAt = value.optLong("nextAttemptAt", value.getLong("enqueuedAt")),
        )
    }
}

data class TunnelConversation(
    val id: String,
    val platformOrigin: String,
    val localEndpointId: String,
    val remoteEndpointId: String,
    val visitor: Boolean,
    val verified: Boolean = false,
    val mode: ConversationMode = ConversationMode.AI,
    val aiRepliesSent: Int = 0,
    val closed: Boolean = false,
    val sequence: Long = 0,
    val waitingNotified: Boolean = false,
    val receivedMessageIds: List<String> = emptyList(),
    val receptionVersion: Long = 0,
    val inbox: List<TunnelReceipt> = emptyList(),
    val peerEndId: String? = null,
    val closedAt: Long = 0,
) {
    fun toJson() = JSONObject().put("id", id).put("origin", platformOrigin).put("local", localEndpointId)
        .put("remote", remoteEndpointId).put("visitor", visitor).put("verified", verified)
        .put("mode", mode.name).put("replies", aiRepliesSent).put("closed", closed).put("seq", sequence)
        .put("waitingNotified", waitingNotified).put("receivedMessageIds", JSONArray(receivedMessageIds))
        .put("receptionVersion", receptionVersion).put("inbox", JSONArray(inbox.map { it.toJson() }))
        .put("peerEndId", peerEndId)
        .put("closedAt", closedAt)

    fun canReply(limit: Int) = verified && !visitor && !closed && mode == ConversationMode.AI && aiRepliesSent < limit

    fun canPublish(receipt: TunnelReceipt, limit: Int) =
        canReply(limit) && receipt.pending && receipt.receptionVersion == receptionVersion

    fun handover() = if (mode == ConversationMode.HUMAN_HANDOVER) this
        else copy(mode = ConversationMode.HUMAN_HANDOVER, receptionVersion = receptionVersion + 1)

    fun resumeAi() = if (mode == ConversationMode.AI) this
        else copy(mode = ConversationMode.AI, waitingNotified = false, receptionVersion = receptionVersion + 1)

    fun needsInitialOffer(recovering: Boolean) = !verified || (visitor && !closed && recovering)

    companion object {
        /** 只有原访客能以首次协商格式发起新会话；已绑定的对端每次仍须验签。 */
        fun acceptsOffer(previous: TunnelConversation?, source: String, freshLink: Boolean): Boolean = when {
            previous == null -> !freshLink
            previous.remoteEndpointId != source -> false
            previous.closed -> freshLink && previous.verified
            else -> freshLink || !previous.visitor
        }

        fun fromJson(row: JSONObject) = TunnelConversation(
            row.getString("id"), row.getString("origin"), row.getString("local"), row.getString("remote"),
            row.getBoolean("visitor"), row.getBoolean("verified"), ConversationMode.valueOf(row.getString("mode")),
            row.getInt("replies"), row.getBoolean("closed"), row.getLong("seq"),
            row.optBoolean("waitingNotified"),
            row.optJSONArray("receivedMessageIds")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty(),
            row.optLong("receptionVersion"),
            row.optJSONArray("inbox")?.let { array -> (0 until array.length()).map { TunnelReceipt.fromJson(array.getJSONObject(it)) } }.orEmpty(),
            row.optString("peerEndId").takeIf { it.isNotEmpty() },
            row.optLong("closedAt"),
        )
    }
}

/** 正文可压缩为仅用于去重的收件凭据；尚未完成接待的正文不得压缩。 */
data class TunnelReceipt(
    val messageId: String,
    val receivedAt: Long,
    val message: ChatMessage?,
    val pending: Boolean,
    val receptionVersion: Long,
) {
    val bodyBytes = message?.toJson()?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0

    fun toJson() = JSONObject().put("id", messageId).put("at", receivedAt).put("message", message?.toJson())
        .put("pending", pending).put("receptionVersion", receptionVersion)

    companion object {
        fun fromJson(row: JSONObject) = TunnelReceipt(row.getString("id"), row.getLong("at"),
            row.optJSONObject("message")?.let(ChatMessage::fromJson), row.getBoolean("pending"), row.getLong("receptionVersion"))
    }
}

/** 收件、待发消息与接待状态共用一次加密写入。一个运行时是此文件的唯一写入者。 */
class TunnelChatQueueStore(
    private val file: File,
    private val cipher: TunnelChatQueueCipher,
    private val limits: () -> TunnelChatSettings,
) {
    private val lock = Any()
    private var cached: Data? = null

    fun canFit(message: ChatMessage): Boolean = messageBytes(message) <=
        minOf(limits().queueMaxBytes, TunnelChatProtocol.MAX_MESSAGE_BYTES.toLong())

    private fun messageBytes(message: ChatMessage) = message.toJson().toString().toByteArray(Charsets.UTF_8).size.toLong()

    fun enqueue(message: ChatMessage, targetEndpointId: String, now: Long = System.currentTimeMillis(),
        conversation: TunnelConversation? = null, receptionId: String? = null): Boolean = synchronized(lock) {
        val config = limits().validate()
        val data = readData(now)
        val previous = conversation?.let { data.conversation(it.id, it.platformOrigin, it.localEndpointId) }
        if (conversation != null && previous == null && isRetired(conversation.id, conversation.platformOrigin, conversation.localEndpointId)) return@synchronized false
        val handover = message.kind == ChatMessageKind.HUMAN_FORMAL && conversation?.mode == ConversationMode.HUMAN_HANDOVER
        var nextConversation = conversation?.let { if (previous == null) it else it.copy(inbox = previous.inbox) }
        if (receptionId != null) {
            val receipt = previous?.inbox?.firstOrNull { it.messageId == receptionId }
            if (previous == null || receipt == null || !previous.canPublish(receipt, config.maxAiReplies)) return@synchronized false
            nextConversation = checkNotNull(nextConversation).finishReception(receptionId)
        }
        val rows = data.normalRows(config, now).filterNot { row -> conversation != null && row.belongsTo(conversation) &&
            (conversation.closed || handover && row.message.kind == ChatMessageKind.AI_FORMAL) }
        if (rows.any { it.message.messageId == message.messageId && it.targetEndpointId == targetEndpointId &&
                it.platformOrigin == config.platformOrigin && it.message.senderEndpointId == message.senderEndpointId }) return@synchronized true
        val next = TunnelQueuedMessage(message, now, targetEndpointId, config.platformOrigin)
        val bytes = rows.sumOf { messageBytes(it.message) } + messageBytes(message)
        if (rows.size >= config.queueMaxMessages || bytes > config.queueMaxBytes) return@synchronized false
        writeData(data.copy(rows = rows + next).withConversation(nextConversation?.closeReception(now)))
        true
    }

    fun receive(conversation: TunnelConversation, message: ChatMessage, now: Long = System.currentTimeMillis()): ChatAck.Status = synchronized(lock) {
        val data = readData(now)
        val live = data.conversation(conversation.id, conversation.platformOrigin, conversation.localEndpointId)
        if (live == null || !live.verified || message.conversationId != live.id || message.senderEndpointId != live.remoteEndpointId ||
            (live.closed && !message.isEnd())) return@synchronized ChatAck.Status.REJECTED
        if (message.messageId == live.peerEndId || message.messageId in live.receivedMessageIds || live.inbox.any { it.messageId == message.messageId }) {
            return@synchronized ChatAck.Status.DUPLICATE
        }
        val ending = message.isEnd()
        if (ending && live.peerEndId != null) return@synchronized ChatAck.Status.REJECTED
        val receipt = TunnelReceipt(message.messageId, now, message,
            !live.visitor && message.kind == ChatMessageKind.TEXT && message.payload.optString("text").isNotBlank(), live.receptionVersion)
        val nextConversation = live.copy(
            closed = live.closed || ending,
            peerEndId = if (ending) message.messageId else live.peerEndId,
            mode = if (live.visitor && message.kind == ChatMessageKind.HUMAN_FORMAL) ConversationMode.HUMAN_HANDOVER else live.mode,
            inbox = (live.inbox + receipt).let { rows ->
                rows.dropLast(MAX_HISTORY_PER_CHAT).map { if (!it.pending && it.message != null) it.copy(message = null) else it } +
                    rows.takeLast(MAX_HISTORY_PER_CHAT)
            },
        ).closeReception(now)
        val next = data.withConversation(nextConversation).withoutClosedMessages(nextConversation).compact(now, receipt)
        // 每个会话额外保留一个结束通知，不让普通收件记录占满关闭通道。
        if (!ending && (next.receiptCount > MAX_RECEIPTS || next.pendingBodyBytes > MAX_PENDING_BYTES)) return@synchronized ChatAck.Status.REJECTED
        writeData(next)
        ChatAck.Status.RECEIVED
    }

    fun finishReception(conversation: TunnelConversation, messageId: String) = synchronized(lock) {
        val data = readData()
        val live = data.conversation(conversation.id, conversation.platformOrigin, conversation.localEndpointId) ?: return@synchronized
        if (live.inbox.any { it.messageId == messageId && it.pending }) {
            writeData(data.withConversation(live.finishReception(messageId)))
        }
    }

    fun due(now: Long = System.currentTimeMillis()): List<TunnelQueuedMessage> = synchronized(lock) {
        val data = readData(now)
        val rows = data.normalRows(limits().validate(), now)
        val next = data.compact(now)
        if (next !== data || rows.size != data.rows.size) writeData(next.copy(rows = rows))
        rows.filter { it.nextAttemptAt <= now }.sortedBy { it.enqueuedAt }
    }

    fun markAttempts(attempted: List<TunnelQueuedMessage>, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (attempted.isEmpty()) return@synchronized
        val selected = attempted.map { it.key() }.toSet()
        val data = readData(now)
        var changed = false
        val rows = data.rows.map { row ->
            if (row.key() !in selected) row else {
                changed = true
                row.copy(attempts = row.attempts + 1, nextAttemptAt = now + retryDelayMs(row.attempts))
            }
        }
        if (changed) writeData(data.copy(rows = rows))
    }

    private fun retryDelayMs(attempts: Int): Long {
        require(attempts >= 0) { "离线消息重试次数无效。" }
        return (5_000L * (1L shl attempts.coerceAtMost(6))).coerceAtMost(5L * 60 * 1000)
    }

    fun acknowledge(ack: ChatAck, targetEndpointId: String) = synchronized(lock) {
        if (ack.status == ChatAck.Status.REJECTED) return@synchronized
        val config = limits()
        val data = readData()
        val rows = data.rows
        val remaining = rows.filterNot { it.message.messageId == ack.messageId && it.message.conversationId == ack.conversationId &&
            it.targetEndpointId == targetEndpointId && it.platformOrigin == config.platformOrigin && it.message.senderEndpointId == config.endpointId }
        if (remaining.size != rows.size) writeData(data.copy(rows = remaining))
    }

    fun snapshot(): List<TunnelQueuedMessage> = synchronized(lock) { readData().rows }
    fun conversations(): List<TunnelConversation> = synchronized(lock) { readData().conversations }
    fun conversation(id: String, origin: String, local: String): TunnelConversation? = synchronized(lock) { readData().conversation(id, origin, local) }

    fun isRetired(id: String, origin: String, local: String): Boolean = synchronized(lock) {
        !Files.notExists(retirementFile(id, origin, local))
    }

    fun retireClosed(protectedIds: Set<String>, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val data = readData(now)
        val candidates = data.conversations.filter { it.closed && it.closedAt > 0 && now - it.closedAt > RECEIPT_AGE_MS &&
            it.id !in protectedIds && it.inbox.isEmpty() && it.receivedMessageIds.isEmpty() && data.rows.none { row -> row.belongsTo(it) } }
        if (candidates.isEmpty()) return@synchronized
        // 先落不可重开的标记，再移除热队列记录；中断至多留下两份关闭证据。
        candidates.forEach {
            val marker = retirementFile(it.id, it.platformOrigin, it.localEndpointId)
            Files.createDirectories(marker.parent)
            try { Files.createFile(marker) } catch (_: java.nio.file.FileAlreadyExistsException) { /* 已归档 */ }
        }
        writeData(data.copy(conversations = data.conversations - candidates.toSet()))
    }

    fun save(conversation: TunnelConversation, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val data = readData()
        require(data.conversation(conversation.id, conversation.platformOrigin, conversation.localEndpointId) != null ||
            !isRetired(conversation.id, conversation.platformOrigin, conversation.localEndpointId)) { "聊天会话已结束。" }
        writeData(data.withConversation(conversation.closeReception(now)).withoutClosedMessages(conversation))
    }

    private fun TunnelQueuedMessage.key() = listOf(platformOrigin, message.senderEndpointId, targetEndpointId, message.conversationId, message.messageId)

    private fun TunnelConversation.finishReception(messageId: String) =
        copy(inbox = inbox.map { if (it.messageId == messageId) it.copy(pending = false) else it })

    private fun TunnelConversation.closeReception(now: Long): TunnelConversation = if (!closed) this
        else copy(closedAt = closedAt.takeIf { it > 0 } ?: now, inbox = inbox.map { if (it.pending) it.copy(pending = false) else it })

    private fun retirementFile(id: String, origin: String, local: String): java.nio.file.Path {
        val key = JSONArray(listOf(origin, local, id)).toString().toByteArray(Charsets.UTF_8)
        val digest = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        return File(file.absoluteFile.parentFile, "retired/${digest.take(2)}/${digest.drop(2)}").toPath()
    }

    private data class Data(val rows: List<TunnelQueuedMessage>, val conversations: List<TunnelConversation>, val legacyIdsUntil: Long = 0) {
        private fun TunnelConversation.key() = Triple(platformOrigin, localEndpointId, id)
        private val index by lazy { conversations.associateBy { it.key() } }
        val receiptCount by lazy { conversations.sumOf { it.inbox.size } }
        val pendingBodyBytes by lazy { conversations.sumOf { chat -> chat.inbox.sumOf { if (it.pending) it.bodyBytes.toLong() else 0L } } }
        private val bodyBytes by lazy { conversations.sumOf { chat -> chat.inbox.sumOf { it.bodyBytes.toLong() } } }
        private val oldestFinished by lazy { conversations.minOfOrNull { chat ->
            chat.inbox.minOfOrNull { if (it.pending) Long.MAX_VALUE else it.receivedAt } ?: Long.MAX_VALUE
        } ?: Long.MAX_VALUE }

        fun conversation(id: String, origin: String, local: String) = index[Triple(origin, local, id)]

        fun normalRows(config: TunnelChatSettings, now: Long) = rows.filter { now - it.enqueuedAt <= config.queueMaxAgeMs }

        fun withoutClosedMessages(chat: TunnelConversation) =
            if (chat.closed) copy(rows = rows.filterNot { it.belongsTo(chat) && !it.message.isEnd() }) else this

        fun withConversation(next: TunnelConversation?): Data {
            if (next == null) return this
            val key = next.key()
            require(key in index || conversations.size < MAX_CONVERSATIONS) { "本地聊天会话记录已满。" }
            return copy(conversations = conversations.filterNot { it.key() == key } + next)
        }

        fun compact(now: Long, newest: TunnelReceipt? = null): Data {
            val expireLegacy = legacyIdsUntil != 0L && now >= legacyIdsUntil
            val cutoff = now - RECEIPT_AGE_MS
            if (!expireLegacy && oldestFinished >= cutoff && bodyBytes <= MAX_HISTORY_BYTES) return this
            var changed = expireLegacy
            var historyBytes = pendingBodyBytes + (newest?.takeUnless { it.pending }?.bodyBytes ?: 0)
            // 保留最新正文，已处理的旧正文只保留凭据。未完成接待、会话绑定与关闭标记不淘汰。
            val keep = mutableSetOf<Pair<Triple<String, String, String>, String>>()
            conversations.flatMap { chat -> chat.inbox.map { chat.key() to it } }
                .filter { (_, receipt) -> receipt !== newest && !receipt.pending && receipt.message != null && receipt.receivedAt >= cutoff }
                .sortedByDescending { (_, receipt) -> receipt.receivedAt }
                .forEach { (key, receipt) ->
                    if (historyBytes + receipt.bodyBytes <= MAX_HISTORY_BYTES) {
                        historyBytes += receipt.bodyBytes
                        keep += key to receipt.messageId
                    }
                }
            val compacted = conversations.map { chat ->
                val key = chat.key()
                val inbox = chat.inbox.mapNotNull { receipt ->
                    when {
                        receipt === newest || receipt.pending -> receipt
                        receipt.receivedAt < cutoff -> { changed = true; null }
                        receipt.message != null && (key to receipt.messageId) !in keep -> { changed = true; receipt.copy(message = null) }
                        else -> receipt
                    }
                }
                if (inbox == chat.inbox && (!expireLegacy || chat.receivedMessageIds.isEmpty())) chat
                else chat.copy(inbox = inbox, receivedMessageIds = if (expireLegacy) emptyList() else chat.receivedMessageIds)
            }
            return if (changed) copy(conversations = compacted, legacyIdsUntil = if (expireLegacy) 0 else legacyIdsUntil) else this
        }
    }

    private fun readData(now: Long = System.currentTimeMillis()): Data = cached ?: loadData(now).also { cached = it }

    private fun loadData(now: Long): Data {
        val encoded = try { AtomicFile(file).readFully() } catch (error: FileNotFoundException) {
            if (file.exists() || File("${file.path}.bak").exists()) throw error
            return Data(emptyList(), emptyList())
        }
        if (encoded.isEmpty()) return Data(emptyList(), emptyList())
        val json = String(cipher.decrypt(Base64.getDecoder().decode(encoded)), Charsets.UTF_8)
        val value = if (json.startsWith('[')) JSONObject().put("rows", JSONArray(json)) else JSONObject(json)
        val rows = value.getJSONArray("rows")
        val conversations = value.optJSONArray("conversations") ?: JSONArray()
        val chats = (0 until conversations.length()).map { TunnelConversation.fromJson(conversations.getJSONObject(it)) }
            .map { if (it.closed && it.closedAt == 0L) it.copy(closedAt = now) else it }
        val legacyIdsUntil = value.optLong("legacyIdsUntil", if (chats.any { it.receivedMessageIds.isNotEmpty() }) now + RECEIPT_AGE_MS else 0)
        return Data((0 until rows.length()).map { TunnelQueuedMessage.fromJson(rows.getJSONObject(it)) }, chats, legacyIdsUntil)
    }

    private fun writeData(data: Data) {
        val parent = file.absoluteFile.parentFile!!
        Files.createDirectories(parent.toPath())
        val value = JSONObject()
            .put("rows", JSONArray(data.rows.map { it.toJson() }))
            .put("conversations", JSONArray(data.conversations.map { it.toJson() }))
            .put("legacyIdsUntil", data.legacyIdsUntil)
            .toString().toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encode(cipher.encrypt(value))
        // AtomicFile.finishWrite 不抛出同步或重命名错误；收件确认需要知道写入成功。
        val temp = File(parent, "${file.name}.pending").toPath()
        try {
            FileOutputStream(temp.toFile()).use { output -> output.write(encoded); output.fd.sync() }
            Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            cached = data
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private companion object {
        const val MAX_CONVERSATIONS = 10_000
        const val MAX_RECEIPTS = 100_000
        const val MAX_HISTORY_PER_CHAT = 200
        const val MAX_HISTORY_BYTES = 8L * 1024 * 1024
        const val MAX_PENDING_BYTES = MAX_HISTORY_BYTES - TunnelChatProtocol.MAX_MESSAGE_BYTES
        const val RECEIPT_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 对端允许配置的最长待发保存期限。
    }
}
