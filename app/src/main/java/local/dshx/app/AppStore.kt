package local.dshx.app

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/** 仅原生管理入口发布活动版本；规则执行器只读取这里检查后的内存定义。 */
internal class AppStore(private val files: File, private val now: () -> Long = System::currentTimeMillis,
    private val visibleApps: (() -> Set<String>)? = null) {
    data class Installed(val app: AppPackage.Package, val enabled: Boolean, val settings: String, val revision: Long)
    private data class Checked(val app: AppPackage.Package, val hash: String, val expires: Long)
    private val root = File(files, "micro-apps")
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val checks = linkedMapOf<String, Checked>()
    @Volatile var error: String? = null
        private set
    private var damaged = false
    private var state = load()
    @Volatile private var installed = readInstalled()

    fun observe(listener: () -> Unit) { listeners.add(listener) }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
    fun active(): List<Installed> = installed.filter { it.enabled }

    /** 独立应用保存设置并保持停用，不因保存暂时获得手机操作权限。 */
    @Synchronized fun configure(id: String, settings: JSONObject, revision: Long): JSONObject {
        require(state.getJSONObject("apps").getJSONObject(validId(id)).getLong("revision") == revision) { "设置已变化，请重新打开设置。" }
        return change("configure", JSONObject().put("id", id).put("settings", settings)
            .put("operationId", UUID.randomUUID().toString()).put("expectedRevision", state.getLong("revision")))
    }

    @Synchronized fun call(action: String, data: JSONObject = JSONObject()): JSONObject {
        require(data.toString().toByteArray().size <= AppPackage.MAX_BYTES + 8192) { "微应用请求过大。" }
        return when (action) {
            "status" -> {
                AppPackage.keys(data, setOf("id", "operationId"))
                if (data.has("operationId")) state.getJSONObject("operations").optJSONObject(data.getString("operationId"))?.getJSONObject("result")?.let { JSONObject(it.toString()) }
                    ?: JSONObject().put("known", false).put("message", "操作结果未记录，请核实当前版本和状态。")
                else status(data.optString("id"))
            }
            "check" -> {
                AppPackage.keys(data, setOf("package"), true)
                val app = AppPackage.parse(data.getJSONObject("package"))
                checks.entries.removeAll { it.value.expires <= now() }
                require(checks.size < 16) { "检查草稿过多，请稍后重试。" }
                val hash = hash(app.json); val id = UUID.randomUUID().toString()
                checks[id] = Checked(app, hash, now() + 300_000)
                JSONObject().put("checkId", id).put("hash", hash).put("expiresAt", now() + 300_000).put("package", JSONObject(app.json))
            }
            "logs" -> {
                AppPackage.keys(data, setOf("id", "limit"))
                val id = validId(data.getString("id"))
                val limit = if (data.has("limit")) AppPackage.integer(data.get("limit"), 1..100) else 20
                val log = path("data/$id/events.jsonl")
                val rows = if (log.isFile) log.readLines().takeLast(limit).mapNotNull { runCatching { JSONObject(it) }.getOrNull() } else emptyList()
                JSONObject().put("events", JSONArray(rows.reversed()))
            }
            "install", "enable", "disable", "remove", "rollback" -> change(action, data)
            else -> error("不支持的微应用操作。")
        }
    }

    @Synchronized private fun change(action: String, data: JSONObject): JSONObject {
        require(!damaged) { "微应用状态文件损坏，原文件已保留，请先恢复数据。" }
        val common = setOf("operationId", "expectedRevision")
        val fields = when (action) {
            "install" -> setOf("checkId", "hash")
            "enable", "configure" -> setOf("id", "settings")
            "remove" -> setOf("id", "deleteData")
            "rollback" -> setOf("id", "version")
            else -> setOf("id")
        }
        AppPackage.keys(data, common + fields, true)
        val operation = data.getString("operationId").also { require(it.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "操作编号无效。" } }
        val signature = hash(action + AppPackage.canonical(data))
        state.getJSONObject("operations").optJSONObject(operation)?.let {
            require(it.getString("request") == signature) { "操作编号已用于不同请求。" }
            return JSONObject(it.getJSONObject("result").toString())
        }
        val revision = state.getLong("revision")
        require(data.get("expectedRevision") is Number && data.getDouble("expectedRevision") == revision.toDouble()) { "设置已变化，请刷新后重试。" }
        val next = JSONObject(state.toString()); val apps = next.getJSONObject("apps")
        var removed: String? = null
        when (action) {
            "install" -> {
                val checked = checks[data.getString("checkId")] ?: error("检查标识已失效，请重新检查。")
                require(checked.expires > now() && checked.hash == data.getString("hash")) { "检查已过期或内容不一致。" }
                val app = checked.app
                require(apps.has(app.id) || apps.length() < 32) { "微应用数量已达 32 个。" }
                val versions = next.getJSONObject("versions")
                val history = versions.optJSONObject(app.id) ?: JSONObject().also { versions.put(app.id, it) }
                require(!history.has(app.version) || history.getString(app.version) == checked.hash) { "同一版本已有不同内容，请增加版本号。" }
                require(history.has(app.version) || history.length() < 64) { "历史版本已达上限。" }
                val target = path("packages/${app.id}/${app.version}/app.json")
                if (target.exists()) require(hash(target.readText()) == checked.hash) { "同一版本已有不同内容，请增加版本号。" }
                else write(target, app.json)
                history.put(app.version, checked.hash)
                val previous = apps.optJSONObject(app.id)
                val settings = runCatching { app.settings(previous?.optJSONObject("settings") ?: JSONObject()) }.getOrElse { app.settings(JSONObject()) }
                // 新版本可能改变行为；安装和回滚都暂停，保留配置后由用户重新启用。
                apps.put(app.id, JSONObject().put("version", app.version).put("hash", checked.hash).put("enabled", false).put("settings", settings).put("revision", revision + 1))
            }
            "rollback" -> {
                val id = validId(data.getString("id")); val old = apps.getJSONObject(id)
                val version = data.getString("version").also { require(it.matches(Regex("[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}"))) }
                val savedHash = next.getJSONObject("versions").getJSONObject(id).getString(version)
                val file = path("packages/$id/$version/app.json")
                require(file.length() <= AppPackage.MAX_BYTES)
                val text = file.readText()
                require(hash(text) == savedHash) { "历史版本内容已变化，不能回滚。" }
                val app = AppPackage.parse(JSONObject(text))
                require(app.id == id && app.version == version)
                apps.put(id, JSONObject().put("version", version).put("hash", hash(app.json)).put("enabled", false).put("revision", revision + 1)
                    .put("settings", runCatching { app.settings(old.getJSONObject("settings")) }.getOrElse { app.settings(JSONObject()) }))
            }
            else -> {
                val id = validId(data.getString("id")); val row = apps.getJSONObject(id)
                row.put("revision", revision + 1)
                when (action) {
                    "enable", "configure" -> {
                        val app = readPackage(id, row)
                        val settings = app.settings(data.getJSONObject("settings"))
                        if (action == "enable" && app.usesScreen) require(app.scope(settings).isNotEmpty()) { "请先选择适用应用。" }
                        visibleApps?.invoke()?.let { visible -> app.settings.filter { it.type == "app-list" }.forEach {
                            require(AppPackage.array(settings.getJSONArray(it.id)).all { name -> name in visible }) { "适用范围包含不可见或不可用的应用，请重新选择。" }
                        } }
                        row.put("settings", settings).put("enabled", action == "enable")
                    }
                    "disable" -> row.put("enabled", false)
                    "remove" -> { require(data.get("deleteData") is Boolean); apps.remove(id); if (data.getBoolean("deleteData")) removed = id }
                }
            }
        }
        next.put("revision", revision + 1)
        val result = JSONObject().put("known", true).put("operationId", operation).put("revision", revision + 1).put("action", action)
        val operations = next.getJSONObject("operations")
        operations.put(operation, JSONObject().put("request", signature).put("result", result))
        while (operations.length() > 100) operations.remove(operations.keys().next())
        write(path("state.json"), next.toString())
        state = next; error = null; installed = readInstalled()
        listeners.forEach { it() }
        // 删除记录不影响已发布的停用状态；失败时保留数据并在回执中说明。
        val reply = JSONObject(result.toString())
        removed?.let { id -> if (!path("data/$id").deleteRecursively()) reply.put("cleanupWarning", "已移除功能，部分独立记录未删除。") }
        return reply
    }

    @Synchronized fun status(id: String = ""): JSONObject {
        val rows = state.getJSONObject("apps")
        val apps = rows.keys().asSequence().filter { id.isBlank() || it == id }.map { key ->
            val row = rows.getJSONObject(key)
            runCatching {
                val app = readPackage(key, row)
                JSONObject().put("id", key).put("name", app.name).put("description", app.description).put("version", app.version)
                    .put("enabled", row.getBoolean("enabled")).put("revision", row.optLong("revision", 0)).put("settings", JSONObject(row.getJSONObject("settings").toString()))
                    .put("package", JSONObject(app.json).apply { if (id.isBlank()) remove("files") })
                    .put("versions", JSONArray(state.getJSONObject("versions").optJSONObject(key)?.keys()?.asSequence()?.toList().orEmpty()))
            }.getOrElse { JSONObject().put("id", key).put("name", key).put("version", row.optString("version"))
                .put("enabled", false).put("error", "应用包校验失败，请重新安装已检查版本。") }
        }.toList()
        return JSONObject().put("revision", state.getLong("revision")).put("apps", JSONArray(apps)).put("capabilities", capabilities())
    }

    @Synchronized fun record(id: String, version: String, rule: String, outcome: String, reason: String, trigger: String) {
        if (state.getJSONObject("apps").optJSONObject(id)?.optString("version") != version) return
        val file = path("data/${validId(id)}/events.jsonl")
        val rows = if (file.isFile) file.readLines().takeLast(499) else emptyList()
        val row = JSONObject().put("at", now()).put("id", id).put("version", version).put("rule", rule)
            .put("outcome", outcome).put("reason", reason).put("trigger", trigger)
        write(file, (rows + row.toString()).joinToString("\n", postfix = "\n"))
    }

    @Synchronized fun storage(id: String, action: String, args: JSONObject, revision: Long? = null): JSONObject {
        if (revision != null) require(installed.any { it.app.id == id && it.enabled && it.revision == revision }) { "应用状态已变化。" }
        require(state.getJSONObject("apps").has(validId(id))) { "微应用已移除。" }
        AppPackage.keys(args, if (action == "set") setOf("key", "value") else setOf("key"), true)
        require(action in setOf("get", "set", "remove"))
        val key = args.getString("key").also { require(it.length in 1..80 && it.none(Char::isISOControl)) }
        val file = path("data/$id/storage.json")
        require(!file.exists() || file.length() <= AppPackage.MAX_BYTES) { "应用数据超过上限。" }
        val data = if (file.exists()) JSONObject(file.readText()) else JSONObject()
        when (action) {
            "get" -> return JSONObject().put("value", data.opt(key) ?: JSONObject.NULL)
            "set" -> data.put(key, args.get("value"))
            "remove" -> data.remove(key)
        }
        require(data.toString().toByteArray().size <= AppPackage.MAX_BYTES) { "应用独立数据不能超过 1 MiB。" }
        write(file, data.toString())
        return JSONObject().put("ok", true)
    }

    @Synchronized fun verify(): Boolean = runCatching {
        if (state.getLong("revision") == 0L && !path("state.json").exists()) return true
        require(path("state.json").readText() == state.toString())
        installed.forEach { entry -> readPackage(entry.app.id, state.getJSONObject("apps").getJSONObject(entry.app.id)) }
        true
    }.getOrElse { error = "微应用文件发生外部变化，已暂停，请重新检查安装。"; false }

    private fun readInstalled(): List<Installed> = state.getJSONObject("apps").let { apps -> apps.keys().asSequence().mapNotNull { id ->
        runCatching { val row = apps.getJSONObject(id); Installed(readPackage(id, row), row.getBoolean("enabled"), row.getJSONObject("settings").toString(), row.optLong("revision", 0)) }.getOrNull()
    }.toList() }

    private fun readPackage(id: String, row: JSONObject): AppPackage.Package {
        val version = row.getString("version")
        require(version.matches(Regex("[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}")))
        val file = path("packages/${validId(id)}/$version/app.json")
        require(file.length() <= AppPackage.MAX_BYTES) { "微应用包过大。" }
        val text = file.readText()
        require(hash(text) == row.getString("hash")) { "应用包内容摘要不一致。" }
        return AppPackage.parse(JSONObject(text)).also { require(it.id == id && it.version == version); it.settings(row.getJSONObject("settings")) }
    }
    private fun load(): JSONObject {
        val file = path("state.json")
        fun empty() = JSONObject().put("format", 1).put("revision", 0).put("apps", JSONObject()).put("operations", JSONObject()).put("versions", JSONObject())
        if (!file.exists() && !File(file.path + ".bak").exists()) return empty()
        return try {
            require(file.length() <= 2 * 1024 * 1024) { "微应用状态文件过大。" }
            val bytes = AtomicFile(file).openRead().use { it.readBytes() }
            JSONObject(bytes.toString(Charsets.UTF_8)).also {
                AppPackage.keys(it, setOf("format", "revision", "apps", "operations", "versions"), true)
                require(it.getInt("format") == 1 && it.getLong("revision") >= 0 && it.getJSONObject("apps").length() <= 32)
            }
        } catch (_: Exception) {
            damaged = true; error = "微应用状态文件损坏，已暂停，原文件保留。"; empty()
        }
    }
    private fun path(relative: String): File {
        val file = File(root, relative)
        var part: File? = file
        while (part != null && part != files) {
            require(!Files.isSymbolicLink(part.toPath())) { "微应用路径不能包含链接。" }
            part = part.parentFile
        }
        require(part == files && file.canonicalPath.startsWith(root.absolutePath + File.separator)) { "微应用路径无效。" }
        return file
    }
    private fun write(file: File, text: String) {
        require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建微应用目录。" }
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(text.toByteArray()); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    private fun validId(id: String): String = id.also { require(it.length in 1..64 && it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "微应用编号无效。" } }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        fun capabilities(): JSONObject = JSONObject().put("formats", JSONArray(listOf("web-v1")))
            .put("capabilities", JSONArray(listOf("storage", "screen.observe", "screen.click")))
            .put("settings", JSONArray(listOf("app-list", "text", "number", "switch")))
            .put("runtime", "android-webview").put("sdk", 1).put("background", true)
            .put("network", "declared-https-origins").put("maxPackageBytes", AppPackage.MAX_BYTES)
            .put("maxFiles", 64).put("maxNodes", 200).put("maxTextChars", 256).put("maxApps", 32).put("operationHistory", 100)
    }
}
