package local.dshx.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

internal val tunnelImageTypes = arrayOf("image/jpeg", "image/png", "image/webp")

internal fun ChatMessage.imageAttachment(): ChatAttachment = ChatAttachment.fromJson(payload.getJSONObject("attachment")).also {
    require(it.mimeType in tunnelImageTypes && it.size > 0) { "图片格式或大小不受支持。" }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
private fun String.fileKey() = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8)).hex()

/** 每条已验签连接双向各一张；接收方每写完一组才请求下一组。 */
internal class TunnelImageTransfer(
    private val send: (JSONObject) -> Boolean,
    private val received: (ChatMessage, File) -> Boolean,
    private val sent: (ChatMessage, Boolean) -> Unit,
) {
    private class Incoming(val message: ChatMessage, val file: File, val size: Long) {
        val hash: MessageDigest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        var touched = System.nanoTime()
    }

    private class Outgoing(val message: ChatMessage, val file: File, val size: Long) {
        var offset = 0L
        var touched = System.nanoTime()
    }

    private var incoming: Incoming? = null
    private var outgoing: Outgoing? = null
    val sending: Boolean get() = outgoing != null
    val receiving: Boolean get() = incoming != null

    fun start(message: ChatMessage, source: File) {
        check(!sending) { "上一张图片还在发送。" }
        val size = message.imageAttachment().size
        require(source.length() == size) { "图片内容不可用。" }
        outgoing = Outgoing(message, source, size)
        if (!transmit(message.toJson())) finishSend(false)
    }

    fun accept(message: ChatMessage, target: File) {
        check(!receiving)
        val size = message.imageAttachment().size
        incoming = Incoming(message, target, size)
        if (!transmit(frame("image_next", message).put("offset", 0L))) discardIncoming()
    }

    fun reject(message: ChatMessage) { transmit(frame("image_failed", message)) }

    fun confirm(message: ChatMessage) { transmit(frame("image_done", message)) }

    fun handle(value: JSONObject): Boolean {
        val id = value.optString("messageId")
        when (TunnelChatProtocol.frameType(value)) {
            "image_chunk" -> incoming?.takeIf { it.message.messageId == id }?.let { writeChunk(it, value) }
            "image_next" -> outgoing?.takeIf { it.message.messageId == id }?.let { writeWindow(it, value.optLong("offset", -1)) }
            "image_failed" -> if (outgoing?.message?.messageId == id) finishSend(false)
            "image_done" -> if (outgoing?.message?.messageId == id) finishSend(true)
            else -> return false
        }
        return true
    }

    fun expire(now: Long = System.nanoTime()) {
        incoming?.takeIf { now - it.touched >= TIMEOUT }?.let { failIncoming(it.message) }
        outgoing?.takeIf { now - it.touched >= TIMEOUT }?.let { finishSend(false) }
    }

    fun close() {
        discardIncoming()
        finishSend(false)
    }

    private fun writeWindow(transfer: Outgoing, offset: Long) {
        if (offset != transfer.offset || offset >= transfer.size) return
        transfer.touched = System.nanoTime()
        try {
            RandomAccessFile(transfer.file, "r").use { input ->
                input.seek(offset)
                repeat(WINDOW_CHUNKS) {
                    val size = minOf(transfer.size - transfer.offset, CHUNK_BYTES.toLong()).toInt()
                    if (size == 0) return
                    val bytes = ByteArray(size).also { input.readFully(it) }
                    if (!transmit(frame("image_chunk", transfer.message).put("offset", transfer.offset)
                            .put("data", Base64.getUrlEncoder().encodeToString(bytes)))) {
                        finishSend(false)
                        return
                    }
                    transfer.offset += size
                }
            }
        } catch (_: IOException) { finishSend(false) }
    }

    private fun writeChunk(transfer: Incoming, value: JSONObject) {
        val message = transfer.message
        val success = runCatching {
            val data = value.getString("data")
            val size = minOf(transfer.size - transfer.offset, CHUNK_BYTES.toLong()).toInt()
            require(value.getLong("offset") == transfer.offset && data.length == (size + 2) / 3 * 4)
            val bytes = Base64.getUrlDecoder().decode(data)
            require(bytes.size == size)
            RandomAccessFile(transfer.file, "rw").use { output ->
                output.seek(transfer.offset)
                output.write(bytes)
                if (transfer.offset + size == transfer.size) output.fd.sync()
            }
            transfer.hash.update(bytes)
            transfer.offset += size
            transfer.touched = System.nanoTime()
            when {
                transfer.offset == transfer.size -> {
                    check(transfer.hash.digest().hex() == message.imageAttachment().sha256 && received(message, transfer.file))
                    discardIncoming()
                    confirm(message)
                }
                transfer.offset % (CHUNK_BYTES * WINDOW_CHUNKS) == 0L ->
                    check(transmit(frame("image_next", message).put("offset", transfer.offset)))
                else -> Unit
            }
        }.isSuccess
        if (!success) failIncoming(message)
    }

    private fun failIncoming(message: ChatMessage) {
        discardIncoming()
        reject(message)
    }

    private fun discardIncoming() {
        incoming?.file?.delete()
        incoming = null
    }

    private fun finishSend(success: Boolean) {
        val message = outgoing?.message ?: return
        outgoing = null
        sent(message, success)
    }

    private fun frame(type: String, message: ChatMessage) = JSONObject()
        .put("protocol", TunnelChatProtocol.CHAT).put("type", type).put("messageId", message.messageId)

    private fun transmit(frame: JSONObject): Boolean = try { send(frame) } catch (_: RuntimeException) { false }

    private companion object {
        // Base64 和 JSON 字段计入帧长度，仍低于 16 KiB。
        const val CHUNK_BYTES = 11 * 1024
        const val WINDOW_CHUNKS = 12
        val TIMEOUT: Long = TimeUnit.SECONDS.toNanos(30)
    }
}

