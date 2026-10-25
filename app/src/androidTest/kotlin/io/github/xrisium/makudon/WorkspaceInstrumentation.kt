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
    private var currentActivity: Activity? = null
    override fun callActivityOnCreate(activity: Activity, savedInstanceState: Bundle?) { super.callActivityOnCreate(activity, savedInstanceState); currentActivity = activity }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); onlyLong = arguments?.getString("case") == "long"; start() }
    override fun onStart() {
        try {
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            model = (targetContext.applicationContext as MakudonApplication).workspace
            ready()
            if (onlyLong) longDocumentTest() else exercise()
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPassed ${checks.size} checks:\n" + checks.joinToString("\n") + "\n") })
        } catch (e: Exception) {
            screenshot("failure")
            if (::model.isInitialized) sendStatus(0, Bundle().apply { putString("stream", main { "Diagnostics: phase=${model.save?.status?.value}, revision=${model.save?.revision}, saved=${model.save?.savedRevision}, bufferLength=${model.textState?.text?.length}, latestLength=${model.save?.latest?.length}, error=${model.ui.error}\n" }) })
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nFAILED after ${checks.size} checks: ${e.javaClass.simpleName}: ${e.message}\n" + e.stackTrace.take(5).joinToString("\n")); putString("failure", e.message) })
        }
    }
    private fun <T> main(block: () -> T): T { val result = AtomicReference<T>(); runOnMainSync { result.set(block()) }; return result.get() }
    private fun waitFor(label: String, timeoutMs: Long = 12_000, condition: () -> Boolean) {
        val start = SystemClock.uptimeMillis()
        while (!condition()) { if (SystemClock.uptimeMillis() - start > timeoutMs) error("Timed out: $label"); SystemClock.sleep(40) }
        waitForIdleSync()
    }
    private fun ready() { waitFor("workspace ready") { main { !model.ui.busy } }; SystemClock.sleep(350); waitForIdleSync() }
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
            while (!node.isLongClickable) node = node.parent ?: error("Missing long press action")
            check(node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
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

    private fun exercise() {
        verify(main { model.ui.page == Page.Recent }, "Launch defaults to Recent")
        click("新建文稿"); click("Markdown"); ready()
        val id = main { checkNotNull(model.ui.activeId) }
        verify(main { model.ui.workspace?.entry(id)?.parent == Workspace.INBOX }, "Recent new draft targets Inbox")
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
        main { model.copy(copied, true) }; ready(); main { model.paste(Workspace.INBOX) }; ready()
        verify(main { model.ui.workspace?.entry(copied)?.parent == Workspace.INBOX && model.clipboard == null }, "Cut moves stable ID only after successful paste")
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
        main { model.newDocument(Page.Recent); model.create(DocumentKind.Plain) }; ready()
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
