package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.contextmenu.data.*
import androidx.compose.foundation.text.contextmenu.provider.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CompletableDeferred

/** Native clipboard callbacks with a body-scoped lifecycle and no external text processors. */
internal class EditorSelectionMenu : TextContextMenuProvider {
    internal class Session(val data: TextContextMenuDataProvider) : TextContextMenuSession {
        val closed = CompletableDeferred<Unit>()
        override fun close() { closed.complete(Unit) }
    }
    var session by mutableStateOf<Session?>(null)
        private set

    fun dismiss() { session?.close(); session = null }

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        dismiss()
        val current = Session(dataProvider)
        session = current
        try { current.closed.await() }
        finally { if (session === current) session = null }
    }
}

@Composable
internal fun EditorSelectionMenuHost(menu: EditorSelectionMenu, content: @Composable () -> Unit) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    androidx.activity.compose.BackHandler(menu.session != null, onBack = menu::dismiss)
    DisposableEffect(menu) { onDispose { menu.dismiss() } }
    Box(Modifier.fillMaxSize().onGloballyPositioned { coordinates = it }) {
        CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides menu, LocalTextContextMenuDropdownProvider provides menu, content = content)
        val session = menu.session
        val anchor = coordinates
        if (session != null && anchor?.isAttached == true) {
            val keys = listOf(TextContextMenuKeys.CutKey, TextContextMenuKeys.CopyKey, ReadingBodyCopyKey, TextContextMenuKeys.PasteKey, TextContextMenuKeys.SelectAllKey)
            val items = session.data.data().components.filterIsInstance<TextContextMenuItem>().filter { it.key in keys }.sortedBy { keys.indexOf(it.key) }
            if (items.isNotEmpty()) {
                val bounds = session.data.contentBounds(anchor).translate(anchor.positionInWindow())
                val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
                val position = object : PopupPositionProvider {
                    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                        val x = (bounds.center.x.toInt() - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                        val above = bounds.top.toInt() - popupContentSize.height - gap
                        val y = if (above >= 0) above else bounds.bottom.toInt() + gap
                        return IntOffset(x, y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
                    }
                }
                Popup(popupPositionProvider = position, onDismissRequest = menu::dismiss, properties = PopupProperties(focusable = false)) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            items.forEach { item ->
                                Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable { item.onClick(session) }.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                                    Text(item.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
