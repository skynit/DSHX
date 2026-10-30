package local.dshx.app

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import android.content.res.AssetManager

/** 知识库中的上传原件和用户技能；资料索引与记忆仍由各自的 Store 管理。 */
class LibraryStore(private val files: File) {
    data class Entry(val id: String, val name: String, val bytes: Long, val updated: Long,
                     val source: String, val removable: Boolean)
    data class Skill(val entry: Entry, val text: String, val revision: String)
    private val uploads = File(files, "library/files")
    private val attachments = File(files, "home/.dsh/storages/attachments/v1")
    private val roots = linkedMapOf(
        "user" to File(files, "home/.dsh/skills"),
        "shared" to File(files, "home/.agents/skills"),
        "project" to File(files, "workspace/.dsh/skills"),
        "agents" to File(files, "workspace/.agents/skills"),
    )

    /** 内置技能按内容更新；用户编辑的文件保留，不能被引擎启动覆盖。 */
    @Synchronized fun prepareSkills(assets: AssetManager) {
        val receipt = File(files, "builtin-skills.json")
        val previous = if (receipt.isFile) org.json.JSONObject(receipt.readText()) else org.json.JSONObject()
        val next = org.json.JSONObject(previous.toString())
        fun visit(path: String) {
            val children = assets.list(path).orEmpty()
            if (children.isNotEmpty()) { children.forEach { visit("$path/$it") }; return }
            val relative = path.removePrefix("skills/")
            val target = checked(roots.getValue("user"), relative)
            val text = assets.open(path).bufferedReader().use { it.readText() }
            val current = if (target.isFile) digest(target.readText()) else null
            val incoming = digest(text)
            if (current == null || current == previous.optString(relative) || current == incoming) {
                require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                val atomic = android.util.AtomicFile(target)
                val output = atomic.startWrite()
                try { output.write(text.toByteArray()); atomic.finishWrite(output) }
                catch (error: Exception) { atomic.failWrite(output); throw error }
                next.put(relative, incoming)
            }
        }
        assets.list("skills").orEmpty().forEach { visit("skills/$it") }
        val atomic = android.util.AtomicFile(receipt); val output = atomic.startWrite()
        try { output.write(next.toString().toByteArray()); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }

    private fun checked(root: File, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute && path.split('/').none { it in setOf("", ".", "..") }) { "文件标识无效。" }
        val target = File(root, path)
        require(target.canonicalFile.toPath().startsWith(root.absoluteFile.toPath()) &&
            target.absoluteFile == target.canonicalFile) { "不支持外部路径或链接文件。" }
        return target
    }

    fun file(id: String): File {
        val type = id.substringBefore(':')
        val root = when (type) { "upload" -> uploads; "attachment" -> attachments; else -> roots[type] }
            ?: error("文件来源无效。")
        return checked(root, id.substringAfter(':', "")).also { require(it.isFile) { "文件已不存在，请刷新。" } }
    }

    private fun entries(root: File, prefix: String, source: String, removable: Boolean, depth: Int): List<Entry> {
        if (!root.exists()) return emptyList()
        require(root.isDirectory && root.absoluteFile == root.canonicalFile) { "无法读取文件目录。" }
        val result = mutableListOf<Entry>()
        fun visit(directory: File, remaining: Int) {
            for (item in directory.listFiles() ?: error("无法读取文件列表。")) {
                if (Files.isSymbolicLink(item.toPath()) || item.name.startsWith('.')) continue
                if (item.isDirectory && remaining > 0) visit(item, remaining - 1)
                else if (item.isFile) {
                    require(result.size < 5000) { "文件过多，请先整理文件目录。" }
                    val path = item.relativeTo(root).invariantSeparatorsPath
                    result += Entry("$prefix:$path", item.name, item.length(), item.lastModified(), source, removable)
                }
            }
        }
        visit(root, depth)
        return result
    }

    fun files(): List<Entry> = (entries(uploads, "upload", "上传文件", true, 1) +
        entries(File(attachments, "files"), "attachment", "聊天附件", false, 2).map { it.copy(id = "attachment:files/" + it.id.substringAfter(':')) } +
        entries(File(attachments, "objects"), "attachment", "聊天图片", false, 1).map {
            it.copy(id = "attachment:objects/" + it.id.substringAfter(':'), name = "图片 · ${it.name.take(12)}")
        }).sortedWith(compareByDescending<Entry> { it.updated }.thenBy { it.id })

    fun skills(): List<Entry> = roots.flatMap { (key, root) ->
        entries(root, key, if (key in setOf("project", "agents")) "工作区技能" else "用户技能", true, 1)
            .filter { it.name == "SKILL.md" || it.id.substringAfter(':').let { path -> '/' !in path && path.endsWith(".md") } }
            .map { entry -> entry.copy(name = if (entry.name == "SKILL.md") entry.id.substringAfter(':').substringBefore('/') else entry.name.removeSuffix(".md")) }
    }.sortedWith(compareBy<Entry> { it.name }.thenBy { it.id })

