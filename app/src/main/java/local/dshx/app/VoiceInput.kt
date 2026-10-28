package local.dshx.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** 关键词检测释放麦克风后，整句识别由手机配置的系统服务负责。 */
class VoiceInput(private val context: Context, private val models: VoiceModels) : AutoCloseable {
    private class ServiceError(val code: Int) : IllegalStateException(errorMessage(code))

    /** 一次 listen 共用的插话判断；切换识别服务时仍然只通知一次。 */
    private class Speech(private val onSpeech: () -> Unit, private val echoText: () -> String) {
        private data class Echo(val text: String, var time: Long)
        private val echoes = mutableListOf<Echo>()
        private var current = ""
        private var notified = false

        fun playing() {
            val echo = normalize(echoText())
            val now = SystemClock.elapsedRealtime()
            if (echo != current) {
                echoes.firstOrNull { it.text == current }?.time = now
                current = echo
            }
            echoes.removeAll { now - it.time > 2_000 }
            if (echo.isNotEmpty()) {
                val saved = echoes.firstOrNull { it.text == echo }
                if (saved == null) echoes.add(Echo(echo, now)) else saved.time = now
                while (echoes.size > 4) echoes.removeAt(0)
            }
        }

        fun text(words: String): String {
            playing()
            val clean = normalize(words)
            if (clean.isEmpty() || echoes.any { matches(clean, it.text) }) return ""
            if (clean.length == 1 && clean[0] in "嗯啊呃唔额") return ""
            if (echoes.isEmpty()) return words.trim()
            // 服务可能把先前播报和用户插话合并成一句，只去掉明确匹配的回声前缀。
            var prefix = 0
            for (echo in echoes) {
                for (size in minOf(clean.length, echo.text.length) downTo 4) {
                    if (echo.text.endsWith(clean.take(size))) { prefix = maxOf(prefix, size); break }
                }
            }
            if (prefix == 0) return words.trim()
            var letters = 0
            val end = words.indexOfFirst { char ->
                if (char.isLetterOrDigit()) letters++
                letters > prefix
            }
            if (end < 0) return ""
            val rest = words.substring(end).trim()
            val content = normalize(rest)
            return if (content.isEmpty() || content.length == 1 && content[0] in "嗯啊呃唔额") "" else rest
        }

        fun notifySpeech() {
            if (notified) return
            notified = true
            onSpeech()
        }

        private fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)

