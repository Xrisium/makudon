package io.github.xrisium.makudon.feature.workspace

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*
import kotlinx.coroutines.CancellationException

private val imageMimes = arrayOf("image/jpeg", "image/png", "image/webp")

@Composable
internal fun ImageThumbnail(model: WorkspaceViewModel, target: WorkspaceViewModel.ImageTarget, modifier: Modifier = Modifier, pixels: Int = 128, showError: Boolean = false) {
    val info = target.entry.image ?: return
    var bitmap by remember(info.sha256, pixels, target.entry.unreadable) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(info.sha256, pixels) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(info.sha256, pixels, retry, target.entry.unreadable) {
        failed = false
        if (target.entry.unreadable) { failed = true; return@LaunchedEffect }
        try { bitmap = model.images.load(info, pixels) { model.imageFile(target) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) Image(loaded.asImageBitmap(), target.entry.filename, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (failed && showError) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.image_unavailable), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { if (target.entry.unreadable) model.reload(); retry++ }) { Text(stringResource(R.string.retry)) }
        } else Glyph(R.drawable.ic_image, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ImagePanelPreview(model: WorkspaceViewModel, entry: Entry, information: Boolean = false) {
    val info = entry.image ?: return
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val usableHeight = (windowHeight - insets.calculateTopPadding() - insets.calculateBottomPadding()).coerceAtLeast(0.dp)
    val widthLimit = with(density) { (if (information) 280.dp else 240.dp).roundToPx() }
    val heightLimit = with(density) { minOf(if (information) 200.dp else 144.dp, usableHeight / 4).roundToPx() }.coerceAtLeast(1)
    val ratio = info.displayWidth.toFloat() / info.displayHeight
    // DropdownMenu queries intrinsic sizes. A regular Layout supports those queries;
    // BoxWithConstraints would introduce unsupported subcomposition inside the menu.
    Layout(modifier = Modifier.fillMaxWidth(), content = {
        ImageThumbnail(model, WorkspaceViewModel.ImageTarget(entry), Modifier.clip(RoundedCornerShape(12.dp)).testTag(if (information) "image-info-preview" else "image-menu-preview"),
            pixels = maxOf(widthLimit, heightLimit), showError = true)
    }) { children, constraints ->
        val available = minOf(widthLimit, if (constraints.hasBoundedWidth) constraints.maxWidth else widthLimit).coerceAtLeast(1)
        val width = minOf(available, (heightLimit * ratio).toInt().coerceAtLeast(1))
        val height = (width / ratio).toInt().coerceIn(1, heightLimit)
        val child = children.single().measure(Constraints.fixed(width, height))
        val containerWidth = constraints.constrainWidth(available)
        layout(containerWidth, constraints.constrainHeight(height)) { child.placeRelative((containerWidth - width) / 2, 0) }
    }
}

@Composable
internal fun MarkdownImage(model: WorkspaceViewModel, node: MdElement, modifier: Modifier = Modifier) {
    val key = ImageReferences.key(node.destination)
    val entry = key?.let(model::imageEntry)
    if (entry == null) {
        Box(modifier.fillMaxWidth().heightIn(min = 72.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.image_unavailable), style = MaterialTheme.typography.bodySmall)
        }
    } else {
        val info = checkNotNull(entry.image)
        BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val height = (maxWidth * info.displayHeight.toFloat() / info.displayWidth).coerceAtMost(320.dp)
            val pixels = with(LocalDensity.current) { maxOf(maxWidth, height).roundToPx() }
            ImageThumbnail(model, WorkspaceViewModel.ImageTarget(entry), Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp)).clickable { model.viewImage(entry) }.testTag("markdown-image-$key"), pixels, showError = true)
        }
    }
}

@Composable
internal fun DeviceImageImportButton(model: WorkspaceViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), model::importImage)
    Surface(Modifier.fillMaxWidth().clickable(enabled = !model.ui.busy && !model.ui.transitioning) {
        if (model.prepareImageImport(false)) picker.launch(imageMimes)
    }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(24.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_image, Modifier.size(26.dp)); Spacer(Modifier.width(18.dp))
            Text(stringResource(R.string.image_import), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Glyph(R.drawable.ic_chevron_right)
        }
    }
}

