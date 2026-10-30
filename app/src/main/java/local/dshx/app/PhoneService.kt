package local.dshx.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.concurrent.thread

/** Android 创建的无障碍服务。节点句柄与窗口状态均只在主线程保存和访问。 */
@Suppress("DEPRECATION") // API 26–32 的节点与窗口仍须 recycle；新系统允许同一释放路径。
class PhoneService : AccessibilityService() {
    private data class Window(val id: Int, val kind: Int, val layer: Int, val bounds: Rect, val active: Boolean, val focused: Boolean)
    private data class Screen(val name: String, val window: Int, val orientation: Int, val width: Int, val height: Int, val windows: List<Window>)
    private data class Target(val node: AccessibilityNodeInfo, val bounds: Rect, val signature: List<Any?>,
        val children: MutableList<Target> = mutableListOf())
    private data class ReadNode(val node: AccessibilityNodeInfo, val bounds: Rect, val signature: List<Any?>, val parent: ReadNode?)
    private data class Frame(val id: String, val session: String, val valid: () -> Boolean, val expires: Long,
        val screen: Screen, val root: AccessibilityNodeInfo, val targets: Map<String, Target>, val revision: Long, val reads: List<ReadNode>)

    private val main = Handler(Looper.getMainLooper())
    private var frame: Frame? = null
    private data class Image(val id: String, val session: String, val valid: () -> Boolean, val expires: Long,
        val screen: Screen, val revision: Long, val width: Int, val height: Int)
    private var image: Image? = null
    private var revision = 0L
    private var screenshotBusy = false
    private var expiry: Runnable? = null
    private val events get() = application as? PhoneEvents

