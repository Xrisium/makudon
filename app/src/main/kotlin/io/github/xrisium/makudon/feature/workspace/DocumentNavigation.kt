package io.github.xrisium.makudon.feature.workspace

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.NavigationSide
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class DocumentScrollRange(val viewport: Float, val extent: Float, val position: Float) {
    val maximum get() = (extent - viewport).coerceAtLeast(0f)
    val progress get() = if (maximum > 0) (position / maximum).coerceIn(0f, 1f) else 0f
}

/** The rail never owns touches: only the visible thumb can start a seek. */
@Composable
internal fun BoxScope.DocumentNavigation(model: WorkspaceViewModel, range: DocumentScrollRange,
    scrolling: Boolean, userDragging: Boolean, seek: suspend (Float) -> Unit) {
    val currentSeek by rememberUpdatedState(seek)
    val requests = remember { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(requests) { for (value in requests) currentSeek(value) }
    DisposableEffect(requests) { onDispose { requests.close() } }
    if (range.viewport <= 0 || range.maximum < range.viewport * 3) return
    val enabled = !model.ui.busy && !model.ui.transitioning && !model.ui.storageBlocked
    val left = model.ui.workspace?.navigationSide == NavigationSide.Left
    val side = if (left) Alignment.CenterStart else Alignment.CenterEnd
    val colors = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var activity by remember { mutableLongStateOf(0L) }
    fun reveal() { visible = true; activity = System.nanoTime() }
    val position by rememberUpdatedState(range.position)
    val moving by rememberUpdatedState(scrolling)
    val gesture by rememberUpdatedState(userDragging)
    val viewport by rememberUpdatedState(range.viewport)
    LaunchedEffect(Unit) {
        var start = position
        var time = System.nanoTime()
        var wasMoving = false
        var userScroll = false
        snapshotFlow { Triple(position, moving, gesture) }.collect { (at, movingNow, draggingNow) ->
            val now = System.nanoTime()
            if (!movingNow) userScroll = false
            if (draggingNow) userScroll = true
            if (!userScroll || !movingNow || !wasMoving || now - time > 800_000_000L) { start = at; time = now }
            else if (!dragging && abs(at - start) >= viewport * 0.45f) { reveal(); start = at; time = now }
            wasMoving = movingNow
        }
    }
    LaunchedEffect(activity, dragging) { if (visible && !dragging) { delay(3000); visible = false } }
    val alpha by animateFloatAsState(if (visible) 0.95f else 0f, label = "navigation-opacity")
    var height by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val thumb = maxOf(with(density) { 48.dp.toPx() }, height * (range.viewport / range.extent).coerceIn(0.06f, 0.5f)).coerceAtMost(height.toFloat())
    val travel by rememberUpdatedState((height - thumb).coerceAtLeast(1f))
    val progress by rememberUpdatedState(range.progress)
    Box(Modifier.align(side).width(48.dp).fillMaxHeight().padding(vertical = 4.dp).onSizeChanged { height = it.height }.testTag("document-navigation")) {
        Canvas(Modifier.matchParentSize().testTag("fast-scroller")) {
            val x = if (left) 10.dp.toPx() else size.width - 14.dp.toPx()
            drawRoundRect(colors.onSurfaceVariant.copy(alpha = alpha * 0.2f), Offset(x, 0f), Size(4.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
            drawRoundRect(colors.onSurfaceVariant.copy(alpha = alpha), Offset(x, progress * travel), Size(4.dp.toPx(), thumb), CornerRadius(2.dp.toPx()))
        }
        if (visible && thumb > 0) {
            val label = stringResource(R.string.fast_scroll)
            Box(Modifier.offset { IntOffset(0, (progress * travel).roundToInt()) }.fillMaxWidth().height(with(density) { thumb.toDp() })
                .testTag("fast-scroll-thumb").semantics {
                    contentDescription = label
                    stateDescription = "${(range.progress * 100).roundToInt()}%"
                    progressBarRangeInfo = ProgressBarRangeInfo(range.progress, 0f..1f)
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val initial = progress
                        var delta = 0f
                        dragging = true
                        try {
                            val first = awaitVerticalTouchSlopOrCancellation(down.id) { change, over -> delta = over; change.consume() } ?: return@awaitEachGesture
                            fun move() { requests.trySend((initial + delta / travel).coerceIn(0f, 1f)) }
                            first.consume(); move()
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                delta += change.positionChange().y
                                move(); change.consume()
                            }
                        } finally { dragging = false; reveal() }
                    }
                })
        }
    }
}
