package local.dshx.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** workspace: 是应用工作区，shared: 是用户在系统文件选择器授权的目录。 */
internal class PhoneFiles(private val context: Context) {
    private val settings = context.getSharedPreferences("phone", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver
    private val workspace get() = File(context.filesDir, "workspace")
    private val jobs = Any()
    private var active = 0
    private var grantVersion = 0L
    private val releases = mutableSetOf<Uri>()
    private fun releaseWhenIdle(uri: Uri) {
        synchronized(jobs) { if (active > 0) releases.add(uri) else release(uri) }
    }

    fun directory(): String = settings.getString("directory", "").orEmpty()
    fun requestGrant(): Long = synchronized(jobs) { ++grantVersion }
    fun grant(uri: Uri, flags: Int, version: Long) = synchronized(jobs) {
        if (version != grantVersion) throw PhoneFailure("cancelled", "目录授权请求已撤销")
        val provider = context.packageManager.resolveContentProvider(uri.authority.orEmpty(), 0)
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri) || provider == null || provider.applicationInfo.uid == context.applicationInfo.uid) {
            throw PhoneFailure("invalid_directory", "请选择外部文件提供程序中的目录")
        }
        val grant = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (grant and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) throw PhoneFailure("permission_required", "目录需要读取授权")
        if (!settings.getBoolean("files", false)) throw PhoneFailure("capability_disabled", "文件能力已关闭")
        resolver.takePersistableUriPermission(uri, grant)
        releases.remove(uri)
        val old = directory()
        settings.edit().putString("directory", uri.toString()).apply()
        if (old.isNotEmpty() && old != uri.toString()) releaseWhenIdle(Uri.parse(old))
    }
    fun revoke() = synchronized(jobs) {
        grantVersion++
        val old = directory()
        settings.edit().remove("directory").apply()
        if (old.isNotEmpty()) releaseWhenIdle(Uri.parse(old))
    }
    private fun release(uri: Uri) {
        resolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let {
            val flags = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { resolver.releasePersistableUriPermission(uri, flags) }
        }
    }
    fun ready(write: Boolean = false): Boolean = resolver.persistedUriPermissions.any {
        it.uri.toString() == directory() && it.isReadPermission && (!write || it.isWritePermission)
    }

    /** 只允许后台 I/O；取消会关闭本次文件并取消提供程序查询。 */
    fun execute(method: String, args: JSONObject, check: () -> Unit, dispatch: () -> Unit, finish: (JSONObject) -> Unit): () -> Unit {
        val closed = AtomicBoolean(false)
        val stream = AtomicReference<Closeable?>()
        val signal = CancellationSignal()
        fun current() { if (closed.get()) throw PhoneFailure("cancelled", "文件请求已取消"); check() }
        synchronized(jobs) { active++ }
        thread(name = "phone-files", isDaemon = true) {
            try {
                current()
                val result = when (method) {
                    "read_file" -> read(args.getString("path"), ::current, stream, signal)
                    "share" -> share(args.getString("path"), ::current, stream)
                    else -> export(args.getString("path"), args.optString("name"), ::current, dispatch, stream, signal)
                }
                current(); finish(result)
            } catch (error: Exception) { finish(PhoneControl.errorResult(error)) }
            finally {
                runCatching { stream.getAndSet(null)?.close() }
                synchronized(jobs) {
                    active--
                    if (active == 0) { releases.filter { it.toString() != directory() }.forEach(::release); releases.clear() }
                }
            }
        }
        return {
            closed.set(true)
            thread(name = "phone-files-cancel", isDaemon = true) { signal.cancel(); runCatching { stream.getAndSet(null)?.close() } }
            Unit
        }
    }

    private fun relative(path: String): List<String> {
        val base = workspace.absolutePath
        val value = if (path.startsWith("workspace:")) path.removePrefix("workspace:")
            else if (path == base) "" else if (path.startsWith("$base/")) path.removePrefix("$base/")
            else throw PhoneFailure("path_denied", "文件必须位于 workspace: 工作区")
        return parts(value)
    }
    private fun descriptor(path: String, directory: Boolean = false): ParcelFileDescriptor {
        val flags = OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK
        val raw = try { Os.open(path, flags, 0) } catch (error: android.system.ErrnoException) {
            if (error.errno == OsConstants.ELOOP || error.errno == OsConstants.ENOTDIR) throw PhoneFailure("path_denied", "不允许符号链接或非目录父项")
            throw PhoneFailure("file_unavailable", "工作区文件不存在或不可读取")
        }
        return try {
            if (directory && !OsConstants.S_ISDIR(Os.fstat(raw).st_mode)) throw PhoneFailure("path_denied", "路径中的父项必须是目录")
            ParcelFileDescriptor.dup(raw)
        } finally { Os.close(raw) }
    }
    /** 逐级持有目录句柄再打开子项，防止检查后父目录被替换成符号链接。 */
    private fun open(path: String): ParcelFileDescriptor {
        val names = relative(path)
        var selected = descriptor(workspace.absolutePath, directory = true)
        try {
            names.forEachIndexed { i, name ->
                val next = descriptor("/proc/self/fd/${selected.fd}/$name", directory = i < names.lastIndex)
                selected.close(); selected = next
            }
            return selected
        } catch (error: Exception) { selected.close(); throw error }
    }
    private fun input(path: String): InputStream {
        val fd = open(path)
        try {
            if (!OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode) || Os.fstat(fd.fileDescriptor).st_size > MAX_EXPORT) {
                throw PhoneFailure("file_unavailable", "仅支持不超过 16 MiB 的工作区普通文件")
            }
            return ParcelFileDescriptor.AutoCloseInputStream(fd)
        } catch (error: Exception) { fd.close(); throw error }
    }
    private fun read(path: String, check: () -> Unit, stream: AtomicReference<Closeable?>, signal: CancellationSignal): JSONObject {
        if (path.startsWith("shared:")) {
            val doc = document(path.removePrefix("shared:"), check, signal)
            if (doc.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                val children = children(doc.uri, check, signal)
                return PhoneControl.success().put("path", path).put("entries", JSONArray(children.take(100).map {
                    JSONObject().put("name", it.name).put("directory", it.mime == DocumentsContract.Document.MIME_TYPE_DIR)
                })).put("truncated", children.size > 100)
            }
            val asset = resolver.openAssetFileDescriptor(doc.uri, "r", signal) ?: throw PhoneFailure("file_unavailable", "无法读取所选文件")
            val input = asset.createInputStream()
            stream.set(input); return input.use { text(path, bounded(it, check), check) }
        }
        val fd = open(path)
        stream.set(fd)
        if (OsConstants.S_ISDIR(Os.fstat(fd.fileDescriptor).st_mode)) return fd.use {
            val entries = JSONArray(); var count = 0
            java.nio.file.Files.newDirectoryStream(File("/proc/self/fd/${fd.fd}").toPath()).use { children ->
                for (child in children) {
                    check()
                    if (runCatching { parts(child.fileName.toString()) }.isFailure || java.nio.file.Files.isSymbolicLink(child)) continue
                    if (++count > 100) break
                    entries.put(JSONObject().put("name", child.fileName.toString()).put("directory", java.nio.file.Files.isDirectory(child, java.nio.file.LinkOption.NOFOLLOW_LINKS)))
                }
            }
            PhoneControl.success().put("path", path).put("entries", entries).put("truncated", count > 100)
        }
        if (!OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode)) { fd.close(); throw PhoneFailure("file_unavailable", "只读取普通文件") }
        val input = ParcelFileDescriptor.AutoCloseInputStream(fd)
        stream.set(input); return input.use { text(path, bounded(it, check), check) }
    }
    private fun bounded(input: InputStream, check: () -> Unit): ByteArray {
        val buffer = ByteArray(65537); var size = 0
        while (size < buffer.size) { check(); val n = input.read(buffer, size, buffer.size - size); if (n < 0) break; size += n }
        return buffer.copyOf(size)
    }
    private fun text(path: String, bytes: ByteArray, check: () -> Unit): JSONObject {
        check(); var size = minOf(bytes.size, 65536)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        var value: String? = null
        repeat(if (bytes.size > 65536) 4 else 1) {
            if (value == null) { decoder.reset(); value = runCatching { decoder.decode(ByteBuffer.wrap(bytes, 0, size)).toString() }.getOrNull(); size-- }
        }
        if (value == null || '\u0000' in value) throw PhoneFailure("not_text", "只读取 UTF-8 文本；二进制文件请使用分享或导出")
        return PhoneControl.success().put("path", path).put("text", value).put("truncated", bytes.size > 65536)
    }
    private fun copy(input: InputStream, output: java.io.OutputStream, check: () -> Unit) {
        val buffer = ByteArray(32768); var total = 0L
        while (true) { check(); val n = input.read(buffer); if (n < 0) break; total += n
            if (total > MAX_EXPORT) throw PhoneFailure("file_too_large", "文件超过 16 MiB")
            output.write(buffer, 0, n)
        }
        output.flush(); check()
    }
    private fun export(path: String, requested: String, check: () -> Unit, dispatch: () -> Unit, stream: AtomicReference<Closeable?>, signal: CancellationSignal): JSONObject {
        val name = requested.ifEmpty { relative(path).lastOrNull().orEmpty() }
        if (parts(name).size != 1 || name.length > 160) throw PhoneFailure("invalid_args", "导出名称必须是单个文件名")
        input(path).use { source ->
            stream.set(source)
            val parent = document("", check, signal, write = true)
            check(); dispatch()
            val created = DocumentsContract.createDocument(resolver, parent.uri, "application/octet-stream", name)
                ?: throw PhoneFailure("export_failed", "文件提供程序拒绝创建文件")
            var complete = false
            try {
                val output = resolver.openOutputStream(created, "w") ?: throw PhoneFailure("export_failed", "无法写入导出文件")
                stream.set(Closeable { try { source.close() } finally { output.close() } })
                output.use { copy(source, it, check) }; check(); complete = true
                return PhoneControl.success().put("name", name).put("exported", true)
            } finally { if (!complete) runCatching { DocumentsContract.deleteDocument(resolver, created) } }
        }
    }
    private fun share(path: String, check: () -> Unit, stream: AtomicReference<Closeable?>): JSONObject {
        val name = relative(path).lastOrNull() ?: throw PhoneFailure("file_unavailable", "请选择工作区文件")
        val folder = File(context.cacheDir, "phone-sharing").apply { mkdirs() }
        folder.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 3600000 }?.forEach { it.delete() }
        val target = File(folder, UUID.randomUUID().toString() + "-" + name.takeLast(100))
        var complete = false
        try {
            input(path).use { source ->
                target.outputStream().use { output ->
                    stream.set(Closeable { try { source.close() } finally { output.close() } }); copy(source, output, check)
                }
            }
            check()
            val uri = FileProvider.getUriForFile(context, context.packageName + ".phone.files", target)
            complete = true
            return PhoneControl.success().put("uri", uri.toString()).put("mime", resolver.getType(uri) ?: "application/octet-stream")
        } finally { if (!complete) target.delete() }
    }

    private data class Document(val uri: Uri, val name: String, val mime: String)
    private fun document(path: String, check: () -> Unit, signal: CancellationSignal, write: Boolean = false): Document {
        if (!ready(write)) throw PhoneFailure("directory_required", "请在 DSHX 设置中选择并授权手机文件目录")
        val tree = Uri.parse(directory())
        var current = Document(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), "", DocumentsContract.Document.MIME_TYPE_DIR)
        for (part in parts(path)) {
            check()
            if (current.mime != DocumentsContract.Document.MIME_TYPE_DIR) throw PhoneFailure("file_unavailable", "路径不是目录")
            val found = children(current.uri, check, signal).filter { it.name == part }
            if (found.size != 1) throw PhoneFailure("file_unavailable", "路径不存在或名称不唯一")
            current = found.single()
        }
        check(); return current
    }
    private fun children(parent: Uri, check: () -> Unit, signal: CancellationSignal): List<Document> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val result = mutableListOf<Document>()
        resolver.query(uri, columns, null, null, null, signal)?.use { cursor ->
            while (cursor.moveToNext()) {
                check(); if (result.size >= 501) break
                val id = cursor.getString(0); val name = cursor.getString(1).orEmpty()
                if (runCatching { parts(name).size == 1 }.getOrDefault(false)) result += Document(DocumentsContract.buildDocumentUriUsingTree(parent, id), name, cursor.getString(2).orEmpty())
            }
        } ?: throw PhoneFailure("file_unavailable", "无法列出授权目录")
        return result
    }
    companion object {
        const val MAX_EXPORT = 16L * 1024 * 1024
        internal fun parts(path: String): List<String> {
            if (path.length > 2048 || path.startsWith('/') || '\\' in path || path.any { it.code < 32 }) throw PhoneFailure("path_denied", "文件路径无效")
            if (path.isEmpty()) return emptyList()
            val parts = path.split('/')
            if (parts.any { it.isBlank() || it in setOf(".", "..", ".dsh", ".ssh", ".android") }) throw PhoneFailure("path_denied", "不能访问父目录或凭据目录")
            return parts
        }
    }
}
