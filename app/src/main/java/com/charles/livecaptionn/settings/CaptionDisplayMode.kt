package com.charles.livecaptionn.settings

enum class CaptionDisplayMode {
    CAPTIONS, CHAT;

    companion object {
        fun fromName(value: String): CaptionDisplayMode =
            entries.firstOrNull { it.name == value } ?: CAPTIONS
    }
}
