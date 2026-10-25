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

/** Small platform-only runner: real accessibility actions and private synthetic data, no product debug controls. */
class WorkspaceInstrumentation : Instrumentation() {
    private lateinit var model: WorkspaceViewModel
    private val checks = mutableListOf<String>()
    private var onlyLong = false
    private var onlyMotion = false
    private var onlyVisual = false
    private var onlyTabs = false
    private var onlyAppearance = false
    private var currentActivity: Activity? = null
    override fun callActivityOnCreate(activity: Activity, savedInstanceState: Bundle?) { super.callActivityOnCreate(activity, savedInstanceState); currentActivity = activity }
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        onlyLong = arguments?.getString("case") == "long"
        onlyMotion = arguments?.getString("case") == "motion"
        onlyVisual = arguments?.getString("case") == "motion-visual"
        onlyTabs = arguments?.getString("case") == "tabs"
        onlyAppearance = arguments?.getString("case") == "appearance"
        start()
    }
    override fun onStart() {
        try {
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            model = (targetContext.applicationContext as MakudonApplication).workspace
            ready()
            when { onlyAppearance -> appearanceTest(); onlyTabs -> tabTest(); onlyVisual -> motionVisualTest(); onlyMotion -> motionTest(); onlyLong -> longDocumentTest(); else -> exercise() }
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
        val root = uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { result += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        visit(root); return result
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
    private fun screenshot(name: String) { uiAutomation.takeScreenshot()?.let { bitmap -> File(targetContext.cacheDir, "workspace-test-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() } }
    private fun saved(timeoutMs: Long = 12_000) = waitFor("current revision saved", timeoutMs) { main { model.save?.status?.value?.phase == SavePhase.Saved && model.save?.latest == model.textState?.text?.toString() } }
    private fun source(id: String, kind: DocumentKind) = File(targetContext.filesDir, "workspace/documents/$id/body.${kind.extension}").readText(Charsets.UTF_8)

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

    private fun motionTest() {
        val oldTheme = main { checkNotNull(model.ui.workspace).theme }
        val oldScale = shell("settings get global animator_duration_scale")
        val catalog = File(targetContext.filesDir, "workspace/catalog.properties")
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
            val pending = File(targetContext.filesDir, "workspace/documents/$id/body.txt.new")
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

            main { model.previous() }; ready()
            verify(main { model.ui.page == Page.Previous }, "Previous version uses secondary navigation")
            click("返回"); ready()
            verify(main { model.ui.page == Page.Editor && model.textState === sessionField }, "Reverse previous-version transition retains the original editor field")
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
        val licenses = targetContext.getString(R.string.licenses)
        val paper = targetContext.getString(R.string.theme_paper)
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
                    verify(enabled(licenses) && enabled(paper), "Long press keeps license and theme controls enabled")
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
        val paper = targetContext.getString(R.string.theme_paper)
        val cream = targetContext.getString(R.string.theme_cream)
        val licenses = targetContext.getString(R.string.licenses)
        val catalog = File(targetContext.filesDir, "workspace/catalog.properties")
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

    private fun exercise() {
        verify(main { model.ui.page == Page.Recent }, "Launch defaults to Recent")
        val defaultLocation = main { if (model.ui.workspace?.entry(Workspace.INBOX) != null) Workspace.INBOX else Workspace.ROOT }
        click("新建文稿"); click("Markdown"); ready()
        val id = main { checkNotNull(model.ui.activeId) }
        verify(main { model.ui.workspace?.entry(id)?.parent == defaultLocation }, "Recent new draft targets Inbox or explicit root fallback")
        click("源码")
        val original = "# 模拟器写作\n\n第一段中文与 emoji 😀。\n\n## 明天\n\n保留段落  \n行尾\n\n- [ ] 未支持的语法\n\n"
        setText("编辑正文", original); saved()
        verify(source(id, DocumentKind.Markdown) == original, "Unicode source auto-save matches complete disk body")
        setText("编辑正文", original + "临时修改"); saved(); click("撤销"); saved()
        verify(source(id, DocumentKind.Markdown) == original, "Undo ignores auto-save and restores original")
        click("重做"); saved(); verify(source(id, DocumentKind.Markdown).endsWith("临时修改"), "Redo restores editing action")
        click("撤销"); saved(); click("排版")
        verify(main { model.textState?.text?.toString() == original }, "Switching views without edit preserves exact source")
        setText("排版编辑第 1 段", "新标题")
        click("返回"); ready()
        val edited = original.replace("# 模拟器写作", "# 新标题")
        verify(source(id, DocumentKind.Markdown) == edited, "Fast typeset edit then leave flushes last paragraph and preserves unknown syntax")
        main { model.open(id) }; ready(); click("源码")
        verify(main { model.textState?.text?.toString() == edited }, "Reopen returns complete saved source")
        click("排版"); setText("排版编辑第 2 段", "新段落\n\n新空行 😀")
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
        val privateUri = android.net.Uri.fromFile(File(targetContext.filesDir, "workspace/documents/$id/body.md"))
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
