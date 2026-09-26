package re.ovo.adbbridge.perf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect

/** 记录一次组合的耗时，重组时会重复记录 */
@Composable
fun TraceComposition(name: String) {
    val startNanos = PerfTrace.begin()
    SideEffect { PerfTrace.end(name, startNanos) }
}
