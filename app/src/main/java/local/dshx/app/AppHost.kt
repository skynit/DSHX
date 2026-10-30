package local.dshx.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.Executors

/** 应用拥有独立网页上下文；界面与后台入口共享应用数据，不共享网页或手机观察凭证。 */
internal class AppHost(private val context: Context, private val store: AppStore, private val phone: AppPhone = AppPhone(context)) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "dsh-app-data").apply { isDaemon = true } }
    private val sessions = mutableSetOf<Page>()
    private val backgrounds = mutableMapOf<String, Page>()
    private val failures = mutableMapOf<String, Pair<Long, String>>()
    @Volatile private var runtimeState = "{}"
    private val listeners = mutableSetOf<() -> Unit>()
    private var started = false
    private var syncing = false
    private var syncAgain = false
    private var eventQueued = false
    private var generation = 0L
    private val changed: () -> Unit = { main.post { sync() } }

    fun start() {
        checkMain()
        if (started) return
        started = true; store.observe(changed); sync()
    }
    private fun sync() {
        checkMain()
        // 停用先撤销网页和端口，不能等待上一轮文件校验。
        val active = store.active()
        sessions.toList().filter { page -> active.none { it.app.id == page.installed.app.id && it.revision == page.installed.revision } }.forEach(Page::close)
        failures.entries.removeAll { (id, failure) -> active.none { it.app.id == id && it.revision == failure.first } }
        publish()
        if (syncing) { syncAgain = true; return }
        syncing = true
        val ticket = ++generation
        io.execute {
            val verified = store.verify()
            main.post {
                syncing = false
                if (ticket == generation && verified) {
                    store.active().filter { it.app.background != null }.forEach { entry ->
                        if (!backgrounds.containsKey(entry.app.id) && !failures.containsKey(entry.app.id)) runCatching {
                            Page(entry, true).also { backgrounds[entry.app.id] = it }
                        }.onFailure { failures[entry.app.id] = entry.revision to "后台入口无法启动。" }
                    }
                }
                publish()
                if (syncAgain) { syncAgain = false; sync() }
            }
        }
    }
    fun open(id: String): Page {
        checkMain(); start()
        val entry = store.active().find { it.app.id == id } ?: error("请先启用应用。")
        require(store.error == null) { "应用文件需要重新检查。" }
        return Page(entry, false)
    }
    fun retry(id: String) { checkMain(); backgrounds[id]?.close(); failures.remove(id); sync() }
    fun status(): JSONObject = JSONObject(runtimeState)
    fun observe(listener: () -> Unit) { checkMain(); listeners.add(listener) }
    fun remove(listener: () -> Unit) { checkMain(); listeners.remove(listener) }
    private fun publish() {
        checkMain()
        val value = JSONObject().apply {
            store.active().forEach { entry -> put(entry.app.id, JSONObject().put("background", backgrounds[entry.app.id]?.connected() == true)
                .put("pages", sessions.count { it.installed.app.id == entry.app.id && !it.background })
                .put("error", failures[entry.app.id]?.second ?: JSONObject.NULL)) }
        }.toString()
        if (value != runtimeState) { runtimeState = value; listeners.toList().forEach { it() } }
    }
    fun cancelPhone() {
        checkMain()
        sessions.forEach { page -> page.phoneEpoch++; phone.clear(page.owner) }
    }
    fun screenEvent() {
        checkMain()
        if (eventQueued) return
        eventQueued = true
        main.postDelayed({
            eventQueued = false
            val name = phone.foreground() ?: return@postDelayed
            // 只传事件元数据；应用自行决定是否请求组件树。
            sessions.toList().filter { page -> "screen.observe" in page.installed.app.capabilities && name in page.allowedApps() }
                .forEach { it.emit(JSONObject().put("event", "screen").put("data", JSONObject().put("package", name).put("at", System.currentTimeMillis()))) }
        }, 150)
    }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    inner class Page(val installed: AppStore.Installed, val background: Boolean) {
        val owner = "app:" + UUID.randomUUID()
        val view = WebView(context)
        private var port: WebMessagePort? = null
        @Volatile private var closed = false
        private var ready = false
        private var theme = JSONObject()
        private var inflight = 0
        private var calls = 0
        private var rateSince = SystemClock.elapsedRealtime()
        private var lastClick = -10_000L
        internal var phoneEpoch = 0L
        var onClosed: (() -> Unit)? = null
        private val app = installed.app
        private val url = app.origin + "/" + if (background) "__dshx/background.html" else app.entry
        init {
            checkMain()
            require(sessions.count { !it.background } < 8 || background) { "打开的应用页面过多，请先关闭部分页面。" }
            sessions.add(this)
            view.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false; allowContentAccess = false
                domStorageEnabled = false; databaseEnabled = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = true
                textZoom = (context.resources.configuration.fontScale * 100).toInt().coerceIn(50, 300)
            }
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR && valid()) io.execute {
                        store.record(app.id, app.version, "", "failed", "script-error", owner)
                    }
                    return true
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = resource(request.url.toString(), request.isForMainFrame)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !localUrl(request.url.toString())
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    ready = false; port?.close(); port = null; phoneEpoch++; phone.clear(owner)
                }
                override fun onPageFinished(view: WebView, loaded: String) {
                    if (closed || !localUrl(loaded) || ready) return
                    connect()
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    failures[app.id] = installed.revision to "应用运行进程已结束，请重新打开或重启后台入口。"
                    close(); return true
                }
            }
            view.loadUrl(url)
            publish()
        }
        internal fun connected() = !closed && ready
        private fun connect() {
            if (!valid() || ready || !localUrl(view.url.orEmpty())) return
            val ports = view.createWebMessageChannel()
            port = ports[0]
            port!!.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(port: WebMessagePort, message: WebMessage) { receive(message.data.orEmpty(), port) }
            }, main)
            ready = true
            applyTheme()
            view.postWebMessage(WebMessage("dshx-connect", arrayOf(ports[1])), Uri.parse(app.origin))
            publish()
        }
        fun appearance(value: JSONObject, background: Int) {
            theme = value
            view.setBackgroundColor(background)
            if (ready) applyTheme()
        }
        private fun applyTheme() {
            view.evaluateJavascript("Object.entries(" + theme + ").forEach(([key,value])=>document.documentElement.style.setProperty(key,value));", null)
        }
        private fun localUrl(url: String): Boolean = runCatching { Uri.parse(url).let {
            it.scheme == "https" && it.host == Uri.parse(app.origin).host && it.port == -1 && it.userInfo == null
        } }.getOrDefault(false)
        internal fun resource(url: String, mainFrame: Boolean = false): WebResourceResponse? {
            if (!valid()) return response("text/plain", "应用已停止。", 403)
            val uri = Uri.parse(url)
            if (!localUrl(url)) {
                val origin = "${uri.scheme}://${uri.host}"
                return if (!mainFrame && uri.scheme == "https" && uri.port == -1 && uri.userInfo == null && origin in app.network) null else response("text/plain", "访问未在应用声明中开放。", 403)
            }
            val path = uri.path.orEmpty().removePrefix("/")
            if (!AppPackage.validPath(path)) return response("text/plain", "路径无效。", 403)
            val source = when (path) {
                "__dshx/sdk.js" -> context.assets.open("micro-app/sdk.js").bufferedReader().use { it.readText() }
                "__dshx/background.html" -> if (background) "<!doctype html><html><head></head><body><script src=\"/${app.background}\"></script></body></html>" else null
                else -> app.files[path]
            } ?: return response("text/plain", "文件不存在。", 404)
            val type = when (path.substringAfterLast('.')) {
                "html" -> "text/html"; "js" -> "application/javascript"; "css" -> "text/css"; "json" -> "application/json"; "svg" -> "image/svg+xml"; else -> "text/plain"
            }
            val body = if (type == "text/html") {
                val sdk = "<meta charset=\"utf-8\"><script src=\"/__dshx/sdk.js\"></script>"
                val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(source)
                if (head != null) source.substring(0, head.range.last + 1) + sdk + source.substring(head.range.last + 1) else sdk + source
            } else source
            return response(type, body)
        }
        private fun response(type: String, content: String, status: Int = 200): WebResourceResponse {
            val origins = app.network.joinToString(" ")
            val headers = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
                "Content-Security-Policy" to "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: $origins; connect-src 'self' $origins; frame-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'self'; form-action 'none'")
            return WebResourceResponse(type, "UTF-8", status, if (status == 200) "OK" else "Blocked", headers, ByteArrayInputStream(content.toByteArray()))
        }
        private fun valid() = !closed && store.error == null && store.active().any { it.app.id == app.id && it.revision == installed.revision }
        internal fun allowedApps() = app.scope(JSONObject(installed.settings))
        private fun receive(text: String, reply: WebMessagePort) {
            if (!valid() || reply !== port) return
            var id = ""
            try {
                require(text.toByteArray().size <= 80 * 1024 && inflight < 16) { "应用请求过多或过大。" }
                val now = SystemClock.elapsedRealtime()
                if (now - rateSince >= 1000) { rateSince = now; calls = 0 }
                require(++calls <= 60) { "应用调用过于频繁。" }
                val request = JSONObject(text)
                AppPackage.keys(request, setOf("id", "method", "args"), true)
                id = request.getString("id").also { require(it.matches(Regex("[A-Za-z0-9-]{1,80}"))) }
                val method = request.getString("method"); val args = request.getJSONObject("args")
                when (method) {
                    "info" -> { AppPackage.keys(args, emptySet(), true); answer(reply, id, JSONObject().put("id", app.id).put("version", app.version).put("settings", JSONObject(installed.settings)).put("background", background)) }
                    "storage.get", "storage.set", "storage.remove" -> {
                        require("storage" in app.capabilities) { "应用未声明独立存储能力。" }
                        inflight++
                        val requestId = id
                        io.execute {
                            val result = runCatching { require(valid()); store.storage(app.id, method.substringAfter('.'), args, installed.revision) }
                            main.post { inflight--; if (valid() && reply === port) result.fold({ answer(reply, requestId, it) }, { answer(reply, requestId, null, it.message) }) }
                        }
                    }
                    "screen.observe", "screen.click" -> {
                        require(method in app.capabilities) { "应用未声明该屏幕能力。" }
                        AppPackage.keys(args, if (method == "screen.observe") emptySet() else setOf("snapshot", "node"), true)
                        val name = phone.foreground() ?: error("当前窗口不可读取。")
                        require(name in allowedApps()) { "当前应用不在用户选择的范围内。" }
                        val epoch = phoneEpoch
                        if (method == "screen.click") require(now - lastClick >= 1000) { "点击过于频繁。" }
                        val result = phone.call(method.substringAfter('.'), args, owner) {
                            valid() && epoch == phoneEpoch && phone.foreground() == name && name in allowedApps()
                        }
                        if (method == "screen.observe") AppPackage.completeNodes(result) else {
                            // 失败或未知也不能让脚本立即重放同一动作。
                            lastClick = now
                            io.execute { store.record(app.id, app.version, "", if (result.optBoolean("ok")) "completed" else "failed", "click-returned", owner) }
                        }
                        answer(reply, id, result)
                    }
                    else -> error("不支持的应用接口：$method")
                }
            } catch (error: Exception) { answer(reply, id, null, error.message ?: "应用调用失败。") }
        }
        private fun answer(reply: WebMessagePort, id: String, result: JSONObject?, error: String? = null) {
            if (closed || port !== reply) return
            reply.postMessage(WebMessage(JSONObject().put("id", id).put("ok", result != null).apply {
                if (result != null) put("result", result) else put("error", error ?: "调用失败。")
            }.toString()))
        }
        fun emit(value: JSONObject) { if (valid() && ready) port?.postMessage(WebMessage(value.toString())) }
        fun close() {
            checkMain()
            if (closed) return
            closed = true; phoneEpoch++; phone.clear(owner)
            port?.close(); port = null
            sessions.remove(this)
            if (backgrounds[app.id] === this) backgrounds.remove(app.id)
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.stopLoading(); view.destroy()
            publish()
            onClosed?.invoke(); onClosed = null
        }
    }
}