@Composable
internal fun ImageDialogs(model: WorkspaceViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), model::importImage)
    if (model.imageSourceDialog) AlertDialog(onDismissRequest = model::dismissImageDialogs,
        title = { Text(stringResource(R.string.image_insert)) }, text = {
            Column {
                TextButton(onClick = { if (model.prepareImageImport(true)) picker.launch(imageMimes) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.image_from_device)) }
                TextButton(onClick = model::chooseLibraryImage, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.image_from_library)) }
            }
        }, confirmButton = { TextButton(onClick = model::dismissImageDialogs) { Text(stringResource(R.string.cancel)) } })
    if (model.imageLibraryDialog) {
        var query by remember { mutableStateOf("") }
        val workspace = model.ui.workspace
        val entries = workspace?.entries.orEmpty().filter { it.isImage && it.name.contains(query, true) }
        AlertDialog(onDismissRequest = model::dismissImageDialogs, title = { Text(stringResource(R.string.image_from_library)) }, text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.image_search)) }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (entries.isEmpty()) item { Text(stringResource(R.string.image_library_empty), Modifier.padding(vertical = 20.dp)) }
                    items(entries, key = { it.id }) { entry ->
                        Row(Modifier.fillMaxWidth().clickable { model.insertLibraryImage(entry) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ImageThumbnail(model, WorkspaceViewModel.ImageTarget(entry), Modifier.size(40.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(entry.filename); Text(workspace?.path(entry.parent ?: Workspace.ROOT).orEmpty(), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = model::dismissImageDialogs) { Text(stringResource(R.string.cancel)) } })
    }
    if (model.cacheDialog) AlertDialog(onDismissRequest = model::closeCache, title = { Text(stringResource(R.string.image_cache)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Formatter.formatFileSize(LocalContext.current, model.cacheBytes))
            Text(stringResource(R.string.image_cache_hint))
            if (model.cacheBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (model.cacheError) Text(stringResource(R.string.image_cache_error), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = model::clearCache, enabled = !model.cacheBusy && model.cacheBytes > 0) { Text(stringResource(R.string.image_cache_clear)) } },
        dismissButton = { TextButton(onClick = model::closeCache, enabled = !model.cacheBusy) { Text(stringResource(R.string.close)) } })
    model.imageViewer?.let { ImageViewer(model, it) }
}

@Composable
private fun ImageViewer(model: WorkspaceViewModel, target: WorkspaceViewModel.ImageTarget) {
    var bitmap by remember(target) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(target) { mutableStateOf(false) }
    var retry by remember(target) { mutableIntStateOf(0) }
    LaunchedEffect(target, retry) {
        failed = false
        try { bitmap = model.images.full(checkNotNull(target.entry.image)) { model.imageFile(target) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Dialog(onDismissRequest = model::closeImage, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                PageBar(target.entry.filename, model::closeImage) { Tool(R.drawable.ic_download, stringResource(R.string.image_export), enabled = !model.ui.busy, onClick = model::exportImage) }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("image-viewer"), contentAlignment = Alignment.Center) {
                    val loaded = bitmap
                    if (loaded != null) {
                        var scale by remember(target) { mutableFloatStateOf(1f) }
                        var offset by remember(target) { mutableStateOf(Offset.Zero) }
                        var viewport by remember { mutableStateOf(IntSize.Zero) }
                        fun bounded(value: Offset, zoom: Float): Offset {
                            val fit = minOf(viewport.width.toFloat() / loaded.width, viewport.height.toFloat() / loaded.height)
                            val x = ((loaded.width * fit * zoom - viewport.width) / 2).coerceAtLeast(0f)
                            val y = ((loaded.height * fit * zoom - viewport.height) / 2).coerceAtLeast(0f)
                            return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
                        }
                        val transform = rememberTransformableState { zoom, pan, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f); offset = bounded(offset + pan, scale)
                        }
                        Image(loaded.asImageBitmap(), target.entry.filename, Modifier.fillMaxSize().onSizeChanged { viewport = it; offset = bounded(offset, scale) }
                            .transformable(transform).pointerInput(target) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
                            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
                    } else if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.image_unavailable)); TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
                    } else CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
internal fun ImageEntryDetails(model: WorkspaceViewModel, entry: Entry, workspace: Workspace) {
    val info = entry.image ?: return
    val calendar = rememberRecentCalendar()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(entry.filename)
        ImagePanelPreview(model, entry, information = true)
        Text(stringResource(R.string.image_details, info.format.extension.uppercase(java.util.Locale.ROOT), info.width, info.height,
            Formatter.formatFileSize(LocalContext.current, entry.bytes.toLong()), displayPath(workspace, entry.parent ?: Workspace.ROOT), detailTimestamp(entry.created, calendar.zone), detailTimestamp(entry.modified, calendar.zone)))
    }
}

@Composable
internal fun ImageDeleteImpact(model: WorkspaceViewModel) {
    if (model.deleteAffected.isNotEmpty()) {
        Text(stringResource(R.string.image_delete_affected), color = MaterialTheme.colorScheme.error)
        model.deleteAffected.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
