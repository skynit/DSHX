package local.dshx.prototype

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class WorkFile(val id: String, val name: String)

/** 原型的真实本地副本；只接受有限文本，不暴露目录或外部文件写权限。 */
class Workspace(private val root: File) {
    companion object {
        const val MAX_BYTES = 256 * 1024
        const val MAX_NAME_LENGTH = 120
        private val TYPES = setOf("md", "txt", "json")
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        fun extractTasks(name: String, text: String): String {
            require(name.substringAfterLast('.', "").lowercase() == "md") { "演示整理仅支持 Markdown；此文件可预览和导出。" }
            val tasks = text.lineSequence().mapIndexedNotNull { index, line ->
                Regex("^\\s*- \\[ \\] (.+)$").matchEntire(line)?.let {
                    "- ${it.groupValues[1]} （原文第 ${index + 1} 行）"
                }
            }.toList()
            require(tasks.isNotEmpty()) { "未找到未完成事项。原文件副本已保留，可导出。" }
            return "# 待办清单\n\n${tasks.joinToString("\n")}\n\n来源：$name；仅提取未完成事项，未推测负责人或日期。\n"
        }
    }

    init { check(root.isDirectory || root.mkdirs()) { "无法创建工作目录" } }

    fun import(name: String, input: InputStream): WorkFile {
        val safeName = name.substringAfterLast('/').substringAfterLast('\\').trim()
        require(safeName.length in 1..MAX_NAME_LENGTH && safeName.none { it.isISOControl() }) { "文件名无效或超过 120 个字符" }
        require(safeName.substringAfterLast('.', "").lowercase() in TYPES) { "仅支持 .txt、.md、.json 文本副本" }
        val buffer = java.io.ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (buffer.size() <= MAX_BYTES) {
            val count = input.read(block, 0, minOf(block.size, MAX_BYTES + 1 - buffer.size()))
            if (count < 0) break
            if (count == 0) throw IllegalArgumentException("文件读取未取得进展，未导入")
            buffer.write(block, 0, count)
        }
        val bytes = buffer.toByteArray()
        require(bytes.size <= MAX_BYTES) { "原型只接收不超过 256 KiB 的文本，原文件未改动" }
        val text = decode(bytes)
        require('\u0000' !in text) { "文件包含二进制内容，未导入" }
        require(root.usableSpace > bytes.size * 4L + 1024 * 1024) { "可用空间不足，未导入" }
        val file = WorkFile(UUID.randomUUID().toString(), safeName)
        val folder = File(root, file.id)
        check(folder.mkdir()) { "无法创建副本目录" }
        try {
            write(File(folder, "name"), safeName)
            write(File(folder, "original"), text)
            write(File(folder, "current"), text)
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
        return file
    }

    fun list(): List<WorkFile> = root.listFiles().orEmpty().filter { it.isDirectory && File(it, "name").isFile && File(it, "current").isFile }
        .map { WorkFile(it.name, File(it, "name").readText()) }.sortedBy { it.name }

    fun read(file: WorkFile): String = File(folder(file), "current").readText()
    fun original(file: WorkFile): String = File(folder(file), "original").readText()
    fun hasChange(file: WorkFile): Boolean = File(folder(file), "before").exists()

    fun extract(file: WorkFile): String {
        val current = read(file)
        val result = extractTasks(file.name, current)
        val directory = folder(file)
        // 结果预期哈希先落盘。中断后按冲突处理，不能覆盖后来的编辑。
        write(File(directory, "before"), current)
        write(File(directory, "result-hash"), digest(result.toByteArray(StandardCharsets.UTF_8)))
        write(File(directory, "current"), result)
        return result
    }

    fun undo(file: WorkFile): WorkFile {
        val directory = folder(file)
        val before = File(directory, "before")
        check(before.isFile) { "没有可撤销的整理" }
        val matches = digest(File(directory, "current").readBytes()) == File(directory, "result-hash").takeIf { it.isFile }?.readText()
        val restored = if (matches) {
            write(File(directory, "current"), before.readText())
            file
        } else {
            val prefix = "恢复-"
            val suffix = ".${file.name.substringAfterLast('.')}"
            val base = file.name.substringBeforeLast('.').take(MAX_NAME_LENGTH - prefix.length - suffix.length)
                .let { if (it.isNotEmpty() && Character.isHighSurrogate(it.last())) it.dropLast(1) else it }
            before.inputStream().use { import("$prefix$base$suffix", it) }
        }
        check(before.delete()) { "结果已恢复，但撤销标记清理失败，请重新打开文件" }
        File(directory, "result-hash").delete()
        return restored
    }

    private fun folder(file: WorkFile): File {
        require(runCatching { UUID.fromString(file.id).toString() == file.id }.getOrDefault(false)) { "无效副本编号" }
        val directory = File(root, file.id)
        check(directory.isDirectory && directory.canonicalFile.parentFile == root.canonicalFile) { "副本不存在" }
        return directory
    }

    private fun decode(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")

    private fun write(file: File, text: String) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.outputStream().use { stream -> stream.write(text.toByteArray(StandardCharsets.UTF_8)); stream.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }
}
