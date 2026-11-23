package local.dshx.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

internal val tunnelImageTypes = arrayOf("image/jpeg", "image/png", "image/webp")
internal val tunnelDocumentTypes = arrayOf("application/pdf", "text/plain", "text/markdown",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
internal val tunnelAttachmentTypes = tunnelImageTypes + tunnelDocumentTypes

internal fun ChatMessage.attachment(): ChatAttachment = ChatAttachment.fromJson(payload.getJSONObject("attachment")).also {
    require(kind.isAttachment() && (kind == ChatMessageKind.IMAGE) == (it.mimeType in tunnelImageTypes) && it.size > 0) {
        "附件类型或大小无效。"
    }
}

internal fun ContentResolver.attachmentName(uri: Uri): String? = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    ?.use { if (it.moveToFirst()) it.getString(0) else null }

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
private fun String.fileKey() = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8)).hex()

internal class TunnelAttachmentFiles(private val root: File) {
    private fun folder(chat: TunnelConversation) = File(root,
        JSONArray(listOf(chat.platformOrigin, chat.localEndpointId, chat.remoteEndpointId, chat.id)).toString().fileKey())

    private fun path(chat: TunnelConversation, message: ChatMessage, suffix: String) = File(folder(chat),
        JSONArray(listOf(message.senderEndpointId, message.messageId)).toString().fileKey() + suffix)

    fun file(chat: TunnelConversation, message: ChatMessage): File =
        path(chat, message, if (message.kind == ChatMessageKind.IMAGE) ".image" else ".file")

    fun staged(chat: TunnelConversation, message: ChatMessage): File = path(chat, message, ".part")

    fun prepare(input: InputStream, id: String, name: String? = null, mimeType: String? = null): Pair<File, ChatAttachment> {
        val document = mimeType?.lowercase()?.takeIf { it in tunnelDocumentTypes }
        require(document != null || mimeType == null || mimeType.lowercase() in tunnelImageTypes) { "附件类型不在聊天允许范围内。" }
        val max = if (document == null) TunnelChatProtocol.MAX_IMAGE_BYTES else TunnelChatProtocol.MAX_DOCUMENT_BYTES
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
                    require(size <= max) { "附件不能超过 ${max / (1024 * 1024)} MiB。" }
                    output.write(buffer, 0, count)
                    hash.update(buffer, 0, count)
                }
            }
            require(size > 0) { "不能发送空附件。" }
            val mime = document ?: checkNotNull(imageMime(temp)) { "请选择可读取的 JPG、PNG 或 WebP 图片。" }
            val suffix = if (mime == "image/jpeg") "jpg" else mime.substringAfter('/')
            val displayName = name ?: if (document == null) "图片.$suffix" else error("无法读取文件名，请重新选择。")
            return temp to ChatAttachment(id, displayName, mime, size, hash.digest().hex()).validate()
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    fun store(chat: TunnelConversation, message: ChatMessage, source: File): File {
        return move(source, file(chat, message))
    }

    fun stage(chat: TunnelConversation, message: ChatMessage, source: File): File {
        return move(source, staged(chat, message))
    }

    fun reserve(chat: TunnelConversation, size: Long): File {
        val parent = folder(chat)
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

    private fun move(source: File, target: File): File {
        if (source.parentFile != target.parentFile) checkSpace(target.parentFile!!, source.length())
        target.parentFile?.mkdirs()
        check(!target.exists() && source.renameTo(target)) { "无法保存附件。" }
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
            files.size < 2048 && files.sumOf { it.length() } + size <= 256L * 1024 * 1024) { "穿透聊天附件存储已满。" }
    }

    companion object {
        const val MAX_AGE = 7L * 24 * 60 * 60 * 1000

        fun matches(file: File, attachment: ChatAttachment): Boolean {
            if (file.length() != attachment.size) return false
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            return hash.digest().hex() == attachment.sha256 &&
                (attachment.mimeType in tunnelDocumentTypes || imageMime(file) == attachment.mimeType)
        }

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
