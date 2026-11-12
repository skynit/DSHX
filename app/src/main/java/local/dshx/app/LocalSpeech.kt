package local.dshx.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 参考 sherpa-onnx v1.13.2 的 SherpaOnnxVadAsr：本机分段后进行整句识别。 */
internal class LocalSpeech(private val context: Context, private val models: VoiceModels) : AutoCloseable {
    private var recognizer: OfflineRecognizer? = null
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var cancelled = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null

    fun listen(cancelled: () -> Boolean, timeoutSeconds: Int, allowEmpty: Boolean, playback: Boolean,
               filter: (String) -> String, onAudio: () -> Unit, onLevel: (Float) -> Unit, state: (String) -> Unit): String {
        val stop = AtomicBoolean(false)
        synchronized(this) {
            check(!closed.get()) { "本地语音识别已关闭。" }
            check(running.compareAndSet(false, true)) { "上一轮收音尚未结束。" }
            this.cancelled = stop
        }
        val stopped = { stop.get() || closed.get() || cancelled() }
        try {
            if (stopped()) throw CancellationException()
            if (recognizer == null) {
                state("正在加载本地识别")
                models.verify("speech")
                models.requireMemory("speech")
                if (stopped()) throw CancellationException()
                recognizer = OfflineRecognizer(config = recognizerConfig(models.directory("speech")))
            }
            if (stopped()) throw CancellationException()
            val vad = Vad(config = vadConfig(File(models.directory("speech"), "silero_vad.onnx").path))
            try {
                return record(vad, stopped, timeoutSeconds, allowEmpty, playback, filter, onAudio, onLevel, state)
            } finally { vad.release() }
        } finally {
            running.set(false)
            // close 可由其他线程发起；本机推理结束前不释放仍在使用的对象。
            if (closed.get() && running.compareAndSet(false, true)) {
                try { release() } finally { running.set(false) }
            }
        }
    }

