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
                .filter { it.optString("role") in setOf("user", "assistant") }.takeLast(8)
            require(history.isNotEmpty()) { "本地对话缺少用户输入。" }
            var remaining = 1800
            val recent = history.asReversed().mapNotNull { message ->
                if (remaining <= 0) null else {
                    val text = limit(message.getString("text"), minOf(600, remaining)).replace("<|", "＜|")
                    remaining -= text.codePointCount(0, text.length)
                    message.getString("role") to text
                }
            }.asReversed()
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
