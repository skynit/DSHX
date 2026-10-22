package local.dshx.app

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.io.StringReader
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Settings belong to Android; the runtime only appends usage events. Call from an IO thread. */
class AssistantStore(filesDir: File) {
    private val directory = File(filesDir, "assistant")
    private val settings = File(directory, "settings.json")
    private val temporary = File(directory, "settings.json.new")
    private val backup = File(directory, "settings.json.bak")

    companion object {
        // Activities may have different store instances while an earlier IO task finishes.
        // The runtime only reads the base; all native writers share this temporary file.
        private val settingsLock = Any()
    }

    data class Settings(val name: String = "", val identity: String = "", val prompt: String = "") {
        fun trimmed(): Settings = Settings(name.trim(), identity.trim(), prompt.trim()).also {
            require(it.name.length <= 80) { "名字不能超过 80 个 UTF-16 单位。" }
            require(it.identity.length <= 2000) { "身份不能超过 2000 个 UTF-16 单位。" }
            require(it.prompt.length <= 16000) { "自定义提示词不能超过 16000 个 UTF-16 单位。" }
        }
    }

    data class Usage(
        val inputTokens: Long? = null, val outputTokens: Long? = null,
        val cacheReadTokens: Long? = null, val cacheWriteTokens: Long? = null,
        val reasoningTokens: Long? = null, val totalTokens: Long? = null,
    ) {
        val known: Boolean get() = inputTokens != null && outputTokens != null
    }

    data class Call(
        val id: String, val provider: String, val model: String, val sessionId: String?,
        val purpose: String, val startedAt: Long, val usage: Usage? = null,
        val outcome: String = "incomplete", val endedAt: Long? = null,
        val dispatch: String = "adapter-attempt",
    ) {
        val duration: Long? get() = endedAt?.minus(startedAt)
    }

    class Summary(calls: List<Call>) {
        val count = calls.size
        val requests = calls.count { it.dispatch == "http-request" }
        val attempts = calls.count { it.dispatch == "adapter-attempt" }
        val success = calls.count { it.outcome == "success" }
        val failed = calls.count { it.outcome == "failed" }
        val cancelled = calls.count { it.outcome == "cancelled" }
        val incomplete = calls.count { it.outcome == "incomplete" }
        val unknown = calls.count { it.usage?.known != true }
        val inputTokens = sum(calls) { it.inputTokens }
        val outputTokens = sum(calls) { it.outputTokens }
        val cacheReadTokens = sum(calls) { it.cacheReadTokens }
        val cacheWriteTokens = sum(calls) { it.cacheWriteTokens }
        val reasoningTokens = sum(calls) { it.reasoningTokens }
        val totalTokens = sum(calls) { it.totalTokens }
        val knownInputTokens = listOfNotNull(inputTokens, cacheReadTokens, cacheWriteTokens)
            .reduceOrNull(BigInteger::add)
        val knownTokens = listOfNotNull(knownInputTokens, outputTokens)
            .reduceOrNull(BigInteger::add)

        // A partial snapshot still contributes every reported field. BigInteger avoids overflow
        // across otherwise valid Long counters; null means no report, never a fabricated zero.
        private fun sum(calls: List<Call>, field: (Usage) -> Long?): BigInteger? = calls
            .mapNotNull { it.usage?.let(field)?.let(BigInteger::valueOf) }
            .reduceOrNull(BigInteger::add)
    }

    data class Snapshot(val calls: List<Call> = emptyList(), val warnings: List<String> = emptyList()) {
        val summary = Summary(calls)
        val sources = calls.groupBy { it.purpose }.mapValues { Summary(it.value) }
    }

    fun readSettings(): Settings = synchronized(settingsLock) {
        if (backup.exists()) {
            // Legacy AtomicFile's backup is the last committed settings. Validate and copy
            // it through the new publisher; never rename the base out from under Node.
            val restored = parseSettings(backup.readText(Charsets.UTF_8))
            publishSettings(restored)
            Files.delete(backup.toPath())
            return@synchronized restored
        }
        val text = try {
            settings.readText(Charsets.UTF_8)
        } catch (error: FileNotFoundException) {
            if (settings.exists() || directory.exists() && !directory.isDirectory) throw error
            return@synchronized Settings()
        }
        // A leftover .new was never published. Keep using the base, even if .new parses.
        parseSettings(text)
    }

    private fun parseSettings(text: String): Settings {
        val json = json(text)
        require(json.integer("version") == 1L) { "不支持的助手设置版本。" }
        return Settings(json.string("name"), json.string("identity"), json.string("prompt")).trimmed()
    }

    fun saveSettings(value: Settings): Settings = synchronized(settingsLock) {
        val clean = value.trimmed()
        publishSettings(clean)
        // An explicit save also replaces unreadable legacy settings. Remove the old backup
        // only after publishing, otherwise a failed write could destroy the recovery copy.
        Files.deleteIfExists(backup.toPath())
        check(parseSettings(settings.readText(Charsets.UTF_8)) == clean) { "助手设置写入后校验失败，请重试。" }
        clean
    }

