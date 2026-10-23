package local.dshx.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Installed offline voices take priority; downloaded MeloTTS covers devices without them. */
class VoiceOutput(private val context: Context, private val interrupted: () -> Unit = {}) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val ready = CompletableFuture<TextToSpeech>()
    private var engine: TextToSpeech? = null
    private var selected: Pair<TextToSpeech, Voice>? = null
    private val stopped = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var prepared = false
    @Volatile private var playback: AudioTrack? = null
    @Volatile private var pending: Pair<String, CompletableFuture<Unit>>? = null
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change -> if (change < 0) { stop(); interrupted() } }, main).build()

    init {
        main.post {
            if (closed.get()) return@post
            engine = TextToSpeech(context.applicationContext) { status ->
                if (closed.get()) { engine?.shutdown(); return@TextToSpeech }
                if (status == TextToSpeech.SUCCESS) ready.complete(engine)
                else ready.completeExceptionally(IllegalStateException("离线语音播报初始化失败。"))
            }.apply {
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { pending?.takeIf { it.first == id }?.second?.complete(Unit) }
                    @Deprecated("Android callback") override fun onError(id: String?) {
                        pending?.takeIf { it.first == id }?.second?.completeExceptionally(IllegalStateException("语音播报失败。"))
                    }
                })
            }
        }
    }

    fun prepare(cancelled: () -> Boolean) {
        if (prepared) return
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!ready.isDone && System.nanoTime() < deadline) {
            if (cancelled() || closed.get()) throw CancellationException()
            try { ready.get(100, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { } catch (_: java.util.concurrent.ExecutionException) { break }
        }
        if (cancelled() || closed.get()) throw CancellationException()
        val tts = if (ready.isDone) runCatching { ready.get() }.getOrNull() else null
        val voice = tts?.voices?.filter { it.locale.language == "zh" && !it.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }?.sortedBy { it.name }?.firstOrNull()
        selected = if (tts != null && voice != null) tts to voice else null
        if (selected == null) {
            val models = VoiceModels(context)
            check(models.ready("output")) { "请先在设置中下载离线播报模型，或安装系统离线中文声音。语音输入仍可使用。" }
            models.verify("output")
        }
        prepared = true
    }

    fun speak(text: String, cancelled: () -> Boolean) {
        prepare(cancelled)
        if (cancelled() || closed.get()) throw CancellationException()
        stopped.set(false)
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "其他应用正在使用声音，请稍后重试。文字回答已保留。" }
        try {
            val system = selected
            if (system != null) speakSystem(text, system.first, system.second) { cancelled() || stopped.get() }
            else speakLocal(text) { cancelled() || stopped.get() }
        } finally { manager.abandonAudioFocusRequest(focus) }
    }

    private fun speakSystem(text: String, tts: TextToSpeech, voice: Voice, cancelled: () -> Boolean) {
        tts.setAudioAttributes(attributes)
        try { for (chunk in chunks(text)) {
            if (cancelled()) throw CancellationException()
            val id = UUID.randomUUID().toString()
            val result = CompletableFuture<Unit>()
            pending = id to result
            main.post {
                if (cancelled() || pending?.first != id) result.completeExceptionally(CancellationException())
                else if (tts.setVoice(voice) != TextToSpeech.SUCCESS || tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                    result.completeExceptionally(IllegalStateException("无法播放离线声音，文字回答已保留。"))
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            try {
                while (true) {
                    if (cancelled()) throw CancellationException()
                    try { result.get(250, TimeUnit.MILLISECONDS); break }
                    catch (_: TimeoutException) { check(System.nanoTime() < deadline) { "语音播报超时，文字回答已保留。" } }
                }
            } finally { if (pending?.first == id) pending = null }
        } } finally { main.post { tts.stop() } }
    }

    private fun speakLocal(text: String, cancelled: () -> Boolean) {
        val models = VoiceModels(context)
        models.requireMemory("output")
        val dir = models.directory("output")
        val native = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(model = File(dir, "model.int8.onnx").path,
                tokens = File(dir, "tokens.txt").path, lexicon = File(dir, "lexicon.txt").path), numThreads = 2,
        ), ruleFsts = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst").joinToString(",") { File(dir, it).path }))
        try {
            for (chunk in chunks(text, 60)) {
                if (cancelled()) throw CancellationException()
                val audio = native.generateWithCallback(chunk, callback = callback(cancelled))
                if (cancelled()) throw CancellationException()
                check(audio.samples.isNotEmpty()) { "离线声音生成失败，文字回答已保留。" }
                val minimum = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                check(minimum > 0) { "当前设备无法播放离线声音。" }
                val track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setBufferSizeInBytes(maxOf(minimum, 16384)).setTransferMode(AudioTrack.MODE_STREAM).build()
                playback = track
                try {
                    check(track.state == AudioTrack.STATE_INITIALIZED) { "离线声音播放初始化失败。" }
                    if (cancelled()) throw CancellationException()
                    track.play()
                    var offset = 0
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                    while (offset < audio.samples.size) {
                        if (cancelled()) throw CancellationException()
                        val count = track.write(audio.samples, offset, minOf(2048, audio.samples.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                        check(count >= 0 && System.nanoTime() < deadline) { "离线声音播放失败，文字回答已保留。" }
                        offset += count
                        if (count == 0) Thread.sleep(10)
                    }
                    while (track.playbackHeadPosition.toLong() < audio.samples.size) {
                        if (cancelled()) throw CancellationException()
                        check(System.nanoTime() < deadline) { "离线声音播放超时。" }
                        Thread.sleep(20)
                    }
                } finally { if (playback === track) playback = null; runCatching { track.stop() }; track.release() }
            }
        } finally { native.release() }
    }

    fun stop() {
        stopped.set(true)
        pending?.second?.completeExceptionally(CancellationException()); pending = null
        runCatching { playback?.pause() }
        main.post { engine?.stop() }
    }
    override fun close() { closed.set(true); stop(); main.post { engine?.shutdown(); engine = null } }

    companion object {
        // Sherpa's JNI looks up invoke(float[]) -> Integer on this concrete object.
        // Kotlin's default invokedynamic lambda only exposes an erased invoke(Object).
        internal fun callback(cancelled: () -> Boolean): (FloatArray) -> Int = object : (FloatArray) -> Int {
            override fun invoke(samples: FloatArray): Int = if (cancelled()) 0 else 1
        }

        fun chunks(text: String, maximum: Int = 300): List<String> {
            require(maximum > 0)
            val clean = text.replace(Regex("```[\\s\\S]*?```"), "，代码请查看聊天界面。")
                .replace(Regex("!?\\[([^]]+)]\\([^)]+\\)"), "$1").replace(Regex("[*#`]+"), "").trim()
            return clean.split(Regex("(?<=[。！？!?\n])")).flatMap { sentence ->
                val points = sentence.codePoints().toArray()
                points.toList().chunked(maximum).map { String(it.toIntArray(), 0, it.size).trim() }
            }.filter { it.isNotBlank() }
        }
    }
}
