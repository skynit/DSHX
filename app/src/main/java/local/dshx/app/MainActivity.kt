package local.dshx.app

import android.annotation.SuppressLint
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.net.URI

class MainActivity : ComponentActivity() {
    private val engine get() = (application as DshApp).engine
    private var browser: WebView? = null
    private var browserInstance: String? = null
    private var pageError by mutableStateOf<String?>(null)
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fileBrowser: WebView? = null
    private var fileInstance: String? = null
    private var fileMultiple = false
    private var filePending = false
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
                chat = { modifier -> state.url?.let { instance -> AndroidView(modifier = modifier, factory = { webView(instance) }) } }
            )
        }
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
