package com.charles.livecaptionn.settings

/** Independent from the CAPTIONS/CHAT reading style and safe for existing installations. */
enum class CaptionPlacement {
    POPUP, SPLIT_SCREEN;

    companion object {
        fun fromName(name: String?): CaptionPlacement = entries.firstOrNull { it.name == name } ?: POPUP
    }
}
