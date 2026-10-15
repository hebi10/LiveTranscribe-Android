package com.charles.livecaptionn.service

import com.charles.livecaptionn.overlay.MAX_CAPTION_MESSAGES
import com.charles.livecaptionn.overlay.MAX_OVERLAY_MESSAGES
import com.charles.livecaptionn.overlay.OverlayUiState
import com.charles.livecaptionn.overlay.overlayCaptionMessages
import com.charles.livecaptionn.settings.CaptionSettings

/**
 * Pure functions extracted from [CaptionForegroundService] for testability.
 *
 * These transforms drive the core captioning pipeline: recording incoming
 * transcript lines, pairing them with translations, and rendering the
 * scrolling overlay body.
 */

/** Bound completed captions and pending work independently during a translation backlog. */
internal const val MAX_PENDING_TRANSCRIPT_LINES = MAX_OVERLAY_MESSAGES
internal const val MAX_TRANSCRIPT_LINES = MAX_OVERLAY_MESSAGES + MAX_PENDING_TRANSCRIPT_LINES + 1

/** Untranslated arrivals must not evict the stable captions the user is reading. */
private fun trimTranscriptLines(lines: List<CaptionRuntimeLine>): List<CaptionRuntimeLine> {
    fun CaptionRuntimeLine.committed() = isFinal && finalTranslationCommitted && translatedText.isNotBlank()
    val retainedIds = mutableSetOf<Long>()
    lines.filter { it.committed() }.takeLast(MAX_OVERLAY_MESSAGES).forEach { retainedIds += it.id }
    lines.filter { it.isFinal && !it.committed() }.takeLast(MAX_PENDING_TRANSCRIPT_LINES)
        .forEach { retainedIds += it.id }
    lines.lastOrNull { !it.isFinal }?.let { retainedIds += it.id }
    return lines.filter { it.id in retainedIds }
}

/**
 * Record a new STT result into the transcript line list.
 *
 * Open/closed state is driven solely by [isFinal] (a natural or bounded speech
 * segment boundary), never by whether a translation has landed yet — a translation
 * can land mid-sentence (e.g. during a natural speech pause that triggers
 * the translate debounce but not Vosk's finalizer), and must not cause the
 * next partial of the same utterance to be appended as a duplicate line.
 *
 * @param lines        Current transcript lines (scroll-back buffer).
 * @param originalText The latest recognized text from STT.
 * @param isFinal      Whether this result is a finalized speech segment.
 * @param newLineId    Id to assign if this text starts/becomes a new version.
 * @return The updated line list, trimmed to [MAX_TRANSCRIPT_LINES].
 */
internal fun recordTranscriptResult(
    lines: List<CaptionRuntimeLine>,
    originalText: String,
    isFinal: Boolean,
    newLineId: Long
): List<CaptionRuntimeLine> {
    if (originalText.isBlank()) return if (isFinal) lines.filter { it.isFinal } else lines
    val last = lines.lastOrNull()
    val lastIsOpen = last != null && !last.isFinal

    if (lastIsOpen) {
        if (last!!.originalText == originalText && last.isFinal == isFinal) return lines
        val updatedLine = if (last.originalText == originalText) {
            // A provisional translation is never promoted to a permanent caption.
            // Keep this utterance's id, but wait for its own final translation.
            if (isFinal) last.copy(isFinal = true, translatedText = "", finalTranslationCommitted = false)
            else last
        } else {
            CaptionRuntimeLine(id = newLineId, originalText = originalText, isFinal = isFinal)
        }
        return trimTranscriptLines(lines.dropLast(1) + updatedLine)
    }

    return trimTranscriptLines(lines + CaptionRuntimeLine(id = newLineId, originalText = originalText, isFinal = isFinal))
}

/**
 * Pair a translated text with its matching transcript line by id. If the id
 * is no longer present (the line's text has since evolved past this
 * translation's snapshot), the result is considered stale and discarded
 * (prevents orphan/stale translations from causing double captions).
 *
 * @param lines          Current transcript lines.
 * @param lineId         Id of the line this translation was requested for.
 * @param translatedText The translation result to attach.
 * @param isFinalTranslation Whether this translation was requested for finalized speech.
 * @return Updated lines with the translation filled in (or unchanged if no match).
 */
