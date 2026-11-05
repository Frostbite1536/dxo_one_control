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
 * others back for more than [timeoutMs]: each waiter gives up after that long and sends
 * anyway. Each party must call exactly one of [arriveAndAwait] or [leave], exactly once.
 */
class CaptureBarrier(parties: Int, private val timeoutMs: Long) {
    private val remaining = AtomicInteger(parties)
    private val allArrived = CompletableDeferred<Unit>()

    init {
        if (parties <= 0) allArrived.complete(Unit)
    }

    /** True once every party has arrived or left. */
    val isReleased: Boolean get() = allArrived.isCompleted

    /**
     * Count this party in and wait for the rest, at most [timeoutMs].
     *
     * @return true if every party arrived or left in time, false if this waiter timed out
     */
    suspend fun arriveAndAwait(): Boolean {
        countDown()
        return withTimeoutOrNull(timeoutMs) { allArrived.await() } != null
    }

    /** Count this party out without waiting; it will not send. */
    fun leave() {
        countDown()
    }

    private fun countDown() {
        if (remaining.decrementAndGet() <= 0) allArrived.complete(Unit)
    }
}
