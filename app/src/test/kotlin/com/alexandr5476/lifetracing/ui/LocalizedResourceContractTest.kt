package com.alexandr5476.lifetracing.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.w3c.dom.Element
import java.nio.file.Path
import java.util.Formattable
import java.util.IllegalFormatException
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class LocalizedResourceContractTest {
    @Test
    fun productionEnglishAndRussianResourcesHaveCompatibleNamesTypesAndArguments() {
        assertCompatible(productionResources("values"), productionResources("values-ru"))
    }

    @Test
    fun missingExplicitRussianKeyCannotBeHiddenByFallback() {
        val english = productionResources("values")
        val mutatedRussian = productionResources("values-ru") - "settings_retry"
        val error = assertThrows<AssertionError> { assertCompatible(english, mutatedRussian) }
        assertTrue(error.message.orEmpty().contains("Missing in Russian: [settings_retry]"), error.message)
    }

    @Test
    fun incompatibleRussianPluralArgumentNamesTheFormAndBothSignatures() {
        val english = productionResources("values")
        val russian = productionResources("values-ru")
        val plurals = russian.getValue("archived_templates_count")
        val mutated = russian + (plurals.name to plurals.copy(forms = plurals.forms + ("few" to "%s шаблона")))
        val error = assertThrows<AssertionError> { assertCompatible(english, mutated) }
        assertTrue(
            error.message.orEmpty().contains("archived_templates_count: EN[other]=[1:d] vs RU[few]=[1:s]"),
            error.message,
        )
    }

    @Test
    fun formatterArgumentOrderFollowsJavaIndexingAndIgnoresLiteralTokens() {
        assertEquals(signature("%s %d"), signature("%2\$d %% %1\$s%n"))
        assertEquals(listOf("1:s", "2:d"), signature("%s %d", formatted = false))
        assertEquals(listOf("1:s", "1:s", "2:d"), signature("%2\$d %s %<s"))
        assertEquals(listOf("1:tH", "1:tm"), signature("%1\$tH %<tm"))
        assertEquals(emptyList<String>(), signature("100% complete"))
        assertEquals(emptyList<String>(), signature("Complete at 100%."))
        assertEquals(emptyList<String>(), signature("%% %n"))
        assertThrows<IllegalArgumentException> { signature("%s %") }
        assertThrows<IllegalArgumentException> { signature("%<s") }
        assertThrows<IllegalArgumentException> { signature("%1\$q") }
    }

    @Test
    fun dailyAndLiveSequenceDurationLabelsHandleSingularValues() {
        val english = productionResources("values")
        val minutes = english.getValue("daily_duration_minutes").forms.getValue("text")
        val seconds = english.getValue("daily_duration_seconds").forms.getValue("text")
        assertEquals("Hours: 1, minutes: 1", String.format(Locale.US, minutes, 1L, 1L))
        assertEquals("Hours: 1, minutes: 1, seconds: 1", String.format(Locale.US, seconds, 1L, 1L, 1L))
    }

    @Test
    fun russianEditorAndHistoryUseLocalizedDomainTerms() {
        val russian = productionResources("values-ru")
        assertEquals("Шаблон действия сохранён.", russian.getValue("activity_editor_committed").forms.getValue("text"))
        val history = russian.getValue("sequence_history_execution_id").forms.getValue("text")
        assertEquals(
            "ID выполнения последовательности: example",
            String.format(Locale.forLanguageTag("ru"), history, "example"),
        )
    }
}

private val formatToken =
    Regex(
        """%(?:(?<index>\d+)\$)?(?<flags>[-#+ 0,(<]*)(?<width>\d+)?""" +
            """(?:\.(?<precision>\d+))?(?<date>[tT])?(?<type>[a-zA-Z%])""",
    )
private val resourceReference = Regex("""@(?:[\w.]+:)?[\w.]+/[\w.]+""")

