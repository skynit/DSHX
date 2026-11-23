package local.dshx.app

import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val CHUNK = 12 * 1024
private const val MAX_UPLOAD = 128L * 1024 * 1024
private const val VISITOR_ORIGIN = "https://dshx-web.invalid"
private val token = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
private val methodWithoutBody = setOf("GET", "HEAD", "DELETE", "OPTIONS", "PROPFIND", "TRACE")
private val denied = setOf("connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
    "transfer-encoding", "upgrade", "host", "content-length", "accept-encoding", "cookie", "set-cookie", "origin", "referer", "forwarded")

internal class WebShareProxy(
    private val shares: WebShares,
    private val send: (JSONObject) -> Boolean,
    private val executor: ExecutorService,
) : AutoCloseable {
    private val requests = java.util.concurrent.ConcurrentHashMap<String, Transfer>()
    private val clients = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()
    private val closed = AtomicBoolean()
    private val listed = AtomicBoolean()
    private val guard = Any()
    private val visited = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun uses(share: String) = share in visited
    fun valid() = visited.all { shares.get(it) != null }

    fun receive(frame: JSONObject) {
        if (closed.get()) return
        if (frame.optString("type") == "list") {
            if (listed.compareAndSet(false, true)) executor.execute(::list)
            return
        }
        val id = frame.optString("id").takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: return
        try {
            when (frame.optString("type")) {
                "open", "socket" -> open(id, frame)
                "ack" -> requests[id]?.credit?.offer(Unit)
                "cancel" -> requests.remove(id)?.close()
                "upload", "upload-done" -> requests[id]?.let { request ->
                    require(request.uploading) { "上传未开始。" }
                    val bytes = if (frame.optString("type") == "upload-done") ByteArray(0) else decode(frame.getString("bytes"))
                    if (!request.upload.offer(bytes)) fail(id, "上传窗口已满。")
                }
                "socket-send" -> requests[id]?.writeSocket(frame)
                "socket-close" -> requests[id]?.closeSocket(frame)
            }
        } catch (_: Exception) { fail(id, "网页请求不受支持或服务不可用。") }
    }

    private fun list() {
        for (item in shares.publicList()) {
            if (closed.get() || !send(JSONObject().put("type", "list").put("done", false).put("items", JSONArray().put(item.publicInfo())))) return
        }
        if (!closed.get()) send(JSONObject().put("type", "list").put("done", true).put("items", JSONArray()))
    }

    private fun open(id: String, frame: JSONObject) {
        if (requests.containsKey(id)) return
        val item = checkNotNull(shares.get(frame.getString("share"))) { "共享已关闭。" }
        val path = frame.getString("path")
        require(webPath(path))
        val target = checkNotNull(item.url.resolve(path))
        require(sameTarget(item.url, target) && webPath(target.encodedPath))
        val socket = frame.getString("type") == "socket"
        val method = if (socket) "GET" else frame.getString("method")
        require(token.matches(method) && method.uppercase() !in setOf("CONNECT", "TRACE"))
        val uploading = !socket && frame.optBoolean("upload")
        val transfer = Transfer(id, item, target, uploading)
        val request = Request.Builder().url(target).apply {
            val headers = frame.optJSONArray("headers") ?: JSONArray()
            require(headers.length() <= 80 && headers.toString().length <= 16 * 1024)
            val skip = connectionTokens(headers)
            for (index in 0 until headers.length()) {
                val pair = headers.getJSONArray(index)
                val name = pair.getString(0)
                val normalized = name.lowercase()
                if (normalized == "origin") header("Origin", VISITOR_ORIGIN)
                else if (normalized !in denied && normalized !in skip &&
                    !normalized.startsWith("sec-") && !normalized.startsWith("x-forwarded-")) addHeader(name, pair.getString(1))
            }
            if (socket) {
                val protocols = frame.optJSONArray("protocols") ?: JSONArray()
                require(protocols.length() <= 16)
                val values = (0 until protocols.length()).map { protocols.getString(it).also { name -> require(token.matches(name)) } }
                if (values.isNotEmpty()) header("Sec-WebSocket-Protocol", values.joinToString(", "))
                header("Origin", VISITOR_ORIGIN)
            }
            method(method, if (uploading || method !in methodWithoutBody) transfer.body() else null)
        }.build()
        synchronized(guard) {
            check(!closed.get() && requests.size < 16)
            val client = clients.computeIfAbsent(item.id) {
                val network = shares.currentNetwork(item)
                check(item.local || network != null)
                OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                    .retryOnConnectionFailure(false).cookieJar(ShareCookies()).readTimeout(120, TimeUnit.SECONDS).apply {
                        network?.let { socketFactory(it.socketFactory) }
                    }.build()
            }
            requests[id] = transfer
            visited += item.id
            if (socket) transfer.socket = client.newWebSocket(request, transfer.listener())
            else {
                transfer.call = client.newCall(request)
                executor.execute { transfer.runHttp(method) }
            }
        }
    }

    private fun fail(id: String, message: String) {
        requests.remove(id)?.close()
        send(JSONObject().put("type", "error").put("id", id).put("message", message))
    }

    override fun close() = synchronized(guard) {
        if (!closed.compareAndSet(false, true)) return@synchronized
        requests.values.forEach(Transfer::close)
        requests.clear()
        clients.values.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll() }
        clients.clear()
    }

    private inner class Transfer(val id: String, val share: WebShare, val target: HttpUrl, val uploading: Boolean) {
        val upload = ArrayBlockingQueue<ByteArray>(2)
        val credit = ArrayBlockingQueue<Unit>(1)
        private val stopped = AtomicBoolean()
        @Volatile var socket: WebSocket? = null
        @Volatile var call: Call? = null

        private fun frame(type: String, fill: JSONObject.() -> Unit = {}) {
            if (stopped.get() || shares.get(share.id) != share ||
                !send(JSONObject().put("type", type).put("id", id).apply(fill))) throw IOException("网页共享已关闭。")
        }

        fun body() = object : RequestBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun isOneShot() = true
            override fun contentLength() = if (uploading) -1L else 0L
            override fun writeTo(sink: BufferedSink) {
                if (!uploading) return
                frame("upload-ready")
                var size = 0L
                while (!stopped.get()) {
                    val bytes = upload.poll(120, TimeUnit.SECONDS) ?: throw IOException("上传超时。")
                    if (bytes.isEmpty()) break
                    size += bytes.size
                    if (size > MAX_UPLOAD) throw IOException("上传超过 128 MiB。")
                    sink.write(bytes)
                    sink.flush()
                    frame("upload-ack")
                }
                if (stopped.get()) throw IOException("上传已取消。")
            }
        }

        fun runHttp(method: String) {
            try {
                val connection = checkNotNull(call)
                connection.execute().use { response ->
                    val headers = JSONArray()
                    val skip = response.headers.values("Connection").flatMap { it.split(',') }.map { it.trim().lowercase() }
                    for ((name, value) in response.headers) {
                        val normalized = name.lowercase()
                        if (normalized in denied || normalized in skip) continue
                        headers.put(JSONArray().put(name).put(if (normalized == "location") redirect(value) else value))
                    }
                    require(headers.length() <= 80 && headers.toString().length <= 16 * 1024)
                    frame("headers") { put("status", response.code).put("headers", headers) }
                    if (method != "HEAD" && response.code !in setOf(204, 205, 304)) response.body?.byteStream()?.use { input ->
                        val buffer = ByteArray(CHUNK)
                        while (!stopped.get()) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            frame("chunk") { put("bytes", encode(buffer.copyOf(size))) }
                            if (credit.poll(120, TimeUnit.SECONDS) == null) throw IOException("网页读取超时。")
                        }
                    }
                    frame("done")
                }
            } catch (_: Exception) {
                if (!stopped.get()) fail(id, "应用服务不可用或请求已结束。")
            } finally {
                requests.remove(id, this)
                close()
            }
        }

        private fun redirect(value: String): String {
            val url = checkNotNull(target.resolve(value))
            val location = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
            require(sameTarget(share.url, url) && webPath(location))
            return location + (url.encodedFragment?.let { "#$it" } ?: "")
        }

        fun listener() = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = socketEvent("socket-ready") {
                put("protocol", response.header("Sec-WebSocket-Protocol", ""))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray().size > CHUNK) return fail(id, "WebSocket 消息过大。")
                message { put("text", text) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > CHUNK) return fail(id, "WebSocket 消息过大。")
                message { put("bytes", encode(bytes.toByteArray())) }
            }
            private fun message(fill: JSONObject.() -> Unit) {
                socketEvent("socket-message", fill)
                if (credit.poll(120, TimeUnit.SECONDS) == null && !stopped.get()) fail(id, "WebSocket 接收超时。")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketEvent("socket-close") { put("code", code).put("reason", reason) }
                requests.remove(id, this@Transfer)
                close()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                if (!stopped.get()) fail(id, "WebSocket 服务不可用。")
            }
        }

        private fun socketEvent(type: String, fill: JSONObject.() -> Unit) {
            runCatching { frame(type, fill) }.onFailure { close() }
        }

        fun writeSocket(frame: JSONObject) {
            val ws = socket ?: return
            if (ws.queueSize() > 128 * 1024) return fail(id, "WebSocket 发送缓冲已满。")
            val size = if (frame.has("bytes")) decode(frame.getString("bytes")).let {
                check(ws.send(it.toByteString()))
                it.size
            } else frame.getString("text").let {
                val count = it.toByteArray().size
                require(count <= CHUNK)
                check(ws.send(it))
                count
            }
            socketEvent("socket-sent") { put("size", size) }
        }

        fun closeSocket(frame: JSONObject) {
            val ws = socket ?: return
            val code = frame.optInt("code", 1000)
            require(code == 1000 || code in 3000..4999)
            ws.close(code, frame.optString("reason").take(100))
        }

        fun close() {
            if (!stopped.compareAndSet(false, true)) return
            call?.cancel()
            socket?.cancel()
            upload.clear()
            upload.offer(ByteArray(0))
            credit.offer(Unit)
        }
    }
}

private class ShareCookies : CookieJar {
    private val cookies = linkedMapOf<Triple<String, String, String>, Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) if (cookie.domain == url.host) {
            val key = Triple(cookie.name, cookie.path, cookie.domain)
            if (cookie.expiresAt <= System.currentTimeMillis()) this.cookies.remove(key) else this.cookies[key] = cookie
        }
        while (this.cookies.size > 128) this.cookies.remove(this.cookies.keys.first())
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.filter {
        it.expiresAt > System.currentTimeMillis() && it.matches(url)
    }.sortedByDescending { it.path.length }
}

private fun connectionTokens(headers: JSONArray): Set<String> = (0 until headers.length()).flatMap { index ->
    val header = headers.getJSONArray(index)
    if (header.getString(0).equals("connection", true)) header.getString(1).split(',') else emptyList()
}.map { it.trim().lowercase() }.toSet()

private fun sameTarget(first: HttpUrl, second: HttpUrl) = first.scheme == second.scheme && first.host == second.host &&
    first.port == second.port && second.username.isEmpty() && second.password.isEmpty()
private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
private fun decode(value: String) = Base64.getDecoder().decode(value).also { require(it.size <= CHUNK) }
