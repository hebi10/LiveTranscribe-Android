package com.charles.livecaptionn.ui

import com.charles.livecaptionn.settings.CaptionPlacement

internal enum class HomeActionKind { START, STOP, BLOCKED }

internal data class HomeActionState(
    val kind: HomeActionKind,
    val label: String,
    val enabled: Boolean
)

internal fun MainUiState.homeAction(startPending: Boolean): HomeActionState = when {
    runtime.stopping -> HomeActionState(HomeActionKind.STOP, "종료 중", false)
    startPending -> HomeActionState(HomeActionKind.STOP, "요청 취소", true)
    runtime.running -> HomeActionState(HomeActionKind.STOP, "번역 중지", true)
    canStart -> HomeActionState(HomeActionKind.START, when {
        runtime.restartLanguageCode != null -> "번역 다시 시작"
        settings.captionPlacement == CaptionPlacement.SPLIT_SCREEN -> "분할 자막 시작"
        else -> "팝업 자막 시작"
    }, true)
    else -> HomeActionState(HomeActionKind.BLOCKED, "준비 후 번역 시작", false)
}
