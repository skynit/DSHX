package local.dshx.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/** 由引擎前台服务在主线程管理，仅在至少一个会话执行任务时持有资源。 */
internal class TaskPower(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var wake: PowerManager.WakeLock? = null
    private var audio: AudioTrack? = null
    private val renew = object : Runnable {
        override fun run() {
            if (!active) return
            hold()
            main.postDelayed(this, 5 * 60_000L)
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        main.removeCallbacks(renew)
        if (active) renew.run() else {
            audio?.let { runCatching { it.release() } }
            audio = null
            wake?.let { runCatching { if (it.isHeld) it.release() } }
            wake = null
        }
    }

    private fun hold() {
        try {
            val lock = wake ?: context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DSHX:task").apply {
                    setReferenceCounted(false)
                }.also { wake = it }
            // 续期覆盖长任务；若续期线程停止响应，锁仍会在十分钟后自动到期。
            lock.acquire(10 * 60_000L)
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
}
