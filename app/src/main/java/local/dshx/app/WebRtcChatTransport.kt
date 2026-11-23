package local.dshx.app

import android.content.Context
import chatquic.Chatquic
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStats
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

data class WebRtcChatSessionInfo(val sessionId: String, val remoteEndpointId: String, val state: State) {
    enum class State { CONNECTING, CONNECTED, CLOSED, FAILED }
}

sealed class WebRtcChatEvent {
    data class LocalOffer(val sessionId: String, val remoteEndpointId: String, val sdp: String) : WebRtcChatEvent()
    data class LocalAnswer(val sessionId: String, val remoteEndpointId: String, val sdp: String) : WebRtcChatEvent()
    data class LocalCandidate(val sessionId: String, val remoteEndpointId: String, val candidate: IceCandidate) : WebRtcChatEvent()
    data class StateChanged(val info: WebRtcChatSessionInfo) : WebRtcChatEvent()
    data class Frame(val sessionId: String, val bytes: ByteArray) : WebRtcChatEvent()
    data class Failed(val sessionId: String, val remoteEndpointId: String, val error: Throwable) : WebRtcChatEvent()
}

/** WebRTC 负责选路和传递数据报；QUIC 在通道上独立处理重传和流。 */
class WebRtcChatTransport(
    context: Context,
    private val webShare: Boolean = false,
    private val onEvent: (WebRtcChatEvent) -> Unit,
) : AutoCloseable {
    private val factory = WebRtcFactory.get(context.applicationContext)
    private val sessions = ConcurrentHashMap<String, Session>()

    fun createOutgoing(sessionId: String, remoteEndpointId: String, iceServers: List<TunnelIceServer>, relayOnly: Boolean = false): WebRtcChatSessionInfo {
        require(sessionId.isNotBlank() && remoteEndpointId.isNotBlank()) { "WebRTC 会话和远端端点不能为空。" }
        val session = createSession(sessionId, remoteEndpointId, iceServers, relayOnly, initiator = true)
        session.peer.createOffer(session.sdpObserver("offer") { description ->
            session.peer.setLocalDescription(session.setObserver("set_local_offer") {
                onEvent(WebRtcChatEvent.LocalOffer(sessionId, remoteEndpointId, description.description))
            }, description)
        }, MediaConstraints())
        return session.info()
    }

    fun acceptIncoming(sessionId: String, remoteEndpointId: String, offerSdp: String, iceServers: List<TunnelIceServer>, relayOnly: Boolean = false): WebRtcChatSessionInfo {
        val session = createSession(sessionId, remoteEndpointId, iceServers, relayOnly, initiator = false)
        session.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, offerSdp)) {
            session.peer.createAnswer(session.sdpObserver("answer") { description ->
                session.peer.setLocalDescription(session.setObserver("set_local_answer") {
                    onEvent(WebRtcChatEvent.LocalAnswer(sessionId, remoteEndpointId, description.description))
                }, description)
            }, MediaConstraints())
        }
        return session.info()
    }

    fun acceptAnswer(sessionId: String, answerSdp: String) {
        val session = sessions[sessionId] ?: throw IllegalArgumentException("未知 WebRTC 会话。")
        session.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, answerSdp), null)
    }

    fun addRemoteCandidate(sessionId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val session = sessions[sessionId] ?: throw IllegalArgumentException("未知 WebRTC 会话。")
        session.addCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun send(sessionId: String, value: ByteArray): Boolean = sessions[sessionId]?.send(value) == true

    internal fun readLink(sessionId: String, onResult: (TunnelChatLink?) -> Unit) {
        val session = sessions[sessionId] ?: return
        val ice = session.peer.iceConnectionState()
        if (ice != PeerConnection.IceConnectionState.CONNECTED && ice != PeerConnection.IceConnectionState.COMPLETED) {
            onResult(null)
            return
        }
        session.peer.getStats { report ->
            if (session.active()) onResult(selectedChatLink(report.statsMap))
        }
    }

    /** 清理已经从 Runtime 中移除的会话，不再次回调状态机。 */
    fun discardSession(sessionId: String) {
        sessions.remove(sessionId)?.disposeSilently()
    }

    override fun close() {
        sessions.keys.toList().forEach(::discardSession)
    }

    private fun createSession(sessionId: String, remoteEndpointId: String, iceServers: List<TunnelIceServer>, relayOnly: Boolean, initiator: Boolean): Session {
        require(sessions[sessionId] == null) { "WebRTC 会话编号已存在。" }
        val servers = if (webShare) iceServers.mapNotNull { server ->
            server.urls.filter { it.startsWith("stun:", true) || it.startsWith("stuns:", true) }
                .takeIf { it.isNotEmpty() }?.let { TunnelIceServer(it) }
        } else iceServers
        val config = PeerConnection.RTCConfiguration(servers.map { server ->
            PeerConnection.IceServer.builder(server.urls).apply {
                server.username?.let(::setUsername)
                server.credential?.let(::setPassword)
            }.createIceServer()
        }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        }
        lateinit var session: Session
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED) session.fail(IllegalStateException("WebRTC ICE 连接失败。"))
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) = session.localCandidate(candidate)
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = channel.dispose()
            override fun onRenegotiationNeeded() = Unit
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                when (state) {
                    PeerConnection.PeerConnectionState.FAILED -> session.fail(IllegalStateException("WebRTC 对等连接失败。"))
                    PeerConnection.PeerConnectionState.CLOSED -> session.emitState(WebRtcChatSessionInfo.State.CLOSED)
                    else -> Unit
                }
            }
        }
        val peer = factory.createPeerConnection(config, observer) ?: error("无法创建 WebRTC 对等连接。")
        session = Session(sessionId, remoteEndpointId, peer, initiator)
        check(sessions.putIfAbsent(sessionId, session) == null) { "WebRTC 会话编号已存在。" }
        try {
            session.openChannel()
        } catch (error: Throwable) {
            discardSession(sessionId)
            throw error
        }
        return session
    }

    private inner class Session(
        val sessionId: String,
        val remoteEndpointId: String,
        val peer: PeerConnection,
        val initiator: Boolean,
    ) {
        private var channel: DataChannel? = null
        private val channelLock = Any()
        private val disposed = AtomicBoolean()
        private var remoteDescriptionSet = false
        private val pendingCandidates = mutableListOf<IceCandidate>()
        private val localCandidates = mutableListOf<IceCandidate>()
        private var descriptionPublished = false
        @Volatile private var state = WebRtcChatSessionInfo.State.CONNECTING

        fun info() = WebRtcChatSessionInfo(sessionId, remoteEndpointId, state)

        fun active() = !disposed.get() && sessions[sessionId] === this

        fun send(value: ByteArray): Boolean = synchronized(channelLock) {
            val openChannel = channel ?: return false
            if (!active() || value.size !in 1..maxFrame() || openChannel.state() != DataChannel.State.OPEN ||
                openChannel.bufferedAmount() >= MAX_BUFFERED) return false
            openChannel.send(DataChannel.Buffer(ByteBuffer.wrap(value), true))
        }

        fun localCandidate(candidate: IceCandidate) = synchronized(localCandidates) {
            if (!active() || webShare && !directWebCandidate(candidate.sdp)) return@synchronized
            if (descriptionPublished) onEvent(WebRtcChatEvent.LocalCandidate(sessionId, remoteEndpointId, candidate))
            else if (localCandidates.size < 128) localCandidates += candidate
        }

        fun openChannel() {
            // 双方提前约定通道编号，各自设置发送语义，不接受对端另开的通道。
            val init = DataChannel.Init().apply {
                negotiated = true
                id = 0
                ordered = webShare
                if (!webShare) maxRetransmits = 0
                protocol = if (webShare) WEB_CHANNEL_LABEL else CHANNEL_LABEL
            }
            synchronized(channelLock) {
                check(active()) { "WebRTC 会话已关闭。" }
                val created = peer.createDataChannel(init.protocol, init) ?: error("无法创建 WebRTC 数据通道。")
                channel = created
                bindChannel(created)
            }
        }

        private fun bindChannel(channel: DataChannel) {
            channel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() {
                    when (channel.state()) {
                        DataChannel.State.OPEN -> emitState(WebRtcChatSessionInfo.State.CONNECTED)
                        DataChannel.State.CLOSED -> emitState(WebRtcChatSessionInfo.State.CLOSED)
                        else -> Unit
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (!active() || !buffer.binary || buffer.data.remaining() !in 1..maxFrame()) return
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    onEvent(WebRtcChatEvent.Frame(sessionId, bytes))
                }
            })
            if (channel.state() == DataChannel.State.OPEN) emitState(WebRtcChatSessionInfo.State.CONNECTED)
        }

        fun setRemoteDescription(description: SessionDescription, onSuccess: (() -> Unit)?) {
            peer.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onSetSuccess() {
                    if (!active()) return
                    synchronized(pendingCandidates) {
                        remoteDescriptionSet = true
                        pendingCandidates.forEach(peer::addIceCandidate)
                        pendingCandidates.clear()
                    }
                    onSuccess?.invoke()
                }
                override fun onCreateFailure(error: String) = fail(IllegalStateException(error))
                override fun onSetFailure(error: String) = fail(IllegalStateException(error))
            }, description)
        }

        fun addCandidate(candidate: IceCandidate) = synchronized(pendingCandidates) {
            if (webShare && !directWebCandidate(candidate.sdp)) return@synchronized
            if (remoteDescriptionSet) peer.addIceCandidate(candidate)
            else if (pendingCandidates.size < 128) pendingCandidates += candidate
            Unit
        }

        fun sdpObserver(stage: String, onSuccess: (SessionDescription) -> Unit): SdpObserver = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) { if (active()) onSuccess(description) }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) = fail(IllegalStateException("WebRTC $stage 失败：$error"))
            override fun onSetFailure(error: String) = fail(IllegalStateException("WebRTC $stage 失败：$error"))
        }

        fun setObserver(stage: String, onSuccess: () -> Unit): SdpObserver = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onSetSuccess() {
                synchronized(localCandidates) {
                    if (!active()) return
                    onSuccess()
                    descriptionPublished = true
                    localCandidates.forEach { localCandidate(it) }
                    localCandidates.clear()
                }
            }
            override fun onCreateFailure(error: String) = fail(IllegalStateException("WebRTC $stage 失败：$error"))
            override fun onSetFailure(error: String) = fail(IllegalStateException("WebRTC $stage 失败：$error"))
        }

        fun emitState(next: WebRtcChatSessionInfo.State) {
            if (!active() || state == next) return
            state = next
            onEvent(WebRtcChatEvent.StateChanged(info()))
        }

        fun fail(error: Throwable) {
            if (active() && state != WebRtcChatSessionInfo.State.FAILED) {
                state = WebRtcChatSessionInfo.State.FAILED
                onEvent(WebRtcChatEvent.Failed(sessionId, remoteEndpointId, error))
            }
        }

        fun disposeSilently() {
            if (!disposed.compareAndSet(false, true)) return
            val current = synchronized(channelLock) { channel.also { channel = null } }
            current?.unregisterObserver()
            current?.close()
            current?.dispose()
            peer.close()
            peer.dispose()
            state = WebRtcChatSessionInfo.State.CLOSED
        }
    }

    private companion object {
        const val CHANNEL_LABEL = "dshx-h3/1"
        const val WEB_CHANNEL_LABEL = "dshx-web/1"
        const val MAX_BUFFERED = 64 * 1024L
    }

    private fun maxFrame() = if (webShare) 32 * 1024 else Chatquic.MaxDatagram
}

