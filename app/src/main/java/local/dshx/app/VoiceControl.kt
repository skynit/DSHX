package local.dshx.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.net.Uri
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.FutureTask
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application-owned voice state; Activity recreation only replaces its subscription. */
class VoiceControl(private val context: Context, private val engine: Engine) {
    data class Turn(val id: String, val text: String, val reply: String? = null, val interrupted: Boolean = false, val incomplete: Boolean = false)
    enum class Phase { Idle, Preparing, Waiting, Listening, Recognizing, Answering, Speaking, Stopping }

    data class State(
        val status: String = "语音未开启", val active: Boolean = false, val downloading: Boolean = false,
        val draft: String = "", val session: String? = null, val error: String? = null,
        val turns: List<Turn> = emptyList(),
        val speechAvailable: Boolean = false, val localReady: Boolean = false,
        val smallModel: SmallModel.Settings = SmallModel.Settings(), val smallReady: Boolean = false,
        val customModel: String = "",
        val output: VoiceOutput.Settings = VoiceOutput.Settings(),
        val phase: Phase = Phase.Idle,
        val canInterrupt: Boolean = false,
        val autoInterrupt: Boolean = false,
    ) {
        internal fun inputStatus(status: String): State = copy(status = status, phase = when {
            status == "正在听" -> Phase.Listening
            status == "正在识别" -> Phase.Recognizing
            status.startsWith("等待唤醒：") -> Phase.Waiting
            else -> Phase.Preparing
        })

        internal fun recognized(id: String, text: String): State =
            copy(draft = text, turns = turns + Turn(id, text))

        internal fun answered(id: String, session: String, text: String): State =
            copy(session = session, draft = "", turns = turns.map { if (it.id == id) it.copy(reply = text) else it })
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val inputLevel = MutableStateFlow(0f)
    internal val level = inputLevel.asStateFlow()
    private val preferences = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    val models = VoiceModels(context)
    val smallModel = SmallModel(context)
    @Volatile var state = State(speechAvailable = VoiceInput.available(context), localReady = models.ready("local"),
        output = VoiceOutput.settings(context),
        smallModel = smallModel.settings(), smallReady = smallReady(smallModel.settings()), customModel = smallModel.name())
        private set
    private var version = 0L
    val generation get() = version
    private var stopped = AtomicBoolean(true)
    @Volatile private var input: VoiceInput? = null
    @Volatile private var output: VoiceOutput? = null
    @Volatile private var interaction: String? = null
    @Volatile private var interruptReply: (() -> Unit)? = null
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
        get() = preferences.getBoolean("speechInterrupt", true)
        set(value) { preferences.edit().putBoolean("speechInterrupt", value).apply() }
    fun saveOutput(settings: VoiceOutput.Settings) {
        check(!state.active) { "请先停止语音再修改播报服务。" }
        VoiceOutput.save(context, settings)
        update(state.copy(output = VoiceOutput.settings(context), error = null))
    }

    private fun smallReady(settings: SmallModel.Settings): Boolean = when (settings.source) {
        "online" -> settings.provider.isNotBlank() && settings.model.isNotBlank()
        "custom" -> smallModel.ready()
        else -> models.ready("local")
    }

    fun saveSmallModel(settings: SmallModel.Settings) {
        check(!state.active) { "请先停止语音再修改小模型。" }
        smallModel.save(settings)
        val saved = smallModel.settings()
        update(state.copy(smallModel = saved, smallReady = smallReady(saved), error = null))
    }

    fun importModel(uri: Uri, name: String) {
        if (state.active) return
        val current = ++version
        val cancel = AtomicBoolean(false); stopped = cancel
        update(state.copy(active = true, downloading = true, error = null, status = "正在导入本地模型"))
        worker.execute {
            try {
                var last = 0L
                context.contentResolver.openInputStream(uri)?.use { input ->
                    smallModel.install(input, name, cancel::get, { bytes ->
                        val now = System.nanoTime()
                        if (now - last > 250_000_000) {
                            last = now
                            publish(current) { it.copy(status = "正在导入本地模型 · ${bytes / (1024 * 1024)} MiB") }
                        }
                    })
                } ?: error("无法读取所选模型文件。")
                main.post {
                    // 文件已发布后，即使停止与提交同时发生，也要刷新可选模型。
                    val failure = runCatching {
                        if (version == current) smallModel.save(smallModel.settings().copy(source = "custom"))
                    }.exceptionOrNull()
                    val saved = smallModel.settings()
                    update(state.copy(smallModel = saved, smallReady = smallReady(saved), customModel = smallModel.name(),
                        status = if (version == current) "本地模型已导入" else state.status, error = failure?.message))
                }
            } catch (error: Throwable) { failed(current, cancel, error) }
            finally { publish(current) { it.copy(active = false, downloading = false) } }
        }
    }

    fun preview() {
        if (state.active) return
        val current = ++version
        val cancel = AtomicBoolean(false); stopped = cancel
        update(state.copy(active = true, error = null, status = "正在生成试听"))
        worker.execute {
            try {
                VoiceOutput(context).use { audio ->
                    output = audio
                    audio.speak("你好，我是你的语音助手。今天想聊些什么？", cancel::get)
                }
                publish(current) { it.copy(status = "试听已结束") }
            } catch (error: Throwable) { failed(current, cancel, error) }
            finally { output = null; publish(current) { it.copy(active = false) } }
        }
    }

    fun observe(listener: (State) -> Unit) { listeners.add(listener); listener(state) }
    fun remove(listener: (State) -> Unit) { listeners.remove(listener) }
    fun draft(text: String) { if (!state.active && state.draft != text) update(state.copy(draft = text.take(8000))) }
    fun select(session: String?) {
        val clean = session?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) }
        if (state.session == clean) return
        if (state.active) stop()
        update(state.copy(session = clean, draft = "", turns = emptyList(), error = null))
    }

    fun refreshSpeech() {
        val available = VoiceInput.available(context)
        if (available != state.speechAvailable) update(state.copy(speechAvailable = available))
    }

    fun download() {
        if (state.active) return
        val current = ++version
        val cancel = AtomicBoolean(false); stopped = cancel
        update(state.copy(active = true, downloading = true, error = null, status = "正在准备本地模型"))
        worker.execute {
            try {
                var last = 0L
                models.install("local", mirror, cancel::get) { done, total ->
                    val now = System.nanoTime()
                    if (now - last > 250_000_000 || done == total) {
                        last = now
                        publish(current) { it.copy(status = "正在下载本地模型 ${done * 100 / total}%") }
                    }
                }
                publish(current) { it.copy(status = "本地模型已就绪", localReady = models.ready("local"), smallReady = smallReady(it.smallModel)) }
            } catch (error: Throwable) { failed(current, cancel, error) }
            finally { publish(current) { it.copy(active = false, downloading = false) } }
        }
    }

    fun start(mode: String) {
        require(mode in setOf("input", "talk", "send", "wake"))
        if (state.active) return
        refreshSpeech()
        if (mode != "input" && engine.state.url == null) {
            update(state.copy(error = "请先启动 dsh，再使用语音对话。")); return
        }
        if (mode != "send" && !state.speechAvailable) {
            update(state.copy(error = "手机没有可用的系统语音识别服务，请在系统语音设置中安装或启用。")); return
        }
        val text = state.draft.trim()
        if (mode == "send" && text.isEmpty()) return
        val current = ++version
        val cancel = AtomicBoolean(false); stopped = cancel
        var session = state.session
        val wake = wakeWord
        update(state.copy(active = true, error = null, status = "正在准备语音", phase = Phase.Preparing))
        worker.execute {
            val audio = VoiceInput(context, models); input = audio
            try {
                if (mode == "wake") models.install("wake", mirror, cancel::get) { _, _ -> }
                if (mode != "input") {
                    output = VoiceOutput(context) { main.post { stop() } }
                    output!!.prepare(cancel::get)
                }
                var next: String? = if (mode == "send") text else null
                do {
                    val words = next ?: audio.listen(if (mode == "wake") wake else null, cancel::get, allowEmpty = mode == "wake",
                        onLevel = { level -> publishLevel(current, level) }) { status ->
                        publish(current) { if (cancel.get()) it else it.inputStatus(status) }
                    }
                    next = null
                    if (cancel.get()) break
                    if (words.isBlank()) continue
                    val turn = UUID.randomUUID().toString()
                    publish(current) { if (cancel.get()) it else it.recognized(turn, words) }
                    if (mode == "input") {
                        publish(current) { it.copy(status = "已识别，可编辑后发送") }; break
                    }
                    val reply = answer(words, turn, session, audio, cancel, current, mode != "send")
                    session = reply.first ?: session
                    next = reply.second
                    if (cancel.get()) break
                    if (mode == "send") {
                        publish(current) { it.copy(status = if (it.turns.lastOrNull()?.interrupted == true) "回答已打断" else "文字已发送，播报完成") }
                        break
                    }
                    if (next != null) continue
                    publish(current) { it.copy(status = "可以继续说，8 秒无输入后结束", phase = Phase.Preparing) }
                    next = audio.listen(null, cancel::get, timeoutSeconds = 8, allowEmpty = true,
                        onLevel = { level -> publishLevel(current, level) }) { status ->
                        publish(current) { if (cancel.get()) it else it.inputStatus(status) }
                    }.ifBlank { null }
                    if (next == null && mode != "wake") {
                        publish(current) { it.copy(status = "本轮对话完成") }; break
                    }
                } while (!cancel.get())
            } catch (error: Throwable) { failed(current, cancel, error) }
            finally {
                audio.close(); if (input === audio) input = null
                output?.close(); output = null; interaction = null; interruptReply = null
                publish(current) { it.copy(active = false, canInterrupt = false, autoInterrupt = false, phase = Phase.Idle, status = if (cancel.get()) "语音已停止" else it.status) }
            }
        }
    }

    fun stop() {
        stopped.set(true)
        interruptReply?.invoke()
        input?.stop(); output?.stop()
        if (state.active) update(state.copy(status = "正在停止语音", phase = Phase.Stopping))
        val key = interaction
        if (key != null) kotlin.concurrent.thread(name = "voice-cancel", isDaemon = true) {
            runCatching { engine.voice("cancel", JSONObject().put("interaction", key)) }
        }
    }

    fun interruptAnswer() { interruptReply?.invoke() }

    private fun answer(words: String, turn: String, session: String?, input: VoiceInput,
                       cancel: AtomicBoolean, current: Long, listen: Boolean): Pair<String?, String?> {
        val key = UUID.randomUUID().toString()
        interaction = key
        val cut = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val selected = AtomicReference(session)
        val monitorFailure = AtomicReference<Throwable?>(null)
        val responseFailure = AtomicReference<Throwable?>(null)
        val text = SpeechText(16_000)
        val audio = checkNotNull(output)
        val request = JSONObject().put("text", words).put("interaction", key).put("local", local)
        if (local) request.put("smallModel", state.smallModel.route())
        if (session != null) request.put("session", session)
        val response = FutureTask {
            if (cancel.get() || cut.get()) throw CancellationException()
            try {
                engine.voice("prompt", request) { event ->
                    if (!cancel.get() && !cut.get()) {
                        val target = event.getString("session")
                        selected.set(target)
                        val reply = text.add(event.getString("text"))
                        publish(current) { if (cancel.get() || cut.get()) it else it.answered(turn, target, reply) }
                    }
                }
            } catch (error: Throwable) {
                if (!cut.get() && !cancel.get()) { responseFailure.set(error); audio.stop() }
                throw error
            }
        }
        val generating = Thread(response, "voice-answer").apply { isDaemon = true }
        val interruptNow: () -> Unit = {
            if (!finished.get() && cut.compareAndSet(false, true)) {
                audio.stop()
                response.cancel(true)
                publish(current) { state ->
                    if (cancel.get()) state else state.copy(status = "正在听", phase = Phase.Listening, canInterrupt = false, autoInterrupt = false,
                        turns = state.turns.map { if (it.id == turn) it.copy(interrupted = true, reply = it.reply ?: "") else it })
                }
            }
        }
        interruptReply = interruptNow
        publish(current) { it.copy(status = "正在回答", phase = Phase.Answering, canInterrupt = true, autoInterrupt = listen && interrupt) }
        val monitor = if (listen && interrupt) FutureTask<String?> {
            try {
                while (!cancel.get() && (!finished.get() || cut.get())) {
                    val next = input.listen(null, { cancel.get() || finished.get() && !cut.get() },
                        timeoutSeconds = 8, allowEmpty = true, onSpeech = interruptNow, echoText = { audio.speakingText },
                        onLevel = { level -> if (cut.get()) publishLevel(current, level) }) { status ->
                        if (cut.get()) publish(current) { if (cancel.get()) it else it.inputStatus(status) }
                    }
                    if (next.isNotBlank()) { interruptNow(); return@FutureTask next }
                    if (cut.get()) return@FutureTask null
                    Thread.sleep(300)
                }
                null
            } catch (_: CancellationException) { null }
            catch (error: Exception) {
                if (!finished.get() && !cancel.get()) {
                    monitorFailure.set(error)
                    publish(current) { it.copy(autoInterrupt = false) }
                }
                null
            }
        } else null
        val listening = monitor?.let { Thread(it, "voice-interrupt").apply { isDaemon = true; start() } }
        generating.start()
        try {
            var completed = false
            var lastChunk = System.nanoTime()
            while (!cancel.get() && !cut.get()) {
                responseFailure.get()?.let { throw it }
                if (!completed && response.isDone) {
                    val reply = response.get()
                    val target = reply.getString("session")
                    selected.set(target)
                    val answer = reply.getString("text")
                    text.finish(reply.optString("streamedText", answer))
                    completed = true
                    publish(current) { if (cancel.get() || cut.get()) it else it.answered(turn, target, answer) }
                }
                val chunk = text.take(completed || System.nanoTime() - lastChunk >= 600_000_000L)
                if (chunk != null) {
                    audio.speak(chunk, { cancel.get() || cut.get() || responseFailure.get() != null }) {
                        publish(current) { if (cancel.get() || cut.get()) it else it.copy(status = "正在播报", phase = Phase.Speaking) }
                    }
                    lastChunk = System.nanoTime()
                } else if (completed) break
                else {
                    Thread.sleep(40)
                }
            }
            if (cut.get() && !cancel.get()) {
                // 保留已打开的识别器，等待同一次收音的最终结果，避免插话开头丢失。
                val next = if (monitorFailure.get() == null) monitor?.get(65, TimeUnit.SECONDS) else null
                return selected.get() to next
            }
            return selected.get() to null
        } catch (error: ExecutionException) {
            if (!cut.get() && !cancel.get()) {
                publish(current) { state -> state.copy(turns = state.turns.map { if (it.id == turn) it.copy(incomplete = true) else it }) }
                throw (responseFailure.get() ?: error.cause ?: error)
            }
            return selected.get() to if (!cancel.get() && monitorFailure.get() == null) monitor?.get(65, TimeUnit.SECONDS) else null
        } catch (error: CancellationException) {
            if (!cut.get() && !cancel.get()) {
                publish(current) { state -> state.copy(turns = state.turns.map { if (it.id == turn) it.copy(incomplete = true) else it }) }
                throw (responseFailure.get() ?: error)
            }
            return selected.get() to if (!cancel.get() && monitorFailure.get() == null) monitor?.get(65, TimeUnit.SECONDS) else null
        } catch (error: Throwable) {
            if (!cut.get() && !cancel.get()) publish(current) { state ->
                state.copy(turns = state.turns.map { if (it.id == turn) it.copy(incomplete = true) else it })
            }
            throw error
        } finally {
            finished.set(true)
            response.cancel(true)
            input.stop()
            audio.stop()
            listening?.join(6_000)
            generating.join(3_000)
            interruptReply = null
            publish(current) { it.copy(canInterrupt = false, autoInterrupt = false) }
            check(listening?.isAlive != true && !generating.isAlive) { "语音任务停止超时，请稍后重试。" }
        }
    }

    /** 每轮独立的文字缓冲；只取完整分句，代码块与链接地址不进入朗读。 */
    internal class SpeechText(private val maximum: Int = 4000) {
        private val full = StringBuilder()
        private val source = StringBuilder()
        private val speech = StringBuilder()
        private var code = false
        private var link = false
        private var ended = false

        @Synchronized fun add(delta: String): String {
            check(!ended) { "本轮回答已经结束。" }
            val remaining = maximum - full.codePointCount(0, full.length)
            val count = delta.codePointCount(0, delta.length).coerceAtMost(remaining)
            val next = delta.substring(0, delta.offsetByCodePoints(0, count))
            full.append(next)
            source.append(next)
            clean(false)
            return full.toString()
        }

        @Synchronized fun finish(answer: String) {
            if (answer.startsWith(full.toString())) add(answer.substring(full.length))
            // 某些引擎会在最终消息中规范化文本；已朗读内容不会重放。
            ended = true
            clean(true)
        }

        private fun clean(final: Boolean) {
            var at = 0
            while (at < source.length) {
                val char = source[at]
                val rest = source.length - at
                if (!final && (char == '`' && rest < 3 || char == ']' && rest < 2 || Character.isHighSurrogate(char) && rest == 1)) break
                if (source.startsWith("```", at)) {
                    if (!code) speech.append("，代码请查看聊天界面。")
                    code = !code; at += 3; continue
                }
                if (code) { at++; continue }
                if (link) { if (char == ')') link = false; at++; continue }
                if (source.startsWith("](", at)) { link = true; at += 2; continue }
                if (char != '[' && char != ']' && char != '`') speech.append(char)
                at++
            }
            source.delete(0, at)
        }

        @Synchronized fun take(flush: Boolean = false): String? {
            if (speech.isEmpty()) return null
            var at = 0
            var points = 0
            var boundary = 0
            while (at < speech.length) {
                val point = Character.codePointAt(speech, at)
                at += Character.charCount(point)
                points++
                if (point.toChar() in "。！？!?；;\n" || points >= 16 && point.toChar() in "，,：:" || points >= 120) {
                    boundary = at; break
                }
            }
            if (boundary == 0 && (ended || flush && points >= 12)) boundary = speech.length
            if (boundary == 0) return null
            val raw = speech.substring(0, boundary)
            speech.delete(0, boundary)
            val clean = VoiceOutput.chunks(raw).joinToString(" ")
            return if (clean.isBlank()) take(flush) else clean
        }
    }

    private fun failed(current: Long, cancelled: AtomicBoolean, error: Throwable) {
        if (cancelled.get()) publish(current) { it.copy(status = "语音已停止", error = null, phase = Phase.Idle) }
        else {
            val cause = generateSequence(error) { it.cause }.last()
            // Native/provider exceptions are not echoed; only our correction-oriented messages are shown.
            val message = cause.message?.takeIf { it.any { char -> char.code in 0x4e00..0x9fff } }?.take(240)
                ?: "语音处理失败，请检查网络、麦克风及语音或模型服务配置。"
            publish(current) { it.copy(status = "语音暂时不可用", error = message, phase = Phase.Idle) }
        }
    }

    private fun publish(current: Long, change: (State) -> State) { main.post { if (version == current) update(change(state)) } }
    private fun publishLevel(current: Long, level: Float) { main.post {
        if (version == current && !stopped.get() && state.active && state.phase in setOf(Phase.Listening, Phase.Waiting)) {
            inputLevel.value = level
        }
    } }
    private fun update(value: State) {
        if (!value.active || value.phase !in setOf(Phase.Listening, Phase.Waiting)) inputLevel.value = 0f
        state = if (value.active) value else value.copy(phase = Phase.Idle)
        listeners.forEach { it(state) }
    }
}
