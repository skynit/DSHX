package local.dshx.app

import android.app.ActivityManager
import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException

/** 用户选择与导入文件独立于可替换的引擎运行包。 */
class SmallModel(private val context: Context) {
    data class Settings(val source: String = "default", val provider: String = "", val model: String = "",
        val imageProvider: String = "", val imageModel: String = "") {
        fun checked(): Settings {
            require(source in setOf("default", "custom", "online")) { "请选择小模型来源。" }
            val clean = copy(provider = provider.trim(), model = model.trim(), imageProvider = imageProvider.trim(), imageModel = imageModel.trim())
            require(listOf(clean.provider, clean.model, clean.imageProvider, clean.imageModel).all { it.length <= 256 && it.none(Char::isISOControl) }) { "服务和模型名称不能超过 256 个字符或包含控制字符。" }
            require(source != "online" || clean.provider.isNotBlank() && clean.model.isNotBlank() && clean.provider != "dshx-local") { "请填写已配置的在线服务标识和模型名称。" }
            require(clean.imageProvider.isBlank() == clean.imageModel.isBlank() && clean.imageProvider != "dshx-local") { "截图服务标识和模型名称需要同时填写；当前内置本地引擎不支持图片。" }
            return clean
        }

        fun route(): JSONObject = if (source == "online") JSONObject().put("provider", provider).put("model", model)
            else JSONObject().put("provider", "dshx-local").put("model", if (source == "custom") "custom" else "qwen3-0.6b")

        fun imageRoute(): JSONObject = if (imageProvider.isNotBlank()) JSONObject().put("provider", imageProvider).put("model", imageModel) else route()
    }

    private val preferences = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "voice/custom-model")
    private val record get() = AtomicFile(File(root, "model.json"))

    fun settings(): Settings = Settings(preferences.getString("smallSource", "default") ?: "default",
        preferences.getString("smallProvider", "").orEmpty(), preferences.getString("smallModel", "").orEmpty(),
        preferences.getString("imageProvider", "").orEmpty(), preferences.getString("imageModel", "").orEmpty()).checked()

    fun status(): JSONObject {
        val saved = settings()
        return JSONObject().put("enabled", preferences.getBoolean("local", false))
            .put("route", saved.imageRoute()).put("textRoute", saved.route())
    }

    fun save(value: Settings) {
        val clean = value.checked()
        require(clean.source != "custom" || ready()) { "请先导入本地模型。" }
        check(preferences.edit().putString("smallSource", clean.source).putString("smallProvider", clean.provider)
            .putString("smallModel", clean.model).putString("imageProvider", clean.imageProvider)
            .putString("imageModel", clean.imageModel).commit()) { "小模型设置保存失败。" }
    }

    private fun entry(): JSONObject? = try {
        record.openRead().bufferedReader().use { JSONObject(it.readText()) }.also {
            require(it.getInt("version") == 1) { "自定义模型记录版本不受支持，请重新导入。" }
            require(it.getString("file").matches(Regex("[a-f0-9-]{36}\\.gguf")))
            require(it.getLong("bytes") in 8..MAX_BYTES && it.getString("sha256").matches(Regex("[a-f0-9]{64}")))
        }
    } catch (_: java.io.FileNotFoundException) { null }

    fun name(): String = runCatching { entry()?.getString("name").orEmpty() }.getOrDefault("")
    fun ready(): Boolean = runCatching {
        val item = entry() ?: return false
        File(root, item.getString("file")).let { it.isFile && it.length() == item.getLong("bytes") }
    }.getOrDefault(false)

    // 原生请求持有文件期间，发布者不能删除旧文件；慢速复制和加载校验均在锁外完成。
    fun <T> withFile(block: (File) -> T): T = synchronized(filesLock) {
        val item = entry() ?: error("请先导入本地模型。")
        val file = File(root, item.getString("file"))
        check(file.isFile && file.length() == item.getLong("bytes") && VoiceModels.hash(file) == item.getString("sha256")) { "自定义模型校验失败，请重新导入。" }
        block(file)
    }

    fun requireMemory(file: File, tokens: Int) {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        // 自定义架构的缓存大小不固定，使用含余量的准入估算，不代表预留内存。
        val minimum = file.length() + file.length() / 2 + 256L * 1024 * 1024 + tokens.coerceIn(512, 4096) * 512L * 1024
        check(!memory.lowMemory && memory.availMem - minimum >= memory.threshold) { "当前可用内存不足，本地模型暂不加载。请关闭其他占用内存的应用后重试。" }
    }

    fun install(input: InputStream, name: String, cancelled: () -> Boolean, progress: (Long) -> Unit,
        validate: (File) -> Unit = { file -> requireMemory(file, 512); LocalModel(context).checkFile(file) }) {
        check(root.isDirectory || root.mkdirs()) { "无法创建模型目录。" }
        val file = File(root, "${UUID.randomUUID()}.gguf")
        var published = false
        try {
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    if (cancelled()) throw CancellationException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= MAX_BYTES) { "本地模型不能超过 4 GiB。" }
                    check(root.usableSpace > count + 32L * 1024 * 1024) { "存储空间不足，无法导入本地模型。" }
                    output.write(buffer, 0, count); progress(size)
                }
                output.fd.sync()
            }
            require(file.length() >= 8) { "请选择完整的 GGUF 模型文件。" }
            val header = ByteArray(8)
            java.io.DataInputStream(file.inputStream()).use { it.readFully(header) }
            require(header.copyOfRange(0, 4).contentEquals("GGUF".toByteArray()) &&
                header[4].toInt() in 2..3 && header.sliceArray(5..7).all { it == 0.toByte() }) { "请选择有效的 GGUF v2 或 v3 模型文件。" }
            validate(file)
            val item = JSONObject().put("version", 1).put("file", file.name).put("name", name.trim().take(120).ifBlank { "自定义本地模型" })
                .put("bytes", file.length()).put("sha256", VoiceModels.hash(file))
            synchronized(filesLock) {
                if (cancelled()) throw CancellationException()
                val previous = runCatching { entry()?.getString("file") }.getOrNull()
                val target = record
                val output = target.startWrite()
                try { output.write(item.toString().toByteArray()); target.finishWrite(output) }
                catch (error: Throwable) { target.failWrite(output); throw error }
                published = true
                if (previous != null) File(root, previous).delete()
            }
        } finally { if (!published) file.delete() }
    }

    companion object {
        private const val MAX_BYTES = 4L * 1024 * 1024 * 1024
        private val filesLock = Any()
    }
}
