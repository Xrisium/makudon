@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.xrisium.makudon

import io.github.xrisium.makudon.feature.workspace.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SaveCoordinatorTest {
    @Test fun idleDebounceAndContinuousTypingHaveBoundedDeadline() = runTest {
        val writes = mutableListOf<Pair<Long, String>>()
        val saver = SaveCoordinator(backgroundScope, "", { currentTime }) { writes += currentTime to it }
        saver.changed("a"); advanceTimeBy(499); runCurrent(); assertTrue(writes.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(listOf(500L to "a"), writes)
        repeat(25) { saver.changed("a$it"); advanceTimeBy(100); runCurrent() }
        assertTrue(writes.size >= 2)
        assertEquals(2_500L, writes[1].first)
        assertEquals(SavePhase.Waiting, saver.status.value.phase)
    }
    @Test fun slowWriterCoalescesToLatestWithoutFalseAcknowledgement() = runTest {
        val writes = mutableListOf<String>(); var simultaneous = 0; var maximum = 0
        val saver = SaveCoordinator(backgroundScope, "0", { currentTime }) {
            simultaneous++; maximum = maxOf(maximum, simultaneous); writes += it; delay(1000); simultaneous--
        }
        saver.changed("1"); advanceTimeBy(500); runCurrent()
        repeat(300) { saver.changed("latest-$it") }
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("1", "latest-299"), writes)
        assertEquals(1L, saver.savedRevision)
        assertEquals(SavePhase.Saving, saver.status.value.phase)
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, maximum); assertEquals(saver.revision, saver.savedRevision)
        assertEquals("latest-299", saver.latest); assertEquals(SavePhase.Saved, saver.status.value.phase)
    }
    @Test fun failureRetainsLatestAndPausesAutomaticRetries() = runTest {
        var failure = true; val writes = mutableListOf<String>()
        val saver = SaveCoordinator(backgroundScope, "baseline", { currentTime }) {
            writes += it; if (failure) throw IOException("injected")
        }
        saver.changed("new"); advanceTimeBy(500); runCurrent()
        assertEquals(SavePhase.Failed, saver.status.value.phase)
        saver.changed("newest"); advanceTimeBy(5000); runCurrent()
        assertEquals(listOf("new"), writes); assertEquals("newest", saver.latest)
        assertFalse(saver.flush())
        failure = false
        assertTrue(saver.flush(retry = true))
        assertEquals(listOf("new", "newest"), writes); assertEquals(SavePhase.Saved, saver.status.value.phase)
    }
    @Test fun leavingWaitsForNewestRevisionAndUnchangedTextDoesNotWrite() = runTest {
        val writes = mutableListOf<String>()
        val saver = SaveCoordinator(backgroundScope, "baseline", { currentTime }) { delay(100); writes += it }
        saver.changed("baseline"); assertTrue(saver.flush()); assertTrue(writes.isEmpty())
        saver.changed("first")
        val barrier = async { saver.flush() }
        runCurrent(); saver.changed("second"); advanceUntilIdle()
        assertTrue(barrier.await()); assertEquals(listOf("first", "second"), writes)
        assertEquals(saver.revision, saver.savedRevision)
    }
}
