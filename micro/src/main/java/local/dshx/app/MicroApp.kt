package local.dshx.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread

/** 独立 APK 的应用对象，只初始化包内微应用，不包含聊天、模型或 Node 环境。 */
class MicroApp : Application(), PhoneEvents {
    internal val store by lazy { AppStore(filesDir) }
    internal val host by lazy { AppHost(this, store, AppPhone(this) { store.active().any { it.app.usesScreen } }) }
    internal lateinit var definition: AppPackage.Package
    internal var failure: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val waiting = mutableListOf<() -> Unit>()

    override fun onCreate() {
        super.onCreate()
        thread(name = "micro-prepare", isDaemon = true) {
            try {
                val value = assets.open("micro-app/app.json").bufferedReader().use { JSONObject(it.readText()) }
                definition = AppPackage.parse(value)
                val status = store.status(definition.id)
                val installed = status.getJSONArray("apps").optJSONObject(0)
                require(installed?.has("error") != true) { "应用数据需要恢复，原文件已保留。" }
                if (installed?.optString("version") != definition.version) {
                    val check = store.call("check", JSONObject().put("package", value))
                    change("install", JSONObject().put("checkId", check.getString("checkId")).put("hash", check.getString("hash")))
                    if (!definition.usesScreen) change("enable", JSONObject().put("id", definition.id)
                        .put("settings", store.status(definition.id).getJSONArray("apps").getJSONObject(0).getJSONObject("settings")))
                }
            } catch (error: Exception) { failure = error.message ?: "应用无法初始化。" }
            main.post {
                ready = true
                if (failure == null) host.start()
                waiting.toList().forEach { it() }; waiting.clear()
            }
        }
    }

    internal fun prepare(done: () -> Unit) { if (ready) done() else waiting.add(done) }
    internal fun change(action: String, data: JSONObject) = store.call(action, data
        .put("operationId", UUID.randomUUID().toString()).put("expectedRevision", store.status().getLong("revision")))
    override fun phoneConnected() { prepare { if (failure == null) host.start() } }
    override fun phoneChanged() { if (ready && failure == null) host.screenEvent() }
    override fun phoneStopped() { if (ready && failure == null) host.cancelPhone() }
}

/** 只为声明了后台脚本的应用保持运行，用户可以从通知或设置中停止。 */
class MicroService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("micro", "应用后台运行", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MicroActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MicroService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "micro").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(applicationInfo.loadLabel(packageManager)).setContentText("后台运行中，可随时停止")
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "停止", stop).build()).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MicroApp
        app.prepare {
            if (app.failure != null) stopSelf()
            else if (intent?.action == "stop") {
                runCatching { app.change("disable", JSONObject().put("id", app.definition.id)) }
                stopSelf()
            } else if (app.store.active().isEmpty()) stopSelf() else app.host.start()
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
