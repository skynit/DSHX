package local.dshx.app

import android.os.SystemClock
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.ViewTreeObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 只持有页面预览；target 为主页面序号，-1 表示边缘返回，拖动不提交导航或业务状态。 */
@Stable
internal class BackGesture {
    var progress by mutableFloatStateOf(0f)
        private set
    var active by mutableStateOf(false)
        private set
    var direction by mutableFloatStateOf(1f)
        private set
    var target by mutableIntStateOf(-1)
        private set
    private var system = false
    private var version = 0
    private var settling: Job? = null
    private val velocity = VelocityTracker()

    val owner: Int get() = version
    val backProgress: Float get() = if (target < 0) progress else 0f

    fun owns(owner: Int): Boolean = active && version == owner

    fun begin(direction: Float, system: Boolean = false, target: Int = -1): Boolean {
        if (active && (this.system != system || this.target != target) && settling == null) return false
        version++
        settling?.cancel()
        settling = null
        if (!active) {
            this.direction = direction
            this.target = target
        }
        this.system = system
        active = true
        velocity.resetTracking()
        sample(SystemClock.uptimeMillis())
        return true
    }

    fun move(progress: Float, time: Long = SystemClock.uptimeMillis()) {
        this.progress = progress.coerceIn(0f, 1f)
        sample(time)
    }

    private fun sample(time: Long) { velocity.addPosition(time, Offset(progress, 0f)) }

    fun speed(): Float = velocity.calculateVelocity().x.coerceIn(-4f, 4f)

    fun finish(scope: CoroutineScope, complete: Boolean, speed: Float = speed(), onComplete: () -> Unit): Job? {
        if (!active) return null
        val current = ++version
        settling?.cancel()
        settling = scope.launch {
            val animation = Animatable(progress)
            animation.updateBounds(0f, 1f)
            // 临近边界时限制向外的初速度，避免先撞到另一端而提前结束整段收尾。
            val spec = if (target < 0) AppMotion.back else AppMotion.page
            val limit = sqrt(spec.stiffness)
            val initialSpeed = speed.coerceIn(-progress * limit, (1f - progress) * limit)
            animation.animateTo(if (complete) 1f else 0f, spec, initialVelocity = initialSpeed) {
                progress = value
            }
            if (version == current) {
                // 先收掉预览，再执行唯一一次真实返回，避免新页面继承旧位移。
                progress = 0f
                active = false
                settling = null
                if (complete) onComplete()
            }
        }
        return settling
    }

    fun reset() {
        version++
        settling?.cancel()
        settling = null
        progress = 0f
        active = false
        target = -1
    }

    companion object {
        fun next(page: Int, direction: Float, count: Int): Int? =
            (page + if (direction < 0f) 1 else -1).takeIf { page in 0 until count && it in 0 until count }

        fun distance(pixels: Float, width: Float, paging: Boolean): Float =
            pixels / width * if (paging) 1.65f else 1f

        fun recognizes(x: Float, y: Float, direction: Float, slop: Float): Boolean =
            x * direction > slop && abs(x) > abs(y) * 1.3f

        fun completes(progress: Float, speed: Float, paging: Boolean = false): Boolean = when {
            paging && speed <= -0.5f -> false
            paging && speed >= 0.5f -> progress >= 0.02f
            paging -> progress + speed * 0.18f >= 0.16f
            speed <= -0.8f -> false
            speed >= 0.8f -> progress >= 0.02f
            else -> progress + speed * 0.18f >= 0.35f
        }

        /** 起点落在系统手势区域时交给 Android；应用只处理靠内侧的边缘带。 */
        fun edge(x: Float, width: Float, band: Float, left: Float, right: Float): Float = when {
            x >= left && x <= maxOf(band, left + band / 2f) -> 1f
            x <= width - right && x >= width - maxOf(band, right + band / 2f) -> -1f
            else -> 0f
        }
    }
}

/** 底栏按真实测量高度随预览滑出；正文同步获得空间，不覆盖末尾内容或输入框。 */
internal fun Modifier.backBar(progress: () -> Float): Modifier = layout { measurable, constraints ->
    val bar = measurable.measure(constraints)
    val height = (bar.height * progress().coerceIn(0f, 1f)).roundToInt()
    layout(bar.width, height) { bar.placeRelative(0, 0) }
}

