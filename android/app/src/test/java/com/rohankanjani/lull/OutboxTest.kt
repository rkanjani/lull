package com.rohankanjani.lull

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone sends the watch goes out in order, and one send that never finishes doesn't hold up the rest. On the
 * iPhone, Watch Connectivity keeps the order and the snapshot carries when it was made, so this has no twin.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxTest {
    @Test
    fun sendsInTheOrderPosted() = runTest {
        val outbox = Outbox(backgroundScope)
        val sent = mutableListOf<String>()
        // The first takes longer to go, as a slow lookup of the watches would.
        outbox.post { delay(500); sent += "older" }
        outbox.post { sent += "newer" }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("older", "newer"), sent)
    }

    @Test
    fun aSendThatNeverFinishesDoesntHoldUpTheRest() = runTest {
        val outbox = Outbox(backgroundScope, patience = 1_000)
        val sent = mutableListOf<String>()
        outbox.post { awaitCancellation() }
        outbox.post { sent += "reply" }
        advanceTimeBy(500)
        runCurrent()
        assertTrue(sent.isEmpty())
        advanceTimeBy(600)
        runCurrent()
        assertEquals(listOf("reply"), sent)
    }

    @Test
    fun waitingEndsEvenWhenTheSendFails() = runTest {
        val outbox = Outbox(backgroundScope)
        var waited = false
        launch {
            outbox.postAndWait { error("No watch nearby") }
            waited = true
        }
        runCurrent()
        assertTrue(waited)
    }

    @Test
    fun anErrandThatStartsTooLateIsDropped() {
        val asked = 1_000_000L
        assertFalse(ErrandWorker.isTooLate(asked, asked + 2_000))
        // Too late to finish before the watch gives up on it.
        assertTrue(ErrandWorker.isTooLate(asked, asked + ErrandWorker.START_WITHIN + 1))
        assertTrue(ErrandWorker.START_WITHIN < WatchMessages.PATIENCE_MILLIS)
    }
}
