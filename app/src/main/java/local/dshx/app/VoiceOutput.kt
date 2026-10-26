package local.dshx.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** 每次播报固定使用一份已保存的服务配置，取消时同时结束请求与播放。 */
class VoiceOutput(private val context: Context, private val interrupted: () -> Unit = {}) : AutoCloseable {
    data class Settings(
        val provider: String = "edge", val edgeVoice: String = "zh-CN-XiaoxiaoNeural",
        val appId: String = "", val accessKey: String = "",
        val resource: String = VOLC_RESOURCE, val volcVoice: String = VOLC_VOICE,
        val auth: String = "key", val apiKey: String = "",
    ) {
        internal fun checked(): Settings {
            require(provider in setOf("edge", "volc")) { "请选择受支持的播报服务。" }
            require(edgeVoice.matches(Regex("[A-Za-z0-9-]{1,120}"))) { "Edge 音色名称无效。" }
            require(volcVoice.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "火山音色名称无效。" }
            require(resource.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "火山资源标识无效。" }
            require(auth in setOf("key", "token")) { "请选择火山引擎的鉴权方式。" }
            require(appId.length <= 200 && accessKey.length <= 500 && apiKey.length <= 500 &&
                (appId + accessKey + apiKey).all { it.code in 32..126 }) { "火山凭据格式无效。" }
            if (provider == "volc") {
                if (auth == "key") require(apiKey.isNotBlank()) { "请填写火山引擎 API Key。" }
                else require(appId.isNotBlank() && accessKey.isNotBlank()) { "请填写火山引擎 App ID 和 Access Token。" }
            }
            return this
        }

        // 配置会包含凭据，不允许通过默认 data class 输出进入诊断日志。
        override fun toString(): String = "VoiceOutput.Settings(provider=$provider)"
    }

