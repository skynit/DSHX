package local.dshx.app

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    fun download(release: Release, target: File, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        val temporary = File(target.path + ".part")
        try {
            request(release.artifact.url).use { response ->
                require(response.code == 200) { "安装包下载失败（${response.code}）。" }
                val body = response.body ?: error("服务器没有返回安装包。")
                require(body.contentLength() == -1L || body.contentLength() == release.artifact.size) { "安装包大小不符。" }
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw InterruptedIOException("下载已取消。")
                        val count = input.read(buffer)
                        if (count < 0) break
                        done += count
                        require(done <= release.artifact.size) { "安装包超过预期大小。" }
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                        progress(done, release.artifact.size)
                    }
                    output.fd.sync()
                } }
                require(done == release.artifact.size && hex(digest.digest()) == release.artifact.hash) { "安装包内容校验失败，请重新下载。" }
            }
            if (cancelled()) throw InterruptedIOException("下载已取消。")
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { call = null; temporary.delete() }
    }

    private fun address(value: String) {
        val root = endpoint.toHttpUrl()
        val url = value.toHttpUrl()
        require(url.scheme == root.scheme && url.host == root.host && url.port == root.port &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "更新文件必须来自配置的下载服务器。" }
    }

    private fun request(value: String): okhttp3.Response {
        address(value)
        return client.newCall(Request.Builder().url(value).header("Cache-Control", "no-cache").build()).also { call = it }.execute()
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
