package local.dshx.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** 每次播报固定使用一份已保存的服务配置，取消时同时结束请求与播放。 */
class VoiceOutput(private val context: Context, private val interrupted: () -> Unit = {}) : AutoCloseable {
    data class Settings(
        val provider: String = "edge", val edgeVoice: String = "zh-CN-XiaoxiaoNeural",
        val appId: String = "", val accessKey: String = "",
        val resource: String = VOLC_RESOURCE, val volcVoice: String = VOLC_VOICE,
        val auth: String = "key", val apiKey: String = "",
        val audioVoice: String = VOLC_AUDIO_VOICE,
    ) {
        internal fun checked(): Settings {
            require(provider in setOf("edge", "volc", "volc-audio")) { "请选择受支持的播报服务。" }
            require(edgeVoice.matches(Regex("[A-Za-z0-9-]{1,120}"))) { "Edge 音色名称无效。" }
            require(volcVoice.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "火山音色名称无效。" }
            require(audioVoice.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "火山音频生成音色名称无效。" }
            require(resource.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "火山资源标识无效。" }
            require(auth in setOf("key", "token")) { "请选择火山引擎的鉴权方式。" }
            require(appId.length <= 200 && accessKey.length <= 500 && apiKey.length <= 500 &&
                (appId + accessKey + apiKey).all { it.code in 32..126 }) { "火山凭据格式无效。" }
            if (provider != "edge") {
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
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var active: Operation? = null
    /** 当前实际出声的分句；合成、等待首帧、句间停顿和取消后为空。 */
    @Volatile var speakingText: String = ""
        private set
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    // 每次调用独占自己的资源，旧连接、焦点监听器和主线程任务不能操作下一次播报。
    private class Operation(val cancelled: () -> Boolean) {
        val stopped = AtomicBoolean(false)
        var call: Call? = null
        var socket: WebSocket? = null
        var buffer: AudioBuffer? = null
        var pending: CompletableFuture<Unit>? = null
        var player: MediaPlayer? = null
        var track: AudioTrack? = null
        fun check() { if (cancelled() || stopped.get()) throw CancellationException() }
    }

    private fun begin(cancelled: () -> Boolean): Operation = synchronized(lock) {
        prepare(cancelled)
        check(active == null) { "请按顺序播放语音。" }
        Operation(cancelled).also { active = it }
    }

    fun prepare(cancelled: () -> Boolean) {
        if (cancelled() || closed.get()) throw CancellationException()
        config.checked()
    }

    /** 阻塞到本次声音播完；onStart 在播放位置首次前进时触发，每次调用最多一次。 */
    fun speak(text: String, cancelled: () -> Boolean, onStart: () -> Unit = {}) {
        val operation = begin(cancelled)
        var started = false
        var currentText = ""
        val notifyStart = {
            synchronized(operation) {
                operation.check()
                speakingText = currentText
                val notify = !started
                started = true
                notify
            }.also { notify -> if (notify) onStart() }
            Unit
        }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener(operation), main).build()
        try {
            operation.check()
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "其他应用正在使用声音，请稍后重试。文字回答已保留。"
            }
            for (chunk in chunks(text, sentences = config.provider != "volc-audio")) {
                operation.check()
                currentText = chunk
                if (config.provider == "edge") {
                    PcmPlayer(operation, notifyStart).use { playback ->
                        val frames = Mp3Frames()
                        edge(chunk, operation, null, { playback.drain() }) { bytes ->
                            frames.accept(bytes) { frame -> playback.accept(frame) }
                        }
                        frames.finish()
                        playback.finish()
                    }
                } else {
                    val bytes = volc(chunk, operation, null)
                    operation.check()
                    check(bytes.isNotEmpty()) { "播报服务没有返回声音，文字回答已保留。" }
                    play(bytes, operation, notifyStart)
                }
                speakingText = ""
            }
        } finally {
            try { manager.abandonAudioFocusRequest(focus) } finally { end(operation) }
        }
    }

    private fun focusListener(operation: Operation) = AudioManager.OnAudioFocusChangeListener { change ->
        // 系统识别服务可能临时抢焦点；继续播报，由主控根据识别文本判断打断。
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            synchronized(lock) {
                if (active === operation && !operation.stopped.get()) {
                    stop(operation)
                    interrupted()
                }
            }
        }
    }

