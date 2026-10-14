package com.charles.livecaptionn.ui.l10n

import java.util.Locale

/**
 * A runtime-localized string table for the whole app UI.
 *
 * Each map key is the canonical English source string; when a key has no
 * translation yet the English text is returned, so the UI degrades gracefully
 * while ML Kit downloads a model / translates in the background — the UI never
 * renders blank or half-localized text.
 */
class UiStrings(private val map: Map<String, String> = emptyMap()) {

    operator fun get(key: String): String = map[key] ?: key

    val isLocalized: Boolean get() = map.isNotEmpty()

    /** Translator for map values with an empty-translation guard. */
    fun translated(key: String): String? = map[key]

    /**
     * Localizes an English format template while preserving its placeholders.
     * Falls back to the English template if the translation is missing or
     * dropped/mangled the %-specifier (which would otherwise crash or corrupt
     * String.format output).
     */
    fun format(template: String, vararg args: Any?): String {
        val localized = map[template] ?: template
        if (localized != template &&
            UiStringCatalog.placeholderCount(localized) != UiStringCatalog.placeholderCount(template)
        ) {
            return typedFormat(template, args) ?: template
        }
        return typedFormat(localized, args) ?: typedFormat(template, args) ?: template
    }

    private fun typedFormat(template: String, args: Array<out Any?>): String? = try {
        String.format(Locale.getDefault(), template, *args)
    } catch (_: Throwable) {
        null
    }

    companion object {
        val EMPTY = UiStrings(emptyMap())
    }
}

/** Source keys still used by the local UI and overlay. No remote localization. */
object UiStringCatalog {
    const val appName = "실시간 영상 번역"
    val ALL: List<String> = listOf(
        appName,
        "Transcript History",
        "No transcripts yet",
        "Search transcripts…",
        "Pause captioning",
        "Resume captioning",
        "Minimize overlay",
        "Close overlay",
        "Expand controls",
        "Collapse controls",
        "Restore overlay",
        "Switch to chat view",
        "Switch to caption view",
        "Show original text",
        "Hide original text",
        "Latest captions",
        "Captions appear in short speech segments",
        "Move caption window",
        "Resize caption window",
        "Enlarge caption window",
        "Shrink caption window",
        "Listening",
        "Stop",
        "Status"
    )

    private val PLACEHOLDER = Regex("%\\d*[_a-zA-Z%]")
    fun placeholderCount(text: String): Int = PLACEHOLDER.findAll(text).count()

    fun isSegmentTranslatable(segment: String): Boolean {
        val trimmed = segment.trim()
        return trimmed.length >= 2 && trimmed.any { it.isLetter() }
    }
}