    private fun record(vad: Vad, cancelled: () -> Boolean, timeoutSeconds: Int, allowEmpty: Boolean, playback: Boolean,
                       filter: (String) -> String, onAudio: () -> Unit, onLevel: (Float) -> Unit, state: (String) -> Unit): String {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "当前设备无法打开麦克风。" }
        val audio = AudioRecord(if (playback) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
        var echo: AcousticEchoCanceler? = null
        var capture: Thread? = null
        val finished = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        // 识别在当前工作线程执行，录音独立继续；缓冲最多约 8 秒，不无限积压声音。
        val frames = ArrayBlockingQueue<FloatArray>(256)
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败。" }
            if (playback && AcousticEchoCanceler.isAvailable()) {
                echo = AcousticEchoCanceler.create(audio.audioSessionId)
                echo?.setEnabled(true)
            }
            recorder = audio
            if (cancelled()) throw CancellationException()
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音，请检查麦克风权限。" }
            capture = Thread({
                try {
                    val buffer = ShortArray(512)
                    while (!finished.get() && !cancelled()) {
                        check(context.getSystemService(AudioManager::class.java).mode !in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
                            "通话正在使用声音设备，语音已停止。"
                        }
                        val read = audio.read(buffer, 0, buffer.size)
                        check(read > 0) { "麦克风被占用或已停止，请重新开始。" }
                        check(frames.offer(FloatArray(read) { buffer[it] / 32768f })) { "声音处理跟不上收音，请分段说话后重试。" }
                    }
                } catch (error: Throwable) { if (!finished.get() && !cancelled()) failure.set(error) }
            }, "voice-record").apply { isDaemon = true; start() }
            val history = AudioBuffer(RATE * 32)
            val detector = object : Detector {
                override fun accept(samples: FloatArray) { history.add(samples); vad.acceptWaveform(samples) }
                override fun detected(): Boolean = vad.isSpeechDetected()
                override fun take(): FloatArray? {
                    if (vad.empty()) return null
                    val segment = vad.front()
                    // VAD 边界可能晚于轻声的首字，保留 300ms 原始前文，避免裁掉句首。
                    val samples = history.prepend(segment.start, segment.samples)
                    vad.pop()
                    return samples
                }
            }
            return collect(detector, read = {
                failure.get()?.let { throw it }
                frames.poll(50, TimeUnit.MILLISECONDS) ?: FloatArray(0)
            }, decode = { samples ->
                check(samples.size <= RATE * 30) { "单次讲话过长，请控制在 30 秒内并分段说话。" }
                val engine = checkNotNull(recognizer)
                val stream = engine.createStream()
                try {
                    stream.acceptWaveform(samples, RATE)
                    engine.decode(stream)
                    engine.getResult(stream).text
                } finally { stream.release() }
            }, cancelled, timeoutSeconds, allowEmpty, filter, onAudio, onLevel, state)
        } finally {
            finished.set(true)
            recorder = null
            runCatching { audio.stop() }
            try { capture?.join(2_000) }
            finally { audio.release(); echo?.release(); onLevel(0f) }
        }
    }

    @Synchronized fun stop() { cancelled.set(true); runCatching { recorder?.stop() } }

    override fun close() {
        closed.set(true)
        stop()
        if (running.compareAndSet(false, true)) {
            try { release() } finally { running.set(false) }
        }
    }

    private fun release() { recognizer?.release(); recognizer = null }

    /** 隔离本机检测器，定向测试可直接验证超时、回声和取消，无需加载 Android JNI。 */
    internal interface Detector {
        fun accept(samples: FloatArray)
        fun detected(): Boolean
        fun take(): FloatArray?
    }

    internal class AudioBuffer(private val capacity: Int) {
        private val samples = FloatArray(capacity)
        private var count = 0

        fun add(frame: FloatArray) {
            for (sample in frame) { samples[count % capacity] = sample; count++ }
        }

        fun prepend(start: Int, speech: FloatArray): FloatArray {
            val first = maxOf(0, start - RATE * 3 / 10, count - capacity)
            if (first >= start || start > count) return speech
            val prefix = start - first
            return FloatArray(prefix + speech.size).also { result ->
                for (i in 0 until prefix) result[i] = samples[(first + i) % capacity]
                speech.copyInto(result, prefix)
            }
        }
    }

    companion object {
        const val RATE = 16000

        internal fun vadConfig(model: String) = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = model, threshold = 0.5f,
                minSilenceDuration = 0.5f, minSpeechDuration = 0.25f, windowSize = 512, maxSpeechDuration = 60f),
            sampleRate = RATE, numThreads = 1, provider = "cpu",
        )

        internal fun recognizerConfig(dir: File) = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(senseVoice = OfflineSenseVoiceModelConfig(
                model = File(dir, "model.int8.onnx").path, language = "auto", useInverseTextNormalization = true,
            ), tokens = File(dir, "tokens.txt").path, numThreads = 2, provider = "cpu", modelType = "sense_voice"),
        )

        internal fun collect(detector: Detector, read: () -> FloatArray, decode: (FloatArray) -> String,
                             cancelled: () -> Boolean, timeoutSeconds: Int, allowEmpty: Boolean,
                             filter: (String) -> String = { it.trim() }, onAudio: () -> Unit = {},
                             onLevel: (Float) -> Unit = {}, state: (String) -> Unit = {},
                             now: () -> Long = SystemClock::elapsedRealtime): String {
            val started = now()
            var heard = false
            var speechStarted: Long? = null
            state("正在听")
            while (true) {
                if (cancelled()) throw CancellationException()
                check(now() - started < 60_000) { "本次语音识别时间过长，请分段说话后重试。" }
                onAudio()
                val samples = read()
                if (cancelled()) throw CancellationException()
                if (samples.isNotEmpty()) {
                    var energy = 0.0
                    for (sample in samples) energy += sample.toDouble() * sample
                    onLevel(VoiceInput.volume(energy, samples.size))
                    detector.accept(samples)
                    heard = heard || detector.detected()
                    if (heard && speechStarted == null) speechStarted = now()
                }
                val segment = detector.take()
                if (segment != null && segment.isNotEmpty()) {
                    state("正在识别")
                    val text = filter(decode(segment))
                    if (cancelled()) throw CancellationException()
                    check(now() - started < 60_000) { "本次语音识别时间过长，请分段说话后重试。" }
                    if (text.isNotBlank()) return text
                    heard = false
                    speechStarted = null
                    state("正在听")
                }
                check(speechStarted == null || now() - speechStarted < 30_000) { "单次讲话过长，请控制在 30 秒内并分段说话。" }
                if (!heard && now() - started >= timeoutSeconds * 1000L) {
                    if (allowEmpty) return ""
                    error("没有识别到有效文字，请重新说一次。")
                }
            }
        }
    }
}
