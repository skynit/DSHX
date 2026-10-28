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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 启动画面只观察引擎，后台服务独立启动；超时后进入工作台继续显示真实状态。 */
@Composable
internal fun StartupScreen(state: EngineState, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val latestState = rememberUpdatedState(state)
    val finish = rememberUpdatedState(onFinished)
    val reveal = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }
    val colors = MiuixTheme.colorScheme
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, AppMotion.startupEnter)
        withTimeoutOrNull(AppMotion.startupWaitMillis) {
            snapshotFlow { latestState.value }.first { it.url != null || !it.busy && it.phase != "未启动" }
        }
        reveal.animateTo(0f, AppMotion.fade)
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
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                Modifier.widthIn(max = 320.dp).fillMaxWidth().graphicsLayer {
                    alpha = reveal.value
                    translationY = (1f - reveal.value) * 8.dp.toPx()
                    scaleX = 0.97f + 0.03f * reveal.value
                    scaleY = scaleX
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(R.drawable.icon_foreground), contentDescription = null,
                    modifier = Modifier.size(112.dp), tint = colors.primary)
                Spacer(Modifier.height(12.dp))
                Text("DSHX", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Spacer(Modifier.height(8.dp))
                Text("你的手机助手", fontSize = 14.sp, color = colors.onSurfaceVariantSummary)
                Spacer(Modifier.height(40.dp))
                Canvas(Modifier.width(128.dp).height(2.dp)) {
                    drawLine(colors.outline, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                        strokeWidth = size.height, cap = StrokeCap.Round)
                    val left = sweep.value * size.width * 0.7f
                    drawLine(colors.primary, Offset(left, size.height / 2), Offset(left + size.width * 0.3f, size.height / 2),
                        strokeWidth = size.height, cap = StrokeCap.Round)
                }
                Spacer(Modifier.height(16.dp))
                Text(if (state.phase == "未启动") "正在启动 dsh" else state.phase,
                    fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}