    private fun publishSettings(value: Settings) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建助手设置目录。" }
        val text = JSONObject().put("version", 1).put("name", value.name)
            .put("identity", value.identity).put("prompt", value.prompt).toString()
        FileOutputStream(temporary).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        // Same-directory atomic replacement lets non-cooperating readers see a complete
        // old or new file. Do not fall back to deleting or writing the base in place.
        Files.move(temporary.toPath(), settings.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun readUsage(): Snapshot {
        val file = File(directory, "usage.jsonl")
        val calls = linkedMapOf<String, Call>()
        val warnings = mutableListOf<String>()
        var warningCount = 0
        fun warn(line: Long, reason: String) {
            warningCount++
            if (warnings.size < 20) warnings += "第 $line 行：$reason"
        }
        val source = try { RandomAccessFile(file, "r") } catch (error: FileNotFoundException) {
            if (file.exists() || directory.exists() && !directory.isDirectory) throw error
            return Snapshot()
        }
        source.use { input ->
            // Bound the read to the length at open, so a busy append-only writer cannot keep
            // a refresh running forever. Never accept an unterminated final event.
            var remaining = input.length()
            val buffer = ByteArray(8192)
            val row = ByteArrayOutputStream()
            var line = 1L
            while (remaining > 0) {
                val size = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (size < 0) throw IOException("用量文件在读取时被截断，请刷新重试。")
                remaining -= size
                for (index in 0 until size) {
                    if (buffer[index] != '\n'.code.toByte()) {
                        row.write(buffer[index].toInt())
                        continue
                    }
                    try {
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(row.toByteArray())).toString()
                        readEvent(json(text), calls) { warn(line, it) }
                    } catch (_: Exception) {
                        warn(line, "记录损坏或字段不合法，已忽略。")
                    }
                    row.reset()
                    line++
                }
            }
            if (row.size() > 0) warn(line, "末尾记录未完整写入，已忽略；稍后可刷新。")
        }
        if (warningCount > warnings.size) warnings += "另有 ${warningCount - warnings.size} 条记录提示。"
        return Snapshot(calls.values.toList(), warnings.toList())
    }

    private fun readEvent(json: JSONObject, calls: MutableMap<String, Call>, warn: (String) -> Unit) {
        require(json.integer("version") == 1L)
        val id = json.string("id")
        require(UUID.fromString(id).toString().equals(id, ignoreCase = true))
        val key = id.lowercase()
        when (json.string("event")) {
            "start" -> {
                require(key !in calls)
                val purpose = json.string("purpose")
                require(purpose in listOf("conversation", "compaction", "session-title", "other"))
                val dispatch = when (json.opt("dispatch")) {
                    "http-request" -> "http-request"
                    "adapter-attempt", null -> "adapter-attempt"
                    else -> {
                        warn("调用的发送分类不合法，已归入发送次数未核实。")
                        "adapter-attempt"
                    }
                }
                calls[key] = Call(key, json.string("provider"), json.string("model"),
                    if (json.has("sessionId")) json.string("sessionId") else null,
                    purpose, json.integer("startedAt"), dispatch = dispatch)
            }
            "usage" -> {
                val call = calls[key] ?: return warn("用量事件没有对应的开始记录，已忽略。")
                val usage = if (json.has("usage")) json.getJSONObject("usage") else JSONObject()
                fun field(name: String): Long? {
                    if (!usage.has(name)) return null
                    return try { usage.integer(name) } catch (_: Exception) {
                        warn("用量字段 $name 不合法，已保留其他有效字段。")
                        null
                    }
                }
                calls[key] = call.copy(usage = Usage(field("inputTokens"), field("outputTokens"),
                    field("cacheReadTokens"), field("cacheWriteTokens"), field("reasoningTokens"), field("totalTokens")))
            }
            "finish" -> {
                val call = calls[key] ?: return warn("结束事件没有对应的开始记录，已忽略。")
                require(call.endedAt == null)
                val outcome = json.string("outcome")
                require(outcome in listOf("success", "failed", "cancelled", "incomplete"))
                val endedAt = json.integer("endedAt")
                require(endedAt >= call.startedAt)
                calls[key] = call.copy(outcome = outcome, endedAt = endedAt)
            }
            else -> warn("未知事件，已忽略。")
        }
    }

    private fun json(text: String): JSONObject {
        // JSONObject also accepts non-JSON syntax such as unquoted keys. A damaged
        // finish event must pass strict JSON validation before it can count as success.
        JsonReader(StringReader(text)).use { reader ->
            require(reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.skipValue()
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        return JSONObject(text)
    }

    private fun JSONObject.string(name: String): String = get(name).let {
        require(it is String) { "设置或记录的 $name 必须是文字。" }
        it
    }

    private fun JSONObject.integer(name: String): Long = get(name).let {
        require(it is Int || it is Long)
        (it as Number).toLong().also { value -> require(value >= 0) }
    }
}
