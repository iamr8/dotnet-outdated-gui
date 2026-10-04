package com.github.iamr8.nugetextended.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HelperService.requeueOnFailure] and [RestartOnce] are the pure parts of `flushChanges` and
 * `call`: they need no [HelperService] instance (and so no `ApplicationManager`).
 */
class HelperServiceTest {
    @Test
    fun requeuesPathsWhenTheActionFails() {
        val changed = mutableSetOf<String>()
        val paths = listOf("a.csproj", "b.csproj")
        val thrown = RuntimeException("boom")

        val caught = try {
            HelperService.requeueOnFailure(changed, paths) { throw thrown }
            null
        } catch (e: RuntimeException) {
            e
        }

        assertEquals(thrown, caught)
        assertEquals(paths.toSet(), changed)
    }

    @Test
    fun doesNotRequeueWhenTheActionSucceeds() {
        val changed = mutableSetOf<String>()
        val result = HelperService.requeueOnFailure(changed, listOf("a.csproj")) { "ok" }

        assertEquals("ok", result)
        assertTrue(changed.isEmpty())
    }

    // Break: RestartOnce.allow ignores `restarted` (a second crash in one call would retry again).
    @Test
    fun restartsOnceThenGivesUp() {
        val restart = RestartOnce()

        assertTrue(restart.allow(disposed = false))
        assertFalse(restart.allow(disposed = false))
        assertFalse(restart.allow(disposed = false))
    }

    // Break: RestartOnce.allow ignores `disposed` (a closed project would start a helper again).
    @Test
    fun neverRestartsAfterDispose() {
        assertFalse(RestartOnce().allow(disposed = true))
    }

    // Break: RestartOnce keeps its flag in a shared place (a later call would get no retry).
    @Test
    fun eachCallGetsItsOwnRestart() {
        assertTrue(RestartOnce().allow(disposed = false))
        assertTrue(RestartOnce().allow(disposed = false))
    }
}
