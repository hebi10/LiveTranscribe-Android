package com.charles.livecaptionn.settings

enum class AppThemeMode {
    SYSTEM, LIGHT, DARK;

    fun useDarkColors(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromName(value: String?): AppThemeMode =
            entries.firstOrNull { it.name == value } ?: DARK
    }
}