/** 只用本地会话和消息的摘要作路径，不使用对端提供的文件名。 */
internal class TunnelImageFiles(private val root: File) {
    private fun folder(chat: TunnelConversation) = File(root,
        JSONArray(listOf(chat.platformOrigin, chat.localEndpointId, chat.remoteEndpointId, chat.id)).toString().fileKey())

    private fun path(chat: TunnelConversation, message: ChatMessage, suffix: String) = File(folder(chat),
        JSONArray(listOf(message.senderEndpointId, message.messageId)).toString().fileKey() + suffix)

    fun file(chat: TunnelConversation, message: ChatMessage): File = path(chat, message, ".image")

    fun staged(chat: TunnelConversation, message: ChatMessage): File = path(chat, message, ".part")

    fun prepare(input: InputStream, id: String): Pair<File, ChatAttachment> {
        val temp = File.createTempFile("pick-", ".part", root.apply { mkdirs() })
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var size = 0L
            temp.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= TunnelChatProtocol.MAX_IMAGE_BYTES) { "图片不能超过 10 MB。" }
                    output.write(buffer, 0, count)
                    hash.update(buffer, 0, count)
                }
            }
            val mime = checkNotNull(imageMime(temp)) { "请选择可读取的 JPG、PNG 或 WebP 图片。" }
            val suffix = if (mime == "image/jpeg") "jpg" else mime.substringAfter('/')
            return temp to ChatAttachment(id, "图片.$suffix", mime, size, hash.digest().hex()).validate()
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    fun reserve(chat: TunnelConversation, message: ChatMessage): File {
        val parent = folder(chat)
        val size = message.imageAttachment().size
        checkSpace(parent, size)
        val part = File.createTempFile("receive-", ".part", parent.apply { mkdirs() })
        return try {
            RandomAccessFile(part, "rw").use { it.setLength(size) }
            part
        } catch (error: Exception) {
            part.delete()
            throw error
        }
    }

    fun store(chat: TunnelConversation, message: ChatMessage, source: File): File {
        return move(source, file(chat, message))
    }

    fun stage(chat: TunnelConversation, message: ChatMessage, source: File): File {
        return move(source, staged(chat, message))
    }

    private fun move(source: File, target: File): File {
        if (source.parentFile != target.parentFile) checkSpace(target.parentFile!!, source.length())
        target.parentFile?.mkdirs()
        check(!target.exists() && source.renameTo(target)) { "无法保存图片。" }
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    fun trim(now: Long = System.currentTimeMillis(), discardParts: Boolean = false) {
        fun stale(file: File) = now - file.lastModified() >= MAX_AGE || (discardParts && file.extension == "part")
        root.listFiles()?.forEach { entry ->
            if (entry.isDirectory) {
                entry.listFiles()?.forEach { if (stale(it)) it.delete() }
                entry.delete() // 仅在目录已经为空时成功。
            } else if (stale(entry)) entry.delete()
        }
    }

    private fun checkSpace(folder: File, size: Long) {
        trim()
        val files = root.listFiles().orEmpty().filter { it.isDirectory }.flatMap { it.listFiles().orEmpty().asList() }
        check(files.filter { it.parentFile == folder }.sumOf { it.length() } + size <= TunnelChatProtocol.MAX_CONVERSATION_ATTACHMENT_BYTES &&
            files.size < 2048 && files.sumOf { it.length() } + size <= 256L * 1024 * 1024) { "穿透聊天图片存储已满。" }
    }

    companion object {
        const val MAX_AGE = 7L * 24 * 60 * 60 * 1000

        fun imageMime(file: File): String? {
            val bounds = bounds(file)
            val mime = bounds.outMimeType?.takeIf { it in tunnelImageTypes } ?: return null
            val bitmap = preview(file, bounds) ?: return null
            bitmap.recycle()
            return mime
        }

        fun preview(file: File): Bitmap? = preview(file, bounds(file))

        private fun bounds(file: File) = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.path, this)
        }

        private fun preview(file: File, bounds: BitmapFactory.Options): Bitmap? {
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0 || width.toLong() * height > 64_000_000L) return null
            var sample = 1
            while (maxOf(width, height) / sample > 512) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
