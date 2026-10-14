package com.charles.livecaptionn.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.CaptionSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.captionDataStore by preferencesDataStore(name = "caption_settings")

class SettingsDataStore(private val context: Context) : SettingsRepository {
    override val settingsFlow: Flow<CaptionSettings> =
        context.captionDataStore.data.map { p ->
            val defaults = CaptionSettings()
            CaptionSettings(
                sourceLanguageCode = p[SOURCE] ?: defaults.sourceLanguageCode,
                targetLanguageCode = p[TARGET] ?: defaults.targetLanguageCode,
                textSizeSp = (p[TEXT_SIZE] ?: defaults.textSizeSp).coerceIn(14f, 40f),
                overlayOpacity = (p[OPACITY] ?: defaults.overlayOpacity).coerceIn(0.2f, 1f),
                showOriginal = p[SHOW_ORIGINAL] ?: defaults.showOriginal,
                overlayX = p[OVERLAY_X] ?: defaults.overlayX,
                overlayY = p[OVERLAY_Y] ?: defaults.overlayY,
                overlayMinimized = p[OVERLAY_MIN] ?: defaults.overlayMinimized,
                audioSource = AudioSource.fromName(p[AUDIO_SOURCE] ?: defaults.audioSource.name),
                overlayWidthDp = p[OVERLAY_W] ?: defaults.overlayWidthDp,
                overlayHeightDp = p[OVERLAY_H] ?: defaults.overlayHeightDp,
                overlayThemeId = p[OVERLAY_THEME] ?: defaults.overlayThemeId,
                overlayFontId = p[OVERLAY_FONT] ?: defaults.overlayFontId,
                saveHistory = p[SAVE_HISTORY] ?: defaults.saveHistory
            )
        }

    override suspend fun update(transform: (CaptionSettings) -> CaptionSettings) {
        context.captionDataStore.edit { p ->
            val current = CaptionSettings(
                sourceLanguageCode = p[SOURCE] ?: "en",
                targetLanguageCode = p[TARGET] ?: "ko",
                textSizeSp = p[TEXT_SIZE] ?: 22f,
                overlayOpacity = p[OPACITY] ?: 0.85f,
                showOriginal = p[SHOW_ORIGINAL] ?: false,
                overlayX = p[OVERLAY_X] ?: 24,
                overlayY = p[OVERLAY_Y] ?: 260,
                overlayMinimized = p[OVERLAY_MIN] ?: false,
                audioSource = AudioSource.fromName(p[AUDIO_SOURCE] ?: "SYSTEM"),
                overlayWidthDp = p[OVERLAY_W] ?: 340,
                overlayHeightDp = p[OVERLAY_H] ?: 160,
                overlayThemeId = p[OVERLAY_THEME] ?: "default",
                overlayFontId = p[OVERLAY_FONT] ?: "default",
                saveHistory = p[SAVE_HISTORY] ?: false
            )
            val next = transform(current)
            p[SOURCE] = next.sourceLanguageCode
            p[TARGET] = next.targetLanguageCode
            p[TEXT_SIZE] = next.textSizeSp.coerceIn(14f, 40f)
            p[OPACITY] = next.overlayOpacity.coerceIn(0.2f, 1f)
            p[SHOW_ORIGINAL] = next.showOriginal
            p[OVERLAY_X] = next.overlayX
            p[OVERLAY_Y] = next.overlayY
            p[OVERLAY_MIN] = next.overlayMinimized
            p[AUDIO_SOURCE] = next.audioSource.name
            p[OVERLAY_W] = next.overlayWidthDp.coerceAtLeast(CaptionSettings.MIN_OVERLAY_WIDTH_DP)
            p[OVERLAY_H] = next.overlayHeightDp.coerceAtLeast(CaptionSettings.MIN_OVERLAY_HEIGHT_DP)
            p[OVERLAY_THEME] = next.overlayThemeId
            p[OVERLAY_FONT] = next.overlayFontId
            p[SAVE_HISTORY] = next.saveHistory
        }
    }

    private companion object {
        val SOURCE = stringPreferencesKey("source")
        val TARGET = stringPreferencesKey("target")
        val TEXT_SIZE = floatPreferencesKey("text_size")
        val OPACITY = floatPreferencesKey("opacity")
        val SHOW_ORIGINAL = booleanPreferencesKey("show_original")
        val OVERLAY_X = intPreferencesKey("overlay_x")
        val OVERLAY_Y = intPreferencesKey("overlay_y")
        val OVERLAY_MIN = booleanPreferencesKey("overlay_min")
        val AUDIO_SOURCE = stringPreferencesKey("audio_source")
        val OVERLAY_W = intPreferencesKey("overlay_w")
        val OVERLAY_H = intPreferencesKey("overlay_h")
        val OVERLAY_THEME = stringPreferencesKey("overlay_theme")
        val OVERLAY_FONT = stringPreferencesKey("overlay_font")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
    }
}

