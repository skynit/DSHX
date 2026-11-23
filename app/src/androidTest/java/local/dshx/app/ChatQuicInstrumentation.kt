package local.dshx.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import chatquic.Chatquic
import chatquic.Link
import chatquic.Peer
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChatQuicInstrumentation : Instrumentation() {
    private lateinit var options: Bundle

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        options = arguments
        start()
    }

    override fun onStart() {
        val result = runCatching { verify() }
        val report = Bundle().apply {
            putString("stream", result.fold({ "\n$it\n" }, { "\n${it.stackTraceToString()}\n" }))
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }

    private fun verify(): String {
        val turn = options.getString("turn")
        val ice = turn?.let { listOf(TunnelIceServer(listOf(it), options.getString("username"), options.getString("credential"))) }.orEmpty()
        val worker = Executors.newSingleThreadExecutor()
        val failures = LinkedBlockingQueue<Throwable>()
        val session = "quic-instrumentation"
        val identities = List(2) { testIdentity() }
        val ready = List(2) { LinkedBlockingQueue<Boolean>() }
        val frames = List(2) { LinkedBlockingQueue<String>() }
        val attachments = List(2) { LinkedBlockingQueue<Pair<String, ByteArray>>() }
        val sent = List(2) { LinkedBlockingQueue<Pair<String, Boolean>>() }
        val routes = List(2) { LinkedBlockingQueue<TunnelChatLink.Path>() }
        val drop = AtomicInteger()
        val dropped = LinkedBlockingQueue<Boolean>()
        val completions = LinkedBlockingQueue<String>()
        val links = arrayOfNulls<Link>(2)
        val transports = arrayOfNulls<WebRtcChatTransport>(2)
        fun transport(i: Int) = checkNotNull(transports[i])
        fun link(i: Int) = checkNotNull(links[i])
        val files = File(targetContext.cacheDir, "quic-instrumentation").apply { mkdirs() }
        val uploadBytes = options.getString("uploadBytes")?.toIntOrNull() ?: 8 * 1024 * 1024
        require(uploadBytes in 1024 * 1024..20 * 1024 * 1024)
        val upload = File(files, "upload").apply { writeBytes(ByteArray(uploadBytes) { it.toByte() }) }
        val download = File(files, "download").apply { writeBytes(ByteArray(388_042) { (it * 23).toByte() }) }
        val chat = TunnelConversation("chat", "https://test.invalid", identities[1].endpointId, identities[0].endpointId,
            visitor = false, verified = true)
        val queueFile = File(files, "queue.json")
        val cipher = FixedTunnelChatQueueCipher(ByteArray(32))
        fun openQueue() = TunnelChatQueueStore(queueFile, cipher) { TunnelChatSettings(platformOrigin = chat.platformOrigin, endpointId = chat.localEndpointId) }
        val queue = openQueue().apply { save(chat) }
        val duringUpload = ChatMessage("text-during-upload", chat.id, 1, ChatSenderRole.VISITOR, chat.remoteEndpointId,
            ChatMessageKind.TEXT, System.currentTimeMillis(), payload = org.json.JSONObject().put("text", "during upload"))
        try {
            for (i in 0..1) {
                val tokens = TunnelPeerToken(identities[i], identities[1 - i].endpointId, session, "chat", i == 0)
                links[i] = Chatquic.newLink(i == 0, object : Peer {
                    override fun sendPacket(data: ByteArray): Boolean {
                        // 文字请求尚未发出。跳过前两个满长包，再丢掉附件上传的数据包。
                        if (i == 0 && data.size.toLong() == Chatquic.MaxDatagram && drop.getAndUpdate { if (it > 0) it - 1 else 0 } == 1) {
                            dropped.add(true)
                            return false
                        }
                        return transport(i).send(session, data)
                    }
                    override fun sign(binding: ByteArray) = tokens.sign(binding)
                    override fun verify(binding: ByteArray, token: String) = tokens.verify(binding, token)
                    override fun ready() { ready[i].add(true) }
                    override fun frame(value: String) {
                        runCatching {
                            if (i == 1 && runCatching { TunnelChatProtocol.parseMessage(value).messageId }.getOrNull() == duringUpload.messageId) {
                                val status = queue.receive(chat, TunnelChatProtocol.parseMessage(value))
                                check(status == ChatAck.Status.RECEIVED)
                                check(link(i).send(ChatAck(duringUpload.messageId, chat.id, status).toJson().toString()))
                            }
                            if (i == 0 && runCatching { TunnelChatProtocol.parseAck(value).messageId }.getOrNull() == duringUpload.messageId) {
                                completions.add("text ack")
                            }
                            frames[i].add(value)
                        }.onFailure { failures.add(it) }
                    }
                    override fun stillPending(id: String) = true
                    override fun reserveAttachment(id: String, metadata: String, size: Long): String = runCatching {
                        val file = File.createTempFile("receive-", ".part", files)
                        RandomAccessFile(file, "rw").use { it.setLength(size) }
                        file.absolutePath
                    }.getOrDefault("")
                    override fun attachment(id: String, metadata: String, path: String): Boolean {
                        if (id != metadata) return false
                        attachments[i].add(id to digest(File(path)))
                        return true
                    }
                    override fun attachmentSent(id: String, delivered: Boolean) {
                        if (i == 0) completions.add("attachment receipt")
                        sent[i].add(id to delivered)
                    }
                    override fun attachmentReceiveFinished(id: String) = Unit
                    override fun finished(reason: String) { failures.add(IllegalStateException(reason)) }
                })
                transports[i] = WebRtcChatTransport(targetContext) { event ->
                    if (event is WebRtcChatEvent.Frame) {
                        link(i).push(event.bytes)
                    } else worker.execute {
                        runCatching {
                            val remote = transport(1 - i)
                            when (event) {
                                is WebRtcChatEvent.LocalOffer -> remote.acceptIncoming(session, "client", event.sdp, ice, relayOnly = turn != null)
                                is WebRtcChatEvent.LocalAnswer -> remote.acceptAnswer(session, event.sdp)
                                is WebRtcChatEvent.LocalCandidate -> remote.addRemoteCandidate(session, event.candidate.sdp,
                                    event.candidate.sdpMid, event.candidate.sdpMLineIndex)
                                is WebRtcChatEvent.StateChanged -> if (event.info.state == WebRtcChatSessionInfo.State.CONNECTED) link(i).start() else Unit
                                is WebRtcChatEvent.Failed -> throw event.error
                                is WebRtcChatEvent.Frame -> Unit
                            }
                        }.onFailure { failures.add(it) }
                    }
                }
            }
            transport(0).createOutgoing(session, "server", ice, relayOnly = turn != null)
            fun <T> await(queue: LinkedBlockingQueue<T>, seconds: Int = 20): T {
                repeat(seconds * 2) {
                    failures.poll()?.let { throw it }
                    queue.poll(500, TimeUnit.MILLISECONDS)?.let { return it }
                }
                error("等待 WebRTC / HTTP/3 结果超时")
            }
            ready.forEach { check(await(it)) }
            check(link(0).send("{\"text\":\"visitor\"}") && link(1).send("{\"text\":\"endpoint\"}"))
            check(await(frames[0]) == "{\"text\":\"endpoint\"}")
            check(await(frames[1]) == "{\"text\":\"visitor\"}")
            for (i in 0..1) transport(i).readLink(session) { it?.path?.let(routes[i]::add) }
            val expected = if (turn == null) TunnelChatLink.Path.DIRECT else TunnelChatLink.Path.RELAY
            routes.forEach { check(await(it) == expected) }
            drop.set(3)
            check(link(0).sendAttachment("upload", "upload", upload.absolutePath))
            check(await(dropped))
            check(link(0).send(duringUpload.toJson().toString()))
            check(await(completions) == "text ack") { "附件收件确认挡住了文字收件确认。" }
            check(openQueue().conversation(chat.id, chat.platformOrigin, chat.localEndpointId)?.inbox?.single()?.messageId == duringUpload.messageId)
            check(link(1).sendAttachment("download", "download", download.absolutePath))
            val receivedUpload = await(attachments[1], seconds = 90)
            val receivedDownload = await(attachments[0], seconds = 90)
            check(receivedUpload.first == "upload" && receivedUpload.second.contentEquals(digest(upload)))
            check(receivedDownload.first == "download" && receivedDownload.second.contentEquals(digest(download)))
            check(await(sent[0]) == "upload" to true)
            check(await(sent[1]) == "download" to true)
            return "QUIC v1 / HTTP/3：$expected，token 验签、双向文字和附件通过；丢包后文字确认先于附件确认。"
        } finally {
            links.forEach { it?.close() }
            worker.submit { transports.forEach { it?.close() } }.get(5, TimeUnit.SECONDS)
            worker.shutdown()
            check(worker.awaitTermination(3, TimeUnit.SECONDS))
            files.deleteRecursively()
        }
    }

    private fun digest(file: File): ByteArray = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        hash.digest()
    }

    private fun testIdentity(): TunnelEndpointIdentity {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        return object : TunnelEndpointIdentity {
            override val publicKey = keys.public.encoded
            override val endpointId = tunnelEndpointId(publicKey)
            override fun sign(value: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(value)
                sign()
            }
        }
    }
}
