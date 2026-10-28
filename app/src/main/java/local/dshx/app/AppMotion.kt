package local.dshx.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 沿用现有控件的短反馈；位移与尺寸使用无回弹弹簧，避免数据和表单晃动。 */
internal object AppMotion {
    val startupEnter = tween<Float>(durationMillis = 320)
    val startupSweep = tween<Float>(durationMillis = 640)
    const val startupWaitMillis = 1600L
    val color = tween<Color>(durationMillis = 160)
    val fade = tween<Float>(durationMillis = 160)
    val size = spring<IntSize>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    val placement = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    val back = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    val page = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 2400f, visibilityThreshold = 0.001f)
}

/** 每帧只更新背景绘制，不让选中颜色带动整行文字重新组合。 */
@Composable
internal fun Modifier.selectionBackground(selected: Boolean): Modifier {
    val color = animateColorAsState(
        if (selected) MiuixTheme.colorScheme.secondary else Color.Transparent,
        animationSpec = AppMotion.color, label = "selection",
    )
    return drawBehind { drawRect(color.value) }
}

/** 保留最终表单布局；系统关闭动画时，Compose 会直接显示或移除字段。 */
@Composable
internal fun Reveal(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(AppMotion.size, expandFrom = Alignment.Top) + fadeIn(AppMotion.fade),
        exit = shrinkVertically(AppMotion.size, shrinkTowards = Alignment.Top) + fadeOut(AppMotion.fade),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