    internal fun synthesize(text: String, cancelled: () -> Boolean, endpoint: String? = null): ByteArray {
        val bytes = ByteArrayOutputStream()
        stream(text, cancelled, endpoint) { bytes.write(it) }
        return bytes.toByteArray()
    }

    /** 回调在调用线程执行；Edge 每包立即交付，火山仍交付完整音频。 */
    internal fun stream(text: String, cancelled: () -> Boolean, endpoint: String? = null, audio: (ByteArray) -> Unit) {
        val operation = begin(cancelled)
        try {
            if (config.provider == "edge") edge(text, operation, endpoint, {}, audio)
            else audio(volc(text, operation, endpoint))
        } finally { end(operation) }
    }

    private fun edge(text: String, operation: Operation, endpoint: String?, idle: () -> Unit, audio: (ByteArray) -> Unit) {
        val buffer = AudioBuffer()
        val id = UUID.randomUUID().toString().replace("-", "")
        val url = (endpoint ?: EDGE_URL) +
            "?TrustedClientToken=$EDGE_TOKEN&ConnectionId=$id&Sec-MS-GEC=${edgeToken(System.currentTimeMillis())}" +
            "&Sec-MS-GEC-Version=2-143.0.3650.75"
        val request = Request.Builder().url(url)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
            .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT)};")
            .build()
        fun receiving(webSocket: WebSocket): Boolean {
            if (operation.stopped.get() || operation.cancelled()) {
                buffer.cancel(); webSocket.cancel(); return false
            }
            return !buffer.ended
        }
        val connection = synchronized(operation) {
            operation.check()
            operation.buffer = buffer
            client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!receiving(webSocket)) return
                    val timestamp = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
                        .withZone(ZoneOffset.UTC).format(Instant.now())
                    val configured = webSocket.send("X-Timestamp:$timestamp\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
                    val sent = webSocket.send("X-RequestId:$id\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${timestamp}Z\r\nPath:ssml\r\n\r\n" + edgeSsml(config.edgeVoice, text))
                    if (!configured || !sent) {
                        buffer.fail(IllegalStateException("Edge TTS 请求发送失败。")); webSocket.cancel()
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!receiving(webSocket)) return
                    if (text.substringBefore("\r\n\r\n").lineSequence().any { it.trim() == "Path:turn.end" }) {
                        buffer.finish(); webSocket.close(1000, null)
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!receiving(webSocket)) return
                    try {
                        check(bytes.size <= STREAM_BUFFER + 16384) { "Edge TTS 声音数据包过大。" }
                        buffer.offer(edgeAudio(bytes.toByteArray()))
                    } catch (error: Exception) { buffer.fail(error); webSocket.cancel() }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    buffer.fail(IllegalStateException("Edge TTS 连接失败，请检查网络后重试。文字回答已保留。"))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    buffer.fail(IllegalStateException("Edge TTS 连接提前结束，请重试。"))
                    webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    buffer.fail(IllegalStateException("Edge TTS 连接提前结束，请重试。"))
                }
            }).also { operation.socket = it }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        try {
            while (true) {
                operation.check()
                check(System.nanoTime() < deadline) { "语音服务等待超时，文字回答已保留。" }
                idle()
                val bytes = buffer.poll()
                if (bytes != null) { operation.check(); audio(bytes) }
                else if (buffer.drained()) break
            }
        } finally {
            connection.cancel()
            buffer.cancel()
            synchronized(operation) {
                if (operation.socket === connection) operation.socket = null
                if (operation.buffer === buffer) operation.buffer = null
            }
        }
    }

    private fun volc(text: String, session: Operation, endpoint: String?): ByteArray {
        val cancelled = { session.cancelled() || session.stopped.get() }
        val generated = config.provider == "volc-audio"
        val builder = Request.Builder().url(endpoint ?: if (generated) VOLC_AUDIO_URL else VOLC_URL)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
        if (!generated) builder.header("X-Api-Resource-Id", config.resource)
        if (config.auth == "key") builder.header("X-Api-Key", config.apiKey)
        else builder.header("X-Api-App-Id", config.appId).header("X-Api-Access-Key", config.accessKey)
        val request = builder.post(volcRequest(config, text).toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val operation = (if (generated) audioClient else client).newCall(request)
        synchronized(session) { session.check(); session.call = operation }
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
                if (generated) return body.byteStream().use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (cancelled()) throw CancellationException()
                        val size = input.read(buffer)
                        if (size == -1) break
                        check(bytes.size() + size <= MAX_AUDIO * 2) { "火山引擎返回的数据过大。" }
                        bytes.write(buffer, 0, size)
                    }
                    val packet = runCatching { JSONObject(bytes.toString("UTF-8")) }
                        .getOrElse { error("火山引擎返回的音频响应无效。") }
                    // 音频生成的成功响应直接包含 audio，没有流式合成的结束码。
                    check(!packet.has("code") || packet.optInt("code", -1) in setOf(0, 20000000)) {
                        "火山引擎音频生成失败，请检查音色、权限和额度。"
                    }
                    val audio = volcAudio(packet, "audio")
                    check(audio.isNotEmpty() && audio.size <= MAX_AUDIO) { "火山引擎没有返回有效声音。" }
                    audio
                }
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
        } catch (error: java.io.IOException) {
            if (cancelled()) throw CancellationException()
            android.util.Log.w("VoiceOutput", "火山语音请求失败：${error.javaClass.simpleName}")
            throw IllegalStateException(if (error is java.io.InterruptedIOException)
                "火山引擎生成声音超时，请稍后重试或缩短播报内容。"
                else "火山引擎连接中断，请检查手机网络后重试。")
        } finally { synchronized(session) { if (session.call === operation) session.call = null } }
    }

    private fun play(bytes: ByteArray, operation: Operation, onStart: () -> Unit) {
        val file = File.createTempFile("speech-", ".mp3", context.cacheDir)
        val result = CompletableFuture<Unit>()
        var media: MediaPlayer? = null
        try {
            file.writeBytes(bytes)
            synchronized(operation) { operation.check(); operation.pending = result }
            main.post {
                synchronized(operation) {
                    if (operation.stopped.get() || operation.cancelled() || result.isDone) return@post
                    val player = MediaPlayer()
                    media = player
                    operation.player = player
                    try {
                        player.setAudioAttributes(attributes)
                        player.setDataSource(file.path)
                        player.setOnPreparedListener {
                            synchronized(operation) {
                                if (!operation.stopped.get() && !operation.cancelled() && !result.isDone) it.start()
                            }
                        }
                        player.setOnCompletionListener { result.complete(Unit) }
                        player.setOnErrorListener { _, _, _ ->
                            result.completeExceptionally(IllegalStateException("语音播放失败，文字回答已保留。")); true
                        }
                        player.prepareAsync()
                    } catch (_: Exception) { result.completeExceptionally(IllegalStateException("无法播放语音，文字回答已保留。")) }
                }
            }
            await(result, operation) {
                val started = CompletableFuture<Boolean>()
                main.post {
                    synchronized(operation) {
                        started.complete(!operation.stopped.get() && !result.isCompletedExceptionally &&
                            (media?.let { runCatching { it.currentPosition > 0 }.getOrDefault(false) } == true))
                    }
                }
                // 位置查询不阻塞取消；回调在 speak 的工作线程触发。
                if (started.get(100, TimeUnit.MILLISECONDS)) onStart()
            }
        } finally {
            val released = CompletableFuture<Unit>()
            main.post {
                synchronized(operation) {
                    media?.release()
                    if (operation.player === media) operation.player = null
                    if (operation.pending === result) operation.pending = null
                }
                file.delete()
                released.complete(Unit)
            }
            runCatching { released.get(3, TimeUnit.SECONDS) }
        }
    }

    private fun await(result: CompletableFuture<Unit>, operation: Operation, progress: () -> Unit) {
        val timeout = if (config.provider == "volc-audio") AUDIO_TIMEOUT else 90L
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout)
        while (true) {
            operation.check()
            try { progress(); result.get(50, TimeUnit.MILLISECONDS); return }
            catch (_: TimeoutException) { check(System.nanoTime() < deadline) { "语音服务等待超时，文字回答已保留。" } }
            catch (error: ExecutionException) { throw error.cause ?: error }
        }
    }

    private fun stop(operation: Operation) = synchronized(operation) {
        operation.stopped.set(true)
        speakingText = ""
        operation.call?.cancel()
        operation.socket?.cancel()
        operation.buffer?.cancel()
        operation.pending?.completeExceptionally(CancellationException())
        operation.track?.let { runCatching { it.pause(); it.flush() } }
        main.post { synchronized(operation) { operation.player?.let { runCatching { it.pause() } } } }
    }

    private fun end(operation: Operation) = synchronized(lock) {
        stop(operation)
        if (active === operation) active = null
    }

    fun stop() { synchronized(lock) { active?.let { stop(it) } } }
    override fun close() { synchronized(lock) { closed.set(true); active?.let { stop(it) } } }

    /** 以字节数限流；队列满立即报错并断连，不能堵塞 WebSocket 的结束/断连回调。 */
    internal class AudioBuffer(private val maximum: Int = STREAM_BUFFER) {
        private val lock = java.lang.Object()
        private val queue = java.util.ArrayDeque<ByteArray>()
        private var size = 0
        private var total = 0
        private var error: Throwable? = null
        @Volatile var ended = false
            private set

        fun offer(bytes: ByteArray) = synchronized(lock) {
            if (ended || bytes.isEmpty()) return@synchronized
            val failure = when {
                bytes.size > maximum - size -> IllegalStateException("语音播放缓冲已满，请稍后重试。")
                bytes.size > MAX_AUDIO - total -> IllegalStateException("语音数据过大，请缩短播报内容。")
                else -> null
            }
            if (failure != null) { fail(failure); throw failure }
            queue.addLast(bytes); size += bytes.size; total += bytes.size
            lock.notifyAll()
        }
        fun finish() = synchronized(lock) {
            if (!ended) {
                if (total == 0) error = IllegalStateException("Edge TTS 没有返回声音，请检查音色或稍后重试。")
                ended = true; lock.notifyAll()
            }
        }
        fun fail(cause: Throwable) = synchronized(lock) {
            if (!ended) { error = cause; ended = true; queue.clear(); size = 0; lock.notifyAll() }
        }
        fun cancel() = synchronized(lock) {
            error = CancellationException(); ended = true; queue.clear(); size = 0; lock.notifyAll()
        }
        fun drained(): Boolean = synchronized(lock) {
            error?.let { throw it }
            ended && queue.isEmpty()
        }
        fun poll(): ByteArray? = synchronized(lock) {
            if (queue.isEmpty() && !ended) lock.wait(20)
            error?.let { throw it }
            queue.pollFirst()?.also { size -= it.size }
        }
    }

    /** 网络包边界不等于 MP3 帧边界；保留半帧，连续喂给同一个解码器。 */
    internal class Mp3Frames {
        private var pending = byteArrayOf()
        private var first = true
        private var count = 0
        fun accept(bytes: ByteArray, frame: (ByteArray) -> Unit) {
            check(bytes.size <= STREAM_BUFFER) { "MP3 数据包过大。" }
            val data = pending + bytes
            var offset = 0
            if (first) {
                if (data.size < 10) { pending = data; return }
                if (data[0] == 73.toByte() && data[1] == 68.toByte() && data[2] == 51.toByte()) {
                    check((6..9).all { data[it].toInt() and 128 == 0 }) { "MP3 标签无效。" }
                    val tagSize = (6..9).fold(0) { n, i -> (n shl 7) or data[i].toInt() } +
                        10 + if (data[5].toInt() and 16 != 0) 10 else 0
                    check(tagSize <= 65536) { "MP3 标签过大。" }
                    if (data.size < tagSize) { pending = data; return }
                    offset = tagSize
                }
                first = false
            }
            while (data.size - offset >= 4) {
                val a = data[offset].toInt() and 255
                val b = data[offset + 1].toInt() and 255
                val c = data[offset + 2].toInt() and 255
                val d = data[offset + 3].toInt() and 255
                // 请求固定为 MPEG-2 Layer III、24kHz、单声道，拒绝不匹配的格式。
                check(a == 255 && b and 254 == 242 && c and 12 == 4 && d and 192 == 192) { "Edge TTS MP3 帧格式无效。" }
                val rate = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)[c ushr 4]
                check(rate > 0) { "Edge TTS MP3 码率无效。" }
                val length = 72000 * rate / 24000 + (c shr 1 and 1)
                if (data.size - offset < length) break
                frame(data.copyOfRange(offset, offset + length))
                count++
                offset += length
            }
            pending = data.copyOfRange(offset, data.size)
        }
        fun finish() { check(count > 0 && pending.isEmpty()) { "Edge TTS MP3 音频不完整。" } }
    }

    private inner class PcmPlayer(private val operation: Operation, private val onStart: () -> Unit) : AutoCloseable {
        private var codec: MediaCodec? = null
        private var track: AudioTrack? = null
        private val info = MediaCodec.BufferInfo()
        private var samples = 0L
        private var written = 0L
        private var eos = false
        private var started = false
        private var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)

        private fun checkProgress() {
            operation.check()
            check(System.nanoTime() < deadline) { "语音解码或播放超时，请重试。" }
            if (!started && (track?.playbackHeadPosition ?: 0) != 0) { started = true; onStart() }
        }
        private fun progress() { deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15) }

        fun accept(frame: ByteArray) {
            operation.check()
            if (codec == null) {
                val decoder = MediaCodec.createDecoderByType("audio/mpeg")
                codec = decoder
                decoder.configure(MediaFormat.createAudioFormat("audio/mpeg", 24000, 1).apply {
                    setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }, null, null, 0)
                decoder.start()
            }
            progress()
            input(frame, 0)
            samples += 576
            drain()
        }

        private fun input(bytes: ByteArray, flags: Int) {
            val decoder = checkNotNull(codec)
            while (true) {
                checkProgress()
                val index = decoder.dequeueInputBuffer(10000)
                if (index >= 0) {
                    val buffer = checkNotNull(decoder.getInputBuffer(index))
                    buffer.clear()
                    check(buffer.remaining() >= bytes.size) { "语音解码缓冲不足。" }
                    buffer.put(bytes)
                    decoder.queueInputBuffer(index, 0, bytes.size, samples * 1000000 / 24000, flags)
                    progress(); return
                }
                drain()
            }
        }

        fun drain() {
            val decoder = codec ?: return
            checkProgress()
            while (true) {
                operation.check()
                val index = decoder.dequeueOutputBuffer(info, 0)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = decoder.outputFormat
                    check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 24000 &&
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1 &&
                        (!format.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)) { "语音解码输出格式无效。" }
                    continue
                }
                if (index < 0) return
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val pcm = checkNotNull(decoder.getOutputBuffer(index))
                        pcm.position(info.offset); pcm.limit(info.offset + info.size)
                        val output = track ?: openTrack().also { track = it }
                        while (pcm.hasRemaining()) {
                            checkProgress()
                            val count = output.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                            check(count >= 0) { "语音播放失败，文字回答已保留。" }
                            if (count == 0) Thread.sleep(5) else { written += count; progress() }
                        }
                    }
                    eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    progress()
                } finally { decoder.releaseOutputBuffer(index, false) }
                checkProgress()
                if (eos) return
            }
        }

        private fun openTrack(): AudioTrack {
            val minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "此设备不支持语音播放。" }
            val output = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, 4800)).build()
            try {
                check(output.state == AudioTrack.STATE_INITIALIZED) { "语音播放初始化失败。" }
                if (android.os.Build.VERSION.SDK_INT >= 31) output.setStartThresholdInFrames(1)
                else output.setBufferSizeInFrames(576)
                synchronized(operation) {
                    operation.check()
                    operation.track = output
                    output.play()
                }
                return output
            } catch (error: Throwable) { output.release(); throw error }
        }

        fun finish() {
            check(codec != null) { "Edge TTS 没有返回有效音频。" }
            input(byteArrayOf(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            while (!eos) { checkProgress(); drain(); if (!eos) Thread.sleep(5) }
            val output = checkNotNull(track) { "Edge TTS 没有返回有效音频。" }
            check(written > 0 && written % 2 == 0L) { "语音解码输出不完整。" }
            // 短音频也要填满设备的启动阈值；补充的静音不计入语音结束位置。
            val padding = (output.bufferSizeInFrames * 2L - written).coerceAtLeast(0).toInt()
            if (padding > 0) {
                val silence = java.nio.ByteBuffer.allocate(padding)
                while (silence.hasRemaining()) {
                    checkProgress()
                    val count = output.write(silence, silence.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                    check(count >= 0) { "语音播放失败。" }
                    if (count == 0) Thread.sleep(5) else progress()
                }
            }
            // 等待已写入的 PCM 真正播完，不能在 turn.end 或解码 EOS 时截掉句尾。
            progress()
            var position = 0L
            while (true) {
                checkProgress()
                val played = output.playbackHeadPosition.toLong() and 0xffffffffL
                if (played != position) { position = played; progress() }
                if (played >= written / 2) break
                Thread.sleep(5)
            }
        }

        override fun close() {
            try {
                synchronized(operation) {
                    track?.let { output ->
                        try { runCatching { output.pause(); output.flush() }; output.release() }
                        finally { if (operation.track === output) operation.track = null }
                    }
                }
            } finally {
                codec?.let { decoder -> try { runCatching { decoder.stop() } } finally { decoder.release() } }
            }
        }
    }

    companion object {
        const val VOLC_RESOURCE = "seed-tts-1.0"
        const val VOLC_VOICE = "zh_female_wanwanxiaohe_moon_bigtts"
        const val VOLC_AUDIO_VOICE = "zh_female_xiaohe_uranus_bigtts"
        private const val EDGE_URL = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val VOLC_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
        private const val VOLC_AUDIO_URL = "https://openspeech.bytedance.com/api/v3/tts/create"
        private const val EDGE_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val STREAM_BUFFER = 256 * 1024
        private const val MAX_AUDIO = 8 * 1024 * 1024
        private const val AUDIO_TIMEOUT = 180L
        private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        private val audioClient = client.newBuilder().readTimeout(AUDIO_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(AUDIO_TIMEOUT, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

        fun settings(context: Context): Settings {
            val p = context.getSharedPreferences("voice-output", Context.MODE_PRIVATE)
            return Settings(p.getString("provider", "edge") ?: "edge", p.getString("edgeVoice", "zh-CN-XiaoxiaoNeural") ?: "zh-CN-XiaoxiaoNeural",
                p.getString("appId", "").orEmpty(), p.getString("accessKey", "").orEmpty(),
                p.getString("resource", VOLC_RESOURCE) ?: VOLC_RESOURCE, p.getString("volcVoice", VOLC_VOICE) ?: VOLC_VOICE,
                p.getString("auth", "key") ?: "key", p.getString("apiKey", "").orEmpty(),
                p.getString("audioVoice", VOLC_AUDIO_VOICE) ?: VOLC_AUDIO_VOICE)
        }

        fun save(context: Context, value: Settings) {
            val clean = value.copy(edgeVoice = value.edgeVoice.trim(), appId = value.appId.trim(), accessKey = value.accessKey.trim(),
                resource = value.resource.trim(), volcVoice = value.volcVoice.trim(), apiKey = value.apiKey.trim(),
                audioVoice = value.audioVoice.trim()).checked()
            check(context.getSharedPreferences("voice-output", Context.MODE_PRIVATE).edit()
                .putString("provider", clean.provider).putString("edgeVoice", clean.edgeVoice).putString("appId", clean.appId)
                .putString("accessKey", clean.accessKey).putString("resource", clean.resource).putString("volcVoice", clean.volcVoice)
                .putString("auth", clean.auth).putString("apiKey", clean.apiKey)
                .putString("audioVoice", clean.audioVoice).commit()) { "语音配置保存失败，请重试。" }
        }

        internal fun volcRequest(settings: Settings, text: String): JSONObject {
            if (settings.provider == "volc-audio") return JSONObject()
                .put("model", "seed-audio-1.0")
                .put("text_prompt", "请使用@音频1的声音，逐字朗读以下文本，不添加其他话语或音效：\n$text")
                .put("references", org.json.JSONArray().put(JSONObject().put("speaker", settings.audioVoice)))
                .put("audio_config", JSONObject().put("format", "mp3").put("sample_rate", 24000))
            return JSONObject().put("user", JSONObject().put("uid", "dshx"))
                .put("req_params", JSONObject().put("text", text).put("speaker", settings.volcVoice)
                    .put("audio_params", JSONObject().put("format", "mp3").put("sample_rate", 24000)))
        }

        internal fun volcAudio(packet: JSONObject, field: String = "data"): ByteArray {
            val value = if (packet.isNull(field)) "" else packet.getString(field)
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
        fun chunks(text: String, maximum: Int = 300, sentences: Boolean = true): List<String> {
            require(maximum > 0)
            val clean = text.replace(Regex("```[\\s\\S]*?```"), "，代码请查看聊天界面。")
                .replace(Regex("!?\\[([^]]+)]\\([^)]+\\)"), "$1").replace(Regex("[*#`]+"), "").trim()
            val parts = if (sentences) clean.split(Regex("(?<=[。！？!?\n])")) else listOf(clean)
            return parts.flatMap { sentence ->
                val points = sentence.codePoints().toArray()
                points.toList().chunked(maximum).map { String(it.toIntArray(), 0, it.size).trim() }
            }.filter { it.isNotBlank() }
        }
    }
}
