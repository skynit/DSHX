package local.dshx.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.android.apksig.ApkVerifier
import okhttp3.OkHttpClient
import java.io.EOFException
import java.io.File
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 应用级更新状态独立于引擎，下载由 Android 调度。 */
internal class AppUpdate(private val context: Context, private val endpoint: String = context.getString(R.string.update_url)) {
    data class State(
        val message: String = "检查是否有新版本", val busy: Boolean = false, val downloading: Boolean = false,
        val release: UpdateStore.Release? = null, val notes: String? = null, val notesError: String? = null,
        val ready: Boolean = false, val error: String? = null,
    )

    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "dshx-update").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val root = File(context.filesDir, "updates")
    private val apk = File(root, "update.apk")
    private val pending = android.util.AtomicFile(File(root, "pending.json"))
    private val certificates by lazy { certificates(context) }
    private val store by lazy { UpdateStore(endpoint, certificates) }
    private var queued = UpdateJob.pending(context)
    private var running: Download? = null
    private var awaitingPermission = false
    var state by mutableStateOf(State())
        private set

    val version: String get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    init {
        val request = queued
        state = if (request == null) State(message = "正在检查已下载文件", busy = true) else waiting()
        worker.execute {
            val restored = if (request != null) waiting().copy(release = runCatching { parse(request.manifest) }.getOrNull())
            else runCatching {
                require(pending.baseFile.length() <= 64 * 1024)
                val release = parse(pending.readFully().toString(Charsets.UTF_8))
                if (release.code <= installedCode(context)) {
                    pending.delete(); apk.delete()
                    State(message = "当前版本已更新至 $version")
                } else {
                    require(apk.isFile && apk.length() == release.artifact.size)
                    State(message = "已下载 ${release.name}，可继续安装", release = release, ready = true)
                }
            }.getOrElse { State() }
            main.post { if (queued == request && running == null) state = restored }
        }
    }

    fun check() {
        if (state.busy) return
        val old = state
        state = old.copy(message = "正在检查更新", busy = true, error = null)
        worker.execute {
            val result = runCatching {
                val remote = store.check(context.packageName, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT)
                // 保留更高版本的待安装文件，包括服务器暂时返回已安装版本的情况。
                val release = old.release?.takeIf { old.ready && it.code >= remote.code } ?: remote
                if (release.code <= installedCode(context)) State(message = "当前已是最新版本")
                else {
                    val notes = runCatching { store.notes(release) }
                    State(message = "发现新版本 ${release.name}", release = release,
                        notes = notes.getOrNull(), notesError = notes.exceptionOrNull()?.message,
                        ready = old.ready && old.release?.raw == release.raw)
                }
            }
            main.post { state = result.getOrElse { old.copy(error = it.message ?: "检查更新失败，请重试。") } }
        }
    }

    fun download() {
        val release = state.release ?: return
        if (state.busy || state.ready) return
        runCatching { UpdateJob.enqueue(context, release) }.onSuccess {
            queued = it
            state = waiting(state)
        }.onFailure { state = state.copy(error = it.message ?: "无法提交系统下载任务，请重试。") }
    }

    fun startJob(request: UpdateJob.Request, client: OkHttpClient, progress: (Int) -> Unit, finish: (Boolean) -> Unit): Boolean {
        if (queued != request || running != null) return false
        val run = Download(UpdateStore(endpoint, certificates, client))
        running = run
        worker.execute {
            var release: UpdateStore.Release? = null
            val result = runCatching {
                val selected = parse(request.manifest, run.store)
                require(selected.code > installedCode(context)) { "此更新已安装，请重新检查更新。" }
                release = selected
                check(root.isDirectory || root.mkdirs()) { "无法创建更新目录。" }
                var percent = -1L
                run.store.download(selected, apk, run.stopped::get, keepPartialOnStop = true) { done, total ->
                    val value = done * 100 / total
                    if (value != percent) {
                        percent = value
                        main.post {
                            if (running === run) {
                                state = state.copy(message = "正在下载 $value%", release = selected)
                                progress(value.toInt())
                            }
                        }
                    }
                }
                if (run.stopped.get()) throw InterruptedIOException()
                main.post { if (running === run) state = state.copy(message = "正在校验安装包", downloading = false) }
                verify(context, selected, apk, certificates)
                if (run.stopped.get()) throw InterruptedIOException()
                val output = pending.startWrite()
                try { output.write(selected.raw.toByteArray(Charsets.UTF_8)); pending.finishWrite(output) }
                catch (error: Exception) { pending.failWrite(output); throw error }
            }
            main.post {
                if (running !== run) return@post
                running = null
                val error = result.exceptionOrNull()
                val retry = error is SocketException || error is InterruptedIOException || error is UnknownHostException ||
                    error is EOFException || error is ProtocolException
                state = when {
                    retry -> waiting(state.copy(release = release ?: state.release), "等待系统恢复下载")
                    error == null -> state.copy(message = "下载完成，可安装 ${release?.name}", busy = false, downloading = false, ready = true)
                    else -> state.copy(message = "下载未完成，可重试", busy = false, downloading = false, ready = false, error = error.message)
                }
                if (!retry) queued = null
                finish(retry)
            }
        }
        return true
    }

    fun stopJob(request: UpdateJob.Request, retry: Boolean): Boolean {
        if (queued != request) return false
        running?.stop()
        running = null
        if (retry) state = waiting(state, "等待系统恢复下载")
        else {
            queued = null
            state = state.copy(message = "下载已停止", busy = false, downloading = false)
        }
        return retry
    }

    fun cancel() {
        if (!state.downloading) return
        val request = queued ?: return
        val release = state.release
        stopJob(request, retry = false)
        UpdateJob.cancel(context)
        state = state.copy(message = "正在取消下载", busy = true)
        worker.execute {
            val result = runCatching { UpdateStore.discardPartial(release ?: parse(request.manifest), apk) }
            main.post {
                state = state.copy(message = "下载已取消", busy = false, error = result.exceptionOrNull()?.message)
            }
        }
    }

    private fun parse(manifest: String, source: UpdateStore = store) =
        source.parse(manifest, context.packageName, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT)

    private fun waiting(previous: State = State(), message: String = "等待系统开始下载") =
        previous.copy(message = message, busy = true, downloading = true, ready = false, error = null)

    private class Download(val store: UpdateStore) {
        val stopped = AtomicBoolean(false)
        fun stop() { stopped.set(true); store.cancel() }
    }

    fun install(activity: Activity, launch: (Intent) -> Unit) {
        val release = state.release ?: return
        if (state.busy || !state.ready) return
        state = state.copy(message = "正在校验安装包", busy = true, error = null)
        worker.execute {
            val result = runCatching { verify(context, release, apk, certificates) }
            main.post {
                state = state.copy(busy = false)
                if (result.isFailure) { state = state.copy(ready = false, error = result.exceptionOrNull()?.message); return@post }
                runCatching {
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                        awaitingPermission = true
                        state = state.copy(message = "请允许 DSHX 安装应用，返回后继续")
                    } else {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.phone.files", apk)
                        @Suppress("DEPRECATION")
                        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true)
                        launch(intent)
                        state = state.copy(message = "等待系统确认安装")
                    }
                }.onFailure { state = state.copy(error = "无法打开系统安装页面：${it.message}") }
            }
        }
    }

    fun resume(activity: Activity, launch: (Intent) -> Unit) {
        if (!awaitingPermission) return
        awaitingPermission = false
        if (context.packageManager.canRequestPackageInstalls()) install(activity, launch)
        else state = state.copy(message = "尚未允许安装，可再次点击安装")
    }

    fun installed(result: Int) {
        state = state.copy(message = if (result == Activity.RESULT_OK) "安装流程已结束，正在使用的版本为 $version" else "安装未完成，可再次点击安装")
    }

    companion object {
        @Suppress("DEPRECATION")
        fun installedCode(context: Context): Long = context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
        }

        @Suppress("DEPRECATION")
        fun certificates(context: Context): List<X509Certificate> {
            val signatures = if (Build.VERSION.SDK_INT >= 28)
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            else context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            return signatures.orEmpty().map { CertificateFactory.getInstance("X.509").generateCertificate(it.toByteArray().inputStream()) as X509Certificate }
        }

        @Suppress("DEPRECATION")
        fun verify(context: Context, release: UpdateStore.Release, file: File, certificates: List<X509Certificate>) {
            require(file.isFile && file.length() == release.artifact.size) { "已下载的安装包不完整，请重新下载。" }
            require(UpdateStore.hash(file) == release.artifact.hash) { "安装包内容发生变化，请重新下载。" }
            val result = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(26).build().verify()
            require(result.isVerified && result.signerCertificates.map { UpdateStore.hash(it.encoded) }.toSet() ==
                certificates.map { UpdateStore.hash(it.encoded) }.toSet()) { "安装包签名与当前应用不一致。" }
            val info = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: error("无法解析安装包。")
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            require(info.packageName == context.packageName && code == release.code && info.versionName == release.name &&
                code > installedCode(context) && (info.applicationInfo?.minSdkVersion ?: 26) <= Build.VERSION.SDK_INT) { "安装包的应用、版本或系统要求不符。" }
        }
    }
}
