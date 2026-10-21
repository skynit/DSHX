package local.dshx.prototype

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/** 走查期间跨页面保存同一份状态；进程退出后只保留已落盘的文件副本。 */
class PrototypeApp : Application() {
    val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = java.util.ArrayDeque<() -> Unit>()
    lateinit var workspace: Workspace
    var listener: (() -> Unit)? = null
    var page = "home"
    var busy = false
    var message: String? = null
    var files = emptyList<WorkFile>()
    var selected: WorkFile? = null
    var text = ""
    var before = ""
    var changed = false
    var receiver = "https://model.example.invalid"
    var allowed = false
    var flow = TaskFlow()
    var approvalDeadline = 0L
    var recovery = "default"
    var exportText: String? = null

    override fun onCreate() {
        super.onCreate()
        runWork({
            workspace = Workspace(File(filesDir, "walkthrough-files"))
            workspace.list()
        }) { files = it }
    }

    fun <T> runWork(action: () -> T, success: (T) -> Unit) {
        if (busy) {
            // 系统选择器可能在进程重建后的初始化期间返回，保留请求而非静默丢弃。
            pending.add { runWork(action, success) }
            return
        }
        busy = true
        message = null
        listener?.invoke()
        worker.execute {
            val result = runCatching(action)
            main.post {
                busy = false
                result.fold(success) { message = it.message ?: "操作未完成，本地文件保留" }
                if (!busy && pending.isNotEmpty()) pending.removeFirst().invoke()
                listener?.invoke()
            }
        }
    }

    fun select(file: WorkFile) {
        runWork({ Triple(workspace.read(file), workspace.original(file), workspace.hasChange(file)) }) {
            selected = file
            text = it.first
            before = it.second
            changed = it.third
            allowed = false
            page = "file"
        }
    }
}