private val directCandidateType = Regex("^(?:a=)?candidate:\\S+\\s+\\d+\\s+\\S+\\s+\\d+\\s+\\S+\\s+\\d+\\s+typ\\s+(host|srflx|prflx)(?:\\s|$)", RegexOption.IGNORE_CASE)
internal fun directWebCandidate(candidate: String) = directCandidateType.containsMatchIn(candidate)

internal fun selectedChatLink(stats: Map<String, RTCStats>): TunnelChatLink? {
    val pairId = stats.values.firstNotNullOfOrNull { entry ->
        if (entry.type == "transport") entry.members["selectedCandidatePairId"] as? String else null
    } ?: return null
    val pair = stats[pairId]?.takeIf { it.type == "candidate-pair" && it.members["state"] == "succeeded" } ?: return null
    fun candidateType(member: String, type: String): String? = stats[pair.members[member] as? String]
        ?.takeIf { it.type == type }?.members?.get("candidateType") as? String
    val local = candidateType("localCandidateId", "local-candidate")
    val remote = candidateType("remoteCandidateId", "remote-candidate")
    val direct = setOf("host", "srflx", "prflx")
    val path = when {
        local == "relay" || remote == "relay" -> TunnelChatLink.Path.RELAY
        local in direct && remote in direct -> TunnelChatLink.Path.DIRECT
        else -> null
    }
    val roundTripMs = (pair.members["currentRoundTripTime"] as? Number)?.toDouble()?.let { it * 1_000 }
        ?.takeIf { it.isFinite() && it >= 0.0 }?.roundToLong()
    return TunnelChatLink(path, roundTripMs)
}

private object WebRtcFactory {
    @Volatile private var factory: PeerConnectionFactory? = null

    @Synchronized
    fun get(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        return PeerConnectionFactory.builder().setOptions(PeerConnectionFactory.Options()).createPeerConnectionFactory().also { factory = it }
    }
}
