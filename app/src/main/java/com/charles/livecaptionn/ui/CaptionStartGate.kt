package com.charles.livecaptionn.ui

/** Keeps permission-only requests separate from a requested caption session. */
class CaptionStartGate(savedStage: String? = null) {
    private enum class Stage { IDLE, CHECKING, PERMISSION_ONLY, START_PERMISSION, PROJECTION, SERVICE, CANCELED_AUDIO, CANCELED_PROJECTION }
    private var stage = Stage.entries.firstOrNull { it.name == savedStage } ?: Stage.IDLE
    val busy: Boolean get() = stage != Stage.IDLE
    val savedStage: String get() = stage.name
    val checking: Boolean get() = stage == Stage.CHECKING
    val servicePending: Boolean get() = stage == Stage.SERVICE

    fun begin(): Boolean {
        if (busy) return false
        stage = Stage.CHECKING
        return true
    }

    fun requestAudioPermission(forStart: Boolean): Boolean {
        if (forStart) {
            if (stage != Stage.CHECKING) return false
            stage = Stage.START_PERMISSION
        } else {
            if (busy) return false
            stage = Stage.PERMISSION_ONLY
        }
        return true
    }

    fun audioPermissionResult(granted: Boolean): Boolean {
        val resumeStart = granted && stage == Stage.START_PERMISSION
        stage = if (resumeStart) Stage.CHECKING else Stage.IDLE
        return resumeStart
    }

    fun awaitProjection() { stage = Stage.PROJECTION }

    fun projectionResult(granted: Boolean): Boolean {
        val start = granted && stage == Stage.PROJECTION
        stage = if (start) Stage.SERVICE else Stage.IDLE
        return start
    }

    fun serviceRequested() { stage = Stage.SERVICE }
    fun cancel() {
        stage = when (stage) {
            Stage.PERMISSION_ONLY, Stage.START_PERMISSION, Stage.CANCELED_AUDIO -> Stage.CANCELED_AUDIO
            Stage.PROJECTION, Stage.CANCELED_PROJECTION -> Stage.CANCELED_PROJECTION
            else -> Stage.IDLE
        }
    }
}
