package local.dshx.app

import java.io.File

/** 复用引擎输出收集：先脱敏，再进入内存和私有滚动文件；磁盘失败不打断引擎。 */
class EngineLog(val directory: File, val maxBytes: Int = 256 * 1024, val fileCount: Int = 3) {
    private val recent = ArrayDeque<String>()
    private val secrets = linkedSetOf<String>()
    var error: String? = null
        private set

    init { require(maxBytes >= 128 && fileCount in 1..8) }

    @Synchronized fun remember(secret: String) {
        if (secret.isNotEmpty()) secrets.add(secret)
        while (secrets.size > 32) secrets.remove(secrets.first())
    }

    @Synchronized fun clean(text: String): String {
        var result = text
        secrets.sortedByDescending { it.length }.forEach { result = result.replace(it, "[已隐藏]") }
        return Engine.redact(result)
    }

    @Synchronized fun append(text: String) {
        var line = clean(text).replace(Regex("[\\r\\n]+"), " ").take(4_000)
        while ((line + "\n").toByteArray(Charsets.UTF_8).size > maxBytes) line = line.dropLast(1)
        recent.addLast(line)
        while (recent.size > 120) recent.removeFirst()
        try {
            check(directory.isDirectory || directory.mkdirs())
            val current = file(0)
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            if (current.length() + bytes.size > maxBytes) {
                check(!file(fileCount - 1).exists() || file(fileCount - 1).delete())
                for (index in fileCount - 2 downTo 0) {
                    if (file(index).exists()) check(file(index).renameTo(file(index + 1)))
                }
            }
            current.appendBytes(bytes)
            error = null
        } catch (_: Exception) { error = "日志文件暂时无法写入，当前输出仍保留在内存。" }
    }

    /** 返回最新若干行，整个响应最多 64 KiB；从旧文件到新文件保持输出顺序。 */
    @Synchronized fun tail(lines: Int = 120): String {
        require(lines in 1..200) { "日志行数须为 1 至 200。" }
        val rows = ArrayDeque<String>()
        var bytes = 0
        fun add(text: String) {
            val row = clean(text).take(4_000)
            rows.addLast(row); bytes += row.toByteArray(Charsets.UTF_8).size + 1
            while (rows.size > lines || bytes > 64 * 1024) {
                bytes -= rows.removeFirst().toByteArray(Charsets.UTF_8).size + 1
            }
        }
        try {
            for (index in fileCount - 1 downTo 0) if (file(index).isFile) {
                file(index).bufferedReader().useLines { it.forEach(::add) }
            }
        } catch (_: Exception) { rows.clear(); bytes = 0 }
        if (rows.isEmpty() || error != null) { rows.clear(); bytes = 0; recent.forEach(::add) }
        return rows.joinToString("\n")
    }

    private fun file(index: Int) = File(directory, if (index == 0) "engine.log" else "engine.$index.log")
}
