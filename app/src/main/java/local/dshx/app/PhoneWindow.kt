package local.dshx.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.util.UUID

/** PhoneControl 创建的操作小窗状态；只接受固定操作名称，不接收工具参数和界面正文。 */
internal class PhoneWindow(context: Context, private val stopPhone: () -> Unit) {
    private val context = context.applicationContext
    var id: String = UUID.randomUUID().toString()
        private set
    var title = "准备操作手机"
        private set
    var detail = "正在打开操作小窗"
        private set
    var count = 0
        private set
    enum class Status(val label: String, val detail: String) {
        Running("执行中", "执行中"),
        Returned("已返回", "已返回，待下一步"),
        Failed("未成功", "未成功，等待处理"),
        Unknown("待确认", "结果未知，需重查"),
    }
    data class Step(val number: Int, val action: String, val startedAt: Long,
                    val status: Status = Status.Running, val duration: Long = 0) {
        fun elapsed(): Long = if (status == Status.Running) (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0) else duration
    }
    var history: List<Step> = emptyList()
        private set
    private val main = Handler(Looper.getMainLooper())
    private val owners = linkedMapOf<String, () -> Boolean>()
    private var page = WeakReference<PhoneWindowActivity>(null)
    private var ready = false
    private var pending: (() -> Unit)? = null
    private val watch = object : Runnable {
        override fun run() {
            if (owners.values.any { !runCatching(it).getOrDefault(false) } || PhoneService.current == null) {
                close()
                return
            }
            main.postDelayed(this, 250)
        }
    }

    fun show(session: String, method: String, valid: () -> Boolean, run: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (current !== this) {
            current?.close()
            current = this
            id = UUID.randomUUID().toString()
            ready = false
            count = 0
            history = emptyList()
        }
        owners[session] = valid
        // 无法显示小窗时仍登记任务，结束后同样返回 DSH。
        if (PhoneService.current == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            run()
            return
        }
        count++
        title = label(method)
        history = (history + Step(count, title.removePrefix("正在"), SystemClock.elapsedRealtime())).takeLast(3)
        detail = "第 $count 次手机调用 · 执行中"
        page.get()?.render()
        main.removeCallbacks(watch)
        main.postDelayed(watch, 250)
        if (ready) { run(); return }
        pending = run
        try {
            context.startActivity(Intent(context, PhoneWindowActivity::class.java)
                .putExtra("window", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (_: RuntimeException) {
            close()
            throw PhoneFailure("window_unavailable", "无法打开手机操作小窗，请检查系统画中画权限")
        }
    }

    fun attach(activity: PhoneWindowActivity) { page = WeakReference(activity) }
    fun detach(activity: PhoneWindowActivity) { if (page.get() === activity) page.clear() }

    fun entered() {
        if (ready || current !== this) return
        ready = true
        // 让系统完成小窗和前台窗口切换，再创建观察凭证，避免凭证一返回就失效。
        main.postDelayed({
            val run = pending
            pending = null
            if (current === this) run?.invoke()
        }, 300)
    }

    fun result(value: org.json.JSONObject) {
        if (current !== this) return
        val last = history.lastOrNull()?.takeIf { it.status == Status.Running } ?: return
        val status = when {
            value.optBoolean("ok") -> Status.Returned
            value.optString("execution_state") == "unknown" -> Status.Unknown
            else -> Status.Failed
        }
        history = history.dropLast(1) + last.copy(status = status, duration = last.elapsed())
        detail = status.detail
        page.get()?.render()
    }

    fun finish(session: String) {
        if (owners.remove(session) == null || owners.isNotEmpty()) return
        close()
        openApp()
    }

    fun openApp() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    fun stop() { stopPhone(); close() }

    fun close() {
        main.removeCallbacksAndMessages(null)
        pending = null
        owners.clear()
        ready = false
        if (current === this) current = null
        val old = page.get()
        page.clear()
        old?.closeWindow()
    }

    fun owns(window: Int): Boolean = page.get()?.owns(window) == true

    companion object {
        private var active = WeakReference<PhoneWindow>(null)
        var current: PhoneWindow?
            get() = active.get()
            private set(value) { active = WeakReference(value) }

        private fun label(method: String): String = when (method) {
            "apps" -> "正在查看手机应用"
            "open" -> "正在打开应用"
            "observe" -> "正在读取当前界面"
            "click", "tap" -> "正在点击控件"
            "input" -> "正在输入文字"
            "scroll", "swipe" -> "正在滚动界面"
            "back" -> "正在返回上一页"
            "home" -> "正在返回桌面"
            "key" -> "正在执行系统按键"
            "screenshot" -> "正在查看屏幕"
            "device" -> "正在读取设备信息"
            "clipboard" -> "正在操作剪贴板"
            "read_file" -> "正在读取手机文件"
            "export" -> "正在导出文件"
            "notify" -> "正在发送设备通知"
            "toast", "overlay" -> "正在显示提示"
            "ask" -> "正在等待你的选择"
            "vibrate" -> "正在振动提示"
            "share" -> "正在打开分享面板"
            "open_url" -> "正在打开网页"
            "location" -> "正在获取位置"
            "sensors", "sensor" -> "正在读取传感器"
            "torch" -> "正在操作手电筒"
            else -> "正在操作手机"
        }
    }
}
