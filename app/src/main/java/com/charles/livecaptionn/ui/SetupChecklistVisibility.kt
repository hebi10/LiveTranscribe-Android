package com.charles.livecaptionn.ui

internal fun MainSetupState.visibleChecklistSteps(showAll: Boolean): List<SetupStep> {
    if (!isVisible) return emptyList()
    if (!showAll) return listOfNotNull(nextStep)
    return buildList {
        add(SetupStep.AUDIO_PERMISSION)
        if (overlayPermissionRequired) add(SetupStep.OVERLAY_PERMISSION)
        add(SetupStep.MODELS)
    }
}
