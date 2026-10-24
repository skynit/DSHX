package local.dshx.app

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** A versioned, verified core. User sessions and workspace never live here. */
class CoreStore(private val root: File) {
    data class Entry(val size: Long, val hash: String, val executable: Boolean)
    data class Manifest(
        val version: String,
        val abi: String,
        val archiveHash: String,
        val archiveSize: Long,
        val entries: Map<String, Entry>
    ) {
        val totalSize: Long get() = entries.values.sumOf { it.size }

        companion object {
            fun parse(text: String): Manifest {
                val json = JSONObject(text)
                require(json.getInt("format") == 1) { "运行环境清单版本不支持" }
                val version = json.getString("version")
                require(version.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "运行环境版本无效" }
                val files = json.getJSONObject("files")
                val entries = files.keys().asSequence().associateWith { key ->
                    val item = files.getJSONObject(key)
                    Entry(item.getLong("size"), item.getString("sha256"), item.getBoolean("executable"))
                }
                require(entries.isNotEmpty() && entries.values.all { it.size >= 0 && it.hash.matches(Regex("[a-f0-9]{64}")) }) {
                    "运行环境文件清单无效"
                }
                require(startupFiles.all { it in entries }) {
                    "运行环境缺少启动文件"
                }
                return Manifest(version, json.getString("abi"), json.getString("sha256"), json.getLong("archiveSize"), entries)
            }
        }
    }

    private fun directory(manifest: Manifest) = File(root, "${manifest.version}-${manifest.archiveHash.take(16)}")

