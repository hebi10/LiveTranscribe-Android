package com.charles.livecaptionn.settings

enum class BubbleSide {
    LEFT, RIGHT;

    companion object {
        fun fromName(value: String?): BubbleSide =
            entries.firstOrNull { it.name == value } ?: RIGHT
    }
}

object BubbleCaptionOptions {
    const val DEFAULT_DURATION_MS = 5000
    const val DEFAULT_TEXT_SIZE_SP = 16f
    val durationsMs = listOf(3000, 5000, 8000)
    val textSizesSp = listOf(14f, 16f, 20f)

    fun duration(value: Int?): Int =
        value?.takeIf { it in durationsMs } ?: DEFAULT_DURATION_MS

    fun textSize(value: Float?): Float =
        value?.takeIf { it.isFinite() && it in textSizesSp } ?: DEFAULT_TEXT_SIZE_SP
}