    private val config = settings(context)
    private val main = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var call: Call? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var pending: CompletableFuture<*>? = null
    @Volatile private var player: MediaPlayer? = null
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change -> if (change < 0) { stop(); interrupted() } }, main).build()

    fun prepare(cancelled: () -> Boolean) {
        if (cancelled() || closed.get()) throw CancellationException()
        config.checked()
    }

    fun speak(text: String, cancelled: () -> Boolean) {
        prepare(cancelled)
        stopped.set(false)
        val cancelledNow = { cancelled() || stopped.get() || closed.get() }
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "其他应用正在使用声音，请稍后重试。文字回答已保留。" }
        try {
            for (chunk in chunks(text)) {
                if (cancelledNow()) throw CancellationException()
                val bytes = synthesize(chunk, cancelledNow)
                if (cancelledNow()) throw CancellationException()
                check(bytes.isNotEmpty()) { "播报服务没有返回声音，文字回答已保留。" }
                play(bytes, cancelledNow)
            }
        } finally { manager.abandonAudioFocusRequest(focus) }
    }

    internal fun synthesize(text: String, cancelled: () -> Boolean, endpoint: String? = null): ByteArray {
        prepare(cancelled)
        return if (config.provider == "edge") edge(text, cancelled, endpoint) else volc(text, cancelled, endpoint)
    }

    private fun edge(text: String, cancelled: () -> Boolean, endpoint: String?): ByteArray {
        val result = CompletableFuture<ByteArray>()
        val audio = ByteArrayOutputStream()
        val id = UUID.randomUUID().toString().replace("-", "")
        val url = (endpoint ?: EDGE_URL) +
            "?TrustedClientToken=$EDGE_TOKEN&ConnectionId=$id&Sec-MS-GEC=${edgeToken(System.currentTimeMillis())}" +
            "&Sec-MS-GEC-Version=2-143.0.3650.75"
        val request = Request.Builder().url(url)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
            .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT)};")
            .build()
        pending = result
        val connection = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (cancelled()) { webSocket.cancel(); result.completeExceptionally(CancellationException()); return }
                val timestamp = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
                    .withZone(ZoneOffset.UTC).format(Instant.now())
                webSocket.send("X-Timestamp:$timestamp\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
                webSocket.send("X-RequestId:$id\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${timestamp}Z\r\nPath:ssml\r\n\r\n" + edgeSsml(config.edgeVoice, text))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.substringBefore("\r\n\r\n").lineSequence().any { it.trim() == "Path:turn.end" }) {
                    if (audio.size() == 0) result.completeExceptionally(IllegalStateException("Edge TTS 没有返回声音，请检查音色或稍后重试。"))
                    else result.complete(audio.toByteArray())
                    webSocket.close(1000, null)
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val data = edgeAudio(bytes.toByteArray())
                    check(audio.size() + data.size <= MAX_AUDIO) { "语音数据过大，请缩短播报内容。" }
                    audio.write(data)
                } catch (error: Exception) { result.completeExceptionally(error); webSocket.cancel() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.completeExceptionally(IllegalStateException("Edge TTS 连接失败，请检查网络后重试。文字回答已保留。"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!result.isDone) result.completeExceptionally(IllegalStateException("Edge TTS 连接提前结束，请重试。"))
            }
        })
        socket = connection
        try { return await(result, cancelled) }
        finally { connection.cancel(); if (socket === connection) socket = null; if (pending === result) pending = null }
    }

    private fun volc(text: String, cancelled: () -> Boolean, endpoint: String?): ByteArray {
        val builder = Request.Builder().url(endpoint ?: VOLC_URL)
            .header("X-Api-Resource-Id", config.resource).header("X-Api-Request-Id", UUID.randomUUID().toString())
        if (config.auth == "key") builder.header("X-Api-Key", config.apiKey)
        else builder.header("X-Api-App-Id", config.appId).header("X-Api-Access-Key", config.accessKey)
        val request = builder.post(volcRequest(config, text).toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val operation = client.newCall(request)
        call = operation
        try {
            if (cancelled()) throw CancellationException()
            operation.execute().use { response ->
                check(response.isSuccessful) {
                    when (response.code) {
                        401, 403 -> "火山引擎鉴权失败，请检查凭据和音色权限。"
                        429 -> "火山引擎请求受限，请检查额度或稍后重试。"
                        else -> "火山引擎请求失败（HTTP ${response.code}），文字回答已保留。"
                    }
                }
                val body = response.body ?: error("火山引擎没有返回声音。")
                return body.charStream().buffered().use { reader ->
                    val audio = ByteArrayOutputStream()
                    var finished = false
                    var total = 0
                    while (true) {
                        if (cancelled()) throw CancellationException()
                        // 单行大小也受限制，避免在完成一行之前无限分配内存。
                        val line = StringBuilder()
                        var ended = false
                        while (true) {
                            val char = reader.read()
                            if (char == -1) { ended = true; break }
                            if (char == '\n'.code) break
                            check(++total <= MAX_AUDIO * 2) { "火山引擎返回的数据过大。" }
                            line.append(char.toChar())
                        }
                        if (line.isEmpty()) { if (ended) break else continue }
                        val value = line.toString().trim().removePrefix("data:").trim()
                        if (value.isEmpty() || value.startsWith(':') || value.startsWith("event:")) continue
                        val packet = JSONObject(value)
                        val code = packet.optInt("code", -1)
                        check(code == 0 || code == 20000000) { "火山引擎合成失败（错误码 $code），请检查音色、资源权限和额度。" }
                        val data = volcAudio(packet)
                        check(audio.size() + data.size <= MAX_AUDIO) { "语音数据过大。" }
                        audio.write(data)
                        if (code == 20000000) { finished = true; break }
                    }
                    check(finished && audio.size() > 0) { "火山引擎声音接收不完整，请重试。文字回答已保留。" }
                    audio.toByteArray()
                }
            }
        } finally { if (call === operation) call = null }
    }

    private fun play(bytes: ByteArray, cancelled: () -> Boolean) {
        val file = File.createTempFile("speech-", ".mp3", context.cacheDir)
        val result = CompletableFuture<Unit>()
        try {
            file.writeBytes(bytes)
            pending = result
            main.post {
                if (cancelled() || pending !== result) { result.completeExceptionally(CancellationException()); return@post }
                val media = MediaPlayer()
                player = media
                try {
                    media.setAudioAttributes(attributes)
                    media.setDataSource(file.path)
                    media.setOnPreparedListener { if (cancelled()) result.completeExceptionally(CancellationException()) else it.start() }
                    media.setOnCompletionListener { result.complete(Unit) }
                    media.setOnErrorListener { _, _, _ -> result.completeExceptionally(IllegalStateException("语音播放失败，文字回答已保留。")); true }
                    media.prepareAsync()
                } catch (_: Exception) { result.completeExceptionally(IllegalStateException("无法播放语音，文字回答已保留。")) }
            }
            await(result, cancelled)
        } finally {
            if (pending === result) pending = null
            val released = CompletableFuture<Unit>()
            main.post { player?.release(); player = null; released.complete(Unit) }
            // 播放对象释放后再清理临时文件；不把音频加入会话备份。
            runCatching { released.get(3, TimeUnit.SECONDS) }
            file.delete()
        }
    }

    private fun <T> await(result: CompletableFuture<T>, cancelled: () -> Boolean): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (true) {
            if (cancelled()) throw CancellationException()
            try { return result.get(100, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { check(System.nanoTime() < deadline) { "语音服务等待超时，文字回答已保留。" } }
        }
    }

    fun stop() {
        stopped.set(true)
        call?.cancel(); socket?.cancel(); pending?.completeExceptionally(CancellationException())
        main.post { player?.let { runCatching { it.pause() } } }
    }
    override fun close() { closed.set(true); stop() }

    companion object {
        const val VOLC_RESOURCE = "seed-tts-2.0"
        const val VOLC_VOICE = "zh_female_xiaohe_uranus_bigtts"
        private const val EDGE_URL = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val VOLC_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
        private const val EDGE_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val MAX_AUDIO = 8 * 1024 * 1024
        private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()

        fun settings(context: Context): Settings {
            val p = context.getSharedPreferences("voice-output", Context.MODE_PRIVATE)
            return Settings(p.getString("provider", "edge") ?: "edge", p.getString("edgeVoice", "zh-CN-XiaoxiaoNeural") ?: "zh-CN-XiaoxiaoNeural",
                p.getString("appId", "").orEmpty(), p.getString("accessKey", "").orEmpty(),
                p.getString("resource", VOLC_RESOURCE) ?: VOLC_RESOURCE, p.getString("volcVoice", VOLC_VOICE) ?: VOLC_VOICE,
                p.getString("auth", "key") ?: "key", p.getString("apiKey", "").orEmpty())
        }

        fun save(context: Context, value: Settings) {
            val clean = value.copy(edgeVoice = value.edgeVoice.trim(), appId = value.appId.trim(), accessKey = value.accessKey.trim(),
                resource = value.resource.trim(), volcVoice = value.volcVoice.trim(), apiKey = value.apiKey.trim()).checked()
            check(context.getSharedPreferences("voice-output", Context.MODE_PRIVATE).edit()
                .putString("provider", clean.provider).putString("edgeVoice", clean.edgeVoice).putString("appId", clean.appId)
                .putString("accessKey", clean.accessKey).putString("resource", clean.resource).putString("volcVoice", clean.volcVoice)
                .putString("auth", clean.auth).putString("apiKey", clean.apiKey).commit()) { "语音配置保存失败，请重试。" }
        }

        internal fun volcRequest(settings: Settings, text: String): JSONObject = JSONObject()
            .put("user", JSONObject().put("uid", "dshx"))
            .put("req_params", JSONObject().put("text", text).put("speaker", settings.volcVoice)
                .put("audio_params", JSONObject().put("format", "mp3").put("sample_rate", 24000)))

        internal fun volcAudio(packet: JSONObject): ByteArray {
            val value = if (packet.isNull("data")) "" else packet.getString("data")
            return if (value.isEmpty()) byteArrayOf() else try { Base64.decode(value, Base64.DEFAULT) }
                catch (_: IllegalArgumentException) { error("火山引擎返回的声音格式无效。") }
        }

        internal fun edgeToken(time: Long): String = EdgeToken.generate(time)

        internal fun edgeSsml(voice: String, text: String): String {
            fun escape(value: String) = value.filter { it >= ' ' || it == '\n' || it == '\t' }
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
            return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='${escape(voice)}'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>${escape(text)}</prosody></voice></speak>"
        }

        internal fun edgeAudio(bytes: ByteArray): ByteArray {
            check(bytes.size >= 2) { "Edge TTS 声音数据不完整。" }
            val size = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
            check(size <= bytes.size - 2) { "Edge TTS 声音数据头无效。" }
            val headers = bytes.copyOfRange(2, size + 2).toString(Charsets.UTF_8).lineSequence().map { it.trim() }.toSet()
            check("Path:audio" in headers) { "Edge TTS 返回了未知声音数据。" }
            val data = bytes.copyOfRange(size + 2, bytes.size)
            check("Content-Type:audio/mpeg" in headers || data.isEmpty()) { "Edge TTS 声音格式无效。" }
            return data
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
