package com.rohankanjani.lull

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Sends one thing at a time, in the order it was posted, so a message that takes longer to go can't reach the watch after
 * a newer one. Any one that takes longer than [patience] is given up on, so a send that never finishes doesn't hold up
 * everything after it.
 */
class Outbox(scope: CoroutineScope, private val patience: Long = 10_000) {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (sending in queue) runCatching { withTimeout(patience) { sending() } }
        }
    }

    fun post(sending: suspend () -> Unit) {
        queue.trySend(sending)
    }

    /** Posts, and waits until it has gone, been given up on or failed. */
    suspend fun postAndWait(sending: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
        post {
            try {
                sending()
            } finally {
                done.complete(Unit)
            }
        }
        done.await()
    }
}
