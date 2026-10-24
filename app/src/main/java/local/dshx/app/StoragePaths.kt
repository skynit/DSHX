package local.dshx.app

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** 应用停机时调整物理目录；不改写 DSH 的会话日志和业务快照内容。 */
class StoragePaths(private val files: File) {
    private val home = File(files, "home/.dsh")
    val root = File(home, "storages")
    val memory = File(root, "memory/state.json")
    val knowledge = File(root, "knowledge")
    val legacyMemory = File(root, "memory.json")

    fun prepare() = synchronized(lock) {
        val moves = listOf(
            File(home, "sessions") to File(root, "sessions"),
            File(home, "attachments") to File(root, "attachments"),
            File(home, "knowledge") to knowledge,
            legacyMemory to memory,
            File(root, "knowledge.json") to File(knowledge, "state.json"),
        ).filter { (source, _) -> Files.exists(source.toPath(), NOFOLLOW_LINKS) }

        // 全部检查后再移动；每项是同一文件系统内的原子重命名，中断后可按剩余旧路径继续。
        for ((source, destination) in moves) {
            checkPath(source)
            checkPath(destination)
            val oldDirectory = moves.firstOrNull { (old, target) ->
                Files.isDirectory(old.toPath(), NOFOLLOW_LINKS) && destination.toPath() != target.toPath() &&
                    destination.toPath().startsWith(target.toPath())
            }
            val existing = oldDirectory?.let { (old, target) ->
                File(old, target.toPath().relativize(destination.toPath()).toString())
            } ?: destination
            require(!Files.exists(existing.toPath(), NOFOLLOW_LINKS)) {
                "新旧存储位置同时存在数据，已停止迁移并保留两份：${destination.relativeTo(home)}"
            }
            if (source.extension == "json") {
                require(Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS)) { "旧存储快照不是普通文件。" }
            } else {
                require(Files.isDirectory(source.toPath(), NOFOLLOW_LINKS)) { "旧存储目录无效。" }
                Files.walk(source.toPath()).use { paths ->
                    paths.forEach { path ->
                        require(Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                            "旧存储目录包含链接或不支持的文件，已停止迁移。"
                        }
                    }
                }
            }
        }
        for ((source, destination) in moves) {
            check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory) { "无法创建存储目录。" }
            Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE)
        }
    }

    private fun checkPath(file: File) {
        var current: File? = file
        while (current != null && current != files) {
            require(!Files.isSymbolicLink(current.toPath())) { "存储路径不能包含符号链接。" }
            if (current != file && Files.exists(current.toPath(), NOFOLLOW_LINKS)) {
                require(Files.isDirectory(current.toPath(), NOFOLLOW_LINKS)) { "存储父路径不是目录。" }
            }
            current = current.parentFile
        }
    }

    companion object {
        private val lock = Any()
    }
}
