package local.dshx.app

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit

/** 清单由 APK 发布者签名；HTTP 只负责传输，不能决定哪些内容可信。 */
internal class UpdateStore(
    private val endpoint: String,
    private val certificates: List<X509Certificate>,
    client: OkHttpClient = OkHttpClient(),
) {
    data class Artifact(val url: String, val size: Long, val hash: String)
    data class Release(
        val code: Long, val name: String, val commit: String,
        val notesUrl: String, val notesHash: String, val artifact: Artifact, val raw: String,
    )

    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    @Volatile private var call: Call? = null

    fun cancel() { call?.cancel() }

    fun check(packageName: String, abis: List<String>, sdk: Int): Release =
        parse(String(read(endpoint, 64 * 1024), Charsets.UTF_8), packageName, abis, sdk)

    fun parse(text: String, packageName: String, abis: List<String>, sdk: Int): Release {
        require(text.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "版本清单过大。" }
        require(text.count { it == '{' || it == '[' } <= 64) { "版本清单结构过于复杂。" }
        val json = JSONObject(text)
        val encoded = json.optString("signature")
        require(encoded.isNotEmpty()) { "版本清单缺少发布者签名。" }
        val signature = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("版本清单签名格式无效。")
        }
        json.remove("signature")
        val payload = canonical(json).toByteArray(Charsets.UTF_8)
        require(certificates.any { certificate -> runCatching {
            Signature.getInstance("SHA256withRSA").run { initVerify(certificate); update(payload); verify(signature) }
        }.getOrDefault(false) }) { "版本清单发布者签名不匹配。" }
        require(json.getInt("format") == 1 && json.getString("packageName") == packageName) { "版本清单不适用于当前应用。" }
        val code = json.getLong("versionCode")
        val name = json.getString("versionName")
        val commit = json.getString("commit")
        require(code in 1..2_100_000_000 && name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) &&
            commit.matches(Regex("[a-f0-9]{40}"))) { "版本信息无效。" }
        val files = json.getJSONArray("artifacts")
        val artifact = abis.firstNotNullOfOrNull { abi ->
            (0 until files.length()).map { files.getJSONObject(it) }.firstOrNull {
                it.getString("abi") == abi && it.getInt("minSdk") in 26..sdk
            }
        } ?: throw IllegalArgumentException("此版本没有适用于当前设备的安装包。")
        val size = artifact.getLong("size")
        val hash = artifact.getString("sha256")
        val notesHash = json.getString("releaseNotesSha256")
        require(size in 1..MAX_APK && listOf(hash, notesHash).all { it.matches(Regex("[a-f0-9]{64}")) }) { "更新文件校验信息无效。" }
        val url = artifact.getString("url")
        val notesUrl = json.getString("releaseNotesUrl")
        address(url); address(notesUrl)
        require(url.toHttpUrl().pathSegments.last() == "DSHX-${if (artifact.getString("abi") == "arm64-v8a") "arm64" else artifact.getString("abi") }-$name.apk" &&
            notesUrl.toHttpUrl().pathSegments.last() == "release-note.txt") { "更新文件名称无效。" }
        return Release(code, name, commit, notesUrl, notesHash, Artifact(url, size, hash), text)
    }

    fun notes(release: Release): String {
        val bytes = read(release.notesUrl, 128 * 1024)
        require(hash(bytes) == release.notesHash) { "更新说明校验失败，请重试。" }
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    fun download(release: Release, target: File, cancelled: () -> Boolean, keepPartialOnStop: Boolean = false,
        progress: (Long, Long) -> Unit,
    ) {
        val artifact = release.artifact
        val directory = target.absoluteFile.parentFile!!
        val partName = "${target.name}.part"
        val temporary = partial(target, release)
        try {
            // 旧版临时文件没有记录对应的安装包，不能拿来续传。
            val partPattern = Regex("${Regex.escape(partName)}(?:\\.[a-f0-9]{64})?")
            directory.listFiles()?.filter { it != temporary && it.isFile && partPattern.matches(it.name) }
                ?.forEach { Files.deleteIfExists(it.toPath()) }
            checkCancelled(cancelled)
            // 应用可能在文件改名后、登记可安装状态前被系统结束。
            if (target.isFile && target.length() == artifact.size && hash(target, cancelled) == artifact.hash) {
                checkCancelled(cancelled)
                Files.deleteIfExists(temporary.toPath())
                progress(artifact.size, artifact.size)
                return
            }
            if (temporary.length() > artifact.size) Files.deleteIfExists(temporary.toPath())
            var offset = temporary.length()
            check(directory.usableSpace > artifact.size - offset + 32L * 1024 * 1024) { "存储空间不足，请清理后重试。" }
            progress(offset, artifact.size)
            while (offset < artifact.size) {
                offset = request(artifact.url, cancelled) {
                    header("Accept-Encoding", "identity") // 文件偏移必须对应原始字节。
                    if (offset > 0) header("Range", "bytes=$offset-")
                }.use { response ->
                    if (offset > 0 && response.code == 416) {
                        Files.deleteIfExists(temporary.toPath())
                        progress(0, artifact.size)
                        0L
                    } else receive(response, artifact, temporary, offset, cancelled, progress)
                }
            }
            require(temporary.length() == artifact.size && hash(temporary, cancelled) == artifact.hash) {
                "安装包内容校验失败，请重新下载。"
            }
            checkCancelled(cancelled)
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (error: IllegalArgumentException) {
            Files.deleteIfExists(temporary.toPath())
            throw error
        } finally {
            call = null
            if (cancelled() && !keepPartialOnStop) discardPartial(release, target)
        }
    }

    private fun receive(response: Response, artifact: Artifact, file: File, offset: Long,
        cancelled: () -> Boolean, progress: (Long, Long) -> Unit,
    ): Long {
        var done = 0L
        val end = when (response.code) {
            200 -> artifact.size
            206 -> {
                val header = response.headers.values("Content-Range").singleOrNull().orEmpty()
                val range = BYTE_RANGE.matchEntire(header)
                val start = range?.groupValues?.get(1)?.toLongOrNull()
                val last = range?.groupValues?.get(2)?.toLongOrNull()
                val size = range?.groupValues?.get(3)?.toLongOrNull()
                require(offset > 0 && start == offset && last != null && last in offset until artifact.size && size == artifact.size) {
                    "安装包续传范围无效。"
                }
                done = offset
                last + 1
            }
            else -> throw IOException("安装包下载失败（${response.code}）。")
        }
        require(response.header("Content-Encoding").let { it == null || it.equals("identity", ignoreCase = true) }) {
            "安装包下载编码无效。"
        }
        val body = response.body ?: error("服务器没有返回安装包。")
        val length = body.contentLength()
        require(length == -1L || length == end - done) { "安装包大小不符。" }
        checkCancelled(cancelled)
        if (done != offset) progress(done, artifact.size)
        FileOutputStream(file, done > 0).use { output ->
            val input = body.byteStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled(cancelled)
                val count = input.read(buffer)
                if (count < 0) break
                require(count <= end - done) { "安装包超过预期大小。" }
                output.write(buffer, 0, count)
                done += count
                progress(done, artifact.size)
            }
            if (done != end) throw EOFException("安装包下载中断，可重试。")
            output.fd.sync()
        }
        return done
    }

    private fun address(value: String) {
        val root = endpoint.toHttpUrl()
        val url = value.toHttpUrl()
        require(url.scheme == root.scheme && url.host == root.host && url.port == root.port &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "更新文件必须来自配置的下载服务器。" }
    }

    private fun request(value: String, cancelled: () -> Boolean = { false }, headers: Request.Builder.() -> Unit = {}): Response {
        address(value)
        val request = Request.Builder().url(value).header("Cache-Control", "no-cache").apply(headers).build()
        val next = client.newCall(request)
        call = next
        if (cancelled()) next.cancel()
        return next.execute()
    }

    private fun read(url: String, maximum: Int): ByteArray = try {
        request(url).use { response ->
            require(response.code == 200) { "更新服务器请求失败（${response.code}）。" }
            val body = response.body ?: error("更新服务器返回空内容。")
            require(body.contentLength() <= maximum) { "更新文件过大。" }
            body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maximum) { "更新文件过大。" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    } finally { call = null }

    companion object {
        const val MAX_APK = 1024L * 1024 * 1024
        private val BYTE_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)

        private fun partial(target: File, release: Release) = File("${target.absolutePath}.part.${release.artifact.hash}")

        fun discardPartial(release: Release, target: File) {
            Files.deleteIfExists(partial(target, release).toPath())
        }

        private fun checkCancelled(cancelled: () -> Boolean) {
            if (cancelled()) throw InterruptedIOException("下载已取消。")
        }

        fun hash(file: File, cancelled: () -> Boolean = { false }): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled(cancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return hex(digest.digest())
        }

        fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

        /** 与发布脚本一致：键排序、无空格的 UTF-8 JSON；签名字段不参与签名。 */
        fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { canonical(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value).replace("\\/", "/")
            is Boolean, is Int, is Long -> value.toString()
            else -> throw IllegalArgumentException("版本清单包含不支持的数据格式。")
        }
    }
}
