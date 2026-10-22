package local.dshx.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.net.URI
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

data class EngineState(val phase: String = "未启动", val message: String = "准备在这台设备运行 dsh", val url: String? = null, val busy: Boolean = false, val policy: ToolPolicy.State = ToolPolicy.State())

/** Owned by the Application; only the foreground service starts or stops it. */
class Engine(private val context: Context, private val worker: Executor = Executors.newSingleThreadExecutor()) {
    val phone = PhoneControl(context)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(EngineState) -> Unit>()
    private val cancelled = AtomicBoolean(false)
    private val logs = ArrayDeque<String>()
    @Volatile private var process: Process? = null
    @Volatile var active = false
        private set
    @Volatile var state = EngineState()
        private set
    private val recovery = EngineRecovery(context.filesDir)
    private val tools = ToolPolicy { main.post { update(state) } }
    @Volatile private var bridge: PolicyBridge? = null

    fun observe(listener: (EngineState) -> Unit) { listeners.add(listener); listener(state) }
    fun remove(listener: (EngineState) -> Unit) { listeners.remove(listener) }
    fun diagnostics(): String = synchronized(logs) { logs.joinToString("\n") }

    @SuppressLint("UsableSpace") // Use already free space; do not evict other apps' caches to install.
    @Synchronized fun start(onFinished: () -> Unit) = launch(onFinished)

    @SuppressLint("UsableSpace")
    @Synchronized private fun launch(onFinished: () -> Unit) {
        if (active) return
        active = true
        cancelled.set(false)
        update(EngineState("启动中", "正在打开已安装的运行环境", busy = true))
        worker.execute {
            var child: Process? = null
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
                val policyBridge = PolicyBridge(tools, instance, phone::execute, phone::finish) {
                    main.post {
                        if (bridge?.instance == instance && !cancelled.get()) {
                            record("原生工具权限连接中断，已请求停止 dsh；旧会话权限不会恢复。")
                            stop()
                        }
                    }
                }
                bridge = policyBridge
                policyBridge.start()
                val builder = ProcessBuilder(
                    File(core, "bin/dshx-run").path,
                    File(core, "bin/node").path, "--expose-internals", "--import", File(core, "compat.mjs").path,
                    File(core, "start.mjs").path,
                    "--profile", "web", "--patch", patch.path,
                    "--no-open", "--host", "127.0.0.1", "--port", "0"
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
                    put("PATH", "${File(core, "bin").path}:/system/bin")
                    put("PREFIX", core.path)
                    put("SHELL", File(core, "bin/bash").path)
                    put("TERMINFO", File(core, "share/terminfo").path)
                    put("LD_LIBRARY_PATH", File(core, "lib").path)
                    put("NODE_EXTRA_CA_CERTS", File(core, "etc/tls/cert.pem").path)
                    put("SSL_CERT_FILE", File(core, "etc/tls/cert.pem").path)
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
                if (cancelled.get()) child.destroy()
                val watched = child
                val timeout = Runnable {
                    if (process === watched && state.url == null && !cancelled.get()) {
                        record("dsh 在 90 秒内未就绪，已停止；可查看诊断后重试")
                        watched.destroy()
                    }
                }
                main.postDelayed(timeout, 90_000)
                try {
                    child.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            launchUrl(line)?.let { url ->
                                if (!cancelled.get()) update(EngineState("运行中", "dsh 已在本机运行", url))
                            }
                            record(redact(line))
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
                    recovery.mark()
                    record(redact(error.message ?: "停止命令进程失败"))
                    finished = EngineState("需要恢复", "恢复未完成，工作区保持停止。请查看诊断并重试启动")
                }
                main.post {
                    synchronized(this@Engine) {
                        process = null
                        active = false
                        update(finished)
                        onFinished()
                    }
                }
            }
        }
    }

    @Synchronized fun stop(): Boolean {
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
        if (cancelled.get() && value.busy && value.phase !in setOf("正在停止", "正在恢复")) return
        state = value.copy(policy = tools.state)
        if (Looper.myLooper() == Looper.getMainLooper()) listeners.forEach { it(state) }
        else main.post { listeners.forEach { it(state) } }
    }

    private fun record(line: String) = synchronized(logs) {
        logs.addLast(line.take(4_000))
        while (logs.size > 120) logs.removeFirst()
    }

    companion object {
        fun launchUrl(line: String, session: String? = null): String? {
            val raw = Regex("^dsh web: (http://127\\.0\\.0\\.1:[0-9]+/\\?token=[A-Za-z0-9._~%-]+)\\s*$").find(line)?.groupValues?.get(1) ?: return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (uri.port !in 1..65535 || uri.host != "127.0.0.1" || uri.userInfo != null) return null
            return if (session != null && session.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) "$raw&dshx-session=$session" else raw
        }

        fun redact(text: String): String = text
            .replace(Regex("(?i)(authorization[\\s=:'\"]+(?:Bearer|Basic)\\s+)[^\\s'\"]+"), "$1[已隐藏]")
            .replace(Regex("(?i)(token|api[_-]?key|authorization)([\\s=:'\"]+)[^\\s&'\"]+"), "$1$2[已隐藏]")
            .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "[密钥已隐藏]")
    }
}