    /** Reuse a completed installation. Full verification is reserved for installation and repair. */
    fun installed(manifest: Manifest, cancelled: () -> Boolean = { false }): File? {
        checkCancel(cancelled)
        val directory = directory(manifest)
        val base = directory.toPath()
        val marker = base.resolve(".verified")
        if (Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(base) ||
            !Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.size(marker) != manifest.archiveHash.length.toLong() ||
            marker.toFile().readText() != manifest.archiveHash) return null
        for (name in startupFiles) {
            checkCancel(cancelled)
            val entry = manifest.entries[name] ?: return null
            val path = base.resolve(name)
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) != entry.size ||
                entry.executable && !Files.isExecutable(path)) return null
        }
        return directory
    }

    fun current(manifest: Manifest, cancelled: () -> Boolean = { false }): File? {
        val directory = installed(manifest, cancelled) ?: return null
        val base = directory.toPath()
        val directories = mutableSetOf("")
        for (name in manifest.entries.keys) {
            var parent = name.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                directories.add(parent)
                parent = parent.substringBeforeLast('/', "")
            }
        }
        var valid = true
        val seen = mutableSetOf<String>()
        try {
            // Default file-tree traversal never follows symbolic links. Check the
            // complete inventory: extra loader files can change execution even
            // when every registered file still has its original hash.
            Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                    checkCancel(cancelled)
                    valid = base.relativize(path).toString() in directories
                    return if (valid) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
                }

                override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                    checkCancel(cancelled)
                    val name = base.relativize(path).toString()
                    if (name == ".verified" && attributes.isRegularFile) return FileVisitResult.CONTINUE
                    val entry = manifest.entries[name]
                    valid = entry != null && attributes.isRegularFile && attributes.size() == entry.size &&
                        (!entry.executable || Files.isExecutable(path)) &&
                        Files.newInputStream(path).use { sha256(it, cancelled) } == entry.hash
                    if (valid) seen.add(name)
                    return if (valid) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
                }
            })
        } catch (_: IOException) { return null }
        return directory.takeIf { valid && seen == manifest.entries.keys }
    }

    fun install(
        manifest: Manifest,
        archive: () -> InputStream,
        available: Long,
        cancelled: () -> Boolean,
        progress: (Long, Long) -> Unit
    ): File {
        require(!Files.isSymbolicLink(root.toPath())) { "运行环境根目录是链接，无法安全安装；请保留工作文件后修复目录" }
        root.mkdirs()
        current(manifest, cancelled)?.let { return it }
        val totalSize = manifest.totalSize
        require(available >= totalSize + manifest.archiveSize + 32L * 1024 * 1024) { "空间不足，请释放空间后重试" }
        val staging = File(root, ".install")
        val zip = File(root, ".archive")
        remove(staging)
        remove(zip)
        Files.createDirectory(staging.toPath())
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            archive().use { input ->
                zip.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancel(cancelled)
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= manifest.archiveSize) { "运行环境压缩包大小不符" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(copied == manifest.archiveSize && digest.digest().hex() == manifest.archiveHash) { "运行环境压缩包校验失败" }
            val seen = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    checkCancel(cancelled)
                    val item = input.nextEntry ?: break
                    require(!item.isDirectory) { "运行环境包含未登记目录" }
                    val entry = manifest.entries[item.name] ?: error("运行环境包含未登记文件")
                    require(seen.add(item.name)) { "运行环境包含重复文件" }
                    val file = target(staging, item.name)
                    file.parentFile!!.mkdirs()
                    val hash = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancel(cancelled)
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= entry.size) { "运行环境文件大小超限" }
                            output.write(buffer, 0, count)
                            hash.update(buffer, 0, count)
                        }
                        // Verify while extracting. Publish the install receipt
                        // only after every entry succeeds; normal launches reuse it.
                    }
                    require(size == entry.size && hash.digest().hex() == entry.hash) { "运行环境文件校验失败" }
                    check(file.setReadable(true, true) && file.setWritable(true, true)) { "无法设置运行环境权限" }
                    if (entry.executable) check(file.setExecutable(true, true)) { "无法设置执行权限" }
                    total += size
                    progress(total, totalSize)
                }
            }
            require(seen == manifest.entries.keys) { "运行环境缺少文件" }
            checkCancel(cancelled)
            File(staging, ".verified").outputStream().use { output ->
                output.write(manifest.archiveHash.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            val destination = directory(manifest)
            // Only an invalid copy of this exact version is removed; other
            // versions and all user data remain intact on install failure.
            remove(destination)
            check(staging.renameTo(destination)) { "无法激活运行环境" }
            return destination
        } finally {
            remove(staging)
            remove(zip)
        }
    }

    /** Remove only this tree's own entries; a link never grants access to its target. */
    private fun remove(file: File) {
        val path = file.toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun target(directory: File, path: String): File {
        require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." } && path != ".verified") { "运行环境路径无效" }
        val file = File(directory, path).canonicalFile
        require(file.path.startsWith(directory.canonicalPath + File.separator)) { "运行环境路径越界" }
        return file
    }

    private fun checkCancel(cancelled: () -> Boolean) {
        if (cancelled()) throw InterruptedException("安装已取消")
    }

    companion object {
        private val startupFiles = listOf(
            "bin/node", "start.mjs", "plugins.mjs", "assistant.mjs",
            "memory.mjs", "memory-store.mjs", "memory-extract.mjs", "read-budget.mjs", "knowledge.mjs", "knowledge-store.mjs", "knowledge-search.mjs", "knowledge-model.mjs", "phone.mjs",
            "lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
            "lib/node_modules/pnpm/bin/pnpm.cjs", "lib/node_modules/pnpm/dist/pnpm.cjs",
            "lib/node_modules/npm/bin/npm-cli.js", "lib/node_modules/npm/lib/cli.js",
            "android.patch.yml", "guard.mjs", "native.node"
        )

        fun sha256(input: InputStream, cancelled: () -> Boolean = { false }): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (cancelled()) throw InterruptedException("校验已取消")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            return digest.digest().hex()
        }

        private fun ByteArray.hex(): String {
            val digits = "0123456789abcdef"
            val chars = CharArray(size * 2)
            forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                chars[index * 2] = digits[value ushr 4]
                chars[index * 2 + 1] = digits[value and 0x0f]
            }
            return String(chars)
        }
    }
}
