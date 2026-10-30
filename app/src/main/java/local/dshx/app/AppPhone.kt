package local.dshx.app

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import org.json.JSONObject

/** 网页运行环境所需的手机接口；DSHX 额外保留聊天任务优先的检查。 */
internal open class AppPhone(private val context: Context, private val enabled: () -> Boolean = { false }) {
    fun foreground(): String? = PhoneService.current?.foreground()
    fun clear(owner: String) { PhoneService.current?.clear(owner) }
    open fun call(method: String, args: JSONObject, owner: String, valid: () -> Boolean): JSONObject {
        val service = PhoneService.current ?: error("无障碍服务未连接。")
        val guard = {
            require(valid() && enabled()) { "手机操作已停止。" }
            require(service === PhoneService.current && service.available(owner)) { "手机服务不可用，请重新观察。" }
            val lock = context.getSystemService(KeyguardManager::class.java)
            require(!lock.isKeyguardLocked && !lock.isDeviceLocked && context.getSystemService(PowerManager::class.java).isInteractive) { "请解锁并点亮屏幕。" }
        }
        guard()
        return if (method == "observe") service.observe(owner, valid, guard, verifyTree = true)
        else { require(method == "click"); service.act(method, args, owner, guard, guard) }
    }
}

/** 由实际 Android Application 接收无障碍生命周期；独立应用没有聊天操作小窗。 */
interface PhoneEvents {
    fun phoneConnected()
    fun phoneChanged()
    fun phoneStopped()
    fun ownsPhoneWindow(id: Int): Boolean = false
    fun closePhoneWindow() {}
}

internal object PhoneReply {
    const val SNAPSHOT_LIFETIME = 10_000L
    fun success(): JSONObject = JSONObject().put("ok", true).put("execution_state", "completed")
    fun failure(code: String, message: String): JSONObject = JSONObject().put("ok", false).put("code", code)
        .put("error", message).put("execution_state", "not_started")
    fun actionResult(accepted: Boolean): JSONObject = if (accepted) success().put("accepted", true)
        else failure("action_failed", "Android 未接受该动作，请重新观察界面").put("accepted", false).put("execution_state", "completed")
    fun errorResult(error: Exception): JSONObject = if (error is PhoneFailure) failure(error.code, error.message ?: "手机操作失败")
        else if (error is SecurityException) failure("permission_required", "系统权限不可用，请在设置中检查对应授权")
        else failure("operation_failed", "手机操作失败，请重新观察界面")
}

/** 固定错误说明，不携带系统异常中的界面文字。 */
internal class PhoneFailure(val code: String, message: String) : RuntimeException(message)
