package com.dxoone.multicam.usb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureBarrierTest {

    private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    @Test
    fun releasesEveryoneWhenTheLastPartyArrives() = runBlocking {
        val barrier = CaptureBarrier(3, timeoutMs = 5_000)
        val releasedAt = (0 until 3).map { k ->
            async(Dispatchers.Default) {
                delay(100L * k) // arrive at 0, 100, 200 ms
                assertTrue(barrier.arriveAndAwait())
                System.nanoTime()
            }
        }.awaitAll()
        val spreadMs = (releasedAt.max() - releasedAt.min()) / 1_000_000
        assertTrue("released ${spreadMs}ms apart", spreadMs < 100)
        assertTrue(barrier.isReleased)
    }

    @Test
    fun aPartyThatLeavesDoesNotHoldTheOthers() = runBlocking {
        val barrier = CaptureBarrier(2, timeoutMs = 5_000)
        val start = System.nanoTime()
        val waiter = async(Dispatchers.Default) { barrier.arriveAndAwait() }
        delay(50)
        barrier.leave()
        assertTrue(waiter.await())
        assertTrue(elapsedMs(start) < 1_000)
    }

    @Test
    fun waitersGiveUpAfterTheTimeout() = runBlocking {
        val barrier = CaptureBarrier(2, timeoutMs = 200)
        val start = System.nanoTime()
        assertFalse(barrier.arriveAndAwait())
        val waited = elapsedMs(start)
        assertTrue("waited ${waited}ms", waited in 150..1_000)
        assertTrue(barrier.isBroken)
        assertTrue(barrier.isReleased)
        // A late party doesn't wait on a broken barrier; it sends at once.
        val lateStart = System.nanoTime()
        assertFalse(barrier.arriveAndAwait())
        assertTrue(elapsedMs(lateStart) < 100)
    }

    @Test
    fun aTimeoutReleasesEveryoneStillWaitingTogether() = runBlocking {
        // A arrives at 0 ms, B at 200 ms, C never arrives: both go at A's deadline (~300 ms),
        // not B at ~500 ms.
        val barrier = CaptureBarrier(3, timeoutMs = 300)
        val start = System.nanoTime()
        val releasedAtMs = (0 until 2).map { k ->
            async(Dispatchers.Default) {
                delay(200L * k)
                assertFalse(barrier.arriveAndAwait())
                elapsedMs(start)
            }
        }.awaitAll()
        assertTrue("released at $releasedAtMs", releasedAtMs.all { it in 250..450 })
        assertTrue("released at $releasedAtMs", releasedAtMs.max() - releasedAtMs.min() < 100)
    }

    @Test
    fun zeroPartiesIsAlreadyReleased() {
        assertTrue(CaptureBarrier(0, timeoutMs = 1_000).isReleased)
    }

    @Test
    fun singlePartyDoesNotWait() = runBlocking {
        val barrier = CaptureBarrier(1, timeoutMs = 5_000)
        val start = System.nanoTime()
        assertTrue(barrier.arriveAndAwait())
        assertTrue(elapsedMs(start) < 1_000)
    }

    @Test
    fun manyConcurrentPartiesAllRelease() = runBlocking {
        val parties = 16
        val barrier = CaptureBarrier(parties, timeoutMs = 5_000)
        val go = CompletableDeferred<Unit>()
        val results = (0 until parties).map { k ->
            async(Dispatchers.Default) {
                go.await()
                if (k % 4 == 0) {
                    barrier.leave()
                    true
                } else {
                    barrier.arriveAndAwait()
                }
            }
        }
        go.complete(Unit)
        assertEquals(List(parties) { true }, results.awaitAll())
    }
}
