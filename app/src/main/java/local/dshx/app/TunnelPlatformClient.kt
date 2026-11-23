package local.dshx.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

data class TunnelIceServer(val urls: List<String>, val username: String? = null, val credential: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("urls", JSONArray(urls))
        .put("username", username ?: JSONObject.NULL).put("credential", credential ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject): TunnelIceServer {
            val urls = when (val raw = value.opt("urls")) {
                is String -> listOf(raw)
                is JSONArray -> (0 until raw.length()).map { raw.getString(it) }
                else -> emptyList()
            }.filter { it.matches(Regex("(?i)(stun|turn)s?:[^\\s]+")) }
            require(urls.isNotEmpty()) { "ICE 服务器地址无效。" }
            val username = value.optString("username").takeIf { it.isNotBlank() }
            val credential = value.optString("credential").takeIf { it.isNotBlank() }
            require((username == null) == (credential == null)) { "ICE 服务器凭据不完整。" }
            return TunnelIceServer(urls, username, credential)
        }
    }
}

data class TunnelPlatformRegistration(
    val endpoint: TunnelEndpointDescriptor,
    val signalingUrl: String,
    val registrationToken: String?,
    val connectionId: String?,
    val iceServers: List<TunnelIceServer>,
    val webAvailable: Boolean = false,
)

class TunnelPlatformException(val code: String, message: String, val retryable: Boolean = false) : Exception(message)

/** 用端点密钥签署平台注册挑战；端点 ID、目录显示名称和 route_id 不是身份凭证。 */
interface TunnelEndpointIdentity {
    val endpointId: String
    val publicKey: ByteArray
    fun sign(value: ByteArray): ByteArray

    fun publicKeyBase64(): String = Base64.getEncoder().withoutPadding().encodeToString(publicKey)
}

/** Android Keystore 中保存端点私钥，应用数据备份不会复制私钥。 */
class AndroidTunnelEndpointIdentity : TunnelEndpointIdentity {
    private val alias = "dshx.tunnel-chat.endpoint"
    private val entry by lazy { loadEntry() }

    private fun loadEntry(): KeyStore.PrivateKeyEntry {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .build())
                generateKeyPair()
            }
        }
        return store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }

    override val publicKey: ByteArray get() = entry.certificate.publicKey.encoded
    override val endpointId: String by lazy { tunnelEndpointId(publicKey) }

    override fun sign(value: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(entry.privateKey)
        update(value)
        sign()
    }
}

internal fun tunnelEndpointId(key: ByteArray): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(key)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long).toString()
}

/** HTTP token 只携带公钥和签名；认证参数由双方从信令和当前 QUIC 连接独立取得。 */
internal class TunnelPeerToken(
    private val identity: TunnelEndpointIdentity,
    private val remoteId: String,
    private val sessionId: String,
    private val conversationId: String,
    private val initiator: Boolean,
) {
    fun sign(binding: ByteArray): String {
        require(binding.size == 32)
        return encode(identity.publicKey) + "." + encode(identity.sign(transcript(binding, initiator)))
    }

    fun verify(binding: ByteArray, token: String): Boolean = runCatching {
        if (binding.size != 32 || token.length !in 1..291) return false
        val parts = token.split('.', limit = 3)
        if (parts.size != 2 || parts[0].length !in 1..180 || parts[1].length !in 1..110) return false
        val key = Base64.getUrlDecoder().decode(parts[0])
        if (tunnelEndpointId(key) != remoteId) return false
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key)) as? java.security.interfaces.ECPublicKey
            ?: return false
        if (publicKey.params?.curve?.field?.fieldSize != 256) return false
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(transcript(binding, !initiator))
            verify(Base64.getUrlDecoder().decode(parts[1]))
        }
    }.getOrDefault(false)

    private fun transcript(binding: ByteArray, signerInitiator: Boolean): ByteArray {
        val (client, server) = if (initiator) identity.endpointId to remoteId else remoteId to identity.endpointId
        return JSONArray(listOf("dshx-h3/1 token", sessionId, conversationId, client, server, signerInitiator, encode(binding)))
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
}