    override fun onServiceConnected() {
        current?.takeIf { it !== this }?.offline()
        clear()
        current = this
        events?.phoneConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 小窗中的进度文字不会改变目标页面；小窗移动、缩放等窗口结构变化仍使观察失效。
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED && events?.ownsPhoneWindow(event.windowId) == true) return
        // 即使应用跳走又返回原窗口，旧凭证也不能复用；同窗口内容改变同样作废。
        revision++
        clear()
        events?.phoneChanged()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        revision++
        clear()
        events?.phoneStopped()
    }

    override fun onInterrupt() { revision++; clear(); events?.phoneStopped() }
    override fun onUnbind(intent: Intent?): Boolean { offline(); return super.onUnbind(intent) }
    override fun onDestroy() { offline(); super.onDestroy() }

    private fun offline() {
        events?.phoneStopped()
        clear()
        if (current === this) { current = null; events?.closePhoneWindow() }
    }

    internal fun clear(owner: String? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (owner != null && frame?.session != owner && image?.session != owner) return
        expiry?.let(main::removeCallbacks)
        expiry = null
        image = null
        val old = frame
        frame = null
        old?.targets?.values?.forEach { it.node.recycle() }
        old?.reads?.forEach { it.node.recycle() }
        old?.root?.recycle()
    }

    internal fun available(owner: String): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val now = SystemClock.elapsedRealtime()
        return !screenshotBusy && frame?.let { it.session != owner && now < it.expires && runCatching(it.valid).getOrDefault(false) } != true &&
            image?.let { it.session != owner && now < it.expires && runCatching(it.valid).getOrDefault(false) } != true
    }

    internal fun foreground(): String? {
        val root = rootInActiveWindow ?: return null
        return try { screen(root).name } catch (_: Exception) { null } finally { root.recycle() }
    }

    internal fun stopApps() { events?.phoneStopped() }

    private fun requireState(value: Boolean, code: String = "stale_snapshot", message: String = "观测已失效，请重新观察界面") {
        if (!value) throw PhoneFailure(code, message)
    }

    /** 每次重新取得活动根与窗口布局；窗口、遮挡或旋转变化均不沿用旧观测。 */
    private fun screen(root: AccessibilityNodeInfo): Screen {
        val name = root.packageName?.toString().orEmpty()
        requireState(name.isNotBlank() && name.length <= 255 && root.windowId >= 0, "no_window", "当前没有可读取的前台应用窗口")
        val windows = windows
        try {
            requireState(windows.size in 1..64, "no_window", "无法确认当前窗口布局")
            val visible = windows.filter { Build.VERSION.SDK_INT < 30 || it.displayId == Display.DEFAULT_DISPLAY }
            val target = visible.singleOrNull { it.id == root.windowId }
                ?: throw PhoneFailure("no_window", "活动窗口不在主屏幕")
            requireState(events?.ownsPhoneWindow(target.id) != true, "protected_window", "操作小窗由你控制，请收起小窗后重新观察目标应用")
            requireState(target.type == AccessibilityWindowInfo.TYPE_APPLICATION && (target.isActive || target.isFocused) &&
                !target.isInPictureInPictureMode, "no_window", "当前活动窗口不是可操作的前台应用")
            val bounds = Rect().also(target::getBoundsInScreen)
            requireState(!bounds.isEmpty, "no_window", "前台窗口没有可见区域")
            val layout = visible.map { Window(it.id, it.type, it.layer, Rect().also(it::getBoundsInScreen), it.isActive, it.isFocused) }
                .sortedBy { it.id }
            requireState(layout.none { it.id != target.id && it.active && it.kind != AccessibilityWindowInfo.TYPE_INPUT_METHOD },
                "window_changed", "其他活动窗口遮挡了前台应用")
            // 节点是整屏绝对坐标；服务的资源尺寸可能扣除系统栏，不能混用。
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?: throw PhoneFailure("no_window", "无法取得主屏幕尺寸")
            val size = Point().also(display::getRealSize)
            requireState(size.x > 0 && size.y > 0, "no_window", "主屏幕尺寸无效")
            return Screen(name, root.windowId, resources.configuration.orientation, size.x, size.y, layout)
        } finally { windows.forEach { it.recycle() } }
    }

    private fun protected(node: AccessibilityNodeInfo): Boolean = node.isPassword ||
        Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive

    private fun actions(node: AccessibilityNodeInfo): List<String> {
        val ids = node.actionList.map { it.id }
        return buildList {
            if (node.isClickable && AccessibilityNodeInfo.ACTION_CLICK in ids) add("click")
            if (node.isEditable && AccessibilityNodeInfo.ACTION_SET_TEXT in ids) add("input")
            if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids) add("forward")
            if (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids) add("backward")
        }
    }

    private fun stateDescription(node: AccessibilityNodeInfo): String =
        if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString().orEmpty() else ""

    private fun signature(node: AccessibilityNodeInfo): List<Any?> = listOf(node.text?.toString(), node.contentDescription?.toString(), stateDescription(node),
        node.className?.toString(), node.viewIdResourceName, node.isEditable, node.isFocused, node.isAccessibilityFocused,
        node.textSelectionStart, node.textSelectionEnd,
        node.isEnabled, node.isClickable, node.isVisibleToUser, node.isPassword, node.isScrollable, node.isCheckable, node.isChecked, node.isSelected, node.childCount,
        node.actionList.map { it.id }.sorted())

    private fun visible(node: AccessibilityNodeInfo, screen: Screen, bounds: Rect): Boolean {
        if (protected(node) || !node.isVisibleToUser || bounds.isEmpty ||
            node.windowId != screen.window || node.packageName?.toString() != screen.name) return false
        val target = screen.windows.single { it.id == screen.window }
        val clipped = Rect(bounds)
        if (!clipped.intersect(target.bounds) || !clipped.intersect(0, 0, screen.width, screen.height)) return false
        var regions = listOf(clipped)
        for (cover in screen.windows.filter { it.id != target.id && it.layer >= target.layer }) {
            regions = regions.flatMap { region ->
                val overlap = Rect(region)
                if (!overlap.intersect(cover.bounds)) listOf(region) else listOf(
                    Rect(region.left, region.top, region.right, overlap.top),
                    Rect(region.left, overlap.bottom, region.right, region.bottom),
                    Rect(region.left, overlap.top, overlap.left, overlap.bottom),
                    Rect(overlap.right, overlap.top, region.right, overlap.bottom)).filter { !it.isEmpty }
            }
            if (regions.isEmpty() || regions.size > 128) return false
        }
        return regions.isNotEmpty()
    }

    // 原生滚动只需仍有未遮挡的可见区域；点击和输入仍须完整可见且无遮挡。
    private fun actionable(node: AccessibilityNodeInfo, screen: Screen, bounds: Rect, action: String): Boolean {
        if (!node.isEnabled || !visible(node, screen, bounds)) return false
        if (action == "forward" || action == "backward") return true
        val target = screen.windows.single { it.id == screen.window }
        if (!target.bounds.contains(bounds) || !Rect(0, 0, screen.width, screen.height).contains(bounds)) return false
        return screen.windows.none { it.id != target.id && it.layer >= target.layer && Rect.intersects(it.bounds, bounds) }
    }

    internal fun observe(session: String, valid: () -> Boolean, check: () -> Unit): JSONObject = observe(session, valid, check, false)

    internal fun observe(session: String, valid: () -> Boolean, check: () -> Unit, verifyTree: Boolean): JSONObject {
        check()
        clear()
        val root = rootInActiveWindow ?: throw PhoneFailure("no_window", "没有可读取的前台界面")
        val targets = linkedMapOf<String, Target>()
        val reads = mutableListOf<ReadNode>()
        var retained = false
        try {
            val screen = screen(root)
            val revision = revision
            val nodes = JSONArray()
            // 系统节点读取也有跨进程开销；给后部可见节点留出预算，仍受8秒请求总时限约束。
            val deadline = SystemClock.elapsedRealtime() + 1000
            var visited = 0
            var truncated = false
            var bytes = 1024 // 为观测元数据预留空间；按最终 JSON 的 UTF-8 字节数累计。
            fun visit(node: AccessibilityNodeInfo, depth: Int, clickParent: String, parent: ReadNode? = null, parentId: String = "", childIndex: Int = 0) {
                // 完整状态检查包含系统查询，每16节点执行一次；入口与返回前仍强制检查。
                if (++visited % 16 == 0) check()
                if (visited > 2048 || depth > 32 || targets.size >= 200 || SystemClock.elapsedRealtime() >= deadline) {
                    truncated = true
                    return
                }
                if (protected(node) || node.windowId != screen.window ||
                    !node.packageName.isNullOrEmpty() && node.packageName.toString() != screen.name) { if (verifyTree) truncated = true; return }
                val bounds = Rect().also(node::getBoundsInScreen)
                val read = if (verifyTree) ReadNode(AccessibilityNodeInfo.obtain(node), bounds, signature(node), parent).also(reads::add) else null
                val actions = actions(node).filter { actionable(node, screen, bounds, it) }
                var clickTarget = clickParent
                val text = node.text?.toString().orEmpty()
                val description = node.contentDescription?.toString().orEmpty()
                val state = stateDescription(node)
                var childParentId = parentId
                if (verifyTree || visible(node, screen, bounds) && (actions.isNotEmpty() || text.isNotEmpty() || description.isNotEmpty() || state.isNotEmpty() || node.isCheckable || node.isSelected)) {
                    val id = "n${targets.size + 1}"
                    if ("click" in actions) clickTarget = id
                    val entry = JSONObject().put("id", id).put("text", text.take(256)).put("description", description.take(256))
                        .put("viewId", node.viewIdResourceName.orEmpty().take(256))
                        .put("type", node.className?.toString().orEmpty().take(128)).put("bounds", boundsJson(bounds))
                        .put("editable", node.isEditable).put("focused", node.isFocused).put("enabled", node.isEnabled)
                        .put("checkable", node.isCheckable).put("checked", node.isChecked).put("selected", node.isSelected)
                        .put("stateDescription", state.take(256))
                        .put("selectionStart", node.textSelectionStart).put("selectionEnd", node.textSelectionEnd)
                        .put("clickTarget", clickTarget).put("actions", JSONArray(actions))
                        .put("textTruncated", text.length > 256 || description.length > 256 || state.length > 256 || node.viewIdResourceName.orEmpty().length > 256)
                    val size = entry.toString().toByteArray(Charsets.UTF_8).size + 1
                    if (bytes + size > 256 * 1024) { truncated = true; return }
                    bytes += size
                    if (verifyTree) entry.put("parent", parentId).put("index", childIndex).put("depth", depth)
                        .put("childCount", node.childCount).put("clickable", node.isClickable).put("visible", visible(node, screen, bounds))
                    childParentId = id
                    nodes.put(entry)
                    val target = Target(AccessibilityNodeInfo.obtain(node), bounds, signature(node))
                    targets[id] = target
                    targets[clickParent]?.children?.add(target)
                }
                for (index in 0 until node.childCount) {
                    if (visited >= 2048 || targets.size >= 200 || SystemClock.elapsedRealtime() >= deadline) { truncated = true; break }
                    val child = node.getChild(index)
                    if (child == null) { truncated = true; continue }
                    try { visit(child, depth + 1, clickTarget, read, childParentId, index) } finally { child.recycle() }
                }
            }
            visit(root, 0, "")
            check()
            val currentRoot = rootInActiveWindow ?: throw PhoneFailure("window_changed", "前台界面已改变")
            try { requireState(currentRoot == root && screen(currentRoot) == screen && this.revision == revision) }
            finally { currentRoot.recycle() }
            val id = UUID.randomUUID().toString()
            val result = PhoneReply.success().put("snapshot", id).put("package", screen.name).put("window", screen.window)
                .put("expiresInMs", PhoneReply.SNAPSHOT_LIFETIME).put("nodes", nodes).put("truncated", truncated)
            requireState(result.toString().toByteArray(Charsets.UTF_8).size <= 256 * 1024,
                "result_too_large", "观测结果超过大小限制")
            frame = Frame(id, session, valid, SystemClock.elapsedRealtime() + PhoneReply.SNAPSHOT_LIFETIME, screen, root, targets, revision, reads)
            retained = true
            expiry = Runnable { clear() }.also { main.postDelayed(it, PhoneReply.SNAPSHOT_LIFETIME) }
            return result
        } finally {
            if (!retained) { targets.values.forEach { it.node.recycle() }; reads.forEach { it.node.recycle() }; root.recycle() }
        }
    }

    internal fun act(method: String, args: JSONObject, session: String, check: () -> Unit, dispatch: () -> Unit): JSONObject {
        check()
        val saved = frame ?: throw PhoneFailure("stale_snapshot", "没有有效观测，请先观察界面")
        try {
            requireState(saved.id == args.getString("snapshot") && saved.session == session &&
                runCatching(saved.valid).getOrDefault(false) && SystemClock.elapsedRealtime() < saved.expires && saved.revision == revision)
            val target = saved.targets[args.getString("node")] ?: throw PhoneFailure("invalid_node", "节点不属于本次观测")
            val root = rootInActiveWindow ?: throw PhoneFailure("window_changed", "前台界面已改变")
            try { requireState(root == saved.root && screen(root) == saved.screen) } finally { root.recycle() }
            val node = target.node
            requireState(node.refresh())
            val bounds = Rect().also(node::getBoundsInScreen)
            val requestedAction = if (method == "scroll") args.getString("direction") else method
            requireState(bounds == target.bounds && signature(node) == target.signature && actionable(node, saved.screen, bounds, requestedAction))
            // 验证父链仍回到原根，并拒绝任何密码、敏感或隐藏的祖先分支。
            var parent: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
            var attached = false
            try {
                for (depth in 0..64) {
                    val entry = parent ?: break
                    requireState(!protected(entry) && entry.windowId == saved.screen.window &&
                        (entry.packageName.isNullOrEmpty() || entry.packageName.toString() == saved.screen.name))
                    if (entry == saved.root) { attached = true; break }
                    parent = entry.parent
                    entry.recycle()
                }
            } finally { parent?.recycle() }
            requireState(attached)
            val action = when (method) {
                "click" -> { requireState("click" in actions(node), "action_unavailable", "节点不支持点击"); AccessibilityNodeInfo.ACTION_CLICK }
                "input" -> {
                    requireState("input" in actions(node), "action_unavailable", "节点不支持直接设置文本")
                    requireInput(args.getString("text"))
                    AccessibilityNodeInfo.ACTION_SET_TEXT
                }
                else -> when (args.getString("direction")) {
                    "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> throw PhoneFailure("invalid_args", "direction 只支持 forward 或 backward")
                }.also { requireState(it in node.actionList.map { entry -> entry.id }, "action_unavailable", "节点不支持该方向滚动") }
            }
            val extras = if (method == "input") Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, args.getString("text"))
            } else null
            check()
            requireState(saved.revision == revision && SystemClock.elapsedRealtime() < saved.expires)
            // 空文字按钮常由子文字提供名称；同一个按钮句柄被复用时也核验这些子节点。
            fun checkChildren(parent: Target) {
                for (child in parent.children) {
                    check()
                    requireState(child.node.refresh())
                    val bounds = Rect().also(child.node::getBoundsInScreen)
                    requireState(bounds == child.bounds && signature(child.node) == child.signature && visible(child.node, saved.screen, bounds))
                    checkChildren(child)
                }
            }
            checkChildren(target)
            val currentRoot = rootInActiveWindow ?: throw PhoneFailure("window_changed", "前台界面已改变")
            try { requireState(currentRoot == saved.root && screen(currentRoot) == saved.screen) } finally { currentRoot.recycle() }
            requireState(node.refresh())
            val currentBounds = Rect().also(node::getBoundsInScreen)
            requireState(currentBounds == target.bounds && signature(node) == target.signature && actionable(node, saved.screen, currentBounds, requestedAction))
            // 原生规则的证据可能在目标外部；刷新整份已遍历树，包含无文字父节点和候选的父链。
            val deadline = SystemClock.elapsedRealtime() + 1000
            for (read in saved.reads) {
                check()
                requireState(SystemClock.elapsedRealtime() < deadline && read.node.refresh(), "stale_snapshot", "规则证据无法及时复核")
                val latest = Rect().also(read.node::getBoundsInScreen)
                requireState(!protected(read.node) && latest == read.bounds && signature(read.node) == read.signature)
                read.parent?.let { expected ->
                    val parent = read.node.parent
                    try { requireState(parent != null && parent == expected.node) } finally { parent?.recycle() }
                }
            }
            check()
            val dispatchRoot = rootInActiveWindow ?: throw PhoneFailure("window_changed", "派发前窗口已改变")
            try { requireState(dispatchRoot == saved.root && screen(dispatchRoot) == saved.screen) }
            finally { dispatchRoot.recycle() }
            requireState(saved.revision == revision && SystemClock.elapsedRealtime() < saved.expires && node.refresh())
            val dispatchBounds = Rect().also(node::getBoundsInScreen)
            requireState(dispatchBounds == target.bounds && signature(node) == target.signature && actionable(node, saved.screen, dispatchBounds, requestedAction))
            // 不用坐标兜底、不尝试祖先点击、不粘贴剪贴板；返回系统原始接受结果。
            dispatch()
            val accepted = node.performAction(action, extras)
            return PhoneReply.actionResult(accepted)
        } finally { clear() }
    }

    internal fun screenshot(session: String, valid: () -> Boolean, check: () -> Unit, dispatch: () -> Unit, complete: (JSONObject) -> Unit) {
        check()
        requireState(Build.VERSION.SDK_INT >= 30, "screenshot_unsupported", "Android 8–10 不支持无障碍截图，需要 Android 11 或更新版本")
        requireState(!screenshotBusy, "busy", "上一张截图尚未完成")
        clear()
        val root = rootInActiveWindow ?: throw PhoneFailure("no_window", "没有可截图的前台应用")
        val screen = try { screen(root) } finally { root.recycle() }
        val revision = revision
        screenshotBusy = true
        try {
            dispatch()
            takeScreenshot(Display.DEFAULT_DISPLAY, Executor { main.post(it) }, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    // 主线程仅接收回调；缩放和 JPEG 编码在后台完成，缓冲区在所有分支释放。
                    thread(name = "dsh-phone-image", isDaemon = true) {
                        val value = try {
                            check()
                            val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?: throw PhoneFailure("screenshot_failed", "无法读取截图缓冲区")
                            try { encode(hardware, check) } finally { hardware.recycle() }
                        } catch (error: Exception) { PhoneReply.errorResult(error) }
                        finally { result.hardwareBuffer.close() }
                        main.post {
                            screenshotBusy = false
                            try {
                                check()
                                val root = rootInActiveWindow ?: throw PhoneFailure("window_changed", "截图期间前台界面已改变")
                                try { requireState(screen(root) == screen && this@PhoneService.revision == revision,
                                    "window_changed", "截图期间前台界面已改变，请重试") } finally { root.recycle() }
                                check()
                                if (value.optBoolean("ok")) {
                                    val saved = Image(UUID.randomUUID().toString(), session, valid,
                                        SystemClock.elapsedRealtime() + PhoneReply.SNAPSHOT_LIFETIME, screen, revision,
                                        value.getInt("width"), value.getInt("height"))
                                    image = saved
                                    expiry = Runnable { clear() }.also { main.postDelayed(it, PhoneReply.SNAPSHOT_LIFETIME) }
                                    value.put("screenshot", saved.id).put("expiresInMs", PhoneReply.SNAPSHOT_LIFETIME)
                                }
                                complete(value.put("package", screen.name))
                            } catch (error: Exception) { complete(PhoneReply.errorResult(error)) }
                        }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    screenshotBusy = false
                    val code = if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) "screenshot_throttled" else "screenshot_failed"
                    try { check(); complete(PhoneReply.failure(code, "系统拒绝截图，可能涉及安全窗口、请求频率或服务权限")) }
                    catch (error: Exception) { complete(PhoneReply.errorResult(error)) }
                }
            })
        } catch (error: Exception) { screenshotBusy = false; throw error }
    }

    /** 坐标使用压缩后图片的像素；一次性凭证只适用于同一会话和未变化的前台窗口。 */
    internal fun gesture(method: String, args: JSONObject, session: String, check: () -> Unit,
        dispatch: () -> Unit, complete: (JSONObject) -> Unit) {
        check()
        requireState(Build.VERSION.SDK_INT >= 30, "unsupported", "坐标操作需要 Android 11 或更新版本的截图")
        val saved = image ?: throw PhoneFailure("stale_snapshot", "请先获取新的手机截图")
        try {
            requireState(saved.id == args.getString("screenshot") && saved.session == session && saved.valid() &&
                SystemClock.elapsedRealtime() < saved.expires && saved.revision == revision)
            val root = rootInActiveWindow ?: throw PhoneFailure("no_window", "当前没有前台窗口")
            try { requireState(screen(root) == saved.screen) } finally { root.recycle() }
            fun point(x: Int, y: Int): Point {
                requireState(x in 0 until saved.width && y in 0 until saved.height, "invalid_args", "坐标超出截图范围")
                return Point((x.toLong() * saved.screen.width / saved.width).toInt(),
                    (y.toLong() * saved.screen.height / saved.height).toInt())
            }
            val start = point(args.getInt("x"), args.getInt("y"))
            val end = if (method == "swipe") point(args.getInt("endX"), args.getInt("endY")) else start
            val ms = if (method == "swipe") args.getInt("ms").toLong() else 80L
            requireState(ms in 50..3000, "invalid_args", "滑动时长必须为 50 到 3000 毫秒")
            val bounds = Rect(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x) + 1, maxOf(start.y, end.y) + 1)
            val window = saved.screen.windows.single { it.id == saved.screen.window }
            requireState(window.bounds.contains(bounds) && saved.screen.windows.none {
                it.id != window.id && it.layer >= window.layer && Rect.intersects(it.bounds, bounds)
            }, "obscured", "手势区域超出应用窗口或存在系统栏、键盘等遮挡")
            // 整条路径还必须避开密码与敏感控件，截图操作不能绕过节点操作的保护。
            val current = rootInActiveWindow ?: throw PhoneFailure("no_window", "当前没有前台窗口")
            var visited = 0
            val deadline = SystemClock.uptimeMillis() + 250
            fun safe(node: AccessibilityNodeInfo, depth: Int = 0, inherited: Boolean = false): Boolean {
                if (depth > 40 || ++visited > 2048 || SystemClock.uptimeMillis() > deadline) return false
                val area = Rect().also(node::getBoundsInScreen)
                val sensitive = inherited || protected(node)
                if (sensitive && (area.isEmpty || Rect.intersects(area, bounds))) return false
                for (i in 0 until minOf(node.childCount, 500)) {
                    val child = node.getChild(i) ?: return false
                    try { if (!safe(child, depth + 1, sensitive)) return false } finally { child.recycle() }
                }
                return node.childCount <= 500
            }
            try { requireState(safe(current), "sensitive", "手势区域包含敏感控件或无法确认其安全范围") } finally { current.recycle() }
            val path = Path().apply { moveTo(start.x.toFloat(), start.y.toFloat()); if (method == "swipe") lineTo(end.x.toFloat(), end.y.toFloat()) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
            clear()
            dispatch()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { complete(PhoneReply.success().put("accepted", true)) }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    complete(PhoneReply.failure("gesture_cancelled", "系统中断手势，请重新观察界面").put("execution_state", "unknown"))
                }
            }, main)
            if (!accepted) complete(PhoneReply.actionResult(false))
        } catch (error: Exception) { clear(); throw error }
    }

    internal fun encode(source: Bitmap, check: () -> Unit): JSONObject {
        val scale = minOf(1.0, 1280.0 / maxOf(source.width, source.height))
        // 硬件位图不能直接传给部分软件缩放路径，先复制成可编码的软件位图。
        val copy = source.copy(Bitmap.Config.ARGB_8888, false) ?: throw PhoneFailure("screenshot_failed", "无法转换截图")
        try {
            var ratio = scale
            repeat(4) { attempt ->
                check()
                val bitmap = Bitmap.createScaledBitmap(copy, maxOf(1, (source.width * ratio).toInt()),
                    maxOf(1, (source.height * ratio).toInt()), true)
                try {
                    val output = ByteArrayOutputStream()
                    requireState(bitmap.compress(Bitmap.CompressFormat.JPEG, 80 - attempt * 10, output), "screenshot_failed", "截图编码失败")
                    check()
                    if (output.size() <= 256 * 1024) return PhoneReply.success().put("mime", "image/jpeg")
                        .put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
                        .put("width", bitmap.width).put("height", bitmap.height)
                } finally { if (bitmap !== copy) bitmap.recycle() }
                ratio *= 0.8
            }
            throw PhoneFailure("screenshot_too_large", "截图压缩后仍超过大小限制")
        } finally { copy.recycle() }
    }

    private fun boundsJson(bounds: Rect): JSONObject = JSONObject().put("left", bounds.left).put("top", bounds.top)
        .put("right", bounds.right).put("bottom", bounds.bottom)

    companion object {
        @Volatile internal var current: PhoneService? = null
            private set

        internal fun requireInput(text: String) {
            if (text.codePointCount(0, text.length) > 2000 || text.toByteArray(Charsets.UTF_8).size > 8192) {
                throw PhoneFailure("invalid_args", "输入文本最多 2000 个 Unicode 码点，且 UTF-8 编码不得超过 8192 字节")
            }
            // 单独的代理项不是有效 Unicode，禁止编码时悄悄替换成问号。
            var index = 0
            while (index < text.length) {
                val value = text[index++]
                if (Character.isHighSurrogate(value)) {
                    if (index >= text.length || !Character.isLowSurrogate(text[index++])) {
                        throw PhoneFailure("invalid_args", "输入文本包含无效 Unicode 字符")
                    }
                } else if (Character.isLowSurrogate(value)) throw PhoneFailure("invalid_args", "输入文本包含无效 Unicode 字符")
            }
        }
    }
}
