package local.dshx.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 应用进程内的恢复入口；服务始终由 Android 连接。所有写入和状态通知在主线程执行。 */
internal class AccessibilityRecovery(private val context: Context) {
    data class State(val enabled: Boolean, val connected: Boolean, val automatic: Boolean, val canRepair: Boolean, val repairing: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val settings = context.getSharedPreferences("phone", Context.MODE_PRIVATE)
    private var repairing = false
    private var focused = false
    private var leftDuringRepair = false
    private var lastAttempt = -RETRY_INTERVAL
    private val current = MutableStateFlow(readState())
    val state = current.asStateFlow()
    private val attempt = Runnable { repair() }

    init {
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(SERVICES), false, object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) { current.value = readState() }
        })
    }

    fun refresh() {
        val next = readState()
        current.value = next
        main.removeCallbacks(attempt)
        if (focused && next.automatic && next.canRepair && next.enabled && !next.connected && !next.repairing) {
            val cooldown = RETRY_INTERVAL - (SystemClock.elapsedRealtime() - lastAttempt)
            main.postDelayed(attempt, maxOf(2_000L, cooldown))
        }
    }

    fun setFocused(value: Boolean) {
        focused = value
        if (!value && repairing) leftDuringRepair = true
        refresh()
    }

    fun setAutomatic(value: Boolean) {
        if (value && !canRepair()) return
        settings.edit().putBoolean("repair_accessibility", value).apply()
        refresh()
    }

    private fun canRepair() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun automatic() = settings.getBoolean("repair_accessibility", false)

    private fun readState() = State(enabled(context), PhoneService.current != null, automatic(), canRepair(), repairing)

    private fun repair() {
        val status = readState()
        if (!focused || !status.automatic || !status.canRepair || !status.enabled || status.connected || status.repairing) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttempt < RETRY_INTERVAL) return
        val before = services(context)
        val own = ComponentName(context, PhoneService::class.java)
        val remaining = before.filterNot { ComponentName.unflattenFromString(it) == own }
        if (before == remaining) return
        lastAttempt = now
        if (!writeServices(remaining)) return
        repairing = true
        leftDuringRepair = false
        current.value = readState()
        main.postDelayed({
            // 不覆盖移除期间发生的系统设置变更，也不在权限或用户选项失效后写回。
            if (focused && !leftDuringRepair && canRepair() && automatic() && services(context) == remaining) writeServices(before)
            repairing = false
            current.value = readState()
        }, 1_000L)
    }

    private fun writeServices(names: List<String>) = try {
        Settings.Secure.putString(context.contentResolver, SERVICES, names.joinToString(":"))
    } catch (_: SecurityException) { false }

    companion object {
        private const val SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        private const val RETRY_INTERVAL = 30_000L
        private fun services(context: Context) = Settings.Secure.getString(context.contentResolver, SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() }

        /** 全局无障碍状态可能随服务是否在运行而变化；授权以本服务是否仍在列表中为准。 */
        fun enabled(context: Context): Boolean {
            val own = ComponentName(context, PhoneService::class.java)
            return services(context).any { ComponentName.unflattenFromString(it) == own }
        }
    }
}
