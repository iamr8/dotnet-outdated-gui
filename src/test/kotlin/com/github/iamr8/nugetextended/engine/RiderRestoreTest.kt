package com.github.iamr8.nugetextended.engine

import com.github.iamr8.nugetextended.engine.RiderRestore.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RiderRestoreTest {

    /** A fake clock: each sleep moves time forward; [states] gives Rider's state per poll (last one repeats). */
    private fun await(vararg states: Boolean?, graceMs: Long = 1_000, maxMs: Long = 10_000): Pair<Outcome, Long> {
        var now = 0L
        var poll = 0
        val outcome = RiderRestore.await(
            busy = { states[minOf(poll++, states.size - 1)] },
            graceMs = graceMs, maxMs = maxMs, pollMs = 250,
            now = { now }, sleep = { now += it }, checkCanceled = {},
        )
        return outcome to now
    }

    @Test
    fun riderRestoreThatEndsIsWaitedFor() {
        assertEquals(Outcome.RIDER_RESTORED to 750L, await(false, true, true, false))
    }

    @Test
    fun noRestoreWithinTheGraceMeansNotRunning() {
        assertEquals(Outcome.NOT_RUNNING to 1_000L, await(false))
    }

    @Test
    fun aRestoreThatStartsLateInTheGraceStillCounts() {
        assertEquals(Outcome.RIDER_RESTORED, await(false, false, false, true, false).first)
    }

    @Test
    fun unreadableStateMeansUnknown() {
        assertEquals(Outcome.UNKNOWN to 0L, await(null))
    }

    @Test
    fun aRestoreLongerThanTheLimitIsStillRunning() {
        assertEquals(Outcome.STILL_RUNNING to 2_000L, await(true, maxMs = 2_000))
    }

    @Test
    fun afterRiderRestoredOnlyOurOwnProjectsAreLeft() {
        assertEquals(listOf("/f"), RiderRestore.oursAfter(Outcome.RIDER_RESTORED, listOf("/a", "/b"), listOf("/f")))
    }

    @Test
    fun withoutRiderWeRestoreEverything() {
        assertEquals(listOf("/a", "/f"), RiderRestore.oursAfter(Outcome.NOT_RUNNING, listOf("/a", "/f"), listOf("/f")))
        assertEquals(listOf("/a", "/f"), RiderRestore.oursAfter(Outcome.UNKNOWN, listOf("/a"), listOf("/f")))
    }

    @Test
    fun whileRiderStillRunsWeRestoreNothing() {
        assertNull(RiderRestore.oursAfter(Outcome.STILL_RUNNING, listOf("/a"), listOf("/f")))
    }
}
