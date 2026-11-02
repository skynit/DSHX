package local.dshx.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.net.URI
import java.net.InetAddress
import java.net.ServerSocket
import android.content.Intent
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

data class EngineState(val phase: String = "未启动", val message: String = "准备在这台设备运行 dsh", val url: String? = null, val busy: Boolean = false, val policy: ToolPolicy.State = ToolPolicy.State(), val backup: SessionBackup.State = SessionBackup.State(), val knowledgeFiles: KnowledgeStore.Transfer = KnowledgeStore.Transfer(), val taskRunning: Boolean = false, val controlRevision: Long = 0)

/** Owned by the Application; only the foreground service starts or stops it. */
class Engine(private val context: Context, private val worker: Executor = Executors.newSingleThreadExecutor()) {
    val phone = PhoneControl(context)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(EngineState) -> Unit>()
    private val cancelled = AtomicBoolean(false)
    private val logs = EngineLog(File(context.filesDir, "diagnostics"))
    private val control = EngineControl(File(context.filesDir, "diagnostics/restarts.json"))
    private val preferences = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
    private val bundledCore by lazy {
        runCatching {
            context.assets.open("core.json").bufferedReader().use { reader ->
                val manifest = JSONObject(reader.readText())
                JSONObject().apply {
                    listOf("version", "abi", "sha256", "dsh", "node").forEach { key -> put(key, manifest.opt(key) ?: JSONObject.NULL) }
                }
            }
        }.getOrElse { JSONObject() }
    }
    @Volatile private var portFallback = false
    @Volatile private var useDynamicPort = false
    private val scheduledRestarts = mutableSetOf<String>()
    @Volatile private var process: Process? = null
    @Volatile var active = false
        private set
    @Volatile var state = EngineState()
        private set
    private val recovery = EngineRecovery(context.filesDir)
    private val backups = SessionBackup(context.filesDir)
    val knowledgeStore = KnowledgeStore(context.filesDir)
    private val knowledgeBusy = AtomicBoolean(false)
    @Volatile private var knowledgeTransfer = KnowledgeStore.Transfer()
    private val tools = ToolPolicy { main.post { update(state) } }
    @Volatile private var bridge: PolicyBridge? = null

    fun observe(listener: (EngineState) -> Unit) { listeners.add(listener); listener(state) }
    fun remove(listener: (EngineState) -> Unit) { listeners.remove(listener) }
    fun diagnostics(): String = logs.tail()

    /** UI 直接调用；网页调用必须经过下方 engine() 的会话与权限校验。 */
    fun requestRestart(): JSONObject = reserveRestart().also { restartReply(it, true) }

    fun control(action: String, args: JSONObject = JSONObject()): JSONObject {
        validateControl(action, args)
        return when (action) {
            "status" -> engineStatus(args)
            "logs" -> engineLogs(args)
            "info" -> engineInfo()
            "restart" -> requestRestart()
            else -> throw IllegalArgumentException("不支持的引擎操作。")
        }
    }

    /** Native engine(action, args, session)：只读诊断不要求 Full，重启必须 Full。 */
    fun engine(action: String, args: JSONObject, session: String): JSONObject {
        val instance = requireNotNull(bridge?.instance) { "引擎连接已失效。" }
        val mode = tools.mode(instance, session)
        require(action != "restart" || mode == ToolPolicy.FULL) { "重启引擎需要 Full 权限。" }
        validateControl(action, args)
        return when (action) {
            "restart" -> reserveRestart(session)
            "status" -> engineStatus(args, session)
            else -> control(action, args)
        }
    }

    fun engineStatus(args: JSONObject = JSONObject(), session: String? = null): JSONObject {
        validateControl("status", args)
        val current = state
        return JSONObject().put("active", active).put("ready", current.url != null && tools.state.connected && !cancelled.get())
            .put("phase", current.phase).put("message", logs.clean(current.message)).put("busy", current.busy)
            .put("operation", control.status(if (args.has("operationId")) args.getString("operationId") else null, session))
            .put("stableUrl", "dshx://engine").put("currentAddress", currentAddress() ?: JSONObject.NULL)
    }

