package local.dshx.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/** 由引擎前台服务在主线程管理，任务执行时持续保活，空闲后保活三十分钟。 */
internal class TaskPower(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    // null 表示已停止；空闲超时后保留 false，避免状态刷新重新触发保活。
    private var active: Boolean? = null
    private var idleUntil = 0L
    private var wake: PowerManager.WakeLock? = null
    private var audio: AudioTrack? = null
    private val renew = object : Runnable {
        override fun run() {
            if (active == null) return
            val timeout = if (active == true) WAKE_TIMEOUT else (idleUntil - SystemClock.elapsedRealtime()).coerceAtMost(WAKE_TIMEOUT)
            if (timeout <= 0) { release(); return }
            hold(timeout)
            main.postDelayed(this, timeout.coerceAtMost(RENEW_INTERVAL))
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        idleUntil = if (value) 0L else SystemClock.elapsedRealtime() + IDLE_TIMEOUT
        main.removeCallbacks(renew)
        renew.run()
    }

    fun stop() {
        active = null
        idleUntil = 0L
        release()
    }

    private fun release() {
        main.removeCallbacks(renew)
        audio?.let { runCatching { it.release() } }
        audio = null
        wake?.let { runCatching { if (it.isHeld) it.release() } }
        wake = null
    }

    private fun hold(timeout: Long) {
        try {
            val lock = wake ?: context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DSHX:task").apply {
                    setReferenceCounted(false)
                }.also { wake = it }
            // 续期覆盖长任务；锁最多十分钟到期，空闲时不超过剩余保活时间。
            lock.acquire(timeout)
        } catch (error: Exception) { Log.w("DSHXTask", "任务唤醒锁申请失败", error) }
        if (audio?.playState == AudioTrack.PLAYSTATE_PLAYING) return
        audio?.let { runCatching { it.release() } }
        audio = null
        var track: AudioTrack? = null
        try {
            // 8000 Hz、单声道、16 位 PCM：16000 字节全零数据正好是一秒静音。
            val silence = ByteArray(16_000)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(8_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(silence.size).build()
            check(track.state != AudioTrack.STATE_UNINITIALIZED)
            check(track.write(silence, 0, silence.size) == silence.size)
            check(track.setLoopPoints(0, 8_000, -1) == AudioTrack.SUCCESS)
            check(track.setVolume(0f) == AudioTrack.SUCCESS)
            track.play()
            audio = track
        } catch (error: Exception) {
            track?.let { runCatching { it.release() } }
            Log.w("DSHXTask", "任务静音循环启动失败，保留唤醒锁并在续期时重试", error)
        }
    }

    private companion object {
        const val IDLE_TIMEOUT = 30 * 60_000L
        const val WAKE_TIMEOUT = 10 * 60_000L
        const val RENEW_INTERVAL = 5 * 60_000L
    }
}
