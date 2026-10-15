package com.charles.livecaptionn.ui

internal enum class AudioPermissionAction { GRANTED, REQUEST, OPEN_SETTINGS }

internal fun audioPermissionAction(
    granted: Boolean,
    completedRequest: Boolean,
    showRationale: Boolean
): AudioPermissionAction = when {
    granted -> AudioPermissionAction.GRANTED
    completedRequest && !showRationale -> AudioPermissionAction.OPEN_SETTINGS
    else -> AudioPermissionAction.REQUEST
}