    fun engineLogs(args: JSONObject = JSONObject()): JSONObject {
        validateControl("logs", args)
        return JSONObject().put("text", logs.tail(args.optInt("lines", 120))).put("logPath", logs.directory.path)
            .put("maxBytes", logs.maxBytes).put("fileCount", logs.fileCount).put("error", logs.error ?: JSONObject.NULL)
    }

    fun engineInfo(): JSONObject {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = bundledCore.optString("version")
        val hash = bundledCore.optString("sha256")
        val installed = if (version.matches(Regex("[A-Za-z0-9._-]{1,80}")) && hash.matches(Regex("[a-f0-9]{64}"))) {
            val root = File(context.filesDir, "core")
            val directory = File(root, "$version-${hash.take(16)}")
            val marker = File(directory, ".verified")
            // 只读安装完成凭据；info 不做全包扫描，完整校验仍由启动与恢复负责。
            runCatching {
                !java.nio.file.Files.isSymbolicLink(root.toPath()) && !java.nio.file.Files.isSymbolicLink(directory.toPath()) &&
                    java.nio.file.Files.isRegularFile(marker.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                    marker.length() == 64L && marker.readText() == hash
            }.getOrDefault(false)
        } else false
        return JSONObject().put("version", info.versionName.orEmpty())
            .put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("engineAbi", bundledCore.opt("abi") ?: JSONObject.NULL)
            .put("coreVersion", bundledCore.opt("version") ?: JSONObject.NULL)
            .put("coreSha256", bundledCore.opt("sha256") ?: JSONObject.NULL)
            .put("installedCoreVersion", if (installed) version else JSONObject.NULL)
            .put("installedCoreSha256", if (installed) hash else JSONObject.NULL)
            .put("dshVersion", bundledCore.opt("dsh") ?: JSONObject.NULL).put("nodeVersion", bundledCore.opt("node") ?: JSONObject.NULL)
            .put("logPath", logs.directory.path).put("maxLogBytes", logs.maxBytes.toLong() * logs.fileCount)
            .put("stableUrl", "dshx://engine").put("currentAddress", currentAddress() ?: JSONObject.NULL)
            .put("preferredPort", preferences.getInt("port", 0)).put("addressStable", false)
    }

    private fun currentAddress(): String? = runCatching {
        val uri = URI(state.url ?: return null)
        if (uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535) "127.0.0.1:${uri.port}" else null
    }.getOrNull()

    private fun validateControl(action: String, args: JSONObject) {
        val fields = when (action) {
            "status" -> setOf("operationId")
            "logs" -> setOf("lines")
            "info", "restart" -> emptySet()
            else -> throw IllegalArgumentException("不支持的引擎操作。")
        }
        require(args.keys().asSequence().all { it in fields }) { "引擎请求含有未知参数。" }
        if (args.has("operationId")) require(args.get("operationId") is String && args.getString("operationId").matches(Regex("[A-Za-z0-9-]{1,128}")))
        if (args.has("lines")) {
            val value = args.get("lines")
            require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 1..200) { "日志行数须为 1 至 200。" }
        }
    }

    @Synchronized private fun reserveRestart(session: String? = null): JSONObject {
        check(!state.backup.busy) { "会话文件正在处理，请稍后重启。" }
        return control.request(session).also { update(state) }
    }

    /** 桥接回执 flush 后才排队；不依赖即将停止的 Agent 再回传 RPC。 */
    internal fun restartReply(result: JSONObject, delivered: Boolean) {
        val id = result.getString("operationId")
        if (!delivered) {
            control.move(id, "accepted", "failed", "重启回执未能送出，未开始重启。")
            update(state)
            return
        }
        synchronized(scheduledRestarts) { if (!scheduledRestarts.add(id)) return }
        // 留出旧连接转送调用回执的时间；生命周期操作由宿主独立持有。
        main.postDelayed({
            synchronized(scheduledRestarts) { scheduledRestarts.remove(id) }
            if (control.status().optString("operationId") == id && control.status(id).getString("state") == "accepted") {
                try {
                    context.startForegroundService(Intent(context, EngineService::class.java).setAction("restart").putExtra("operationId", id))
                } catch (_: Exception) { control.move(id, "accepted", "failed", "系统未允许启动引擎服务，请返回应用后重试。") }
                update(state)
            }
        }, 750)
    }

