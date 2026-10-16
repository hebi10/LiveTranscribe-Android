package com.charles.livecaptionn.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.settings.RecognitionLanguages
import com.charles.livecaptionn.settings.CaptionPlacement
import com.charles.livecaptionn.settings.CaptionDisplayMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.captionDataStore by preferencesDataStore(name = "caption_settings")

class SettingsDataStore(private val context: Context) : SettingsRepository {
    override val settingsFlow: Flow<CaptionSettings> =
        context.captionDataStore.data.map(::readSettings)

    override suspend fun update(transform: (CaptionSettings) -> CaptionSettings) {
        context.captionDataStore.edit { p -> updatePreferences(p, transform) }
    }

    internal companion object {
        internal fun readSettings(p: Preferences): CaptionSettings {
            val defaults = CaptionSettings()
            return CaptionSettings(
                sourceLanguageCode = RecognitionLanguages.normalizeMode(p[SOURCE]),
                targetLanguageCode = p[TARGET] ?: defaults.targetLanguageCode,
                textSizeSp = (p[TEXT_SIZE] ?: defaults.textSizeSp).coerceIn(14f, 40f),
                overlayOpacity = (p[OPACITY] ?: defaults.overlayOpacity).coerceIn(0.2f, 1f),
                showOriginal = p[SHOW_ORIGINAL] ?: defaults.showOriginal,
                captionDisplayMode = CaptionDisplayMode.fromName(p[DISPLAY_MODE] ?: defaults.captionDisplayMode.name),
                stableCaptions = p[STABLE_CAPTIONS] ?: defaults.stableCaptions,
                overlayControlsExpanded = p[CONTROLS_EXPANDED] ?: defaults.overlayControlsExpanded,
                overlayX = p[OVERLAY_X] ?: defaults.overlayX,
                overlayY = p[OVERLAY_Y] ?: defaults.overlayY,
                overlayMinimized = p[OVERLAY_MIN] ?: defaults.overlayMinimized,
                audioSource = AudioSource.fromName(p[AUDIO_SOURCE] ?: defaults.audioSource.name),
                overlayWidthDp = p[OVERLAY_W] ?: defaults.overlayWidthDp,
                overlayHeightDp = p[OVERLAY_H] ?: defaults.overlayHeightDp,
                overlayThemeId = p[OVERLAY_THEME] ?: defaults.overlayThemeId,
                overlayFontId = p[OVERLAY_FONT] ?: defaults.overlayFontId,
                saveHistory = p[SAVE_HISTORY] ?: defaults.saveHistory,
                captionPlacement = CaptionPlacement.fromName(p[PLACEMENT])
            )
        }

        internal fun updatePreferences(p: MutablePreferences, transform: (CaptionSettings) -> CaptionSettings) {
            val current = readSettings(p)
            val next = transform(current)
            p[SOURCE] = RecognitionLanguages.normalizeMode(next.sourceLanguageCode)
            p[TARGET] = next.targetLanguageCode
            p[TEXT_SIZE] = next.textSizeSp.coerceIn(14f, 40f)
            p[OPACITY] = next.overlayOpacity.coerceIn(0.2f, 1f)
            p[SHOW_ORIGINAL] = next.showOriginal
            p[DISPLAY_MODE] = next.captionDisplayMode.name
            p[STABLE_CAPTIONS] = next.stableCaptions
            p[CONTROLS_EXPANDED] = next.overlayControlsExpanded
            p[OVERLAY_X] = next.overlayX
            p[OVERLAY_Y] = next.overlayY
            p[OVERLAY_MIN] = next.overlayMinimized
            p[AUDIO_SOURCE] = next.audioSource.name
            p[OVERLAY_W] = next.overlayWidthDp.coerceAtLeast(CaptionSettings.MIN_OVERLAY_WIDTH_DP)
            p[OVERLAY_H] = next.overlayHeightDp.coerceAtLeast(CaptionSettings.MIN_OVERLAY_HEIGHT_DP)
            p[OVERLAY_THEME] = next.overlayThemeId
            p[OVERLAY_FONT] = next.overlayFontId
            p[SAVE_HISTORY] = next.saveHistory
            p[PLACEMENT] = next.captionPlacement.name
        }

        private val SOURCE = stringPreferencesKey("source")
        private val TARGET = stringPreferencesKey("target")
        private val TEXT_SIZE = floatPreferencesKey("text_size")
        private val OPACITY = floatPreferencesKey("opacity")
        private val SHOW_ORIGINAL = booleanPreferencesKey("show_original")
        private val DISPLAY_MODE = stringPreferencesKey("caption_display_mode")
        private val STABLE_CAPTIONS = booleanPreferencesKey("stable_captions")
        private val CONTROLS_EXPANDED = booleanPreferencesKey("overlay_controls_expanded")
        private val OVERLAY_X = intPreferencesKey("overlay_x")
        private val OVERLAY_Y = intPreferencesKey("overlay_y")
        private val OVERLAY_MIN = booleanPreferencesKey("overlay_min")
        private val AUDIO_SOURCE = stringPreferencesKey("audio_source")
        private val OVERLAY_W = intPreferencesKey("overlay_w")
        private val OVERLAY_H = intPreferencesKey("overlay_h")
        private val OVERLAY_THEME = stringPreferencesKey("overlay_theme")
        private val OVERLAY_FONT = stringPreferencesKey("overlay_font")
        private val PLACEMENT = stringPreferencesKey("caption_placement")
        private val SAVE_HISTORY = booleanPreferencesKey("save_history")
    }
}
