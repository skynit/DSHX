package local.dshx.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

class VoiceService : Service() {
    private val voice get() = (application as DshApp).voice
    private var started = false
    private var owner = -1L
    private var start = 0
    private var wake: PowerManager.WakeLock? = null
    private val manager by lazy { getSystemService(AudioManager::class.java) }
    private var devices = emptySet<Int>()
    private val routing = object : AudioDeviceCallback() {
        private fun changed() {
            val next = manager.getDevices(AudioManager.GET_DEVICES_ALL).map { it.id }.toSet()
            if (next != devices && started && voice.generation == owner) voice.stop()
            devices = next
        }
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed()
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (started && voice.generation == owner) voice.stop() }
    }
    private val listener: (VoiceControl.State) -> Unit = { state ->
        if (state.active) getSystemService(NotificationManager::class.java).notify(2, notification(state.status))
        if (started && !state.active && voice.generation == owner) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(start) }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("voice", "语音与麦克风", NotificationManager.IMPORTANCE_LOW))
        startForeground(2, notification("正在准备语音"))
        devices = manager.getDevices(AudioManager.GET_DEVICES_ALL).map { it.id }.toSet()
        manager.registerAudioDeviceCallback(routing, Handler(Looper.getMainLooper()))
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        voice.observe(listener)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        start = startId
        if (intent?.action == "stop") { voice.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        else {
            voice.start(intent?.action ?: "talk"); owner = voice.generation; started = true
            if (!voice.state.active) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId) }
            else if (wake == null) {
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DSHX:voice").apply { acquire() }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        voice.remove(listener)
        if (voice.generation == owner) voice.stop()
        manager.unregisterAudioDeviceCallback(routing); unregisterReceiver(noisy)
        wake?.let { if (it.isHeld) it.release() }; wake = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(status: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 3, Intent(this, VoiceService::class.java).setAction("stop"), flags)
        return Notification.Builder(this, "voice").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("DSHX · $status").setContentText("使用手机系统识别，点此返回；可随时停止麦克风")
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "停止语音", stop).build()).setOngoing(true).build()
    }
}
