package local.dshx.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.min

/** One worker owns the microphone, wake stream, VAD and sentence recognizer. */
class VoiceInput(private val context: Context, private val models: VoiceModels) : AutoCloseable {
    private var recognizer: OfflineRecognizer? = null
    @Volatile private var recorder: AudioRecord? = null
    private var verified = false

    fun listen(wake: String?, cancelled: () -> Boolean, timeoutSeconds: Int = 5, allowEmpty: Boolean = false,
               playback: Boolean = false, onWake: () -> Unit = {}, state: (String) -> Unit): String {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "请先允许麦克风权限。" }
        if (!verified) { models.verify("speech"); models.verify("wake"); verified = true }
        if (wake != null) { recognizer?.release(); recognizer = null }
        if (cancelled()) throw CancellationException()
        val dir = models.directory("wake")
        val vad = Vad(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
            model = File(dir, "silero_vad.onnx").path, minSilenceDuration = 0.8f, minSpeechDuration = 0.15f, maxSpeechDuration = 30f,
        )))
        var spotter: KeywordSpotter? = null
        var stream: OnlineStream? = null
        var audio: AudioRecord? = null
        var echo: AcousticEchoCanceler? = null
        try {
            if (wake != null) {
                val encoded = keyword(wake, File(dir, "tokens.txt"), File(dir, "en.phone"))
                // The native constructor validates a nonempty keyword file before streams can add words.
                val keywords = File(context.filesDir, "voice/keywords.txt").apply { writeText(encoded + "\n") }
                spotter = KeywordSpotter(config = KeywordSpotterConfig(modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder.onnx").path, decoder = File(dir, "decoder.onnx").path, joiner = File(dir, "joiner.onnx").path,
                    ), tokens = File(dir, "tokens.txt").path, numThreads = 1, provider = "cpu", modelType = "zipformer2",
                ), keywordsFile = keywords.path))
                stream = spotter.createStream()
            }
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "当前设备无法打开麦克风。" }
            audio = AudioRecord(if (playback) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败。" }
            if (playback) {
                check(AcousticEchoCanceler.isAvailable()) { "当前设备不支持播放中的回声消除。" }
                echo = AcousticEchoCanceler.create(audio.audioSessionId)
                check(echo != null && echo.setEnabled(true) == 0) { "无法启用回声消除，请关闭播报中唤醒。" }
            }
            recorder = audio
            audio.startRecording()
            state(if (wake == null) "正在听" else "等待唤醒：$wake")
            val chunk = ShortArray(512)
            val buffer = FloatArray(RATE * 32)
            val ring = FloatArray(RATE * 2)
            var ringCount = 0
            var ringPosition = 0
            var count = 0
            var listening = wake == null
            var heard = false
            var elapsed = 0
            while (!cancelled()) {
                check(context.getSystemService(AudioManager::class.java).mode !in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
                    "通话正在使用声音设备，语音已停止。"
                }
                val read = audio.read(chunk, 0, chunk.size)
                check(read > 0 || cancelled()) { "麦克风被占用或已停止，请重新开始。" }
                if (read <= 0) break
                val samples = FloatArray(read) { chunk[it] / 32768f }
                if (!listening) {
                    for (sample in samples) { ring[ringPosition] = sample; ringPosition = (ringPosition + 1) % ring.size; ringCount = min(ringCount + 1, ring.size) }
                    stream!!.acceptWaveform(samples, RATE)
                    while (spotter!!.isReady(stream)) {
                        spotter.decode(stream)
                        if (spotter.getResult(stream).keyword.isNotBlank()) {
                            listening = true
                            for (index in 0 until ringCount) buffer[index] = ring[(ringPosition - ringCount + index + ring.size) % ring.size]
                            count = ringCount
                            vad.reset()
                            vad.acceptWaveform(buffer.copyOf(count))
                            heard = vad.isSpeechDetected() || !vad.empty()
                            onWake()
                            state("正在听")
                            break
                        }
                    }
                    continue
                }
                val take = min(read, buffer.size - count)
                samples.copyInto(buffer, count, 0, take); count += take; elapsed += read
                vad.acceptWaveform(samples)
                heard = heard || vad.isSpeechDetected()
                if (!vad.empty() || count >= RATE * 30 || !heard && elapsed >= RATE * timeoutSeconds) break
            }
            if (cancelled()) throw CancellationException()
            if (!heard && allowEmpty) return ""
            check(heard) { "没有听到说话，请重新开始。" }
            audio.stop(); audio.release(); audio = null; recorder = null
            stream?.release(); stream = null; spotter?.release(); spotter = null
            vad.release()
            state("正在识别")
            val words = transcribe(buffer.copyOf(count)).let { removeWake(it, wake, File(dir, "tokens.txt"), File(dir, "en.phone")) }
            if (words.isBlank() && allowEmpty) return ""
            return words.also {
                if (cancelled()) throw CancellationException()
                check(it.isNotBlank()) { "没有识别到有效文字，请重新说一次。" }
            }
        } finally {
            recorder = null
            runCatching { audio?.stop() }; audio?.release()
            stream?.release(); spotter?.release(); vad.release()
            echo?.release()
        }
    }

    fun transcribe(samples: FloatArray): String {
        require(samples.isNotEmpty() && samples.size <= RATE * 32) { "识别音频为空或过长。" }
        if (recognizer == null) {
            models.requireMemory("speech")
            models.verify("speech")
            val speech = models.directory("speech")
            recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = File(speech, "model.int8.onnx").path, language = "auto"),
                tokens = File(speech, "tokens.txt").path, numThreads = 2, provider = "cpu", modelType = "sense_voice",
            )))
        }
        val engine = recognizer!!
        val stream = engine.createStream()
        try {
            stream.acceptWaveform(samples, RATE); engine.decode(stream)
            return engine.getResult(stream).text.replace(Regex("<\\|[^|]*\\|>"), "").trim()
        } finally {
            stream.release()
            // Answer generation and speech synthesis must not retain the recognizer weights.
            recognizer?.release(); recognizer = null
        }
    }

    fun stop() { runCatching { recorder?.stop() } }
    override fun close() { stop(); recognizer?.release(); recognizer = null }

    companion object {
        const val RATE = 16000

        fun removeWake(text: String, wake: String?, tokens: File? = null, english: File? = null): String {
            if (wake == null) return text.trim()
            val exact = text.replaceFirst(Regex("^\\s*" + wake.map { Regex.escape(it.toString()) }.joinToString("[\\s，,。.!！?？]*") + "[\\s，,。.!！?？]*", RegexOption.IGNORE_CASE), "").trim()
            if (exact != text.trim() || tokens == null || english == null || wake.any { !it.isWhitespace() && it.code !in 0x4e00..0x9fff }) return exact
            // KWS already confirmed the spoken phrase. ASR may spell its leading Chinese
            // homophones differently (小橙 -> 小程); compare model phones only at the start.
            val length = wake.count { !it.isWhitespace() }
            val prefix = Regex("^\\s*((?:[\\p{IsHan}][\\s，,。.!！?？]*){$length})([\\s\\S]*)$").matchEntire(text) ?: return exact
            val spoken = prefix.groupValues[1].filter { it.code in 0x4e00..0x9fff }
            val same = runCatching {
                keyword(spoken, tokens, english).substringBefore(" @") == keyword(wake, tokens, english).substringBefore(" @")
            }.getOrDefault(false)
            return if (same) prefix.groupValues[2].trim() else exact
        }

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
