package com.charles.livecaptionn.settings

enum class AudioSource {
    MIC, SYSTEM;

    companion object {
        fun fromName(name: String): AudioSource =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
