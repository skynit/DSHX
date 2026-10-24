package local.dshx.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID

/** Android 只管理暂存和通信；正式目录、原文和任务由 Node 写入。 */
class KnowledgeStore(private val filesDir: File) {
    private val root get() = StoragePaths(filesDir).knowledge
    val workspace: String get() = File(filesDir, "workspace").absolutePath

    // 随应用引擎保留编辑上下文；切换标签或重建 Activity 不改变草稿所属资料。
    var draft: Draft? = null
    var optionsDraft: JSONObject? = null
    var collectionDraft: JSONObject? = null

    data class Draft(val source: Source?, val original: String, val title: String, val note: String,
                     val text: String, val collectionId: String, val operationId: String? = null) {
        fun request(): Pair<String, JSONObject> {
            require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE) { "正文不能为空，且不能超过 2 MiB。" }
            require(title.trim().isNotBlank()) { "请填写资料标题。" }
            require(collectionId.isNotBlank()) { "请先创建或选择集合。" }
            val data = change(source?.revision)
            if (source == null) return "import" to data.put("title", title.trim()).put("format", "md")
                .put("text", text).put("collectionId", collectionId)
            val patch = JSONObject()
            if (title.trim() != source.title) patch.put("title", title.trim())
            if (note != source.note) patch.put("note", note)
            if (collectionId != source.collectionId) patch.put("collectionId", collectionId)
            if (text != original) patch.put("text", text)
            require(patch.length() > 0) { "没有要保存的修改。" }
            return "update" to data.put("sourceId", source.id).put("patch", patch)
        }
    }

    data class Transfer(val busy: Boolean = false, val message: String = "", val error: Boolean = false, val revision: Long = 0)
    data class Input(val title: String, val open: () -> InputStream)
    data class Ticket(val id: String, val operation: JSONObject, val bytes: Long, val hash: String, val format: String, val title: String) {
        fun json(): JSONObject = JSONObject().put("ticket", id).put("operationId", operation.getString("operationId"))
            .put("bytes", bytes).put("hash", hash).put("format", format).put("title", title)
    }
    data class Collection(val id: String, val parentId: String?, val title: String, val scope: JSONObject, val revision: Long) {
        val scopeText get() = if (scope.getString("kind") == "user") "用户通用" else "当前工作区"
    }
    data class Source(val id: String, val collectionId: String, val title: String, val note: String, val format: String,
                      val revision: Long, val version: Long, val bytes: Long, val state: String, val scope: JSONObject)
    data class Job(val id: String, val operationId: String, val type: String, val state: String, val stage: String,
                   val effect: String, val progress: String, val errorCode: String?, val result: JSONObject?) {
        val active get() = state in setOf("waiting", "running")
        val label get() = when (state) { "waiting" -> "等待中"; "running" -> "处理中"; "waitingRetry" -> "待继续";
            "succeeded" -> "已完成"; "failed" -> "阶段失败"; else -> "已取消" }
        val effectText get() = when (effect) { "published" -> "资料已发布"; "removed" -> "删除已生效";
            "switched" -> "索引已切换"; "restored" -> "恢复已生效"; else -> "尚未生效" }
        val stageText get() = when (stage) { "prepare" -> "准备"; "publish" -> "发布"; "keyword" -> "关键词";
            "embedding" -> "语义处理"; "summary" -> "摘要"; "cleanup" -> "清理"; "validate" -> "校验";
            "switch" -> "切换"; "export" -> "导出"; "restore" -> "恢复"; else -> stage }
    }
    data class Status(val ready: Boolean, val enabled: Boolean, val settingsRevision: Long, val catalogRevision: Long,
                      val embedding: JSONObject, val summary: JSONObject, val collections: List<Collection>,
                      val sources: List<Source>, val jobs: List<Job>, val operations: List<JSONObject>, val raw: JSONObject)
    data class Catalog(val collections: List<Collection>, val sources: List<Source>, val revision: Long, val nextOffset: Long? = null)
    data class RefPage(val ref: String, val title: String, val text: String, val nextOffset: Long?)
    data class Page(val source: Source, val text: String, val nextOffset: Long?, val truncated: Boolean)
    class Error(val code: String, message: String) : IllegalStateException(message)

    /** 有界流式复制，完成并同步后才允许登记票据，失败时删除半份文件。 */
    fun stage(input: InputStream, title: String, format: String, operation: JSONObject): Ticket {
        require(format in setOf("md", "txt", "markdown", "text", "gzip")) { "仅支持 Markdown、TXT 或知识归档。" }
        uuid(text(operation, "operationId"))
        require(number(operation, "createdAt") > 0) { "知识操作时间无效。" }
        val id = UUID.randomUUID().toString()
        val directory = File(root, "inbox/$id")
        require(directory.mkdirs()) { "无法创建知识暂存目录。" }
        try {
            require(directory.canonicalFile.parentFile == File(root, "inbox").canonicalFile) { "知识暂存目录无效。" }
            val hash = MessageDigest.getInstance("SHA-256")
            val limit = if (format == "gzip") MAX_ARCHIVE else MAX_SOURCE
            var bytes = 0L
            File(directory, "input").outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("已停止文件复制。")
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= limit) { "文件超过${if (format == "gzip") "256 MiB" else "2 MiB"}上限。" }
                    output.write(buffer, 0, count)
                    hash.update(buffer, 0, count)
                }
                require(bytes > 0) { "不能导入空文件。" }
                output.fd.sync()
            }
            return Ticket(id, operation, bytes, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, format, title.take(160))
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun discard(ticket: Ticket) { File(root, "inbox/${ticket.id}").deleteRecursively() }

    /** 导出结果只能指向当前操作的 exports 目录；流式交付且验证字节数和摘要。 */
    fun export(result: JSONObject, operationId: String, output: OutputStream): Long {
        uuid(operationId)
        val directory = File(root, "exports/$operationId").canonicalFile
        val archive = result.optJSONObject("archive") ?: result
        if (archive.has("ticket")) require(text(archive, "ticket") == operationId) { "知识归档票据与操作不匹配。" }
        val path = (archive.opt("path") as? String) ?: if (archive.optString("ticket") == operationId)
            File(directory, "archive.dshx").absolutePath else throw IllegalArgumentException("导出任务尚未提供归档文件，请刷新任务。")
        val file = if (File(path).isAbsolute) File(path) else File(root, path)
        require(file.canonicalFile.parentFile == directory &&
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.canonicalFile == file.absoluteFile) { "知识导出文件位置无效。" }
        val expected = if (archive.has("bytes")) number(archive, "bytes") else file.length()
        require(expected in 1..MAX_ARCHIVE && file.length() == expected) { "知识归档大小无效。" }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                count += size
                require(count <= expected) { "知识归档在保存期间发生变化。" }
                digest.update(buffer, 0, size)
                output.write(buffer, 0, size)
            }
        }
        require(count == expected) { "知识归档写出不完整。" }
        if (archive.has("hash")) require(text(archive, "hash") == digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }) { "知识归档摘要不匹配。" }
        output.flush()
        return count
    }

    companion object {
        const val MAX_FRAME = 256 * 1024
        const val MAX_SOURCE = 2L * 1024 * 1024
        const val MAX_ARCHIVE = 256L * 1024 * 1024
        private const val MAX_INTEGER = 9_007_199_254_740_991L
        private val changes = setOf("settings", "collection", "import", "update", "remove", "rebuild", "cancel", "retry", "export", "restore")
        private val states = setOf("waiting", "running", "waitingRetry", "succeeded", "failed", "cancelled")
        private val effects = setOf("none", "published", "removed", "switched", "restored")
        private val stages = setOf("prepare", "publish", "keyword", "embedding", "summary", "cleanup", "validate", "switch", "export", "restore")

        fun operation(): JSONObject = JSONObject().put("operationId", UUID.randomUUID().toString()).put("createdAt", System.currentTimeMillis())
        fun change(revision: Long? = null): JSONObject = operation().apply { revision?.let { put("expectedRevision", it) } }
        private fun keys(value: JSONObject, allowed: Set<String>) {
            require(value.keys().asSequence().all { it in allowed }) { "知识请求包含不支持的字段。" }
        }
        fun number(value: JSONObject, key: String): Long {
            val number = value.get(key)
            require(number is Number && number.toDouble().isFinite() && number.toDouble() == number.toLong().toDouble() && number.toLong() in 0..MAX_INTEGER) { "知识数据的 $key 必须是非负整数。" }
            return number.toLong()
        }
        private fun flag(value: JSONObject, key: String): Boolean {
            require(value.get(key) is Boolean) { "知识数据的 $key 必须是开关。" }
            return value.getBoolean(key)
        }
        fun text(value: JSONObject, key: String, limit: Int = MAX_FRAME): String {
            val text = value.get(key)
            require(text is String && !text.contains('\u0000') && text.toByteArray(Charsets.UTF_8).size <= limit) { "知识数据的 $key 格式无效。" }
            return text
        }
        private fun id(value: JSONObject, key: String) { require(text(value, key, 256).isNotBlank()) { "知识编号不能为空。" } }
        private fun uuid(value: String) { require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "知识操作编号无效。" } }
        fun scope(value: JSONObject) {
            when (text(value, "kind")) {
                "user" -> keys(value, setOf("kind"))
                "workspace" -> { keys(value, setOf("kind", "workspace")); require(text(value, "workspace", 8192).startsWith('/')) { "知识工作区无效。" } }
                else -> throw IllegalArgumentException("知识适用范围无效。")
            }
        }
        fun request(action: String, data: JSONObject): JSONObject {
            val base = if (action in changes) setOf("operationId", "createdAt") else emptySet()
            val fields = when (action) {
                "status" -> setOf("operationId", "jobId")
                "list" -> setOf("collectionId", "offset", "limit")
                "read" -> setOf("sourceId", "id", "offset", "ref", "maxChars")
                "search" -> setOf("query", "withinRef", "limit", "kind", "cursor")
                "settings" -> setOf("expectedRevision", "settings")
                "collection" -> setOf("expectedRevision", "type", "id", "parentId", "title", "scope")
                "import" -> setOf("expectedRevision", "text", "ticket", "title", "format", "collectionId")
                "update" -> setOf("sourceId", "expectedRevision", "patch", "ticket")
                "remove" -> setOf("sourceId", "expectedRevision")
                "rebuild" -> setOf("expectedRevision", "kind", "sourceId", "collectionId")
                "cancel", "retry" -> setOf("jobId")
                "export" -> setOf("expectedRevision", "collectionId")
                "restore" -> setOf("expectedRevision", "ticket", "scope")
                "ticket" -> setOf("ticket", "operationId", "bytes", "hash", "format", "title")
                else -> throw IllegalArgumentException("不支持的知识操作。")
            }
            keys(data, base + fields)
            if (action in changes) { uuid(text(data, "operationId")); require(number(data, "createdAt") > 0) { "知识操作时间无效。" } }
            if (data.has("operationId")) uuid(text(data, "operationId"))
            if (data.has("expectedRevision")) number(data, "expectedRevision")
            for (name in listOf("sourceId", "collectionId", "jobId", "id")) if (data.has(name)) id(data, name)
            if (data.has("scope")) scope(data.getJSONObject("scope"))
            when (action) {
                "list" -> { if (data.has("offset")) number(data, "offset"); if (data.has("limit")) require(number(data, "limit") in 1..100) }
                "read" -> { if (data.has("ref")) text(data, "ref", 2000) else id(data, if (data.has("sourceId")) "sourceId" else "id"); if (data.has("maxChars")) require(number(data, "maxChars") in 1..3000); if (data.has("offset")) number(data, "offset") }
                "search" -> { require(text(data, "query", 4096).isNotBlank()) { "请输入搜索内容。" }; if (data.has("limit")) require(number(data, "limit") in 1..10)
                    if (data.has("kind")) require(text(data, "kind") in setOf("all", "document", "memory"))
                    if (data.has("withinRef")) text(data, "withinRef", 2000)
                }
                "settings" -> { number(data, "expectedRevision"); val settings = data.getJSONObject("settings"); keys(settings, setOf("enabled", "embedding", "summary"));
                    if (settings.has("enabled")) flag(settings, "enabled")
                    for (name in listOf("embedding", "summary")) if (settings.has(name)) configuration(settings.getJSONObject(name), name)
                }
                "collection" -> { val type = text(data, "type"); require(type in setOf("create", "update", "remove")); number(data, "expectedRevision")
                    if (type != "create") id(data, "id")
                    if (type != "remove") require(text(data, "title", 640).isNotBlank()) { "集合名称不能为空。" }
                    if (data.has("parentId") && !data.isNull("parentId")) id(data, "parentId")
                }
                "import" -> { id(data, "collectionId"); require(data.has("text") xor data.has("ticket")) { "请选择文件或填写正文。" }
                    require(text(data, "format") in setOf("md", "txt", "markdown", "text")); require(text(data, "title", 640).isNotBlank()) { "请填写资料标题。" }
                    if (data.has("text")) require(text(data, "text").isNotBlank()) { "正文不能为空。" }
                }
                "update" -> { id(data, "sourceId"); number(data, "expectedRevision"); val patch = data.getJSONObject("patch"); keys(patch, setOf("title", "note", "collectionId", "text"));
                    require(patch.length() > 0 || data.has("ticket")) { "没有要保存的修改。" }
                    require(!(patch.has("text") && data.has("ticket"))) { "正文和替换文件不能同时提交。" }
                    for (name in listOf("title", "note", "collectionId", "text")) if (patch.has(name)) text(patch, name)
                    if (patch.has("title")) require(patch.getString("title").isNotBlank()) { "资料标题不能为空。" }
                }
                "remove" -> { id(data, "sourceId"); number(data, "expectedRevision") }
                "rebuild" -> require(text(data, "kind") in setOf("keyword", "semantic", "summary", "chunks")) { "重建类型无效。" }
                "cancel", "retry" -> id(data, "jobId")
                "ticket" -> { uuid(text(data, "ticket")); require(number(data, "bytes") in 1..MAX_ARCHIVE); require(text(data, "hash").matches(Regex("[a-f0-9]{64}"))); require(text(data, "format") in setOf("md", "txt", "markdown", "text", "gzip")); text(data, "title", 640) }
                "restore" -> { uuid(text(data, "ticket")); if (data.has("scope")) scope(data.getJSONObject("scope")) }
            }
            if (data.has("ticket")) uuid(text(data, "ticket"))
            require(JSONObject().put("knowledge", UUID.randomUUID().toString()).put("action", action).put("data", data)
                .toString().toByteArray(Charsets.UTF_8).size <= MAX_FRAME) { "知识请求超过 256 KiB，请改用文件导入。" }
            return data
        }

        fun configurationFields(kind: String): Set<String> = if (kind == "embedding") setOf("enabled", "provider", "model", "protocol", "dimensions", "normalized", "normalize",
                "inputTemplateVersion", "kinds", "sourceKinds", "batchSize", "maxInputTokens", "maxRequestBytes", "queryPrefix", "documentPrefix", "sendDimensions", "configId", "endpointHash")
                else setOf("enabled", "provider", "model", "maxInputBytes", "promptVersion", "configId")

        fun settingsPatch(previous: JSONObject, value: JSONObject, kind: String): JSONObject? {
            val fields = configurationFields(kind) - setOf("configId", "endpointHash")
            fun clean(input: JSONObject) = JSONObject().apply {
                input.keys().asSequence().filter { it in fields }.forEach { put(it, input.get(it)) }
                if (kind == "embedding") {
                    val kinds = input.optJSONArray("kinds") ?: input.optJSONArray("sourceKinds")
                    remove("sourceKinds")
                    if (kinds != null) put("kinds", kinds)
                }
            }
            val before = clean(previous)
            val after = clean(value)
            if (fields.all { before.opt(it)?.toString() == after.opt(it)?.toString() }) return null
            // 配置标识与端点摘要由宿主按新配置生成，客户端不能沿用旧身份。
            return after
        }

        private fun configuration(value: JSONObject, kind: String) {
            keys(value, configurationFields(kind))
            flag(value, "enabled")
            for (name in listOf("provider", "model", "configId", "endpointHash")) if (value.has(name)) text(value, name, 1024)
            for (name in listOf("dimensions", "inputTemplateVersion", "batchSize", "maxInputTokens", "maxRequestBytes", "maxInputBytes", "promptVersion"))
                if (value.has(name)) require(number(value, name) > 0) { "模型参数必须是正整数。" }
            for (name in listOf("normalized", "normalize", "sendDimensions")) if (value.has(name)) flag(value, name)
            if (value.has("protocol")) require(text(value, "protocol") in setOf("openai", "openai-embeddings"))
            for (name in listOf("queryPrefix", "documentPrefix")) if (value.has(name)) text(value, name, 1024)
            for (name in listOf("kinds", "sourceKinds")) if (value.has(name)) {
                val kinds = value.getJSONArray(name)
                require(kinds.length() in 1..2 && (0 until kinds.length()).all { kinds.get(it) is String && kinds.getString(it) in setOf("document", "memory") }) { "语义处理范围无效。" }
            }
            if (value.getBoolean("enabled")) {
                require(text(value, "provider", 1024).isNotBlank() && text(value, "model", 1024).isNotBlank()) { "请填写服务与模型。" }
                if (kind == "embedding") require(number(value, "dimensions") in 1..8192) { "嵌入维度无效。" }
            }
        }
        fun error(code: String): String = when (code) {
            "KNOWLEDGE_INPUT" -> "知识请求无效，请检查填写内容。"
            "KNOWLEDGE_NOT_READY" -> "知识引擎尚未就绪，请刷新状态。"
            "KNOWLEDGE_CONFLICT" -> "资料修订已变化，草稿已保留；请重新核对后保存。"
            "KNOWLEDGE_MISSING" -> "资料或任务已不存在，请刷新目录。"
            "KNOWLEDGE_VERSION_CHANGED" -> "来源版本已更新，请重新打开原文。"
            "KNOWLEDGE_BUSY" -> "目标正在处理其他任务，请稍后重试。"
            "KNOWLEDGE_LIMIT" -> "已达到知识处理上限，请检查文件大小或任务数量。"
            "KNOWLEDGE_WRITE" -> "知识数据保存失败，请检查存储空间。"
            "KNOWLEDGE_INDEX" -> "知识索引尚不可用，可尝试重建关键词。"
            "KNOWLEDGE_MODEL" -> "模型服务不可用，请检查对应服务配置。"
            "KNOWLEDGE_CANCELLED" -> "已停止尚未完成的处理，请查看实际生效状态。"
            "KNOWLEDGE_OPERATION_EXPIRED" -> "原操作已超过重试期限，请先查询实际结果。"
            else -> "知识处理失败，请刷新状态并核实原操作。"
        }
        fun response(value: JSONObject): JSONObject {
            require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_FRAME) { "知识响应超过 256 KiB。" }
            keys(value, setOf("knowledge", "ok", "result", "error", "code"))
            uuid(text(value, "knowledge"))
            if (flag(value, "ok")) {
                require(!value.has("error") && !value.has("code")) { "知识成功响应包含错误字段。" }
                value.getJSONObject("result")
            } else {
                require(!value.has("result")) { "知识失败响应包含结果字段。" }
                require(text(value, "code", 128).matches(Regex("KNOWLEDGE_[A-Z_]+"))) { "知识错误码无效。" }
                require(text(value, "error", 4096).isNotBlank()) { "知识错误信息为空。" }
            }
            return value
        }

        private fun <T> rows(value: JSONObject, name: String, limit: Int, parse: (JSONObject) -> T): List<T> {
            val rows = value.getJSONArray(name)
            require(rows.length() <= limit) { "知识列表超出限制。" }
            return List(rows.length()) { parse(rows.getJSONObject(it)) }
        }
        fun collection(value: JSONObject): Collection {
            id(value, "id"); val scope = value.getJSONObject("scope"); scope(scope)
            return Collection(text(value, "id"), if (value.isNull("parentId")) null else text(value, "parentId", 256),
                text(value, "title", 800), scope, number(value, "revision"))
        }
        fun source(value: JSONObject): Source {
            id(value, "id"); id(value, "collectionId"); val scope = value.getJSONObject("scope"); scope(scope)
            val format = text(value, "format"); require(format in setOf("md", "txt", "markdown", "text"))
            val bytes = number(value, "bytes"); require(bytes <= MAX_SOURCE)
            require(text(value, "state") in setOf("active", "deleted", "unavailable")) { "知识来源状态无效。" }
            require(number(value, "revision") > 0 && number(value, "version") > 0) { "知识来源版本无效。" }
            return Source(text(value, "id"), text(value, "collectionId"), text(value, "title", 800),
                if (value.has("note")) text(value, "note", 8192) else "", format, number(value, "revision"), number(value, "version"),
                bytes, text(value, "state", 80), scope)
        }
        fun job(value: JSONObject): Job {
            id(value, "id"); uuid(text(value, "operationId"))
            val state = text(value, "state"); require(state in states) { "知识任务状态无效。" }
            if (value.has("attempts")) number(value, "attempts")
            if (value.has("cancelRequested")) flag(value, "cancelRequested")
            val stage = text(value, "stage"); require(stage in stages) { "知识任务阶段无效。" }
            val effect = text(value, "effect"); require(effect in effects) { "知识任务生效状态无效。" }
            val progress = when (val progress = value.opt("progress")) {
                is Number -> {
                    val count = progress.toDouble()
                    require(count.isFinite() && count >= 0)
                    if (count in 0.0..1.0) "进度 ${(count * 100).toInt()}%" else "已处理 $progress"
                }
                is JSONObject -> {
                    val key = if (progress.has("done")) "done" else "completed"
                    val done = number(progress, key)
                    if (progress.has("total") && !progress.isNull("total")) { val total = number(progress, "total"); require(done <= total); "已处理 $done / $total" } else "已处理 $done"
                }
                null, JSONObject.NULL -> ""
                else -> throw IllegalArgumentException("知识任务进度无效。")
            }
            val error = if (value.isNull("errorCode")) null else text(value, "errorCode", 128).also { require(it.matches(Regex("KNOWLEDGE_[A-Z_]+"))) }
            return Job(text(value, "id"), text(value, "operationId"), text(value, "type", 80), state, stage, effect, progress, error,
                if (value.isNull("result")) null else value.getJSONObject("result"))
        }
        fun status(value: JSONObject): Status {
            if (value.has("ready") && !flag(value, "ready") && !value.has("settingsRevision"))
                throw IllegalStateException("知识引擎尚未就绪，请刷新状态；原有资料不会被覆盖。")
            val embedding = value.getJSONObject("embedding")
            val summary = value.getJSONObject("summary")
            if (!value.isNull("job")) job(value.getJSONObject("job"))
            if (!value.isNull("operation")) {
                val operation = value.getJSONObject("operation")
                uuid(text(operation, "operationId")); number(operation, "createdAt")
                if (operation.has("effect")) require(text(operation, "effect") in effects)
                if (!operation.isNull("result")) operation.getJSONObject("result")
            }
            for (config in listOf(embedding, summary)) if (config.has("enabled")) flag(config, "enabled")
            return Status(flag(value, "ready"), flag(value, "enabled"), number(value, "settingsRevision"), number(value, "catalogRevision"),
                embedding, summary, rows(value, "collections", 100, ::collection), rows(value, "sources", 100, ::source),
                rows(value, "jobs", 1024, ::job), rows(value, "operations", 4096) { operation ->
                    uuid(text(operation, "operationId")); number(operation, "createdAt")
                    if (operation.has("effect")) require(text(operation, "effect") in effects)
                    if (!operation.isNull("jobId")) id(operation, "jobId")
                    operation
                }, value)
        }
        fun catalog(value: JSONObject): Catalog = Catalog(rows(value, "collections", 100, ::collection), rows(value, "sources", 100, ::source),
            number(value, "catalogRevision"), if (value.isNull("nextOffset")) null else number(value, "nextOffset"))

        /** 所有页使用同一目录修订；变化时整体重读一次，禁止混合两个版本。 */
        fun loadCatalog(call: (String, JSONObject) -> JSONObject): Catalog {
            repeat(2) { attempt ->
                val collections = linkedMapOf<String, Collection>()
                val sources = linkedMapOf<String, Source>()
                var revision: Long? = null
                var offset = 0L
                var changed = false
                var pages = 0
                do {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("已停止目录读取。")
                    require(++pages <= 201) { "知识目录分页过多，请刷新。" }
                    val page = catalog(call("list", JSONObject().put("offset", offset).put("limit", 20)))
                    if (revision != null && revision != page.revision) { changed = true; break }
                    revision = page.revision
                    page.collections.forEach { collections[it.id] = it }
                    page.sources.forEach { sources[it.id] = it }
                    require(collections.size <= 100 && sources.size <= 100) { "知识目录超过当前容量限制。" }
                    val next = page.nextOffset
                    require(next == null || next > offset) { "知识目录分页位置无效。" }
                    offset = next ?: -1
                } while (offset >= 0)
                if (!changed) return Catalog(collections.values.toList(), sources.values.toList(), requireNotNull(revision))
                if (attempt == 1) throw Error("KNOWLEDGE_VERSION_CHANGED", "目录在读取时持续变化，请稍后刷新。")
            }
            throw IllegalStateException("知识目录读取失败。")
        }
        fun page(value: JSONObject): Page {
            val next = if (value.isNull("nextOffset")) null else number(value, "nextOffset")
            val truncated = flag(value, "truncated")
            require(!truncated || next != null) { "知识原文分页缺少下一位置。" }
            return Page(source(value.getJSONObject("source")), text(value, "text"), next, truncated)
        }
        fun reference(value: JSONObject): RefPage {
            val ref = text(value, "ref", 2000)
            require(text(value, "kind") in setOf("document", "memory", "overview")) { "知识引用类型无效。" }
            val offset = number(value, "offset")
            val next = if (value.isNull("nextOffset")) null else number(value, "nextOffset")
            val truncated = flag(value, "truncated")
            require((!truncated || next != null) && (next == null || next > offset)) { "知识引用分页无效。" }
            if (value.has("sourceVersion")) number(value, "sourceVersion")
            return RefPage(ref, text(value, "title", 8192), text(value, "text"), next)
        }
        fun search(value: JSONObject): List<JSONObject> {
            require(text(value, "mode") in setOf("keyword", "hybrid")) { "知识搜索模式无效。" }
            flag(value, "hasMore"); flag(value, "truncated")
            val coverage = value.getJSONObject("coverage")
            require(number(coverage, "indexed") <= number(coverage, "eligible")) { "知识语义覆盖状态无效。" }
            return rows(value, "results", 10) { row ->
                require(text(row, "kind") in setOf("document", "memory")) { "知识搜索来源无效。" }
                id(row, "sourceId"); number(row, "sourceVersion"); text(row, "ref", 2000); text(row, "title", 8192); text(row, "snippet", 8192)
                row
            }
        }
        fun accepted(value: JSONObject, action: String? = null, data: JSONObject? = null): Job? {
            if (value.has("job")) {
                val job = job(value.getJSONObject("job"))
                val operation = value.getJSONObject("operation")
                val operationId = text(operation, "operationId")
                uuid(operationId)
                if (data != null) require(operationId == text(data, "operationId")) { "知识操作回执不匹配。" }
                if (action in setOf("cancel", "retry")) {
                    require(data != null && job.id == text(data, "jobId")) { "知识控制任务回执不匹配。" }
                } else require(operationId == job.operationId) { "知识受理结果与任务不匹配。" }
                number(operation, "createdAt")
                return job
            }
            return null
        }
    }
}
