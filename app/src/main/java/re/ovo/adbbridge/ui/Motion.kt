package re.ovo.adbbridge.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier

private const val APPEAR_MS = 160
private const val DISAPPEAR_MS = 100
private const val SLIDE_MS = 140

/** 同位置替换内容的过渡：新旧内容同时淡入淡出，点击后立即开始 */
fun <S> AnimatedContentTransitionScope<S>.crossFade(): ContentTransform {
    return fadeIn(tween(APPEAR_MS)) togetherWith fadeOut(tween(DISAPPEAR_MS))
}

/**
 * 列表项只在重排时位移，不做入场淡入
 * 页面切换瞬间整列同时出现，逐项淡入让每一帧重绘整列
 */
fun LazyItemScope.itemAnimation(): Modifier {
    return Modifier.animateItem(
        placementSpec = tween(SLIDE_MS, easing = FastOutSlowInEasing),
    )
}
