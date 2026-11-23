package local.dshx.app

import android.content.Context
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 只接收浏览器发起的网页会话，不进入聊天接待或 AI 流程。 */
internal class WebShareServer(
    context: Context,
    private val shares: WebShares,
    private val identity: () -> TunnelEndpointIdentity,
    private val signaling: TunnelSignalingClient,
    private val scheduler: ScheduledExecutorService,
    private val schedule: (() -> Unit) -> Unit,
) : AutoCloseable {
    private val workers = ThreadPoolExecutor(0, 24, 30, TimeUnit.SECONDS, SynchronousQueue(), { task ->
        Thread(task, "dshx-web-http").apply { isDaemon = true }
    })
    private val pendingFrames = Semaphore(128)
    private val overflowing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val transport = WebRtcChatTransport(context, webShare = true) { event ->
        if (event !is WebRtcChatEvent.Frame) schedule { handle(event) }
        else if (pendingFrames.tryAcquire()) schedule {
            try { handle(event) } finally { pendingFrames.release() }
        } else if (overflowing.add(event.sessionId)) schedule {
            try { end(event.sessionId) } finally { overflowing.remove(event.sessionId) }
        }
    }
    private val sessions = mutableMapOf<String, Session>()

    fun signal(event: TunnelSignalingEvent.Message, servers: List<TunnelIceServer>, public: Boolean) {
        val id = event.sessionId ?: return
        val visitor = event.sourceEndpointId ?: return
        when (event.type) {
            "web_offer" -> {
                val offer = event.payload.optString("sdp")
                if (sessions.containsKey(id)) return
                if (!public || shares.publicList().isEmpty() || sessions.size >= 12 || offer.isBlank() ||
                    offer.lineSequence().any { it.startsWith("a=candidate:", true) && !directWebCandidate(it) }) {
                    signaling.closeWeb(id, visitor)
                    return
                }
                val session = Session(id, visitor, offer)
                sessions[id] = session
                runCatching { transport.acceptIncoming(id, visitor, offer, servers) }.onFailure { end(id) }
                scheduler.schedule({ schedule { if (sessions[id] === session && !session.ready) end(id) } }, 25, TimeUnit.SECONDS)
            }
            "web_candidate" -> if (sessions[id]?.visitor == visitor) runCatching {
                transport.addRemoteCandidate(id, event.payload.getString("candidate"),
                    event.payload.optString("sdp_mid").takeUnless { it.isBlank() || it == "null" }, event.payload.getInt("sdp_mline_index"))
            }
            "web_close" -> if (sessions[id]?.visitor == visitor) end(id, notify = false)
        }
    }

    private fun handle(event: WebRtcChatEvent) {
        when (event) {
            is WebRtcChatEvent.LocalAnswer -> sessions[event.sessionId]?.let { session ->
                if (!runCatching {
                        val key = identity()
                        val transcript = "dshx-web/1\n${session.id}\n${session.visitor}\n${key.endpointId}\n${session.offer}\n${event.sdp}"
                        val encoding = Base64.getUrlEncoder().withoutPadding()
                        val proof = encoding.encodeToString(key.publicKey) + "." + encoding.encodeToString(key.sign(transcript.toByteArray()))
                        signaling.answerWeb(session.id, session.visitor, event.sdp, proof)
                    }.getOrDefault(false)) end(session.id)
            }
            is WebRtcChatEvent.LocalCandidate -> sessions[event.sessionId]?.let {
                if (!signaling.candidateWeb(it.id, it.visitor, event.candidate)) end(it.id)
            }
            is WebRtcChatEvent.StateChanged -> when (event.info.state) {
                WebRtcChatSessionInfo.State.CONNECTED -> sessions[event.info.sessionId]?.let(::verifyDirect)
                WebRtcChatSessionInfo.State.FAILED, WebRtcChatSessionInfo.State.CLOSED -> end(event.info.sessionId)
                WebRtcChatSessionInfo.State.CONNECTING -> Unit
            }
            is WebRtcChatEvent.Frame -> sessions[event.sessionId]?.let { session ->
                val frame = runCatching { JSONObject(event.bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return
                if (session.ready) session.proxy.receive(frame)
                else if (frame.optString("type") == "list") session.waitingList = true
            }
            is WebRtcChatEvent.Failed -> end(event.sessionId)
            is WebRtcChatEvent.LocalOffer -> Unit
        }
    }

    private fun verifyDirect(session: Session) {
        transport.readLink(session.id) { link -> schedule {
            if (sessions[session.id] !== session) return@schedule
            when (link?.path) {
                TunnelChatLink.Path.DIRECT -> {
                    session.ready = true
                    if (session.waitingList) {
                        session.waitingList = false
                        session.proxy.receive(JSONObject().put("type", "list"))
                    }
                }
                TunnelChatLink.Path.RELAY -> end(session.id)
                null -> scheduler.schedule({ schedule { if (sessions[session.id] === session) verifyDirect(session) } }, 200, TimeUnit.MILLISECONDS)
            }
        } }
    }

    fun revoke(share: String) = sessions.values.filter { it.proxy.uses(share) }.map { it.id }.forEach { end(it) }
    fun checkTargets() = sessions.values.filterNot { it.proxy.valid() }.map { it.id }.forEach { end(it) }
    fun stop() = sessions.keys.toList().forEach { end(it) }

    private fun end(id: String, notify: Boolean = true) {
        val session = sessions.remove(id) ?: return
        session.active = false
        session.proxy.close()
        if (notify) signaling.closeWeb(id, session.visitor)
        transport.discardSession(id)
    }

    override fun close() {
        stop()
        transport.close()
        workers.shutdownNow()
    }

    private inner class Session(val id: String, val visitor: String, val offer: String) {
        @Volatile var active = true
        @Volatile var ready = false
        var waitingList = false
        val proxy = WebShareProxy(shares, ::send, workers)

        private fun send(frame: JSONObject): Boolean {
            val bytes = frame.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > 32 * 1024) return false
            if (transport.send(id, bytes)) return true
            // 数据通道发送缓冲满时，阻塞 HTTP 工作者，不阻塞接收信令的线程。
            if (!Thread.currentThread().name.startsWith("dshx-web-http") && !Thread.currentThread().name.startsWith("OkHttp")) return false
            repeat(200) {
                if (!active || Thread.currentThread().isInterrupted) return false
                if (transport.send(id, bytes)) return true
                Thread.sleep(15L)
            }
            return false
        }
    }
}