    internal fun beginRestart(id: String): Boolean = control.move(id, "accepted", "stopping").also { update(state) }
    internal fun startingRestart(id: String): Boolean = control.move(id, "stopping", "starting").also { update(state) }
    internal fun failRestart(id: String, message: String) {
        val current = control.status(id).getString("state")
        control.move(id, current, "failed", logs.clean(message)); update(state)
    }
    internal fun restartStatus(id: String): JSONObject = control.status(id)
    internal fun retryPort(): Boolean = portFallback.also { portFallback = false }
    internal fun cancelRestart() { control.cancel(); update(state) }
    fun voice(action: String, data: org.json.JSONObject = org.json.JSONObject(),
              onEvent: ((org.json.JSONObject) -> Unit)? = null): org.json.JSONObject =
        (bridge ?: throw IllegalStateException("请先启动 dsh，再使用语音对话。" )).voice(action, data, onEvent)

    /** 管理动作经现有认证连接发送；调用方中断线程时会向 Node 取消对应请求。 */
    fun memory(action: String, data: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject =
        (bridge ?: throw IllegalStateException("请先启动 dsh，再管理长期记忆。" )).memory(action, data)

    fun hosting(action: String, data: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject {
        val current = bridge ?: throw IllegalStateException("请先启动 dsh，再设置实验体。")
        if (action != "adopt") return current.hosting(action, data)
        val proposal = current.hosting("prepare", data).getJSONObject("skill")
        val receipt = try { LibraryStore(context.filesDir).adoptSkill(proposal) }
        catch (error: LibraryStore.SkillNotWritten) {
            val rejection = org.json.JSONObject().put("id", proposal.getString("id"))
                .put("operation", proposal.getString("operation")).put("digest", proposal.getString("digest"))
            try { current.hosting("rejected", rejection) }
            catch (_: Exception) { throw IllegalStateException("技能未写入，状态待同步；请再次采用以核对。") }
            throw error
        }
        return try { current.hosting("applied", receipt) }
        catch (_: Exception) { throw IllegalStateException("技能已写入，状态待同步；请再次采用以核对。") }
    }

    fun createApp(data: org.json.JSONObject): org.json.JSONObject =
        (bridge ?: throw IllegalStateException("请先启动 dsh，再创建微应用。")).createApp(data)

    fun apps(action: String, data: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject {
        val store = (context.applicationContext as DshApp).apps
        return store.call(action, data).also { result ->
            if (action == "status" && !data.has("operationId")) {
                result.put("phone", phone.status())
                result.put("runtime", (context.applicationContext as DshApp).appHost.status())
                store.error?.let { result.put("runtimeError", it) }
            }
        }
    }

    fun knowledge(action: String, data: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject =
        (bridge ?: throw IllegalStateException("请先启动 dsh，再管理知识库。")).knowledge(action, data)

    /** 文件选择器返回后独立处理，不占用服务启停队列，也不依赖弹层是否还在。 */
    fun knowledgeFiles(action: String, data: org.json.JSONObject, inputs: List<KnowledgeStore.Input> = emptyList(),
                       output: (() -> java.io.OutputStream)? = null, cleanup: (() -> Unit)? = null): Boolean {
        if (!knowledgeBusy.compareAndSet(false, true)) return false
        fun report(busy: Boolean, message: String, error: Boolean = false) {
            main.post {
                knowledgeTransfer = KnowledgeStore.Transfer(busy, message, error, knowledgeTransfer.revision + 1)
                update(state)
            }
        }
        report(true, "正在处理知识文件…")
        kotlin.concurrent.thread(name = "dsh-knowledge-files", isDaemon = true) {
            var completed = 0
            val failures = mutableListOf<String>()
            try {
                if (action == "export") {
                    try {
                        requireNotNull(output).invoke().use { knowledgeStore.export(data.getJSONObject("result"), data.getString("operationId"), it) }
                        completed = 1
                    } catch (error: Exception) { runCatching { cleanup?.invoke() }; throw error }
                } else {
                    require(inputs.size in 1..100) { "每次请选择 1 至 100 个文件。" }
                    inputs.forEach { input ->
                        // 每个文件独立编号；通信不确定时保留票据，不创建新操作重做。
                        val operation = KnowledgeStore.operation()
                        var ticket: KnowledgeStore.Ticket? = null
                        var submitted = false
                        try {
                            val format = if (action == "restore") "gzip" else when (input.title.substringAfterLast('.', "").lowercase()) {
                                "md", "markdown" -> "md"; "txt" -> "txt"; else -> throw IllegalArgumentException("仅支持 Markdown 和 TXT 文件。")
                            }
                            ticket = input.open().use { knowledgeStore.stage(it, input.title, format, operation) }
                            knowledge("ticket", ticket.json())
                            val request = org.json.JSONObject(operation.toString())
                            when (action) {
                                "restore" -> request.put("ticket", ticket.id).put("scope", org.json.JSONObject().put("kind", "user"))
                                "update" -> request.put("sourceId", data.getString("sourceId")).put("expectedRevision", data.getLong("expectedRevision"))
                                    .put("patch", org.json.JSONObject()).put("ticket", ticket.id)
                                else -> request.put("ticket", ticket.id).put("title", input.title.take(160)).put("format", format).put("collectionId", data.getString("collectionId"))
                            }
                            submitted = true
                            knowledge(action, request)
                            completed++
                        } catch (error: Exception) {
                            if (!submitted && error is IllegalArgumentException) ticket?.let(knowledgeStore::discard)
                            failures.add("${input.title.take(80)}：${error.message?.take(180) ?: "处理失败"}（操作 ${operation.getString("operationId")}）")
                        }
                    }
                }
            } catch (error: Exception) { failures.add(error.message?.take(200) ?: "知识文件处理失败。") }
            finally {
                knowledgeBusy.set(false)
                val success = if (action == "export") if (failures.isEmpty()) "知识归档已保存，不含记忆和会话。" else "知识归档未完整保存，请重新选择保存位置。" else "已受理 $completed 个文件，请查看任务的实际生效状态。"
                report(false, if (failures.isEmpty()) success else "$success\n${failures.joinToString("\n")}", failures.isNotEmpty())
            }
        }
        return true
    }

    fun backup(open: () -> java.io.OutputStream, cleanup: () -> Unit): Boolean {
        val accepted = transfer("备份") {
            try {
                val count = open().use(backups::backup)
                "已备份 $count 个会话及附件"
            } catch (error: Exception) {
                runCatching(cleanup)
                throw error
            }
        }
        if (!accepted) worker.execute { runCatching(cleanup) }
        return accepted
    }

    fun restore(open: () -> java.io.InputStream): Boolean = transfer("恢复") {
        val result = open().use(backups::restore)
        "新增 ${result.added} 个会话，跳过 ${result.skipped} 个重复会话"
    }

    @Synchronized private fun transfer(label: String, action: () -> String): Boolean {
        if (state.backup.busy) return false
        if (active || state.busy || recovery.needed) {
            update(state.copy(backup = SessionBackup.State(message = "请先在工作台停止服务；若提示需要恢复，请启动并正常停止后重试。", error = true)))
            return false
        }
        update(EngineState("正在$label", "正在处理会话文件，请稍候", busy = true, backup = SessionBackup.State(busy = true)))
        worker.execute {
            val result = runCatching(action)
            main.post {
                synchronized(this@Engine) {
                    update(EngineState("已停止", "会话和模型配置保留在本机，可重新启动", backup = SessionBackup.State(
                        message = result.getOrElse { "${label}失败：${it.message ?: "请重试"}" }, error = result.isFailure)))
                }
            }
        }
        return true
    }

    /** 完整收尾后通知服务；restart 表示子进程曾启动，且残留进程已清理，可以重新启动。 */
    @SuppressLint("UsableSpace") // Use already free space; do not evict other apps' caches to install.
    @Synchronized fun start(onFinished: (restart: Boolean) -> Unit) {
        if (state.backup.busy) { onFinished(false); return }
        launch(onFinished)
    }

    @SuppressLint("UsableSpace")
    @Synchronized private fun launch(onFinished: (restart: Boolean) -> Unit) {
        if (active || state.busy) return
        active = true
        cancelled.set(false)
        update(EngineState("启动中", "正在打开已安装的运行环境", busy = true))
        worker.execute {
            var child: Process? = null
            var restart = false
            var finished = EngineState("已停止", "会话和模型配置保留在本机，可重新启动")
            try {
                if (cancelled.get()) throw InterruptedException()
                recovery.stopProcesses()
                val manifest = context.assets.open("core.json").bufferedReader().use { CoreStore.Manifest.parse(it.readText()) }
                require(manifest.abi in Build.SUPPORTED_ABIS) { "此安装包适用于 ${manifest.abi}，当前设备为 ${Build.SUPPORTED_ABIS.joinToString()}" }
                val root = File(context.filesDir, "core")
                val store = CoreStore(root)
                var lastProgress = 0L
                if (recovery.needed) update(EngineState("正在恢复", "正在校验上次异常退出的运行环境", busy = true))
                val core = (if (recovery.needed) null else store.installed(manifest, cancelled::get)) ?: store.install(manifest, { context.assets.open("core.zip") }, context.filesDir.usableSpace, cancelled::get) { done, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 250 || done == total) {
                        lastProgress = now
                        update(EngineState("安装运行环境", "正在解压并逐文件校验 ${done * 100 / total}%", busy = true))
                    }
                }
                if (cancelled.get()) throw InterruptedException()
                val home = File(context.filesDir, "home").apply { mkdirs() }
                LibraryStore(context.filesDir).prepareSkills(context.assets)
                StoragePaths(context.filesDir).prepare()
                // Recover legacy settings before Node can read them, even if
                // the user starts chat without opening the native settings page.
                AssistantStore(context.filesDir).readSettings()
                val workspace = File(context.filesDir, "workspace").apply { mkdirs() }
                val temp = File(context.cacheDir, "engine").apply { mkdirs() }
                val patch = File(context.filesDir, "android.patch.yml")
                if (recovery.needed) {
                    recovery.complete()
                }
                if (cancelled.get()) throw InterruptedException()
                patch.writeText(File(core, "android.patch.yml").readText().replace("@CORE@", core.path))
                val instance = UUID.randomUUID().toString()
                tools.begin(instance)
                val policyBridge = PolicyBridge(tools, instance, phone::execute, phone::finish, LocalModel(context)::reply, SmallModel(context)::status, phone::status,
                    apps = ::apps, engine = ::engine, engineReply = ::restartReply, taskState = { active ->
                        main.post {
                            if (bridge?.instance == instance) update(state.copy(taskRunning = active && !cancelled.get()))
                        }
                    }) {
                    main.post {
                        if (bridge?.instance == instance && !cancelled.get()) {
                            record("原生工具权限连接中断，已请求停止 dsh；旧会话权限不会恢复。")
                            stop(cancelRestart = false)
                        }
                    }
                }
                bridge = policyBridge
                logs.remember(policyBridge.token)
                policyBridge.start()
                val requestedPort = if (useDynamicPort) 0 else preferredPort(preferences.getInt("port", 0))
                useDynamicPort = false
                val builder = ProcessBuilder(
                    File(core, "bin/dshx-run").path,
                    File(core, "bin/node").path, "--expose-internals", "--import", File(core, "compat.mjs").path,
                    File(core, "start.mjs").path,
                    "--profile", "web", "--patch", patch.path,
                    "--no-open", "--host", "127.0.0.1", "--port", requestedPort.toString()
                ).directory(workspace).redirectErrorStream(true)
                builder.environment().apply {
                    // Do not forward desktop credentials, Node injection flags or proxy settings.
                    clear()
                    put("HOME", home.path)
                    put("DSH_HOME", File(home, ".dsh").path)
                    put("DSHX_WORKSPACE", workspace.path)
                    put("DSHX_ASSISTANT_DIR", File(context.filesDir, "assistant").path)
                    put("DSHX_DATA_ROOT", context.applicationInfo.dataDir)
                    put("DSHX_POLICY_PORT", policyBridge.port.toString())
                    put("DSHX_POLICY_INSTANCE", instance)
                    put("DSHX_POLICY_TOKEN", policyBridge.token)
                    put("TMPDIR", temp.path)
                    put("TMP", temp.path)
                    put("TEMP", temp.path)
                    put("PATH", "${File(core, "bin").path}:/system/bin")
                    put("PREFIX", core.path)
                    put("TERMUX_APP__DATA_DIR", context.applicationInfo.dataDir)
                    put("TERMUX__PREFIX", core.path)
                    put("TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE", "disable")
                    put("LD_PRELOAD", File(core, "lib/libtermux-exec-ld-preload.so").path)
                    put("PYTHONPYCACHEPREFIX", File(home, ".cache/python").path)
                    put("SHELL", File(core, "bin/bash").path)
                    put("TERMINFO", File(core, "share/terminfo").path)
                    put("LD_LIBRARY_PATH", File(core, "lib").path)
                    put("NODE_EXTRA_CA_CERTS", File(core, "etc/tls/cert.pem").path)
                    put("SSL_CERT_FILE", File(core, "etc/tls/cert.pem").path)
                    put("GIT_EXEC_PATH", File(core, "libexec/git-core").path)
                    put("GIT_TEMPLATE_DIR", File(core, "share/git-core/templates").path)
                    put("GIT_SSL_CAINFO", File(core, "etc/tls/cert.pem").path)
                    put("GIT_PAGER", "cat")
                    put("LANG", "C.UTF-8")
                    put("DSHX_PARENT", android.os.Process.myPid().toString())
                    put("DSH_TELEMETRY_DISABLED", "1")
                    put("DSH_TELEMETRY_MODE", "OFF")
                }
                update(EngineState("启动中", "正在启动 dsh，请稍候", busy = true))
                child = try { builder.start() } catch (error: Exception) {
                    if (!cancelled.get()) recovery.mark()
                    throw error
                }
                process = child
                restart = true
                if (cancelled.get()) child.destroy()
                val watched = child
                val timeout = Runnable {
                    if (process === watched && (state.url == null || !tools.state.connected) && !cancelled.get()) {
                        record("dsh 在 90 秒内未就绪，已停止；可查看诊断后重试")
                        watched.destroy()
                        main.postDelayed({ if (process === watched && watched.isAlive) watched.destroyForcibly() }, 3_000)
                    }
                }
                main.postDelayed(timeout, 90_000)
                try {
                    child.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            launchUrl(line)?.let { url ->
                                logs.remember(URI(url).rawQuery.substringAfter("token="))
                                if (!cancelled.get()) {
                                    preferences.edit().putInt("port", URI(url).port).apply()
                                    update(EngineState("运行中", "dsh 已在本机运行", url))
                                }
                            }
                            if (requestedPort != 0 && state.url == null && line.contains("EADDRINUSE")) {
                                portFallback = true
                                useDynamicPort = true
                            }
                            record(line)
                        }
                    }
                    val code = child.waitFor()
                    if (!cancelled.get()) {
                        recovery.mark()
                        finished = EngineState("启动或运行失败", "dsh 已退出（$code）。请查看诊断后重试")
                    }
                } finally { main.removeCallbacks(timeout) }
            } catch (_: InterruptedException) {
                finished = EngineState("已停止", "已取消，保留已有运行环境和会话")
            } catch (error: Exception) {
                // Closing the process stream during an explicit stop may throw.
                // It is a completed stop, so retain the normal stopped state.
                if (!cancelled.get()) {
                    record(redact(error.message ?: error.javaClass.simpleName))
                    if (child != null) recovery.mark()
                    finished = EngineState("无法启动", redact(error.message ?: "请查看诊断并重试"))
                }
            } finally {
                bridge?.close()
                bridge = null
                child?.destroy()
                try {
                    recovery.stopProcesses()
                    child?.waitFor()
                } catch (error: Exception) {
                    restart = false
                    recovery.mark()
                    record(redact(error.message ?: "停止命令进程失败"))
                    finished = EngineState("需要恢复", "恢复未完成，工作区保持停止。请查看诊断并重试启动")
                }
                main.post {
                    synchronized(this@Engine) {
                        process = null
                        active = false
                        update(finished)
                        onFinished(restart)
                    }
                }
            }
        }
    }

    @Synchronized fun stop(cancelRestart: Boolean = true): Boolean {
        if (cancelRestart) cancelRestart()
        if (!active) return false
        cancelled.set(true)
        bridge?.close()
        val child = process
        update(EngineState("正在停止", "正在结束 dsh；关闭聊天页不会停止后台服务", busy = true))
        child?.destroy()
        if (child != null) main.postDelayed({ if (process === child && child.isAlive) child.destroyForcibly() }, 3_000)
        return true
    }

    private fun update(value: EngineState) {
        if (cancelled.get() && value.busy && !value.backup.busy && value.phase !in setOf("正在停止", "正在恢复")) return
        val operation = control.status()
        if (value.url != null && tools.state.connected && !cancelled.get() && operation.optString("state") == "starting") {
            control.move(operation.getString("operationId"), "starting", "succeeded")
        }
        state = value.copy(policy = tools.state, knowledgeFiles = knowledgeTransfer, controlRevision = control.revision)
        if (Looper.myLooper() == Looper.getMainLooper()) listeners.forEach { it(state) }
        else main.post { listeners.forEach { it(state) } }
    }

    private fun record(line: String) = logs.append(line)

    companion object {
        /** 提前探测占用；真正 bind 仍有竞争窗口，EADDRINUSE 由服务完整收尾后回退。 */
        internal fun preferredPort(port: Int): Int {
            if (port !in 1..65535) return 0
            return runCatching { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { port } }.getOrDefault(0)
        }
        fun launchUrl(line: String, session: String? = null): String? {
            val raw = Regex("^dsh web: (http://127\\.0\\.0\\.1:[0-9]+/\\?token=[A-Za-z0-9._~%-]+)\\s*$").find(line)?.groupValues?.get(1) ?: return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (uri.port !in 1..65535 || uri.host != "127.0.0.1" || uri.userInfo != null) return null
            return if (session != null && session.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) "$raw&dshx-session=$session" else raw
        }

        fun redact(text: String): String = text
            .replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
            .replace(Regex("(?im)((?:set-cookie|cookie)\\s*:\\s*)[^\\r\\n]+"), "$1[已隐藏]")
            .replace(Regex("(?i)(authorization[\\s=:'\"]+(?:Bearer|Basic)\\s+)[^\\s'\"]+"), "$1[已隐藏]")
            .replace(Regex("(?i)(Bearer\\s+)[^\\s'\",;}]+"), "$1[已隐藏]")
            .replace(Regex("""(?i)([\w-]*(?:token|api[_-]?key|authorization|password|secret|cookie)[\w-]*["']?\s*[:=]\s*)(["'])((?:\\.|(?!\2)[^\\])*)\2"""), "$1$2[已隐藏]$2")
            .replace(Regex("(?i)([\\w-]*(?:token|api[_-]?key|authorization|password|secret|cookie)[\\w-]*)([\\s=:'\"]+)[^\\s&'\",;}]+"), "$1$2[已隐藏]")
            .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "[密钥已隐藏]")
    }
}
