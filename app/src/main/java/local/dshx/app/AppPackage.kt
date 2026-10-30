package local.dshx.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** dsh 创建的页面、脚本和声明；宿主只开放声明过的接口。 */
internal object AppPackage {
    const val MAX_BYTES = 1024 * 1024
    data class Setting(val id: String, val type: String, val label: String, val value: Any)
    data class Package(val id: String, val name: String, val version: String, val description: String,
        val entry: String, val background: String?, val files: Map<String, String>, val capabilities: Set<String>,
        val network: Set<String>, val settings: List<Setting>, val json: String) {
        fun settings(input: JSONObject): JSONObject {
            keys(input, settings.map { it.id }.toSet())
            val result = JSONObject()
            for (setting in settings) {
                val value = if (input.has(setting.id)) input.get(setting.id) else setting.value
                when (setting.type) {
                    "app-list" -> {
                        require(value is JSONArray) { "适用应用必须是列表。" }
                        val names = array(value, 100).map { require(it is String && packageName.matches(it) && it.length <= 255) { "应用包名无效。" }; it }
                        require(names.distinct().size == names.size) { "适用应用不能重复。" }
                    }
                    "switch" -> require(value is Boolean) { "开关值无效。" }
                    "text" -> require(value is String && value.length <= 2000) { "文字设置过长。" }
                    "number" -> require(value is Number && value.toDouble().isFinite() && value.toDouble() in -1e9..1e9) { "数字设置无效。" }
                }
                result.put(setting.id, value)
            }
            return result
        }
        fun scope(settings: JSONObject): Set<String> = settings.optJSONArray("apps")?.let { array(it).map(Any::toString).toSet() }.orEmpty()
        val usesScreen get() = capabilities.any { it.startsWith("screen.") }
        val origin get() = "https://$id.apps.dshx.local"
    }
    fun parse(value: JSONObject): Package {
        require(value.toString().toByteArray().size <= MAX_BYTES) { "微应用包不能超过 1 MiB。" }
        keys(value, setOf("format", "id", "name", "version", "description", "entry", "background", "files", "capabilities", "network", "settings"))
        require(value.getString("format") == "web-v1") { "仅支持 web-v1 应用包，请按内置技能重新生成。" }
        val id = text(value, "id", 64).also { require(it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "微应用编号无效。" } }
        val version = text(value, "version", 40).also { require(it.matches(Regex("[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}"))) { "版本请使用三段数字。" } }
        val capabilities = strings(value.getJSONArray("capabilities"), 8, 40).toSet()
        require(capabilities.all { it in setOf("storage", "screen.observe", "screen.click") }) { "包含宿主不支持的能力。" }
        require("screen.click" !in capabilities || "screen.observe" in capabilities) { "屏幕点击必须同时声明观察能力。" }
        val network = value.optJSONArray("network")?.let { strings(it, 8, 255).toSet() }.orEmpty()
        network.forEach { origin ->
            val uri = URI(origin)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawAuthority == uri.host &&
                uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null && !uri.host.endsWith(".dshx.local")) { "网络来源必须是无端口、无路径的 HTTPS 地址。" }
        }
        val rawFiles = value.getJSONObject("files")
        require(rawFiles.length() in 1..64) { "应用应包含 1 到 64 个文本文件。" }
        val files = rawFiles.keys().asSequence().associateWith { path ->
            require(validPath(path) && !path.startsWith("__dshx/") && path.substringAfterLast('.') in setOf("html", "js", "css", "json", "txt", "svg")) { "文件路径或类型无效：$path" }
            val content = rawFiles.get(path)
            require(content is String && content.toByteArray().size <= 256 * 1024) { "单个文件应为不超过 256 KiB 的文本。" }
            content as String
        }
        val entry = text(value, "entry", 160).also { require(it.endsWith(".html") && files.containsKey(it)) { "页面入口必须指向包内 HTML 文件。" } }
        val background = if (value.has("background")) text(value, "background", 160).also {
            require(it.endsWith(".js") && files.containsKey(it)) { "后台入口必须指向包内 JavaScript 文件。" }
        } else null
        val settings = array(value.getJSONArray("settings"), 16).map { raw ->
            val row = raw as? JSONObject ?: error("设置项必须是对象。")
            keys(row, setOf("id", "type", "label", "default"), true)
            val key = text(row, "id", 40).also { require(it.matches(Regex("[a-z][a-z0-9-]*"))) { "设置编号无效。" } }
            val type = text(row, "type", 20).also { require(it in setOf("app-list", "text", "number", "switch")) { "不支持的设置类型。" } }
            if (type == "app-list") require(row.get("default") is JSONArray && row.getJSONArray("default").length() == 0) { "应用范围默认必须为空。" }
            Setting(key, type, text(row, "label", 40), row.get("default"))
        }
        require(settings.map { it.id }.distinct().size == settings.size) { "设置编号不能重复。" }
        val app = Package(id, text(value, "name", 40), version, text(value, "description", 200), entry, background,
            files, capabilities, network, settings, canonical(value))
        if (app.usesScreen) require(settings.any { it.id == "apps" && it.type == "app-list" }) { "屏幕功能须提供 apps 适用应用设置。" }
        app.settings(JSONObject())
        return app
    }
    fun validPath(path: String) = path.length in 1..160 && path.split('/').all { it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) && it != "." && it != ".." }
    fun completeNodes(observation: JSONObject): List<JSONObject> {
        require(!observation.optBoolean("truncated", true)) { "observation-incomplete" }
        return array(observation.getJSONArray("nodes"), 200).map { it as JSONObject }.also { rows ->
            require(rows.none { it.optBoolean("textTruncated") }) { "observation-incomplete" }
        }
    }
    fun keys(value: JSONObject, allowed: Set<String>, exact: Boolean = false) {
        val actual = value.keys().asSequence().toSet()
        require(if (exact) actual == allowed else actual.all { it in allowed }) { "字段缺失或包含不支持的字段。" }
    }
    fun integer(value: Any, range: IntRange): Int {
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble() && value.toInt() in range) { "数字超出允许范围或不是整数。" }
        return value.toInt()
    }
    fun array(value: JSONArray, max: Int = 100): List<Any> {
        require(value.length() <= max) { "列表超过允许长度。" }
        return (0 until value.length()).map(value::get)
    }
    fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> array(value, 10000).joinToString(",", "[", "]", transform = ::canonical)
        else -> JSONArray().put(value).toString().let { it.substring(1, it.length - 1) }
    }
    private fun strings(value: JSONArray, max: Int, length: Int): List<String> = array(value, max).map {
        require(it is String && it.isNotBlank() && it.length <= length && it.none(Char::isISOControl)) { "文字字段无效。" }; it as String
    }
    private fun text(row: JSONObject, key: String, max: Int): String = row.getString(key).also {
        require(row.get(key) is String && it.isNotBlank() && it.length <= max && it.none(Char::isISOControl)) { "$key 文字无效。" }
    }
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
}
