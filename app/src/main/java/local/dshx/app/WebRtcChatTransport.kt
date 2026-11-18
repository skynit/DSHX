package local.dshx.app

import android.content.Context
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class WebRtcChatSessionInfo(val sessionId: String, val remoteEndpointId: String, val state: State) {
    enum class State { CONNECTING, CONNECTED, CLOSED, FAILED }
}

sealed class WebRtcChatEvent {
    data class LocalOffer(val sessionId: String, val remoteEndpointId: String, val sdp: String) : WebRtcChatEvent()
    data class LocalAnswer(val sessionId: String, val remoteEndpointId: String, val sdp: String) : WebRtcChatEvent()
    data class LocalCandidate(val sessionId: String, val remoteEndpointId: String, val candidate: IceCandidate) : WebRtcChatEvent()
    data class StateChanged(val info: WebRtcChatSessionInfo) : WebRtcChatEvent()
    data class Frame(val sessionId: String, val remoteEndpointId: String, val bytes: ByteArray, val binary: Boolean) : WebRtcChatEvent()
    data class Failed(val sessionId: String, val remoteEndpointId: String, val error: Throwable) : WebRtcChatEvent()
}

/**
 * WebRTC 只负责建立加密 DataChannel；上层必须继续使用 dshx-chat/1 JSON，
 * 不把平台信令消息或普通透明 HTTP 数据直接暴露给聊天层。
 */
class WebRtcChatTransport(
    context: Context,
    private val onEvent: (WebRtcChatEvent) -> Unit,
) : AutoCloseable {
    private val factory = WebRtcFactory.get(context.applicationContext)
    private val sessions = ConcurrentHashMap<String, Session>()

    fun createOutgoing(sessionId: String, remoteEndpointId: String, iceServers: List<TunnelIceServer>, relayOnly: Boolean = false): WebRtcChatSessionInfo {
        require(sessionId.isNotBlank() && remoteEndpointId.isNotBlank()) { "WebRTC 会话和远端端点不能为空。" }
        val session = createSession(sessionId, remoteEndpointId, iceServers, relayOnly, initiator = true)
        val init = DataChannel.Init().apply {
            ordered = true
            protocol = TunnelChatProtocol.CHAT
        }
        session.channel = session.peer.createDataChannel(TunnelChatProtocol.CHAT, init)
        session.bindChannel(session.channel!!)
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

    fun send(sessionId: String, value: ByteArray, binary: Boolean = false): Boolean {
        require(value.size <= TunnelChatProtocol.MAX_MESSAGE_BYTES) { "聊天帧过大。" }
        val channel = sessions[sessionId]?.channel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(value), binary))
    }

    fun sendJson(sessionId: String, value: org.json.JSONObject): Boolean = send(sessionId, value.toString().toByteArray(Charsets.UTF_8))

    /** 清理已经从 Runtime 中移除的会话，不再次回调状态机。 */
    fun discardSession(sessionId: String) {
        sessions.remove(sessionId)?.disposeSilently()
    }

    override fun close() {
        sessions.keys.toList().forEach(::discardSession)
    }

    private fun createSession(sessionId: String, remoteEndpointId: String, iceServers: List<TunnelIceServer>, relayOnly: Boolean, initiator: Boolean): Session {
        require(sessions[sessionId] == null) { "WebRTC 会话编号已存在。" }
        val config = PeerConnection.RTCConfiguration(iceServers.map { server ->
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
            override fun onDataChannel(channel: DataChannel) {
                if (session.active() && channel.label() == TunnelChatProtocol.CHAT && session.channel == null) {
                    session.channel = channel
                    session.bindChannel(channel)
                } else channel.dispose()
            }
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
        return session
    }

    private inner class Session(
        val sessionId: String,
        val remoteEndpointId: String,
        val peer: PeerConnection,
        val initiator: Boolean,
    ) {
        @Volatile var channel: DataChannel? = null
        private val disposed = AtomicBoolean()
        private var remoteDescriptionSet = false
        private val pendingCandidates = mutableListOf<IceCandidate>()
        private val localCandidates = mutableListOf<IceCandidate>()
        private var descriptionPublished = false
        @Volatile private var state = WebRtcChatSessionInfo.State.CONNECTING

        fun info() = WebRtcChatSessionInfo(sessionId, remoteEndpointId, state)

        fun active() = !disposed.get() && sessions[sessionId] === this

        fun localCandidate(candidate: IceCandidate) = synchronized(localCandidates) {
            if (!active()) return@synchronized
            if (descriptionPublished) onEvent(WebRtcChatEvent.LocalCandidate(sessionId, remoteEndpointId, candidate))
            else if (localCandidates.size < 128) localCandidates += candidate
        }

        fun bindChannel(channel: DataChannel) {
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
                    if (!active()) return
                    if (buffer.data.remaining() > TunnelChatProtocol.MAX_MESSAGE_BYTES) {
                        fail(IllegalArgumentException("入站聊天帧过大。"))
                        return
                    }
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    onEvent(WebRtcChatEvent.Frame(sessionId, remoteEndpointId, bytes, buffer.binary))
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
            channel?.unregisterObserver()
            channel?.close()
            channel?.dispose()
            peer.close()
            peer.dispose()
            state = WebRtcChatSessionInfo.State.CLOSED
        }
    }
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
