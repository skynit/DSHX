package local.dshx.app

import android.annotation.SuppressLint
import android.Manifest
import android.os.Build
import android.provider.Settings
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.net.URI

class MainActivity : ComponentActivity() {
    private var phoneRevision by mutableIntStateOf(0)
    private val engine get() = (application as DshApp).engine
    private val voice get() = (application as DshApp).voice
    private var pendingVoice: String? = null
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val action = pendingVoice; pendingVoice = null
        if (allowed && action != null) voiceAction(action)
        else if (!allowed) Toast.makeText(this, "麦克风未授权，仍可使用文字聊天。", Toast.LENGTH_LONG).show()
    }
    private val assistantStore by lazy { AssistantStore(filesDir) }
    private var browser: WebView? = null
    private var browserInstance: String? = null
    private var pageError by mutableStateOf<String?>(null)
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fileBrowser: WebView? = null
    private var fileInstance: String? = null
    private var fileMultiple = false
    private var filePending = false
    private val phonePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { phoneRevision++ }
    private val phoneDirectory = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == RESULT_OK && uri != null && engine.phone.allowed("files")) {
            val files = engine.phone.device.files
            val version = files.requestGrant()
            kotlin.concurrent.thread(name = "phone-directory", isDaemon = true) {
                val result = runCatching { files.grant(uri, data.flags, version) }
                runOnUiThread {
                    phoneRevision++
                    if (result.isFailure) Toast.makeText(this, "目录授权失败，请重新选择。", Toast.LENGTH_LONG).show()
                }
            }
        } else phoneRevision++
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        filePending = false
        val current = fileCallback != null && fileBrowser === browser && fileInstance == engine.state.url &&
            sameOrigin(fileInstance.orEmpty(), browser?.url.orEmpty())
        val files = if (current && result.resultCode == RESULT_OK) selectedFiles(result.data, fileMultiple, ::canReadFile) else null
        finishFiles(files)
        if (current && result.resultCode == RESULT_OK && files == null) {
            Toast.makeText(this, "未能读取所选文件，请重新选择。", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        val info = assets.open("core-info.json").bufferedReader().use { JSONObject(it.readText()) }
        val bundle = BundleInfo(info.getString("dsh"), info.getString("node"), info.getString("abi"), info.getLong("unpackedSize"))
        setContent {
            var state by remember { mutableStateOf(engine.state) }
            var theme by remember { mutableStateOf(preferences.getString("theme", "system") ?: "system") }
            var voiceState by remember { mutableStateOf(voice.state) }
            DisposableEffect(voice) {
                val listener: (VoiceControl.State) -> Unit = { next ->
                    val previous = voiceState.session
                    voiceState = next
                    if (previous != next.session && next.session != null && next.status == "正在播报") {
                        engine.state.url?.let { url -> browser?.loadUrl("$url&dshx-session=${Uri.encode(next.session)}") }
                    }
                }
                voice.observe(listener)
                onDispose { voice.remove(listener) }
            }
            DisposableEffect(engine) {
                val listener: (EngineState) -> Unit = {
                    state = it
                    if (fileInstance != it.url) finishFiles(null)
                }
                engine.observe(listener)
                onDispose { engine.remove(listener) }
            }
            DshScreen(
                state = state, bundle = bundle, webVersion = WebView.getCurrentWebViewPackage()?.versionName,
                theme = theme,
                onTheme = { value -> theme = value; preferences.edit().putString("theme", value).apply() },
                onStart = { startForegroundService(Intent(this, EngineService::class.java)) },
                onStop = { startService(Intent(this, EngineService::class.java).setAction("stop")) },
                diagnostics = engine::diagnostics, pageError = pageError,
                onReload = { pageError = null; browser?.reload() },
                assistantStore = assistantStore,
                phone = engine.phone, phoneRevision = phoneRevision,
                voice = voice, voiceState = voiceState, onVoice = ::voiceAction,
                onPhonePermission = ::phonePermission,
                chat = { modifier -> state.url?.let { instance -> AndroidView(modifier = modifier, factory = { webView(instance) }) } }
            )
        }
    }

    private fun voiceAction(action: String) {
        if (action == "stop") { voice.stop(); return }
        if (action in setOf("download", "download-local", "download-output")) {
            voice.download(when (action) { "download-local" -> "local"; "download-output" -> "output"; else -> "speech" }); return
        }
        if (action == "send") { voice.start(action); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && action != "send") {
            pendingVoice = action; microphone.launch(Manifest.permission.RECORD_AUDIO); return
        }
        startForegroundService(Intent(this, VoiceService::class.java).setAction(action))
    }

    override fun onResume() {
        super.onResume()
        engine.phone.resume()
        engine.phone.device.attach(this)
        phoneRevision++
    }
    override fun onPause() { engine.phone.device.detach(this); super.onPause() }

    private fun phonePermission(action: String) {
        try {
            when (action) {
                "location" -> phonePermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                "torch" -> phonePermissions.launch(arrayOf(Manifest.permission.CAMERA))
                "steps" -> if (Build.VERSION.SDK_INT >= 29) phonePermissions.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
                "notifications" -> startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                "overlay" -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                "directory" -> phoneDirectory.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
                "revoke" -> { engine.phone.revokeDirectory(); phoneRevision++ }
            }
        } catch (_: RuntimeException) { Toast.makeText(this, "无法打开系统授权页面，请检查系统设置。", Toast.LENGTH_LONG).show() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(instance: String): WebView {
        if (browserInstance != instance || browser == null) {
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
            finishFiles(null)
            browser?.destroy()
            browserInstance = instance
            pageError = null
            browser = WebView(this).apply {
                // The upstream page uses a 100% height root. WebView's default
                // wrap-content measurement collapses that root to zero height.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                addJavascriptInterface(object {
                    @JavascriptInterface fun selected(session: String) {
                        runOnUiThread {
                            if (browser === this@apply && browserInstance == instance && engine.state.url == instance && sameOrigin(instance, url.orEmpty())) {
                                voice.select(session.ifBlank { null })
                            }
                        }
                    }
                }, "DshxVoice")
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        if (filePending || engine.state.url != instance || !sameOrigin(instance, view.url.orEmpty()) ||
                            params.mode !in listOf(FileChooserParams.MODE_OPEN, FileChooserParams.MODE_OPEN_MULTIPLE)) {
                            callback.onReceiveValue(null)
                            return true
                        }
                        fileCallback = callback
                        fileBrowser = view
                        fileInstance = instance
                        fileMultiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                        filePending = true
                        try {
                            filePicker.launch(params.createIntent().addCategory(Intent.CATEGORY_OPENABLE))
                        } catch (_: RuntimeException) {
                            filePending = false
                            finishFiles(null)
                            Toast.makeText(this@MainActivity, "无法打开文件选择器，请重试。", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !sameOrigin(instance, request.url.toString())
                    override fun onPageFinished(view: WebView, url: String) {
                        if (engine.state.url != instance || !sameOrigin(instance, url)) return
                        val css = assets.open("chat.css").bufferedReader().use { it.readText() }
                        view.evaluateJavascript("""
                            (() => {
                                if (document.getElementById('dshx-chat-layout')) return;
                                const style = document.createElement('style');
                                style.id = 'dshx-chat-layout';
                                style.textContent = ${JSONObject.quote(css)};
                                document.head.append(style);
                                let selected;
                                const syncVoice = () => {
                                    try {
                                        const session = JSON.parse(localStorage.getItem('dsh.sessions.current') || '{}').sessionId || '';
                                        if (selected !== session) { selected = session; window.DshxVoice.selected(session); }
                                    } catch {}
                                };
                                syncVoice(); setInterval(syncVoice, 500);
                            })()
                        """.trimIndent(), null)
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) pageError = "聊天页面连接失败。可以重新加载，或在工作台查看运行状态。"
                    }
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                        finishFiles(null)
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.destroy()
                        browser = null
                        browserInstance = null
                        pageError = "网页组件异常退出。dsh 服务仍在运行，返回工作台后可重新进入聊天。"
                        return true
                    }
                }
                loadUrl(instance)
            }
        }
        return browser!!.also { (it.parent as? ViewGroup)?.removeView(it) }
    }

    private fun canReadFile(uri: Uri): Boolean = runCatching {
        val provider = packageManager.resolveContentProvider(uri.authority.orEmpty(), 0) ?: return@runCatching false
        // A picker response must never turn this app's own private provider
        // into an upload source. Require an explicit read grant as well.
        provider.applicationInfo.uid != applicationInfo.uid &&
            checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun finishFiles(files: Array<Uri>?) {
        val callback = fileCallback
        fileCallback = null
        fileBrowser = null
        fileInstance = null
        callback?.onReceiveValue(files)
    }

    override fun onDestroy() { finishFiles(null); browser?.destroy(); browser = null; super.onDestroy() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        browser?.invalidate()
    }

    companion object {
        internal fun selectedFiles(data: Intent?, multiple: Boolean, canRead: (Uri) -> Boolean): Array<Uri>? {
            val clip = data?.clipData
            val files = if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri ?: return null }
                else listOfNotNull(data?.data)
            if (files.isEmpty() || !multiple && files.size != 1 ||
                files.any { it.scheme != "content" || it.authority.isNullOrBlank() || !canRead(it) }) return null
            return files.distinct().toTypedArray()
        }

        fun sameOrigin(instance: String, target: String): Boolean = runCatching {
            val origin = URI(instance)
            val uri = URI(target)
            uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == origin.port && uri.userInfo == null
        }.getOrDefault(false)
    }
}
