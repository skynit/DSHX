package local.dshx.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.FutureTask

/** Application-owned voice state; Activity recreation only replaces its subscription. */
class VoiceControl(private val context: Context, private val engine: Engine) {
    data class State(
        val status: String = "语音未开启", val active: Boolean = false, val downloading: Boolean = false,
        val draft: String = "", val session: String? = null, val error: String? = null,
        val speechReady: Boolean = false, val localReady: Boolean = false, val outputReady: Boolean = false,
    )
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val preferences = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    val models = VoiceModels(context)
    @Volatile var state = State(speechReady = models.ready("speech"), localReady = models.ready("local"), outputReady = models.ready("output"))
        private set
    private var version = 0L
    val generation get() = version
    private var stopped = AtomicBoolean(true)
    @Volatile private var input: VoiceInput? = null
    private var output: VoiceOutput? = null
    private var interaction: String? = null
    var wakeWord: String
        get() = preferences.getString("wakeWord", "你好小橙") ?: "你好小橙"
        set(value) {
            check(!state.active) { "请先停止语音再修改唤醒词。" }
            require(value.isNotBlank() && value.length <= 40 && Regex("[\\p{IsHan}A-Za-z' ]+").matches(value)) { "唤醒词需为 1～40 个中文或英文字符。" }
            preferences.edit().putString("wakeWord", value.trim()).apply()
        }
    var mirror: Boolean
        get() = preferences.getBoolean("mirror", false)
        set(value) { preferences.edit().putBoolean("mirror", value).apply() }
    var local: Boolean
        get() = preferences.getBoolean("local", false)
        set(value) { preferences.edit().putBoolean("local", value).apply() }
    var interrupt: Boolean
        get() = preferences.getBoolean("interrupt", false)
        set(value) { preferences.edit().putBoolean("interrupt", value).apply() }
    val echoSupported get() = android.media.audiofx.AcousticEchoCanceler.isAvailable()

