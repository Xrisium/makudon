package io.github.xrisium.makudon.feature.workspace

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.NavigationSide
import kotlinx.coroutines.channels.Channel
import kotlin.math.roundToInt

internal data class DocumentScrollRange(val viewport: Float, val extent: Float, val position: Float) {
    val maximum get() = (extent - viewport).coerceAtLeast(0f)
    val progress get() = if (maximum > 0) (position / maximum).coerceIn(0f, 1f) else 0f
}

/** Bounded, conflated seeks keep a drag responsive without queueing stale scroll requests. */
@Composable
internal fun BoxScope.DocumentNavigation(
    model: WorkspaceViewModel,
    range: DocumentScrollRange,
    scrolling: Boolean,
    seek: suspend (Float) -> Unit,
) {
    val currentSeek by rememberUpdatedState(seek)
    val requests = remember { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(requests) { for (value in requests) currentSeek(value) }
    DisposableEffect(requests) { onDispose { requests.close() } }
    val enabled = !model.ui.busy && !model.ui.transitioning && !model.ui.storageBlocked
    if (range.viewport <= 0 || range.maximum < range.viewport) return
    val left = model.ui.workspace?.navigationSide == NavigationSide.Left
    val side = if (left) Alignment.CenterStart else Alignment.CenterEnd
    val showTop = range.position > range.viewport * 1.5f
    val showBottom = range.maximum - range.position > range.viewport * 1.5f
    val showScroller = range.maximum >= range.viewport * 3
    val colors = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (dragging || scrolling) 0.95f else 0.55f, label = "navigation-opacity")
    Column(Modifier.align(side).width(48.dp).fillMaxHeight().padding(vertical = 4.dp).testTag("document-navigation"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp)) {
            if (showTop) IconButton(onClick = { requests.trySend(0f) }, enabled = enabled, modifier = Modifier.fillMaxSize().testTag("scroll-top")) {
                Box(Modifier.fillMaxSize(), contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd) {
                    Box(Modifier.size(24.dp).background(colors.surface.copy(alpha = 0.94f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_arrow_up), stringResource(R.string.scroll_top), Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showScroller) {
                var trackHeight by remember { mutableIntStateOf(0) }
                val progress by rememberUpdatedState(range.progress)
                val ratio by rememberUpdatedState((range.viewport / range.extent).coerceIn(0.06f, 0.5f))
                val label = stringResource(R.string.fast_scroll)
                val percent = (range.progress * 100).roundToInt()
                Canvas(Modifier.fillMaxSize().onSizeChanged { trackHeight = it.height }.testTag("fast-scroller")
                    .semantics {
                        contentDescription = label
                        stateDescription = "$percent%"
                        progressBarRangeInfo = ProgressBarRangeInfo(range.progress, 0f..1f)
                        if (enabled) setProgress { value -> requests.trySend(value.coerceIn(0f, 1f)); true }
                    }
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val height = trackHeight.toFloat()
                            val thumb = maxOf(48.dp.toPx(), height * ratio).coerceAtMost(height)
                            val travel = (height - thumb).coerceAtLeast(1f)
                            val top = progress * travel
                            // Keep the point grabbed within the thumb under the finger.
                            val grab = if (down.position.y in top..(top + thumb)) down.position.y - top else thumb / 2
                            fun move(y: Float) { requests.trySend(((y - grab) / travel).coerceIn(0f, 1f)) }
                            dragging = true
                            down.consume()
                            move(down.position.y)
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    move(change.position.y)
                                    change.consume()
                                } while (true)
                            } finally { dragging = false }
                        }
                    }) {
                    val thumb = maxOf(48.dp.toPx(), size.height * ratio).coerceAtMost(size.height)
                    val x = if (left) 10.dp.toPx() else size.width - 14.dp.toPx()
                    drawRoundRect(colors.onSurfaceVariant.copy(alpha = alpha * 0.2f), Offset(x, 0f), Size(4.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
                    drawRoundRect(colors.onSurfaceVariant.copy(alpha = alpha), Offset(x, progress * (size.height - thumb)), Size(4.dp.toPx(), thumb), CornerRadius(2.dp.toPx()))
                }
            }
        }
        Box(Modifier.size(48.dp)) {
            if (showBottom) IconButton(onClick = { requests.trySend(1f) }, enabled = enabled, modifier = Modifier.fillMaxSize().testTag("scroll-bottom")) {
                Box(Modifier.fillMaxSize(), contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd) {
                    Box(Modifier.size(24.dp).background(colors.surface.copy(alpha = 0.94f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_arrow_down), stringResource(R.string.scroll_bottom), Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