private data class LocalizedResource(
    val name: String,
    val type: String,
    val forms: Map<String, String>,
    val formatted: Boolean,
)

private fun productionResources(directory: String): Map<String, LocalizedResource> {
    val root = Path.of(System.getProperty("lifetracing.productionResources"))
    val factory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
    val document = factory.newDocumentBuilder().parse(root.resolve("$directory/strings.xml").toFile())
    val resources = document.documentElement
    check(resources.tagName == "resources") { "$directory/strings.xml must have a <resources> root" }
    return buildMap {
        for (element in resources.childElements()) {
            val name = element.getAttribute("name")
            check(name.isNotBlank()) { "$directory/strings.xml: unnamed <${element.tagName}>" }
            val type = if (element.tagName == "item") element.getAttribute("type") else element.tagName
            val forms =
                if (type == "plurals") {
                    pluralForms(element, "$directory/$name")
                } else {
                    mapOf("text" to element.textContent)
                }
            val entry = LocalizedResource(name, type, forms, element.getAttribute("formatted") != "false")
            check(put(name, entry) == null) { "$directory/strings.xml: duplicate resource $name" }
        }
    }
}

private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

private fun pluralForms(
    element: Element,
    location: String,
): Map<String, String> =
    buildMap {
        for (item in element.childElements()) {
            check(item.tagName == "item") { "$location: expected <item>, found <${item.tagName}>" }
            val quantity = item.getAttribute("quantity")
            check(put(quantity, item.textContent) == null) { "$location: duplicate plural quantity '$quantity'" }
        }
    }