internal fun updateTranscriptTranslation(
    lines: List<CaptionRuntimeLine>,
    lineId: Long,
    translatedText: String,
    isFinalTranslation: Boolean
): List<CaptionRuntimeLine> {
    if (translatedText.isBlank()) return lines
    val existingIndex = lines.indexOfFirst { it.id == lineId }
    if (existingIndex < 0) return lines
    val existing = lines[existingIndex]
    // A partial already in flight can finish after STT finalizes the same id.
    // Committed finals are immutable even if another completion arrives later.
    if (existing.isFinal != isFinalTranslation || existing.finalTranslationCommitted) return lines
    return trimTranscriptLines(lines.mapIndexed { index, line ->
        if (index == existingIndex) line.copy(
            translatedText = translatedText,
            finalTranslationCommitted = isFinalTranslation
        ) else line
    })
}

/**
 * Update the legacy current-translation value from ordered, matching lines.
 * Older completions and rejected partials cannot regress it to stale text.
 */
internal fun applyTranscriptTranslation(
    runtime: CaptionRuntimeState,
    lineId: Long,
    translatedText: String,
    isFinalTranslation: Boolean
): CaptionRuntimeState {
    val updated = updateTranscriptTranslation(runtime.transcriptLines, lineId, translatedText, isFinalTranslation)
    if (updated === runtime.transcriptLines) return runtime
    return runtime.copy(
        transcriptLines = updated,
        translatedText = updated.lastOrNull { it.translatedText.isNotBlank() }?.translatedText.orEmpty()
    )
}

/** Compact captions retain the last two visible, paired translations. */
internal fun overlayTranscriptText(runtime: CaptionRuntimeState, stableCaptions: Boolean = true): String =
    overlayCaptionMessages(runtime, stableCaptions).takeLast(MAX_CAPTION_MESSAGES)
        .joinToString("\n") { it.translatedText }

/** Finals never wait for or depend on the partial-preview preference. */
internal fun shouldTranslateCaption(isFinal: Boolean, stableCaptions: Boolean): Boolean = isFinal || !stableCaptions

/** Build source and translated display text from the same eligible snapshots. */
internal fun buildOverlayUiState(runtime: CaptionRuntimeState, settings: CaptionSettings): OverlayUiState {
    val messages = overlayCaptionMessages(runtime, settings.stableCaptions)
    val captions = messages.takeLast(MAX_CAPTION_MESSAGES)
    return OverlayUiState(
        originalText = captions.joinToString("\n") { it.originalText },
        translatedText = captions.lastOrNull()?.translatedText.orEmpty(),
        transcriptText = captions.joinToString("\n") { it.translatedText },
        messages = messages,
        displayMode = settings.captionDisplayMode,
        stableCaptions = settings.stableCaptions,
        controlsExpanded = settings.overlayControlsExpanded,
        status = runtime.status,
        phase = runtime.phase,
        stopping = runtime.stopping,
        statusDetail = runtime.actionableNotice,
        information = runtime.information,
        skippedTranslations = runtime.skippedTranslations,
        skippedSpeechSegments = runtime.skippedSpeechSegments,
        textSizeSp = settings.textSizeSp,
        opacity = settings.overlayOpacity,
        showOriginal = settings.showOriginal,
        minimized = settings.overlayMinimized,
        suggestedLanguageCode = runtime.suggestedLanguageCode,
        themeId = settings.overlayThemeId,
        fontId = settings.overlayFontId
    )
}


/** Record speech and keep legacy values paired with the retained snapshots. */
internal fun applyTranscriptResult(
    runtime: CaptionRuntimeState,
    text: String,
    isFinal: Boolean,
    newLineId: Long
): CaptionRuntimeState {
    val lines = recordTranscriptResult(runtime.transcriptLines, text, isFinal, newLineId)
    val recorded = runtime.copy(
        originalText = if (text.isBlank()) lines.lastOrNull()?.originalText.orEmpty() else text,
        translatedText = lines.lastOrNull { it.translatedText.isNotBlank() }?.translatedText.orEmpty(),
        transcriptLines = lines
    )
    // Empty boundaries only remove previews. A terminal tail must not erase
    // the capture failure that the engine reported immediately before it.
    // Error text arrives before Main observes ERROR; only the capture error sink
    // may clear that text after capture resumes successfully.
    return recorded
}
