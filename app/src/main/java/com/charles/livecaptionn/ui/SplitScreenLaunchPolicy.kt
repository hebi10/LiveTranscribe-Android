package com.charles.livecaptionn.ui

internal fun canRequestAdjacentLaunch(sdkInt: Int, alreadyMultiWindow: Boolean): Boolean =
    sdkInt >= 32 || alreadyMultiWindow
