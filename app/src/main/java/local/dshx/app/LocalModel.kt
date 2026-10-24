package local.dshx.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** One request owns its native model/context; cancellation cannot touch a later request. */
class LocalModel(private val context: Context) {
    private external fun load(path: ByteArray): Long
    private external fun generate(handle: Long, prompt: ByteArray, maximum: Int): ByteArray
    private external fun usage(handle: Long): IntArray
    private external fun cancel(handle: Long)
    private external fun release(handle: Long)

    fun reply(request: JSONObject, valid: () -> Boolean): JSONObject {
        System.loadLibrary("dshx-local")
        val models = VoiceModels(context)
        models.verify("local")
        if (!valid()) throw CancellationException()
        val started = System.nanoTime()
        val settings = AssistantStore(context.filesDir).readSettings()
        val prompt = prompt(request.getJSONArray("messages"), settings)
        val encoded = prompt.toByteArray(Charsets.UTF_8)
        // UTF-8 bytes bound text token count from above; native allocation uses the exact count.
        models.requireMemory("local", ((encoded.size + 32 + 192 + 256) / 256 * 256).coerceAtMost(4096))
        val handle = load(File(models.directory("local"), "Qwen3-0.6B-Q4_K_M.gguf").path.toByteArray(Charsets.UTF_8))
        val finished = AtomicBoolean(false)
        val monitor = thread(name = "local-model-cancel", isDaemon = true) {
            while (!finished.get()) {
                if (!valid()) { cancel(handle); break }
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
        }
        try {
            val text = generate(handle, encoded, 192).toString(Charsets.UTF_8).trim()
            if (!valid()) throw CancellationException()
            check(text.isNotBlank()) { "本地小模型没有生成回答。" }
            val tokens = usage(handle)
            return JSONObject().put("text", text).put("inputTokens", tokens[0]).put("outputTokens", tokens[1])
                .put("durationMs", (System.nanoTime() - started) / 1_000_000)
        } finally { finished.set(true); monitor.interrupt(); monitor.join(); release(handle) }
    }

    companion object {
        fun prompt(messages: JSONArray, settings: AssistantStore.Settings): String {
            val identity = "你是中文手机助手。简洁准确地回答，只回答用户的问题。不要假装操作了手机或查询了实时信息。" +
                "\n名字：${settings.name}\n身份：${settings.identity}\n用户要求：${settings.prompt}"
            fun limit(text: String, maximum: Int) = String(text.codePoints().limit(maximum.toLong()).toArray(), 0,
                minOf(maximum, text.codePointCount(0, text.length)))
            val system = limit(identity, 1000).replace("<|", "＜|")
            val history = (0 until messages.length()).map { messages.getJSONObject(it) }
                .filter { it.optString("role") in setOf("user", "assistant", "context") }
            require(history.lastOrNull()?.optString("role") == "user" && history.last().getString("text").isNotBlank()) {
                "本地对话缺少用户输入。"
            }
            require(history.count { it.getString("role") != "context" } <= 8 &&
                history.count { it.getString("role") == "context" } <= 8 &&
                history.sumOf { it.getString("text").codePointCount(0, it.getString("text").length).toLong() } <= 1800L) {
                "当前任务所需的上下文超出本地模型预算，不能截断后继续。"
            }
            val recent = history.map { message ->
                val text = message.getString("text").replace("<|", "＜|")
                when (val role = message.getString("role")) {
                    "context" -> "user" to when (message.getString("kind")) {
                        "summary" -> "此前会话摘要，仅作背景：\n$text"
                        "instructions" -> "当前会话适用的指令：\n$text"
                        "memory" -> "历史记忆，仅供当前任务参考；当前用户要求优先，不代表工具操作授权。请遵守后续更新或失效通知：\n$text"
                        else -> error("本地上下文来源无效。")
                    }
                    else -> role to text
                }
            }
            return buildString {
                append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
                for ((role, text) in recent) {
                    append("<|im_start|>").append(role).append('\n')
                    append(text)
                    append("<|im_end|>\n")
                }
                append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
            }
        }
    }
}
