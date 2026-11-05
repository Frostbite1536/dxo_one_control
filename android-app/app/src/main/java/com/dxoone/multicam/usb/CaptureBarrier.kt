package com.dxoone.multicam.usb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * One-shot start line for a multi-camera capture.
 *
 * Each of `parties` cameras either calls [arriveAndAwait] (its request frame is built and it
 * holds its own request lock, so all that is left is the bulk-OUT write) or calls [leave] (it
 * will not send, e.g. it isn't connected). When every party has done one or the
 * other, all waiting cameras are released together, so their writes start at about the same
 * moment instead of being spread by mutex waits, JSON encoding and dispatch.
 *
 * A camera that is late (say, its request lock is held by another command) can't hold the
 * others back for more than [timeoutMs]: the first waiter to time out *breaks* the barrier,
 * which releases every camera waiting at that moment together, and later arrivals don't
 * wait at all. So the deadline is shared: [timeoutMs] after the first arrival at most.
 * Each party must call exactly one of [arriveAndAwait] or [leave], exactly once.
 */
class CaptureBarrier(parties: Int, private val timeoutMs: Long) {
    private val remaining = AtomicInteger(parties)
    private val allArrived = CompletableDeferred<Unit>()
    @Volatile private var broken = false

    init {
        if (parties <= 0) allArrived.complete(Unit)
    }

    /** True once waiters are no longer held: every party arrived or left, or it broke. */
    val isReleased: Boolean get() = allArrived.isCompleted

    /** True if a waiter timed out before every party arrived or left. */
    val isBroken: Boolean get() = broken

    /**
     * Count this party in and wait for the rest, at most [timeoutMs].
     *
     * @return true if every party arrived or left in time, false if the barrier broke
     *   (this waiter or an earlier one timed out); the caller sends either way.
     */
    suspend fun arriveAndAwait(): Boolean {
        countDown()
        if (withTimeoutOrNull(timeoutMs) { allArrived.await() } == null) {
            // Break the barrier: release everyone still waiting together with us.
            broken = true
            allArrived.complete(Unit)
        }
        return !broken
    }

    /** Count this party out without waiting; it will not send. */
    fun leave() {
        countDown()
    }

    private fun countDown() {
        if (remaining.decrementAndGet() <= 0 && !broken) allArrived.complete(Unit)
    }
}
