package io.github.xrisium.makudon.feature.workspace

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.Entry
import kotlin.math.abs

// Outgoing content remains drawable, but cannot receive touch or accessibility actions.
internal fun Modifier.blockInteractions(blocked: Boolean): Modifier = if (!blocked) this else
    clearAndSetSemantics { }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

@Composable
internal fun WorkspacePages(
    model: WorkspaceViewModel,
    screens: SaveableStateHolder,
    onAction: (Entry, String) -> Unit,
    onNewFolder: () -> Unit,
    secondary: @Composable (Page) -> Unit,
) {
    val ui = model.ui
    // All three main tabs share one secondary-navigation destination.
    val destination = if (ui.page in MainPages) Page.Recent else ui.page
    val transition = updateTransition(destination, label = "workspace-page")
    val moving = ui.transitioning || transition.currentState != transition.targetState || transition.isRunning
    val distance = with(LocalDensity.current) { 24.dp.roundToPx() }
    LaunchedEffect(ui.navigationId, transition.currentState, transition.targetState, transition.isRunning) {
        if (transition.currentState == transition.targetState && !transition.isRunning) {
            model.finishTransition(ui.navigationId)
        }
    }
    transition.AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val duration = if (ui.backwards) 200 else 220
            val direction = if (ui.backwards) -1 else 1
            (slideInHorizontally(tween(duration, easing = FastOutSlowInEasing)) { distance * direction } +
                fadeIn(tween(duration))) togetherWith
                (slideOutHorizontally(tween(duration, easing = FastOutSlowInEasing)) { -distance * direction } +
                    fadeOut(tween(duration))) using null
        },
        contentAlignment = Alignment.TopStart,
    ) { page ->
        screens.SaveableStateProvider(page.name) {
            Box(Modifier.fillMaxSize().blockInteractions(moving || page != destination)) {
                if (page == Page.Recent) MainWorkspace(model, screens, onAction, onNewFolder)
                else secondary(page)
            }
        }
    }
}

@Composable
private fun MainWorkspace(
    model: WorkspaceViewModel,
    screens: SaveableStateHolder,
    onAction: (Entry, String) -> Unit,
    onNewFolder: () -> Unit,
) {
    val ui = model.ui
    val workspace = ui.workspace ?: return
    val transition = updateTransition(ui.mainTab, label = "main-tab")
    val moving = ui.tabTransitioning || transition.currentState != transition.targetState || transition.isRunning
    val distance = with(LocalDensity.current) { 48.dp.toPx() }
    val edge = with(LocalDensity.current) { 24.dp.toPx() }
    LaunchedEffect(ui.tabTransitionId, transition.currentState, transition.targetState, transition.isRunning) {
        if (transition.currentState == transition.targetState && !transition.isRunning) {
            model.finishTabTransition(ui.tabTransitionId)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().discreteTabSwipe(
            enabled = !ui.busy && !ui.transitioning && !moving && ui.page in MainPages,
            minimumDistance = distance,
            edgeInset = edge,
            onSwipe = { direction ->
                val index = MainPages.indexOf(model.ui.mainTab) + direction
                MainPages.getOrNull(index)?.let(model::selectTab)
            },
        )) {
            transition.AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val direction = if (MainPages.indexOf(targetState) > MainPages.indexOf(initialState)) 1 else -1
                    // Every accepted event travels one viewport, even Directory -> Settings.
                    // The intermediate tab is never composed for this transition.
                    slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it * direction } togetherWith
                        slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it * direction } using null
                },
            ) { page ->
                screens.SaveableStateProvider("tab-${page.name}") {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .blockInteractions(moving || ui.page !in MainPages || page != ui.mainTab)) {
                        when (page) {
                            Page.Directory -> DirectoryPage(model, onAction, onNewFolder)
                            Page.Settings -> SettingsPage(model)
                            else -> RecentPage(workspace, ui.busy,
                                { model.open(it) }, { model.newDocument(Page.Recent) })
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val indicatorPosition by transition.animateFloat(
            transitionSpec = { tween(240, easing = FastOutSlowInEasing) }, label = "tab-indicator-position",
        ) { MainPages.indexOf(it).toFloat() }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
            val tabWidth = (maxWidth - 16.dp) / MainPages.size
            // One opaque background moves with the page transition; separate fading
            // backgrounds would briefly lose emphasis and discolor the labels.
            Box(Modifier.matchParentSize()) {
                Box(Modifier.offset { IntOffset(((tabWidth + 8.dp) * indicatorPosition).roundToPx(), 0) }.width(tabWidth).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)))
            }
            val labels = listOf(R.string.directory, R.string.recent, R.string.settings)
            val icons = listOf(R.drawable.ic_folder_tree, R.drawable.ic_clock_3, R.drawable.ic_settings_2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MainPages.forEachIndexed { index, page ->
                    val selected = page == ui.mainTab
                    val foreground by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                        tween(240, easing = FastOutSlowInEasing), label = "tab-foreground",
                    )
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                        .selectable(selected, enabled = !ui.busy || ui.savingPreferences, role = Role.Tab, onClick = {
                            if (!moving) model.selectTab(page)
                        }).padding(vertical = 11.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Glyph(icons[index], tint = foreground)
                        Text(stringResource(labels[index]), style = MaterialTheme.typography.labelSmall, color = foreground)
                    }
                }
            }
        }
    }
}

// Gesture recognition changes no page offset or enabled state. Only an accepted
// pointer-up emits navigation. Children keep vertical scroll, selection and menus.
private fun Modifier.discreteTabSwipe(
    enabled: Boolean,
    minimumDistance: Float,
    edgeInset: Float,
    onSwipe: (Int) -> Unit,
): Modifier = pointerInput(enabled, minimumDistance, edgeInset) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var cancelled = down.position.x < edgeInset || down.position.x > size.width - edgeInset
        var horizontal = false
        var released = false
        while (!released) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val delta = change.position - down.position
            if (event.changes.any { it.id != down.id && it.pressed }) cancelled = true
            // Compose delivers platform cancellation as an already-consumed release.
            // Inspect it before consuming our own accepted horizontal movement.
            if (!change.pressed && change.isConsumed) cancelled = true
            if (!cancelled && !horizontal) {
                if (change.isConsumed || (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) >= abs(delta.x))) {
                    cancelled = true
                } else if (abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) >= abs(delta.y) * 1.5f) {
                    horizontal = true
                }
            }
            if (!cancelled && horizontal) change.consume()
            released = !change.pressed
            if (released && !cancelled && horizontal &&
                abs(delta.x) >= maxOf(minimumDistance, viewConfiguration.touchSlop * 2) &&
                abs(delta.x) >= abs(delta.y) * 1.5f) {
                onSwipe(if (delta.x < 0) 1 else -1)
            }
        }
    }
}
