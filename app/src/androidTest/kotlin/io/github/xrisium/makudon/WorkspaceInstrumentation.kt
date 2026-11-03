package io.github.xrisium.makudon

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.text.input.TextFieldState
import io.github.xrisium.makudon.app.MakudonApplication
import io.github.xrisium.makudon.domain.*
import io.github.xrisium.makudon.feature.workspace.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import io.github.xrisium.makudon.data.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/** Small platform-only runner: real accessibility actions and private synthetic data, no product debug controls. */
class WorkspaceInstrumentation : Instrumentation() {
    private lateinit var model: WorkspaceViewModel
    private val checks = mutableListOf<String>()
    private var profileMarkdownLong = false
    private var onlyMarkdownLong = false
    private var onlyMarkdownToolbar = false
    private var onlyMarkdownVisual = false
    private var onlyMarkdown = false
    private var onlyMarkdownTable = false
    private var onlyLong = false
    private var onlyMotion = false
    private var onlyVisual = false
    private var onlyTabs = false
    private var onlyAppearance = false
    private var onlySelection = false
    private var onlySelectionVisual = false
    private var onlyRecent = false
    private var onlyDetails = false
    private var onlyTextSize = false
    private var onlyBackup = false
    private var onlyBackupVisual = false
    private var onlyFiles = false
    private var onlyExportDialog = false
    private var onlyLocalization = false
    private var localizationRestartPhase: String? = null
    private var onlyTrash = false
    private var trashRestartPhase: String? = null
    private var currentActivity: Activity? = null
    override fun callActivityOnCreate(activity: Activity, savedInstanceState: Bundle?) { super.callActivityOnCreate(activity, savedInstanceState); currentActivity = activity }
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        profileMarkdownLong = arguments?.getString("profile") == "true"
        onlyMarkdownToolbar = arguments?.getString("case") == "markdown-toolbar"
        onlyMarkdownLong = arguments?.getString("case") == "markdown-long"
        onlyMarkdownVisual = arguments?.getString("case") == "markdown-visual"
        onlyMarkdown = arguments?.getString("case") == "markdown"
        onlyMarkdownTable = arguments?.getString("case") == "markdown-table"
        onlyLong = arguments?.getString("case") == "long"
        onlyMotion = arguments?.getString("case") == "motion"
        onlyVisual = arguments?.getString("case") == "motion-visual"
        onlyTabs = arguments?.getString("case") == "tabs"
        onlyAppearance = arguments?.getString("case") == "appearance"
        onlySelection = arguments?.getString("case") == "selection"
        onlySelectionVisual = arguments?.getString("case") == "selection-visual"
        onlyRecent = arguments?.getString("case") == "recent"
        onlyDetails = arguments?.getString("case") == "details"
        onlyTextSize = arguments?.getString("case") == "text-size"
        onlyBackup = arguments?.getString("case") == "backup"
        onlyBackupVisual = arguments?.getString("case") == "backup-visual"
        onlyFiles = arguments?.getString("case") == "files"
        onlyExportDialog = arguments?.getString("case") == "export-dialog"
        onlyLocalization = arguments?.getString("case") == "localization"
        localizationRestartPhase = arguments?.getString("case")?.takeIf { it == "localization-restart-seed" || it == "localization-restart-verify" }
        onlyTrash = arguments?.getString("case") == "trash"
        trashRestartPhase = arguments?.getString("case")?.takeIf { it == "trash-restart-seed" || it == "trash-restart-verify" }
        start()
    }
    override fun onStart() {
        try {
            if (onlyMarkdown || onlyMarkdownToolbar || onlyMarkdownTable) uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("language bootstrap and first workspace frame") {
                val label = main { currentActivity?.getString(R.string.recent) }
                label != null && nodes().any { it.text?.toString() == label }
            }
            model = (targetContext.applicationContext as MakudonApplication).workspace
            ready()
            val originalLanguage = main { androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags() }
            if (!onlyLocalization && localizationRestartPhase == null) {
                main { model.changeLanguage("zh-Hans") }; ready()
                waitFor("Chinese baseline") { main { model.languages.selected == "zh-Hans" } }
            }
            when { onlyMarkdownTable -> markdownTableTest(); onlyMarkdownToolbar -> markdownToolbarTest(); onlyMarkdownLong -> markdownLongTest(); onlyMarkdownVisual -> markdownVisualTest(); onlyMarkdown -> markdownTest(); onlyTextSize -> textSizeTest(); localizationRestartPhase != null -> localizationRestartTest(checkNotNull(localizationRestartPhase)); onlyLocalization -> localizationTest(); trashRestartPhase != null -> trashRestartTest(checkNotNull(trashRestartPhase)); onlyTrash -> trashTest(); onlyExportDialog -> exportDialogTest(); onlyFiles -> fileExchangeTest(); onlyBackupVisual -> backupVisualTest(); onlyBackup -> backupTest(); onlyDetails -> detailsTest(); onlyRecent -> recentTest(); onlySelectionVisual -> selectionVisualTest(); onlySelection -> selectionTest(); onlyAppearance -> appearanceTest(); onlyTabs -> tabTest(); onlyVisual -> motionVisualTest(); onlyMotion -> motionTest(); onlyLong -> longDocumentTest(); else -> exercise() }
            if (!onlyLocalization && localizationRestartPhase == null) runBlocking { withContext(Dispatchers.Main) { model.languages.change(originalLanguage) } }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPassed ${checks.size} checks:\n" + checks.joinToString("\n") + "\n") })
        } catch (e: Exception) {
            screenshot("failure")
            if (::model.isInitialized) sendStatus(0, Bundle().apply { putString("stream", main { "Diagnostics: page=${model.ui.page}, busy=${model.ui.busy}, transitioning=${model.ui.transitioning}, tabTransitioning=${model.ui.tabTransitioning}, phase=${model.save?.status?.value}, revision=${model.save?.revision}, saved=${model.save?.savedRevision}, bufferLength=${model.textState?.text?.length}, latestLength=${model.save?.latest?.length}, error=${model.ui.error}\n" }) })
            val tabs = nodes().filter { it.text?.toString() in listOf("目录", "最近", "设置") }
            sendStatus(0, Bundle().apply { putString("stream", "Tab semantics: " + tabs.joinToString { "${it.text}: enabled=${it.isEnabled}, clickable=${it.isClickable}, parentClickable=${it.parent?.isClickable}" } + "\n") })
            val tools = nodes().filter { it.contentDescription?.toString() in listOf("搜索文稿", "新建文稿", "创建目录", "新建文件夹") }
            sendStatus(0, Bundle().apply { putString("stream", "Tool semantics: " + tools.joinToString { "${it.contentDescription}: enabled=${it.isEnabled}, clickable=${it.isClickable}, parentEnabled=${it.parent?.isEnabled}, parentClickable=${it.parent?.isClickable}" } + "\n") })
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nFAILED after ${checks.size} checks: ${e.javaClass.simpleName}: ${e.message}\n" + e.stackTrace.take(5).joinToString("\n")); putString("failure", e.message) })
        }
    }
    private fun <T> main(block: () -> T): T { val result = AtomicReference<T>(); runOnMainSync { result.set(block()) }; return result.get() }
    private fun waitFor(label: String, timeoutMs: Long = 12_000, condition: () -> Boolean) {
        val start = SystemClock.uptimeMillis()
        while (!condition()) { if (SystemClock.uptimeMillis() - start > timeoutMs) error("Timed out: $label"); SystemClock.sleep(40) }
        waitForIdleSync()
    }
    private fun ready() { waitFor("workspace ready") { main { !model.ui.busy && !model.ui.transitioning && !model.ui.tabTransitioning } }; SystemClock.sleep(350); waitForIdleSync() }
    private fun verify(value: Boolean, label: String) { check(value) { label }; checks += label; sendStatus(0, Bundle().apply { putString("stream", "PASS ${checks.size}: $label\n") }) }
    private fun nodes(): List<AccessibilityNodeInfo> {
        // Restore steps replace large semantic subtrees without changing Activity/window IDs.
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        val root = uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { result += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        visit(root)
        if (onlyMarkdown || onlyMarkdownToolbar || onlyMarkdownTable) {
            val activeLayer = uiAutomation.windows.firstOrNull { it.id == root.windowId }?.layer ?: 0
            uiAutomation.windows.filter { it.layer > activeLayer && it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }.forEach { window -> window.root?.let(::visit) }
        }
        return result
    }
    private fun find(label: String): AccessibilityNodeInfo {
        var match: AccessibilityNodeInfo? = null
        waitFor("node $label") { match = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }; match != null }
        return checkNotNull(match)
    }
    private fun click(label: String) {
        waitFor("clickable $label") {
            val found = nodes().asSequence().filter { it.text?.toString() == label || it.contentDescription?.toString() == label }.mapNotNull { start ->
                var candidate: AccessibilityNodeInfo? = start
                while (candidate != null && !candidate.isClickable) candidate = candidate.parent
                candidate?.takeIf { it.isEnabled }
            }.firstOrNull()
            found?.let { it.refresh(); it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
        }
        SystemClock.sleep(350); waitForIdleSync()
    }
    private fun setText(label: String, text: String) {
        val labelled = find(label)
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> editable(child)?.let { return it } }
            return null
        }
        val labelBounds = Rect(); labelled.getBoundsInScreen(labelBounds)
        val node = editable(labelled) ?: nodes().filter { it.isEditable }.minByOrNull { candidate ->
            val bounds = Rect(); candidate.getBoundsInScreen(bounds)
            kotlin.math.abs(bounds.centerY() - labelBounds.centerY()) + kotlin.math.abs(bounds.centerX() - labelBounds.centerX())
        } ?: error("Missing editable node for $label")
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.refresh()
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })) { "Set text rejected: $label" }
    }
    private fun longPress(label: String, physical: Boolean = false) {
        SystemClock.sleep(250)
        var node = find(label); node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(250); node = find(label); node.refresh()
        if (!physical) {
            waitFor("stable long press action $label") {
                nodes().filter { it.text?.toString() == label || it.contentDescription?.toString() == label }.any { candidate ->
                    var current: AccessibilityNodeInfo? = candidate
                    while (current != null && !current.isLongClickable) current = current.parent
                    current?.let { it.refresh(); it.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) } == true
                }
            }
            waitFor("context menu") { nodes().any { it.text?.toString() == "重命名" } }
            find(label)
            return
        }
        val rect = Rect(); node.getBoundsInScreen(rect)
        val time = SystemClock.uptimeMillis(); val x = rect.centerX().toFloat(); val y = rect.centerY().toFloat()
        fun event(action: Int) = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        event(MotionEvent.ACTION_DOWN).let { uiAutomation.injectInputEvent(it, true); it.recycle() }
        SystemClock.sleep(700)
        event(MotionEvent.ACTION_UP).let { uiAutomation.injectInputEvent(it, true); it.recycle() }
        waitFor("context menu") { nodes().any { it.text?.toString() == "重命名" } }
        find(label)
    }
    private fun fileResult(): String? = main {
        val state = model.files.ui
        state.message?.let { if (state.messageArgument == null) model.languages.context().getString(it) else model.languages.context().getString(it, state.messageArgument) }
    }
    private fun screenshot(name: String) { uiAutomation.takeScreenshot()?.let { bitmap -> File(targetContext.cacheDir, "workspace-test-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() } }
    private fun saved(timeoutMs: Long = 12_000) = waitFor("current revision saved", timeoutMs) { main { model.save?.status?.value?.phase == SavePhase.Saved && model.save?.latest == model.textState?.text?.toString() } }
    private fun workspaceDirectory(): File {
        val pointer = File(targetContext.filesDir, "library-state.properties")
        if (!pointer.exists()) return File(targetContext.filesDir, "workspace")
        val active = java.util.Properties().apply { pointer.reader().use { load(it) } }.getProperty("active")
        return File(targetContext.filesDir, if (active == "legacy") "workspace" else "library-generations/$active")
    }
    private fun currentStore() = (WorkspaceViewModel::class.java.getDeclaredField("library").apply { isAccessible = true }.get(model) as io.github.xrisium.makudon.data.LibraryController).store

    private fun textSizeTest() {
        val oldSize = main { checkNotNull(model.ui.workspace).editorTextSize }
        val oldFont = shell("settings get system font_scale")
        val oldAnimation = shell("settings get global animator_duration_scale")
        val oldScreen = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val ids = mutableListOf<String>()
        fun menu() { click(main { model.languages.context().getString(R.string.item_actions) }) }
        fun bounds(label: String) = Rect().also { find(label).getBoundsInScreen(it) }
        fun equalWidths(labels: List<String>) {
            val boxes = labels.map(::bounds)
            verify(boxes.maxOf { it.width() } - boxes.minOf { it.width() } <= 1, "Equal final segment widths: $labels")
            verify(boxes.zipWithNext().all { (a, b) -> a.right <= b.left }, "Non-overlapping segments: $labels")
        }
        try {
            runBlocking { withContext(Dispatchers.IO) {
                val store = currentStore()
                ids += store.create("字号测试", Workspace.ROOT, DocumentKind.Plain, "午后，阳光落在窗边。\n\n这样的日子适合慢慢写字。😀")
                ids += store.create("排版字号", Workspace.ROOT, DocumentKind.Markdown, "# 标题\n\n正文段落\n\n## 次级标题")
            } }
            main { model.reload() }; ready(); main { model.open(ids[0]) }; ready()
            main { model.preferences(editorTextSize = EditorTextSize.Medium) }; ready(); menu()
            equalWidths(listOf("正文大小: 小", "正文大小: 中", "正文大小: 大"))
            val actionLabels = listOf("详细信息", "重命名", "导出 .txt 副本", "删除")
            val actionBounds = actionLabels.map(::bounds)
            val ioLock = WorkspaceViewModel::class.java.getDeclaredField("ioLock").apply { isAccessible = true }.get(model) as kotlinx.coroutines.sync.Mutex
            val beforePreference = checkNotNull(uiAutomation.takeScreenshot())
            runBlocking { ioLock.lock() }
            try {
                click("正文大小: 小")
                verify(main { model.ui.busy && model.ui.savingPreferences }, "Delayed text-size write exercises the real preferences gate")
                val duringPreference = checkNotNull(uiAutomation.takeScreenshot())
                try {
                    verify(actionBounds.all { rect ->
                        val before = android.graphics.Bitmap.createBitmap(beforePreference, rect.left, rect.top, rect.width(), rect.height())
                        val during = android.graphics.Bitmap.createBitmap(duringPreference, rect.left, rect.top, rect.width(), rect.height())
                        try { before.sameAs(during) } finally { before.recycle(); during.recycle() }
                    }, "Pending text-size persistence keeps unrelated menu text pixels unchanged")
                } finally { duringPreference.recycle() }
                verify(actionLabels.all { label ->
                    var node: AccessibilityNodeInfo? = find(label)
                    var enabled = true
                    while (node != null) { enabled = enabled && node.isEnabled; node = node.parent }
                    enabled
                }, "Pending text-size persistence keeps menu appearance enabled")
                click("重命名")
                verify(nodes().any { it.text?.toString() == "正文大小" }, "Pending preferences still gate menu actions without closing the menu")
            } finally { ioLock.unlock(); beforePreference.recycle() }
            ready()
            screenshot("text-size-pending-complete")
            verify(main { model.ui.workspace?.editorTextSize == EditorTextSize.Small } && nodes().any { it.text?.toString() == "正文大小" },
                "Preference completion retains the selected size and open menu")
            val smallBox = bounds("正文大小: 小")
            val largeBox = bounds("正文大小: 大")
            val density = targetContext.resources.displayMetrics.density
            val sampleY = smallBox.top + (8 * density).toInt()
            val baseline = checkNotNull(uiAutomation.takeScreenshot())
            val highlightColor = baseline.getPixel(smallBox.centerX(), sampleY)
            baseline.recycle()
            shell("settings put global animator_duration_scale 5")
            SystemClock.sleep(300)
            var largeAction = find("正文大小: 大")
            while (!largeAction.isClickable) largeAction = checkNotNull(largeAction.parent)
            check(largeAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("large size committed") { main { model.ui.workspace?.editorTextSize == EditorTextSize.Large } }
            var crossedIntermediate = false
            repeat(6) {
                val middle = checkNotNull(uiAutomation.takeScreenshot())
                try {
                    val matching = (smallBox.left + 4 until largeBox.right - 4).filter { middle.getPixel(it, sampleY) == highlightColor }
                    if (matching.isNotEmpty() && matching.average() > smallBox.centerX() + smallBox.width() * .1 && matching.average() < largeBox.centerX() - largeBox.width() * .1) {
                        crossedIntermediate = true
                        File(targetContext.cacheDir, "workspace-test-text-size-slide-mid.png").outputStream().use { middle.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    }
                } finally { middle.recycle() }
                SystemClock.sleep(70)
            }
            verify(crossedIntermediate,
                "Single highlight visibly crosses intermediate positions from small directly to large")
            SystemClock.sleep(1500); screenshot("text-size-slide-end")
            shell("settings put global animator_duration_scale 0")
            click("正文大小: 中"); ready()
            verify(main { model.ui.workspace?.editorTextSize == EditorTextSize.Medium }, "Zero animation scale settles selection normally")
            shell("settings put global animator_duration_scale $oldAnimation")
            val field = main { model.textState }
            for ((label, size) in listOf("小" to EditorTextSize.Small, "大" to EditorTextSize.Large, "中" to EditorTextSize.Medium)) {
                click("正文大小: $label"); ready()
                verify(main { model.ui.workspace?.editorTextSize == size && model.textState === field }, "Size $label applies without replacing the input buffer")
                verify(nodes().any { it.text?.toString() == "正文大小" }, "Menu remains open after choosing $label")
            }
            screenshot("text-size-zh")
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); ready()
            setText("编辑正文", "字号切换后继续输入😀"); saved()
            main { model.back() }; ready(); main { model.open(ids[1]) }; ready()
            menu(); click("正文大小: 大"); ready()
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); ready()
            click("排版"); ready(); screenshot("text-size-typeset-large")
            verify(main { model.ui.editorMode == EditorMode.Typeset && model.textState?.text?.toString() == "# 标题\n\n正文段落\n\n## 次级标题" }, "Typeset size preserves exact Markdown source")
            main { currentActivity?.recreate() }; ready()
            verify(main { model.ui.workspace?.editorTextSize == EditorTextSize.Large }, "Size survives Activity recreation")
            main { model.back() }; ready(); main { model.reload() }; ready(); main { model.open(ids[0]) }; ready()
            verify(main { model.ui.workspace?.editorTextSize == EditorTextSize.Large && model.textState?.text?.toString() == "字号切换后继续输入😀" }, "Reload preserves global size and saved text")
            shell("wm size 840x1890"); shell("settings put system font_scale 2.0"); SystemClock.sleep(1200); ready()
            menu(); equalWidths(listOf("正文大小: 小", "正文大小: 中", "正文大小: 大")); screenshot("text-size-zh-200")
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); ready()
            main { model.changeLanguage("en") }; SystemClock.sleep(1200); ready(); menu()
            equalWidths(listOf("Text size: Small", "Text size: Medium", "Text size: Large"))
            verify(listOf("S", "M", "L").all { label -> nodes().any { it.text?.toString() == label } }, "English uses compact S M L labels with full accessible descriptions")
            click("Text size: Small"); ready(); screenshot("text-size-en-200")
            verify(main { model.ui.workspace?.editorTextSize == EditorTextSize.Small }, "English selector applies size at 200% font scale")
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); ready()
        } finally {
            shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
            shell(if (oldAnimation == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $oldAnimation")
            shell(if (oldScreen == null) "wm size reset" else "wm size $oldScreen")
            SystemClock.sleep(700); ready()
            main { model.back() }; ready()
            main { model.preferences(editorTextSize = oldSize) }; ready()
            runBlocking { withContext(Dispatchers.IO) { ids.forEach { currentStore().removeToTrash(listOf(it), java.util.UUID.randomUUID().toString()) } } }
            main { model.reload() }; ready()
        }
    }

    private fun localizationTest() {
        val original = main { androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags() }
        val previousFont = shell("settings get system font_scale")
        val previousSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val ids = mutableListOf<String>()
        fun setLocale(tag: String) {
            val old = currentActivity
            val changed = main { androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags() != tag }
            main { androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(tag)) }
            waitFor("language configuration $tag") { main {
                androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags() == tag && (!changed || old !== currentActivity)
            } }
            SystemClock.sleep(700); ready()
        }
        try {
            @Suppress("DEPRECATION")
            val startup = targetContext.packageManager.getProviderInfo(android.content.ComponentName(targetContext.packageName, "androidx.startup.InitializationProvider"), android.content.pm.PackageManager.GET_META_DATA)
            verify(targetContext.classLoader.loadClass(startup.name) != null && startup.metaData?.containsKey("androidx.emoji2.text.EmojiCompatInitializer") != true, "Runtime startup provider exists without automatic downloadable emoji initialization")
            setLocale("en")
            main { model.go(Page.Settings) }; ready()
            verify(nodes().any { it.text?.toString() == "Settings" } && nodes().any { it.text?.toString() == "Language" }, "English settings resolve in the actual Activity")
            val previousModel = main { (targetContext.applicationContext as MakudonApplication).workspace }
            click("Language"); click("简体中文")
            waitFor("Chinese picker choice") { main { model.languages.selected == "zh-Hans" } }; SystemClock.sleep(700); ready()
            verify(nodes().any { it.text?.toString() == "设置" } && previousModel === main { (targetContext.applicationContext as MakudonApplication).workspace }, "Native-language picker recreates UI and retains the single workspace owner")
            screenshot("locale-settings-zh")
            click("语言"); click("English"); SystemClock.sleep(700); ready()
            verify(main { model.languages.selected == "en" && model.ui.page == Page.Settings }, "Picker preserves the current page across recreation")
            screenshot("locale-settings-en")
            val english = main { model.languages.context().resources }
            verify(english.getQuantityString(R.plurals.selected_count, 0, 0) == "0 items selected" && english.getQuantityString(R.plurals.selected_count, 1, 1) == "1 item selected" && english.getQuantityString(R.plurals.selected_count, 2, 2) == "2 items selected", "English zero, singular and plural forms carry their counts")
            main { model.go(Page.Recent) }; ready(); main { model.newDocument(Page.Recent) }; ready(); main { model.create(DocumentKind.Plain) }; ready()
            val id = main { checkNotNull(model.ui.activeId) }; ids += id
            verify(main { checkNotNull(model.ui.workspace).entry(id)?.name == "Untitled document" }, "New English default name is chosen at creation")
            val field = main { checkNotNull(model.textState) }
            val input = "# Existing 中文 name and 😀 body\n\nPending input survives locale recreation."
            val oldActivity = currentActivity
            main {
                field.edit { replace(0, length, input) }
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("zh-Hans"))
            }
            waitFor("editor locale recreation") { currentActivity !== oldActivity }; ready(); saved()
            verify(main { model.textState === field && field.text.toString() == input && model.ui.activeId == id }, "External language change retains the same editor field, buffer and active document")
            verify(source(id, DocumentKind.Plain) == input && main { checkNotNull(model.ui.workspace).entry(id)?.name == "Untitled document" }, "Pending input is saved without translating existing names or bodies")
            click("Untitled document"); setText("名称", "Pending rename 中文")
            setLocale("en")
            verify(nodes().any { it.text?.toString() == "Pending rename 中文" } && nodes().any { it.text?.toString() == "Rename" }, "Unsubmitted rename text and dialog survive an external locale change")
            click("Cancel")
            main { model.copy(id, false) }; ready(); main { model.paste(Workspace.ROOT) }; ready()
            val copied = main { checkNotNull(model.ui.workspace).entries.single { it.name == "Untitled document (copy)" } }; ids += copied.id
            verify(source(copied.id, copied.kind) == input && copied.id != id, "Android naming resources preserve the copy suffix space and independent body")
            main { model.back() }; ready(); main { model.go(Page.Settings) }; ready()
            main { model.files.text("既有名称.txt", "independent fixed export") }
            waitFor("fixed export prepared") { main { !model.files.ui.busy && model.files.ui.export != null } }
            val export = main { checkNotNull(model.files.ui.export) }
            main { model.files.saveTo(android.net.Uri.parse("content://io.github.xrisium.makudon.debug.test.fixtures/export")) }
            waitFor("export result") { main { !model.files.ui.busy && model.files.ui.message != null } }
            setLocale("zh-Hans")
            verify(main { model.files.ui.export === export && export.name == "既有名称.txt" } && export.file.readText() == "independent fixed export", "A prepared export retains its identity, original name and bytes after changing language")
            verify(nodes().any { it.text?.toString() == "已写入所选位置。" }, "An existing export result is resolved again in the new language")
            main { model.files.close() }
            setLocale("en")
            main { model.backup.prepareExport() }
            waitFor("ZIP prepared") { main { !model.backup.ui.busy && model.backup.ui.exportReady } }
            val zipName = main { model.backup.pendingExportName }
            setLocale("zh-Hans")
            verify(zipName.startsWith("Makudon-backup-") && main { model.backup.pendingExportName == zipName }, "ZIP retry uses the operation's original language and filename")
            main { model.backup.cancel() }; waitFor("ZIP stage released") { main { !model.backup.locked } }
            val before = main { checkNotNull(model.ui.workspace).entries.map { it.id }.toSet() }
            main { currentActivity!!.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse("content://io.github.xrisium.makudon.debug.test.fixtures/markdown"), "text/markdown").setClass(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            waitFor("VIEW import") { main { model.ui.page == Page.Editor && model.ui.activeId !in before } }; ready()
            val imported = main { checkNotNull(model.ui.activeId) }; ids += imported
            setLocale("en")
            verify(main { checkNotNull(model.ui.workspace).entries.count { it.id !in before } == 1 && model.ui.activeId == imported }, "Activity recreation does not replay an external VIEW import")
            main { model.back() }; ready(); main { model.go(Page.Settings) }; ready()
            shell("wm size 840x1890"); shell("settings put system font_scale 2.0"); SystemClock.sleep(900); ready()
            verify(nodes().any { it.text?.toString() == "Language" }, "Language entry stays reachable at 320dp and 200% font size")
            click("Language")
            verify(nodes().any { it.text?.toString() == "English" } && nodes().any { it.text?.toString() == "简体中文" }, "Both self-names remain available in the enlarged picker")
            screenshot("locale-picker-large-en"); click("Close")
            shell(if (previousFont == "null") "settings delete system font_scale" else "settings put system font_scale $previousFont")
            shell(if (previousSize == null) "wm size reset" else "wm size $previousSize")
            setLocale("en-XA"); screenshot("locale-pseudo-expanded")
            verify(main { currentActivity!!.resources.getString(R.string.settings) != "Settings" }, "Debug expanded pseudolocale is generated")
            setLocale("ar-XB"); screenshot("locale-pseudo-rtl")
            verify(main { currentActivity!!.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL }, "Debug RTL pseudolocale exercises layout direction")
            setLocale("en")
            click("Language"); click("System default"); SystemClock.sleep(700); ready()
            verify(main { androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().isEmpty && model.languages.selected.isEmpty() }, "System default clears the explicit platform preference")
            val unsupported = targetContext.createConfigurationContext(android.content.res.Configuration(targetContext.resources.configuration).apply { setLocale(java.util.Locale.FRENCH) })
            verify(unsupported.getString(R.string.settings) == "Settings", "Unsupported language has a complete English resource fallback")
        } finally {
            shell(if (previousFont == "null") "settings delete system font_scale" else "settings put system font_scale $previousFont")
            shell(if (previousSize == null) "wm size reset" else "wm size $previousSize")
            main { model.files.close(); model.backup.cancel() }; SystemClock.sleep(400); ready()
            main { model.go(Page.Directory) }; ready()
            ids.forEach { id -> if (main { model.ui.workspace?.entry(id) != null }) { main { model.delete(id) }; ready() } }
            val discarded = main { model.trashInventory.nodes.filter { it.entry.id in ids }.map { it.id } }
            if (discarded.isNotEmpty()) { main { model.purgeTrash(discarded) }; ready() }
            setLocale(original)
        }
    }

    private fun localizationRestartTest(phase: String) {
        val fixture = File(targetContext.cacheDir, "localization-restart.txt")
        if (phase == "localization-restart-seed") {
            val previous = main { androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags() }
            main { model.changeLanguage("en") }; SystemClock.sleep(700); ready()
            main { model.newDocument(Page.Recent) }; ready(); main { model.create(DocumentKind.Plain) }; ready()
            val id = main { checkNotNull(model.ui.activeId) }
            main { model.textState!!.edit { replace(0, length, "locale restart 中文 😀") } }; saved()
            fixture.writeText("$id\n$previous")
            verify(main { model.languages.selected == "en" }, "Seed explicit English platform preference before a cold restart")
            verify(source(id, DocumentKind.Plain) == "locale restart 中文 😀", "Seed complete synthetic document before a cold restart")
        } else {
            val values = fixture.readText().split('\n')
            val id = values[0]
            waitFor("cold English workspace rendered") { main { model.languages.selected == "en" } && nodes().any { it.text?.toString() == "Recent" } }
            verify(main { model.languages.selected == "en" } && nodes().any { it.text?.toString() == "Recent" }, "Cold process restores explicit English without overriding it from app storage")
            verify(source(id, DocumentKind.Plain) == "locale restart 中文 😀", "Cold language initialization preserves committed document bytes")
            main { model.delete(id) }; ready()
            val removed = main { model.trashInventory.nodes.filter { it.entry.id == id }.map { it.id } }
            if (removed.isNotEmpty()) { main { model.purgeTrash(removed) }; ready() }
            runBlocking { withContext(Dispatchers.Main) { model.languages.change(values.getOrElse(1) { "" }) } }
            fixture.delete()
        }
    }

    private fun trashRestartTest(phase: String) {
        val lock = WorkspaceViewModel::class.java.getDeclaredField("ioLock").apply { isAccessible = true }.get(model) as kotlinx.coroutines.sync.Mutex
        fun <T> disk(action: () -> T): T = runBlocking { withContext(Dispatchers.IO) { lock.withLock { action() } } }
        val fixture = File(targetContext.cacheDir, "trash-restart-fixture.txt")
        if (phase == "trash-restart-seed") {
            val id = disk { currentStore().create("回收站进程重启-" + SystemClock.uptimeMillis(), Workspace.ROOT, DocumentKind.Plain, "进程重启仍完整 😀") }
            main { model.reload() }; ready(); main { model.delete(id) }; ready()
            val deleted = main { model.trashInventory.nodes.single { it.entry.id == id } }
            disk { fixture.writeText("$id\n${deleted.id}", Charsets.UTF_8) }
            verify(main { model.ui.workspace?.entry(id) == null }, "Restart fixture is committed to trash before process death")
        } else {
            val (original, deleted) = disk { fixture.readLines(Charsets.UTF_8) }
            verify(main { model.trashInventory.nodes.any { it.id == deleted } && model.ui.workspace?.entry(original) == null }, "Cold process startup loads persisted trash without resurrecting draft")
            main { model.openTrash() }; ready(); main { model.browseTrash(deleted) }; ready()
            verify(main { model.trashText == "进程重启仍完整 😀" }, "Cold-start trash preview preserves exact Unicode body")
            val oldTheme = main { model.ui.workspace!!.theme }
            main { model.preferences(theme = ColorTheme.Ink) }; ready(); screenshot("trash-dark-preview")
            main { model.preferences(theme = oldTheme) }; ready()
            main { model.requestTrashRestore(listOf(deleted)) }; ready()
            verify(disk { currentStore().read(original) } == "进程重启仍完整 😀" && main { model.trashInventory.nodes.none { it.id == deleted } }, "Cold-start restore commits once and removes recovered trash item")
            main { model.go(Page.Directory) }; ready()
            disk { currentStore().delete(original); check(fixture.delete()) }
            main { model.reload() }; ready()
        }
    }
    private fun trashTest() {
        val lock = WorkspaceViewModel::class.java.getDeclaredField("ioLock").apply { isAccessible = true }.get(model) as kotlinx.coroutines.sync.Mutex
        fun <T> disk(action: () -> T): T = runBlocking { withContext(Dispatchers.IO) { lock.withLock { action() } } }
        val stamp = SystemClock.uptimeMillis()
        val name = "回收站验收-$stamp"
        val oldFont = shell("settings get system font_scale")
        val oldSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val originalTrash = main { model.trashInventory.nodes.map { it.id }.toSet() }
        val ids = disk {
            val store = currentStore()
            val folder = store.create(name, Workspace.ROOT, DocumentKind.Folder)
            val nested = store.create("子文件夹", folder, DocumentKind.Folder)
            val one = store.create("单独恢复", nested, DocumentKind.Markdown, "# 单独恢复正文 😀")
            val two = store.create("留在目录", nested, DocumentKind.Plain, "保留正文")
            val active = store.create("编辑删除-$stamp", Workspace.ROOT, DocumentKind.Plain, "初稿")
            listOf(folder, nested, one, two, active)
        }
        fun trashId(original: String) = main { model.trashInventory.nodes.single { it.entry.id == original }.id }
        fun showPrefix(prefix: String): String {
            repeat(24) {
                val node = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.startsWith(prefix) == true }
                if (node != null) return node.text.toString()
                nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(150)
            }
            error("Missing $prefix")
        }
        try {
            main { model.reload() }; ready()
            main { model.go(Page.Recent) }; ready(); main { model.open(ids[4]) }; ready()
            main { model.textState!!.edit { replace(0, length, "删除前最新输入 😀") } }
            click("条目操作"); click("删除")
            verify(nodes().any { it.text?.toString()?.contains("删除后可以从回收站恢复。") == true }, "Editor deletion keeps 删除 and explains trash recovery")
            click("取消"); verify(main { model.ui.activeId == ids[4] }, "Cancel deletion preserves active editor")
            val retainedField = main { model.textState }
            val blockedJournal = File(targetContext.filesDir, "trash/index.properties.new")
            disk { check(blockedJournal.mkdir()) }
            try {
                main { model.delete(ids[4]) }; ready()
                verify(main { model.ui.error == Failure.Corrupt && model.ui.activeId == ids[4] && model.textState === retainedField }, "Failed trash journal blocks writing and retains active draft buffer")
            } finally { disk { check(blockedJournal.delete()) } }
            click("重试"); ready()
            verify(main { model.ui.error == null && model.ui.activeId == ids[4] && model.textState === retainedField }, "Editor recovery retry reloads both catalogs without replacing input")
            click("条目操作"); click("删除"); click("删除"); ready()
            verify(main { model.ui.activeId == null && model.ui.page == Page.Recent }, "Deleting active draft returns to source and releases editor")
            SystemClock.sleep(2200)
            verify(main { model.ui.workspace?.entry(ids[4]) == null && model.trashInventory.nodes.any { it.entry.id == ids[4] } }, "Deleted editor never resurrects after autosave interval")
            main { model.go(Page.Directory) }; ready(); main { model.startSelection(ids[0]); model.requestDelete() }
            find("删除 1 项？")
            verify(nodes().any { it.text?.toString()?.contains("共 2 个文件夹、2 篇文稿") == true }, "Batch deletion confirms full recursive scope")
            click("删除"); ready()
            main { model.exitSelection(); model.go(Page.Settings) }; ready()
            val summary = showPrefix("回收站")
            verify(summary == "回收站" && nodes().any { Regex("\\d+项，.+").matches(it.text?.toString().orEmpty()) }, "Settings trash row separates its title from item count and disk size")
            verify(nodes().any { it.text?.toString() == "卸载应用会导致文稿丢失，请定期导出备份" } && nodes().none { it.text?.toString()?.startsWith("文稿保存在本应用私有目录") == true }, "Settings keeps the concise backup warning and removes the duplicate notice")
            screenshot("trash-settings")
            click(summary); ready()
            verify(main { model.ui.page == Page.Trash }, "Settings trash entry opens its secondary page")
            verify(nodes().none { it.text?.toString() in listOf("内容会保留，直到你手动彻底删除。", "删除的文稿和文件夹会出现在这里。") }, "Trash list omits the removed explanatory hints")
            click("条目操作")
            val menuItem = find("选择")
            var menuRow = menuItem
            while (!menuRow.isClickable && menuRow.parent != null) menuRow = menuRow.parent
            val menuBounds = Rect(); menuRow.getBoundsInScreen(menuBounds)
            verify(menuBounds.width() / targetContext.resources.displayMetrics.density >= 239, "Trash menu uses the same minimum width as workspace context menus")
            screenshot("trash-menu")
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); ready()
            click("编辑删除-$stamp"); ready()
            verify(nodes().any { it.text?.toString() == "删除前最新输入 😀" } && nodes().none { it.isEditable }, "Trash preview contains latest saved body and is read only")
            click("恢复"); ready()
            verify(main { model.ui.workspace?.entry(ids[4]) != null && model.trashPreview == null }, "Preview restore returns draft to original root")
            val restored = main { checkNotNull(model.trashRestored) }
            val restoredText = model.languages.context().getString(R.string.trash_restored_item, restored.entries.single().name, restored.locations.single())
            val restoredBounds = Rect(); find(restoredText).getBoundsInScreen(restoredBounds)
            val restoredShot = checkNotNull(uiAutomation.takeScreenshot())
            val inset = (24 * targetContext.resources.displayMetrics.density).toInt()
            val y = restoredBounds.centerY()
            verify(restoredShot.getPixel(inset, y) == restoredShot.getPixel(restoredShot.width - inset - 1, y) && restoredShot.getPixel(inset, y) != restoredShot.getPixel(2, y), "Restore success card spans the list width with equal side margins")
            restoredShot.recycle(); screenshot("trash-restore-notice")
            click(name); ready(); click("子文件夹"); ready(); click("单独恢复"); ready(); click("恢复"); ready()
            verify(main { model.trashRestoreRequest != null } && nodes().any { it.text?.toString() == "确认恢复到根目录" }, "Nested single document asks before root fallback")
            screenshot("trash-root-confirm")
            click("取消"); verify(main { model.ui.workspace?.entry(ids[2]) == null }, "Cancel root fallback keeps draft in trash")
            click("恢复"); ready(); click("恢复"); ready()
            verify(main { model.ui.workspace?.entry(ids[2])?.parent == Workspace.ROOT && model.trashInventory.nodes.any { it.entry.id == ids[3] } }, "Single draft restores independently and preserves remaining deleted tree")
            click("恢复此文件夹"); ready()
            verify(main { model.trashRestoreRequest != null }, "Subfolder restore also asks when original parent remains deleted")
            click("取消")
            main { model.back() }; ready()
            click("恢复此文件夹"); ready()
            verify(main { model.ui.workspace?.entry(ids[0]) != null && model.ui.workspace?.entry(ids[3])?.parent == ids[1] }, "Restoring top folder preserves remaining hierarchy")
            verify(disk { currentStore().read(ids[2]) } == "# 单独恢复正文 😀", "Restored Markdown source remains exact")
            main { model.go(Page.Directory) }; ready()
            main { model.delete(ids[0]) }; ready()
            main { model.openTrash() }; ready()
            click(name); ready(); screenshot("trash-folder")
            main { currentActivity?.recreate() }; ready()
            val deletedFolder = trashId(ids[0])
            verify(main { model.ui.page == Page.Trash && model.trashFolder == deletedFolder }, "Activity recreation preserves deleted folder browsing")
            click("彻底删除此文件夹")
            verify(nodes().any { it.text?.toString()?.contains("无法从回收站恢复") == true }, "Permanent deletion always warns about irreversible trash removal")
            click("取消")
            main { model.back() }; ready()
            main { model.selectTrash(deletedFolder) }
            find("已选 1 项")
            verify(nodes().any { it.text?.toString() == "已选 1 项" }, "Trash selection exposes count and batch actions")
            screenshot("trash-selection")
            click("彻底删除"); click("彻底删除"); ready()
            verify(main { model.trashInventory.nodes.none { it.entry.id == ids[0] } && model.ui.workspace?.entry(ids[2]) != null }, "Batch purge removes chosen subtree and preserves live restored draft")
            main { model.go(Page.Directory) }; ready(); main { model.delete(ids[4]) }; ready(); main { model.openTrash() }; ready()
            shell("wm size 840x1890"); shell("settings put system font_scale 2.0")
            waitFor("trash narrow configuration") { main { currentActivity?.resources?.configuration?.let { it.screenWidthDp <= 320 && it.fontScale >= 1.99f } == true } }; ready()
            screenshot("trash-200-list")
            click("编辑删除-$stamp"); ready(); screenshot("trash-200-preview")
            click("彻底删除"); screenshot("trash-200-purge-confirm")
            verify(find("取消").isVisibleToUser && find("彻底删除").isVisibleToUser, "320dp and 200% font keep destructive confirmation reachable")
            click("取消"); main { model.back() }; ready()
            main { model.go(Page.Settings) }; ready(); showPrefix("回收站"); screenshot("trash-200-settings")
            verify(nodes().any { it.text?.toString() == "回收站" } && nodes().any { Regex("\\d+项，.+").matches(it.text?.toString().orEmpty()) }, "Narrow settings retains the separate trash title and complete details")
            main { model.openTrash() }; ready()
            main { model.reload() }; ready()
            verify(main { model.trashInventory.nodes.any { it.entry.id == ids[4] } }, "Reload reads persisted trash without automatic expiry")
        } finally {
            shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            ready()
            main { model.dismissTrashRestore(); model.exitTrashSelection(); model.exitSelection(); model.go(Page.Directory) }; ready()
            disk {
                val store = currentStore()
                ids.forEach { if (store.refresh().entry(it) != null) store.delete(it) }
                val trash = WorkspaceViewModel::class.java.getDeclaredField("trash").apply { isAccessible = true }.get(model) as io.github.xrisium.makudon.data.TrashStore
                val synthetic = trash.inventory().nodes.filter { it.id !in originalTrash }.map { it.id }
                if (synthetic.isNotEmpty()) trash.purge(synthetic)
            }
            main { model.reload() }; ready()
        }
    }
    private fun exportDialogTest() {
        val files = model.files
        fun prepared() { waitFor("export operation") { main { !files.ui.busy } }; waitForIdleSync() }
        fun visibleText(text: String) = nodes().any { it.text?.toString() == text }
        val destination = android.net.Uri.parse("content://io.github.xrisium.makudon.debug.test.fixtures/export")
        try {
            main { files.text("弹窗测试.txt", "export dialog test") }; prepared()
            verify(visibleText("弹窗测试.txt") && visibleText("保存到下载目录") && visibleText("通过其他应用分享") && visibleText("选择保存位置") && nodes().none { it.text?.toString()?.startsWith("这是开始导出时的副本") == true || it.text?.toString()?.startsWith("通过保存至云空间分享") == true }, "Initial export shows only the filename and requested actions")
            main { files.saveTo(destination) }; prepared()
            verify(visibleText(model.languages.context().getString(R.string.file_saved_location)) && !visibleText("弹窗测试.txt"), "Chosen location result replaces the filename")
            main { files.share(null) { } }; prepared()
            verify(visibleText("弹窗测试.txt") && !visibleText(model.languages.context().getString(R.string.file_saved_location)), "Share returns to filename without claiming a save")
            main { files.download() }; prepared()
            val saved = fileResult()
            verify(saved != null && visibleText(saved) && !visibleText("弹窗测试.txt"), "Downloads result replaces the filename")
            val name = saved!!.substringAfterLast('/')
            val downloads = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            targetContext.contentResolver.query(downloads, arrayOf("_id"), "_display_name=?", arrayOf(name), null)?.use { cursor ->
                while (cursor.moveToNext()) targetContext.contentResolver.delete(android.content.ContentUris.withAppendedId(downloads, cursor.getLong(0)), null, null)
            }
            screenshot("export-dialog")
        } finally { main { files.close() } }
    }

    @android.annotation.SuppressLint("NewApi") // This platform suite runs on the configured API 36 emulator.
    private fun fileExchangeTest() {
        val access = io.github.xrisium.makudon.data.AndroidFileAccess
        val robot = checkNotNull(targetContext.getDrawable(R.drawable.ic_android_robot))
        verify(kotlin.math.abs(robot.intrinsicWidth.toFloat() / robot.intrinsicHeight - 152f / 89f) < 0.04f, "Android robot intrinsic dimensions retain the official SVG aspect ratio")
        val files = model.files
        val resolver = targetContext.contentResolver
        fun uri(path: String) = android.net.Uri.parse("content://io.github.xrisium.makudon.debug.test.fixtures/$path")
        fun prepared() { waitFor("file operation") { main { !files.ui.busy } }; waitForIdleSync() }
        fun rejected(code: Int, intent: Intent?) = try { access.selectedUri(code, intent); false } catch (_: IllegalArgumentException) { true }
        verify(access.selectedUri(Activity.RESULT_CANCELED, Intent().setData(uri("markdown"))) == null, "Cancelled results never import attached data")
        verify(rejected(Activity.RESULT_OK, null), "Successful result without URI is rejected")
        verify(rejected(Activity.RESULT_FIRST_USER, Intent().setData(uri("markdown"))), "Unexpected result codes are rejected")
        verify(rejected(Activity.RESULT_OK, Intent().setData(android.net.Uri.parse("file:///tmp/draft.txt"))), "Direct file paths are rejected")
        val clip = android.content.ClipData.newRawUri("one", uri("markdown"))
        verify(access.selectedUri(Activity.RESULT_OK, Intent().apply { clipData = clip }) == uri("markdown"), "Single ClipData URI is accepted")
        clip.addItem(android.content.ClipData.Item(uri("invalid")))
        verify(rejected(Activity.RESULT_OK, Intent().apply { clipData = clip }), "Multiple ClipData items are rejected")
        val before = main { model.ui.workspace!!.entries.filter { !it.folder }.map { it.id }.toSet() }
        try {
            click("新建文稿"); click("导入"); prepared()
            verify(listOf("选择用于选取文件的应用", "测试文件选择", "安卓原生文件选择器").all { label -> nodes().any { it.text?.toString() == label } }, "Import offers real app labels and explicit system fallback")
            screenshot("files-import-apps")
            click("安卓原生文件选择器")
            waitFor("system file picker") { uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            verify(main { !files.ui.visible }, "Explicit system fallback opens actual DocumentsUI")
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor("system picker cancellation") { main { files.ui.visible } }
            click("测试取消选择"); waitFor("picker cancellation returned") { main { files.ui.visible } }; prepared()
            verify(main { files.ui.visible && model.ui.workspace!!.entries.count { !it.folder } == before.size }, "Real picker cancellation returns to app choice without creating a draft")
            click("测试多项选择"); waitFor("invalid picker result returned") { main { files.ui.error != null } }; prepared()
            verify(main { files.ui.error == io.github.xrisium.makudon.R.string.file_invalid_selection }, "Real multi-file result reports recoverable error")
            click("测试文件选择")
            waitFor("real picker import") { main { !model.ui.busy && model.ui.page == Page.Editor } }; ready()
            val first = main { model.ui.activeId!! }
            verify(source(first, DocumentKind.Markdown).contains("独立副本"), "Explicit app launch imports actual ClipData result as private Markdown")
            main { files.import(ImportKind.Text, Workspace.ROOT) }; prepared()
            val unavailable = access.importIntent().setComponent(android.content.ComponentName("missing.file.app", "Missing"))
            main { files.launchImport(unavailable) { targetContext.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            verify(main { files.ui.error == io.github.xrisium.makudon.R.string.file_app_unavailable && files.ui.visible }, "Missing app keeps choice open and never starts a fallback automatically")
            main { files.close() }
            main { model.import(uri("binary-md")) }; ready()
            verify(main { model.ui.workspace!!.entry(model.ui.activeId!!)!!.kind == DocumentKind.Markdown }, "Octet-stream metadata preserves .md type")
            main { model.import(uri("no-metadata")) }; ready()
            verify(main { model.ui.workspace!!.entry(model.ui.activeId!!)!!.kind == DocumentKind.Plain }, "Missing name metadata still imports a valid text MIME stream")
            val active = main { model.ui.activeId }
            main { model.import(uri("denied")) }; ready()
            verify(main { model.ui.activeId == active && model.ui.error != null }, "Revoked source access preserves active document")
            main { model.dismissError() }
            setText("编辑正文", "开始导出时的最新输入 😀")
            main { model.exportCopy() }; prepared()
            val editorCopy = main { files.ui.export!! }
            main { model.rename(checkNotNull(active), "导出后-${SystemClock.uptimeMillis()}") }; ready()
            main { model.textState!!.edit { replace(0, length, "之后的输入") } }
            verify(editorCopy.file.readText() == "开始导出时的最新输入 😀" && editorCopy.name != main { model.ui.workspace!!.entry(checkNotNull(active))!!.name + ".txt" }, "Editor export captures latest input and freezes filename across later edits and rename")
            main { files.close() }
            main { files.text("中文快照.txt", "captured before edits 😀") }; prepared()
            val snapshot = main { files.ui.export!! }
            verify(nodes().any { it.text?.toString() == snapshot.name } && nodes().none { it.text?.toString()?.startsWith("这是开始导出时的副本") == true || it.text?.toString()?.startsWith("通过保存至云空间分享") == true }, "Export initially shows the filename without extra explanation or dedicated cloud share")
            main { currentActivity?.recreate() }
            waitFor("export restored after recreation") { nodes().any { it.text?.toString() == "选择导出方式" } }
            verify(main { files.ui.export == snapshot }, "Activity recreation preserves export bytes and filename")
            click("选择保存位置")
            waitFor("system save picker") { uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            // CREATE_DOCUMENT can focus the filename and open its IME. The first BACK
            // dismisses that keyboard; cancel only while DocumentsUI still owns the window.
            repeat(3) {
                if (uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) {
                    uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    SystemClock.sleep(700); waitForIdleSync()
                }
            }
            waitFor("save picker returned") { uiAutomation.rootInActiveWindow?.packageName == targetContext.packageName }
            verify(main { files.ui.export == snapshot }, "Actual system save cancellation retains original snapshot")
            main { files.saveTo(uri("denied")) }; prepared()
            verify(main { files.ui.error != null && files.ui.export == snapshot }, "Failed destination keeps immutable copy for retry")
            main { files.saveTo(null) }
            verify(main { files.ui.export == snapshot && files.ui.visible }, "Cancelled save retains the same export session")
            main { files.saveTo(uri("export")) }; prepared()
            verify(resolver.openInputStream(uri("export"))!!.bufferedReader().use { it.readText() } == "captured before edits 😀", "Retry writes original snapshot bytes")
            verify(main { files.ui.message == R.string.file_saved_location } && nodes().none { it.text?.toString() == snapshot.name }, "Direct write replaces the filename with the save result")
            screenshot("files-export-options")
            val share = access.shareIntent(targetContext, snapshot)
            val sharedUri = share.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
            verify(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 && share.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0 && share.clipData!!.getItemAt(0).uri == sharedUri, "File sharing grants read-only URI via stream and ClipData")
            main { files.share(null) { } }; prepared()
            verify(main { files.ui.message == null && files.ui.export == snapshot } && nodes().any { it.text?.toString() == snapshot.name }, "Share launch retains the filename without claiming a save")
            main { files.close() }
            main { files.text("迟到结果.txt", "must not be written") }; prepared()
            main { files.create { }; files.close(); files.created(Activity.RESULT_OK, Intent().setData(uri("export"))) }
            verify(main { files.ui.error == R.string.file_request_expired } && resolver.openInputStream(uri("export"))!!.bufferedReader().use { it.readText() } == "captured before edits 😀", "Late save result cannot write after its export session was closed")
            main { files.close() }
            verify(resolver.openInputStream(sharedUri)!!.bufferedReader().use { it.readText() } == "captured before edits 😀", "Closing export leaves asynchronously shared copy readable")
            val source = File(targetContext.cacheDir, "files-source.zip").apply { writeText("independent ZIP source") }
            main { files.archive("备份.zip", source) { source.delete() } }; prepared()
            val zipCopy = main { files.ui.export!! }
            main { files.close() }
            verify(!source.exists() && zipCopy.file.readText() == "independent ZIP source", "ZIP source cleanup cannot delete shared export copy")
            for (failure in listOf("download-update-fail", "download-cleanup-fail")) {
                var failed = false
                try { access.download(resolver, snapshot, uri(failure)) }
                catch (e: Exception) { failed = if (failure.endsWith("cleanup-fail")) e is io.github.xrisium.makudon.data.DownloadCleanupException else e is IllegalStateException }
                verify(failed, "Download publication and cleanup failure remains visible: $failure")
                if (failure.endsWith("cleanup-fail")) resolver.delete(uri(failure), "cleanup", null)
                else verify(try { resolver.openInputStream(uri(failure))?.close(); false } catch (_: java.io.FileNotFoundException) { true }, "Failed publication removes pending download")
            }
            main { files.text("同名验收.txt", "download one") }; prepared(); main { files.download() }; prepared()
            val savedOne = fileResult()
            verify(savedOne?.startsWith("已保存到 Download/Makudon/") == true && nodes().none { it.text?.toString() == "同名验收.txt" }, "Downloads save result replaces the filename")
            val firstName = savedOne!!.substringAfterLast('/')
            main { files.close(); files.text("同名验收.txt", "download two") }; prepared(); main { files.download() }; prepared()
            val secondName = main { checkNotNull(files.ui.messageArgument) }
            verify(firstName != secondName, "Repeated Downloads filename creates independent file rather than overwrite")
            val downloads = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.query(downloads, arrayOf("_id", "_display_name"), "_display_name IN (?,?)", arrayOf(firstName, secondName), null)!!.use { cursor ->
                val contents = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val item = android.content.ContentUris.withAppendedId(downloads, cursor.getLong(0))
                    contents += resolver.openInputStream(item)!!.bufferedReader().use { it.readText() }
                    resolver.delete(item, null, null)
                }
                verify(contents == setOf("download one", "download two"), "Both same-name exports preserve their independent content")
            }
            main { files.close(); files.import(ImportKind.Archive) }; prepared()
            verify(main { files.ui.importing }, "ZIP import uses the same app-selection mechanism")
            main { files.close() }
            val expired = FileExchange(targetContext.applicationContext as android.app.Application, kotlinx.coroutines.CoroutineScope(Dispatchers.Main))
            main { expired.imported(Activity.RESULT_OK, Intent().setData(uri("markdown"))) }
            verify(expired.ui.error == R.string.file_request_expired, "Result without surviving request reports expiry and does not import")
            val oldFont = shell("settings get system font_scale")
            val oldSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
            try {
                shell("wm size 840x1890"); shell("settings put system font_scale 2.0")
                waitFor("narrow file UI") { main { currentActivity?.resources?.configuration?.let { it.screenWidthDp <= 320 && it.fontScale >= 1.99f } == true } }
                main { files.text("大字号.txt", "字号验收") }; prepared(); SystemClock.sleep(400)
                fun reveal(label: String) {
                    repeat(15) {
                        val node = nodes().firstOrNull { it.text?.toString() == label }
                        if (node != null) { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); SystemClock.sleep(200); return }
                        nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        SystemClock.sleep(100)
                    }
                    error("Missing file control: $label")
                }
                reveal("选择保存位置"); screenshot("files-export-200")
                verify(find("选择保存位置").isVisibleToUser && find("关闭").isVisibleToUser, "320dp and 200% fonts keep export fallback and close reachable")
                main { files.close(); files.import(ImportKind.Text) }; prepared()
                reveal("安卓原生文件选择器"); screenshot("files-import-200")
                verify(find("安卓原生文件选择器").isVisibleToUser && find("关闭").isVisibleToUser, "320dp and 200% fonts keep app choices and system fallback reachable")
            } finally {
                main { files.close() }
                shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
                shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
                ready()
            }
        } finally {
            main { files.close(); model.dismissError() }; ready()
            if (main { model.ui.page == Page.Editor }) { main { model.back() }; ready() }
            for (id in main { model.ui.workspace!!.entries.filter { !it.folder && it.id !in before }.map { it.id } }) { main { model.delete(id) }; ready() }
        }
    }
    private fun source(id: String, kind: DocumentKind) = File(workspaceDirectory(), "documents/$id/body.${kind.extension}").readText(Charsets.UTF_8)

    private fun backupTest() {
        val sourceUri = android.net.Uri.parse("content://io.github.xrisium.makudon.debug.test.fixtures/archive")
        val exportUri = android.net.Uri.parse("content://io.github.xrisium.makudon.debug.test.fixtures/archive-export")
        val lock = WorkspaceViewModel::class.java.getDeclaredField("ioLock").apply { isAccessible = true }.get(model) as kotlinx.coroutines.sync.Mutex
        fun <T> disk(block: () -> T): T = runBlocking { withContext(Dispatchers.IO) { lock.withLock { block() } } }
        fun readyBackup() { waitFor("backup operation", 60_000) { main { !model.backup.ui.busy } }; ready(); main { model.backup.ui.error }?.let { error("Backup failed: $it") } }
        fun enter() { main { model.go(Page.Settings) }; ready(); main { model.backups() }; readyBackup() }
        fun reveal(label: String) {
            repeat(8) {
                if (nodes().any { it.text?.toString() == label || it.contentDescription?.toString() == label }) return@repeat
                nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                SystemClock.sleep(100)
            }
            repeat(18) {
                if (nodes().any { it.text?.toString() == label || it.contentDescription?.toString() == label }) {
                    find(label).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    SystemClock.sleep(200); return
                }
                nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(150)
            }
            screenshot("backup-scroll-failure")
            error("Could not reveal $label")
        }
        fun selectArchive(mode: RestoreMode) {
            main { model.backup.read(sourceUri) }; readyBackup()
            verify(main { model.backup.ui.mode == RestoreMode.Separate }, "Separate is the default restore mode")
            if (mode != RestoreMode.Separate) {
                val label = model.languages.context().getString(restoreModeLabel(mode)); reveal(label); click(label)
            }
            reveal("预览恢复结果"); click("预览恢复结果"); readyBackup()
        }
        fun submit() { reveal("确认恢复方式"); click("确认恢复方式"); find("确认恢复"); click("确认"); readyBackup() }
        val synthetic = disk {
            val store = currentStore()
            val folder = store.create("电脑目录", Workspace.ROOT, DocumentKind.Folder)
            val doc = store.create("笔记", folder, DocumentKind.Markdown, "上一保存版")
            store.save(doc, "本地冲突正文")
            val localOnly = store.create("ZIP验收独有", folder, DocumentKind.Plain, "只在本地")
            listOf(folder, doc, localOnly)
        }
        main { model.reload() }; ready()
        try {
            enter()
            find("备份与恢复")
            verify(true, "Settings opens the native backup page")
            val expectedSource = targetContext.contentResolver.openInputStream(sourceUri).use { checkNotNull(it).readBytes() }
            main { model.backup.read(sourceUri) }; readyBackup()
            val radioLabels = nodes().filter { it.text?.toString() in listOf("单独保存", "增量更新", "完全覆盖") }.map { it.text.toString() }
            verify(radioLabels.take(2) == listOf("单独保存", "增量更新"), "Restore modes start with Separate then Incremental")
            verify(main { model.backup.ui.inventory?.documents == 2 && model.backup.ui.inventory?.skipped == listOf("附件.png") }, "Computer ZIP without manifest validates and lists unsupported files")
            find(model.languages.context().getString(R.string.backup_separate_description))
            currentActivity?.let { activity -> main { activity.recreate() } }; ready()
            verify(main { model.backup.ui.inventory != null && model.backup.ui.mode == RestoreMode.Separate }, "Activity recreation preserves the disk-backed restore preview")
            reveal("预览恢复结果"); click("预览恢复结果"); readyBackup()
            screenshot("backup-preview")
            submit()
            verify(main { model.ui.page == Page.Directory && model.ui.workspace?.entry(synthetic[1]) != null }, "Separate restore returns to Directory and preserves existing IDs")
            val separate = main { checkNotNull(model.ui.workspace).entries.single { it.name.startsWith("恢复 ") } }
            verify(main { checkNotNull(model.ui.workspace).entries.any { it.name == "电脑目录" && it.parent == separate.id } }, "Separate restore retains the ZIP outer folder under a new root folder")
            enter(); main { model.backup.undo() }; readyBackup()
            verify(main { model.ui.workspace?.entry(separate.id) == null }, "Undo removes the restored wrapper and reinstates the original library")

            enter(); selectArchive(RestoreMode.Incremental)
            verify(main { model.backup.ui.plan?.conflicts?.size == 1 && model.backup.ui.plan?.resolved == false }, "Incremental preview requires explicit conflict confirmation")
            reveal("确认此项选择"); screenshot("backup-conflict"); click("确认此项选择"); readyBackup()
            verify(main { model.backup.ui.plan?.decisions?.values?.single() == ConflictChoice.Both }, "Conflict suggestion is Keep Both and is confirmed individually")
            submit()
            verify(source(synthetic[1], DocumentKind.Markdown) == "本地冲突正文" && source(synthetic[2], DocumentKind.Plain) == "只在本地", "Incremental Keep Both preserves local conflicting and local-only bodies")
            val copy = main { checkNotNull(model.ui.workspace).entries.single { it.name == "笔记（恢复副本）" } }
            verify(source(copy.id, DocumentKind.Markdown).contains("ZIP 验收"), "Conflict copy has independent imported content")
            enter(); main { model.backup.undo() }; readyBackup()

            main { model.open(synthetic[1]) }; ready()
            val oldSession = main { checkNotNull(model.save) }
            main { model.go(Page.Settings) }; ready()
            main { model.backups() }; readyBackup()
            selectArchive(RestoreMode.Replace)
            reveal("确认恢复方式"); click("确认恢复方式")
            find(model.languages.context().getString(R.string.backup_replace_description))
            verify(nodes().any { it.text?.toString()?.startsWith("本次将移除并替换") == true }, "Full replacement repeats the destructive warning and local document count")
            click("取消")
            verify(main { model.ui.workspace?.entry(synthetic[1]) != null }, "Cancel replacement confirmation leaves the current library intact")
            submit()
            verify(main { checkNotNull(model.ui.workspace).entries.count { !it.folder } == 2 && model.ui.workspace?.entry(synthetic[1]) == null }, "Full replacement publishes exactly the ZIP document set")
            main { oldSession.changed("迟到的旧保存") }
            runBlocking { oldSession.flush() }
            verify(oldSession.status.value.failure == Failure.StaleRestore, "Old editor session cannot write through after the library switch")
            enter(); main { model.backup.undo() }; readyBackup()
            verify(source(synthetic[1], DocumentKind.Markdown) == "本地冲突正文", "Undo restores the full original body after replacement")
            main { model.open(synthetic[1]) }; ready()
            main { model.textState?.edit { replace(0, length, "撤回后编辑 😀") } }; saved()
            enter(); main { model.backup.undo() }; readyBackup()
            enter(); main { model.backup.undo() }; readyBackup()
            verify(source(synthetic[1], DocumentKind.Markdown) == "撤回后编辑 😀", "Repeated undo swaps independent libraries and preserves edits made after undo")

            enter(); main { model.backup.prepareExport() }; readyBackup()
            verify(main { model.backup.ui.exportReady }, "Whole-library export is generated and verified before choosing a destination")
            main { model.backup.export(exportUri) }; readyBackup()
            val exported = File(targetContext.cacheDir, "backup-test-export.zip")
            targetContext.contentResolver.openInputStream(exportUri).use { input -> exported.outputStream().use { checkNotNull(input).copyTo(it) } }
            val parsed = io.github.xrisium.makudon.data.LibraryArchive.read(exported, File(targetContext.cacheDir, "backup-test-parse-${System.nanoTime()}"))
            verify(parsed.inventory.documents == main { checkNotNull(model.ui.workspace).entries.count { !it.folder } }, "SAF output is a complete readable ZIP with matching document count")
            verify(targetContext.contentResolver.openInputStream(sourceUri).use { checkNotNull(it).readBytes() }.contentEquals(expectedSource), "Source ZIP bytes remain unchanged")
            main { model.backup.clearSnapshot() }; readyBackup()
            verify(main { model.backup.ui.snapshot == null }, "Manual snapshot cleanup preserves the current library")

            main { model.backup.read(sourceUri) }; readyBackup()
            val countBefore = main { model.ui.workspace?.entries?.size }
            main { model.backup.cancel() }; waitFor("cancelled preview") { main { !model.backup.locked } }
            verify(main { model.ui.workspace?.entries?.size == countBefore && model.backup.ui.inventory == null }, "Cancelling a validated ZIP discards staging without changing the library")
            main { model.open(synthetic[1]) }; ready()
            main { model.reload() }; ready()
            main { model.textState?.edit { replace(0, length, "普通重读后仍可保存") } }; saved()
            verify(source(synthetic[1], DocumentKind.Markdown) == "普通重读后仍可保存", "Reloading the same library preserves the existing editor save session")
            val pending = File(workspaceDirectory(), "documents/${synthetic[1]}/body.md.new")
            check(pending.mkdir())
            try {
                main { model.textState?.edit { replace(0, length, "保存失败时的最新输入") } }
                waitFor("injected failed save") { main { model.save?.status?.value?.phase == SavePhase.Failed } }
                main { model.backup.prepareExport() }
                waitFor("failed-save export barrier") { main { !model.backup.ui.busy } }
                verify(main { model.backup.ui.error == Failure.Storage && !model.backup.ui.exportReady && model.ui.page == Page.Editor && model.textState?.text?.toString() == "保存失败时的最新输入" }, "Failed save blocks whole-library export and preserves the current editor buffer")
            } finally {
                check(pending.delete())
                main { model.backup.cancel() }; waitFor("failed export reset") { main { !model.backup.locked } }
                main { model.retrySave() }; saved()
                main { model.go(Page.Settings) }; ready()
            }
        } finally {
            main { model.backup.cancel() }
            waitFor("backup cleanup") { main { !model.backup.locked } }
            disk { val store = currentStore(); if (store.refresh().entry(synthetic[0]) != null) store.delete(synthetic[0]) }
            main { model.reload() }; ready()
        }
    }

    private fun backupVisualTest() {
        val oldFont = shell("settings get system font_scale")
        val oldSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val lock = WorkspaceViewModel::class.java.getDeclaredField("ioLock").apply { isAccessible = true }.get(model) as kotlinx.coroutines.sync.Mutex
        val root = runBlocking { withContext(Dispatchers.IO) { lock.withLock {
            val store = currentStore()
            val folder = store.create("电脑目录", Workspace.ROOT, DocumentKind.Folder)
            store.create("笔记", folder, DocumentKind.Markdown, "视觉冲突本地正文")
            folder
        } } }
        fun show(label: String) {
            repeat(25) {
                val node = nodes().firstOrNull { it.text?.toString() == label }
                if (node != null) { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); SystemClock.sleep(250); return }
                nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(150)
            }
            error("Missing visual control: $label")
        }
        fun inside(label: String): Boolean {
            val bounds = Rect(); find(label).getBoundsInScreen(bounds)
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            val fits = !bounds.isEmpty && bounds.left >= 0 && bounds.top >= 0 && bounds.right <= bitmap.width && bounds.bottom <= bitmap.height
            bitmap.recycle(); return fits
        }
        fun backupReady() { waitFor("visual backup ready", 60_000) { main { !model.backup.ui.busy } }; ready(); check(main { model.backup.ui.error == null }) }
        try {
            main { model.reload() }; ready(); main { model.go(Page.Settings) }; ready()
            main { model.backups() }; backupReady()
            main { model.backup.read(android.net.Uri.parse("content://io.github.xrisium.makudon.debug.test.fixtures/archive")) }; backupReady()
            shell("wm size 840x1890"); shell("settings put system font_scale 2.0")
            waitFor("actual backup narrow configuration") { main { currentActivity?.resources?.configuration?.let { it.screenWidthDp <= 320 && it.fontScale >= 1.99f } == true } }
            ready(); SystemClock.sleep(600)
            screenshot("backup-200-modes")
            show("完全覆盖"); click("完全覆盖")
            show("预览恢复结果"); click("预览恢复结果"); backupReady()
            show("确认恢复方式"); click("确认恢复方式")
            screenshot("backup-200-confirm")
            verify(inside("确认") && inside("取消"), "320dp and 200% fonts keep destructive confirmation buttons visible with scrollable explanation")
            click("取消")
            main { model.backup.chooseMode(RestoreMode.Incremental); model.backup.preview() }; backupReady()
            show("确认此项选择")
            screenshot("backup-200-conflict")
            verify(inside("确认此项选择"), "320dp and 200% fonts keep the individual conflict confirmation reachable")
        } finally {
            main { model.backup.cancel() }; waitFor("visual stage cancelled") { main { !model.backup.locked } }
            shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            ready()
            runBlocking { withContext(Dispatchers.IO) { lock.withLock { currentStore().delete(root) } } }
            main { model.reload() }; ready()
        }
    }

    private fun shell(command: String): String = uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText().trim() }
    }

    private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val display = targetContext.resources.displayMetrics
        val time = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                (fromX + (toX - fromX) * fraction) * display.widthPixels,
                (fromY + (toY - fromY) * fraction) * display.heightPixels, 0).let {
                it.source = InputDevice.SOURCE_TOUCHSCREEN
                check(uiAutomation.injectInputEvent(it, true)); it.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..12) { SystemClock.sleep(20); send(MotionEvent.ACTION_MOVE, step / 12f) }
        send(MotionEvent.ACTION_UP, 1f)
        ready()
    }

    private fun tap(label: String) {
        val bounds = Rect(); find(label).getBoundsInScreen(bounds)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0).let {
                it.source = InputDevice.SOURCE_TOUCHSCREEN
                check(uiAutomation.injectInputEvent(it, true)); it.recycle()
            }
        }
        ready()
    }

    private fun detailsTest() {
        val lock = WorkspaceViewModel::class.java.getDeclaredField("ioLock").apply { isAccessible = true }.get(model) as kotlinx.coroutines.sync.Mutex
        fun <T> disk(block: () -> T): T = runBlocking { withContext(Dispatchers.IO) { lock.withLock { block() } } }
        val store = currentStore()
        val suffix = SystemClock.uptimeMillis()
        var folderName = "信息目录-$suffix"
        val docName = "信息正文-$suffix"
        val ids = disk {
            val folder = store.create(folderName, Workspace.ROOT, DocumentKind.Folder)
            val nested = store.create("子目录", folder, DocumentKind.Folder)
            val empty = store.create("空目录", folder, DocumentKind.Folder)
            val doc = store.create(docName, folder, DocumentKind.Plain, "中😀")
            store.create("深层文稿", nested, DocumentKind.Markdown, "# 深层正文")
            listOf(folder, doc, empty)
        }
        val oldFont = shell("settings get system font_scale")
        val oldSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val oldTheme = main { checkNotNull(model.ui.workspace).theme }
        fun shown(label: String) = nodes().any { it.text?.toString() == label || it.contentDescription?.toString() == label }
        fun detailText() = nodes().mapNotNull { it.text?.toString() }.first { it.contains("创建时间：") || it.contains("Created:") }
        fun expectedTime(timestamp: Long) = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(timestamp))
        fun openFolder() { longPress(folderName); click("更多信息"); ready() }
        try {
            main { model.reload() }; ready(); main { model.go(Page.Directory) }; ready()
            openFolder()
            val entry = main { checkNotNull(model.ui.workspace?.entry(ids[0])) }
            val expectedBytes = TextRules.bytes("中😀").size.toLong() + TextRules.bytes("# 深层正文").size
            verify(main { model.ui.infoDiskBytes == expectedBytes } && detailText().contains("总磁盘大小："), "Folder information totals all nested UTF-8 material bytes")
            verify(detailText().contains("创建时间：${expectedTime(entry.created)}") && detailText().contains("最近修改时间：${expectedTime(entry.modified)}"), "Folder information displays exact creation and recursive modification timestamps")
            verify(!shown("上一保存版本") && !shown("恢复此版本"), "Folder information contains no document-history action")
            screenshot("details-folder")
            main { currentActivity?.recreate() }; ready()
            verify(shown("更多信息") && detailText().contains(expectedTime(entry.created)), "Information dialog survives Activity recreation with its inspected data")
            click("关闭")
            main { model.open(ids[1]) }; ready()
            click("条目操作")
            verify(!shown("上一保存版本") && shown("详细信息"), "Editor overflow removes previous-version navigation")
            click("详细信息"); ready()
            verify(detailText().contains(docName + ".txt") && !shown("恢复此版本"), "Editor detail panel identifies its document without a history action")
            screenshot("details-document")
            click("关闭")
            setText("编辑正文", "最新输入😀\n第二行")
            click("条目操作"); click("详细信息"); ready()
            val doc = main { checkNotNull(model.ui.workspace?.entry(ids[1])) }
            verify(source(ids[1], DocumentKind.Plain) == "最新输入😀\n第二行" && detailText().contains("UTF-8 大小：${TextRules.bytes("最新输入😀\n第二行").size} 字节"), "Editor information saves and refreshes the latest body before displaying size")
            verify(detailText().contains("创建时间：${expectedTime(doc.created)}") && detailText().contains("最近修改时间：${expectedTime(doc.modified)}"), "Document timestamps retain creation and refresh modification to the second")
            click("关闭")
            val pending = File(workspaceDirectory(), "documents/${ids[1]}/body.txt.new")
            check(pending.mkdir())
            try {
                setText("编辑正文", "保存失败仍保留缓冲")
                click("条目操作"); click("详细信息"); ready()
                verify(!shown("详细信息") && main { model.save?.status?.value?.phase == SavePhase.Failed && model.textState?.text?.toString() == "保存失败仍保留缓冲" }, "Failed save prevents opening stale editor information and preserves input")
            } finally { check(pending.delete()) }
            main { model.retrySave() }; ready(); saved()
            main { model.back() }; ready(); main { model.go(Page.Recent) }; ready()
            longPress(docName); click("更多信息"); ready()
            verify(detailText().contains(docName + ".txt") && !shown("上一保存版本"), "Recent long-press information uses the same refreshed timestamp panel")
            click("关闭"); main { model.go(Page.Directory) }; ready()
            openFolder()
            verify(main { model.ui.infoDiskBytes == TextRules.bytes("保存失败仍保留缓冲").size.toLong() + TextRules.bytes("# 深层正文").size }, "Reopening folder information recalculates disk size after editing a descendant")
            click("关闭")
            main { model.preferences(theme = ColorTheme.Ink) }; ready()
            openFolder()
            folderName += "用于验证长名称和窄屏滚动".repeat(5)
            disk { store.rename(ids[0], folderName) }
            main { model.reload() }; ready()
            shell("wm size 840x1890"); shell("settings put system font_scale 2.0")
            waitFor("320dp and 200% details") { main { currentActivity?.resources?.configuration?.let { it.screenWidthDp <= 320 && it.fontScale >= 1.99f } == true } }
            ready(); screenshot("details-folder-200")
            val close = find("关闭"); val bounds = Rect(); close.getBoundsInScreen(bounds)
            val scrollable = nodes().firstOrNull { node -> node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
            verify(!bounds.isEmpty && bounds.top >= 0 && scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true, "Narrow 200% folder dialog keeps close visible and long information scrollable")
            SystemClock.sleep(500); screenshot("details-folder-200-scrolled")
            main { model.changeLanguage("en") }; ready()
            verify(detailText().contains("Total disk size:") && detailText().contains("Last modified:"), "English information panel includes recursive disk size and timestamps")
            screenshot("details-folder-en-200"); click("Close")
        } finally {
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            ready(); main { model.changeLanguage("zh-Hans") }; ready()
            main { model.go(Page.Directory) }; ready()
            disk { store.delete(ids[0]) }
            main { model.reload() }; ready(); main { model.preferences(theme = oldTheme) }; ready()
        }
    }

    private fun recentTest() {
        main { model.go(Page.Recent) }; ready()
        val store = currentStore()
        val lock = WorkspaceViewModel::class.java.getDeclaredField("ioLock").apply { isAccessible = true }.get(model) as kotlinx.coroutines.sync.Mutex
        val calendar = RecentCalendar.current()
        val suffix = SystemClock.uptimeMillis().toString()
        val names = listOf("日期今天-$suffix", "日期前天-$suffix", "日期本周-$suffix", "日期往年-$suffix")
        val openedDays = listOf(0L, 2L, 5L, 400L)
        val modifiedDays = listOf(0L, 1L, 2L, 400L)
        val catalog = File(workspaceDirectory(), "catalog.properties")
        val originalOpened = mutableMapOf<String, String>()
        fun day(days: Long) = calendar.today.minusDays(days).atTime(12, 0).atZone(calendar.zone).toInstant().toEpochMilli()
        val ids = runBlocking { withContext(Dispatchers.IO) { lock.withLock {
            val created = names.map { store.create(it, Workspace.ROOT, DocumentKind.Plain, "日期与菜单验收") }
            val properties = java.util.Properties().apply { catalog.reader(Charsets.UTF_8).use { load(it) } }
            for (n in 0 until properties.getProperty("count").toInt()) {
                val id = properties.getProperty("$n.id")
                if (id !in created) {
                    originalOpened[id] = properties.getProperty("$n.opened")
                    properties.setProperty("$n.opened", "0")
                }
            }
            created.forEachIndexed { index, id ->
                val n = (0 until properties.getProperty("count").toInt()).first { properties.getProperty("$it.id") == id }
                properties.setProperty("$n.opened", day(openedDays[index]).toString())
                check(File(workspaceDirectory(), "documents/$id/body.txt").setLastModified(day(modifiedDays[index])))
            }
            catalog.writer(Charsets.UTF_8).use { properties.store(it, null) }
            store.load()
            created
        } } }
        fun shown(text: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == text }
        fun scrollTo(text: String) {
            for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                repeat(16) {
                    if (shown(text)) {
                        val bounds = Rect(); find(text).getBoundsInScreen(bounds)
                        if (bounds.bottom > targetContext.resources.displayMetrics.heightPixels * .65f) swipe(.5f, .7f, .5f, .45f)
                        return
                    }
                    if (direction == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) swipe(.5f, .3f, .5f, .8f)
                    else swipe(.5f, .8f, .5f, .3f)
                }
            }
            error("Missing recent item: $text")
        }
        try {
            main { model.reload() }; ready()
            val groups = main { checkNotNull(model.ui.workspace).recentGroups(calendar) }
            verify(RecentPeriod.entries.all { it in groups }, "Recent documents occupy all four calendar groups")
            for (label in listOf("今天", "3天内", "7天内", "更早以前")) {
                scrollTo(label)
                verify(shown(label), "Recent renders section $label")
            }
            for ((index, label) in listOf("今天", "昨天", "前天").withIndex()) {
                scrollTo(names[index])
                verify(nodes().any { it.text?.toString()?.contains("·  $label ") == true }, "Card modification date renders $label")
            }
            scrollTo(names[3])
            verify(nodes().any { it.text?.toString()?.contains(calendar.today.minusDays(400).year.toString()) == true }, "Other-year card retains its year")
            screenshot("recent-earlier")
            scrollTo(names[0]); screenshot("recent-groups")
            val originalTime = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            val originalAutoTime = shell("settings get global auto_time")
            val originalZone = shell("getprop persist.sys.timezone")
            try {
                shell("settings put global auto_time 0")
                val tomorrow = calendar.today.plusDays(1).atTime(13, 0).atZone(calendar.zone).toInstant().toEpochMilli()
                shell("cmd alarm set-time $tomorrow")
                waitFor("date-change refresh") { nodes().any { it.text?.toString()?.contains("·  昨天 ") == true } }
                verify(shown(names[0]) && nodes().any { it.text?.toString()?.contains("·  昨天 ") == true }, "System date change refreshes relative card dates without navigation")
                val alternateZone = if (calendar.zone.id == "Pacific/Kiritimati") "America/Los_Angeles" else "Pacific/Kiritimati"
                shell("cmd alarm set-timezone $alternateZone")
                val expectedTime = android.text.format.DateFormat.getTimeFormat(targetContext).apply {
                    timeZone = java.util.TimeZone.getTimeZone(alternateZone)
                }.format(java.util.Date(day(0)))
                waitFor("timezone refresh") { nodes().any { it.text?.toString()?.contains("$expectedTime  ·") == true } }
                verify(nodes().any { it.text?.toString()?.contains("$expectedTime  ·") == true }, "System timezone broadcast refreshes visible card time without navigation")
            } finally {
                shell("cmd alarm set-timezone $originalZone")
                shell("cmd alarm set-time ${originalTime + SystemClock.elapsedRealtime() - elapsed}")
                shell("settings put global auto_time $originalAutoTime")
            }
            waitFor("restored card date") { nodes().any { it.text?.toString()?.contains("·  今天 ") == true } }
            longPress(names[0], physical = true)
            verify(shown("更多信息") && shown("重命名") && shown("删除") && !shown("复制"), "Recent card keeps its three-action long-press menu")
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); ready()
            click(names[0]); ready()
            click("条目操作")
            verify(listOf("详细信息", "重命名", "删除", "导出 .txt 副本").all(::shown), "Editor overflow exposes all new and existing actions")
            screenshot("editor-menu")
            click("详细信息"); ready()
            verify(shown("详细信息") && nodes().any { it.text?.toString()?.contains(names[0] + ".txt") == true }, "Editor details opens the active document")
            click("关闭")
            click("条目操作"); click("重命名")
            setText("名称", names[1]); click("重命名")
            verify(shown("此位置已有同名条目，请换一个名称；未覆盖原条目。"), "Editor rename rejects sibling conflicts")
            setText("名称", names[0] + "-改名"); click("重命名"); ready()
            verify(main { model.ui.activeId == ids[0] && model.ui.page == Page.Editor && model.ui.workspace?.entry(ids[0])?.name == names[0] + "-改名" }, "Rename retains active editor ID and file type")
            click(names[0] + "-改名"); click("取消")
            verify(main { model.ui.page == Page.Editor }, "Title rename shortcut remains available")
            click("条目操作"); click("删除"); click("取消")
            verify(main { model.ui.activeId == ids[0] && model.ui.workspace?.entry(ids[0]) != null }, "Cancel editor deletion preserves document and editor")
            val pending = File(workspaceDirectory(), "documents/${ids[0]}/body.txt.new")
            check(pending.mkdir())
            try {
                setText("编辑正文", "删除前保存屏障保留的最新输入")
                click("条目操作"); click("删除"); click("删除"); ready()
                verify(main { model.ui.page == Page.Editor && model.ui.workspace?.entry(ids[0]) != null && model.save?.status?.value?.phase == SavePhase.Failed }, "Failed save prevents active-document deletion and keeps editable buffer")
            } finally { check(pending.delete()) }
            click("重试"); ready(); saved()
            click("条目操作"); click("删除"); click("删除"); ready()
            verify(main { model.ui.page == Page.Recent && model.ui.activeId == null && model.save == null && model.textState == null }, "Deleting active document returns to recent and releases editor")
            SystemClock.sleep(2200)
            verify(main { model.ui.workspace?.entry(ids[0]) == null } && !File(workspaceDirectory(), "documents/${ids[0]}").exists(), "No delayed autosave resurrects the deleted draft")
            main { model.go(Page.Directory) }; ready(); main { model.open(ids[1]) }; ready()
            click("条目操作"); click("删除"); click("删除"); ready()
            verify(main { model.ui.page == Page.Directory && model.ui.activeId == null }, "Deleting an editor opened from directory returns to directory")
        } finally {
            main { if (model.ui.page == Page.Editor) model.back() }; ready()
            runBlocking { withContext(Dispatchers.IO) { lock.withLock {
                ids.forEach { if (store.refresh().entry(it) != null) store.delete(it) }
                val properties = java.util.Properties().apply { catalog.reader(Charsets.UTF_8).use { load(it) } }
                for (n in 0 until properties.getProperty("count").toInt()) {
                    originalOpened[properties.getProperty("$n.id")]?.let { properties.setProperty("$n.opened", it) }
                }
                catalog.writer(Charsets.UTF_8).use { properties.store(it, null) }
                store.load()
            } } }
            main { model.reload() }; ready()
        }
    }

    private fun motionTest() {
        val oldTheme = main { checkNotNull(model.ui.workspace).theme }
        val oldScale = shell("settings get global animator_duration_scale")
        val catalog = File(workspaceDirectory(), "catalog.properties")
        val catalogBefore = catalog.readBytes()
        try {
            shell("settings put global animator_duration_scale 1")
            // Android intentionally omits the accessibility click action on a selected tab.
            tap("最近"); ready()
            verify(main { model.ui.page == Page.Recent && !model.ui.tabTransitioning }, "Clicking current tab does not leave navigation locked")
            swipe(.82f, .55f, .18f, .55f)
            verify(main { model.ui.page == Page.Settings }, "Horizontal drag settles on Settings")
            swipe(.82f, .55f, .18f, .55f)
            verify(main { model.ui.page == Page.Settings }, "Right boundary does not wrap")
            swipe(.18f, .55f, .82f, .55f)
            verify(main { model.ui.page == Page.Recent }, "Reverse drag returns to Recent")
            swipe(.18f, .55f, .82f, .55f)
            verify(main { model.ui.page == Page.Directory }, "Reverse drag reaches Directory")
            swipe(.18f, .55f, .82f, .55f)
            verify(main { model.ui.page == Page.Directory }, "Left boundary does not wrap")
            click("搜索文稿"); setText("搜索文稿名称", "动效搜索保留")
            swipe(.82f, .55f, .18f, .55f)
            click("目录"); ready()
            var restoredSearch: AccessibilityNodeInfo? = null
            waitFor("restored search accessibility node") {
                restoredSearch = nodes().firstOrNull { it.className?.toString() == "android.widget.EditText" }
                restoredSearch != null
            }
            val searchField = checkNotNull(restoredSearch)
            verify(searchField.text?.toString()?.contains("动效搜索保留") == true, "Search query survives swipe and bottom tab navigation")
            check(searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "动效搜索更新") }))
            waitFor("updated restored search") { nodes().any { it.text?.toString()?.contains("动效搜索更新") == true } }
            verify(true, "Restored search field remains editable")
            click("关闭"); ready()
            click("设置"); ready()
            swipe(.5f, .72f, .5f, .38f)
            verify(main { model.ui.page == Page.Settings }, "Vertical scrolling does not switch tabs")
            click("最近"); ready()
            verify(catalog.readBytes().contentEquals(catalogBefore), "Tab gestures and clicks do not rewrite catalog preferences or document metadata")

            click("新建文稿"); click("纯文本"); ready()
            val id = main { checkNotNull(model.ui.activeId) }
            val field = main { checkNotNull(model.textState) }
            val first = "动效合成资料：立即返回仍应保存中文与 emoji 😀。"
            main {
                field.edit { replace(0, length, first) }
                model.back(); model.back()
            }
            ready()
            verify(source(id, DocumentKind.Plain) == first && main { model.ui.page == Page.Recent && model.textState == null }, "Immediate edit plus repeated back saves complete body and releases departing editor")
            main { model.open(id) }; ready()
            val sessionField = main { model.textState }
            val obsoleteNavigation = main { model.ui.navigationId - 1 }
            main { model.finishTransition(obsoleteNavigation) }
            verify(main { model.textState === sessionField && model.ui.activeId == id }, "Obsolete transition completion cannot clear the current editor")

            // Deliberately block only this run's synthetic body's atomic temporary file.
            val pending = File(workspaceDirectory(), "documents/$id/body.txt.new")
            check(pending.mkdir())
            val failed = first + "保存失败时保留此句。"
            try {
                setText("编辑正文", failed)
                main { model.back() }; ready()
                verify(main { model.ui.page == Page.Editor && model.save?.status?.value?.phase == SavePhase.Failed && model.textState?.text?.toString() == failed && !model.ui.transitioning }, "Failed save cancels back transition and preserves latest editable buffer")
                screenshot("motion-save-failed")
            } finally { check(pending.delete()) }
            setText("编辑正文", failed + "仍可继续输入。")
            main { model.retrySave() }; ready(); saved()
            verify(source(id, DocumentKind.Plain).endsWith("仍可继续输入。"), "Failed navigation unlocks editing and retry saves the newest text")

            main { model.back() }
            // Recreate while the IO barrier or reverse animation is pending.
            main { currentActivity?.recreate() }
            SystemClock.sleep(600); ready()
            verify(main { model.ui.page == Page.Recent && model.textState == null && !model.ui.transitioning }, "Activity recreation during exit cannot strand the editor or navigation lock")

            main { model.open(id) }; ready()
            main { model.back(); model.backgroundSave() }; ready()
            verify(main { model.ui.page == Page.Recent && model.textState == null }, "Background completion does not strand a departing session")

            click("设置"); ready()
            for (theme in ColorTheme.entries) {
                main { model.preferences(theme = theme) }; ready()
                verify(main { model.ui.workspace?.theme == theme }, "Theme ${theme.name} settles without rebuilding navigation")
            }
            main { model.preferences(theme = ColorTheme.Paper) }; ready()
            main { model.preferences(theme = ColorTheme.Ink) }
            SystemClock.sleep(100); screenshot("motion-theme-mid")
            ready(); screenshot("motion-theme-ink")
            main { model.preferences(theme = ColorTheme.Cream) }; ready()
            main { model.preferences(theme = ColorTheme.Sage) }; ready()
            verify(main { model.ui.workspace?.theme == ColorTheme.Sage }, "Sequential theme retargeting reaches the latest palette")

            shell("settings put global animator_duration_scale 0")
            SystemClock.sleep(150)
            click("最近"); ready(); main { model.open(id) }; ready()
            click("返回"); ready()
            verify(main { model.ui.page == Page.Recent && !model.ui.transitioning && !model.ui.tabTransitioning && model.textState == null }, "Zero animation scale completes tab transition and reverse navigation without locks")
            main { model.preferences(theme = ColorTheme.Paper) }; ready()
            screenshot("motion-zero-scale")
        } finally {
            shell(if (oldScale == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $oldScale")
            ready()
            main { model.preferences(theme = oldTheme) }; ready()
        }
    }

    private fun tabTest() {
        val oldScale = shell("settings get global animator_duration_scale")
        val display = targetContext.resources.displayMetrics
        val density = display.density
        val licenses = model.languages.context().getString(R.string.ui_font)
        val paper = model.languages.context().getString(R.string.theme_paper)
        fun enabled(label: String): Boolean {
            var node: AccessibilityNodeInfo? = find(label)
            while (node != null) {
                if (!node.isEnabled) return false
                node = node.parent
            }
            return true
        }
        fun pixels(rect: Rect): android.graphics.Bitmap {
            val full = checkNotNull(uiAutomation.takeScreenshot())
            return android.graphics.Bitmap.createBitmap(full, rect.left, rect.top, rect.width(), rect.height()).also { full.recycle() }
        }
        fun hold(offsetDp: Float, release: Boolean, inspect: () -> Unit) {
            val bounds = Rect(); find(licenses).getBoundsInScreen(bounds)
            val time = SystemClock.uptimeMillis()
            fun send(action: Int, offset: Float = 0f) {
                MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                    display.widthPixels * .6f + offset * density, bounds.centerY().toFloat(), 0).let {
                    it.source = InputDevice.SOURCE_TOUCHSCREEN
                    check(uiAutomation.injectInputEvent(it, true)); it.recycle()
                }
            }
            send(MotionEvent.ACTION_DOWN)
            try {
                SystemClock.sleep(80)
                if (offsetDp != 0f) send(MotionEvent.ACTION_MOVE, offsetDp)
                SystemClock.sleep(750)
                inspect()
            } finally { send(if (release) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL, offsetDp) }
            ready()
        }
        try {
            shell("settings put global animator_duration_scale 1")
            click("设置"); ready()
            val chipBounds = Rect(); find(paper).getBoundsInScreen(chipBounds)
            val before = pixels(chipBounds)
            try {
                hold(0f, false) {
                    verify(main { model.ui.page == Page.Settings && !model.ui.tabTransitioning }, "Stationary long press does not start navigation")
                    verify(enabled(licenses) && enabled(paper), "Long press keeps font and theme controls enabled")
                    val during = pixels(chipBounds)
                    try { verify(before.sameAs(during), "Long press elsewhere does not recolor theme text pixels") } finally { during.recycle() }
                }
                hold(16f, true) {
                    verify(main { !model.ui.tabTransitioning }, "Small horizontal jitter does not drive an animation")
                }
                verify(main { model.ui.page == Page.Settings }, "Subthreshold release does not switch tabs")
                hold(72f, false) {
                    verify(main { !model.ui.tabTransitioning && model.ui.page == Page.Settings }, "Threshold drag stays on source until pointer up")
                    verify(enabled(licenses) && enabled(paper), "Recognized horizontal gesture does not disable settings text")
                    val during = pixels(chipBounds)
                    try { verify(before.sameAs(during), "Threshold drag does not move or gray theme text before release") } finally { during.recycle() }
                    screenshot("tabs-held")
                }
                verify(main { model.ui.page == Page.Settings }, "Cancelled horizontal gesture does not navigate")
            } finally { before.recycle() }
            hold(72f, true) { }
            verify(main { model.ui.page == Page.Recent }, "Accepted pointer up advances exactly one neighboring tab")
            swipe(.18f, .55f, .82f, .55f)
            verify(main { model.ui.page == Page.Directory }, "Second discrete swipe reaches Directory")
            swipe(.18f, .55f, .82f, .55f)
            verify(main { model.ui.page == Page.Directory }, "Discrete swipe does not wrap beyond the first tab")

            // Slow the animation only for observing the direct jump and its input gate.
            shell("settings put global animator_duration_scale 5")
            SystemClock.sleep(200)
            main { model.selectTab(Page.Settings) }
            val id = main { model.ui.tabTransitionId }
            SystemClock.sleep(250); screenshot("tabs-direct-mid")
            verify(main { model.ui.page == Page.Settings && model.ui.tabTransitioning }, "Nonadjacent navigation targets Settings directly during animation")
            main { model.selectTab(Page.Recent); model.newDocument(); model.finishTabTransition(id - 1) }
            verify(main { model.ui.page == Page.Settings && model.ui.tabTransitioning && model.ui.tabTransitionId == id }, "Repeated navigation and stale completion cannot interrupt an active tab transition")
            ready()
            verify(enabled(licenses) && enabled(paper), "Settings controls remain enabled after direct transition")
            swipe(.82f, .55f, .18f, .55f)
            verify(main { model.ui.page == Page.Settings }, "Discrete swipe does not wrap beyond the last tab")
            swipe(.5f, .72f, .5f, .38f)
            verify(main { model.ui.page == Page.Settings }, "Vertical scrolling remains independent of horizontal navigation")
            main { model.selectTab(Page.Directory); currentActivity?.recreate() }
            ready()
            verify(main { model.ui.page == Page.Directory && !model.ui.tabTransitioning }, "Activity recreation during a tab transition releases its input gate")
            shell("settings put global animator_duration_scale 0")
            SystemClock.sleep(200)
            click("设置"); ready()
            verify(main { model.ui.page == Page.Settings && !model.ui.tabTransitioning }, "Zero animation scale completes a nonadjacent tab switch without a lock")
            screenshot("tabs-settled")
        } finally {
            shell(if (oldScale == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $oldScale")
        }
    }

    private fun appearanceTest() {
        val oldTheme = main { checkNotNull(model.ui.workspace).theme }
        val oldScale = shell("settings get global animator_duration_scale")
        val density = targetContext.resources.displayMetrics.density
        val paper = model.languages.context().getString(R.string.theme_paper)
        val cream = model.languages.context().getString(R.string.theme_cream)
        val licenses = model.languages.context().getString(R.string.licenses)
        val catalog = File(workspaceDirectory(), "catalog.properties")
        // Hold the real disk gate to make even a slow preferences write observable.
        // This reproduces the disabled-color flash independently of device speed.
        val ioLock = WorkspaceViewModel::class.java.getDeclaredField("ioLock").apply { isAccessible = true }.get(model) as kotlinx.coroutines.sync.Mutex
        fun crop(rect: Rect): android.graphics.Bitmap {
            val full = checkNotNull(uiAutomation.takeScreenshot())
            return android.graphics.Bitmap.createBitmap(full, rect.left, rect.top, rect.width(), rect.height()).also { full.recycle() }
        }
        fun enabled(label: String): Boolean {
            var node: AccessibilityNodeInfo? = find(label)
            while (node != null) { if (!node.isEnabled) return false; node = node.parent }
            return true
        }
        fun darkest(bitmap: android.graphics.Bitmap, rect: Rect): Int {
            var value = 765
            for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                val pixel = bitmap.getPixel(x, y)
                value = minOf(value, android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel))
            }
            return value
        }
        try {
            shell("settings put global animator_duration_scale 1")
            click("设置"); ready()
            main { model.preferences(theme = ColorTheme.Paper) }; ready()
            val chip = Rect(); find(paper).getBoundsInScreen(chip)
            val before = crop(chip)
            val catalogBefore = catalog.readBytes()
            val stateBefore = main { model.ui }
            try {
                repeat(5) { click(paper) }
                verify(main { model.ui === stateBefore }, "Repeated selected theme clicks leave state and busy flags untouched")
                verify(catalogBefore.contentEquals(catalog.readBytes()), "Repeated selected theme clicks do not rewrite preferences")
                val after = crop(chip)
                try { verify(before.sameAs(after), "Repeated selected theme clicks preserve exact text pixels") } finally { after.recycle() }
            } finally { before.recycle() }
            val licenseBounds = Rect(); find(licenses).getBoundsInScreen(licenseBounds)
            val licenseBefore = crop(licenseBounds)
            kotlinx.coroutines.runBlocking { ioLock.lock() }
            try {
                click(cream)
                verify(main { model.ui.busy && model.ui.savingPreferences && model.ui.workspace?.theme == ColorTheme.Paper }, "Theme persistence remains serialized while disk is delayed")
                verify(enabled(paper) && enabled(licenses), "Pending theme persistence does not disable theme or license text")
                val during = crop(licenseBounds)
                try { verify(licenseBefore.sameAs(during), "Pending theme persistence keeps unrelated text pixels unchanged") } finally { during.recycle() }
                main { model.selectTab(Page.Recent) }
                verify(main { model.ui.page == Page.Settings }, "Navigation remains gated while preferences are being persisted")
            } finally { ioLock.unlock(); licenseBefore.recycle() }
            ready()
            verify(main { model.ui.workspace?.theme == ColorTheme.Cream && !model.ui.savingPreferences }, "Theme persistence releases its gate after success")

            shell("settings put global animator_duration_scale 3")
            SystemClock.sleep(200)
            data class PaletteCase(val from: ColorTheme, val to: ColorTheme, val start: Int, val end: Int, val textBrightness: Int)
            for ((from, to, startColor, endColor, textBrightness) in listOf(
                PaletteCase(ColorTheme.Paper, ColorTheme.Cream, 0xFFF7F7F5.toInt(), 0xFFF5F1E8.toInt(), 52 + 49 + 45),
                PaletteCase(ColorTheme.Mist, ColorTheme.Sage, 0xFFF1F4F6.toInt(), 0xFFF1F4EF.toInt(), 48 + 55 + 47),
            )) {
                main { model.preferences(theme = from) }; ready(); SystemClock.sleep(500)
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                val minimumText = darkest(bitmap, licenseBounds); bitmap.recycle()
                main { model.preferences(theme = to) }
                while (main { model.ui.busy }) SystemClock.sleep(5)
                val samples = mutableSetOf<Int>()
                var bounded = true
                var stableText = true
                repeat(10) {
                    val frame = checkNotNull(uiAutomation.takeScreenshot())
                    try {
                        val pixel = frame.getPixel(2, frame.height / 3)
                        samples += pixel
                        for (shift in listOf(0, 8, 16)) {
                            val first = (startColor ushr shift) and 255
                            val last = (endColor ushr shift) and 255
                            bounded = bounded && ((pixel ushr shift) and 255) in (minOf(first, last) - 2)..(maxOf(first, last) + 2)
                        }
                        // Both endpoints have different foreground luminance; permit
                        // that intentional change, but never a disabled-color excursion.
                        stableText = stableText && darkest(frame, licenseBounds) <= maxOf(minimumText, textBrightness) + 6
                        if (it == 2) File(targetContext.cacheDir, "workspace-test-appearance-$from-$to-mid.png").outputStream().use { output -> frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
                    } finally { frame.recycle() }
                    SystemClock.sleep(45)
                }
                verify(samples.size > 2 && bounded, "Nearby palettes $from to $to interpolate through bounded intermediate background pixels")
                verify(stableText, "Nearby palettes $from to $to never flash disabled text")
                ready()
            }

            main { model.preferences(theme = ColorTheme.Paper) }; ready(); SystemClock.sleep(500)
            click("目录"); ready()
            val first = Rect(); find("目录").getBoundsInScreen(first)
            val last = Rect(); find("设置").getBoundsInScreen(last)
            val sampleY = first.centerY()
            fun indicatorCenter(): Float {
                val frame = checkNotNull(uiAutomation.takeScreenshot())
                try {
                    val xs = (0 until frame.width).filter { frame.getPixel(it, sampleY) == 0xFFEEEFEA.toInt() }
                    check(xs.isNotEmpty()) { "Missing solid tab highlight at y=$sampleY" }
                    return (xs.first() + xs.last()) / 2f
                } finally { frame.recycle() }
            }
            val initialCenter = indicatorCenter()
            verify(kotlin.math.abs(initialCenter - first.exactCenterX()) < 2 * density, "Single highlight initially aligns with Directory")
            main { model.selectTab(Page.Settings) }
            val positions = mutableListOf<Float>()
            repeat(9) {
                positions += indicatorCenter()
                if (it == 2) screenshot("appearance-tab-mid")
                SystemClock.sleep(45)
            }
            verify(positions.any { it > first.centerX() + 20 * density && it < last.centerX() - 20 * density }, "Cross-tab highlight has real horizontal intermediate positions")
            verify(positions.zipWithNext().all { (a, b) -> b >= a - 1 }, "Highlight moves monotonically without fading out or jumping backwards")
            ready()
            verify(kotlin.math.abs(indicatorCenter() - last.exactCenterX()) < 2 * density, "Highlight settles exactly on Settings")
            val settled = indicatorCenter()
            click("设置"); ready()
            verify(indicatorCenter() == settled, "Repeated selected tab click does not restart or move its highlight")
            screenshot("appearance-settled")
        } finally {
            shell(if (oldScale == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $oldScale")
            ready()
            main { model.preferences(theme = oldTheme) }; ready()
        }
    }

    private fun motionVisualTest() {
        val oldTheme = main { checkNotNull(model.ui.workspace).theme }
        val oldScale = shell("settings get global animator_duration_scale")
        val oldFont = shell("settings get system font_scale")
        val oldNight = shell("cmd uimode night").substringAfter(": ").trim()
        try {
            shell("settings put global animator_duration_scale 3")
            main { model.go(Page.Directory) }; ready()
            main { model.createFolder("动效验收-" + SystemClock.uptimeMillis(), Workspace.ROOT) }; ready()
            val folder = main { model.ui.selected }
            main { model.newDocument(Page.Directory, folder) }; ready()
            main { model.create(DocumentKind.Markdown) }; ready()
            val id = main { checkNotNull(model.ui.activeId) }
            main { model.typeset(false) }
            setText("编辑正文", "# 动效验收\n\n中文与 emoji 😀，只用于测试过渡。\n\n第二段应稳定显示。")
            saved()
            val retained = main { model.textState }
            main { model.back() }
            val start = SystemClock.uptimeMillis()
            while (main { model.ui.page != Page.Directory }) {
                check(SystemClock.uptimeMillis() - start < 12_000); SystemClock.sleep(5)
            }
            verify(main { model.ui.transitioning && model.textState === retained && model.ui.backwards }, "Reverse exit retains the exact editor session until the final frame")
            SystemClock.sleep(220); screenshot("motion-editor-exit-mid")
            ready()
            verify(main { model.textState == null }, "Reverse exit releases the retained session on completion")
            screenshot("motion-directory")
            // Folder and child are synthetic; exercise real arrow and list animations.
            val name = main { checkNotNull(model.ui.workspace?.entry(folder)).name }
            tap("展开 $name"); ready(); screenshot("motion-directory-expanded")
            tap("收起 $name"); ready(); screenshot("motion-directory-collapsed")
            main { model.newDocument(Page.Directory, folder) }
            SystemClock.sleep(100); screenshot("motion-new-mid")
            ready(); click("返回"); ready()
            verify(main { model.ui.page == Page.Directory && model.ui.selected == folder }, "New-page reverse transition restores Directory and selection")

            main { model.go(Page.Settings) }; ready()
            main { model.preferences(theme = ColorTheme.Paper) }; ready()
            SystemClock.sleep(900)
            main { model.preferences(theme = ColorTheme.Ink) }
            while (main { model.ui.busy }) SystemClock.sleep(5)
            val paletteSamples = mutableSetOf<Int>()
            var capturedMiddle = false
            repeat(16) {
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    val pixel = bitmap.getPixel(2, bitmap.height / 3)
                    paletteSamples += pixel
                    if (!capturedMiddle && pixel != 0xFFF7F7F5.toInt() && pixel != 0xFF1E201F.toInt()) {
                        File(targetContext.cacheDir, "workspace-test-motion-theme-slow-mid.png").outputStream().use { output -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
                        capturedMiddle = true
                    }
                    bitmap.recycle()
                }
                SystemClock.sleep(45)
            }
            sendStatus(0, Bundle().apply { putString("stream", "Theme background sample colors=${paletteSamples.joinToString { Integer.toHexString(it) }}\n") })
            verify(paletteSamples.size > 2 && capturedMiddle, "Theme paints intermediate colors rather than jumping between endpoint palettes")
            ready(); screenshot("motion-theme-slow-final")
            if (oldNight in listOf("yes", "no", "auto")) {
                main { model.preferences(theme = ColorTheme.System) }; ready()
                shell("cmd uimode night yes"); SystemClock.sleep(900); ready()
                screenshot("motion-system-dark")
                shell("cmd uimode night no"); SystemClock.sleep(900); ready()
                screenshot("motion-system-light")
                verify(main { model.ui.workspace?.theme == ColorTheme.System && !model.ui.transitioning }, "System night changes preserve follow-system preference and navigation")
            }
            main { model.licenses() }; ready(); screenshot("motion-licenses")
            click("返回"); ready()
            verify(main { model.ui.page == Page.Settings }, "License page reverse transition returns to Settings")
            shell("settings put system font_scale 2.0")
            waitFor("200 percent font applied to Activity") { main { currentActivity?.resources?.configuration?.fontScale == 2f } }
            ready()
            screenshot("motion-font-200")
            verify(main { model.ui.page == Page.Settings && !model.ui.transitioning }, "200 percent font configuration does not strand navigation")
            main { model.go(Page.Directory) }; ready(); screenshot("motion-directory-font-200")
            // Remove only the synthetic subtree made by this visual test.
            main { model.delete(folder) }; ready()
            verify(main { model.ui.workspace?.entry(folder) == null && model.ui.workspace?.entry(id) == null }, "Animated deletion removes only the synthetic test subtree")
        } finally {
            shell(if (oldScale == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $oldScale")
            shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
            if (oldNight in listOf("yes", "no", "auto")) shell("cmd uimode night $oldNight")
            SystemClock.sleep(700); ready()
            main { model.preferences(theme = oldTheme) }; ready()
        }
    }

    private fun selectionTest() {
        val oldPreferences = main { checkNotNull(model.ui.workspace) }
        val oldTheme = oldPreferences.theme
        main { model.go(Page.Recent) }; ready()
        screenshot("recent-plus-icon")
        verify(nodes().any { it.contentDescription?.toString() == "新建文稿" }, "Recent plus icon retains an accessible new-draft label")
        main { model.go(Page.Directory) }; ready()
        main { model.preferences(theme = ColorTheme.Paper, sort = SortKey.Name, descending = false, foldersFirst = true) }; ready()
        val store = currentStore()
        val lock = WorkspaceViewModel::class.java.getDeclaredField("ioLock").apply { isAccessible = true }.get(model) as kotlinx.coroutines.sync.Mutex
        val suffix = (SystemClock.uptimeMillis() % 100000).toString()
        val rootName = "000-复选-$suffix"
        val names = listOf("甲-$suffix", "乙-$suffix", "深-$suffix", "源-$suffix", "路线-$suffix", "雨夜-$suffix", "章节-$suffix", "人物-$suffix")
        val ids = runBlocking { withContext(Dispatchers.IO) { lock.withLock {
            val root = store.create(rootName, Workspace.ROOT, DocumentKind.Folder)
            val a = store.create(names[0], root, DocumentKind.Folder)
            val b = store.create(names[1], root, DocumentKind.Folder)
            val deep = store.create(names[2], a, DocumentKind.Folder)
            val source = store.create(names[3], root, DocumentKind.Plain, "独立剪贴板快照 👩🏽‍💻")
            val one = store.create(names[4], a, DocumentKind.Plain, "路线原文")
            val two = store.create(names[5], deep, DocumentKind.Plain, "雨夜原文")
            val three = store.create(names[6], b, DocumentKind.Plain, "章节原文")
            val four = store.create(names[7], b, DocumentKind.Plain, "人物原文")
            listOf(root, a, b, deep, source, one, two, three, four)
        } } }
        val root = ids[0]; val a = ids[1]; val b = ids[2]; val deep = ids[3]; val source = ids[4]
        val one = ids[5]; val two = ids[6]; val three = ids[7]; val four = ids[8]
        fun label(id: String) = main { checkNotNull(model.ui.workspace?.entry(id)).filename }
        fun shown(text: String) = nodes().any { it.text?.toString() == text || it.contentDescription?.toString() == text }
        fun show(text: String) {
            fun match() = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
            if (match() != null) return
            for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                repeat(16) {
                    val scroller = nodes().firstOrNull { it.isScrollable } ?: return@repeat
                    val bounds = Rect(); scroller.getBoundsInScreen(bounds)
                    val x = bounds.centerX().toFloat()
                    val low = bounds.top + bounds.height() * .25f
                    val high = bounds.top + bounds.height() * .75f
                    val from = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) low else high
                    val to = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) high else low
                    val down = SystemClock.uptimeMillis()
                    for (step in 0..9) {
                        val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 9 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                        val y = from + (to - from) * step / 9
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { this.source = InputDevice.SOURCE_TOUCHSCREEN }
                        uiAutomation.injectInputEvent(event, true); event.recycle(); SystemClock.sleep(30)
                    }
                    SystemClock.sleep(220); waitForIdleSync()
                    if (match() != null) return
                }
            }
            error("Unable to scroll to synthetic selection item: $text")
        }
        fun tap(text: String) {
            show(text)
            val bounds = Rect(); find(text).getBoundsInScreen(bounds)
            check(!bounds.isEmpty) { "No touch bounds for $text" }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0).apply { this.source = InputDevice.SOURCE_TOUCHSCREEN }
                uiAutomation.injectInputEvent(event, true); event.recycle(); SystemClock.sleep(35)
            }
            SystemClock.sleep(350); waitForIdleSync()
        }
        fun hold(text: String, physical: Boolean = false) { show(text); longPress(text, physical) }
        fun chooseFolder(id: String) {
            val location = main { checkNotNull(model.ui.workspace).path(id) }
            val node = find(location)
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            tap(location)
        }
        try {
            main { model.reload() }; ready()
            if (shown("展开 文稿库")) tap("展开 文稿库")
            if (shown("收起全部文件夹")) tap("收起全部文件夹")
            for (name in listOf(rootName, names[0], names[2], names[1])) {
                show(name)
                if (shown("展开 $name")) tap("展开 $name")
            }
            main { model.select(root) }
            hold(label(source), physical = true); tap("复制"); ready()
            verify(main { model.clipboard?.ids == listOf(source) && model.clipboard?.cut == false }, "Single context copy populates the shared private clipboard")
            hold(label(one), physical = true); tap("复选"); ready()
            verify(main { model.selection == setOf(one) && model.ui.selected == root }, "Context menu enters selection without changing the new draft location")
            verify(!shown("搜索文稿") && !shown("选择文件夹可更改新建位置，长按显示更多") && !shown("最近") && shown("清空选择") && shown("退出复选"), "Selection removes toolbar hint and main navigation, keeping X and clear")
            tap(label(two)); tap(label(three))
            verify(main { model.selection == setOf(one, two, three) }, "Row clicks select files across directories and depths without opening the editor")
            tap("粘贴"); find("选择一个粘贴位置")
            verify(main { model.pasteRequest?.targets?.suggested == null && model.pasteRequest?.targets?.related?.toSet() == setOf(a, deep, b) }, "Dispersed selection resolves distinct suggested destinations without an implicit target")
            var pasteButton: AccessibilityNodeInfo? = find("粘贴到这里")
            var disabled = false
            while (pasteButton != null) { if (!pasteButton.isEnabled) disabled = true; pasteButton = pasteButton.parent }
            verify(disabled, "Ambiguous paste confirm remains disabled before a destination is selected")
            screenshot("selection-paste-targets")
            tap("取消")
            verify(main { model.selection?.size == 3 && model.clipboard?.ids == listOf(source) }, "Cancel preserves selected destination hints and copied source")
            tap("粘贴"); chooseFolder(b); tap("粘贴到这里"); ready()
            val copied = main { checkNotNull(model.ui.workspace).entries.single { it.parent == b && it.name == names[3] }.id }
            verify(main { model.ui.workspace?.entries?.count { it.name == names[3] } == 2 && model.selection?.size == 3 && model.clipboard?.ids == listOf(source) }, "One copied source is pasted once to the chosen folder, not once per selected file")
            verify(source(copied, DocumentKind.Plain) == "独立剪贴板快照 👩🏽‍💻", "Copied body is complete and independent")
            tap("清空选择")
            verify(main { model.selection?.isEmpty() == true && model.clipboard != null }, "Clear preserves selection mode and shared clipboard")
            tap(label(three)); tap(label(four)); tap("粘贴"); find("确认粘贴位置")
            verify(main { model.pasteRequest?.targets?.suggested == b }, "Multiple files in one folder resolve one destination")
            tap("取消"); screenshot("selection-light")
            main { currentActivity?.recreate() }; ready()
            verify(main { model.selection == setOf(three, four) && model.clipboard?.ids == listOf(source) }, "Activity recreation retains selection IDs and shared clipboard without repeating an action")
            tap("清空选择"); tap(label(a))
            verify(main { model.selection == setOf(a) }, "Selecting an ancestor normalizes the entire subtree")
            tap(label(one))
            verify(main { model.selection == setOf(a) }, "Inherited descendants cannot be excluded while their folder is selected")
            tap("收起 " + names[0])
            verify(main { model.selection == setOf(a) }, "Collapsing a folder preserves the recursive selection")
            tap("展开 " + names[0]); tap("清空选择"); tap(label(one))
            verify(main { model.ui.workspace?.selectionOf(model.selection.orEmpty(), a) == ItemSelection.Partial }, "Selecting a child gives its ancestor a partial check state")
            tap("退出复选")
            verify(main { model.selection == null && model.clipboard?.ids == listOf(source) } && shown("搜索文稿") && shown("最近"), "X exits selection and restores the normal controls without clearing clipboard")
            hold(label(one)); tap("剪切"); ready()
            hold(label(deep)); tap("复选"); tap("粘贴"); find("确认粘贴位置"); tap("取消")
            verify(main { model.clipboard?.cut == true && model.clipboard?.ids == listOf(one) && model.ui.workspace?.entry(one)?.parent == a }, "Canceling a cut paste leaves source and clipboard unchanged")
            tap("粘贴"); tap("粘贴到这里"); ready()
            verify(main { model.clipboard == null && model.ui.workspace?.entry(one)?.parent == deep }, "Cut paste moves a stable ID and clears clipboard only after successful publication")
            tap("清空选择"); main { model.toggleChecked(one); model.toggleChecked(three) }
            tap("复制"); ready()
            verify(main { model.clipboard?.items?.size == 2 && model.clipboard?.cut == false }, "Batch copy uses the same clipboard as single-item menus")
            tap("退出复选"); hold(label(root)); tap("粘贴"); find("剪贴板 · 复制 2 项"); tap("粘贴到这里"); ready()
            verify(main { model.ui.workspace?.entries?.count { it.parent == root && it.name in setOf(names[4], names[6]) } == 2 }, "Single folder paste consumes a multi-item copied snapshot")
            main { model.startSelection(one) }; tap("删除"); find("删除 1 项？"); tap("取消")
            verify(main { model.ui.workspace?.entry(one) != null }, "Cancel batch delete preserves the original")
            tap("删除"); tap("删除"); ready()
            verify(main { model.ui.workspace?.entry(one) == null && model.selection?.isEmpty() == true && model.clipboard?.items?.size == 2 }, "Batch delete cleans selection while preserving independent copy snapshots")
            tap("粘贴"); find("剪贴板 · 复制 2 项"); tap("取消")
            main { model.exitSelection(); model.preferences(theme = ColorTheme.Ink) }; ready()
            main { model.startSelection(b) }; ready(); screenshot("selection-dark")
        } catch (e: Exception) {
            screenshot("selection-fixture-state")
            sendStatus(0, Bundle().apply { putString("stream", "Selection fixture nodes: " + nodes().mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }.filter { suffix in it }.joinToString(" | ") + "\n") })
            throw e
        } finally {
            main { model.dismissPaste(); model.dismissDelete(); model.exitSelection() }; ready()
            if (main { model.ui.workspace?.entry(root) != null }) { main { model.delete(root) }; ready() }
            main { model.preferences(theme = oldTheme, sort = oldPreferences.sort, descending = oldPreferences.descending, foldersFirst = oldPreferences.foldersFirst) }; ready()
        }
    }

    private fun selectionVisualTest() {
        val oldTheme = main { checkNotNull(model.ui.workspace).theme }
        val oldFont = shell("settings get system font_scale")
        val oldSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val store = currentStore()
        val lock = WorkspaceViewModel::class.java.getDeclaredField("ioLock").apply { isAccessible = true }.get(model) as kotlinx.coroutines.sync.Mutex
        val ids = runBlocking { withContext(Dispatchers.IO) { lock.withLock {
            val root = store.create("窄屏复选-" + SystemClock.uptimeMillis(), Workspace.ROOT, DocumentKind.Folder)
            val a = store.create("较长的第一个目标文件夹", root, DocumentKind.Folder)
            val b = store.create("第二个目标文件夹", root, DocumentKind.Folder)
            val source = store.create("剪贴板来源", root, DocumentKind.Plain, "合成内容")
            val one = store.create("第一篇", a, DocumentKind.Plain)
            val two = store.create("第二篇", b, DocumentKind.Plain)
            listOf(root, source, one, two)
        } } }
        try {
            main { model.reload() }; ready(); main { model.go(Page.Directory) }; ready()
            main { model.preferences(theme = ColorTheme.Ink) }; ready()
            main { model.startSelection(ids[2]); model.toggleChecked(ids[3]); model.copy(ids[1], false) }; ready()
            shell("wm size 840x1890"); shell("settings put system font_scale 2.0")
            waitFor("actual narrow width and font scaling") { main { currentActivity?.resources?.configuration?.let { it.screenWidthDp <= 320 && it.fontScale >= 1.99f } == true } }
            ready(); SystemClock.sleep(1500); waitForIdleSync(); screenshot("selection-font-200-narrow")
            val bitmap = checkNotNull(uiAutomation.takeScreenshot()); val width = bitmap.width; val height = bitmap.height; bitmap.recycle()
            fun inside(label: String): Boolean {
                val node = find(label); val bounds = Rect(); node.getBoundsInScreen(bounds)
                return !bounds.isEmpty && bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height
            }
            verify(listOf("退出复选", "清空选择", "复制", "剪切", "粘贴", "删除").all(::inside), "320dp with actual 200% fonts keeps header and all four actions inside the display")
            main { model.requestPaste() }; waitForIdleSync(); SystemClock.sleep(500)
            screenshot("selection-font-200-targets")
            verify(inside("取消") && inside("粘贴到这里") && main { model.pasteRequest?.targets?.suggested == null }, "Narrow 200% destination dialog keeps cancel/confirm visible and ambiguity unselected")
        } finally {
            main { model.dismissPaste(); model.exitSelection() }
            shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            ready(); main { model.delete(ids[0]) }; ready(); main { model.preferences(theme = oldTheme) }; ready()
        }
    }

    private fun markdownLongTest() {
        main { model.newDocument(Page.Recent) }; ready(); main { model.create(DocumentKind.Markdown) }; ready()
        val id = main { checkNotNull(model.ui.activeId) }
        try {
            click("源码")
            val unit = "# 标题 😀\n\n正文 **粗体** 与中文。\n\n"
            val points = unit.codePointCount(0, unit.length)
            val dense = unit.repeat(100_000 / points) + "中".repeat(100_000 % points)
            var started = SystemClock.uptimeMillis()
            setText("编辑正文", dense); saved(60_000)
            val savedAt = SystemClock.uptimeMillis()
            waitFor("dense Markdown parsed", 60_000) { main { model.markdownDocument?.source == dense } }
            sendStatus(0, Bundle().apply { putString("stream", "Dense Markdown 100000 points input+save_ms=${savedAt - started}, input+parse_ms=${SystemClock.uptimeMillis() - started}\n") })
            verify(source(id, DocumentKind.Markdown) == dense, "100000-point dense Markdown saves complete UTF-8 source")
            val profiler = if (profileMarkdownLong) {
                android.os.Debug.startMethodTracingSampling(File(targetContext.cacheDir, "markdown-long.trace").absolutePath, 32 * 1024 * 1024, 5_000)
                Thread {
                    SystemClock.sleep(15_000)
                    android.os.Debug.stopMethodTracing()
                    sendStatus(0, Bundle().apply { putString("stream", "Markdown sampling trace captured\n") })
                }.apply { isDaemon = true; start() }
            } else null
            started = SystemClock.uptimeMillis(); click("排版"); waitForIdleSync()
            profiler?.join()
            sendStatus(0, Bundle().apply { putString("stream", "Dense Markdown typeset_switch_ms=${SystemClock.uptimeMillis() - started}\n") })
            verify(main { model.textState!!.text.toString() == dense }, "Dense Markdown typeset projection preserves all source")
            started = SystemClock.uptimeMillis(); click("阅读"); waitForIdleSync()
            sendStatus(0, Bundle().apply { putString("stream", "Dense Markdown reading_switch_ms=${SystemClock.uptimeMillis() - started}\n") })
            verify(nodes().none { it.isEditable }, "Long Markdown reading uses a non-editable lazy viewport")
            click("源码")
            val emoji = "😀".repeat(100_000)
            started = SystemClock.uptimeMillis(); setText("编辑正文", emoji); saved(60_000)
            waitFor("emoji Markdown parsed", 60_000) { main { model.markdownDocument?.source == emoji } }
            sendStatus(0, Bundle().apply { putString("stream", "Single paragraph 100000 emoji Markdown input+save+parse_ms=${SystemClock.uptimeMillis() - started}\n") })
            verify(source(id, DocumentKind.Markdown) == emoji, "100000 emoji code points remain complete through parse and save")
            setText("编辑正文", emoji + "超"); verify(main { model.textState!!.text.toString() == emoji }, "Markdown oversized paste is rejected without partial replacement")
            main { model.dismissError() }
        } finally {
            if (main { model.ui.page == Page.Editor }) { main { model.back() }; ready() }
            main { model.delete(id) }; ready()
        }
    }

    private fun markdownTableTest() {
        val oldTheme = main { model.ui.workspace!!.theme }
        val oldFont = shell("settings get system font_scale").trim()
        val oldSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        main { model.newDocument(Page.Recent) }; ready(); main { model.create(DocumentKind.Markdown) }; ready()
        val id = main { checkNotNull(model.ui.activeId) }
        val sample = "表前正文\n\n| 标题 | B |\n| :--- | ---: |\n| 中文 |  |\n| **粗体** | 后 |\n\n表后正文 😀"
        fun parsed() = waitFor("table parse") { main { model.markdownDocument?.source == model.textState?.text?.toString() } }
        fun body() = main { model.textState!!.text.toString() }
        fun cell(label: String): AccessibilityNodeInfo {
            fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isEditable) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { editable(it)?.let { found -> return found } }
                return null
            }
            val labelled = find(label)
            var ancestor: AccessibilityNodeInfo? = labelled
            while (ancestor != null) {
                if (ancestor.isEditable) return ancestor
                ancestor = ancestor.parent
            }
            return checkNotNull(editable(labelled))
        }
        fun connection() = checkNotNull(currentActivity!!.window.decorView.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
        fun hideKeyboard() {
            main { (currentActivity!!.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(currentActivity!!.window.decorView.windowToken, 0) }
            SystemClock.sleep(400); waitForIdleSync()
        }
        fun tableBackground(label: String) {
            val rect = Rect().also { find(label).getBoundsInScreen(it) }
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            val x = (rect.left + 3).coerceIn(0, bitmap.width - 1)
            val y = (rect.top + 3).coerceIn(0, bitmap.height - 1)
            val beside = (rect.left - 12).coerceAtLeast(0)
            verify(bitmap.getPixel(x, y) == bitmap.getPixel(beside, y), "Table cell background matches adjacent document background")
            bitmap.recycle()
        }
        try {
            click("源码"); setText("编辑正文", sample); saved(); parsed(); click("排版"); hideKeyboard()
            verify(nodes().none { it.text?.toString() == "编辑表格源码" }, "Typeset removes table source action")
            verify(cell("表格第 2 行，第 1 列").isEditable, "Table exposes native cell input directly")
            val tap = Rect().also { find("表格第 2 行，第 1 列").getBoundsInScreen(it) }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, tap.centerX().toFloat(), tap.centerY().toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                check(uiAutomation.injectInputEvent(event, true)); event.recycle()
            }
            SystemClock.sleep(250); main { connection().commitText("点", 1) }; saved(); parsed()
            verify(cell("表格第 2 行，第 1 列").text?.contains("点") == true, "A real tap enters the cell and sends native keyboard input without opening source")
            click("撤销"); saved(); parsed()
            setText("表格第 2 行，第 1 列", "中文修改 😀"); saved(); parsed()
            val changed = sample.replace("中文", "中文修改 😀")
            verify(body() == changed && source(id, DocumentKind.Markdown) == changed, "Cell input immediately updates the shared saved Markdown and preserves surrounding source")
            click("撤销"); saved(); parsed(); verify(body() == sample, "Cell edit uses the shared undo history")
            click("重做"); saved(); parsed(); verify(body() == changed, "Shared redo restores cell input")
            setText("表格第 2 行，第 2 列", "a|b"); saved(); parsed()
            verify(body().contains("a\\|b") && cell("表格第 2 行，第 2 列").text?.contains("a|b") == true, "Typing a pipe keeps two columns and hides the escape")
            setText("表格第 1 行，第 1 列", "表头"); saved(); parsed()
            verify(body().contains("| 表头 | B |\n| :--- | ---: |"), "Header editing keeps the original alignment separator")
            setText("表格第 3 行，第 1 列", "粗字"); saved(); parsed()
            verify(body().contains("**粗字**"), "Editing visible formatted cell text preserves emphasis syntax")
            val beforeLimit = body()
            setText("表格第 2 行，第 1 列", "界".repeat(100_001)); saved(); parsed()
            verify(body() == beforeLimit && cell("表格第 2 行，第 1 列").text?.contains("中文修改") == true, "Oversized cell paste rejects the entire change and keeps the editable mirror")
            setText("表格第 2 行，第 1 列", ""); saved(); parsed()
            // The synthetic InputConnection owns composition here; a visible Gboard would
            // finish an unfamiliar synthetic composing span in response to updateSelection.
            hideKeyboard()
            val composingConnection = main { connection() }
            main { check(composingConnection.setComposingText("zh", 1)); check(composingConnection.setComposingText("zhong", 1)) }; parsed()
            main { check(composingConnection.setComposingText("中文", 1)); composingConnection.finishComposingText() }; saved(); parsed()
            verify(cell("表格第 2 行，第 1 列").text?.contains("中文") == true && !body().contains("zhong"), "Real cell InputConnection composition replaces candidates without duplicating text")
            main { connection().setComposingText("读", 1) }; val composing = body()
            click("阅读"); saved(); parsed()
            verify(body() == composing && nodes().none { it.isEditable }, "Switching to reading during cell composition keeps visible input and becomes read-only")
            click("源码"); verify(body() == composing && source(id, DocumentKind.Markdown) == composing, "Source view receives exact cell edits")
            click("排版")
            val pending = File(workspaceDirectory(), "documents/$id/body.md.new")
            check(pending.mkdir())
            try {
                setText("表格第 2 行，第 1 列", "保存失败保留")
                waitFor("cell save failure") { main { model.save?.status?.value?.phase == SavePhase.Failed } }
                click("阅读"); parsed()
                verify(body().contains("保存失败保留") && source(id, DocumentKind.Markdown) == composing, "A failed cell save retains latest memory text in reading and the last confirmed disk file")
            } finally { check(pending.delete()); main { model.retrySave() }; saved() }
            click("排版"); main { model.preferences(theme = ColorTheme.Paper) }; ready(); hideKeyboard(); tableBackground("表格第 1 行，第 1 列"); screenshot("table-typeset-light")
            click("文本"); verify(nodes().none { it.text?.toString()?.contains("· 1/3") == true }, "Toolbar removes group position label")
            click("条目操作"); verify(nodes().any { it.text?.toString() == "导出 .md 副本" } && nodes().none { it.text?.toString() == "导出 .txt 副本" }, "Markdown menu exports only a Markdown copy")
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); SystemClock.sleep(250)
            main { model.preferences(theme = ColorTheme.Ink) }; ready(); tableBackground("表格第 1 行，第 1 列"); screenshot("table-typeset-dark")
            main { model.editorMode(EditorMode.Reading) }; ready(); screenshot("table-reading-dark")
            main { model.editorMode(EditorMode.Typeset); model.changeLanguage("en") }; ready()
            shell("settings put system font_scale 2.0")
            val density = Regex("(?:Override|Physical) density: (\\d+)").findAll(shell("wm density")).last().groupValues[1].toInt()
            shell("wm size ${320 * density / 160}x${720 * density / 160}"); SystemClock.sleep(800); ready()
            screenshot("table-en-narrow-200")
            verify(cell("Table row 1, column 1").isEditable, "Cell input remains accessible in English at 320dp and 200% font size")
        } catch (e: Exception) { screenshot("table-failure"); throw e } finally {
            shell("settings put system font_scale $oldFont"); shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            main { model.changeLanguage("zh-Hans") }; ready(); hideKeyboard()
            if (main { model.ui.page == Page.Editor }) { main { model.back() }; ready() }
            main { model.delete(id) }; ready(); main { model.preferences(theme = oldTheme) }; ready()
        }
    }

    private fun markdownToolbarTest() {
        val oldKeyboard = shell("settings get secure show_ime_with_hard_keyboard").trim()
        main { model.newDocument(Page.Recent) }; ready(); main { model.create(DocumentKind.Markdown) }; ready()
        val id = main { checkNotNull(model.ui.activeId) }
        fun bounds(label: String) = Rect().also { find(label).getBoundsInScreen(it) }
        fun tapPoint(x: Float, y: Float) {
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                check(uiAutomation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(30)
            }
            SystemClock.sleep(300); waitForIdleSync()
        }
        fun hideKeyboard() {
            main {
                val activity = currentActivity!!
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                activity.currentFocus?.clearFocus()
            }
            SystemClock.sleep(600); waitForIdleSync()
        }
        try {
            click("源码"); val body = "###### 六级标题\n\n####### 更深标题\n\n正文测试"; setText("编辑正文", body); saved(); hideKeyboard()
            val count = main { model.languages.context().resources.getQuantityString(R.plurals.document_count, body.length, body.length) }
            val footer = bounds(count)
            click("排版"); verify(bounds(count).centerY() == footer.centerY(), "Source and typeset footer share a baseline")
            click("阅读"); verify(bounds(count).centerY() == footer.centerY(), "Reading footer reserves the same 48dp row as editing")
            val modeBoxes = listOf("源码", "排版", "阅读").map(::bounds)
            verify(modeBoxes.maxOf { it.width() } - modeBoxes.minOf { it.width() } <= 1 && modeBoxes.zipWithNext().all { (a,b) -> a.right == b.left }, "Three mode touch targets are equal and contiguous")
            val sourceBox = modeBoxes[0]
            tapPoint(sourceBox.left + 3f, sourceBox.centerY().toFloat())
            verify(main { model.ui.editorMode == EditorMode.Source }, "Left edge inside source indicator accepts a real touch")
            val readingBox = bounds("阅读")
            tapPoint(readingBox.right - 3f, readingBox.centerY().toFloat())
            verify(main { model.ui.editorMode == EditorMode.Reading }, "Right edge inside reading indicator accepts a real touch")
            click("排版"); click("文本")
            verify(listOf("标题", "粗体", "斜体", "删除线", "链接").all { name -> nodes().any { it.contentDescription?.toString() == name } }, "Text group presents five related Lucide tools")
            click("文本"); verify(nodes().none { it.contentDescription?.toString() == "粗体" }, "Clicking current group hides its tools")
            click("段落"); verify(listOf("列表", "引用", "增加缩进", "减少缩进").all { name -> nodes().any { it.contentDescription?.toString() == name } }, "Selecting another group expands its paragraph tools")
            click("插入"); verify(listOf("行内代码", "代码块", "分隔线", "表格").all { name -> nodes().any { it.contentDescription?.toString() == name } }, "Insert group exposes four tools without a More menu")
            screenshot("markdown-toolbar-insert")
            click("文本"); click("标题")
            verify(nodes().any { it.text?.toString() == "5 级标题" } && nodes().any { it.text?.toString() == "6 级标题" }, "Heading popup contains body and all six heading levels")
            screenshot("markdown-toolbar-heading")
            click("6 级标题"); saved()
            verify(main { model.textState!!.text.toString() == body.replace("正文测试", "###### 正文测试") }, "Sixth heading tool inserts the exact source prefix")
            click("撤销"); saved()
            verify(main { model.textState!!.text.toString() == body }, "Sixth heading formatting restores in one undo")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            val edit = nodes().first { it.isEditable }; edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            main { val view = currentActivity!!.window.decorView.findFocus()!!; (currentActivity!!.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(view, 0) }
            waitFor("IME shown", 20_000) { main { currentActivity!!.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true } }
            SystemClock.sleep(500); waitForIdleSync()
            val selection = main { model.currentSelection() }; val revision = main { model.save!!.revision }
            val toolbar = bounds("粗体")
            // Recognize the gesture during movement, but publish the group only on pointer-up.
            val time = SystemClock.uptimeMillis(); val y = toolbar.centerY().toFloat()
            val width = targetContext.resources.displayMetrics.widthPixels
            for (step in 0..8) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), if (step == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,
                    width * (.8f - .6f * step / 8), y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                check(uiAutomation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(25)
            }
            verify(nodes().any { it.contentDescription?.toString() == "粗体" }, "Horizontal movement does not change group before release")
            MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, width * .2f, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }.let { check(uiAutomation.injectInputEvent(it, true)); it.recycle() }
            find("列表"); SystemClock.sleep(350)
            verify(nodes().any { it.contentDescription?.toString() == "引用" }, "Pointer-up discretely advances to paragraph group while IME is visible")
            verify(main { model.currentSelection() == selection && model.save!!.revision == revision && currentActivity!!.window.decorView.rootWindowInsets!!.isVisible(android.view.WindowInsets.Type.ime()) }, "Group swipe preserves selection, body revision and keyboard")
            val listTop = bounds("列表").top
            click("列表")
            var lastItem = find("取消列表")
            while (!lastItem.isClickable && lastItem.parent != null) lastItem = lastItem.parent
            val last = Rect().also { lastItem.getBoundsInScreen(it) }
            val density = targetContext.resources.displayMetrics.density
            verify((listTop - last.bottom) in (6 * density).toInt()..(11 * density).toInt(), "Popup anchors 4dp above toolbar plus 4dp content padding with IME")
            screenshot("markdown-toolbar-ime-list")
            click("取消列表"); saved()
            verify(main { model.textState!!.text.toString() == body || model.textState!!.text.toString() == body.replace("####### 更深标题", "更深标题") }, "Toolbar navigation never rewrites unrelated source")
        } catch (e: Exception) { screenshot("markdown-toolbar-failure"); throw e } finally {
            hideKeyboard()
            shell(if (oldKeyboard == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $oldKeyboard")
            if (main { model.ui.page == Page.Editor }) { main { model.back() }; ready() }
            main { model.delete(id) }; ready()
        }
    }

    private fun markdownVisualTest() {
        val oldFont = shell("settings get system font_scale").trim()
        val oldSize = shell("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
        val oldTheme = main { model.ui.workspace!!.theme }
        val oldLanguage = main { model.languages.selected }
        main { model.newDocument(Page.Recent) }; ready(); main { model.create(DocumentKind.Markdown) }; ready()
        val id = main { checkNotNull(model.ui.activeId) }
        try {
            click("源码"); setText("编辑正文", "# Writing & 阅读 😀\n\nA **bold** and *italic* paragraph with ~~removed~~ words.\n\n- [ ] A task\n- [x] Finished\n\n> A quote\n\n| A | B |\n| --- | --- |\n| Text | 中文 |\n\n```text\nplain code\n```\n")
            saved(); waitFor("visual parse") { main { model.markdownDocument?.source == model.textState?.text?.toString() } }
            main { model.preferences(theme = ColorTheme.Paper) }; ready()
            main { model.changeLanguage("en") }; ready()
            main { model.editorMode(EditorMode.Reading) }; ready(); screenshot("markdown-reading-en-light")
            verify(nodes().none { it.isEditable } && nodes().any { it.text?.toString() == "Reading" }, "English light-theme reading stays read-only")
            verify(nodes().any { it.isHeading }, "Reading exposes real accessibility heading semantics")
            verify(nodes().any { it.isCheckable && !it.isClickable }, "Reading task semantics expose state without a mutating click action")
            shell("wm size 840x1800"); shell("settings put system font_scale 2.0")
            waitFor("English narrow fonts", 30_000) { main { currentActivity!!.resources.configuration.screenWidthDp <= 321 && currentActivity!!.resources.configuration.fontScale >= 1.99f } }; ready()
            screenshot("markdown-reading-en-narrow-200")
            verify(listOf(R.string.source_view, R.string.typeset_view, R.string.reading_view).all { resource ->
                val label = main { model.languages.context().getString(resource) }; nodes().any { it.text?.toString() == label || it.contentDescription?.toString() == label }
            }, "English 320dp doubled fonts retain three accessible mode labels")
            main { model.editorMode(EditorMode.Typeset) }; ready(); click("Text"); screenshot("markdown-tools-en-narrow-200")
            val width = checkNotNull(uiAutomation.takeScreenshot()).let { val w = it.width; it.recycle(); w }
            verify(listOf("Heading", "Bold", "Italic", "Strikethrough", "Link").all { label ->
                val bounds = Rect(); find(label).getBoundsInScreen(bounds); bounds.left >= 0 && bounds.right <= width && !bounds.isEmpty
            }, "All text-group toolbar touch targets fit the 320dp window")
            shell("settings put system font_scale 1.0"); shell("wm size 1800x840")
            waitFor("landscape layout", 30_000) { main { currentActivity!!.resources.configuration.screenWidthDp > currentActivity!!.resources.configuration.screenHeightDp && currentActivity!!.resources.configuration.fontScale <= 1.01f } }; ready()
            main { model.editorMode(EditorMode.Reading) }; ready(); screenshot("markdown-reading-landscape")
            verify(nodes().none { it.isEditable }, "Landscape reading has no editable body")
        } catch (e: Exception) { screenshot("markdown-visual-failure"); throw e } finally {
            shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            ready(); main { model.changeLanguage(oldLanguage) }; ready()
            if (main { model.ui.page == Page.Editor }) { main { model.back() }; ready() }
            main { model.delete(id) }; ready(); main { model.preferences(theme = oldTheme) }; ready()
        }
    }

    private fun markdownTest() {
        val oldFont = shell("settings get system font_scale").trim()
        val oldSize = shell("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
        val oldTheme = main { model.ui.workspace!!.theme }
        var id: String? = null
        fun parsed() = waitFor("current Markdown parse") { main { model.markdownDocument?.source == model.textState?.text?.toString() } }
        fun group(label: String) {
            // Native selection may show the IME; the footer groups intentionally hide then.
            main {
                val activity = currentActivity!!
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            SystemClock.sleep(500); waitForIdleSync(); click(label)
        }
        fun sourceSelection(start: Int, end: Int = start) {
            main { model.editorMode(EditorMode.Source) }; SystemClock.sleep(300); waitForIdleSync()
            val labelled = find("编辑正文")
            fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isEditable) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { child -> editable(child)?.let { return it } }
                return null
            }
            val node = editable(labelled) ?: nodes().firstOrNull { it.isEditable } ?: error("Missing editable child for source selection")
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS); SystemClock.sleep(100); node.refresh()
            check(node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }))
            sendStatus(0, Bundle().apply { putString("stream", main { "Selection diagnostic: expected=$start..$end actual=${model.currentSelection()} editable=${node.isEditable} semantic=${node.textSelectionStart}..${node.textSelectionEnd} mode=${model.ui.editorMode}\n" }) })
            waitFor("source selection applied") { main { model.currentSelection() == TextSelection(start, end) } }
            main { model.editorMode(EditorMode.Typeset) }; SystemClock.sleep(300); waitForIdleSync()
        }
        try {
            click("新建文稿"); click("Markdown"); ready()
            id = main { checkNotNull(model.ui.activeId) }
            val sample = "# 写作标题 😀\n\n正文 **粗体** 与 *斜体*、~~删除~~和[链接](https://example.com)。\n\n- [ ] 任务\n  - 子项\n\n> 引用\n\n| A | B |\n| --- | --- |\n| 中文 | 😀 |\n\n```kotlin\nval x = 1\n```\n\n<div>原样保留</div>\n"
            click("源码"); setText("编辑正文", sample); saved(); parsed()
            verify(source(id, DocumentKind.Markdown) == sample, "Source input saves exact Unicode Markdown")
            click("排版"); verify(main { model.ui.editorMode == EditorMode.Typeset }, "Typeset is a continuous native editor")
            screenshot("markdown-typeset")
            click("阅读"); verify(nodes().none { it.isEditable }, "Reading exposes no editable text field")
            val revision = main { model.save!!.revision }
            verify(main { model.textState!!.text.toString() == sample }, "Reading uses latest memory text without rewriting source")
            screenshot("markdown-reading")
            click("排版"); click("源码"); click("阅读")
            verify(main { model.save!!.revision == revision } && source(id, DocumentKind.Markdown) == sample, "Three-view roundtrip creates no body revision or byte changes")
            click("排版"); click("文本")
            val position = sample.indexOf("正文")
            sourceSelection(position, position + 2)
            verify(main { model.currentSelection() == TextSelection(position, position + 2) }, "Typeset switch retains exact source selection for tools")
            click("粗体"); parsed(); saved()
            val bold = main { model.textState!!.text.toString() }
            if (bold != sample.replace("正文", "**正文**")) File(targetContext.cacheDir, "markdown-bold-diagnostic.txt").writeText(bold)
            verify(bold == sample.replace("正文", "**正文**"), "Typeset toolbar applies bold only to the selected source range")
            click("撤销"); saved(); verify(main { model.textState!!.text.toString() == sample }, "One undo restores formatting and original source")
            verify(main { model.currentSelection() == TextSelection(position, position + 2) }, "Undo restores the original selected range")
            click("重做"); saved(); verify(main { model.textState!!.text.toString() == bold }, "Redo restores the complete formatting operation")
            click("撤销"); parsed(); saved()
            sourceSelection(sample.indexOf("正文"), sample.indexOf("任务") + 2)
            verify(main { model.currentSelection().max > model.currentSelection().min + 30 }, "Native source and typeset share a cross-paragraph selection")
            sourceSelection(2)
            click("标题"); click("3 级标题"); parsed(); saved()
            verify(main { model.textState!!.text.toString().startsWith("### 写作标题") }, "Heading tool changes current heading level in typeset")
            click("撤销"); parsed(); saved()
            sourceSelection(sample.indexOf("引用"))
            group("段落"); click("引用"); parsed(); saved()
            verify(main { !model.textState!!.text.toString().contains("> 引用") }, "Quote tool removes one quote layer")
            click("撤销"); parsed(); saved()
            sourceSelection(position, position + 2)
            group("文本"); click("链接"); verify(nodes().any { it.text?.toString() == "链接名称" }, "Link dialog opens from typeset with retained selection")
            click("取消"); verify(main { model.textState!!.text.toString() == sample }, "Cancelling a link leaves source unchanged")
            val stale = main { model.editToken() }
            main { model.applyEditorEdit(EditResult(sample + "x", model.currentSelection())) }; parsed(); saved()
            verify(main { !model.applyLink(stale, "迟到", "https://example.com") && model.textState!!.text.toString() == sample + "x" }, "A delayed link command rejects its old source revision")
            click("源码"); setText("编辑正文", sample); saved(); parsed(); click("排版")
            runBlocking { withContext(Dispatchers.Main) { model.editorScroll.scrollTo(0) } }
            SystemClock.sleep(300); waitForIdleSync()
            click("切换任务完成状态"); saved(); parsed()
            verify(main { model.textState!!.text.toString() == sample.replace("- [ ] 任务", "- [x] 任务") }, "A typeset task checkbox changes only the source marker")
            click("撤销"); saved(); parsed()
            verify(main { model.textState!!.text.toString() == sample }, "A task toggle is one shared undo step")

            main { model.editorMode(EditorMode.Source) }; setText("编辑正文", "- item"); saved(); parsed()
            main { model.textState!!.edit { selection = androidx.compose.ui.text.TextRange(length) }; model.editorMode(EditorMode.Typeset) }
            find("编辑正文").performAction(AccessibilityNodeInfo.ACTION_FOCUS); waitForIdleSync()
            main {
                val connection = checkNotNull(currentActivity!!.window.decorView.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                check(connection.commitText("\n", 1))
            }
            saved(); parsed()
            verify(main { model.textState!!.text.toString() == "- item\n- " }, "Real InputConnection Enter continues a typeset list")
            click("撤销"); saved(); verify(main { model.textState!!.text.toString() == "- item" }, "Enter and its automatic marker undo together")
            parsed()
            main {
                val connection = checkNotNull(currentActivity!!.window.decorView.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                connection.setComposingText("zhong", 1)
                connection.setComposingText("中文", 1)
                connection.finishComposingText()
            }
            saved(); parsed()
            verify(main { model.textState!!.text.toString() == "- item中文" }, "Real composing InputConnection commits Chinese without duplicate or lost text")
            main {
                val connection = checkNotNull(currentActivity!!.window.decorView.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                connection.setComposingText("组", 1)
            }
            parsed(); click("粗体"); saved(); parsed()
            verify(main { model.textState!!.text.toString() == "- item中文组****" && model.textState!!.composition == null }, "Explicit formatting preserves visible composing text and finishes composition")
            main {
                val connection = checkNotNull(currentActivity!!.window.decorView.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                connection.setComposingText("读", 1)
            }
            val composingVisible = main { model.textState!!.text.toString() }
            click("阅读"); parsed(); saved()
            verify(main { model.textState!!.text.toString() == composingVisible } && nodes().none { it.isEditable }, "Switching to reading during composition keeps visible text and removes editing input")

            click("源码"); setText("编辑正文", sample); saved(); parsed(); click("排版")
            click("条目操作"); click("查找与替换")
            setText("查找内容", "正文"); waitForIdleSync()
            verify(nodes().any { it.text?.toString() == "1 / 1" }, "Find reports literal match count in typeset")
            setText("替换为", "新正文"); click("全部替换"); saved(); parsed()
            verify(main { model.textState!!.text.toString() == sample.replace("正文", "新正文") }, "Replace all edits current source atomically")
            click("关闭"); click("撤销"); saved(); parsed()
            click("阅读"); click("条目操作"); click("查找"); setText("查找内容", "粗体"); waitForIdleSync()
            verify(nodes().none { it.text?.toString() == "全部替换" || it.text?.toString() == "替换为" }, "Reading find has no replacement action")
            click("关闭")
            val readRevision = main { model.save!!.revision }
            main { model.undo(); model.format(MarkdownAction.Bold) }
            verify(main { model.save!!.revision == readRevision && model.textState!!.text.toString() == sample }, "Reading gates mutating editor commands as well as hiding tools")
            main { model.exportCopy() }; waitFor("Markdown export prepared") { main { !model.files.ui.busy && model.files.ui.export != null } }
            val export = main { checkNotNull(model.files.ui.export) }
            verify(export.name.endsWith(".md") && export.mime == "text/markdown" && export.file.readText() == sample, "Default Markdown export preserves source and MIME")
            main { model.files.close() }
            click("源码")
            val pending = File(workspaceDirectory(), "documents/$id/body.md.new")
            check(pending.mkdir())
            val unsaved = sample + "尚未保存的最新正文"
            try {
                setText("编辑正文", unsaved)
                waitFor("Markdown save failure") { main { model.save?.status?.value?.phase == SavePhase.Failed } }
                click("阅读"); parsed()
                verify(main { model.ui.editorMode == EditorMode.Reading && model.textState!!.text.toString() == unsaved && model.save!!.status.value.phase == SavePhase.Failed }, "Reading shows latest memory content and retains a save failure")
                verify(source(id, DocumentKind.Markdown) == sample, "Reading never replaces the last confirmed file after a save failure")
            } finally { check(pending.delete()) }
            main { model.retrySave() }; ready(); saved()
            click("源码"); setText("编辑正文", sample); saved(); parsed(); click("阅读")
            val retained = main { model.textState }
            main { currentActivity!!.recreate() }; ready(); parsed()
            verify(main { model.ui.editorMode == EditorMode.Reading && model.textState === retained }, "Activity recreation retains reading mode and shared editor session")
            shell("wm size 840x1800"); shell("settings put system font_scale 2.0")
            waitFor("narrow screen and actual doubled app fonts", 30_000) { main { currentActivity!!.resources.configuration.screenWidthDp <= 321 && currentActivity!!.resources.configuration.fontScale >= 1.99f } }
            ready(); SystemClock.sleep(500)
            screenshot("markdown-reading-narrow-200")
            verify(listOf("源码", "排版", "阅读").all { label -> nodes().any { it.text?.toString() == label } }, "320dp and 200 percent fonts keep all three mode controls")
            main { model.editorMode(EditorMode.Typeset) }; ready()
            waitFor("typeset mode after resize") { main { model.ui.editorMode == EditorMode.Typeset } }
            screenshot("markdown-typeset-narrow-200")
            main { model.editorMode(EditorMode.Reading); model.immersive = true }; waitForIdleSync(); screenshot("markdown-immersive")
            verify(nodes().none { it.isEditable || it.text?.toString() == "格式" }, "Immersive reading remains non-editable")
        } catch (e: Exception) { screenshot("markdown-before-cleanup-failure"); throw e } finally {
            main { model.files.close(); model.immersive = false }
            shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            ready()
            if (main { model.ui.page == Page.Editor }) { main { model.back() }; ready() }
            id?.let { main { model.delete(it) }; ready() }
            main { model.preferences(theme = oldTheme) }; ready()
        }
    }

    private fun exercise() {
        verify(main { model.ui.page == Page.Recent }, "Launch defaults to Recent")
        val defaultLocation = main { Workspace.ROOT }
        click("新建文稿"); click("Markdown"); ready()
        val id = main { checkNotNull(model.ui.activeId) }
        verify(main { model.ui.workspace?.entry(id)?.parent == defaultLocation }, "Recent new draft targets root")
        click("源码")
        val original = "# 模拟器写作\n\n第一段中文与 emoji 😀。\n\n## 明天\n\n保留段落  \n行尾\n\n- [ ] 未支持的语法\n\n"
        setText("编辑正文", original); saved()
        verify(source(id, DocumentKind.Markdown) == original, "Unicode source auto-save matches complete disk body")
        setText("编辑正文", original + "临时修改"); saved(); click("撤销"); saved()
        verify(source(id, DocumentKind.Markdown) == original, "Undo ignores auto-save and restores original")
        click("重做"); saved(); verify(source(id, DocumentKind.Markdown).endsWith("临时修改"), "Redo restores editing action")
        click("撤销"); saved(); click("排版")
        verify(main { model.textState?.text?.toString() == original }, "Switching views without edit preserves exact source")
        setText("编辑正文", original.replace("# 模拟器写作", "# 新标题"))
        click("返回"); ready()
        val edited = original.replace("# 模拟器写作", "# 新标题")
        verify(source(id, DocumentKind.Markdown) == edited, "Fast typeset edit then leave flushes last paragraph and preserves unknown syntax")
        main { model.open(id) }; ready(); click("源码")
        verify(main { model.textState?.text?.toString() == edited }, "Reopen returns complete saved source")
        click("排版"); setText("编辑正文", edited.replace("第一段中文与 emoji 😀。", "新段落\n\n新空行 😀"))
        click("源码")
        val secondEdit = edited.replace("第一段中文与 emoji 😀。", "新段落\n\n新空行 😀")
        verify(main { model.textState?.text?.toString() == secondEdit }, "Multiline typeset paragraph patch preserves separators and following blocks")
        click("撤销"); saved(); verify(source(id, DocumentKind.Markdown) == edited, "Shared undo crosses Markdown view switch")
        screenshot("editor")
        click("返回"); ready(); click("目录"); ready(); click("新建文件夹")
        val prefix = "模拟器验收-" + SystemClock.uptimeMillis()
        val nameField = nodes().firstOrNull { it.className == "android.widget.EditText" } ?: error("Missing name field")
        nameField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prefix) })
        click("创建"); ready()
        val folder = main { model.ui.selected }
        longPress(prefix, physical = true); verify(nodes().any { it.text?.toString() == "删除" }, "Real held touch opens folder menu with delete")
        screenshot("folder-menu"); click("重命名")
        nodes().first { it.className == "android.widget.EditText" }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prefix + "-改名") })
        click("重命名"); ready()
        verify(main { model.ui.workspace?.entry(folder)?.name == prefix + "-改名" }, "Folder rename retains stable ID")
        longPress(prefix + "-改名"); click("删除"); screenshot("delete-confirm"); click("取消")
        verify(main { model.ui.workspace?.entry(folder) != null }, "Cancel delete preserves folder")
        longPress(prefix + "-改名"); click("新建文稿"); click("纯文本"); ready()
        val plain = main { checkNotNull(model.ui.activeId) }
        verify(main { model.ui.workspace?.entry(plain)?.parent == folder }, "Folder menu creation uses selected location")
        setText("编辑正文", "原生纯文本\n\n自动保存与长按菜单。"); saved()
        click("返回"); ready()
        main { model.copy(plain, false) }; ready(); main { model.paste(folder) }; ready()
        val copied = main { model.ui.workspace?.entries?.single { it.parent == folder && it.id != plain && !it.folder }?.id ?: error("Missing copy") }
        verify(source(plain, DocumentKind.Plain) == source(copied, DocumentKind.Plain), "Copied draft has independent ID and identical bytes")
        main { model.open(copied) }; ready(); setText("编辑正文", "只修改副本"); saved(); click("返回"); ready()
        verify(source(plain, DocumentKind.Plain) != source(copied, DocumentKind.Plain), "Editing copy preserves original body")
        main { model.rename(copied, prefix + "-移动稿") }; ready()
        main { model.copy(copied, true) }; ready(); main { model.paste(defaultLocation) }; ready()
        verify(main { model.ui.workspace?.entry(copied)?.parent == defaultLocation && model.clipboard == null }, "Cut moves stable ID only after successful paste")
        // Only synthetic items created by this run are removed.
        longPress(prefix + "-改名"); click("删除"); find("删除文件夹？"); verify(nodes().any { it.text?.toString()?.contains("1 篇文稿") == true }, "Recursive delete confirms actual descendant document count")
        click("删除"); ready()
        verify(main { model.ui.workspace?.entry(folder) == null && model.ui.workspace?.entry(plain) == null && model.ui.workspace?.entry(copied) != null }, "Confirmed subtree deletion preserves moved peer")
        main { model.preferences(theme = ColorTheme.Ink) }; ready(); screenshot("directory-dark")
        main { model.go(Page.Recent) }; ready(); screenshot("recent-dark")
        val fixture = android.net.Uri.parse("content://io.github.xrisium.makudon.debug.test.fixtures/markdown")
        val raw = targetContext.contentResolver.openInputStream(fixture)?.use { it.readBytes() } ?: error("Fixture unavailable")
        main { model.import(fixture) }; waitFor("imported editor") { main { model.ui.page == Page.Editor && !model.ui.busy } }
        val imported = main { checkNotNull(model.ui.activeId) }
        val normalized = TextRules.decode(raw, imported = true)
        verify(source(imported, DocumentKind.Markdown) == normalized, "Import normalizes BOM/CRLF and publishes independent Markdown body")
        click("源码"); setText("编辑正文", normalized + "副本中的修改"); saved()
        verify(targetContext.contentResolver.openInputStream(fixture)?.use { it.readBytes() }?.contentEquals(raw) == true, "Editing imported draft leaves source URI bytes unchanged")
        val exported = main { model.prepareExport(); model.textState?.text?.toString() ?: error("Missing editor") }
        setText("编辑正文", exported + "导出后再改")
        val exportUri = android.net.Uri.parse("content://io.github.xrisium.makudon.debug.test.fixtures/export")
        main { model.export(exportUri) }; ready()
        verify(targetContext.contentResolver.openInputStream(exportUri)?.bufferedReader()?.use { it.readText() } == exported, "Export writes captured snapshot and does not switch to later editor contents")
        val protectedBody = source(id, DocumentKind.Markdown)
        val privateUri = android.net.Uri.fromFile(File(workspaceDirectory(), "documents/$id/body.md"))
        main { model.prepareExport(); model.export(privateUri) }; ready()
        verify(main { model.ui.error == Failure.Missing } && source(id, DocumentKind.Markdown) == protectedBody, "Export rejects file URI and cannot overwrite a private draft")
        main { model.dismissError() }
        val beforeInvalid = main { model.ui.workspace?.entries?.size }
        main { model.import(android.net.Uri.parse("content://io.github.xrisium.makudon.debug.test.fixtures/invalid")) }
        waitFor("invalid import error") { main { !model.ui.busy && model.ui.error != null } }
        verify(main { model.ui.error == Failure.InvalidText && model.ui.workspace?.entries?.size == beforeInvalid && model.ui.activeId == imported }, "Invalid UTF-8 import preserves current draft and catalog")
        main { model.dismissError() }; click("返回"); ready()
        val external = Intent(Intent.ACTION_VIEW).setDataAndType(fixture, "text/markdown").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        @Suppress("DEPRECATION")
        val candidates = targetContext.packageManager.queryIntentActivities(external, 0)
        verify(candidates.any { it.activityInfo.packageName == targetContext.packageName }, "Manifest registers compatible Markdown VIEW candidate")
        external.setClass(targetContext, MainActivity::class.java)
        main { targetContext.startActivity(external) }
        waitFor("external VIEW copy") { main { !model.ui.busy && model.ui.page == Page.Editor && model.ui.activeId != imported } }
        val externalId = main { checkNotNull(model.ui.activeId) }
        verify(source(externalId, DocumentKind.Markdown) == normalized, "Hot external VIEW imports a new independent copy")
        val beforeRecreate = main { model.ui.workspace?.entries?.size }
        main { currentActivity?.recreate() }; SystemClock.sleep(600); ready()
        verify(main { model.ui.workspace?.entries?.size == beforeRecreate && model.ui.activeId == externalId }, "Activity recreation does not replay external import or replace editor")
        click("返回"); ready()
        longDocumentTest()
    }
    private fun longDocumentTest() {
        main { model.newDocument(Page.Recent) }; ready()
        main { model.create(DocumentKind.Plain) }; ready()
        val single = "中".repeat(100_000)
        val start = SystemClock.uptimeMillis()
        setText("编辑正文", single); saved(60_000)
        sendStatus(0, Bundle().apply { putString("stream", "Single paragraph 100000 code points input+save elapsed_ms=${SystemClock.uptimeMillis() - start}\n") })
        val large = ("中".repeat(48) + "😀\n").repeat(2000)
        val largeId = main { checkNotNull(model.ui.activeId) }
        setText("编辑正文", large); saved(60_000)
        verify(source(largeId, DocumentKind.Plain) == large, "100000 Unicode code points save without truncation")
        setText("编辑正文", large + "超")
        verify(main { model.textState?.text?.toString() == large }, "Oversized paste is rejected in full")
        main { model.dismissError() }; setText("编辑正文", "模拟器长文边界已验证。\n\n本条为合成验收资料，可自行删除。"); saved()
        main { model.rename(largeId, "模拟器长文验收") }; ready(); click("返回"); ready()
        screenshot("recent-final")
        val proof = java.util.Properties().apply { setProperty("id", largeId); setProperty("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(source(largeId, DocumentKind.Plain).toByteArray()).joinToString("") { "%02x".format(it) }) }
        File(targetContext.cacheDir, "workspace-test-proof.properties").outputStream().use { proof.store(it, "synthetic restart proof") }
        // Keep the two explicitly synthetic test drafts available for the user to try; no pre-existing item is changed.
    }
}
