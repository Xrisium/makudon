package io.github.xrisium.makudon

import io.github.xrisium.makudon.data.*
import io.github.xrisium.makudon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class LocalizationBehaviorTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun languagePreferenceSurvivesNewReaderAndFailedWritePreservesOldValue() {
        val file = File(temp.root, "preferences/language")
        val preference = LanguagePreference(file)
        assertEquals("", preference.read())
        preference.write("zh-Hans")
        assertEquals("zh-Hans", LanguagePreference(file).read())
        val broken = LanguagePreference(file, object : AtomicDisk() {
            override fun write(file: File, bytes: ByteArray) { throw WorkspaceException(Failure.Storage) }
        })
        try { broken.write("en"); fail() } catch (_: WorkspaceException) { }
        assertEquals("zh-Hans", LanguagePreference(file).read())
        preference.write("")
        assertEquals("", LanguagePreference(file).read())
    }

    @Test fun localizedCopiesPreserveOriginalNamesAndKeepBodiesIndependent() {
        val store = WorkspaceStore(temp.newFolder())
        store.load()
        val chinese = NameSuffix("（副本）", "（副本 %1\$d）")
        val english = NameSuffix(" (copy)", " (copy %1\$d)")
        val original = store.create("未命名文稿", Workspace.ROOT, DocumentKind.Plain, "正文")
        store.pasteAll(store.captureAll(listOf(original)), Workspace.ROOT, chinese)
        store.pasteAll(store.captureAll(listOf(original)), Workspace.ROOT, english)
        store.pasteAll(store.captureAll(listOf(original)), Workspace.ROOT, english)
        val entries = store.refresh().entries.filter { !it.folder }
        assertEquals(setOf("未命名文稿", "未命名文稿（副本）", "未命名文稿 (copy)", "未命名文稿 (copy 2)"), entries.map { it.name }.toSet())
        assertEquals(4, entries.map { it.id }.distinct().size)
        store.save(entries.single { it.name.endsWith("(copy)") }.id, "edited")
        assertEquals("正文", store.read(original))
    }

    @Test fun suffixProtectsUnicodeBoundaryAndRejectsUnsafeTranslation() {
        val result = NameSuffix(" (copy)", " (copy %1\$d)").append("😀".repeat(60), 2)
        assertTrue(result.length <= 120)
        TextRules.bytes(result)
        try { NameSuffix("/copy", "copy %1\$d"); fail() } catch (_: WorkspaceException) { }
        try { NameSuffix("copy", "copy"); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun localeChangesDisplayOrderingWithoutChangingConflictRules() {
        val entries = listOf("z", "ä", "file10", "file2").map { Entry(it, it, Workspace.ROOT, DocumentKind.Plain, 0) }
        val workspace = Workspace(entries)
        val german = workspace.sorted(entries, Locale.GERMAN).map { it.name }
        val swedish = workspace.sorted(entries, Locale.forLanguageTag("sv")).map { it.name }
        assertTrue(german.indexOf("ä") < german.indexOf("z"))
        assertTrue(swedish.indexOf("ä") > swedish.indexOf("z"))
        assertTrue(german.indexOf("file2") < german.indexOf("file10"))
        assertEquals(entries, workspace.entries)
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertTrue(TextRules.conflicts(listOf(entries[0].copy(name = "FILE")), "file", Workspace.ROOT, DocumentKind.Plain))
        } finally { Locale.setDefault(previous) }
    }
}
