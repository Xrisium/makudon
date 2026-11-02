package io.github.xrisium.makudon

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Runs in the existing JVM/Check/CI task, without an Android emulator or translation service. */
class LocalizationResourcesTest {
    @Test fun shippedCatalogsMatchDeclaredLanguagesAndParameters() {
        val res = File("src/main/res")
        val declared = parseXml(File(res, "xml/locales_config.xml").readText()).getElementsByTagName("locale")
        val tags = (0 until declared.length).map { (declared.item(it) as Element).getAttribute("android:name") }
        assertEquals(tags.size, tags.toSet().size)
        assertTrue("English is the complete default", "en" in tags)
        val base = catalog(File(res, "values"))
        require(base.isNotEmpty())
        base.values.filter { it.type == "plurals" }.forEach { require(it.forms.keys.containsAll(setOf("one", "other"))) }
        val locales = res.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values-") }
            .mapNotNull { dir -> tag(dir.name)?.let { it to dir } }.toMap()
        assertEquals("Every translation must be declared and every declared language must be complete", tags.toSet() - "en", locales.keys)
        for ((language, dir) in locales) validate(base, catalog(dir), language)
        val debug = catalog(File("src/debug/res/values"))
        assertEquals(setOf("app_name"), debug.keys)
        assertEquals("Makudon Dev", debug.getValue("app_name").forms.getValue("value"))
    }

    @Test fun rejectsMissingAndObsoleteKeys() {
        invalid("<string name=\"a\">Hello</string>", "<string name=\"b\">你好</string>")
    }
    @Test fun rejectsChangedParameterTypeAndIndex() {
        invalid("<string name=\"a\">%1\$d selected</string>", "<string name=\"a\">%1\$s</string>")
        invalid("<string name=\"a\">%1\$d selected</string>", "<string name=\"a\">%2\$d</string>")
    }
    @Test fun rejectsUnnumberedOrDroppedParameters() {
        invalid("<string name=\"a\">%1\$s: %2\$d</string>", "<string name=\"a\">%s: %d</string>")
        invalid("<string name=\"a\">%1\$s: %2\$d</string>", "<string name=\"a\">%1\$s</string>")
    }
    @Test fun rejectsDuplicateKeysAndEmptyValues() {
        rejects { read("<string name=\"a\">A</string><string name=\"a\">B</string>") }
        rejects { read("<string name=\"a\"> </string>") }
    }
    @Test fun rejectsTranslatedInvariantAndChangedResourceType() {
        invalid("<string name=\"a\" translatable=\"false\">Makudon</string>", "<string name=\"a\">品牌</string>")
        invalid("<string name=\"a\">A</string>", "<plurals name=\"a\"><item quantity=\"other\">A</item></plurals>")
    }
    @Test fun checksEveryPluralBranchAndAllowsDifferentCategories() {
        val base = read("<plurals name=\"a\"><item quantity=\"one\">%1\$d item</item><item quantity=\"other\">%1\$d items</item></plurals>")
        validate(base, read("<plurals name=\"a\"><item quantity=\"other\">%1\$d 项</item></plurals>"), "zh-Hans")
        rejects { validate(base, read("<plurals name=\"a\"><item quantity=\"one\">Wrong</item><item quantity=\"other\">%1\$d 项</item></plurals>"), "en") }
        rejects { validate(base, read("<plurals name=\"a\"><item quantity=\"one\">%1\$d item</item></plurals>"), "en") }
    }
    @Test fun permitsReorderedParametersAndLiteralPercent() {
        validate(read("<string name=\"a\">%1\$s: %2\$d %%</string>"), read("<string name=\"a\">%2\$d %%: %1\$s</string>"), "zh-Hans")
    }

    private fun invalid(base: String, translation: String) = rejects { validate(read(base), read(translation), "zh-Hans") }
    private fun rejects(action: () -> Unit) { try { action(); fail("Expected invalid translation to fail") } catch (_: IllegalArgumentException) { } }
    private data class Message(val type: String, val translatable: Boolean, val forms: Map<String, String>)
    private fun read(body: String) = parseCatalog("<resources>$body</resources>")
    private fun catalog(dir: File): Map<String, Message> {
        val result = mutableMapOf<String, Message>()
        dir.listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
            parseCatalog(file.readText()).forEach { (key, message) -> require(result.put(key, message) == null) { "Duplicate $key in $dir" } }
        }
        return result
    }
    private fun parseXml(text: String) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement

    private fun parseCatalog(text: String): Map<String, Message> {
        val result = mutableMapOf<String, Message>()
        val root = parseXml(text)
        for (index in 0 until root.childNodes.length) {
            val entry = root.childNodes.item(index) as? Element ?: continue
            if (entry.tagName !in setOf("string", "plurals", "string-array")) continue
            val forms = mutableMapOf<String, String>()
            if (entry.tagName == "string") forms["value"] = entry.textContent
            else {
                val items = entry.getElementsByTagName("item")
                for (i in 0 until items.length) {
                    val item = items.item(i) as Element
                    val key = if (entry.tagName == "plurals") item.getAttribute("quantity") else i.toString()
                    require(forms.put(key, item.textContent) == null) { "Duplicate quantity $key" }
                }
            }
            require(forms.isNotEmpty() && forms.values.none { it.isBlank() }) { "Empty resource ${entry.getAttribute("name")}" }
            require(result.put(entry.getAttribute("name"), Message(entry.tagName, entry.getAttribute("translatable") != "false", forms)) == null) { "Duplicate key" }
        }
        return result
    }
    private fun validate(base: Map<String, Message>, translated: Map<String, Message>, tag: String) {
        val expected = base.filterValues { it.translatable }
        require(expected.keys == translated.keys) { "$tag missing=${expected.keys - translated.keys}, extra=${translated.keys - expected.keys}" }
        translated.forEach { (key, message) ->
            val source = expected.getValue(key)
            require(source.type == message.type) { "$tag $key changed resource type" }
            require(message.translatable) { "$tag $key hides a translation" }
            if (message.type == "plurals") {
                require("other" in message.forms) { "$tag $key needs other" }
                require(message.forms.keys.all { it in setOf("zero", "one", "two", "few", "many", "other") })
                if (tag.substringBefore('-') == "en") require("one" in message.forms)
            } else require(source.forms.keys == message.forms.keys)
            message.forms.forEach { (form, text) ->
                val reference = source.forms.getValue(if (source.type == "plurals") "other" else form)
                require(parameters(reference) == parameters(text)) { "$tag $key/$form changed parameters" }
            }
        }
    }
    private fun parameters(text: String): List<String> {
        val pattern = Regex("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?(?:[tT])?[a-zA-Z%]")
        require(!text.replace(pattern, "").contains('%')) { "Invalid format" }
        return pattern.findAll(text).mapNotNull { match ->
            if (match.value == "%%") null else {
                require(match.groupValues[1].isNotEmpty()) { "Unnumbered parameter" }
                match.groupValues[1] + match.value.takeLast(1)
            }
        }.sorted().toList()
    }
    private fun tag(directory: String): String? {
        val qualifier = directory.removePrefix("values-")
        return when {
            qualifier.startsWith("b+") -> qualifier.removePrefix("b+").replace('+', '-')
            qualifier.matches(Regex("[a-z]{2,3}(-r[A-Z]{2})?")) -> qualifier.replace("-r", "-")
            else -> null
        }
    }
}
