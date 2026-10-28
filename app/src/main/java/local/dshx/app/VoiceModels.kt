package local.dshx.app

import android.content.Context
import android.app.ActivityManager
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Model files live outside the replaceable Node bundle; only verified complete sets are published. */
class VoiceModels(private val context: Context) {
    private val catalog by lazy { context.assets.open("voice/models.json").bufferedReader().use { JSONObject(it.readText()) } }
    private val root = File(context.filesDir, "voice/models")

    init {
        // 仅清理已移除的旧版整句识别资源，保留唤醒和文字模型。
        listOf("speech-20240717", "speech-20240717.new").forEach { File(root, it).deleteRecursively() }
        File(root, "wake-20251220-1/silero_vad.onnx").delete()
    }

    fun directory(name: String): File = File(root, "$name-${catalog.getJSONObject(name).getString("version")}")

    fun ready(name: String): Boolean = runCatching {
        val dir = directory(name)
        val files = catalog.getJSONObject(name).getJSONArray("files")
        File(dir, "ready").isFile && (0 until files.length()).all { index ->
            val item = files.getJSONObject(index)
            File(dir, item.getString("name")).let { it.isFile && it.length() == item.getLong("bytes") }
        }
    }.getOrDefault(false)

    fun verify(name: String) {
        check(ready(name)) { "请先在设置中下载本地语音模型。" }
        val files = catalog.getJSONObject(name).getJSONArray("files")
        for (index in 0 until files.length()) {
            val item = files.getJSONObject(index)
            check(hash(File(directory(name), item.getString("name"))) == item.getString("sha256")) { "本地模型校验失败，请重新下载。" }
        }
    }

    fun requireMemory(name: String, contextTokens: Int = 4096) {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        // Allocation estimates include headroom over isolated API 34 emulator measurements.
        // Preserve Android's low-memory reserve; this is admission control, not a reservation.
        // Qwen3 f16 KV is 112 KiB per token on this model (448 MiB / 4096).
        val minimum = when (name) {
            "local" -> 700L * 1024 * 1024 + (contextTokens.coerceIn(512, 4096) - 512L) * 112 * 1024
            else -> error("未知模型类型")
        }
        check(!memory.lowMemory && memory.availMem - minimum >= memory.threshold) { "当前可用内存不足，本地模型暂不加载。请关闭其他占用内存的应用后重试。" }
    }

    @Synchronized fun install(name: String, mirror: Boolean, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        if (ready(name) && runCatching { verify(name) }.isSuccess) return
        val model = catalog.getJSONObject(name)
        val files = model.getJSONArray("files")
        check(root.isDirectory || root.mkdirs()) { "无法创建模型目录。" }
        val target = directory(name)
        val temporary = File(root, target.name + ".new")
        temporary.deleteRecursively()
        val total = (0 until files.length()).sumOf { files.getJSONObject(it).getLong("bytes") }
        check(context.filesDir.usableSpace > total + 32L * 1024 * 1024) { "存储空间不足，无法安装本地模型。" }
        check(temporary.mkdirs()) { "无法准备模型下载。" }
        var done = 0L
        try {
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                val filename = item.getString("name")
                require(filename.matches(Regex("[a-zA-Z0-9._-]+")))
                val expected = item.getLong("bytes")
                val sha = MessageDigest.getInstance("SHA-256")
                val connection = if (item.has("asset")) null else {
                    val address = item.getString(if (mirror && item.has("mirror")) "mirror" else "url")
                    (URL(address).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000; readTimeout = 15_000
                        check(url.protocol == "https") { "模型下载必须使用 HTTPS。" }
                        check(responseCode == 200) { "模型下载失败，请检查网络或更换下载来源。" }
                    }
                }
                try {
                    val input = if (connection == null) context.assets.open(item.getString("asset")) else connection.inputStream
                    input.use { source ->
                        FileOutputStream(File(temporary, filename)).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var received = 0L
                            while (true) {
                                if (cancelled()) throw CancellationException()
                                val count = source.read(buffer)
                                if (count == -1) break
                                received += count
                                check(received <= expected) { "模型文件大小不符。" }
                                output.write(buffer, 0, count); sha.update(buffer, 0, count)
                                progress(done + received, total)
                            }
                            check(received == expected && hex(sha.digest()) == item.getString("sha256")) { "模型文件校验失败。" }
                            output.fd.sync()
                        }
                    }
                } finally { connection?.disconnect() }
                done += expected
            }
            if (cancelled()) throw CancellationException()
            File(temporary, "ready").writeText(model.getString("version"))
            // A corrupt unpublished directory is never treated as a model set.
            if (target.exists()) check(target.deleteRecursively()) { "无法替换损坏的模型。" }
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
        } finally { temporary.deleteRecursively() }
    }

    companion object {
        fun hash(file: File): String {
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; sha.update(buffer, 0, count) }
            }
            return hex(sha.digest())
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
