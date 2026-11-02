package io.github.xrisium.makudon.domain

import java.util.Locale

/** Operation-scoped naming material. Numeric fallback is for non-UI callers. */
data class NameSuffix(val first: String, val numbered: String) {
    init {
        require(numbered.count { it == '%' } == 1 && numbered.contains("%1\$d"))
        require(first.isNotBlank() && first != String.format(Locale.ROOT, numbered, 2))
        listOf(first, String.format(Locale.ROOT, numbered, 20_000)).forEach {
            require(it.length <= 60)
            TextRules.checkName("x$it")
        }
    }
    fun suffix(number: Int): String = if (number == 1) first else String.format(Locale.ROOT, numbered, number)
    fun append(name: String, number: Int): String {
        val suffix = suffix(number)
        return (safeNamePrefix(name, 120 - suffix.length) + suffix).also(TextRules::checkName)
    }
    companion object { val Numeric = NameSuffix(" (1)", " (%1\$d)") }
}
