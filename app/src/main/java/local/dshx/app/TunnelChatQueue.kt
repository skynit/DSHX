package local.dshx.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
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

/**
 * 文件队列只保存发送方尚未投递的消息。每次重试保留原 message_id/conversation_id，
 * 不通过创建新会话绕过端点限频。
 */
class TunnelChatQueueStore(
    private val file: File,
    private val cipher: TunnelChatQueueCipher,
    private val limits: () -> TunnelChatSettings,
) {
    private val lock = Any()

    fun enqueue(message: ChatMessage, targetEndpointId: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val config = limits().validate()
        val rows = readRows().filter { now - it.enqueuedAt <= config.queueMaxAgeMs }.toMutableList()
        if (rows.any { it.message.messageId == message.messageId }) return@synchronized true
        val next = TunnelQueuedMessage(message, now, targetEndpointId, config.platformOrigin)
        val bytes = rows.sumOf { it.message.toJson().toString().toByteArray(Charsets.UTF_8).size.toLong() } +
            message.toJson().toString().toByteArray(Charsets.UTF_8).size
        if (rows.size >= config.queueMaxMessages || bytes > config.queueMaxBytes) return@synchronized false
        rows += next
        writeRows(rows)
        true
    }

    fun due(now: Long = System.currentTimeMillis()): List<TunnelQueuedMessage> = synchronized(lock) {
        val config = limits().validate()
        val original = readRows()
        val rows = original.filter { now - it.enqueuedAt <= config.queueMaxAgeMs }
        if (rows.size != original.size) writeRows(rows)
        rows.filter { it.nextAttemptAt <= now }.sortedBy { it.enqueuedAt }
    }

    fun markAttempt(messageId: String, now: Long = System.currentTimeMillis(), delayMs: Long = 1_000L) = synchronized(lock) {
        val rows = readRows().map {
            if (it.message.messageId == messageId) it.copy(attempts = it.attempts + 1, nextAttemptAt = now + delayMs.coerceAtLeast(0)) else it
        }
        writeRows(rows)
    }

    fun retryDelayMs(attempts: Int): Long {
        require(attempts >= 0) { "离线消息重试次数无效。" }
        return (5_000L * (1L shl attempts.coerceAtMost(6))).coerceAtMost(5L * 60 * 1000)
    }

    fun acknowledge(ack: ChatAck, targetEndpointId: String) = synchronized(lock) {
        if (ack.status == ChatAck.Status.REJECTED) return@synchronized
        val config = limits()
        val rows = readRows()
        val remaining = rows.filterNot { it.message.messageId == ack.messageId && it.message.conversationId == ack.conversationId &&
            it.targetEndpointId == targetEndpointId && it.platformOrigin == config.platformOrigin && it.message.senderEndpointId == config.endpointId }
        if (remaining.size != rows.size) writeRows(remaining)
    }

    fun snapshot(): List<TunnelQueuedMessage> = synchronized(lock) { readRows() }

    private fun readRows(): List<TunnelQueuedMessage> {
        if (!file.isFile) return emptyList()
        if (file.length() == 0L) return emptyList()
        val plain = cipher.decrypt(Base64.getDecoder().decode(AtomicFile(file).readFully()))
        val rows = JSONArray(String(plain, Charsets.UTF_8))
        return (0 until rows.length()).map { TunnelQueuedMessage.fromJson(rows.getJSONObject(it)) }
    }

    private fun writeRows(rows: List<TunnelQueuedMessage>) {
        file.parentFile?.mkdirs()
        val value = JSONArray().apply { rows.forEach { put(it.toJson()) } }.toString().toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encode(cipher.encrypt(value))
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(encoded)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
