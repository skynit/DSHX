package local.dshx.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.util.Locale
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 原生入口。开关独立于引擎；无障碍对象仅由主线程访问。 */
class PhoneControl(context: Context) {
    private val context = context.applicationContext
    private val settings = this.context.getSharedPreferences("phone", Context.MODE_PRIVATE)
    internal val device = PhoneDevice.get(this.context)
    internal val window = PhoneWindow(this.context) { setEnabled(false) }

    fun finish(session: String) { main.post { window.finish(session) } }

    fun enabled(): Boolean = settings.getBoolean("enabled", false)

    /** 由设置页在主线程调用；关闭同时撤销所有实例的排队请求和观测。 */
    fun setEnabled(value: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "手机开关必须在主线程修改" }
        settings.edit().putBoolean("enabled", value).apply()
        if (!value) {
            PhoneWindow.current?.close()
            pending.get()?.let { call ->
                call.finish(failure("disabled", "手机操作开关已关闭"))
                main.removeCallbacksAndMessages(call)
            }
            PhoneService.current?.clear()
            device.reset()
        }
    }

    fun status(): JSONObject {
        val enabled = enabled()
        val online = PhoneService.current != null
        val locked = locked()
        val interactive = interactive()
        return success().put("enabled", enabled).put("accessibility", online)
            .put("deviceReady", enabled && !locked && interactive)
            .put("ready", enabled && online && !locked && interactive).put("locked", locked).put("interactive", interactive)
            .put("screenshotSupported", Build.VERSION.SDK_INT >= 30)
            .put("screenshotReady", enabled && online && !locked && interactive && Build.VERSION.SDK_INT >= 30)
            .put("coordinateSupported", Build.VERSION.SDK_INT >= 30).put("snapshotLifetimeMs", SNAPSHOT_LIFETIME)
            .put("timeoutMs", TIMEOUT).put("askTimeoutMs", ASK_TIMEOUT).put("capabilities", device.status())
    }

    fun allowed(name: String): Boolean = device.allowed(name)
    fun setAllowed(name: String, value: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        device.setAllowed(name, value)
        if (!value) pending.get()?.takeIf { it.capability == name }?.let { it.finish(failure("capability_disabled", "设备能力已关闭")) }
    }

    fun revokeDirectory() {
        check(Looper.myLooper() == Looper.getMainLooper())
        pending.get()?.takeIf { it.capability == "files" }?.finish(failure("cancelled", "手机目录授权已撤销"))
        device.files.revoke()
    }

    /** 后台 socket 线程同步等待；超时、撤销和关闭均使尚未执行的主线程工作失效。 */
    fun execute(method: String, args: JSONObject, session: String, valid: () -> Boolean): JSONObject {
        if (method !in methods) return failure("unknown_method", "不支持的手机操作")
        if (session.isBlank() || !runCatching(valid).getOrDefault(false)) return failure("cancelled", "引擎或会话已失效")
        val fields = when (method) {
            "open" -> setOf("package")
            "click" -> setOf("snapshot", "node")
            "input" -> setOf("snapshot", "node", "text")
            "scroll" -> setOf("snapshot", "node", "direction")
            "key" -> setOf("name")
            "tap" -> setOf("screenshot", "x", "y")
            "swipe" -> setOf("screenshot", "x", "y", "endX", "endY", "ms")
            else -> emptySet()
        }
        if (method == "apps") {
            if (args.keys().asSequence().any { it !in setOf("query", "userOnly") } ||
                args.has("query") && (args.opt("query") !is String || args.getString("query").length > 200) ||
                args.has("userOnly") && args.opt("userOnly") !is Boolean) return failure("invalid_args", "应用筛选参数无效")
        } else if (method in PhoneDevice.methods) {
            try { device.validate(method, args) } catch (error: Exception) { return errorResult(error) }
        } else if (args.keys().asSequence().any { it !in fields } || fields.any {
            if (it in setOf("x", "y", "endX", "endY", "ms")) args.opt(it) !is Number ||
                runCatching { args.getDouble(it) != args.getInt(it).toDouble() }.getOrDefault(true)
            else args.opt(it) !is String
        }) {
            return failure("invalid_args", "操作参数缺失、类型错误或包含多余字段")
        }
        if (method == "status") return status()
        if (Looper.myLooper() == Looper.getMainLooper()) return failure("wrong_thread", "手机操作必须从后台线程调用")
        // 复制参数，避免调用方在排队期间更改待执行内容。
        val input = JSONObject(args.toString())
        val call = Call(valid, method in screenMethods, if (method == "ask") ASK_TIMEOUT else TIMEOUT, method == "key" && input.optString("name") == "lock", PhoneDevice.capability(method, input), method == "export")
        if (!pending.compareAndSet(null, call)) return failure("busy", "已有手机请求正在处理")
        val work = Runnable {
            try {
                checkCall(call)
                val service = PhoneService.current
                call.service = service
                val check = { checkCall(call) }
                // 与取消/超时完成回执使用同一把锁，避免回执宣称未派发后才开始动作。
                val dispatch = { synchronized(call) { check(); call.dispatched = true } }
                when (method) {
                    "apps" -> call.finish(apps(input, check))
                    "open" -> call.finish(open(input.getString("package"), service, dispatch))
                    "observe" -> call.finish(service!!.observe(session, valid, check))
                    "click", "input", "scroll" -> call.finish(service!!.act(method, input, session, check, dispatch))
                    "back", "home", "key" -> {
                        val key = if (method == "key") input.getString("name") else method
                        val action = when (key) {
                            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                            "quicksettings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                            "lock" -> if (Build.VERSION.SDK_INT >= 28) android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                                else throw PhoneFailure("unsupported", "系统锁屏动作需要 Android 9 或更新版本")
                            else -> throw PhoneFailure("invalid_args", "不支持的系统按键")
                        }
                        service!!.clear()
                        dispatch()
                        val accepted = service.performGlobalAction(action)
                        call.finish(actionResult(accepted))
                    }
                    "screenshot" -> service!!.screenshot(session, valid, check, dispatch) { call.finish(it) }
                    "tap", "swipe" -> service!!.gesture(method, input, session, check, dispatch) { call.finish(it) }
                    else -> call.cleanup = device.execute(method, input, check, dispatch, { call.finish(it) }, { enabled() && runCatching(valid).getOrDefault(false) })
                }
            } catch (error: Exception) {
                PhoneService.current?.clear()
                call.finish(errorResult(error))
            }
        }
        val job = Runnable {
            try {
                checkCall(call)
                window.show(session, method, valid) { work.run() }
            } catch (error: Exception) { call.finish(errorResult(error)) }
        }
        if (!main.postAtTime(job, call, SystemClock.uptimeMillis())) {
            call.finish(failure("unavailable", "主线程无法接收手机请求"))
        }
        var delivered = false
        var reply: JSONObject? = null
        try {
            while (call.result.get() == null) {
                try { checkCall(call) } catch (error: Exception) { call.finish(errorResult(error)); break }
                call.done.await(25, TimeUnit.MILLISECONDS)
            }
            // 系统动作可能已经接受；失效或超时不能伪装成成功，也无法撤回已派发的动作。
            val value = try { checkCall(call, completed = true); call.result.get()!! }
                catch (error: Exception) { errorResult(error) }
            delivered = value.optBoolean("ok")
            return call.reply(value).also { reply = it }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            val value = failure("cancelled", "手机请求等待已中断；实际界面结果需重新观察")
            call.finish(value)
            return call.reply(value).also { reply = it }
        } finally {
            main.removeCallbacksAndMessages(call)
            main.post {
                call.cleanup?.invoke()
                reply?.let(window::result)
                if (!delivered) PhoneService.current?.clear()
            }
            pending.compareAndSet(call, null)
        }
    }

    private fun checkCall(call: Call, completed: Boolean = false) {
        if (SystemClock.elapsedRealtime() >= call.deadline) throw PhoneFailure("timeout", "手机请求超时；实际界面结果需重新观察")
        if (!completed && call.result.get() != null || !runCatching(call.valid).getOrDefault(false)) {
            throw PhoneFailure("cancelled", "手机请求已取消；实际界面结果需重新观察")
        }
        if (!enabled()) throw PhoneFailure("disabled", "手机操作开关未开启")
        val service = PhoneService.current
        if (call.needsService && service == null) throw PhoneFailure("accessibility_offline", "无障碍服务未连接")
        if (call.needsService && call.service != null && call.service !== service) throw PhoneFailure("accessibility_offline", "无障碍服务连接已更换")
        if (!completed && !(call.locking && call.dispatched) && locked()) throw PhoneFailure("locked", "设备已锁定，拒绝手机操作")
        if (!completed && !(call.locking && call.dispatched) && !interactive()) throw PhoneFailure("screen_off", "屏幕已关闭，拒绝手机操作")
    }

    private fun locked(): Boolean = runCatching {
        val lock = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        lock.isKeyguardLocked || lock.isDeviceLocked
    }.getOrDefault(true)

    private fun interactive(): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }.getOrDefault(false)

    private fun apps(args: JSONObject, check: () -> Unit): JSONObject {
        val manager = context.packageManager
        val launchable = manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo?.enabled == true && it.activityInfo.applicationInfo.enabled }.map { it.activityInfo.packageName }.toSet()
        val query = args.optString("query").lowercase(Locale.ROOT)
        val rows = manager.getInstalledApplications(0).distinctBy { it.packageName }.sortedBy { it.packageName }
        val apps = JSONArray(); var bytes = 1024; var truncated = false
        for (row in rows) {
            check()
            val system = row.flags and ApplicationInfo.FLAG_SYSTEM != 0
            if (args.optBoolean("userOnly") && system) continue
            val label = row.loadLabel(manager).toString().take(200)
            if (query.isNotEmpty() && !row.packageName.lowercase(Locale.ROOT).contains(query) && !label.lowercase(Locale.ROOT).contains(query)) continue
            val entry = JSONObject().put("package", row.packageName).put("name", label).put("system", system)
                .put("launchable", row.packageName in launchable).put("enabled", row.enabled)
            val size = entry.toString().toByteArray(Charsets.UTF_8).size + 1
            if (apps.length() >= 500 || bytes + size > 256 * 1024) { truncated = true; break }
            bytes += size; apps.put(entry)
        }
        check()
        return success().put("apps", apps).put("truncated", truncated).put("scope", "android-visible")
    }

    private fun open(name: String, service: PhoneService?, dispatch: () -> Unit): JSONObject {
        if (!name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) || name.length > 255) {
            throw PhoneFailure("invalid_args", "package 必须是有效应用包名")
        }
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(name)
        if (manager.queryIntentActivities(launcher, 0).none { it.activityInfo?.enabled == true && it.activityInfo.applicationInfo.enabled }) {
            throw PhoneFailure("app_unavailable", "应用没有可用的桌面启动入口")
        }
        val intent = manager.getLaunchIntentForPackage(name) ?: throw PhoneFailure("app_unavailable", "无法取得应用启动入口")
        service?.clear()
        device.launch(intent, {}, dispatch)
        return success().put("accepted", true).put("package", name)
    }

    private class Call(val valid: () -> Boolean, val needsService: Boolean, timeout: Long, val locking: Boolean, val capability: String?, val exporting: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        val done = CountDownLatch(1)
        val result = AtomicReference<JSONObject?>()
        @Volatile var service: PhoneService? = null
        @Volatile var dispatched = false
        @Volatile var cleanup: (() -> Unit)? = null
        @Synchronized fun finish(value: JSONObject) { if (result.compareAndSet(null, value)) done.countDown() }
        @Synchronized fun reply(value: JSONObject): JSONObject {
            if (exporting && dispatched && !value.optBoolean("ok")) value.put("cleanupWarning", "导出已开始；若文件提供程序未能完成清理，授权目录可能留下不完整文件，请检查。")
            return value.put("execution_state", when {
            value.optBoolean("ok") || value.optString("execution_state") == "completed" -> "completed"
            dispatched -> "unknown"
            else -> "not_started"
        })
        }
    }

    internal companion object {
        const val SNAPSHOT_LIFETIME = 10_000L
        private const val TIMEOUT = 8_000L
        private const val ASK_TIMEOUT = 60_000L
        private val main = Handler(Looper.getMainLooper())
        private val pending = AtomicReference<Call?>()
        private val screenMethods = setOf("observe", "click", "input", "scroll", "back", "home", "key", "screenshot", "tap", "swipe")
        private val methods = setOf("status", "apps", "open") + screenMethods + PhoneDevice.methods
        fun success(): JSONObject = JSONObject().put("ok", true).put("execution_state", "completed")
        fun failure(code: String, message: String): JSONObject = JSONObject().put("ok", false).put("code", code)
            .put("error", message).put("execution_state", "not_started")
        fun actionResult(accepted: Boolean): JSONObject = if (accepted) success().put("accepted", true)
            else failure("action_failed", "Android 未接受该动作，请重新观察界面").put("accepted", false).put("execution_state", "completed")
        fun errorResult(error: Exception): JSONObject = if (error is PhoneFailure) failure(error.code, error.message ?: "手机操作失败")
            else if (error is SecurityException) failure("permission_required", "系统权限不可用，请在设置中检查对应授权")
            else failure("operation_failed", "手机操作失败，请重新观察界面")
    }
}

/** 只携带固定错误说明，避免将系统异常中的界面文字带入日志或响应。 */
internal class PhoneFailure(val code: String, message: String) : RuntimeException(message)
