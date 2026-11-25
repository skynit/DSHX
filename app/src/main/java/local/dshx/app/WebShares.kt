package local.dshx.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID

internal data class WebShare(val id: String, val name: String, val url: HttpUrl, val enabled: Boolean) {
    val local get() = url.host == "127.0.0.1"
    val path get() = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
    fun publicInfo() = JSONObject().put("id", id).put("name", name).put("path", path)
}

internal fun webShareUrl(value: String): HttpUrl {
    val url = requireNotNull(value.trim().toHttpUrlOrNull()) { "请填写 HTTP 或 HTTPS 服务地址。" }
    require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && webPath(url.encodedPath)) { "地址不能包含登录凭据、片段或保留路径。" }
    val host = url.host
    require(host == "127.0.0.1" || privateIpv4(host)) { "只接受 127.0.0.1 或局域网的固定 IPv4 地址。" }
    return url
}

internal fun webPath(path: String): Boolean = path.length in 1..4096 && path.startsWith('/') && !path.startsWith("//") &&
    path.none { it <= ' ' || it == '\\' || it == '#' } &&
    !path.substringBefore('?').let { it == "/__dshx" || it.startsWith("/__dshx/") }

internal fun privateIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val numbers = parts.map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 && octet.toString() == it } ?: return false }
    if (numbers[3] == 0 || numbers[3] == 255) return false
    return numbers[0] == 10 || numbers[0] == 172 && numbers[1] in 16..31 || numbers[0] == 192 && numbers[1] == 168
}

internal class WebShares(context: Context, private val protectedPort: (Int) -> Boolean) : AutoCloseable {
    private val preferences = context.getSharedPreferences("web-shares", Context.MODE_PRIVATE)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val approvals = mutableMapOf<String, Network>()
    private val saved = MutableStateFlow(load())
    val items = saved.asStateFlow()
    private val checked = MutableStateFlow(emptySet<String>())
    val approvedLan = checked.asStateFlow()
    private var networkListener: ConnectivityManager.NetworkCallback? = null

    @Synchronized fun watchNetwork(onChanged: () -> Unit) {
        if (networkListener != null) return
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = forgetNetworks(onChanged) { it != network }
            override fun onLost(network: Network) = forgetNetworks(onChanged) { it == network }
        }
        connectivity.registerDefaultNetworkCallback(listener)
        networkListener = listener
    }

    private fun forgetNetworks(onChanged: () -> Unit, remove: (Network) -> Boolean) {
        val changed = synchronized(this) {
            approvals.entries.removeAll { remove(it.value) }.also { if (it) checked.value = approvals.keys.toSet() }
        }
        if (changed) onChanged()
    }

    @Synchronized fun save(id: String?, name: String, address: String, enabled: Boolean): WebShare {
        return save(id ?: UUID.randomUUID().toString(), name, webShareUrl(address), enabled, creating = id == null)
    }

    @Synchronized fun createDraft(args: JSONObject): JSONObject {
        val fields = setOf("operationId", "name", "address")
        require(args.keys().asSequence().toSet() == fields && fields.all { args.opt(it) is String }) { "创建共享只接受编号、名称和地址。" }
        val id = args.getString("operationId")
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "请使用标准小写 UUID 作为操作编号。" }
        val name = args.getString("name").trim()
        val address = args.getString("address")
        require(address.length <= 2048) { "服务地址过长。" }
        val url = webShareUrl(address)
        val item = saved.value.find { it.id == id }?.also {
            require(it.name == name && it.url == url) { "该编号已用于其他共享，不会覆盖原条目。" }
        } ?: save(id, name, url, enabled = false, creating = true)
        return JSONObject().put("id", item.id).put("name", item.name).put("address", item.url.toString()).put("enabled", item.enabled)
    }

    private fun save(id: String, name: String, url: HttpUrl, enabled: Boolean, creating: Boolean): WebShare {
        val list = saved.value
        require(name.trim().length in 1..80) { "名称须为 1 至 80 字。" }
        require(if (creating) list.size < 32 else list.any { it.id == id }) { "共享不存在或条目已满。" }
        require(!protectedPort(url.port) || url.host != "127.0.0.1") { "不能共享 DSHX 管理服务。" }
        val item = WebShare(id, name.trim(), url, enabled)
        val network = if (!item.local && enabled) checkNotNull(onLink(item)) { "目标不在当前 Wi-Fi 或有线局域网中。" } else null
        persist(list.filterNot { it.id == item.id } + item)
        if (network == null) approvals.remove(item.id) else approvals[item.id] = network
        checked.value = approvals.keys.toSet()
        return item
    }

    @Synchronized fun delete(id: String) {
        persist(saved.value.filterNot { it.id == id })
        approvals.remove(id)
        checked.value = approvals.keys.toSet()
    }

    @Synchronized fun get(id: String): WebShare? = saved.value.firstOrNull { it.id == id && available(it) }

    @Synchronized fun publicList(): List<WebShare> = saved.value.filter(::available)

    @Synchronized fun currentNetwork(item: WebShare): Network? {
        if (item.local) return null
        val current = onLink(item)
        if (current == null || current != approvals[item.id]) {
            approvals.remove(item.id)
            checked.value = approvals.keys.toSet()
            return null
        }
        return current
    }

    @Synchronized fun available(item: WebShare): Boolean = item.enabled &&
        (if (item.local) !protectedPort(item.url.port) else currentNetwork(item) != null)

    @Synchronized override fun close() {
        networkListener?.let { connectivity.unregisterNetworkCallback(it) }
        networkListener = null
        approvals.clear()
        checked.value = emptySet()
    }

    private fun onLink(item: WebShare): Network? {
        val network = connectivity.activeNetwork ?: return null
        val caps = connectivity.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        val links = connectivity.getLinkProperties(network) ?: return null
        val target = InetAddress.getByName(item.url.host)
        if (links.linkAddresses.any { it.address == target }) return null
        if (links.routes.none { !it.hasGateway() && it.destination.prefixLength != 0 && it.destination.contains(target) }) return null
        return network
    }

    private fun persist(items: List<WebShare>) {
        val json = JSONArray(items.map { JSONObject().put("id", it.id).put("name", it.name)
            .put("url", it.url.toString()).put("enabled", it.enabled) })
        check(preferences.edit().putString("items", json.toString()).commit()) { "共享设置未能保存。" }
        saved.value = items
    }

    private fun load(): List<WebShare> = runCatching {
        val array = JSONArray(preferences.getString("items", "[]"))
        (0 until minOf(array.length(), 32)).mapNotNull { index -> runCatching {
            val item = array.getJSONObject(index)
            WebShare(UUID.fromString(item.getString("id")).toString(), item.getString("name"),
                webShareUrl(item.getString("url")), item.optBoolean("enabled"))
        }.getOrNull() }
    }.getOrDefault(emptyList())
}
