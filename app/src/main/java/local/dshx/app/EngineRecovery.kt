package local.dshx.app

import android.os.Process
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Records an actual engine failure that requires repair on the next start. */
class EngineRecovery(private val files: File) {
    private val marker = File(files, ".trust-pending")
    @Volatile private var required = false
    val needed: Boolean get() = required || Files.exists(marker.toPath(), NOFOLLOW_LINKS)

    @Synchronized fun mark() {
        required = true
        check(!Files.isSymbolicLink(marker.toPath())) { "恢复标记异常，请查看诊断" }
        marker.outputStream().use { output -> output.write(byteArrayOf(1)); output.fd.sync() }
    }

    @Synchronized fun complete() {
        Files.deleteIfExists(marker.toPath())
        required = false
    }

    /** This app has one host process; every other process with its UID belongs to the engine. */
    fun stopProcesses() {
        repeat(60) {
            val remaining = processes()
            if (remaining.isEmpty()) return
            remaining.forEach { pid ->
                // Recheck ownership immediately before signalling a PID that may have exited.
                if (pid in processes()) Process.killProcess(pid)
            }
            Thread.sleep(50)
        }
        check(processes().isEmpty()) { "仍有命令进程未结束，工作区保持停止；请重试恢复" }
    }

    private fun processes(): List<Int> {
        val entries = checkNotNull(File("/proc").listFiles()) { "无法核实命令进程，工作区保持停止" }
        return entries.mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == Process.myPid()) return@mapNotNull null
            val lines = runCatching { File(entry, "status").readLines() }.getOrNull() ?: return@mapNotNull null
            val uid = lines.firstOrNull { it.startsWith("Uid:") }?.substringAfter(':')?.trim()?.substringBefore('\t')?.substringBefore(' ')?.toIntOrNull()
            val zombie = lines.any { it.startsWith("State:") && it.substringAfter(':').trim().startsWith("Z") }
            pid.takeIf { uid == Process.myUid() && !zombie }
        }
    }
}
