package local.dshx.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import chatquic.Chatquic
import chatquic.Peer
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.random.Random

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

private enum class SignalRetry { FAST, SLOW, NONE }

// 网络故障时逐步延长等待；加少量随机偏移，避免平台恢复时端点同时拨号。
internal fun signalRetryDelayMs(attempt: Int): Long {
    val ceiling = minOf(60_000L, 1_000L shl attempt.coerceIn(0, 6))
    return Random.nextLong(ceiling * 3 / 4, ceiling + 1)
}

class TunnelChatRuntime(
    private val context: Context,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    identityFactory: () -> TunnelEndpointIdentity = ::AndroidTunnelEndpointIdentity,
    private val http: OkHttpClient = tunnelHttpClient(),
) : AutoCloseable {
    private val settingsStore = TunnelChatSettingsStore(context)
    private val listeners = CopyOnWriteArraySet<(TunnelChatRuntimeState) -> Unit>()
    private val signaling = TunnelSignalingClient(http)
    private val sessionBindings = ConcurrentHashMap<String, SessionBinding>()
    private val authenticatedSessions = ConcurrentHashMap.newKeySet<String>()
    private val conversationMessages = mutableMapOf<String, MutableList<ChatMessage>>()
    private val attachmentFiles = TunnelAttachmentFiles(File(context.noBackupFilesDir, "tunnel-images"))
    private val attachmentDeliveries = mutableMapOf<Triple<String, String, String>, TunnelAttachmentDelivery>()
    private var preparingAttachment = false
    private val retryInitialOffer = mutableSetOf<String>()
    private val admission = TunnelConversationAdmission(
        settings = { currentSettings },
        loadLastAcceptedAt = settingsStore::lastAcceptedAt,
        saveLastAcceptedAt = settingsStore::saveLastAcceptedAt,
    )
    private val aiWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dshx-tunnel-ai").apply { isDaemon = true }
    }
    private var receiving = false
    private var receptionEpoch = 0L
    private var pendingReply: ReceptionReply? = null
    private val retryScheduler = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "dshx-tunnel-retry").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var registrationRetry: ScheduledFuture<*>? = null
    private var registrationTimeout: ScheduledFuture<*>? = null
    private var registrationFailures = 0
    @Volatile private var registration: TunnelPlatformRegistration? = null
    private val identity by lazy(identityFactory)
    internal val webShares = WebShares(context) { port -> (context.applicationContext as? DshApp)?.engine?.ownsLocalPort(port) == true }
    private var web: WebShareServer? = null
    private var generation = 0L
    @Volatile private var closed = false
    @Volatile private var engineReady = false
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

    init {
        webShares.watchNetwork { onWorker { web?.checkTargets(); updateWebCapability() } }
        submit { synchronized(this) { attachmentFiles.trim(discardParts = true) } }
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
                        if (state.status == Status.ONLINE || authenticatedSessions.isNotEmpty()) retryQueued()
                        web?.checkTargets()
                        sessionBindings.values.forEach {
                            if (it.sessionId in authenticatedSessions && !it.closing) sampleLink(it)
                        }
                        pumpReception()
                    }.onFailure { publishState("本地聊天记录读取或重试失败。") }
                }
            }
        }, 5L, 5L, TimeUnit.SECONDS)
        retryScheduler.scheduleAtFixedRate({ onWorker { attachmentFiles.trim() } }, 1L, 1L, TimeUnit.HOURS)
    }

    fun observe(listener: (TunnelChatRuntimeState) -> Unit) { listeners.add(listener); listener(state) }

    fun remove(listener: (TunnelChatRuntimeState) -> Unit) { listeners.remove(listener) }

    fun setMessageListener(listener: ((ChatMessage, String) -> Unit)?) { messageListener = listener }

    fun setAiResponder(responder: ((TunnelChatAiRequest) -> String?)?) {
        aiResponder = responder
        if (responder != null) onWorker { pumpReception() }
    }

    fun settings(): TunnelChatSettings = currentSettings

    internal val webPlatformAvailable: Boolean get() = registration?.webAvailable == true

    @Synchronized internal fun saveWebShare(id: String?, name: String, address: String, enabled: Boolean): WebShare {
        val item = webShares.save(id, name, address, enabled)
        if (id != null) web?.revoke(id)
        updateWebCapability()
        return item
    }

    @Synchronized internal fun deleteWebShare(id: String) {
        webShares.delete(id)
        web?.revoke(id)
        updateWebCapability()
    }

    private fun updateWebCapability() {
        if (!webPlatformAvailable || state.status != Status.ONLINE) return
        signaling.publishWebStatus(webShares.publicList().isNotEmpty())
    }

    @Synchronized fun saveSettings(settings: TunnelChatSettings): TunnelChatSettings {
        val clean = settings.validate()
        val previous = currentSettings
        val origins = if (previous.enabled && previous.publication == TunnelPublication.PUBLISHED) {
            publishedOrigins + previous.platformOrigin
        } else publishedOrigins
        currentSettings = settingsStore.save(clean.copy(endpointId = previous.endpointId), origins)
        publishedOrigins = origins
        if (previous.platformOrigin != clean.platformOrigin) {
            conversationMessages.clear()
            attachmentDeliveries.clear()
        }
        if (!clean.enabled) stop()
        else if (previous != currentSettings && engineReady) {
            registrationFailures = 0
            if (previous.platformOrigin != clean.platformOrigin || clean.publication == TunnelPublication.HIDDEN) stop()
            else disconnectSignaling("正在应用端点设置…", SignalRetry.NONE)
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
        if (ready) {
            start()
            onWorker { pumpReception() }
        } else stop()
    }

    @Synchronized private fun start() {
        if (closed || !engineReady || acceptingSignals) return
        if (!currentSettings.enabled) {
            update(initialState(currentSettings))
            return
        }
        cancelRegistrationTimers()
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
                val platform = TunnelPlatformClient(clean.platformOrigin, http)
                platform.registerEndpoint(clean, identity, webShare = webShares.publicList().isNotEmpty())
            }
            synchronized(this) {
                if (!isCurrent(run)) return@submit
                runCatching {
                    val result = registered.getOrThrow()
                    registration = result
                    signaling.connect(result.signalingUrl, currentSettings, identity, result.registrationToken) { event ->
                        onWorker { if (isCurrent(run)) handleSignalingEvent(event) }
                    }
                    registrationTimeout = retryScheduler.schedule({ onWorker {
                        if (isCurrent(run) && state.status == Status.STARTING) {
                            disconnectSignaling("等待信令注册超时。", SignalRetry.FAST)
                        }
                    } }, 25, TimeUnit.SECONDS)
                }.onFailure { error ->
                    val retry = if ((error as? TunnelPlatformException)?.retryable == true) SignalRetry.FAST else SignalRetry.NONE
                    disconnectSignaling(error.message ?: "穿透平台注册失败。", retry)
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
        receptionEpoch++
        pendingReply = null
        acceptingSignals = false
        cancelRegistrationTimers()
        registrationFailures = 0
        web?.close()
        web = null
        signaling.close()
        sessionBindings.values.forEach { it.close() }
        sessionBindings.clear()
        authenticatedSessions.clear()
        retryInitialOffer.clear()
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
        val sessionId = if (conversation.needsInitialOffer(conversation.id in retryInitialOffer)) conversation.id
            else UUID.randomUUID().toString()
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

    private fun sendAiFormalText(conversation: TunnelConversation, receipt: TunnelReceipt, message: ChatMessage): Boolean {
        val next = conversation.copy(aiRepliesSent = conversation.aiRepliesSent + 1)
        if (!enqueue(message, next, allowFull = true, receptionId = receipt.messageId)) return false
        appendMessage(message, currentSettings.endpointId)
        flushQueued(conversation.id)
        record(conversation.id)?.let(::notifyWaitingHuman)
        return true
    }

    @Synchronized fun sendHumanFormalText(conversationId: String, text: String): ChatMessage {
        val conversation = requireOpen(conversationId)
        require(conversation.verified && !conversation.visitor) { "访客身份尚未验证。" }
        val message = textMessage(conversation, text, ChatSenderRole.ENDPOINT_USER, ChatMessageKind.HUMAN_FORMAL)
        val queuedAi = queue.snapshot().filter { it.message.conversationId == conversationId &&
            it.message.kind == ChatMessageKind.AI_FORMAL && matchesEndpoint(it, conversation.remoteEndpointId) }.map { it.message.messageId }.toSet()
        enqueue(message, conversation.handover())
        conversationMessages[conversationId]?.removeAll { it.senderEndpointId == conversation.localEndpointId && it.messageId in queuedAi }
        appendMessage(message, currentSettings.endpointId)
        flushQueued(conversationId)
        return message
    }

    fun sendAttachment(conversationId: String, uri: Uri, retryId: String? = null): String {
        require(uri.scheme == "content") { "请重新选择附件。" }
        val (original, id) = synchronized(this) {
            check(!preparingAttachment) { "正在读取另一个附件。" }
            val binding = requireAttachmentSession(conversationId)
            val last = attachmentDeliveries[key(conversationId)]
            val id = retryId?.takeIf { last?.messageId == it && !last.delivered } ?: UUID.randomUUID().toString()
            preparingAttachment = true
            binding to id
        }
        try {
            val resolver = context.contentResolver
            val (temp, attachment) = try {
                val name = resolver.attachmentName(uri)
                val type = resolver.getType(uri)
                val input = resolver.openInputStream(uri) ?: error("无法读取所选附件，请重新选择。")
                input.use { attachmentFiles.prepare(it, id, name, type) }
            } catch (_: java.io.IOException) { error("无法读取或保存所选附件，请重试。") }
            try {
                synchronized(this) {
                    val conversation = requireOpen(conversationId)
                    val binding = requireAttachmentSession(conversationId)
                    check(binding === original) { "聊天连接已变更，请重试。" }
                    val role = if (conversation.visitor) ChatSenderRole.VISITOR else ChatSenderRole.ENDPOINT_USER
                    val kind = if (attachment.mimeType in tunnelImageTypes) ChatMessageKind.IMAGE else ChatMessageKind.FILE
                    val message = ChatMessage(id, conversationId, conversation.sequence + 1, role, conversation.localEndpointId,
                        kind, System.currentTimeMillis(), payload = JSONObject().put("attachment", attachment.toJson()))
                    val file = attachmentFiles.stage(conversation, message, temp)
                    try {
                        save(conversation.copy(sequence = message.seq))
                        attachmentDeliveries.remove(key(conversationId))
                        binding.sendingAttachment = message
                        if (binding.quic?.sendAttachment(message.messageId, message.toJson().toString(), file.absolutePath) != true) {
                            binding.sendingAttachment = null
                            finishAttachment(binding, message, false)
                        }
                    } catch (error: Exception) {
                        binding.sendingAttachment = null
                        file.delete()
                        throw error
                    }
                    publishState(state.message)
                }
            } finally { temp.delete() }
        } finally { synchronized(this) { preparingAttachment = false } }
        return id
    }

    fun imagePreview(message: ChatMessage): Bitmap? {
        if (message.kind != ChatMessageKind.IMAGE) return null
        val file = synchronized(this) {
            record(message.conversationId)?.takeIf { message.senderEndpointId == it.localEndpointId || message.senderEndpointId == it.remoteEndpointId }
                ?.let { attachmentFiles.file(it, message) }
        } ?: return null
        return TunnelAttachmentFiles.preview(file)
    }

    fun exportAttachment(conversationId: String, messageId: String, senderEndpointId: String, uri: Uri) {
        require(uri.scheme == "content") { "请选择保存位置。" }
        val file = synchronized(this) {
            val chat = checkNotNull(record(conversationId)) { "聊天记录不可用。" }
            val history = if (senderEndpointId == chat.localEndpointId) chat.sentAttachments else chat.inbox.mapNotNull { it.message }
            val message = history.firstOrNull {
                it.messageId == messageId && it.senderEndpointId == senderEndpointId && it.kind == ChatMessageKind.FILE
            } ?: error("文件不可用或已过期。")
            attachmentFiles.file(chat, message)
        }
        try {
            file.inputStream().use { input ->
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置。")
                output.use { input.copyTo(it) }
            }
        } catch (_: java.io.FileNotFoundException) {
            error("文件不可用，或无法写入所选位置。")
        } catch (_: java.io.IOException) {
            error("无法保存文件，请重试。")
        }
    }

    private fun requireAttachmentSession(id: String): SessionBinding {
        requireOpen(id)
        val binding = checkNotNull(binding(id)?.takeIf { it.sessionId in authenticatedSessions }) { "连接后才能发送附件。" }
        check(binding.sendingAttachment == null) { "上一个附件还在发送。" }
        return binding
    }

    @Synchronized fun resumeAi(conversationId: String) {
        val conversation = requireOpen(conversationId)
        require(conversation.verified && !conversation.visitor) { "不能修改对方的接待模式。" }
        save(conversation.resumeAi())
        publishState(state.message)
    }

    @Synchronized fun endConversation(conversationId: String) {
        val conversation = requireOpen(conversationId)
        binding(conversationId)?.quic?.cancelAttachment()
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
        webShares.close()
        worker.shutdownNow()
        aiWorker.shutdownNow()
        retryScheduler.shutdownNow()
    }

    private fun enqueue(message: ChatMessage, conversation: TunnelConversation, allowFull: Boolean = false, receptionId: String? = null): Boolean {
        require(message.toJson().toString().toByteArray(Charsets.UTF_8).size <= TunnelChatProtocol.MAX_MESSAGE_BYTES) { "聊天消息过大。" }
        val next = conversation.copy(sequence = message.seq)
        if (!queue.enqueue(message, next.remoteEndpointId, conversation = next, receptionId = receptionId)) {
            check(allowFull) { "本地待发消息队列已满。" }
            return false
        }
        return true
    }

    private fun key(id: String, origin: String = currentSettings.platformOrigin, local: String = currentSettings.endpointId) =
        Triple(origin, local, id)

    private fun record(id: String) = queue.conversation(id, currentSettings.platformOrigin, currentSettings.endpointId)

    private fun save(conversation: TunnelConversation) = queue.save(conversation)

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

    private fun flushQueued(conversationId: String, afterHandshake: Boolean = false) {
        val binding = binding(conversationId) ?: return
        if (!authenticatedSessions.contains(binding.sessionId)) return
        val due = queue.due()
        // 结束通知必须在这条刚验签的连接上立即尝试，不能等退避跨过关闭计时器。
        val rows = (if (afterHandshake && record(conversationId)?.closed == true) queue.snapshot().filter { it.message.isEnd() } else due)
            .filter { it.message.conversationId == conversationId && matchesEndpoint(it, binding.remoteEndpointId) }
        queue.markAttempts(rows)
        sendQueued(binding, rows)
    }

    private fun sendQueued(binding: SessionBinding, rows: List<TunnelQueuedMessage>) {
        rows.forEach { row ->
            val message = row.message
            val pendingAi = message.messageId.takeIf { message.kind == ChatMessageKind.AI_FORMAL }.orEmpty()
            val sent = binding.sessionId in authenticatedSessions && binding.quic?.sendFrame(message.toJson().toString(), pendingAi) == true
            if (!sent) publishState("离线消息仍在等待确认。")
        }
    }

    private fun matchesEndpoint(row: TunnelQueuedMessage, remoteEndpointId: String) =
        row.targetEndpointId == remoteEndpointId && row.platformOrigin == currentSettings.platformOrigin &&
            row.message.senderEndpointId == currentSettings.endpointId

    private fun retryQueued() {
        val ready = mutableListOf<Pair<SessionBinding, List<TunnelQueuedMessage>>>()
        val reconnect = mutableListOf<TunnelConversation>()
        val attempted = mutableListOf<TunnelQueuedMessage>()
        val due = queue.due().filter { matchesEndpoint(it, it.targetEndpointId) }
        queue.retireClosed(sessionBindings.values.map { it.conversationId }.toSet())
        due.groupBy { it.message.conversationId }.forEach { (conversationId, rows) ->
            val row = rows.first()
            val conversation = record(conversationId) ?: run {
                // 旧版待发访客文本没有会话记录。无法恢复旧版接待状态，不为旧版 AI 正式回复重建状态。
                if (row.message.senderRole != ChatSenderRole.VISITOR || row.message.kind != ChatMessageKind.TEXT) return@forEach
                newConversation(conversationId, row.targetEndpointId, visitor = true)
                    .copy(sequence = rows.filter { matchesEndpoint(it, row.targetEndpointId) }.maxOf { it.message.seq }).also { save(it) }
            }
            val selected = rows.filter { matchesEndpoint(it, conversation.remoteEndpointId) && (!conversation.closed || it.message.isEnd()) }
            if (selected.isEmpty()) return@forEach
            val binding = binding(conversationId)
            if (binding == null) {
                if (state.status != Status.ONLINE) return@forEach
                attempted += selected
                reconnect += conversation
            } else if (binding.sessionId in authenticatedSessions) {
                attempted += selected
                ready += binding to selected
            }
        }
        queue.markAttempts(attempted)
        ready.forEach { (binding, rows) -> sendQueued(binding, rows) }
        reconnect.forEach { runCatching { connect(it) } }
    }

    private fun handleSignalingEvent(event: TunnelSignalingEvent) {
        if (!acceptingSignals) return
        when (event) {
            is TunnelSignalingEvent.Registered -> {
                registrationTimeout?.cancel(false)
                registrationTimeout = null
                registrationFailures = 0
                if (event.iceServers.isNotEmpty()) registration = registration?.copy(iceServers = event.iceServers)
                val message = when {
                    withdrawals().isNotEmpty() -> "正在确认撤下旧平台上的公开端点…"
                    currentSettings.publication == TunnelPublication.HIDDEN -> "已在线，端点处于隐身模式。"
                    else -> "公共端点已在线。"
                }
                update(TunnelChatRuntimeState(Status.ONLINE, message, currentSettings.descriptor(online = true)))
                updateWebCapability()
            }
            is TunnelSignalingEvent.Message -> handleSignalingMessage(event)
            is TunnelSignalingEvent.Failed -> {
                val error = event.error
                val retry = when (error) {
                    is TunnelPlatformException -> if (error.retryable) SignalRetry.FAST else SignalRetry.SLOW
                    is SSLHandshakeException, is SSLPeerUnverifiedException -> SignalRetry.SLOW
                    is IOException -> SignalRetry.FAST
                    else -> SignalRetry.SLOW
                }
                val message = if (error is IOException) "穿透信令连接失败。" else error.message ?: "穿透信令失败。"
                disconnectSignaling(message, retry)
            }
            is TunnelSignalingEvent.Closed -> disconnectSignaling("穿透信令已断开。",
                if (event.replaced) SignalRetry.SLOW else SignalRetry.FAST)
        }
    }

    private fun cancelRegistrationTimers() {
        registrationRetry?.cancel(false)
        registrationRetry = null
        registrationTimeout?.cancel(false)
        registrationTimeout = null
    }

    private fun disconnectSignaling(message: String, retry: SignalRetry) {
        acceptingSignals = false
        val run = ++generation
        cancelRegistrationTimers()
        registration = null
        web?.stop()
        signaling.close()
        sessionBindings.values.filterNot { it.sessionId in authenticatedSessions }.forEach { disconnectSession(it.sessionId) }
        if (retry != SignalRetry.NONE && engineReady && currentSettings.enabled) {
            val delay = if (retry == SignalRetry.SLOW) TimeUnit.MINUTES.toMillis(5) else {
                signalRetryDelayMs(registrationFailures).also { registrationFailures = (registrationFailures + 1).coerceAtMost(6) }
            }
            registrationRetry = retryScheduler.schedule({ onWorker { if (run == generation) start() } }, delay, TimeUnit.MILLISECONDS)
        }
        update(TunnelChatRuntimeState(if (retry == SignalRetry.NONE) Status.FAILED else Status.OFFLINE, message, currentSettings.descriptor(false)))
    }

    private fun handleSignalingMessage(event: TunnelSignalingEvent.Message) {
        if (event.type in setOf("web_offer", "web_candidate", "web_close")) {
            val servers = registration?.iceServers.orEmpty()
            val public = engineReady && currentSettings.enabled && currentSettings.publication == TunnelPublication.PUBLISHED
            val runtime = web ?: if (event.type == "web_offer" && public && webShares.publicList().isNotEmpty()) {
                WebShareServer(context, webShares, { identity }, signaling, retryScheduler, ::onWorker).also { web = it }
            } else null
            if (runtime != null) runtime.signal(event, servers, public)
            else if (event.type == "web_offer" && event.sessionId != null && event.sourceEndpointId != null) {
                signaling.closeWeb(event.sessionId, event.sourceEndpointId)
            }
            return
        }
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
                val freshLink = conversationId != sessionId
                val previous = record(conversationId)
                if (sessionBindings[sessionId]?.let { it.conversationId != conversationId || it.remoteEndpointId != source } == true) return
                if (offer.isBlank() || !validId(sessionId) || !validId(conversationId) ||
                    currentSettings.publication == TunnelPublication.HIDDEN || !TunnelConversation.acceptsOffer(previous, source, freshLink) ||
                    previous == null && queue.isRetired(conversationId, currentSettings.platformOrigin, currentSettings.endpointId)) {
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
                // 只有已绑定并验签的端点可以跳过新会话限频；未知会话仍走首次接待。
                when (admission.open(conversationId, reconnect = previous?.verified == true || previous?.visitor == true)) {
                    is TunnelConversationAdmission.AdmissionResult.Accepted -> {
                        val conversation = previous ?: newConversation(conversationId, source, visitor = false)
                        val attempt = SessionBinding(sessionId, conversation, initiator = false)
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
                    if (binding.answerReceived) return
                    binding.answerReceived = true
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
                if (!signaling.sendOffer(event.sessionId, event.remoteEndpointId, event.sdp, binding.conversationId)) disconnectSession(event.sessionId)
            }
            is WebRtcChatEvent.LocalAnswer -> {
                if (!signaling.sendAnswer(event.sessionId, event.remoteEndpointId, event.sdp)) disconnectSession(event.sessionId)
            }
            is WebRtcChatEvent.LocalCandidate -> signaling.sendCandidate(event.sessionId, event.remoteEndpointId, event.candidate.sdp, event.candidate.sdpMid, event.candidate.sdpMLineIndex)
            is WebRtcChatEvent.Frame -> Unit // 数据报直接进入 QUIC，不排进聊天状态队列。
            is WebRtcChatEvent.StateChanged -> {
                if (event.info.state == WebRtcChatSessionInfo.State.CONNECTED) {
                    binding.quic?.start()
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

    private fun linkReady(binding: SessionBinding) {
        val conversation = record(binding.conversationId) ?: binding.conversation
        if (!conversation.verified) save(conversation.copy(verified = true))
        authenticatedSessions += binding.sessionId
        if (!conversation.closed) sampleLink(binding)
        retryInitialOffer.remove(binding.conversationId)
        flushQueued(binding.conversationId, afterHandshake = true)
        if (conversation.closed) expireClosing(binding)
        publishState(state.message)
    }

    private fun handleFrame(binding: SessionBinding, value: String) {
        if (binding.sessionId !in authenticatedSessions) return
        val frame = runCatching { JSONObject(value) }.getOrNull() ?: return
        val type = runCatching { TunnelChatProtocol.frameType(frame) }.getOrNull() ?: return
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
        if (message.kind.isAttachment() || !permits(conversation, message)) return
        val status = queue.receive(conversation, message)
        if (status == ChatAck.Status.REJECTED) return
        binding.quic?.send(TunnelChatProtocol.ack(ChatAck(message.messageId, message.conversationId, status)).toString())
        if (message.isEnd()) {
            binding.quic?.cancelAttachment()
            expireClosing(binding)
        }
        if (status == ChatAck.Status.RECEIVED) {
            messageListener?.invoke(message, binding.remoteEndpointId)
            publishState(state.message)
            pumpReception()
        }
    }

    private fun permits(conversation: TunnelConversation, message: ChatMessage): Boolean {
        if (message.conversationId != conversation.id || message.senderEndpointId != conversation.remoteEndpointId) return false
        if (message.isEnd()) return message.senderRole == if (conversation.visitor) ChatSenderRole.ENDPOINT_USER else ChatSenderRole.VISITOR
        if (conversation.closed) return false
        return if (conversation.visitor) {
            (message.senderRole == ChatSenderRole.AI && message.kind in setOf(ChatMessageKind.AI_FORMAL, ChatMessageKind.CONVERSATION_EVENT)) ||
                (message.senderRole == ChatSenderRole.ENDPOINT_USER && (message.kind == ChatMessageKind.HUMAN_FORMAL || message.kind.isAttachment()))
        } else message.senderRole == ChatSenderRole.VISITOR && (message.kind == ChatMessageKind.TEXT || message.kind.isAttachment())
    }

    private fun receiveAttachment(binding: SessionBinding, id: String, metadata: String, source: File): Boolean = runCatching {
        val message = attachmentMessage(id, metadata) ?: return false
        if (!TunnelAttachmentFiles.matches(source, message.attachment())) return false
        synchronized(this) { saveReceivedAttachment(binding, message, source) }
    }.getOrDefault(false)

    private fun attachmentMessage(id: String, metadata: String): ChatMessage? {
        val offer = ChatMessage.fromJson(JSONObject(metadata))
        if (offer.messageId != id) return null
        val attachment = offer.attachment().takeIf { it.attachmentId == id } ?: return null
        return offer.copy(replyTo = null, payload = JSONObject().put("attachment", attachment.toJson()))
    }

    private fun attachmentConversation(binding: SessionBinding, message: ChatMessage): TunnelConversation? {
        if (sessionBindings[binding.sessionId] !== binding || binding.sessionId !in authenticatedSessions) return null
        return record(binding.conversationId)?.takeIf { !it.closed && permits(it, message) }
    }

    private fun reserveAttachment(binding: SessionBinding, id: String, metadata: String, size: Long): String = runCatching {
        val message = attachmentMessage(id, metadata) ?: return ""
        if (message.attachment().size != size) return ""
        synchronized(this) {
            val conversation = attachmentConversation(binding, message) ?: return ""
            val file = attachmentFiles.reserve(conversation, size)
            binding.receivingAttachment = true
            runCatching { publishState(state.message) }
            file.absolutePath
        }
    }.getOrDefault("")

    private fun saveReceivedAttachment(binding: SessionBinding, message: ChatMessage, source: File): Boolean {
        val conversation = attachmentConversation(binding, message) ?: return false
        val previous = conversation.inbox.firstOrNull { it.messageId == message.messageId }?.message
        val existing = attachmentFiles.file(conversation, message)
        if (previous != null || existing.exists()) {
            val attachment = message.attachment()
            return previous?.kind == message.kind && previous.attachment() == attachment && existing.length() == attachment.size
        }
        val saved = attachmentFiles.store(conversation, message, source)
        var accepted = false
        try {
            accepted = queue.receive(conversation, message) == ChatAck.Status.RECEIVED
            if (accepted) publishState(state.message)
            return accepted
        } finally { if (!accepted) saved.delete() }
    }

    private fun finishAttachment(binding: SessionBinding, message: ChatMessage, delivered: Boolean) {
        val chat = binding.conversation
        val staged = attachmentFiles.staged(chat, message)
        val delivery = runCatching {
            if (!delivered) return@runCatching false
            val saved = attachmentFiles.store(chat, message, staged)
            val dropped = try { queue.confirmSentAttachment(chat, message) } catch (error: Exception) {
                saved.delete()
                throw error
            }
            conversationMessages[chat.id]?.removeAll { it.senderEndpointId == chat.localEndpointId && it.messageId in dropped }
            true
        }.getOrDefault(false)
        staged.delete()
        if (key(chat.id, chat.platformOrigin, chat.localEndpointId) == key(chat.id)) {
            attachmentDeliveries[key(chat.id)] = TunnelAttachmentDelivery(message.messageId, delivery)
            publishState(state.message)
        }
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
        val rows = conversationMessages.getOrPut(message.conversationId) { mutableListOf() }
        if (rows.none { it.messageId == message.messageId }) {
            rows += message
            if (rows.size > 200) rows.removeAt(0)
        }
        messageListener?.invoke(message, remoteEndpointId)
        publishState(state.message)
    }

    private fun TunnelConversation.canReply() = canReply(currentSettings.maxAiReplies)

    private fun pumpReception() {
        if (!engineReady || !currentSettings.enabled || receiving) return
        val responder = aiResponder ?: return
        pendingReply?.let {
            if (!finishReception(it)) return
            pendingReply = null
        }
        val conversations = currentConversations()
        conversations.filter { !it.waitingNotified && it.aiRepliesSent > 0 && !it.canReply() }.forEach { notifyWaitingHuman(it) }
        val (conversation, receipt) = conversations.mapNotNull { chat ->
            if (chat.closed || binding(chat.id)?.sendingAttachment != null) null else chat.inbox.firstOrNull { it.pending }?.let { chat to it }
        }.minByOrNull { (_, receipt) -> receipt.receivedAt } ?: return
        val text = checkNotNull(receipt.message).payload.optString("text")
        val formal = conversation.canPublish(receipt, currentSettings.maxAiReplies)
        if (conversation.mode == ConversationMode.AI && receipt.receptionVersion == conversation.receptionVersion && !formal) {
            if (!notifyWaitingHuman(conversation)) return
            queue.finishReception(conversation, receipt.messageId)
            onWorker { pumpReception() }
            return
        }
        val epoch = receptionEpoch
        val sessionKey = UUID.nameUUIDFromBytes(JSONArray(listOf(conversation.platformOrigin, conversation.localEndpointId,
            conversation.remoteEndpointId, conversation.id)).toString().toByteArray(Charsets.UTF_8)).toString()
        receiving = true
        try {
            aiWorker.execute {
                val request = TunnelChatAiRequest(sessionKey, conversation.remoteEndpointId, text.take(4_000), formal)
                val reply = runCatching { responder(request) }.getOrNull()?.trim().orEmpty()
                onWorker {
                    receiving = false
                    if (epoch == receptionEpoch) {
                        pendingReply = ReceptionReply(conversation, receipt.messageId, formal, reply.take(4_000))
                    }
                    runCatching { pumpReception() }.onFailure { publishState("AI 接待结果保存失败。") }
                }
            }
        } catch (_: RejectedExecutionException) {
            receiving = false
            publishState("AI 接待繁忙，端点用户仍可回复。")
        }
    }

    private fun finishReception(reply: ReceptionReply): Boolean {
        val conversation = reply.conversation
        if (key(conversation.id, conversation.platformOrigin, conversation.localEndpointId) != key(conversation.id)) return true
        val live = record(conversation.id)?.takeIf { it.verified && !it.closed && it.remoteEndpointId == conversation.remoteEndpointId }
            ?: return true
        val receipt = live.inbox.firstOrNull { it.pending && it.messageId == reply.messageId } ?: return true
        if (reply.text.isNotBlank() && reply.formal && live.canPublish(receipt, currentSettings.maxAiReplies)) {
            if (binding(live.id)?.sendingAttachment != null) return false
            val message = textMessage(live, reply.text, ChatSenderRole.AI, ChatMessageKind.AI_FORMAL).copy(replyTo = receipt.messageId)
            if (!queue.canFit(message)) {
                queue.finishReception(live, receipt.messageId)
                publishState("AI 回复超过队列大小限制，端点用户仍可回复。")
                return true
            }
            if (sendAiFormalText(live, receipt, message)) return true
            publishState("待发队列已满，AI 回复将重试。")
            return false
        }
        queue.finishReception(live, receipt.messageId)
        if (reply.text.isBlank()) {
            publishState(if (reply.formal) "AI 回复失败，端点用户仍可回复。" else "未能生成 AI 内部建议。")
        } else if (!reply.formal || live.mode == ConversationMode.HUMAN_HANDOVER) {
            appendMessage(textMessage(live, reply.text, ChatSenderRole.AI, ChatMessageKind.AI_SUGGESTION), currentSettings.endpointId)
        }
        return true
    }

    private fun notifyWaitingHuman(conversation: TunnelConversation): Boolean {
        val live = record(conversation.id) ?: return true
        if (!live.verified || live.visitor || live.closed || live.mode != ConversationMode.AI || live.canReply() || live.waitingNotified) return true
        val message = textMessage(live, "AI 正式回复已达上限，等待人工回复。", ChatSenderRole.AI, ChatMessageKind.CONVERSATION_EVENT)
        message.payload.put("event", "waiting_human")
        if (!queue.canFit(message)) return true
        if (!enqueue(message, live.copy(waitingNotified = true), allowFull = true)) return false
        appendMessage(message, currentSettings.endpointId)
        flushQueued(live.id)
        return true
    }

    private fun currentConversations(): List<TunnelConversation> = if (!currentSettings.enabled) emptyList()
        else queue.conversations().filter { it.platformOrigin == currentSettings.platformOrigin && it.localEndpointId == currentSettings.endpointId }

    private fun snapshots(): List<TunnelChatConversationSnapshot> = currentConversations()
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
                messages = (conversation.inbox.mapNotNull { receipt -> receipt.message?.let { receipt.receivedAt to it } } +
                    conversation.sentAttachments.map { it.createdAt to it } +
                    conversationMessages[conversation.id].orEmpty().map { it.createdAt to it })
                    .sortedBy { it.first }.takeLast(200).map { it.second },
                canSendAttachment = !conversation.closed && connected,
                sendingAttachment = binding?.sendingAttachment != null,
                receivingAttachment = binding?.receivingAttachment == true,
                attachmentDelivery = attachmentDeliveries[key(conversation.id)],
                link = binding?.link?.takeIf { connected && !conversation.closed },
            )
        }.sortedBy { it.conversationId }

    private fun disconnectSession(sessionId: String, notify: Boolean = false) {
        val binding = sessionBindings.remove(sessionId) ?: return
        val authenticated = authenticatedSessions.remove(sessionId)
        binding.close()
        val retry = !authenticated && binding.initiator && binding.conversation.visitor && binding.conversation.verified &&
            !binding.conversation.closed && sessionId != binding.conversationId
        if (!authenticated && binding.initiator && binding.conversation.visitor && binding.conversation.verified) {
            // 对端可能在保存首次验签结果前退出。下次用原会话编号尝试首次接待，失败再尝试独立连接编号。
            if (retry) retryInitialOffer += binding.conversationId
            else retryInitialOffer -= binding.conversationId
        }
        admission.close(binding.conversationId)
        if (notify && state.status == Status.ONLINE) signaling.closeSession(sessionId, binding.remoteEndpointId)
        transport?.discardSession(sessionId)
        publishState(state.message)
        if (retry && state.status == Status.ONLINE) {
            val run = generation
            onWorker {
                if (!isCurrent(run) || binding.conversationId !in retryInitialOffer || binding(binding.conversationId) != null) return@onWorker
                val conversation = record(binding.conversationId)?.takeUnless { it.closed } ?: return@onWorker
                runCatching { connect(conversation) }
            }
        }
    }

    private fun sampleLink(binding: SessionBinding) {
        transport?.readLink(binding.sessionId) { binding.updateLink(it) }
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

    private fun transportOrThrow(): WebRtcChatTransport {
        check(acceptingSignals && !closed) { "穿透聊天未在线。" }
        return transport ?: WebRtcChatTransport(context) { event ->
            val binding = sessionBindings[event.sessionId()]
            if (event is WebRtcChatEvent.Frame) {
                binding?.quic?.push(event.bytes)
            } else if (binding != null) onWorker {
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

    private data class ReceptionReply(val conversation: TunnelConversation, val messageId: String, val formal: Boolean, val text: String)

    private inner class SessionBinding(val sessionId: String, val conversation: TunnelConversation, val initiator: Boolean) {
        val conversationId get() = conversation.id
        val remoteEndpointId get() = conversation.remoteEndpointId
        private val tokens = TunnelPeerToken(identity, remoteEndpointId, sessionId, conversationId, initiator)
        var answerReceived = false
        var closing = false
        var sendingAttachment: ChatMessage? = null
        var receivingAttachment = false
        private val linkState = MutableStateFlow<TunnelChatLink?>(null)
        val link = linkState.asStateFlow()
        fun updateLink(value: TunnelChatLink?) { linkState.value = value }

        private fun onLive(action: () -> Unit) = onWorker {
            if (sessionBindings[sessionId] === this) action()
        }

        @Volatile var quic: chatquic.Link? = Chatquic.newLink(initiator, object : Peer {
            override fun sendPacket(data: ByteArray) = sessionBindings[sessionId] === this@SessionBinding &&
                transport?.send(sessionId, data) == true
            override fun sign(binding: ByteArray): String = runCatching { tokens.sign(binding) }.getOrDefault("")
            override fun verify(binding: ByteArray, token: String) = tokens.verify(binding, token)
            override fun ready() = withLive { linkReady(this@SessionBinding) }
            override fun frame(value: String) = withLive { handleFrame(this@SessionBinding, value) }
            override fun stillPending(id: String): Boolean = synchronized(this@TunnelChatRuntime) {
                !closed && sessionBindings[sessionId] === this@SessionBinding && runCatching {
                    queue.snapshot().any { row -> row.message.messageId == id && row.message.conversationId == conversationId &&
                        matchesEndpoint(row, remoteEndpointId) }
                }.getOrDefault(false)
            }
            override fun reserveAttachment(id: String, metadata: String, size: Long) =
                this@TunnelChatRuntime.reserveAttachment(this@SessionBinding, id, metadata, size)
            override fun attachment(id: String, metadata: String, path: String) = receiveAttachment(this@SessionBinding, id, metadata, File(path))
            override fun attachmentReceiveFinished(id: String) = withLive {
                receivingAttachment = false
                publishState(state.message)
            }
            override fun attachmentSent(id: String, delivered: Boolean) = onLive {
                val message = sendingAttachment?.takeIf { it.messageId == id } ?: return@onLive
                sendingAttachment = null
                finishAttachment(this@SessionBinding, message, delivered)
            }
            override fun finished(reason: String) = onLive {
                val ready = sessionId in authenticatedSessions
                disconnectSession(sessionId, notify = true)
                publishState(if (ready) "聊天连接已断开。" else "无法建立已验证的聊天连接。")
            }
        })
            private set

        private fun withLive(action: () -> Unit): Unit = synchronized(this@TunnelChatRuntime) {
            if (!closed && sessionBindings[sessionId] === this) {
                runCatching(action).onFailure { publishState("聊天连接处理失败。") }
            }
            Unit
        }

        fun close() {
            quic?.close()
            quic = null
            sendingAttachment?.let { finishAttachment(this, it, false) }
            sendingAttachment = null
        }
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
