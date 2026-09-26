package com.github.iamr8.nugetextended.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HelperService.requeueOnFailure] is the pure part of `flushChanges`'s fix: it needs no
 * [HelperService] instance (and so no `ApplicationManager`), just a mutable set and an action.
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
}
