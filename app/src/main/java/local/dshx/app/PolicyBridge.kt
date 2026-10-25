package local.dshx.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** One authenticated connection for live sessions and cancellable native phone calls. */
class PolicyBridge(
    private val policy: ToolPolicy, val instance: String,
    private val phone: ((String, JSONObject, String, () -> Boolean) -> JSONObject)? = null,
    private val phoneFinished: ((String) -> Unit)? = null,
    private val model: ((JSONObject, () -> Boolean) -> JSONObject)? = null,
    private val smallModel: (() -> JSONObject)? = null,
    private val lost: () -> Unit,
) : Closeable {
    val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(4)
    @Volatile private var closed = false
    @Volatile private var client: Socket? = null
    private data class Call(val id: Long, val session: String, val cancelled: AtomicBoolean = AtomicBoolean())
    @Volatile private var phoneCall: Call? = null
    @Volatile private var modelCall: Call? = null
    private val nativeCalls = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()

    /** Called off the main thread; replies share the existing authenticated connection. */
    fun voice(action: String, data: JSONObject = JSONObject()): JSONObject = request("voice", action, data, 180)

    fun memory(action: String, data: JSONObject = JSONObject()): JSONObject = request("memory", action, data, 60)

    /** 中断和超时只释放等待；业务取消必须显式调用 cancel 并提供任务编号。 */
    fun knowledge(action: String, data: JSONObject = JSONObject()): JSONObject {
        KnowledgeStore.request(action, data)
        return request("knowledge", action, data, 30).also { result ->
            when (action) {
                "status" -> KnowledgeStore.status(result)
                "list" -> KnowledgeStore.catalog(result)
                "read" -> if (data.has("ref")) KnowledgeStore.reference(result) else KnowledgeStore.page(result)
                "search" -> KnowledgeStore.search(result)
                else -> KnowledgeStore.accepted(result, action, data)
            }
        }
    }

    private fun request(channel: String, action: String, data: JSONObject, timeout: Long): JSONObject {
        val socket = client ?: throw IllegalStateException("引擎尚未连接，请先启动 dsh。")
        check(!closed) { "引擎连接已停止。" }
        val id = UUID.randomUUID().toString()
        val key = "$channel:$id"
        val result = CompletableFuture<JSONObject>()
        nativeCalls[key] = result
        var cancel = false
        try {
            check(client === socket && !closed) { "引擎连接已停止。" }
            send(socket, JSONObject().put(channel, id).put("action", action).put("data", data))
            val response = result.get(timeout, TimeUnit.SECONDS)
            if (channel == "knowledge" && !response.getBoolean("ok"))
                throw KnowledgeStore.Error(response.getString("code"), KnowledgeStore.error(response.getString("code")))
            check(response.optBoolean("ok")) { response.optString("error", "请求失败，请重试。") }
            return response.getJSONObject("result")
        } catch (error: InterruptedException) {
            cancel = true
            throw error
        } catch (error: java.util.concurrent.TimeoutException) {
            cancel = true
            throw IllegalStateException(if (channel == "knowledge") "等待知识回执超时；任务可能已受理，请用原操作编号核实结果。"
                else "请求等待超时，请重新读取长期记忆或语音状态确认实际结果。", error)
        } finally {
            nativeCalls.remove(key)
            result.cancel(false)
            if (cancel && channel == "memory" && !closed && client === socket) runCatching {
                send(socket, JSONObject().put("memory", id).put("action", "cancel").put("data", JSONObject().put("id", id)))
            }
        }
    }

    fun start() {
        thread(name = "dsh-policy-listener", isDaemon = true) {
            while (!closed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (!slots.tryAcquire()) { socket.close(); continue }
                val open = synchronized(this) { if (closed) false else { sockets.add(socket); true } }
                if (!open) { socket.close(); slots.release(); continue }
                thread(name = "dsh-policy-query", isDaemon = true) {
                    try { serve(socket) } finally { sockets.remove(socket); runCatching { socket.close() }; slots.release() }
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        var accepted = false
        try {
            socket.soTimeout = 3_000
            val input = socket.getInputStream()
            val deadline = System.nanoTime() + 3_000_000_000L
            val hello = JSONObject(line(input) {
                val remaining = deadline - System.nanoTime()
                require(remaining > 0) { "握手超时" }
                socket.soTimeout = (remaining / 1_000_000L).coerceAtLeast(1).toInt()
            } ?: return)
            if (hello.keys().asSequence().toSet() != setOf("version", "instance", "token") || hello.get("version") != 1 ||
                hello.optString("instance") != instance || !MessageDigest.isEqual(
                    token.toByteArray(Charsets.UTF_8), hello.optString("token").toByteArray(Charsets.UTF_8))) return
            synchronized(this) {
                if (closed || client != null) return
                policy.connected(instance)
                client = socket
                accepted = true
            }
            socket.soTimeout = 0
            send(socket, JSONObject().put("id", 0).put("ok", true).put("state", snapshot()))
            while (!closed) {
                val frame = line(input, limit = MemoryStore.MAX_BYTES + 65_536) ?: break
                val request = JSONObject(frame)
                if (request.has("knowledge")) {
                    require(frame.toByteArray(Charsets.UTF_8).size <= KnowledgeStore.MAX_FRAME) { "知识帧超过 256 KiB。" }
                    KnowledgeStore.response(request)
                    nativeCalls["knowledge:${request.getString("knowledge")}"]?.complete(request)
                    continue
                }
                if (request.has("voice") || request.has("memory")) {
                    val channel = if (request.has("memory")) "memory" else "voice"
                    require(request.keys().asSequence().all { it in setOf(channel, "ok", "result", "error") })
                    require(request.get("ok") is Boolean)
                    nativeCalls["$channel:${request.getString(channel)}"]?.complete(request)
                    continue
                }
                val id = request.get("id")
                require(id is Number && id.toDouble() == id.toLong().toDouble() && id.toLong() in 1..9_007_199_254_740_991L)
                val response = JSONObject().put("id", id)
                try {
                    val method = request.getString("method")
                    val allowed = when (method) {
                        "register" -> setOf("id", "method", "session", "title", "parent")
                        "phone" -> setOf("id", "method", "session", "action", "args")
                        "phone-cancel" -> setOf("id", "method", "session", "call")
                        "local-model" -> setOf("id", "method", "session", "messages", "model")
                        "local-cancel" -> setOf("id", "method", "session", "call")
                        else -> setOf("id", "method", "session")
                    }
                    require(request.keys().asSequence().all { it in allowed }) { "查询不接受额外字段" }
                    val session = request.optString("session")
                    if (method == "local-model") {
                        policy.mode(instance, session)
                        val owner = policy.state.sessions.first { it.id == session }
                        val handler = requireNotNull(model) { "本地模型未配置。" }
                        val call = Call(id.toLong(), session)
                        synchronized(this) { require(modelCall == null) { "本地模型正在处理上一轮。" }; modelCall = call }
                        thread(name = "dsh-local-model", isDaemon = true) {
                            val result = JSONObject().put("id", id)
                            try {
                                val value = handler(request) { !closed && !call.cancelled.get() && policy.state.sessions.any { it === owner } }
                                check(!closed && !call.cancelled.get() && policy.state.sessions.any { it === owner })
                                result.put("ok", true).put("result", value)
                            } catch (error: Throwable) {
                                android.util.Log.w("DSHXVoice", "Local model failed", error)
                                result.put("ok", false).put("error", if (error.message == "当前可用内存不足，本地模型暂不加载。请关闭其他占用内存的应用后重试。")
                                    error.message else "本地模型未就绪、已取消或处理失败，请检查模型文件和设备内存。")
                            }
                            finally { synchronized(this) { if (modelCall === call) modelCall = null } }
                            runCatching { send(socket, result) }
                        }
                        continue
                    }
                    if (method == "phone") {
                        policy.mode(instance, session)
                        val owner = policy.state.sessions.first { it.id == session }
                        val handler = requireNotNull(phone) { "手机功能桥未配置" }
                        val action = request.getString("action")
                        val args = request.getJSONObject("args")
                        val call = Call(id.toLong(), session)
                        synchronized(this) {
                            require(phoneCall == null) { "已有手机操作正在执行，请等待回执后重新观察界面" }
                            phoneCall = call
                        }
                        thread(name = "dsh-phone-call", isDaemon = true) {
                            val result = JSONObject().put("id", id)
                            try {
                                val value = handler(action, args, session) {
                                    !closed && !call.cancelled.get() &&
                                        policy.state.sessions.any { it === owner } &&
                                        runCatching { policy.mode(instance, session) == ToolPolicy.FULL }.getOrDefault(false)
                                }
                                require(!closed && !call.cancelled.get()) { "手机操作已取消，实际界面结果需重新观察" }
                                result.put("ok", true).put("result", value)
                            } catch (error: Exception) {
                                result.put("ok", false).put("error", (error.message?.take(180) ?: "手机操作失败，请重新观察界面") +
                                    if (action == "export") "；导出可能已开始，授权目录可能残留不完整文件，请检查。" else "")
                            } finally {
                                synchronized(this) { if (phoneCall === call) phoneCall = null }
                            }
                            runCatching { send(socket, result) }
                        }
                        continue
                    }
                    when (method) {
                        "register" -> policy.register(instance, session, request.getString("title"), if (request.isNull("parent")) null else request.getString("parent"))
                        "mode" -> policy.mode(instance, session)
                        "small-model" -> { policy.mode(instance, session); response.put("result", requireNotNull(smallModel).invoke()) }
                        "end" -> policy.end(instance, session)
                        "phone-finish" -> { policy.mode(instance, session); phoneFinished?.invoke(session) }
                        "phone-cancel" -> {
                            val call = request.getLong("call")
                            phoneCall?.takeIf { it.id == call && it.session == session }?.cancelled?.set(true)
                        }
                        "local-cancel" -> {
                            modelCall?.takeIf { it.id == request.getLong("call") && it.session == session }?.cancelled?.set(true)
                        }
                        else -> throw IllegalArgumentException("仅支持登记、查询和结束会话")
                    }
                    response.put("ok", true)
                } catch (_: Exception) {
                    response.put("ok", false).put("error", "会话查询无效或不支持；不能通过连接修改权限。")
                }
                send(socket, response)
            }
        } catch (_: Exception) {
            // No authentication material or raw request is written to diagnostics.
        } finally {
            if (accepted) {
                val notify = synchronized(this) {
                    if (!closed && client === socket) { closed = true; client = null; true } else false
                }
                if (notify) { release(); lost() }
            }
        }
    }

    private fun snapshot(): JSONObject {
        val state = policy.state
        return JSONObject().put("instance", state.instance).put("revision", state.revision).put("connected", state.connected)
            .put("sessions", JSONArray(state.sessions.map { row ->
                JSONObject().put("id", row.id).put("title", row.title).put("root", row.root).put("mode", row.mode)
            }))
    }

    private fun send(socket: Socket, value: JSONObject) = synchronized(socket) {
        if (value.optBoolean("ok")) value.put("state", snapshot())
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        if (value.has("knowledge")) require(bytes.size <= KnowledgeStore.MAX_FRAME) { "知识消息超过 256 KiB。" }
        socket.getOutputStream().apply { write(bytes); write(10); flush() }
    }

    override fun close() {
        synchronized(this) { closed = true; client = null }
        release()
    }

    private fun release() {
        nativeCalls.values.forEach { it.completeExceptionally(IllegalStateException("引擎连接已停止。")) }
        nativeCalls.clear()
        phoneCall?.cancelled?.set(true)
        modelCall?.cancelled?.set(true)
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        policy.disconnect(instance)
    }

    companion object {
        private fun line(input: InputStream, limit: Int = 32_768, beforeRead: (() -> Unit)? = null): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() <= limit) {
                beforeRead?.invoke()
                val value = input.read()
                if (value == -1) { require(bytes.size() == 0); return null }
                if (value == 10) {
                    require(bytes.size() <= limit) { "查询消息过长" }
                    return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
                }
                bytes.write(value)
            }
            throw IllegalArgumentException("查询消息过长")
        }
    }
}