/**
 * 公共端点目录 HTTP 客户端。所有响应都在边界解析，目录内容只用于展示和寻址，
 * 不被当成端点身份或聊天授权。
 */
class TunnelPlatformClient(
    origin: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) {
    private val origin: HttpUrl = origin.trim().removeSuffix("/").toHttpUrl().also {
        require(it.scheme == "https" || it.scheme == "http") { "穿透平台地址必须是 HTTP(S)。" }
        require(it.username.isEmpty() && it.password.isEmpty() && it.querySize == 0 && it.fragment == null) { "穿透平台地址不能包含凭据或 query。" }
    }

    fun listPublicEndpoints(query: String? = null, cursor: String? = null, limit: Int = 50): TunnelDirectoryPage {
        require(limit in 1..100) { "目录页大小必须在 1 到 100 之间。" }
        val url = origin.newBuilder().encodedPath(TunnelChatProtocol.DIRECTORY_PATH).apply {
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("query", it.take(80)) }
            cursor?.takeIf { it.isNotBlank() }?.let { addQueryParameter("cursor", it.take(256)) }
            addQueryParameter("limit", limit.toString())
        }.build()
        execute(Request.Builder().url(url).get().build()).use { response ->
            val page = TunnelDirectoryPage.fromJson(JSONObject(readBody(response)))
            return page.copy(items = page.items.filter { it.publication == TunnelPublication.PUBLISHED })
        }
    }

    fun registerEndpoint(settings: TunnelChatSettings, identity: TunnelEndpointIdentity, webShare: Boolean = false): TunnelPlatformRegistration {
        val clean = settings.validate()
        require(identity.endpointId == clean.endpointId) { "端点身份与设置不匹配。" }
        val body = JSONObject()
            .put("endpointId", clean.endpointId)
            .put("publicKey", identity.publicKeyBase64())
            .put("displayName", clean.displayName)
            .put("publicIntroduction", clean.publicIntroduction)
            .put("publication", clean.publication.name.lowercase())
            .put("acceptance", clean.acceptance.name.lowercase())
            .put("maxAiReplies", clean.maxAiReplies)
            .apply { if (webShare) put("webShare", true) }
            .toString()
        val challengeRequest = Request.Builder().url(origin.newBuilder()
            .encodedPath("${TunnelChatProtocol.REGISTER_PATH}/challenge").build())
            .post(ByteArray(0).toRequestBody()).build()
        val challenge = execute(challengeRequest).use { JSONObject(readBody(it)).getString("challenge") }
        require(challenge.matches(Regex("[A-Za-z0-9_-]{43}"))) { "平台注册挑战无效。" }
        val proof = identity.sign("dshx-platform-register/1\n$challenge\n$body".toByteArray(Charsets.UTF_8))
        val request = Request.Builder()
            .url(origin.newBuilder().encodedPath(TunnelChatProtocol.REGISTER_PATH).build())
            .header("X-DSHX-Challenge", challenge)
            .header("X-DSHX-Signature", Base64.getUrlEncoder().withoutPadding().encodeToString(proof))
            .put(body.toRequestBody("application/json".toMediaTypeOrNull())).build()
        execute(request).use { response ->
            return parseRegistration(readBody(response), clean).also {
                require(it.endpoint.publication == clean.publication) { "平台未确认端点可见性设置。" }
            }
        }
    }

    fun updateEndpoint(settings: TunnelChatSettings, identity: TunnelEndpointIdentity): TunnelPlatformRegistration = registerEndpoint(settings, identity)

    fun unregisterEndpoint(endpointId: String, registrationToken: String? = null) {
        require(endpointId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "端点编号无效。" }
        val request = Request.Builder().url(origin.newBuilder().encodedPath(TunnelChatProtocol.DIRECTORY_PATH).addPathSegment(endpointId).build())
            .apply { registrationToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .delete().build()
        execute(request).close()
    }

    fun webSocketUrl(): String {
        val scheme = if (origin.scheme == "https") "wss" else "ws"
        val base = origin.toString().removeSuffix("/")
            .replaceFirst(Regex("^${origin.scheme}://"), "$scheme://")
        return base + TunnelChatProtocol.SIGNALING_PATH
    }

    private fun execute(request: Request): Response {
        val response = network { http.newCall(request).execute() }
        if (!response.isSuccessful) response.use {
            val body = readBody(response)
            val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            val code = error?.optString("code")?.takeIf { it.isNotBlank() } ?: "http_${response.code}"
            throw TunnelPlatformException(code, error?.optString("message")?.takeIf { it.isNotBlank() }
                ?: "穿透平台请求失败（HTTP ${response.code}）。", response.code == 429 || response.code >= 500)
        }
        return response
    }

    private fun readBody(response: Response) = network { response.body?.string().orEmpty() }

    private inline fun <T> network(call: () -> T): T = try { call() } catch (_: java.io.IOException) {
        throw TunnelPlatformException("platform_unavailable", "穿透平台暂时不可用。", retryable = true)
    }

    private fun parseRegistration(raw: String, settings: TunnelChatSettings): TunnelPlatformRegistration {
        val value = JSONObject(raw)
        val endpointValue = value.optJSONObject("endpoint") ?: value
        if (settings.publication == TunnelPublication.HIDDEN) {
            require(endpointValue.optString("endpointId") == settings.endpointId && endpointValue.optString("publication") == "hidden") {
                "平台未确认撤下此端点。"
            }
        }
        require(endpointValue.optString("endpointId", settings.endpointId) == settings.endpointId) { "平台返回的端点身份不匹配。" }
        val endpoint = settings.descriptor(online = false).copy(
            displayName = endpointValue.optString("displayName", settings.displayName),
            publicIntroduction = endpointValue.optString("publicIntroduction", settings.publicIntroduction),
            publication = when (endpointValue.optString("publication", settings.publication.name.lowercase())) {
                "published" -> TunnelPublication.PUBLISHED
                "hidden" -> TunnelPublication.HIDDEN
                else -> throw TunnelPlatformException("invalid_response", "平台返回了未知发布状态。")
            },
            acceptance = when (endpointValue.optString("acceptance", settings.acceptance.name.lowercase())) {
                "accepting" -> TunnelAcceptance.ACCEPTING
                "paused" -> TunnelAcceptance.PAUSED
                else -> throw TunnelPlatformException("invalid_response", "平台返回了未知接收状态。")
            },
            maxAiReplies = endpointValue.optInt("maxAiReplies", settings.maxAiReplies),
            capabilities = endpointValue.optJSONArray("capabilities")?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            } ?: settings.descriptor(false).capabilities,
        )
        val servers = value.optJSONArray("iceServers")?.let { array ->
            (0 until array.length()).map { TunnelIceServer.fromJson(array.getJSONObject(it)) }
        } ?: emptyList()
        val signalingUrl = value.optString("signalingUrl", webSocketUrl())
        Request.Builder().url(signalingUrl).build().url.let { url ->
            val expectedScheme = if (origin.scheme == "https") "wss" else "ws"
            require(signalingUrl.startsWith("$expectedScheme://") && url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port) {
                "平台返回的信令地址不属于当前安全平台。"
            }
            require(url.querySize == 0 && url.fragment == null && url.username.isEmpty() && url.password.isEmpty()) {
                "平台返回的信令地址包含不受支持的凭据或参数。"
            }
        }
        return TunnelPlatformRegistration(
            endpoint = endpoint,
            signalingUrl = signalingUrl,
            registrationToken = value.optString("registrationToken").takeIf { it.isNotBlank() },
            connectionId = value.optString("connectionId").takeIf { it.isNotBlank() },
            iceServers = servers,
            webAvailable = value.optBoolean("webAvailable"),
        )
    }
}

