package local.dshx.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Save
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable
internal fun WebSharesSettings(runtime: TunnelChatRuntime, state: TunnelChatRuntimeState) {
    val items by runtime.webShares.items.collectAsState()
    val approvedLan by runtime.webShares.approvedLan.collectAsState()
    val context = LocalContext.current
    val settings = runtime.settings()
    val publicOnline = settings.publication == TunnelPublication.PUBLISHED && state.status == Status.ONLINE
    val canShare = publicOnline && runtime.webPlatformAvailable && settings.platformOrigin.startsWith("https://")
    var editing by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("") }
    Section("共享网页")
    for (item in items) {
        val availability = when {
            !item.enabled -> "未启用"
            !item.local && item.id !in approvedLan -> "需要重新确认目标"
            !runtime.webShares.available(item) -> "目标不可用"
            !publicOnline -> "端点未公开在线"
            !canShare -> "平台未启用网页访问"
            else -> "已启用 · 公开"
        }
        Panel(padding = 0) {
            LinkRow(item.name, "$availability · ${item.url.host}:${item.url.port}") { editing = item.id; result = "" }
        }
    }
    if (items.isEmpty()) Note("暂无共享网页")
    Action("添加网页", { result = ""; editing = "new" }, primary = true, icon = Lucide.Plus)
    if (canShare && items.any(runtime.webShares::available)) {
        Action("复制端点网页链接", {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DSHX 网页共享",
                webShareLink(settings.platformOrigin, settings.endpointId).toString()))
            result = "链接已复制。"
        }, icon = Lucide.Copy)
    }
    if (result.isNotEmpty()) Note(result)
    editing?.let { id -> key(id) {
        val selected = items.firstOrNull { it.id == id }
        val name = rememberTextFieldState(selected?.name.orEmpty())
        val address = rememberTextFieldState(selected?.url?.toString().orEmpty())
        var enabled by remember { mutableStateOf(selected?.enabled == true) }
        var error by remember { mutableStateOf<String?>(null) }
        Section(if (selected == null) "添加网页" else "编辑网页")
        Panel {
            AssistantField("名称", name, 80, false, 1)
            AssistantField("服务地址", address, 4096, false, 1, "http://127.0.0.1:8080/")
            SettingsSwitch("启用共享", "端点公开时，任何访客都可以访问。", enabled) { enabled = it }
            error?.let { Note(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("保存", {
                    runCatching { runtime.saveWebShare(selected?.id, name.text.toString(), address.text.toString(), enabled) }
                        .onSuccess { editing = null; result = "已保存。" }.onFailure { error = it.message ?: "无法保存共享。" }
                }, primary = true, icon = Lucide.Save)
                Action("取消", { editing = null })
                if (selected != null) Action("移除", {
                    runCatching { runtime.deleteWebShare(selected.id) }.onSuccess { editing = null; result = "已移除。" }
                        .onFailure { error = it.message ?: "无法移除共享。" }
                }, danger = true)
            }
        }
    } }
}

internal fun webShareLink(platform: String, endpoint: String, list: Boolean = false): Uri {
    val url = platform.toHttpUrl()
    require(url.isHttps && endpoint.matches(Regex("[a-f0-9-]{36}"))) { "网页共享需要 HTTPS 平台。" }
    val link = url.newBuilder().encodedPath("/web/").addQueryParameter("endpoint", endpoint)
    if (list) link.addQueryParameter("list", "1")
    return Uri.parse(link.build().toString())
}

internal fun openWebShare(context: Context, platform: String, endpoint: String) {
    val link = webShareLink(platform, endpoint, list = true)
    // 由浏览器引擎承载访问页，应用不向外部网页注入 Android 接口。
    val extras = Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) }
    context.startActivity(Intent(Intent.ACTION_VIEW, link).putExtras(extras))
}
