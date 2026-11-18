package local.dshx.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class TunnelChatRuntimeState(
    val status: Status = Status.DISABLED,
    val message: String = "穿透聊天未开启。",
    val endpoint: TunnelEndpointDescriptor? = null,
    val activeConversations: Int = 0,
    val conversations: List<TunnelChatConversationSnapshot> = emptyList(),
)

/** 运行状态只反映客户端本地生命周期，不把“平台注册成功”冒充为“聊天连接成功”。 */
enum class Status { DISABLED, STARTING, ONLINE, OFFLINE, FAILED }

class TunnelChatRuntime(
    private val context: Context,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
) : AutoCloseable {
    private val settingsStore = TunnelChatSettingsStore(context)
    private val listeners = CopyOnWriteArraySet<(TunnelChatRuntimeState) -> Unit>()
    private val signaling = TunnelSignalingClient()
    private val sessionBindings = ConcurrentHashMap<String, SessionBinding>()
    private val authenticatedSessions = ConcurrentHashMap.newKeySet<String>()
    private val conversationStates = ConcurrentHashMap<String, TunnelConversationState>()
    private val conversationMetadata = ConcurrentHashMap<String, ConversationMetadata>()
    private val conversationMessages = ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>>()
    private val sequence = ConcurrentHashMap<String, Long>()
    private val deduplicator = TunnelMessageDeduplicator()
    private val admission = TunnelConversationAdmission { currentSettings }
    private val aiWorker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(16), { task ->
        Thread(task, "dshx-tunnel-ai").apply { isDaemon = true }
    }, ThreadPoolExecutor.DiscardPolicy())
    private val retryScheduler = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "dshx-tunnel-retry").apply { isDaemon = true } }
    @Volatile private var registration: TunnelPlatformRegistration? = null
    private val identity by lazy { AndroidTunnelEndpointIdentity() }
    private var generation = 0L
    @Volatile private var closed = false
    @Volatile private var engineReady = false
    @Volatile private var nextRegistrationAttempt = Long.MAX_VALUE
    @Volatile private var acceptingSignals = false
    @Volatile private var currentSettings = settingsStore.read()
    @Volatile var state: TunnelChatRuntimeState = initialState(currentSettings)
        private set
    @Volatile private var messageListener: ((ChatMessage, String) -> Unit)? = null
    @Volatile private var aiResponder: ((TunnelChatAiRequest) -> String?)? = null
    @Volatile private var transport: WebRtcChatTransport? = null
    private val queue by lazy {
        TunnelChatQueueStore(
            File(context.filesDir, "tunnel-chat/outbox.json"),
            AndroidTunnelChatQueueCipher(),
        ) { currentSettings }
    }

    init {
        retryScheduler.scheduleAtFixedRate({
            onWorker {
                runCatching {
                    if (engineReady && System.currentTimeMillis() >= nextRegistrationAttempt) start()
                    if (state.status == Status.ONLINE) retryQueued()
                }.onFailure { publishState("本地待发消息读取或重试失败。") }
            }
        }, 5L, 5L, TimeUnit.SECONDS)
    }

    fun observe(listener: (TunnelChatRuntimeState) -> Unit) { listeners.add(listener); listener(state) }

    fun remove(listener: (TunnelChatRuntimeState) -> Unit) { listeners.remove(listener) }

    fun setMessageListener(listener: ((ChatMessage, String) -> Unit)?) { messageListener = listener }

    fun setAiResponder(responder: ((TunnelChatAiRequest) -> String?)?) { aiResponder = responder }

    fun settings(): TunnelChatSettings = currentSettings

    @Synchronized fun saveSettings(settings: TunnelChatSettings): TunnelChatSettings {
        val clean = settings.validate()
        val previous = currentSettings
        currentSettings = settingsStore.save(clean.copy(endpointId = previous.endpointId))
        if (!clean.enabled) stop()
        else if (previous != clean && engineReady) {
            if (previous.platformOrigin != clean.platformOrigin || clean.publication == TunnelPublication.HIDDEN) stop()
            else disconnectSignaling("正在应用端点设置…", retry = false)
            start()
        } else if (!engineReady) update(initialState(clean))
        return currentSettings
    }

    @Synchronized fun setEngineReady(ready: Boolean) {
        if (engineReady == ready) return
        engineReady = ready
        if (ready) start() else stop()
    }

    @Synchronized private fun start() {
        if (closed || !engineReady || acceptingSignals) return
        if (!currentSettings.enabled) {
            update(initialState(currentSettings))
            return
        }
        nextRegistrationAttempt = Long.MAX_VALUE
        val run = ++generation
        acceptingSignals = true
        update(TunnelChatRuntimeState(Status.STARTING, "正在注册聊天端点…", currentSettings.descriptor(online = false)))
        submit {
            val registered = runCatching {
                val clean = synchronized(this) {
                    if (!isCurrent(run)) return@submit
                    settingsWithIdentity()
                }
                val platform = TunnelPlatformClient(clean.platformOrigin)
                platform.registerEndpoint(clean, identity)
            }
            synchronized(this) {
                if (!isCurrent(run)) return@submit
                runCatching {
                    val result = registered.getOrThrow()
                    registration = result
                    signaling.connect(result.signalingUrl, currentSettings, identity, result.registrationToken) { event ->
                        onWorker { if (isCurrent(run)) handleSignalingEvent(event) }
                    }
                    retryScheduler.schedule({ onWorker {
                        if (isCurrent(run) && state.status == Status.STARTING) disconnectSignaling("等待信令注册超时。", retry = true)
                    } }, 25, TimeUnit.SECONDS)
                }.onFailure { error ->
                    disconnectSignaling(error.message ?: "穿透平台注册失败。", (error as? TunnelPlatformException)?.retryable == true)
                }
            }
        }
    }

    private fun settingsWithIdentity(): TunnelChatSettings {
        val endpointId = identity.endpointId
        if (currentSettings.endpointId != endpointId) {
            currentSettings = settingsStore.save(currentSettings.copy(endpointId = endpointId))
        }
        return currentSettings
    }

    private fun stop() {
        generation++
        acceptingSignals = false
        nextRegistrationAttempt = Long.MAX_VALUE
        signaling.close()
        sessionBindings.clear()
        authenticatedSessions.clear()
        admission.clear()
        registration = null
        transport?.close()
        transport = null
        update(initialState(currentSettings))
    }

    fun listPublicEndpoints(platformOrigin: String, query: String? = null, cursor: String? = null): TunnelDirectoryPage {
        return TunnelPlatformClient(platformOrigin).listPublicEndpoints(query, cursor)
    }

    @Synchronized fun startConversation(endpoint: TunnelEndpointDescriptor, platformOrigin: String): String {
        require(currentSettings.enabled && platformOrigin == currentSettings.platformOrigin) { "穿透平台已切换，请刷新端点目录。" }
        return if (endpoint.online && state.status == Status.ONLINE) openConversation(endpoint.endpointId)
        else prepareConversation(endpoint.endpointId)
    }

    @Synchronized fun openConversation(remoteEndpointId: String, conversationId: String = UUID.randomUUID().toString(), relayOnly: Boolean = false): String {
        require(remoteEndpointId.isNotBlank() && remoteEndpointId != currentSettings.endpointId) { "目标端点无效。" }
        require(state.status == Status.ONLINE) { "端点尚未在线，暂不能发起聊天。" }
        val metadata = conversationMetadata[conversationId]
        require(metadata == null || metadata.outgoing && metadata.remoteEndpointId == remoteEndpointId &&
            metadata.platformOrigin == currentSettings.platformOrigin) { "聊天会话目标不匹配。" }
        sessionBindings.values.firstOrNull { it.conversationId == conversationId }?.let { return conversationId }
        val current = registration ?: throw IllegalStateException("端点尚未向平台注册。")
        require(conversationId.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"))) {
            "穿透会话编号必须是 UUID。"
        }
        val sessionId = conversationId
        sessionBindings[sessionId] = SessionBinding(sessionId, conversationId, remoteEndpointId, outgoing = true)
        conversationStates.putIfAbsent(conversationId, TunnelConversationState { currentSettings.maxAiReplies })
        conversationMetadata.putIfAbsent(conversationId, ConversationMetadata(remoteEndpointId, outgoing = true, platformOrigin = currentSettings.platformOrigin))
        conversationMessages.putIfAbsent(conversationId, CopyOnWriteArrayList())
        sequence.putIfAbsent(conversationId, 0L)
        try {
            transportOrThrow().createOutgoing(sessionId, remoteEndpointId, current.iceServers, relayOnly)
            expireNegotiation(sessionBindings.getValue(sessionId))
        } catch (error: Throwable) {
            disconnectSession(sessionId)
            throw error
        }
        publishState(state.message)
        return conversationId
    }

    fun reconnectConversation(conversationId: String, relayOnly: Boolean = false): String {
        val metadata = conversationMetadata[conversationId] ?: throw IllegalArgumentException("未知聊天会话。")
        return openConversation(metadata.remoteEndpointId, conversationId, relayOnly)
    }

    private fun prepareConversation(remoteEndpointId: String): String {
        settingsWithIdentity()
        require(validId(remoteEndpointId) && remoteEndpointId != currentSettings.endpointId) { "目标端点无效。" }
        val id = UUID.randomUUID().toString()
        conversationMetadata[id] = ConversationMetadata(remoteEndpointId, outgoing = true, platformOrigin = currentSettings.platformOrigin)
        conversationStates[id] = TunnelConversationState { currentSettings.maxAiReplies }
        publishState(state.message)
        return id
    }

    @Synchronized fun sendText(conversationId: String, text: String): ChatMessage {
        val binding = sessionBindings.values.firstOrNull { it.conversationId == conversationId }
        require(currentSettings.enabled && conversationMetadata[conversationId]?.outgoing == true) { "访客会话尚未建立。" }
        val message = textMessage(conversationId, text, ChatSenderRole.VISITOR, ChatMessageKind.TEXT)
        enqueue(message)
        appendMessage(message, currentSettings.endpointId)
        binding?.let { flushQueued(it.conversationId) } ?: publishState("消息已加入本地待发队列。")
        return message
    }

    @Synchronized private fun sendAiFormalText(conversationId: String, text: String): ChatMessage? {
        val binding = sessionBindings.values.firstOrNull { it.conversationId == conversationId } ?: return null
        val conversation = conversationStates[conversationId] ?: return null
        val message = textMessage(conversationId, text, ChatSenderRole.AI, ChatMessageKind.AI_FORMAL)
        if (!conversation.recordAiFormalReply { enqueue(message) }) return null
        appendMessage(message, currentSettings.endpointId)
        flushQueued(binding.conversationId)
        notifyWaitingHuman(binding, conversation)
        return message
    }

    @Synchronized fun sendHumanFormalText(conversationId: String, text: String): ChatMessage {
        val binding = sessionBindings.values.firstOrNull { it.conversationId == conversationId }
        require(binding != null && binding.sessionId in authenticatedSessions && !binding.outgoing) { "访客尚未连接。" }
        val message = textMessage(conversationId, text, ChatSenderRole.ENDPOINT_USER, ChatMessageKind.HUMAN_FORMAL)
        enqueue(message)
        conversationStates[conversationId]?.recordFormalMessage(ChatMessageKind.HUMAN_FORMAL, ChatSenderRole.ENDPOINT_USER)
        appendMessage(message, currentSettings.endpointId)
        flushQueued(binding.conversationId)
        return message
    }

    @Synchronized fun resumeAi(conversationId: String) {
        conversationStates[conversationId]?.resumeAi()
        publishState(state.message)
    }

    @Synchronized override fun close() {
        closed = true
        stop()
        worker.shutdownNow()
        aiWorker.shutdownNow()
        retryScheduler.shutdownNow()
    }

    private fun enqueue(message: ChatMessage) {
        val target = conversationMetadata[message.conversationId] ?: error("会话未建立。")
        check(target.platformOrigin == currentSettings.platformOrigin) { "会话属于之前使用的穿透平台。" }
        require(message.toJson().toString().toByteArray(Charsets.UTF_8).size <= TunnelChatProtocol.MAX_MESSAGE_BYTES) { "聊天消息过大。" }
        check(queue.enqueue(message, target.remoteEndpointId)) { "本地待发消息队列已满。" }
    }

    private fun nextSequence(conversationId: String): Long = synchronized(sequence) {
        val next = (sequence[conversationId] ?: 0L) + 1L
        sequence[conversationId] = next
        next
    }

    private fun textMessage(conversationId: String, text: String, role: ChatSenderRole, kind: ChatMessageKind): ChatMessage = ChatMessage(
        messageId = UUID.randomUUID().toString(),
        conversationId = conversationId,
        seq = nextSequence(conversationId),
        senderRole = role,
        senderEndpointId = currentSettings.endpointId,
        kind = kind,
        createdAt = System.currentTimeMillis(),
        payload = JSONObject().put("text", text),
    )

    @Synchronized private fun flushQueued(conversationId: String) {
        val binding = sessionBindings.values.firstOrNull { it.conversationId == conversationId } ?: return
        if (!authenticatedSessions.contains(binding.sessionId)) return
        queue.due().filter { it.message.conversationId == conversationId && matchesEndpoint(it, binding.remoteEndpointId) }.forEach { row ->
            val sent = authenticatedSessions.contains(binding.sessionId) && transport?.sendJson(binding.sessionId, TunnelChatProtocol.message(row.message)) == true
            queue.markAttempt(row.message.messageId, delayMs = queue.retryDelayMs(row.attempts))
            if (!sent) publishState("离线消息仍在等待确认。")
        }
    }

    private fun matchesEndpoint(row: TunnelQueuedMessage, remoteEndpointId: String) =
        row.targetEndpointId == remoteEndpointId && row.platformOrigin == currentSettings.platformOrigin &&
            row.message.senderEndpointId == currentSettings.endpointId

    private fun retryQueued() {
        queue.due().groupBy { it.message.conversationId }.forEach { (conversationId, rows) ->
            val binding = sessionBindings[conversationId]
            if (binding != null) {
                flushQueued(conversationId)
            } else {
                val row = rows.first()
                if (row.message.senderRole != ChatSenderRole.VISITOR || !matchesEndpoint(row, row.targetEndpointId)) return@forEach
                rows.forEach { queue.markAttempt(it.message.messageId, delayMs = queue.retryDelayMs(it.attempts)) }
                sequence.merge(conversationId, rows.maxOf { it.message.seq }, ::maxOf)
                runCatching { openConversation(row.targetEndpointId, conversationId) }
            }
        }
    }

    private fun handleSignalingEvent(event: TunnelSignalingEvent) {
        if (!acceptingSignals) return
        when (event) {
            is TunnelSignalingEvent.Registered -> {
                if (event.iceServers.isNotEmpty()) registration = registration?.copy(iceServers = event.iceServers)
                val message = if (currentSettings.publication == TunnelPublication.HIDDEN) "已在线，端点处于隐身模式。" else "公共端点已在线。"
                update(TunnelChatRuntimeState(Status.ONLINE, message, currentSettings.descriptor(online = true)))
            }
            is TunnelSignalingEvent.Message -> handleSignalingMessage(event)
            is TunnelSignalingEvent.Failed -> disconnectSignaling(
                if (event.error is java.io.IOException) "穿透信令连接失败。" else event.error.message ?: "穿透信令失败。",
                event.error is java.io.IOException,
            )
            TunnelSignalingEvent.Closed -> disconnectSignaling("穿透信令已断开。", retry = true)
        }
    }

    private fun disconnectSignaling(message: String, retry: Boolean) {
        acceptingSignals = false
        generation++
        registration = null
        nextRegistrationAttempt = if (retry) System.currentTimeMillis() + 15_000L else Long.MAX_VALUE
        signaling.close()
        sessionBindings.values.filterNot { it.sessionId in authenticatedSessions }.forEach { disconnectSession(it.sessionId) }
        update(TunnelChatRuntimeState(if (retry) Status.OFFLINE else Status.FAILED, message, currentSettings.descriptor(false)))
    }

    private fun handleSignalingMessage(event: TunnelSignalingEvent.Message) {
        if (event.type == "ack" || event.type == "error" || event.type == "session_connected") {
            val sessionId = event.sessionId
            if (event.type == "error") {
                sessionId?.let {
                    if (it !in authenticatedSessions) disconnectSession(it)
                }
                publishState("穿透平台拒绝了信令：${event.payload.optString("message").ifBlank { event.payload.optString("code", "未知错误") }}")
            }
            return
        }
        val sessionId = event.sessionId ?: return
        val source = event.sourceEndpointId ?: return
        if (source == currentSettings.endpointId || source.isBlank()) return
        when (event.type) {
            "offer" -> {
                val offer = event.payload.optString("sdp")
                val conversationId = sessionId
                if (offer.isBlank() || !validId(sessionId) || !validId(conversationId)) return
                val previous = conversationMetadata[conversationId]
                if (previous != null && (previous.remoteEndpointId != source || previous.outgoing || previous.platformOrigin != currentSettings.platformOrigin)) return
                if (sessionBindings.values.any { it.conversationId == conversationId }) {
                    signaling.closeSession(sessionId, source)
                    return
                }
                when (admission.open(conversationId, reconnect = previous?.verified == true)) {
                    is TunnelConversationAdmission.AdmissionResult.Accepted -> {
                        sessionBindings[sessionId] = SessionBinding(sessionId, conversationId, source, outgoing = false)
                        sessionBindings.getValue(sessionId).handshake.offer = offer
                        conversationStates.putIfAbsent(conversationId, TunnelConversationState { currentSettings.maxAiReplies })
                        conversationMetadata.putIfAbsent(conversationId, ConversationMetadata(source, outgoing = false, platformOrigin = currentSettings.platformOrigin))
                        conversationMessages.putIfAbsent(conversationId, CopyOnWriteArrayList())
                        sequence.putIfAbsent(conversationId, 0L)
                        val iceServers = event.payload.optJSONArray("ice_servers")?.let { array ->
                            runCatching { (0 until array.length()).map { index -> TunnelIceServer.fromJson(array.getJSONObject(index)) } }.getOrNull()
                        } ?: registration?.iceServers.orEmpty()
                        runCatching {
                            transportOrThrow().acceptIncoming(sessionId, source, offer, iceServers)
                            expireNegotiation(sessionBindings.getValue(sessionId))
                        }.onFailure { disconnectSession(sessionId, notify = true) }
                        publishState(state.message)
                    }
                    is TunnelConversationAdmission.AdmissionResult.RateLimited -> signaling.closeSession(sessionId, source)
                    is TunnelConversationAdmission.AdmissionResult.Rejected -> signaling.closeSession(sessionId, source)
                }
            }
            "answer" -> if (sessionBindings[sessionId]?.let { it.remoteEndpointId == source && it.outgoing } == true) {
                event.payload.optString("sdp").takeIf { it.isNotBlank() }?.let {
                    val binding = sessionBindings[sessionId] ?: return
                    if (binding.handshake.answer != null) return
                    binding.handshake.answer = it
                    runCatching { transport?.acceptAnswer(sessionId, it) }
                }
            }
            "candidate" -> if (sessionBindings[sessionId]?.remoteEndpointId == source) event.payload.optString("candidate").takeIf { it.isNotBlank() }?.let {
                runCatching { transport?.addRemoteCandidate(sessionId, it, event.payload.optString("sdp_mid").takeIf { value -> value.isNotBlank() }, event.payload.optInt("sdp_mline_index", 0)) }
            }
            "session_close" -> {
                if (sessionBindings[sessionId]?.remoteEndpointId == source) disconnectSession(sessionId)
            }
        }
    }

    private fun handleWebRtcEvent(event: WebRtcChatEvent) {
        val binding = sessionBindings[event.sessionId()] ?: return
        when (event) {
            is WebRtcChatEvent.LocalOffer -> {
                binding.handshake.offer = event.sdp
                if (!signaling.sendOffer(event.sessionId, event.remoteEndpointId, event.sdp)) disconnectSession(event.sessionId)
            }
            is WebRtcChatEvent.LocalAnswer -> {
                binding.handshake.answer = event.sdp
                if (!signaling.sendAnswer(event.sessionId, event.remoteEndpointId, event.sdp)) disconnectSession(event.sessionId)
            }
            is WebRtcChatEvent.LocalCandidate -> signaling.sendCandidate(event.sessionId, event.remoteEndpointId, event.candidate.sdp, event.candidate.sdpMid, event.candidate.sdpMLineIndex)
            is WebRtcChatEvent.Frame -> handleFrame(binding, event)
            is WebRtcChatEvent.StateChanged -> {
                if (event.info.state == WebRtcChatSessionInfo.State.CONNECTED) {
                    if (!sendHello(binding)) disconnectSession(binding.sessionId, notify = true)
                }
                if (event.info.state == WebRtcChatSessionInfo.State.CLOSED || event.info.state == WebRtcChatSessionInfo.State.FAILED) {
                    disconnectSession(event.info.sessionId, notify = true)
                }
            }
            is WebRtcChatEvent.Failed -> {
                disconnectSession(event.sessionId, notify = true)
                publishState("WebRTC 聊天连接失败。")
            }
        }
    }

    private fun handleFrame(binding: SessionBinding, event: WebRtcChatEvent.Frame) {
        if (event.binary) return
        if (event.bytes.size > TunnelChatProtocol.MAX_MESSAGE_BYTES) return
        val frame = runCatching { JSONObject(String(event.bytes, Charsets.UTF_8)) }.getOrNull() ?: return
        val type = runCatching { TunnelChatProtocol.frameType(frame) }.getOrNull() ?: return
        if (binding.sessionId !in authenticatedSessions) {
            if (type == "hello" && sendHello(binding)) {
                val proof = binding.handshake.prove(frame, identity)
                if (proof != null && transport?.sendJson(binding.sessionId, proof) == true) return
            } else if (type == "proof" && binding.handshake.verify(frame, identity)) {
                authenticatedSessions += binding.sessionId
                conversationMetadata[binding.conversationId]?.verified = true
                flushQueued(binding.conversationId)
                publishState(state.message)
                return
            }
            disconnectSession(binding.sessionId, notify = true)
            publishState("远端端点身份验证失败。")
            return
        }
        if (type == "ack") {
            runCatching { ChatAck.fromJson(frame) }.getOrNull()?.takeIf { it.conversationId == binding.conversationId }
                ?.takeIf { it.status == ChatAck.Status.RECEIVED || it.status == ChatAck.Status.DUPLICATE }
                ?.let { queue.acknowledge(it, binding.remoteEndpointId) }
            return
        }
        val message = runCatching { ChatMessage.fromJson(frame) }.getOrNull() ?: return
        if (message.conversationId != binding.conversationId || message.senderEndpointId != binding.remoteEndpointId) return
        val permitted = if (binding.outgoing) {
            (message.senderRole == ChatSenderRole.AI && message.kind in setOf(ChatMessageKind.AI_FORMAL, ChatMessageKind.CONVERSATION_EVENT)) ||
                (message.senderRole == ChatSenderRole.ENDPOINT_USER && message.kind == ChatMessageKind.HUMAN_FORMAL)
        } else message.senderRole == ChatSenderRole.VISITOR && message.kind == ChatMessageKind.TEXT
        if (!permitted) return
        val accepted = deduplicator.accept("${binding.remoteEndpointId}/${binding.conversationId}/${message.messageId}")
        signalingAck(binding, ChatAck(message.messageId, message.conversationId, if (accepted) ChatAck.Status.RECEIVED else ChatAck.Status.DUPLICATE))
        if (!accepted) return
        appendMessage(message, binding.remoteEndpointId)
        if (binding.outgoing) conversationStates[message.conversationId]?.recordFormalMessage(message.kind, message.senderRole)
        if (message.senderRole == ChatSenderRole.VISITOR && !binding.outgoing) scheduleAi(binding, message)
    }

    private fun signalingAck(binding: SessionBinding, ack: ChatAck) {
        transport?.sendJson(binding.sessionId, TunnelChatProtocol.ack(ack))
    }

    private fun update(next: TunnelChatRuntimeState) {
        state = next.copy(activeConversations = sessionBindings.size, conversations = snapshots())
        listeners.forEach { it(state) }
    }

    private fun publishState(message: String) = update(state.copy(message = message))

    private fun appendMessage(message: ChatMessage, remoteEndpointId: String) {
        val rows = conversationMessages.getOrPut(message.conversationId) { CopyOnWriteArrayList() }
        if (rows.none { it.messageId == message.messageId }) {
            rows += message
            if (rows.size > 200) rows.removeAt(0)
        }
        messageListener?.invoke(message, remoteEndpointId)
        publishState(state.message)
    }

    private fun scheduleAi(binding: SessionBinding, message: ChatMessage) {
        val text = message.payload.optString("text").takeIf { it.isNotBlank() } ?: return
        val state = conversationStates[message.conversationId] ?: return
        val formal = state.canGenerateAiFormalReply()
        if (!formal && state.mode == ConversationMode.AI) {
            notifyWaitingHuman(binding, state)
            return
        }
        val responder = aiResponder ?: return
        val suggestion = !formal && state.mode == ConversationMode.HUMAN_HANDOVER
        if (!formal && !suggestion) return
        aiWorker.execute {
            if (!engineReady || sessionBindings[binding.sessionId] !== binding) return@execute
            val request = TunnelChatAiRequest(message.conversationId, binding.remoteEndpointId, text.take(4_000), formal)
            val reply = runCatching { responder(request) }.getOrNull()?.trim().orEmpty()
            if (reply.isBlank()) return@execute
            onWorker {
                if (!engineReady || sessionBindings[binding.sessionId] !== binding) return@onWorker
                runCatching {
                    if (formal && sendAiFormalText(message.conversationId, reply.take(4_000)) != null) return@runCatching
                    if (state.mode == ConversationMode.HUMAN_HANDOVER) {
                        val internal = textMessage(message.conversationId, reply.take(4_000), ChatSenderRole.AI, ChatMessageKind.AI_SUGGESTION)
                        appendMessage(internal, currentSettings.endpointId)
                    }
                }.onFailure { publishState("AI 回复未能加入待发队列。") }
            }
        }
    }

    private fun notifyWaitingHuman(binding: SessionBinding, conversation: TunnelConversationState) {
        if (!conversation.markWaitingHumanIfNeeded()) return
        val event = conversationEvent(binding.conversationId, "waiting_human", "AI 正式回复已达上限，等待人工回复。")
        enqueue(event)
        appendMessage(event, currentSettings.endpointId)
        flushQueued(binding.conversationId)
    }

    private fun conversationEvent(conversationId: String, name: String, text: String): ChatMessage = ChatMessage(
        messageId = UUID.randomUUID().toString(),
        conversationId = conversationId,
        seq = nextSequence(conversationId),
        senderRole = ChatSenderRole.AI,
        senderEndpointId = currentSettings.endpointId,
        kind = ChatMessageKind.CONVERSATION_EVENT,
        createdAt = System.currentTimeMillis(),
        payload = JSONObject().put("event", name).put("text", text),
    )

    private fun snapshots(): List<TunnelChatConversationSnapshot> = conversationStates.keys
        .union(conversationMessages.keys)
        .mapNotNull { conversationId ->
            val meta = conversationMetadata[conversationId] ?: return@mapNotNull null
            val binding = sessionBindings.values.firstOrNull { it.conversationId == conversationId }
            TunnelChatConversationSnapshot(
                conversationId = conversationId,
                remoteEndpointId = meta.remoteEndpointId,
                outgoing = meta.outgoing,
                connected = binding != null && binding.sessionId in authenticatedSessions,
                mode = conversationStates[conversationId]?.mode ?: ConversationMode.AI,
                aiRepliesSent = conversationStates[conversationId]?.aiRepliesSent ?: 0,
                messages = conversationMessages[conversationId]?.toList().orEmpty(),
            )
        }.filter { conversationMetadata[it.conversationId]?.platformOrigin == currentSettings.platformOrigin }.sortedBy { it.conversationId }

    private fun disconnectSession(sessionId: String, notify: Boolean = false) {
        val binding = sessionBindings.remove(sessionId) ?: return
        authenticatedSessions.remove(sessionId)
        admission.close(binding.conversationId)
        if (notify && state.status == Status.ONLINE) signaling.closeSession(sessionId, binding.remoteEndpointId)
        transport?.discardSession(sessionId)
        publishState(state.message)
    }

    private fun expireNegotiation(binding: SessionBinding) {
        retryScheduler.schedule({ onWorker {
            if (sessionBindings[binding.sessionId] === binding && binding.sessionId !in authenticatedSessions) {
                disconnectSession(binding.sessionId, notify = true)
                publishState("聊天连接超时，待发消息将重试。")
            }
        } }, 25, TimeUnit.SECONDS)
    }

    private fun sendHello(binding: SessionBinding): Boolean {
        if (!binding.helloSent) {
            binding.helloSent = transport?.sendJson(binding.sessionId, binding.handshake.hello()) == true
        }
        return binding.helloSent
    }

    private fun transportOrThrow(): WebRtcChatTransport {
        check(acceptingSignals && !closed) { "穿透聊天未在线。" }
        return transport ?: WebRtcChatTransport(context) { event ->
            val binding = sessionBindings[event.sessionId()]
            if (binding != null) onWorker {
                if (sessionBindings[event.sessionId()] === binding) runCatching { handleWebRtcEvent(event) }
                    .onFailure { publishState("聊天连接处理失败。") }
            }
        }.also { transport = it }
    }

    private fun onWorker(action: () -> Unit) = submit { synchronized(this) { if (!closed) action() } }

    private fun submit(action: () -> Unit) {
        if (closed) return
        try { worker.execute(action) } catch (error: RejectedExecutionException) { if (!closed) throw error }
    }

    private fun isCurrent(run: Long): Boolean = run == generation && acceptingSignals && !closed

    private fun validId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))

    private class SessionBinding(val sessionId: String, val conversationId: String, val remoteEndpointId: String, val outgoing: Boolean) {
        val handshake = TunnelPeerHandshake(remoteEndpointId, sessionId, outgoing)
        var helloSent = false
    }
    private class ConversationMetadata(val remoteEndpointId: String, val outgoing: Boolean, val platformOrigin: String) {
        @Volatile var verified = false
    }

    private fun WebRtcChatEvent.sessionId(): String = when (this) {
        is WebRtcChatEvent.LocalOffer -> sessionId
        is WebRtcChatEvent.LocalAnswer -> sessionId
        is WebRtcChatEvent.LocalCandidate -> sessionId
        is WebRtcChatEvent.StateChanged -> info.sessionId
        is WebRtcChatEvent.Frame -> sessionId
        is WebRtcChatEvent.Failed -> sessionId
    }

    companion object {
        private fun initialState(settings: TunnelChatSettings) = TunnelChatRuntimeState(
            if (settings.enabled) Status.OFFLINE else Status.DISABLED,
            if (settings.enabled) "等待穿透聊天启动。" else "穿透聊天未开启。",
            settings.descriptor(online = false),
        )
    }
}