    fun readSkill(entry: Entry): Skill {
        require(entry.id.substringBefore(':') in roots) { "不是技能文件。" }
        val text = file(entry.id).inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_SKILL) { "技能超过 256 KiB，请在文件工具中编辑。" }
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
        return Skill(entry, text, digest(text))
    }

    fun saveSkill(name: String, text: String, previous: Skill? = null, root: String = "user"): Entry = synchronized(skillLock) {
        require(root in roots) { "技能目录无效。" }
        require(previous != null || name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && name.length <= 100) { "技能名称请使用小写英文、数字和短横线。" }
        require(text.toByteArray().size <= MAX_SKILL && text.isNotBlank()) { "技能正文不能为空，且不能超过 256 KiB。" }
        val body = text
        require(body.startsWith("---\n") && Regex("(?m)^---\\s*$").findAll(body).count() >= 2) { "请保留技能文件顶部的元数据。" }
        val header = body.removePrefix("---\n").substringBefore("\n---")
        require(Regex("(?m)^name:[ \\t]*\\S[^\\r\\n]*$").containsMatchIn(header) &&
            Regex("(?m)^description:[ \\t]*\\S[^\\r\\n]*$").containsMatchIn(header)) { "技能顶部必须保留 name 和 description。" }
        val target = if (previous != null) {
            file(previous.entry.id).also { require(readSkill(previous.entry).revision == previous.revision) { "技能已被修改，请重新打开；当前编辑仍保留。" } }
        } else checked(roots.getValue(root), "$name/SKILL.md").also { require(!it.exists()) { "同名技能已存在，请编辑已有技能。" } }
        require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建技能目录。" }
        val temporary = File(target.parentFile, ".${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { it.write(body.toByteArray()); it.fd.sync() }
            require(temporary.renameTo(target)) { "技能保存失败，请重试。" }
        } finally { temporary.delete() }
        previous?.entry?.copy(bytes = target.length(), updated = target.lastModified())
            ?: Entry("$root:$name/SKILL.md", name, target.length(), target.lastModified(), if (root == "project") "工作区技能" else "用户技能", true)
    }

    class SkillNotWritten(message: String) : IllegalStateException(message)

    /** 正式文件与采用回执分步保存；重复请求核对内容后恢复确认，绝不覆盖用户编辑。 */
    fun adoptSkill(proposal: org.json.JSONObject): org.json.JSONObject = synchronized(skillLock) {
        val name = proposal.getString("name")
        val operation = proposal.getString("operation")
        val id = proposal.getString("id")
        val text = proposal.getString("body")
        val hash = proposal.getString("digest")
        require(name.startsWith("specimen-") && name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && name.length <= 80) { "候选技能名称无效。" }
        require(operation.matches(Regex("[A-Za-z0-9-]{1,100}")) && id.length in 1..100 && digest(text) == hash) { "候选技能内容或采用编号无效。" }
        val target = checked(roots.getValue("project"), "$name/SKILL.md")
        val path = "project:$name/SKILL.md"
        val atomic = android.util.AtomicFile(File(files, "specimen-adoptions.json"))
        val records = try { org.json.JSONObject(atomic.openRead().bufferedReader().use { it.readText() }) }
            catch (_: java.io.FileNotFoundException) { org.json.JSONObject() }
        val previous = records.optJSONObject(operation)
        val matching = target.isFile && target.length() <= MAX_SKILL && digest(target.readText()) == hash
        fun reject(message: String): Nothing {
            if (previous == null && !matching) throw SkillNotWritten(message)
            throw IllegalStateException(message)
        }
        if (proposal.getString("workspace") != File(files, "workspace").absolutePath) reject("候选属于其他工作区，不能写入当前技能目录。")
        if (previous != null) {
            require(previous.getString("id") == id && previous.getString("digest") == hash && previous.getString("path") == path) { "采用编号已用于其他内容。" }
            require(matching || previous.optString("phase") == "pending" && !target.exists()) { "技能已被修改或删除，已保留用户更改。" }
            if (previous.optString("phase") != "pending") return@synchronized org.json.JSONObject(previous.toString())
        }
        if (previous == null && records.length() >= 128) reject("采用记录已满，请先整理。")
        if (target.exists()) { if (!matching) reject("同名技能已存在，已保留原文件。") }
        val receipt = org.json.JSONObject().put("id", id).put("operation", operation).put("digest", hash).put("path", path)
        fun persist() {
            val output = atomic.startWrite()
            try { output.write(records.toString().toByteArray()); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
        }
        // 发布前留下持久意图；断电或最终回执丢失后不能误报成从未写入。
        records.put(operation, org.json.JSONObject(receipt.toString()).put("phase", "pending")); persist()
        if (!target.exists()) saveSkill(name, text, root = "project")
        records.put(operation, receipt); persist()
        receipt
    }

    @Synchronized fun upload(name: String, input: InputStream): Entry {
        val leaf = name.replace('\\', '/').substringAfterLast('/').filter { !it.isISOControl() }.take(160)
            .takeIf { it.isNotBlank() && it !in setOf(".", "..") } ?: "未命名文件"
        val id = UUID.randomUUID().toString()
        val directory = checked(uploads, id)
        require(directory.mkdirs()) { "无法创建上传目录。" }
        val target = File(directory, leaf)
        try {
            target.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var size = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= MAX_FILE) { "单个文件不能超过 32 MiB。" }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        } catch (error: Exception) { target.delete(); directory.delete(); throw error }
        return Entry("upload:$id/$leaf", leaf, target.length(), target.lastModified(), "上传文件", true)
    }

    fun remove(entry: Entry, skill: Skill? = null): Unit = synchronized(skillLock) {
        require(entry.removable && entry.id.substringBefore(':') != "attachment") { "聊天附件由历史会话引用，不能在此删除。" }
        val target = file(entry.id)
        if (skill != null) require(skill.entry.id == entry.id && readSkill(entry).revision == skill.revision) { "技能已变化，请重新打开。" }
        require(target.delete()) { "文件删除失败，请重试。" }
        if (entry.id.startsWith("upload:")) target.parentFile?.delete()
    }

    companion object {
        private val skillLock = Any()
        const val MAX_SKILL = 256 * 1024
        const val MAX_FILE = 32L * 1024 * 1024
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
