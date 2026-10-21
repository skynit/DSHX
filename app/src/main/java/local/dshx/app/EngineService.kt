package local.dshx.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

class DshApp : Application() {
    val engine by lazy { Engine(this) }
}

class EngineService : Service() {
    private var latestStart = 0
    private val engine get() = (application as DshApp).engine
    private val listener: (EngineState) -> Unit = { state ->
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
        if (intent?.action == "stop") {
            if (!engine.stop()) finish()
        } else {
            engine.start { finish() }
        }
        // A killed process must never resume a model task without a user action.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine.remove(listener)
        if (engine.state.busy || engine.state.url != null) engine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
