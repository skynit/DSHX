package local.dshx.app

import android.content.Context
import org.json.JSONArray
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
    val pendingWithdrawal: Boolean = false,
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
    private val conversationMessages = ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>>()
    private val deduplicator = TunnelMessageDeduplicator()
    private val admission = TunnelConversationAdmission(
        settings = { currentSettings },
        loadLastAcceptedAt = settingsStore::lastAcceptedAt,
        saveLastAcceptedAt = settingsStore::saveLastAcceptedAt,
    )
    private val aiWorker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(16), { task ->
        Thread(task, "dshx-tunnel-ai").apply { isDaemon = true }
    }, ThreadPoolExecutor.AbortPolicy())
    private val retryScheduler = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "dshx-tunnel-retry").apply { isDaemon = true } }
    @Volatile private var registration: TunnelPlatformRegistration? = null
    private val identity by lazy { AndroidTunnelEndpointIdentity() }
    private var generation = 0L
    @Volatile private var closed = false
    @Volatile private var engineReady = false
    @Volatile private var nextRegistrationAttempt = Long.MAX_VALUE
    @Volatile private var acceptingSignals = false
    @Volatile private var currentSettings = settingsStore.read()
    private var publishedOrigins = settingsStore.publishedOrigins()
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
    private val records by lazy { queue.conversations().associateBy { key(it.id, it.platformOrigin, it.localEndpointId) }.toMutableMap() }

    init {
        if (withdrawals().isNotEmpty()) {
            state = state.copy(pendingWithdrawal = true)
            submit { syncWithdrawals() }
        }
        retryScheduler.scheduleAtFixedRate({
            submit {
                syncWithdrawals()
                synchronized(this) {
                    if (closed) return@synchronized
                runCatching {
                    if (engineReady && System.currentTimeMillis() >= nextRegistrationAttempt) start()
                    if (state.status == Status.ONLINE) retryQueued()
                }.onFailure { publishState("本地待发消息读取或重试失败。") }
                }
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
        val origins = if (previous.enabled && previous.publication == TunnelPublication.PUBLISHED) {
            publishedOrigins + previous.platformOrigin
        } else publishedOrigins
        currentSettings = settingsStore.save(clean.copy(endpointId = previous.endpointId), origins)
        publishedOrigins = origins
        if (previous.platformOrigin != clean.platformOrigin) conversationMessages.clear()
        if (!clean.enabled) stop()
        else if (previous != currentSettings && engineReady) {
            if (previous.platformOrigin != clean.platformOrigin || clean.publication == TunnelPublication.HIDDEN) stop()
            else disconnectSignaling("正在应用端点设置…", retry = false)
            if (withdrawals().isNotEmpty()) submit { syncWithdrawals() }
            start()
        } else if (!engineReady) update(initialState(clean))
        if ((!clean.enabled || !engineReady) && withdrawals().isNotEmpty()) submit { syncWithdrawals() }
        return currentSettings
    }

    private fun withdrawals() = publishedOrigins.filterNot {
        currentSettings.enabled && currentSettings.publication == TunnelPublication.PUBLISHED && it == currentSettings.platformOrigin
    }

    // 与公开注册使用同一个工作线程。先登记可能公开的地址，再发请求；响应丢失也不能漏掉撤下任务。
    private fun syncWithdrawals() {
        val origins = synchronized(this) { if (closed) return; withdrawals() }
        for (origin in origins) {
            val settings = synchronized(this) {
                if (closed || origin !in withdrawals()) return@synchronized null
                currentSettings.copy(platformOrigin = origin, endpointId = identity.endpointId, publication = TunnelPublication.HIDDEN)
            } ?: continue
            runCatching { TunnelPlatformClient(origin).updateEndpoint(settings, identity) }.onSuccess {
                synchronized(this) {
                    if (closed) return
                    val remaining = publishedOrigins - origin
                    settingsStore.save(currentSettings, remaining)
                    publishedOrigins = remaining
                    update(state)
                }
            }
        }
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
                    settingsWithIdentity().also { settings ->
                        if (settings.publication == TunnelPublication.PUBLISHED) {
                            val origins = publishedOrigins + settings.platformOrigin
                            settingsStore.save(settings, origins)
                            publishedOrigins = origins
                        }
                    }
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
            currentSettings = settingsStore.save(currentSettings.copy(endpointId = endpointId), publishedOrigins)
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

    private fun openConversation(remoteEndpointId: String): String = prepareConversation(remoteEndpointId).also { connect(record(it)!!) }

    private fun connect(conversation: TunnelConversation) {
        require(state.status == Status.ONLINE) { "端点尚未在线，暂不能发起聊天。" }
        require(conversation.visitor || conversation.verified) { "访客身份尚未验证。" }
        if (binding(conversation.id) != null) return
        val current = registration ?: throw IllegalStateException("端点尚未向平台注册。")
        val sessionId = if (conversation.verified) UUID.randomUUID().toString() else conversation.id
        val attempt = SessionBinding(sessionId, conversation, initiator = true)
        sessionBindings[sessionId] = attempt
        try {
            transportOrThrow().createOutgoing(sessionId, conversation.remoteEndpointId, current.iceServers)
            expireNegotiation(attempt)
        } catch (error: Throwable) {
            disconnectSession(sessionId)
            throw error
        }
        publishState(state.message)
    }

    @Synchronized fun reconnectConversation(conversationId: String) {
        connect(requireOpen(conversationId))
    }

    private fun prepareConversation(remoteEndpointId: String): String {
        settingsWithIdentity()
        require(validId(remoteEndpointId) && remoteEndpointId != currentSettings.endpointId) { "目标端点无效。" }
        val id = UUID.randomUUID().toString()
        save(newConversation(id, remoteEndpointId, visitor = true))
        publishState(state.message)
        return id
    }

    @Synchronized fun sendText(conversationId: String, text: String): ChatMessage {
        val conversation = requireOpen(conversationId)
        require(conversation.visitor) { "访客会话尚未建立。" }
        val message = textMessage(conversation, text, ChatSenderRole.VISITOR, ChatMessageKind.TEXT)
        enqueue(message, conversation)
        appendMessage(message, currentSettings.endpointId)
        flushQueued(conversationId)
        return message
    }

    private fun sendAiFormalText(conversation: TunnelConversation, text: String) {
        val message = textMessage(conversation, text, ChatSenderRole.AI, ChatMessageKind.AI_FORMAL)
        val next = conversation.copy(aiRepliesSent = conversation.aiRepliesSent + 1)
        enqueue(message, next)
        appendMessage(message, currentSettings.endpointId)
        flushQueued(conversation.id)
        notifyWaitingHuman(next)
    }

    @Synchronized fun sendHumanFormalText(conversationId: String, text: String): ChatMessage {
        val conversation = requireOpen(conversationId)
        require(conversation.verified && !conversation.visitor) { "访客身份尚未验证。" }
        val message = textMessage(conversation, text, ChatSenderRole.ENDPOINT_USER, ChatMessageKind.HUMAN_FORMAL)
        enqueue(message, conversation.copy(mode = ConversationMode.HUMAN_HANDOVER))
        appendMessage(message, currentSettings.endpointId)
        flushQueued(conversationId)
        return message
    }

    @Synchronized fun resumeAi(conversationId: String) {
        val conversation = requireOpen(conversationId)
        require(conversation.verified && !conversation.visitor) { "不能修改对方的接待模式。" }
        save(conversation.copy(mode = ConversationMode.AI, waitingNotified = false))
        publishState(state.message)
    }

    @Synchronized fun endConversation(conversationId: String) {
        val conversation = requireOpen(conversationId)
        val ended = conversation.copy(closed = true)
        if (conversation.verified) {
            val role = if (conversation.visitor) ChatSenderRole.VISITOR else ChatSenderRole.ENDPOINT_USER
            val message = textMessage(conversation, "会话已结束。", role, ChatMessageKind.CONVERSATION_EVENT)
            message.payload.put("event", "ended")
            if (!enqueue(message, ended, allowFull = true)) {
                save(ended)
                binding(conversationId)?.let { disconnectSession(it.sessionId, notify = true) }
                publishState("会话已在本地结束；待发队列已满，未能通知对方。")
                return
            }
            flushQueued(conversationId)
            binding(conversationId)?.let { expireClosing(it) }
        } else {
            save(ended)
            binding(conversationId)?.let { disconnectSession(it.sessionId, notify = true) }
        }
        publishState(state.message)
    }

    @Synchronized override fun close() {
        closed = true
        stop()
        worker.shutdownNow()
        aiWorker.shutdownNow()
        retryScheduler.shutdownNow()
    }

    private fun enqueue(message: ChatMessage, conversation: TunnelConversation, allowFull: Boolean = false): Boolean {
        require(message.toJson().toString().toByteArray(Charsets.UTF_8).size <= TunnelChatProtocol.MAX_MESSAGE_BYTES) { "聊天消息过大。" }
        val next = conversation.copy(sequence = message.seq)
        if (!queue.enqueue(message, next.remoteEndpointId, conversation = next)) {
            check(allowFull) { "本地待发消息队列已满。" }
            return false
        }
        records[key(next.id)] = next
        return true
    }

    private fun key(id: String, origin: String = currentSettings.platformOrigin, local: String = currentSettings.endpointId) =
        Triple(origin, local, id)

    private fun record(id: String) = records[key(id)]

    private fun save(conversation: TunnelConversation) {
        queue.save(conversation)
        records[key(conversation.id)] = conversation
    }

    private fun newConversation(id: String, remote: String, visitor: Boolean) =
        TunnelConversation(id, currentSettings.platformOrigin, currentSettings.endpointId, remote, visitor)

    private fun binding(id: String) = sessionBindings.values.firstOrNull { it.conversationId == id }

    private fun requireOpen(id: String): TunnelConversation = record(id)
        ?.takeIf { currentSettings.enabled && !it.closed } ?: error("聊天会话不可用或已结束。")

    private fun textMessage(conversation: TunnelConversation, text: String, role: ChatSenderRole, kind: ChatMessageKind): ChatMessage = ChatMessage(
        messageId = UUID.randomUUID().toString(),
        conversationId = conversation.id,
        seq = conversation.sequence + 1,
        senderRole = role,
        senderEndpointId = currentSettings.endpointId,
        kind = kind,
        createdAt = System.currentTimeMillis(),
        payload = JSONObject().put("text", text),
    )

    @Synchronized private fun flushQueued(conversationId: String) {
        val binding = binding(conversationId) ?: return
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
            val row = rows.firstOrNull { matchesEndpoint(it, it.targetEndpointId) } ?: return@forEach
            val conversation = record(conversationId) ?: run {
                // 旧版待发访客文本没有会话记录。无法恢复旧版接待状态，不为旧版 AI 正式回复重建状态。
                if (row.message.senderRole != ChatSenderRole.VISITOR || row.message.kind != ChatMessageKind.TEXT) return@forEach
                newConversation(conversationId, row.targetEndpointId, visitor = true)
                    .copy(sequence = rows.filter { matchesEndpoint(it, row.targetEndpointId) }.maxOf { it.message.seq }).also { save(it) }
            }
            if (conversation.remoteEndpointId != row.targetEndpointId || conversation.closed && !row.message.isEnd()) return@forEach
            val binding = binding(conversationId)
            if (binding != null) {
                flushQueued(conversationId)
            } else {
                rows.filter { matchesEndpoint(it, conversation.remoteEndpointId) }.forEach {
                    queue.markAttempt(it.message.messageId, delayMs = queue.retryDelayMs(it.attempts))
                }
                runCatching { connect(conversation) }
            }
        }
    }

    private fun handleSignalingEvent(event: TunnelSignalingEvent) {
        if (!acceptingSignals) return
        when (event) {
            is TunnelSignalingEvent.Registered -> {
                if (event.iceServers.isNotEmpty()) registration = registration?.copy(iceServers = event.iceServers)
                val message = when {
                    withdrawals().isNotEmpty() -> "正在确认撤下旧平台上的公开端点…"
                    currentSettings.publication == TunnelPublication.HIDDEN -> "已在线，端点处于隐身模式。"
                    else -> "公共端点已在线。"
                }
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
                val conversationId = event.payload.optString("conversation_id", sessionId)
                val reconnect = conversationId != sessionId
                val previous = record(conversationId)
                if (offer.isBlank() || !validId(sessionId) || !validId(conversationId) ||
                    reconnect && previous?.verified != true || previous?.verified == true && !reconnect || previous != null &&
                    (previous.remoteEndpointId != source || !reconnect && (previous.visitor || previous.closed))) {
                    signaling.closeSession(sessionId, source)
                    return
                }
                val existing = binding(conversationId)
                if (existing != null) {
                    // 双方同时重连时，编号较小的一方保留自己发出的 offer。
                    if (!existing.initiator || existing.sessionId in authenticatedSessions || currentSettings.endpointId < source) {
                        if (existing.sessionId != sessionId) signaling.closeSession(sessionId, source)
                        return
                    }
                    disconnectSession(existing.sessionId, notify = true)
                }
                when (admission.open(conversationId, reconnect = reconnect)) {
                    is TunnelConversationAdmission.AdmissionResult.Accepted -> {
                        val conversation = previous ?: newConversation(conversationId, source, visitor = false)
                        val attempt = SessionBinding(sessionId, conversation, initiator = false)
                        attempt.handshake.offer = offer
                        sessionBindings[sessionId] = attempt
                        val iceServers = event.payload.optJSONArray("ice_servers")?.let { array ->
                            runCatching { (0 until array.length()).map { index -> TunnelIceServer.fromJson(array.getJSONObject(index)) } }.getOrNull()
                        } ?: registration?.iceServers.orEmpty()
                        runCatching {
                            transportOrThrow().acceptIncoming(sessionId, source, offer, iceServers)
                            expireNegotiation(attempt)
                        }.onFailure { disconnectSession(sessionId, notify = true) }
                        publishState(state.message)
                    }
                    is TunnelConversationAdmission.AdmissionResult.RateLimited -> signaling.closeSession(sessionId, source)
                    is TunnelConversationAdmission.AdmissionResult.Rejected -> signaling.closeSession(sessionId, source)
                }
            }
            "answer" -> if (sessionBindings[sessionId]?.let { it.remoteEndpointId == source && it.initiator } == true) {
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
                if (!signaling.sendOffer(event.sessionId, event.remoteEndpointId, event.sdp, binding.conversationId)) disconnectSession(event.sessionId)
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
                val conversation = record(binding.conversationId) ?: binding.conversation
                if (!conversation.verified) save(conversation.copy(verified = true))
                authenticatedSessions += binding.sessionId
                flushQueued(binding.conversationId)
                if (conversation.closed) expireClosing(binding)
                publishState(state.message)
                return
            }
            disconnectSession(binding.sessionId, notify = true)
            publishState("远端端点身份验证失败。")
            return
        }
        val conversation = record(binding.conversationId) ?: return
        if (type == "ack") {
            runCatching { ChatAck.fromJson(frame) }.getOrNull()?.takeIf { it.conversationId == binding.conversationId }
                ?.takeIf { it.status == ChatAck.Status.RECEIVED || it.status == ChatAck.Status.DUPLICATE }
                ?.let {
                    queue.acknowledge(it, binding.remoteEndpointId)
                    if (conversation.closed && queue.snapshot().none { row -> row.message.conversationId == conversation.id &&
                            matchesEndpoint(row, binding.remoteEndpointId) && row.message.isEnd() }) {
                        disconnectSession(binding.sessionId, notify = true)
                    }
                }
            return
        }
        val message = runCatching { ChatMessage.fromJson(frame) }.getOrNull() ?: return
        if (message.conversationId != binding.conversationId || message.senderEndpointId != binding.remoteEndpointId) return
        if (message.isEnd()) {
            val role = if (conversation.visitor) ChatSenderRole.ENDPOINT_USER else ChatSenderRole.VISITOR
            if (message.senderRole != role) return
            if (!conversation.closed) {
                save(conversation.copy(closed = true, receivedMessageIds = rememberMessage(conversation, message.messageId)))
                appendMessage(message, binding.remoteEndpointId)
            }
            signalingAck(binding, ChatAck(message.messageId, message.conversationId, ChatAck.Status.RECEIVED))
            expireClosing(binding)
            return
        }
        if (conversation.closed) return
        val permitted = if (conversation.visitor) {
            (message.senderRole == ChatSenderRole.AI && message.kind in setOf(ChatMessageKind.AI_FORMAL, ChatMessageKind.CONVERSATION_EVENT)) ||
                (message.senderRole == ChatSenderRole.ENDPOINT_USER && message.kind == ChatMessageKind.HUMAN_FORMAL)
        } else message.senderRole == ChatSenderRole.VISITOR && message.kind == ChatMessageKind.TEXT
        if (!permitted) return
        if (message.messageId in conversation.receivedMessageIds) {
            signalingAck(binding, ChatAck(message.messageId, message.conversationId, ChatAck.Status.DUPLICATE))
            return
        }
        val accepted = deduplicator.accept("${binding.remoteEndpointId}/${binding.conversationId}/${message.messageId}")
        if (!accepted) {
            signalingAck(binding, ChatAck(message.messageId, message.conversationId, ChatAck.Status.DUPLICATE))
            return
        }
        val nextConversation = conversation.copy(
            mode = if (conversation.visitor && message.kind == ChatMessageKind.HUMAN_FORMAL) ConversationMode.HUMAN_HANDOVER else conversation.mode,
            receivedMessageIds = rememberMessage(conversation, message.messageId),
        )
        save(nextConversation)
        signalingAck(binding, ChatAck(message.messageId, message.conversationId, ChatAck.Status.RECEIVED))
        appendMessage(message, binding.remoteEndpointId)
        if (message.senderRole == ChatSenderRole.VISITOR && !conversation.visitor) scheduleAi(nextConversation, message)
    }

    private fun signalingAck(binding: SessionBinding, ack: ChatAck) {
        transport?.sendJson(binding.sessionId, TunnelChatProtocol.ack(ack))
    }

    private fun update(next: TunnelChatRuntimeState) {
        val pending = withdrawals().isNotEmpty()
        val endpoint = next.endpoint?.let {
            if (pending && currentSettings.publication == TunnelPublication.HIDDEN) {
                it.copy(publication = TunnelPublication.PUBLISHED, online = false)
            } else it
        }
        state = next.copy(activeConversations = sessionBindings.size, conversations = snapshots(),
            endpoint = endpoint, pendingWithdrawal = pending)
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

    private fun rememberMessage(conversation: TunnelConversation, messageId: String): List<String> =
        (conversation.receivedMessageIds + messageId).takeLast(10_000)

    private fun TunnelConversation.canReply() = canReply(currentSettings.maxAiReplies)

    private fun scheduleAi(conversation: TunnelConversation, message: ChatMessage) {
        val text = message.payload.optString("text").takeIf { it.isNotBlank() } ?: return
        val responder = aiResponder ?: return
        val scope = key(conversation.id)
        val sessionKey = UUID.nameUUIDFromBytes(JSONArray(listOf(conversation.platformOrigin, conversation.localEndpointId,
            conversation.remoteEndpointId, conversation.id)).toString().toByteArray(Charsets.UTF_8)).toString()
        try {
            aiWorker.execute {
                val reception = synchronized(this) {
                    if (!engineReady || !currentSettings.enabled || key(conversation.id) != scope) return@execute
                    val live = record(conversation.id)?.takeUnless { it.closed } ?: return@execute
                    if (!live.canReply() && live.mode == ConversationMode.AI) {
                        onWorker { record(conversation.id)?.takeIf { key(it.id) == scope }?.let(::notifyWaitingHuman) }
                        return@execute
                    }
                    live.mode
                }
                val formal = reception == ConversationMode.AI
                val request = TunnelChatAiRequest(sessionKey, conversation.remoteEndpointId, text.take(4_000), formal)
                val reply = runCatching { responder(request) }.getOrNull()?.trim().orEmpty()
                onWorker {
                    if (!engineReady || !currentSettings.enabled || key(conversation.id) != scope) return@onWorker
                    val live = record(conversation.id)?.takeUnless { it.closed } ?: return@onWorker
                    if (reply.isBlank()) {
                        publishState(if (formal) "AI 回复失败，端点用户仍可回复。" else "未能生成 AI 内部建议。")
                        return@onWorker
                    }
                    runCatching {
                        if (formal && live.canReply()) sendAiFormalText(live, reply.take(4_000))
                        else if (live.mode == ConversationMode.HUMAN_HANDOVER) {
                            val internal = textMessage(live, reply.take(4_000), ChatSenderRole.AI, ChatMessageKind.AI_SUGGESTION)
                            appendMessage(internal, currentSettings.endpointId)
                        }
                    }.onFailure { publishState("AI 回复未能加入待发队列。") }
                }
            }
        } catch (_: RejectedExecutionException) {
            publishState("AI 接待繁忙，端点用户仍可回复。")
        }
    }

    private fun notifyWaitingHuman(conversation: TunnelConversation) {
        if (conversation.closed || conversation.mode != ConversationMode.AI || conversation.canReply() || conversation.waitingNotified) return
        val message = textMessage(conversation, "AI 正式回复已达上限，等待人工回复。", ChatSenderRole.AI, ChatMessageKind.CONVERSATION_EVENT)
        message.payload.put("event", "waiting_human")
        if (enqueue(message, conversation.copy(waitingNotified = true), allowFull = true)) {
            appendMessage(message, currentSettings.endpointId)
            flushQueued(conversation.id)
        }
    }

    private fun snapshots(): List<TunnelChatConversationSnapshot> = (if (!currentSettings.enabled) emptyList() else records.values
        .filter { it.platformOrigin == currentSettings.platformOrigin && it.localEndpointId == currentSettings.endpointId })
        .map { conversation ->
            val binding = binding(conversation.id)
            val connected = binding != null && binding.sessionId in authenticatedSessions
            TunnelChatConversationSnapshot(
                conversationId = conversation.id,
                remoteEndpointId = conversation.remoteEndpointId,
                outgoing = conversation.visitor,
                connected = !conversation.closed && connected,
                connecting = !conversation.closed && binding != null && !connected,
                closed = conversation.closed,
                mode = conversation.mode,
                aiRepliesSent = conversation.aiRepliesSent,
                messages = conversationMessages[conversation.id]?.toList().orEmpty(),
            )
        }.sortedBy { it.conversationId }

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

    private fun expireClosing(binding: SessionBinding) {
        if (binding.closing) return
        binding.closing = true
        retryScheduler.schedule({ onWorker {
            if (sessionBindings[binding.sessionId] === binding) disconnectSession(binding.sessionId, notify = true)
        } }, 10, TimeUnit.SECONDS)
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

    private class SessionBinding(val sessionId: String, val conversation: TunnelConversation, val initiator: Boolean) {
        val conversationId get() = conversation.id
        val remoteEndpointId get() = conversation.remoteEndpointId
        val handshake = TunnelPeerHandshake(remoteEndpointId, sessionId, initiator, conversationId)
        var helloSent = false
        var closing = false
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