        private fun matches(text: String, echo: String): Boolean {
            if (text in echo) return true
            // 较长回声容忍少量识别错字；短词必须完全匹配，避免吞掉“停下”等插话。
            if (text.length < 6 || text.length > echo.length) return false
            val limit = minOf(3, text.length / 6)
            for (start in 0..echo.length - text.length) {
                // 尾部新出现的“停下”等文字不能当成可容忍的回声误差。
                if (!echo.regionMatches(start + text.length - 2, text, text.length - 2, 2)) continue
                var errors = 0
                for (i in text.indices) {
                    if (text[i] != echo[start + i] && ++errors > limit) break
                }
                if (errors <= limit) return true
            }
            return false
        }
    }

    private class Request {
        val done = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        var recognizer: SpeechRecognizer? = null // 仅在主线程创建、使用和释放。
        @Volatile var ready = 0L
        @Volatile var heard = false
        @Volatile var began = false
        @Volatile var partial = false
        @Volatile var progress = 0L
        @Volatile var length = 0
        @Volatile var ended = 0L
        val stopping = AtomicBoolean(false)
        var candidate = ""
        var candidateAt = 0L
        @Volatile var text = ""
        @Volatile var failure: Exception? = null

        fun words(text: String, now: Long): String {
            val clean = text.filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)
            if (clean.isNotEmpty()) {
                heard = true
                // 重复文字、标点变动、缩短和等长改写都不能被噪音用来延长收音。
                if (clean.length > length) { length = clean.length; progress = now }
            }
            return clean
        }

        fun confirms(text: String, now: Long): Boolean {
            if (text.length < 2) { candidate = ""; return false }
            if (candidate.isEmpty() || !text.startsWith(candidate.take(2))) {
                candidate = text; candidateAt = now; return false
            }
            return now - candidateAt >= 300
        }

        fun finish(text: String = "", failure: Exception? = null) {
            if (!finished.compareAndSet(false, true)) return
            this.text = text
            this.failure = failure
            done.countDown()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var request: Request? = null
    @Volatile private var recorder: AudioRecord? = null
    private var wakeVerified = false

    fun listen(wake: String?, cancelled: () -> Boolean, timeoutSeconds: Int = 5, allowEmpty: Boolean = false,
               playback: Boolean = false, onWake: () -> Unit = {}, onLevel: (Float) -> Unit = {}, state: (String) -> Unit): String {
        return listen(wake, cancelled, timeoutSeconds, allowEmpty, playback, onWake, onLevel, {}, { "" }, state)
    }

    /**
     * onSpeech 在主线程最多通知一次，不停止识别；返回值始终来自最终结果。
     * echoText 由控制器提供当前播报文字。系统服务不保证回声消除或中间结果，
     * 开始事件与音量不触发插话；普通中间文字需至少两字、两次一致确认且间隔至少 300ms。
     * 播报回声历史最多保留四条、两秒；文字 1800ms 不再增长时主动停止收音并等待真实最终结果。
     * 静音和纯回声可通过 allowEmpty 交给控制器重启。
     */
    fun listen(wake: String?, cancelled: () -> Boolean, timeoutSeconds: Int = 5, allowEmpty: Boolean = false,
               playback: Boolean = false, onWake: () -> Unit = {}, onLevel: (Float) -> Unit = {},
               onSpeech: () -> Unit = {}, echoText: () -> String = { "" }, state: (String) -> Unit): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "语音识别需要在后台执行。" }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "请先允许麦克风权限。" }
        check(available(context)) { "手机没有可用的系统语音识别服务，请在系统语音设置中安装或启用。" }
        if (cancelled()) throw CancellationException()
        if (wake != null) awaitWake(wake, cancelled, playback, onWake, onLevel, state)
        if (cancelled()) throw CancellationException()
        val onDevice = onDeviceAvailable(context)
        val deadline = SystemClock.elapsedRealtime() + 60_000
        val speech = Speech(onSpeech, echoText)
        return try {
            recognize(onDevice, deadline, cancelled, timeoutSeconds, allowEmpty, speech, onLevel, state)
        } catch (error: ServiceError) {
            val unavailable = error.code in setOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
            if (!onDevice || !unavailable || !SpeechRecognizer.isRecognitionAvailable(context)) throw error
            if (cancelled()) throw CancellationException()
            state("正在准备系统语音识别")
            recognize(false, deadline, cancelled, timeoutSeconds, allowEmpty, speech, onLevel, state)
        }
    }

    private fun recognize(onDevice: Boolean, deadline: Long, cancelled: () -> Boolean, timeoutSeconds: Int, allowEmpty: Boolean,
                          speech: Speech, onLevel: (Float) -> Unit, state: (String) -> Unit): String {
        val current = Request()
        request = current
        val waiting = SystemClock.elapsedRealtime()
        main.post {
            if (current.finished.get()) return@post
            if (cancelled()) { current.finish(failure = CancellationException()); return@post }
            if (SystemClock.elapsedRealtime() >= deadline) {
                current.finish(failure = IllegalStateException("本次语音识别时间过长，请分段说话后重试。")); return@post
            }
            try {
                val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    else SpeechRecognizer.createSpeechRecognizer(context)
                current.recognizer = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    private fun active(): Boolean = request === current && !current.finished.get() && !cancelled()

                    override fun onReadyForSpeech(params: Bundle?) {
                        if (!active()) return
                        speech.playing()
                        if (current.ready == 0L) current.ready = SystemClock.elapsedRealtime()
                        state("正在听")
                    }
                    override fun onBeginningOfSpeech() {
                        if (!active()) return
                        if (current.ready == 0L) current.ready = SystemClock.elapsedRealtime()
                        // 系统也可能把环境声报告为“开始说话”，不能据此取消回答。
                        current.began = true
                    }
                    override fun onRmsChanged(rmsdB: Float) {
                        if (active()) onLevel(systemVolume(rmsdB))
                    }
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        if (!active()) return
                        if (current.ended == 0L) current.ended = SystemClock.elapsedRealtime()
                        onLevel(0f)
                        state("正在识别")
                    }
                    override fun onError(error: Int) {
                        if (!active()) return
                        if (allowEmpty && error in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) current.finish()
                        else current.finish(failure = ServiceError(error))
                    }
                    override fun onResults(results: Bundle?) {
                        if (!active()) return
                        val text = speech.text(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull { it.isNotBlank() }.orEmpty())
                        if (text.isNotBlank()) speech.notifySpeech()
                        if (!active()) return
                        if (text.isBlank() && !allowEmpty) current.finish(failure = IllegalStateException("没有识别到有效文字，请重新说一次。"))
                        else current.finish(text)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!active()) return
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull { it.isNotBlank() }.orEmpty()
                        val now = SystemClock.elapsedRealtime()
                        if (partial.isNotBlank()) {
                            if (current.ready == 0L) current.ready = now
                            current.partial = true
                        }
                        val words = speech.text(partial)
                        val clean = current.words(words, now)
                        if (current.confirms(clean, now)) speech.notifySpeech()
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                })
            } catch (_: SecurityException) {
                current.finish(failure = IllegalStateException(errorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)))
            } catch (_: Exception) {
                current.finish(failure = if (onDevice) ServiceError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
                    else IllegalStateException("无法启动系统语音识别，请在系统语音设置中检查语音服务。"))
            }
        }
        try {
            while (!current.done.await(50, TimeUnit.MILLISECONDS)) {
                if (cancelled()) throw CancellationException()
                check(context.getSystemService(AudioManager::class.java).mode !in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
                    "通话正在使用声音设备，语音已停止。"
                }
                val now = SystemClock.elapsedRealtime()
                when {
                    now >= deadline -> current.finish(failure = IllegalStateException("本次语音识别时间过长，请分段说话后重试。"))
                    current.ready == 0L && now - waiting >= 10_000 -> current.finish(failure = if (onDevice) ServiceError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
                        else IllegalStateException("系统语音服务启动超时，请检查服务后重试。"))
                    current.ended != 0L && now - current.ended >= 5_000 ->
                        current.finish(failure = IllegalStateException("系统语音识别超时，请检查网络或语音服务后重试。"))
                    current.ready != 0L && current.ended == 0L && !current.heard && (!current.began || current.partial) &&
                        now - current.ready >= timeoutSeconds * 1000L -> {
                        if (allowEmpty) current.finish()
                        else current.finish(failure = IllegalStateException(errorMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)))
                    }
                    current.progress != 0L && current.ended == 0L && now - current.progress >= 1_800 &&
                        current.stopping.compareAndSet(false, true) -> main.post {
                        val stale = SystemClock.elapsedRealtime() - current.progress >= 1_800
                        if (request !== current || current.finished.get() || cancelled() || current.ended != 0L || !stale) {
                            current.stopping.set(false)
                            return@post
                        }
                        // stopListening 保留已收录的声音并请求最终结果；cancel 会直接丢弃结果。
                        current.ended = SystemClock.elapsedRealtime()
                        onLevel(0f)
                        state("正在识别")
                        try { current.recognizer?.stopListening() }
                        catch (_: Exception) { current.finish(failure = IllegalStateException("无法结束系统收音，请重新开始语音。")) }
                    }
                }
            }
            if (cancelled()) throw CancellationException()
            current.failure?.let { throw it }
            return current.text
        } finally {
            current.finish(failure = CancellationException())
            val released = CountDownLatch(1)
            main.post {
                try {
                    current.recognizer?.let { recognizer ->
                        runCatching { recognizer.cancel() }
                        runCatching { recognizer.destroy() }
                    }
                    current.recognizer = null
                } finally {
                    released.countDown()
                }
            }
            onLevel(0f)
            try {
                check(released.await(5, TimeUnit.SECONDS)) { "系统语音服务停止超时，请稍后重试。" }
            } finally {
                if (request === current) request = null
            }
        }
    }

    private fun awaitWake(wake: String, cancelled: () -> Boolean, playback: Boolean, onWake: () -> Unit,
                          onLevel: (Float) -> Unit, state: (String) -> Unit) {
        if (!wakeVerified) { models.verify("wake"); wakeVerified = true }
        val dir = models.directory("wake")
        val encoded = keyword(wake, File(dir, "tokens.txt"), File(dir, "en.phone"))
        val keywords = File(context.filesDir, "voice/keywords.txt").apply { writeText(encoded + "\n") }
        val spotter = KeywordSpotter(config = KeywordSpotterConfig(modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(dir, "encoder.onnx").path, decoder = File(dir, "decoder.onnx").path, joiner = File(dir, "joiner.onnx").path,
            ), tokens = File(dir, "tokens.txt").path, numThreads = 1, provider = "cpu", modelType = "zipformer2",
        ), keywordsFile = keywords.path))
        var audio: AudioRecord? = null
        var echo: AcousticEchoCanceler? = null
        try {
            val stream = spotter.createStream()
            try {
                val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "当前设备无法打开麦克风。" }
                audio = AudioRecord(if (playback) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败。" }
                if (playback) {
                    check(AcousticEchoCanceler.isAvailable()) { "当前设备不支持播放中的回声消除。" }
                    echo = AcousticEchoCanceler.create(audio.audioSessionId)
                    check(echo != null && echo.setEnabled(true) == 0) { "无法启用回声消除，请关闭播报中唤醒。" }
                }
                recorder = audio
                audio.startRecording()
                state("等待唤醒：$wake")
                val chunk = ShortArray(512)
                var levelSamples = 0
                var energy = 0.0
                while (!cancelled()) {
                    check(context.getSystemService(AudioManager::class.java).mode !in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
                        "通话正在使用声音设备，语音已停止。"
                    }
                    val read = audio.read(chunk, 0, chunk.size)
                    check(read > 0 || cancelled()) { "麦克风被占用或已停止，请重新开始。" }
                    if (read <= 0) break
                    val samples = FloatArray(read) { chunk[it] / 32768f }
                    for (sample in samples) energy += sample.toDouble() * sample
                    levelSamples += read
                    if (levelSamples >= RATE / 10) {
                        onLevel(volume(energy, levelSamples)); levelSamples = 0; energy = 0.0
                    }
                    stream.acceptWaveform(samples, RATE)
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                        if (spotter.getResult(stream).keyword.isNotBlank()) { onWake(); return }
                    }
                }
                throw CancellationException()
            } finally { stream.release() }
        } finally {
            recorder = null
            runCatching { audio?.stop() }; audio?.release()
            echo?.release(); spotter.release(); onLevel(0f)
        }
    }

    fun stop() {
        request?.finish(failure = CancellationException())
        runCatching { recorder?.stop() }
    }
    override fun close() = stop()

    companion object {
        const val RATE = 16000

        fun available(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable(context)

        private fun onDeviceAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        internal fun systemVolume(rms: Float): Float = if (rms.isFinite()) ((rms + 2f) / 12f).coerceIn(0f, 1f) else 0f

        internal fun errorMessage(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "系统语音服务无法录音，请检查麦克风是否被占用。"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "系统语音服务未获授权，请检查麦克风权限并完成语音服务的首次启用。"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统语音识别网络不可用，请检查网络后重试。"
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有识别到有效文字，请重新说一次。"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "系统语音识别正在使用中，请稍后重试。"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "系统语音服务暂不支持中文，请在系统语音设置中启用或下载中文识别。"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "系统语音识别请求过于频繁，请稍后重试。"
            else -> "系统语音识别失败，请检查系统语音服务后重试。"
        }

        internal fun volume(energy: Double, samples: Int): Float =
            if (samples <= 0) 0f else (sqrt(energy / samples) * 4).toFloat().coerceIn(0f, 1f)

        fun keyword(text: String, tokens: File, english: File): String {
            require(text.isNotBlank() && text.length <= 40 && text.none { it == '@' || it == '\n' || it == '\r' }) { "唤醒词需为 1～40 个中文或英文字符。" }
            val vocabulary = tokens.readLines().map { it.substringBefore(' ') }.toSet()
            val syllables by lazy {
                File(tokens.parentFile, "pinyin.txt").useLines { lines ->
                    lines.filter { it.startsWith("U+") }.mapNotNull { line ->
                        val code = line.substringAfter("U+").substringBefore(':').toInt(16)
                        if (code <= Char.MAX_VALUE.code && code.toChar() in text) {
                            code.toChar() to line.substringAfter(':').substringBefore('#').substringBefore(',').trim()
                        } else null
                    }.toMap()
                }
            }
            val lexicon by lazy { english.readLines().associate { val row = it.trim().split(Regex("\\s+")); row.first() to row.drop(1) } }
            val parts = Regex("[\\p{IsHan}]|[A-Za-z']+|\\s+").findAll(text).toList()
            require(parts.joinToString("") { it.value } == text) { "唤醒词仅支持中文、英文单词和空格。" }
            val result = parts.flatMap { match ->
                val value = match.value
                when {
                    value.isBlank() -> emptyList()
                    value[0].code in 0x4e00..0x9fff -> {
                        val syllable = syllables[value[0]] ?: throw IllegalArgumentException("这个汉字暂不支持唤醒，请更换短语。")
                        val initial = listOf("zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w").firstOrNull { syllable.startsWith(it) }
                        if (initial == null) listOf(syllable) else listOf(initial, syllable.removePrefix(initial))
                    }
                    else -> lexicon[value.uppercase()] ?: throw IllegalArgumentException("该英文单词没有唤醒发音，请更换常用单词。")
                }
            }
            require(result.isNotEmpty() && result.all { it in vocabulary }) { "该唤醒词的发音暂不支持，请更换短语。" }
            return result.joinToString(" ") + " @" + text.replace(' ', '_')
        }
    }
}
