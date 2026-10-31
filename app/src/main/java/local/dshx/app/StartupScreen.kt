package local.dshx.app

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.sin

/** 启动画面只观察引擎，后台服务独立启动；超时后进入工作台继续显示真实状态。 */
@Composable
internal fun StartupScreen(state: EngineState, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val latestState = rememberUpdatedState(state)
    val finish = rememberUpdatedState(onFinished)
    val reveal = remember { Animatable(0f) }
    val jump = remember { Animatable(0f) }
    val water = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }
    val colors = MiuixTheme.colorScheme
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { reveal.animateTo(1f, AppMotion.startupEnter) }
            val waterMotion = launch { water.animateTo(1f, AppMotion.startupWater) }
            jump.animateTo(1f, AppMotion.startupJump)
            withTimeoutOrNull(AppMotion.startupWaitMillis) {
                snapshotFlow { latestState.value }.first { it.url != null || !it.busy && it.phase != "未启动" }
            }
            waterMotion.cancel()
            reveal.animateTo(0f, AppMotion.fade)
        }
        finish.value()
    }
    LaunchedEffect(Unit) {
        if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) sweep.snapTo(0.5f)
        else repeat(4) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, AppMotion.startupSweep)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().background(colors.surface)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .graphicsLayer { alpha = reveal.value },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            WhaleScene(jump, water, Modifier.widthIn(max = 400.dp).fillMaxWidth().aspectRatio(1.12f))
            Spacer(Modifier.height(8.dp))
            Text("DSHX", fontSize = 30.sp, letterSpacing = 5.sp,
                fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(8.dp))
            Text("你的手机助手", fontSize = 14.sp, color = colors.onSurfaceVariantSummary)
            Spacer(Modifier.height(32.dp))
            Row(Modifier.widthIn(max = 320.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Canvas(Modifier.size(24.dp, 12.dp)) {
                    repeat(3) { index ->
                        val pulse = (0.5f + 0.5f * sin((sweep.value * 2f * PI - index * 0.9f).toFloat()))
                        drawCircle(colors.primary.copy(alpha = 0.3f + 0.7f * pulse),
                            radius = 2.dp.toPx(), center = Offset((4 + index * 8).dp.toPx(), size.height / 2))
                    }
                }
                Text(if (state.phase == "未启动") "正在启动 dsh" else state.phase,
                    fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false)
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

/** 复用带手机的正式 Logo；在绘制时放大有效图形，避免应用图标的安全留白缩小鲸鱼。 */
@Composable
private fun WhaleScene(jump: Animatable<Float, *>, water: Animatable<Float, *>, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val whale = painterResource(R.drawable.icon_foreground)
    val tint = remember(colors.primary) { ColorFilter.tint(colors.primary) }
    val wave = remember { Path() }
    Canvas(modifier) {
        val center = Offset(size.width * 0.5f, size.height * 0.43f)
        val waterY = size.height * 0.79f
        val progress = water.value
        val splash = ((progress - 0.12f) / 0.72f).coerceIn(0f, 1f)
        val lift = sin(splash * PI).toFloat()

        drawCircle(Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.07f), Color.Transparent),
            center = center, radius = size.width * 0.46f), radius = size.width * 0.46f, center = center)
        drawOval(colors.primary.copy(alpha = 0.06f),
            topLeft = Offset(size.width * 0.12f, waterY - size.height * 0.035f),
            size = Size(size.width * 0.76f, size.height * 0.09f))

        // 原图有效宽约为 490 / 1024；保留同一资源和比例，让鲸鱼占画面宽度约 78%。
        val side = size.width * 0.78f * 1024f / 490f
        val whaleCenter = center + Offset((1f - jump.value) * size.width * 0.08f,
            (1f - jump.value) * size.height * 0.7f)
        clipRect(bottom = waterY) {
            rotate((1f - jump.value) * 20f, whaleCenter) {
                translate(whaleCenter.x - side / 2, whaleCenter.y - side / 2) {
                    with(whale) { draw(Size(side, side), colorFilter = tint) }
                }
            }
        }

        // 水面挡住水下部分，水滴从两侧散开后消失，等待时只留下安静的涟漪。
        wave.reset()
        wave.moveTo(size.width * 0.04f, waterY + size.height * 0.025f)
        wave.cubicTo(size.width * 0.18f, waterY + size.height * 0.055f,
            size.width * 0.28f, waterY - lift * size.height * 0.045f, size.width * 0.5f, waterY)
        wave.cubicTo(size.width * 0.72f, waterY + lift * size.height * 0.04f,
            size.width * 0.82f, waterY + size.height * 0.015f, size.width * 0.96f, waterY + size.height * 0.025f)
        wave.cubicTo(size.width * 0.82f, waterY + size.height * 0.12f,
            size.width * 0.18f, waterY + size.height * 0.12f, size.width * 0.04f, waterY + size.height * 0.025f)
        wave.close()
        drawPath(wave, Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.12f), Color.Transparent),
            startY = waterY, endY = waterY + size.height * 0.12f))
        repeat(2) { index ->
            val spread = 0.24f + progress * 0.28f + index * 0.15f
            drawOval(colors.primary.copy(alpha = (0.2f - index * 0.06f) * (1f - progress * 0.55f)),
                topLeft = Offset(size.width * (1f - spread) / 2, waterY - size.height * 0.015f * (index + 1)),
                size = Size(size.width * spread, size.height * (0.045f + index * 0.025f)),
                style = Stroke(width = 1.dp.toPx()))
        }
        if (splash > 0f && splash < 1f) repeat(8) { index ->
            val direction = if (index % 2 == 0) -1f else 1f
            val distance = size.width * (0.1f + splash * (0.17f + index / 2 * 0.035f))
            val point = Offset(center.x + direction * distance,
                waterY - lift * size.height * (0.12f + index / 2 * 0.035f))
            drawLine(colors.primary.copy(alpha = 0.55f * (1f - splash)), point,
                point + Offset(direction * 2.dp.toPx(), 4.dp.toPx()),
                strokeWidth = (2.5f - index / 2 * 0.3f).dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
