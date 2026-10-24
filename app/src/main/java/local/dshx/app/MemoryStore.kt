package local.dshx.app

import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** Node 是记忆快照的唯一写入方；Android 停机时只读取经过校验的设置摘要。 */
class MemoryStore(filesDir: File) {
    private val paths = StoragePaths(filesDir)
    private val file get() = if (java.nio.file.Files.exists(paths.memory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        paths.memory
    } else paths.legacyMemory

    data class Settings(val enabled: Boolean = false, val autoSave: Boolean = false) {
        fun json(): JSONObject = JSONObject().put("enabled", enabled).put("autoSave", autoSave)
    }

    data class Summary(val settings: Settings, val revision: Long)

    data class Status(
        val settings: Settings, val active: Boolean, val ready: Boolean, val revision: Long,
        val count: Int, val pending: Int, val jobs: List<JSONObject>, val autoReady: Boolean, val message: String?,
    )

    data class Entry(
        val id: String, val type: String, val scope: JSONObject, val key: String, val summary: String,
        val content: String, val source: JSONObject, val state: String, val locked: Boolean,
        val createdAt: Long, val updatedAt: Long, val expiresAt: Long?,
    ) {
        val scopeText: String get() = if (scope.getString("kind") == "user") "用户通用" else "工作区：${scope.getString("workspace")}"
        val typeText: String get() = when (type) { "preference" -> "偏好"; "fact" -> "事实"; else -> "经验" }
    }

    fun readSettings(): Settings = readSummary().settings

    fun readSummary(): Summary {
        val text = try {
            file.inputStream().use { input ->
                val bytes = input.readLimited(MAX_BYTES + 1)
                require(bytes.size <= MAX_BYTES) { "长期记忆文件过大，状态未知。" }
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }
        } catch (error: FileNotFoundException) {
            if (java.nio.file.Files.notExists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Summary(Settings(), 0)
            throw error
        }
        val parser = org.json.JSONTokener(text)
        val json = JSONObject(parser)
        require(parser.nextClean() == '\u0000') { "长期记忆文件包含多余内容，状态未知。" }
        return parseSnapshot(json)
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        private const val MAX_INTEGER = 9_007_199_254_740_991L

        // 限制实际读取字节数，不能使用只设置初始容量的 readBytes。
        private fun java.io.InputStream.readLimited(limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() < limit) {
                val size = read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (size < 0) break
                output.write(buffer, 0, size)
            }
            return output.toByteArray()
        }

        private fun number(json: JSONObject, key: String): Long {
            val value = json.get(key)
            require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() &&
                value.toLong() in 0..MAX_INTEGER) { "长期记忆的 $key 格式无效。" }
            return value.toLong()
        }

        private fun flag(json: JSONObject, key: String): Boolean {
            require(json.get(key) is Boolean) { "长期记忆的 $key 格式无效。" }
            return json.getBoolean(key)
        }

        private fun text(json: JSONObject, key: String): String {
            require(json.get(key) is String) { "长期记忆的 $key 必须是文字。" }
            return json.getString(key)
        }

        fun parse(json: JSONObject): Settings = Settings(flag(json, "enabled"), flag(json, "autoSave"))

        fun parseSnapshot(json: JSONObject): Summary {
            val unit = json.getJSONObject("unit")
            require(text(unit, "name") == "memory" && number(unit, "version") == 1L) { "不支持的长期记忆存储格式。" }
            require(json.getJSONObject("tables").length() == 0) { "长期记忆存储格式无效。" }
            val value = json.getJSONObject("global")
            require(number(value, "version") == 1L) { "不支持的长期记忆版本。" }
            val revision = number(value, "revision")
            number(value, "epoch")
            val settings = parse(value.getJSONObject("settings"))
            require(value.getJSONArray("entries").length() <= 500 && value.getJSONArray("jobs").length() <= 32 &&
                value.getJSONArray("blocked").length() <= 2000 && value.getJSONObject("cursors").length() <= 256) {
                "长期记忆存储超出限制，状态未知。"
            }
            return Summary(settings, revision)
        }

        fun status(json: JSONObject): Status {
            if (json.isNull("settings") || json.isNull("revision")) {
                throw IllegalStateException(json.optString("message").takeIf(String::isNotBlank) ?: "长期记忆状态未知，请重新读取。")
            }
            val count = number(json, "count")
            val pending = number(json, "pending")
            require(count <= 500 && pending <= 500) { "长期记忆数量无效。" }
            val jobs = json.getJSONArray("jobs")
            require(jobs.length() <= 32) { "长期记忆任务过多。" }
            return Status(parse(json.getJSONObject("settings")), flag(json, "active"), flag(json, "ready"),
                number(json, "revision"), count.toInt(), pending.toInt(),
                List(jobs.length()) {
                    jobs.getJSONObject(it).also { job ->
                        require(text(job, "id").isNotBlank() && text(job, "state") in setOf("running", "waiting", "failed")) { "整理任务格式无效。" }
                        require(number(job, "attempts") <= 2) { "整理任务重试次数无效。" }
                        if (job.has("modelReady")) flag(job, "modelReady")
                        if (job.has("message")) text(job, "message")
                    }
                }, flag(json, "autoReady"),
                if (json.has("message") && !json.isNull("message")) text(json, "message").takeIf(String::isNotBlank) else null)
        }

        fun entries(json: JSONObject): Pair<List<Entry>, Long> {
            val revision = number(json, "revision")
            val rows = json.getJSONArray("entries")
            require(rows.length() <= 500) { "长期记忆条目过多。" }
            return List(rows.length()) { entry(rows.getJSONObject(it)) } to revision
        }

        fun read(json: JSONObject): Pair<Entry, Long> = entry(json.getJSONObject("entry")) to number(json, "revision")

        fun entry(json: JSONObject): Entry {
            val type = text(json, "type")
            require(type in setOf("preference", "fact", "experience")) { "不支持的记忆类型。" }
            val scope = json.getJSONObject("scope")
            checkScope(scope)
            val source = json.getJSONObject("source")
            require(text(source, "kind") in setOf("manual", "auto")) { "记忆来源无效。" }
            val state = text(json, "state")
            require(state in setOf("active", "pending")) { "记忆状态无效。" }
            require(json.has("expiresAt")) { "缺少记忆失效时间。" }
            return Entry(text(json, "id"), type, scope, text(json, "key"), text(json, "summary"), text(json, "content"),
                source, state, flag(json, "locked"), number(json, "createdAt"), number(json, "updatedAt"),
                if (json.isNull("expiresAt")) null else number(json, "expiresAt"))
        }

        private fun checkScope(scope: JSONObject) {
            when (text(scope, "kind")) {
                "user" -> require(scope.keys().asSequence().toSet() == setOf("kind")) { "用户范围不能包含工作区。" }
                "workspace" -> {
                    val path = text(scope, "workspace")
                    require(path.startsWith('/') && path.codePointCount(0, path.length) <= 2048 && !path.contains('\u0000') &&
                        scope.keys().asSequence().toSet() == setOf("kind", "workspace")) { "请填写有效的工作区绝对路径（最多 2048 个字符）。" }
                }
                else -> throw IllegalArgumentException("不支持的记忆范围。")
            }
        }

        fun save(revision: Long, id: String?, type: String, scope: JSONObject, key: String, summary: String,
                 content: String, expiresAt: Long?): JSONObject {
            require(revision in 0..MAX_INTEGER) { "请先重新读取记忆状态。" }
            require(type in setOf("preference", "fact", "experience")) { "请选择记忆类型。" }
            checkScope(scope)
            val topic = key.trim()
            val title = summary.trim()
            val body = content.trim()
            require(topic.isNotBlank() && topic.codePointCount(0, topic.length) <= 160 && !topic.contains('\u0000')) { "主题键须为 1 至 160 个字符。" }
            require(title.isNotBlank() && title.codePointCount(0, title.length) <= 160 && !title.contains('\u0000')) { "摘要须为 1 至 160 个字符。" }
            require(body.isNotBlank() && body.codePointCount(0, body.length) <= 2000 && !body.contains('\u0000')) { "正文须为 1 至 2000 个字符。" }
            require(expiresAt == null || expiresAt in 0..MAX_INTEGER) { "失效时间无效。" }
            return JSONObject().put("revision", revision).put("type", type).put("scope", scope).put("key", topic)
                .put("summary", title).put("content", body).put("expiresAt", expiresAt ?: JSONObject.NULL)
                .apply { if (id != null) { require(id.isNotBlank()) { "记忆编号无效。" }; put("id", id) } }
        }
    }
}
