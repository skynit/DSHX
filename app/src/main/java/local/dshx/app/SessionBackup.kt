package local.dshx.app

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 保存上游会话日志与不可变附件；不包含模型凭据、运行包或工作区文件。 */
class SessionBackup(private val files: File) {
    data class State(val busy: Boolean = false, val message: String? = null, val error: Boolean = false)
    data class Result(val added: Int, val skipped: Int)
    private data class Entry(val size: Long, val hash: String)
    private val paths = StoragePaths(files)
    private val home = paths.root

    fun backup(output: OutputStream): Int {
        paths.prepare()
        val sources = storedFiles("sessions") + storedFiles("attachments/v1")
        require(sources.size <= MAX_FILES) { "会话与附件文件数量过多" }
        var total = 0L
        val entries = sources.mapValues { (_, file) ->
            file.inputStream().use { copy(it, null, MAX_BYTES - total) }.also { total += it.size }
        }
        val sessions = sessions(entries.keys)
        val inventory = JSONObject()
        entries.forEach { (name, entry) ->
            inventory.put(name, JSONObject().put("size", entry.size).put("sha256", entry.hash))
        }
        val manifest = JSONObject().put("format", "dshx-sessions").put("version", 1)
            .put("createdAt", System.currentTimeMillis()).put("files", inventory).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST) { "会话文件清单过大，无法备份" }
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
            entries.forEach { (name, entry) ->
                zip.putNextEntry(ZipEntry(name))
                val copied = target(home, name).inputStream().use { copy(it, zip, entry.size) }
                check(copied == entry) { "会话文件在备份时发生变化，请停止服务后重试" }
                zip.closeEntry()
            }
        }
        return sessions.size
    }

    fun restore(input: InputStream): Result {
        val staging = Files.createTempDirectory(files.toPath(), ".session-restore-").toFile()
        try {
            val archive = File(staging, "backup.zip")
            archive.outputStream().use { copy(input, it, MAX_BYTES + MAX_MANIFEST * 2L) }
            val unpacked = File(staging, "data").apply { mkdirs() }
            val entries = ZipFile(archive).use { zip ->
                val manifest = zip.getEntry("manifest.json") ?: error("不是 DSHX 会话备份文件")
                require(manifest.size in 1..MAX_MANIFEST.toLong()) { "备份清单大小无效" }
                val json = zip.getInputStream(manifest).use { source ->
                    val text = java.io.ByteArrayOutputStream()
                    copy(source, text, MAX_MANIFEST.toLong())
                    JSONObject(text.toString("UTF-8"))
                }
                require(json.getString("format") == "dshx-sessions" && json.getInt("version") == 1) { "不支持此备份格式或版本" }
                val inventory = json.getJSONObject("files")
                require(inventory.length() <= MAX_FILES) { "备份文件数量过多" }
                var total = 0L
                val plan = inventory.keys().asSequence().associateWith { name ->
                    require(allowed(name)) { "备份包含不支持的文件路径" }
                    val item = inventory.getJSONObject(name)
                    val entry = Entry(item.getLong("size"), item.getString("sha256"))
                    require(entry.size in 0..MAX_BYTES && entry.hash.matches(HASH)) { "备份文件清单无效" }
                    total += entry.size
                    require(total <= MAX_BYTES) { "备份解压后超过 512 MiB" }
                    entry
                }
                sessions(plan.keys)
                val seen = mutableSetOf<String>()
                val items = zip.entries()
                while (items.hasMoreElements()) {
                    val item = items.nextElement()
                    require(seen.add(item.name) && !item.isDirectory) { "备份包含重复文件或未登记目录" }
                    if (item.name == "manifest.json") continue
                    val entry = plan[item.name] ?: error("备份包含未登记文件")
                    require(item.size == entry.size) { "备份文件大小不符" }
                    val destination = target(unpacked, item.name)
                    check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory) { "无法创建恢复临时目录" }
                    destination.outputStream().use { out ->
                        val actual = zip.getInputStream(item).use { copy(it, out, entry.size) }
                        require(actual == entry) { "备份文件校验失败" }
                        out.fd.sync()
                    }
                }
                require(seen == plan.keys + "manifest.json") { "备份文件不完整" }
                plan
            }
            paths.prepare()
            return merge(unpacked, entries)
        } finally { staging.deleteRecursively() }
    }

    private fun merge(staging: File, entries: Map<String, Entry>): Result {
        val incoming = sessions(entries.keys)
        val known = sessions(storedFiles("sessions").keys).keys
        val added = incoming.filterKeys { it !in known }
        if (added.isEmpty()) return Result(0, incoming.size)
        // 附件先到位，再发布完整会话目录，避免出现能看到会话却读不到附件的窗口。
        val attachments = entries.filterKeys { it.startsWith("attachments/") }
        attachments.forEach { (name, entry) ->
            val destination = target(home, name)
            if (Files.exists(destination.toPath(), NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(destination.toPath(), NOFOLLOW_LINKS) &&
                    destination.inputStream().use { copy(it, null, MAX_BYTES) } == entry) { "本机已有同名附件内容不同，恢复已停止" }
            }
        }
        val destinations = added.values.associateWith { path ->
            target(home, path).also { require(!Files.exists(it.toPath(), NOFOLLOW_LINKS)) { "本机会话目录已存在，恢复已停止" } }
        }
        val published = mutableListOf<File>()
        try {
            for (name in attachments.keys) {
                val destination = target(home, name)
                if (Files.exists(destination.toPath(), NOFOLLOW_LINKS)) continue
                check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory) { "无法创建附件目录" }
                Files.move(target(staging, name).toPath(), destination.toPath())
                published += destination
            }
            for ((path, destination) in destinations) {
                check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory) { "无法创建会话目录" }
                Files.move(target(staging, path).toPath(), destination.toPath())
                published += destination
            }
        } catch (error: Exception) {
            published.asReversed().forEach { it.deleteRecursively() }
            throw error
        }
        return Result(added.size, incoming.size - added.size)
    }

    private fun storedFiles(path: String): Map<String, File> {
        val root = target(home, path)
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return emptyMap()
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) { "会话或附件目录无效" }
        val result = linkedMapOf<String, File>()
        Files.walk(root.toPath()).use { paths ->
            paths.forEach { file ->
                require(!Files.isSymbolicLink(file)) { "会话或附件目录包含符号链接" }
                if (Files.isDirectory(file, NOFOLLOW_LINKS)) return@forEach
                val name = home.toPath().relativize(file).toString()
                if (!allowed(name)) return@forEach // 上游锁、临时文件与可重新生成的图片缓存不进入备份。
                require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "会话或附件不是普通文件" }
                result[name] = file.toFile()
            }
        }
        return result
    }

    /** 编码后的 ID 是上游使用的无碰撞目录名；跨项目目录也按同一个 ID 去重。 */
    private fun sessions(names: Collection<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (name in names.filter { it.startsWith("sessions/") }) {
            val parts = name.split('/')
            val path = parts.take(3).joinToString("/")
            val old = result.put(parts[2], path)
            require(old == null || old == path) { "备份或本机存在跨目录重复的会话 ID" }
        }
        return result
    }

    private fun target(root: File, name: String): File {
        val parts = name.split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it && it.none { ch -> ch.code < 32 } }) { "备份文件路径无效" }
        val destination = File(root, name)
        var current: File? = destination
        while (current != null && current != files) {
            require(!Files.isSymbolicLink(current.toPath())) { "会话或附件路径包含符号链接" }
            if (current != destination && Files.exists(current.toPath(), NOFOLLOW_LINKS)) {
                require(Files.isDirectory(current.toPath(), NOFOLLOW_LINKS)) { "会话或附件的父路径不是目录" }
            }
            current = current.parentFile
        }
        return destination
    }

    private fun allowed(name: String): Boolean {
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.length > 255 || '\\' in it || it.any { ch -> ch.code < 32 } }) return false
        if (parts.size == 4 && parts[0] == "sessions") {
            return (parts[1] == "_no-cwd" || parts[1].startsWith("--") && parts[1].endsWith("--")) &&
                parts[2].matches(SESSION_ID) && parts[3].matches(LOG)
        }
        if (parts.size !in 5..6 || parts.take(2) != listOf("attachments", "v1")) return false
        val hash = parts[4]
        return hash.matches(HASH) && parts[3] == hash.take(2) &&
            (parts.size == 5 && parts[2] in setOf("objects", "file-objects") || parts.size == 6 && parts[2] == "files")
    }

    private fun copy(input: InputStream, output: OutputStream?, limit: Long): Entry {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            require(size <= limit) { "备份文件大小超过限制" }
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
        }
        return Entry(size, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    companion object {
        private const val MAX_BYTES = 512L * 1024 * 1024
        private const val MAX_FILES = 20000
        private const val MAX_MANIFEST = 4 * 1024 * 1024
        private val HASH = Regex("[a-f0-9]{64}")
        private val SESSION_ID = Regex("(?:[A-Za-z0-9._-]|~[0-9A-F]{4})+")
        private val LOG = Regex("session(?:\\.v[1-9][0-9]*)?\\.jsonl(?:\\.zstd)?")
    }
}