@Composable
internal fun <T : Any> BackPages(
    page: T,
    previous: T,
    gesture: BackGesture,
    enabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    pages: List<T> = emptyList(),
    onPage: (T) -> Unit = {},
    web: Boolean = false,
    canSwipe: (Int) -> Boolean = { true },
    content: @Composable (T, Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val back by rememberUpdatedState(onBack)
    val select by rememberUpdatedState(onPage)
    val allowSwipe by rememberUpdatedState(canSwipe)
    val density = LocalDensity.current
    val view = LocalView.current
    var edges by remember(view) { mutableStateOf(Insets.NONE) }
    DisposableEffect(view) {
        // target-28 的装饰窗口可能先消耗 Compose 收到的边距；根窗口仍有真实的系统手势区域。
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            edges = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.systemGestures()) ?: Insets.NONE
        }
        listener.onGlobalLayout()
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    val band = with(density) { 32.dp.toPx() }
    val left = edges.left.toFloat()
    val right = edges.right.toFloat()
    DisposableEffect(page) { onDispose { gesture.reset() } }

    // 系统负责自己的边缘区域及完成/取消判定；应用接收真实的预测返回进度。
    PredictiveBackHandler(enabled) { events ->
        var accepted = false
        var start = 0f
        try {
            events.collect { event ->
                if (!accepted) {
                    if (gesture.target >= 0) gesture.reset()
                    accepted = gesture.begin(if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f, system = true)
                    start = gesture.progress
                }
                if (accepted) gesture.move(start + (1f - start) * event.progress)
            }
            // 三键导航和点击系统返回没有进度事件，也走同一条收尾路径。
            if (!accepted && gesture.target >= 0) gesture.reset()
            if (accepted || gesture.begin(1f, system = true)) gesture.finish(scope, true, onComplete = { back() })
        } catch (cancel: CancellationException) {
            if (accepted) gesture.finish(scope, false, onComplete = {})
            throw cancel
        }
    }

    val pointer = Modifier.pointerInput(page, enabled, pages, web, left, right, band) {
        val index = pages.indexOf(page)
        if (!enabled && index < 0) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x < left || down.position.x > size.width - right) return@awaitEachGesture
            val edge = if (enabled) BackGesture.edge(down.position.x, size.width.toFloat(), band, left, right) else 0f
            val retaking = gesture.active
            var direction = if (retaking) gesture.direction else edge
            var target = if (retaking) gesture.target else -1
            // 原生横向滚动控件优先处理正文手势；WebView 会消费整条触摸流，改由网页报告横向滚动能力。
            val pass = if (edge != 0f || web || retaking) PointerEventPass.Initial else PointerEventPass.Main
            var locked = false
            var released = false
            var owner = 0
            var last = down.position
            var start = gesture.progress
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            try {
                while (true) {
                    val event = awaitPointerEvent(pass)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || event.changes.count { it.pressed } > 1) break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val delta = change.position - down.position
                    // 从 Initial 转到 Main 时可能再次收到按下事件；点击控件消费按下并不代表已认领拖动。
                    if (!locked && change.pressed && change.uptimeMillis == down.uptimeMillis) continue
                    if (!locked) {
                        if (!change.pressed || change.isConsumed || !retaking && edge != 0f && delta.x * edge < -viewConfiguration.touchSlop ||
                            abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) >= abs(delta.x)) {
                            break
                        }
                        val intent = if (delta.x < 0f) -1f else 1f
                        if (BackGesture.recognizes(delta.x, delta.y, intent, viewConfiguration.touchSlop)) {
                            if (!retaking && edge == 0f) {
                                direction = intent
                                target = BackGesture.next(index, direction, pages.size) ?: break
                                if (!allowSwipe(if (direction < 0f) -1 else 1)) break
                            }
                            locked = gesture.begin(direction, target = target)
                            if (!locked) break
                            owner = gesture.owner
                            start = gesture.progress
                            // 识别前的距离阈值不映射到位移，越过阈值的部分仍跟随手指。
                            last = down.position + Offset(intent * viewConfiguration.touchSlop, 0f)
                        }
                    }
                    if (locked) {
                        if (change.isConsumed || !gesture.owns(owner)) break
                        val next = start + BackGesture.distance((change.position.x - last.x) * gesture.direction, size.width.toFloat(), target >= 0)
                        gesture.move(next, change.uptimeMillis)
                        start = gesture.progress
                        last = change.position
                        change.consume()
                        if (!change.pressed) {
                            val speed = BackGesture.distance(tracker.calculateVelocity().x * gesture.direction, size.width.toFloat(), target >= 0).coerceIn(-4f, 4f)
                            gesture.finish(scope, BackGesture.completes(gesture.progress, speed, target >= 0), speed) {
                                if (target < 0) back() else pages.getOrNull(target)?.let { select(it) }
                            }
                            released = true
                            break
                        }
                    }
                }
            } finally {
                if (locked && !released && gesture.owns(owner)) gesture.finish(scope, false, onComplete = {})
            }
        }
    }
    val target = pages.getOrNull(gesture.target) ?: previous
    Box(modifier.clipToBounds().then(pointer)) {
        if (gesture.active && target != page) key(target) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val distance = if (gesture.target < 0) 0.2f else 1f
                translationX = -gesture.direction * size.width * distance * (1f - gesture.progress)
            }.background(MiuixTheme.colorScheme.background).clearAndSetSemantics {}.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }) {
                content(target, false)
            }
        }
        key(page) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationX = if (target == page) 0f else gesture.direction * size.width * gesture.progress
            }.background(MiuixTheme.colorScheme.background)) {
                content(page, true)
            }
        }
    }
}