    fun observe(listener: (State) -> Unit) { listeners.add(listener); listener(state) }
    fun remove(listener: (State) -> Unit) { listeners.remove(listener) }
    fun draft(text: String) { if (!state.active && state.draft != text) update(state.copy(draft = text.take(8000))) }
    fun select(session: String?) {
        val clean = session?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) }
        if (state.session == clean) return
        if (state.active) stop()
        update(state.copy(session = clean, draft = ""))
    }

    fun download(name: String = "speech") {
        if (state.active) return
        val current = ++version
        val cancel = AtomicBoolean(false); stopped = cancel
        update(state.copy(active = true, downloading = true, error = null, status = "正在准备本地模型"))
        worker.execute {
            try {
                models.install("wake", mirror, cancel::get) { _, _ -> }
                var last = 0L
                models.install(name, mirror, cancel::get) { done, total ->
                    val now = System.nanoTime()
                    if (now - last > 250_000_000 || done == total) {
                        last = now
                        publish(current) { it.copy(status = "正在下载本地模型 ${done * 100 / total}%") }
                    }
                }
                publish(current) { it.copy(status = "本地模型已就绪", speechReady = models.ready("speech"), localReady = models.ready("local"), outputReady = models.ready("output")) }
            } catch (error: Throwable) { failed(current, cancel, error) }
            finally { publish(current) { it.copy(active = false, downloading = false) } }
        }
    }

    fun start(mode: String) {
        require(mode in setOf("input", "talk", "send", "wake"))
        if (state.active) return
        if (mode != "input" && engine.state.url == null) {
            update(state.copy(error = "请先启动 dsh，再使用语音对话。")); return
        }
        val text = state.draft.trim()
        if (mode == "send" && text.isEmpty()) return
        val current = ++version
        val cancel = AtomicBoolean(false); stopped = cancel
        val key = UUID.randomUUID().toString(); interaction = key
        var session = state.session
        val wake = wakeWord
        update(state.copy(active = true, error = null, status = "正在准备语音"))
        worker.execute {
            val audio = VoiceInput(context, models); input = audio
            try {
                models.install("wake", mirror, cancel::get) { _, _ -> }
                if (mode != "input") {
                    output = VoiceOutput(context) { main.post { stop() } }
                    output!!.prepare(cancel::get)
                }
                var next: String? = if (mode == "send") text else null
                do {
                    val words = next ?: audio.listen(if (mode == "wake") wake else null, cancel::get, allowEmpty = mode == "wake") { status ->
                        publish(current) { it.copy(status = status) }
                    }
                    next = null
                    if (cancel.get()) break
                    if (words.isBlank()) continue
                    publish(current) { it.copy(draft = words) }
                    if (mode == "input") {
                        publish(current) { it.copy(status = "已识别，可编辑后发送") }; break
                    }
                    publish(current) { it.copy(status = "正在回答") }
                    val request = JSONObject().put("text", words).put("interaction", key).put("local", local)
                    if (session != null) request.put("session", session)
                    val reply = engine.voice("prompt", request)
                    if (cancel.get()) break
                    val selected = reply.getString("session")
                    session = selected
                    // A newly created voice conversation remains the same session for follow-ups.
                    publish(current) { it.copy(session = selected, draft = "", status = "正在播报") }
                    next = speak(reply.getString("text"), wake, audio, cancel, current, mode != "send")
                    if (cancel.get()) break
                    if (mode == "send") { publish(current) { it.copy(status = "文字已发送，播报完成") }; break }
                    if (next != null) continue
                    publish(current) { it.copy(status = "可以继续说，8 秒无输入后结束") }
                    next = audio.listen(null, cancel::get, timeoutSeconds = 8, allowEmpty = true) { status ->
                        publish(current) { it.copy(status = status) }
                    }.ifBlank { null }
                    if (next == null && mode != "wake") {
                        publish(current) { it.copy(status = "本轮对话完成") }; break
                    }
                } while (!cancel.get())
            } catch (error: Throwable) { failed(current, cancel, error) }
            finally {
                audio.close(); if (input === audio) input = null
                output?.close(); output = null
                publish(current) { it.copy(active = false, status = if (cancel.get()) "语音已停止" else it.status) }
            }
        }
    }

    fun stop() {
        stopped.set(true)
        input?.stop(); output?.stop()
        if (state.active) update(state.copy(status = "正在停止语音"))
        val key = interaction
        if (key != null) kotlin.concurrent.thread(name = "voice-cancel", isDaemon = true) {
            runCatching { engine.voice("cancel", JSONObject().put("interaction", key)) }
        }
    }

    private fun speak(text: String, wake: String, audio: VoiceInput, cancel: AtomicBoolean, current: Long, listen: Boolean): String? {
        if (!interrupt || !listen) { output!!.speak(text, cancel::get); return null }
        // The same microphone capture continues from wake detection into the interrupted sentence.
        val phase = AtomicInteger(0) // 0 listening, 1 wake accepted, 2 playback ended without wake
        val result = FutureTask {
            audio.listen(wake, { cancel.get() || phase.get() == 2 }, allowEmpty = true, playback = true,
                onWake = { if (phase.compareAndSet(0, 1)) output?.stop() }) { status ->
                if (phase.get() == 1) publish(current) { it.copy(status = status) }
            }
        }
        val watcher = Thread(result, "voice-playback-wake").apply { isDaemon = true; start() }
        try {
            try { output!!.speak(text, cancel::get) }
            catch (error: Exception) { if (phase.get() != 1 || cancel.get()) throw error }
        } finally {
            if (phase.compareAndSet(0, 2)) audio.stop()
            // Capture has a bounded read; decoding, when a wake was accepted, finishes before reuse.
            watcher.join()
        }
        return if (phase.get() == 1 && !cancel.get()) result.get().ifBlank { null }
        else { if (!cancel.get()) runCatching { result.get() }.onFailure { failure ->
            if (failure.cause !is java.util.concurrent.CancellationException) throw failure
        }; null }
    }

    private fun failed(current: Long, cancelled: AtomicBoolean, error: Throwable) {
        if (cancelled.get()) publish(current) { it.copy(status = "语音已停止", error = null) }
        else {
            val cause = generateSequence(error) { it.cause }.last()
            // Native/provider exceptions are not echoed; only our correction-oriented messages are shown.
            val message = cause.message?.takeIf { it.any { char -> char.code in 0x4e00..0x9fff } }?.take(240)
                ?: "语音处理失败，请检查本地模型、麦克风及模型服务配置。"
            publish(current) { it.copy(status = "语音暂时不可用", error = message) }
        }
    }

    private fun publish(current: Long, change: (State) -> State) { main.post { if (version == current) update(change(state)) } }
    private fun update(value: State) { state = value; listeners.forEach { it(value) } }
}
