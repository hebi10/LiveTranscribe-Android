package com.charles.livecaptionn.speech

/** Serialize native calls and make finalization/closure terminal and idempotent. */
internal class NativeSessionOwner<T>(private val resource: T) {
    private var finished = false
    private var closed = false

    @Synchronized fun <R> feed(empty: R, action: (T) -> R): R =
        if (finished || closed) empty else action(resource)

    @Synchronized fun <R> finish(empty: R, action: (T) -> R): R {
        if (finished || closed) return empty
        finished = true
        return action(resource)
    }

    @Synchronized fun close(action: (T) -> Unit) {
        if (closed) return
        closed = true
        action(resource)
    }
}