/** WebSocket 信令事件。业务正文只在 WebRTC DataChannel 上发送。 */
sealed class TunnelSignalingEvent {
    data class Registered(val connectionId: String?, val registrationToken: String?, val iceServers: List<TunnelIceServer>) : TunnelSignalingEvent()
    data class Message(val type: String, val sessionId: String?, val sourceEndpointId: String?, val payload: JSONObject) : TunnelSignalingEvent()
    data class Failed(val error: Throwable) : TunnelSignalingEvent()
    data object Closed : TunnelSignalingEvent()
}

class TunnelSignalingClient(
    private val http: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).followRedirects(false).build(),
) : AutoCloseable {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var endpointId: String? = null

    fun connect(url: String, settings: TunnelChatSettings, identity: TunnelEndpointIdentity, registrationToken: String? = null,
                listener: (TunnelSignalingEvent) -> Unit) {
        val clean = settings.validate()
        require(identity.endpointId == clean.endpointId) { "端点身份与设置不匹配。" }
        this.endpointId = clean.endpointId
        socket?.close(1000, "replaced")
        val request = Request.Builder().url(url).apply {
            registrationToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        }.build()
        socket = http.newWebSocket(request, SocketListener(clean, listener))
    }

    fun sendOffer(sessionId: String, targetEndpointId: String, sdp: String, conversationId: String = sessionId): Boolean =
        sendSignal("offer", sessionId, targetEndpointId,
            JSONObject().put("description", JSONObject().put("type", "offer").put("sdp", sdp))
                .apply { if (conversationId != sessionId) put("conversation_id", conversationId) })

    fun sendAnswer(sessionId: String, targetEndpointId: String, sdp: String): Boolean = sendSignal("answer", sessionId, targetEndpointId,
        JSONObject().put("description", JSONObject().put("type", "answer").put("sdp", sdp)))

    fun sendCandidate(sessionId: String, targetEndpointId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int): Boolean = sendSignal(
        "candidate", sessionId, targetEndpointId, JSONObject().put("candidate", candidate).put("sdp_mid", sdpMid ?: JSONObject.NULL).put("sdp_mline_index", sdpMLineIndex),
    )

    fun closeSession(sessionId: String, targetEndpointId: String): Boolean = sendSignal("session_close", sessionId, targetEndpointId, JSONObject())

    internal fun answerWeb(session: String, visitor: String, sdp: String, proof: String): Boolean =
        sendSignal("web_answer", session, visitor, JSONObject()
            .put("description", JSONObject().put("type", "answer").put("sdp", sdp)).put("proof", proof))

    internal fun candidateWeb(session: String, visitor: String, candidate: org.webrtc.IceCandidate): Boolean =
        sendSignal("web_candidate", session, visitor, JSONObject().put("candidate", candidate.sdp)
            .put("sdp_mid", candidate.sdpMid ?: JSONObject.NULL).put("sdp_mline_index", candidate.sdpMLineIndex))

    internal fun closeWeb(session: String, visitor: String): Boolean = sendSignal("web_close", session, visitor, JSONObject())

    internal fun publishWebStatus(enabled: Boolean): Boolean = socket?.send(
        envelope("web_status", null, null, JSONObject().put("enabled", enabled)).toString(),
    ) == true

    override fun close() {
        socket?.close(1000, "client_closed")
        socket = null
    }

    private fun sendSignal(type: String, sessionId: String, targetEndpointId: String, payload: JSONObject): Boolean {
        require(sessionId.isNotBlank() && targetEndpointId.isNotBlank()) { "信令会话和目标端点不能为空。" }
        val body = envelope(type, sessionId, targetEndpointId, payload)
        require(body.toString().toByteArray(Charsets.UTF_8).size <= TunnelChatProtocol.MAX_SIGNALING_BYTES) { "信令消息过大。" }
        return socket?.send(body.toString()) == true
    }

    private fun envelope(type: String, sessionId: String?, targetEndpointId: String?, payload: JSONObject): JSONObject = JSONObject()
        .put("protocol", TunnelChatProtocol.SIGNALING)
        .put("version", 1)
        .put("type", type)
        .put("message_id", UUID.randomUUID().toString())
        .apply {
            sessionId?.let { put("session_id", it) }
            // Signaling V1 的 register 首帧不得包含 source/destination。
            if (type != "register" && type != "register_proof") {
                endpointId?.let { put("source", JSONObject().put("kind", "endpoint").put("id", it)) }
                targetEndpointId?.let { put("destination", JSONObject().put("kind", "endpoint").put("id", it)) }
            }
            if (payload.length() > 0 || type != "error") put("payload", JSONObject(payload.toString()))
        }

    private inner class SocketListener(private val settings: TunnelChatSettings, private val listener: (TunnelSignalingEvent) -> Unit) : WebSocketListener() {
        private var registerId: String? = null
        private var registered = false
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val route = JSONObject().put("id", settings.endpointId)
            if (settings.publication == TunnelPublication.HIDDEN) route.put("code", UUID.randomUUID().toString())
            val body = envelope("register", null, null, JSONObject()
                .put("routes", JSONArray().put(route)))
            registerId = body.getString("message_id")
            webSocket.send(body.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.toByteArray(Charsets.UTF_8).size > TunnelChatProtocol.MAX_SIGNALING_BYTES) {
                listener(TunnelSignalingEvent.Failed(IllegalArgumentException("入站信令消息过大。")))
                webSocket.close(1009, "message_too_large")
                return
            }
            runCatching { handle(JSONObject(text)) }.onFailure {
                listener(TunnelSignalingEvent.Failed(it))
                webSocket.close(1002, "invalid_signaling")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            listener(TunnelSignalingEvent.Failed(IllegalArgumentException("信令不接受二进制消息。")))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            listener(TunnelSignalingEvent.Failed(t))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            listener(TunnelSignalingEvent.Closed)
        }

        private fun handle(value: JSONObject) {
            require(value.optString("protocol") == TunnelChatProtocol.SIGNALING && value.optInt("version") == 1) { "信令协议版本不受支持。" }
            val type = value.getString("type")
            val payload = value.optJSONObject("payload") ?: JSONObject()
            val source = value.optJSONObject("source")
            require(value.optJSONObject("destination")?.optString("id") == settings.endpointId) { "信令目标与本端不匹配。" }
            if (!registered) {
                require(source?.optString("kind") == "platform" && value.optString("reply_to") == registerId) { "信令注册回执无效。" }
                if (type == "register_challenge") error("平台要求端点证书挑战，当前客户端尚不支持。")
                if (type == "error") error("平台拒绝信令注册：${value.optJSONObject("error")?.optString("code").orEmpty()}")
                require(type == "register_ok" && payload.optJSONArray("registered_route_ids")?.optString(0) == settings.endpointId) { "平台未确认本端路由。" }
                registered = true
            }
            when (type) {
                "register_ok" -> {
                    val servers = payload.optJSONArray("ice_servers")?.let { array ->
                        (0 until array.length()).map { TunnelIceServer.fromJson(array.getJSONObject(it)) }
                    } ?: emptyList()
                    listener(TunnelSignalingEvent.Registered(payload.optString("connection_id").takeIf { it.isNotBlank() }, payload.optString("registration_token").takeIf { it.isNotBlank() }, servers))
                }
                "offer", "answer", "web_offer", "web_answer" -> {
                    val description = payload.optJSONObject("description") ?: return
                    val normalized = JSONObject(payload.toString()).put("sdp", description.optString("sdp"))
                    listener(TunnelSignalingEvent.Message(type, value.optString("session_id").takeIf { it.isNotBlank() }, source?.optString("id"), normalized))
                }
                else -> listener(TunnelSignalingEvent.Message(type, value.optString("session_id").takeIf { it.isNotBlank() }, source?.optString("id"),
                    if (type == "error") value.optJSONObject("error") ?: JSONObject() else payload))
            }
        }
    }
}