private fun assertCompatible(
    english: Map<String, LocalizedResource>,
    russian: Map<String, LocalizedResource>,
) {
    val failures = mutableListOf<String>()
    (english.keys - russian.keys).sorted().takeIf { it.isNotEmpty() }?.let { failures += "Missing in Russian: $it" }
    (russian.keys - english.keys).sorted().takeIf { it.isNotEmpty() }?.let { failures += "Missing in English: $it" }
    for (name in (english.keys intersect russian.keys).sorted()) {
        val en = english.getValue(name)
        val ru = russian.getValue(name)
        when {
            en.type != ru.type -> failures += "$name: EN type ${en.type} vs RU type ${ru.type}"
            en.type == "string" -> {
                val source = resourceSignature(english, en, "text", "EN")
                val translation = resourceSignature(russian, ru, "text", "RU")
                if (source != translation) failures += "$name: EN=$source vs RU=$translation"
            }
            en.type == "plurals" -> failures += pluralFailures(english, russian, en, ru)
            else -> failures += "$name: unsupported resource type ${en.type} in strings.xml"
        }
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
}

private fun pluralFailures(
    english: Map<String, LocalizedResource>,
    russian: Map<String, LocalizedResource>,
    en: LocalizedResource,
    ru: LocalizedResource,
): List<String> =
    buildList {
        val expectedEnglish = setOf("one", "other")
        val expectedRussian = setOf("one", "few", "many", "other")
        if (en.forms.keys != expectedEnglish) {
            add("${en.name}: EN quantities ${en.forms.keys}, expected $expectedEnglish")
        }
        if (ru.forms.keys != expectedRussian) {
            add("${ru.name}: RU quantities ${ru.forms.keys}, expected $expectedRussian")
        }
        if ("other" in en.forms) {
            val expected = resourceSignature(english, en, "other", "EN")
            for ((locale, resources, entry) in listOf(Triple("EN", english, en), Triple("RU", russian, ru))) {
                for (quantity in entry.forms.keys) {
                    val actual = resourceSignature(resources, entry, quantity, locale)
                    if (actual != expected) add("${en.name}: EN[other]=$expected vs $locale[$quantity]=$actual")
                }
            }
        }
    }

private fun resourceSignature(
    resources: Map<String, LocalizedResource>,
    entry: LocalizedResource,
    form: String,
    locale: String,
    visited: Set<String> = emptySet(),
): List<String> {
    val value = entry.forms.getValue(form).trim()
    val location = "$locale ${entry.name}[$form]"
    require(entry.name !in visited) { "$location: cyclic string reference through $visited" }
    val isReference = resourceReference.matches(value)
    return when {
        isReference && value.startsWith("@string/") -> {
            val target = resources[value.removePrefix("@string/")]
            require(target?.type == "string") { "$location: missing local string target $value" }
            resourceSignature(resources, target, "text", locale, visited + entry.name)
        }
        // A framework or dependency reference has no locally inspectable format.
        isReference -> listOf(value)
        else ->
            try {
                signature(value, entry.formatted)
            } catch (error: IllegalArgumentException) {
                throw AssertionError("$location: ${error.message} in '$value'", error)
            }
    }
}

private fun signature(
    value: String,
    formatted: Boolean = true,
): List<String> {
    val arguments = mutableListOf<String>()
    val rawPercents = mutableListOf<Int>()
    var hasFormatToken = false
    var ordinaryIndex = 0
    var previousIndex = 0
    var cursor = value.indexOf('%')
    while (cursor >= 0) {
        val token = formatToken.matchAt(value, cursor)?.takeIf { it.isValidFormatToken() }
        if (token == null) {
            rawPercents += cursor
            cursor = value.indexOf('%', cursor + 1)
            continue
        }
        cursor = value.indexOf('%', token.range.last + 1)
        hasFormatToken = true
        if (token.consumesArgument()) {
            val index = token.group("index")
            val argumentIndex =
                when {
                    '<' in token.group("flags") -> previousIndex
                    index.isNotEmpty() -> index.toInt()
                    else -> ++ordinaryIndex
                }
            require(argumentIndex > 0) { "invalid argument index in '${token.value}'" }
            val conversion = token.group("type")
            val type = if (token.group("date").isEmpty()) conversion.lowercase() else "t$conversion"
            arguments += "$argumentIndex:$type"
            previousIndex = argumentIndex
        }
    }
    // AAPT's formatted=false relaxes source checks, but Android still formats when given arguments.
    val invalid =
        rawPercents.firstOrNull {
            val looksMalformed = formatted && value.getOrNull(it + 1)?.let(::looksLikeFormat) == true
            hasFormatToken || looksMalformed
        }
    require(invalid == null) { "invalid format token at character $invalid" }
    return arguments.sorted()
}

private fun looksLikeFormat(next: Char): Boolean = next.isLetterOrDigit() || next in "\$<"

private fun MatchResult.group(name: String): String = groups[name]?.value.orEmpty()

private fun MatchResult.consumesArgument(): Boolean = group("date").isNotEmpty() || group("type") !in setOf("%", "n")

private fun MatchResult.isValidFormatToken(): Boolean {
    val index = group("index")
    val flags = group("flags")
    val invalidIndex = index.isNotEmpty() && (index.toIntOrNull() ?: 0) <= 0
    val relativeCount = flags.count { it == '<' }
    val invalidRelative = relativeCount > if (consumesArgument()) 1 else 0
    if (invalidIndex || invalidRelative) return false

    val datePrefix = group("date")
    val conversion = group("type")
    val argument: Any =
        when {
            datePrefix.isNotEmpty() -> 0L
            conversion.lowercase() in listOf("a", "e", "f", "g") -> 0.0
            conversion.equals("s", ignoreCase = true) -> Formattable { _, _, _, _ -> }
            else -> 0
        }
    // Validate Java's syntax without allocating padding or an array sized by the explicit index.
    val probeWidth = if (group("width").isEmpty()) "" else "1"
    val probePrecision = if (group("precision").isEmpty()) "" else ".0"
    val probe = "%${flags.replace("<", "")}$probeWidth$probePrecision$datePrefix$conversion"
    return try {
        String.format(Locale.ROOT, probe, argument)
        true
    } catch (_: IllegalFormatException) {
        false
    }
}
