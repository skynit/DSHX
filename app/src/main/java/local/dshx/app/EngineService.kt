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
import android.os.SystemClock

class DshApp : Application(), PhoneEvents {
    internal val apps by lazy { AppStore(filesDir, visibleApps = {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo?.enabled == true && it.activityInfo.applicationInfo.enabled }.map { it.activityInfo.packageName }.toSet()
    }) }
    internal val appHost by lazy { AppHost(this, apps, object : AppPhone(this) {
        override fun call(method: String, args: org.json.JSONObject, owner: String, valid: () -> Boolean) =
            PhoneControl(this@DshApp).appCall(method, args, owner, valid)
    }) }
    override fun phoneConnected() { appHost.start() }
    override fun phoneChanged() { appHost.screenEvent() }
    override fun phoneStopped() { appHost.cancelPhone() }
    override fun ownsPhoneWindow(id: Int) = PhoneWindow.current?.owns(id) == true
    override fun closePhoneWindow() { PhoneWindow.current?.close() }
    override fun onCreate() {
        super.onCreate()
        Handler(Looper.getMainLooper()).post { appHost.start() }
    }
    val engine by lazy { Engine(this) }
    val voice by lazy { VoiceControl(this, engine) }
}

class EngineService : Service() {
    private var latestStart = 0
    private var running = false
    private var retryDelay = 1_000L
    private var readySince = 0L
    private val main = Handler(Looper.getMainLooper())
    private val retry = Runnable { startEngine() }
    private val engine get() = (application as DshApp).engine
    private val listener: (EngineState) -> Unit = { state ->
        if (state.url != null && readySince == 0L) readySince = SystemClock.elapsedRealtime()
        getSystemService(NotificationManager::class.java).notify(1, notification(state.phase))
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("engine", "dsh 运行状态", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notification("正在准备"))
        engine.observe(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        main.removeCallbacks(retry)
        if (intent?.action == "stop") {
            running = false
            if (!engine.stop()) finish()
            return START_NOT_STICKY
        }
        running = true
        retryDelay = 1_000L
        startEngine()
        // 系统回收前台服务后可能传入空 Intent，仍需重新启动引擎。
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(retry)
        engine.remove(listener)
        if (engine.state.busy || engine.state.url != null) engine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startEngine() {
        if (!running || engine.active) return
        readySince = 0L
        engine.start(::finished)
    }

    private fun finished(restart: Boolean) {
        if (!running || !restart) {
            running = false
            finish()
            return
        }
        if (readySince != 0L && SystemClock.elapsedRealtime() - readySince >= 60_000L) retryDelay = 1_000L
        getSystemService(NotificationManager::class.java).notify(1, notification("等待恢复，${retryDelay / 1000} 秒后重试"))
        main.removeCallbacks(retry)
        main.postDelayed(retry, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
    }

    private fun finish() {
        if (stopSelfResult(latestStart)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, EngineService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "engine")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("DSHX · $text")
            .setContentText("dsh 在本机运行，点此返回；可随时停止")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()
    }
}
