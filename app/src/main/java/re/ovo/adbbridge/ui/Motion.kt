package re.ovo.adbbridge.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

private const val APPEAR_MS = 160
private const val DISAPPEAR_MS = 100
private const val SLIDE_MS = 200
private const val SCREEN_SLIDE_RATIO = 0.04f

/** 同位置替换内容的过渡：新旧内容同时淡入淡出，点击后立即开始 */
fun <S> AnimatedContentTransitionScope<S>.crossFade(): ContentTransform {
    return fadeIn(tween(APPEAR_MS)) togetherWith fadeOut(tween(DISAPPEAR_MS))
}

/** 页面切换的过渡：横向平移配合淡入淡出，方向取目标页面在枚举里的次序 */
fun <S : Enum<S>> AnimatedContentTransitionScope<S>.slideFade(): ContentTransform {
    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
    val spec = tween<IntOffset>(SLIDE_MS, easing = FastOutSlowInEasing)
    val enter = fadeIn(tween(APPEAR_MS)) +
        slideInHorizontally(spec) { direction * (it * SCREEN_SLIDE_RATIO).toInt() }
    val exit = fadeOut(tween(DISAPPEAR_MS)) +
        slideOutHorizontally(spec) { -direction * (it * SCREEN_SLIDE_RATIO).toInt() }
    return enter togetherWith exit
}

/** 卡片与列表项插入、移除、位移的过渡，配合 item key 生效 */
fun LazyItemScope.itemAnimation(): Modifier {
    return Modifier.animateItem(
        fadeInSpec = tween(APPEAR_MS),
        fadeOutSpec = tween(DISAPPEAR_MS),
        placementSpec = tween(SLIDE_MS, easing = FastOutSlowInEasing),
    )
}
