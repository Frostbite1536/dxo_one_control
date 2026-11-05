package com.dxoone.multicam.service

import com.dxoone.multicam.usb.CameraConnection
import com.dxoone.multicam.usb.CaptureBarrier
import com.dxoone.multicam.usb.CaptureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/**
 * Multi-camera capture: sends `dxo_photo_take` to several cameras, in parallel or one after
 * another, and reports per-camera results plus the host-side trigger spread.
 *
 * What is measured: for each camera, the moment its request frame finished being written to
 * bulk-OUT ([CaptureResult.triggerWrittenAtNanos]). That is the closest host-side proxy for
 * the trigger. The cameras give no feedback on when the shutter actually fired, so the
 * spread between shutters is NOT measured and can be larger (USB scheduling on the camera
 * side, firmware latency, focus, exposure).
 *
 * Invariants:
 * - INV-MULTI-003: Partial failure handling with detailed status reporting
 * - INV-MULTI-004: Synchronization accuracy documentation (only the host-side trigger spread
 *   is measured; shutter sync is not)
 */
class SyncCaptureEngine(
    private val barrierTimeoutMs: Long = DEFAULT_BARRIER_TIMEOUT_MS
) {

    suspend fun capture(cameras: List<CameraConnection>, mode: CaptureMode): MultiCaptureResult =
        when (mode) {
            CaptureMode.PARALLEL -> captureAllParallel(cameras)
            CaptureMode.SEQUENTIAL -> captureAllSequential(cameras)
        }

    /**
     * Capture from all cameras at once.
     *
     * Each camera's request is prepared (request lock taken, frame encoded) and then held at
     * a [CaptureBarrier]; when every camera is ready (or after [barrierTimeoutMs]) they are
     * released together, so the writes start as close together as the host can manage.
     */
    suspend fun captureAllParallel(cameras: List<CameraConnection>): MultiCaptureResult =
        captureWith(cameras, CaptureMode.PARALLEL) { ready ->
            val barrier = CaptureBarrier(ready.size, barrierTimeoutMs)
            // INV-MULTI-003: takePhoto never throws (except cancellation), so awaitAll
            // collects every camera's result.
            ready.map { camera -> async { camera.takePhoto(barrier) } }.awaitAll()
        }

    /**
     * Capture from each camera after the previous one has replied. The trigger spread is
     * then roughly the sum of the capture round-trips.
     */
    suspend fun captureAllSequential(cameras: List<CameraConnection>): MultiCaptureResult =
        captureWith(cameras, CaptureMode.SEQUENTIAL) { ready ->
            ready.map { camera -> camera.takePhoto() }
        }

    private suspend fun captureWith(
        cameras: List<CameraConnection>,
        mode: CaptureMode,
        captureReady: suspend CoroutineScope.(List<CameraConnection>) -> List<CaptureResult>
    ): MultiCaptureResult = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        val ready = cameras.filter { it.isReady() }
        fun notReady(camera: CameraConnection) =
            CaptureResult(cameraId = camera.id, success = false, error = "Camera not ready")

        if (ready.isEmpty()) {
            return@withContext MultiCaptureResult(
                sessionId = sessionId,
                totalCameras = cameras.size,
                results = cameras.map(::notReady),
                allSucceeded = false,
                totalTimeMs = 0,
                mode = mode,
                error = "No cameras ready for capture"
            )
        }

        // Report every requested camera, in the order given (INV-MULTI-003).
        val captured = ready.zip(captureReady(ready)).toMap()
        val results = cameras.map { captured[it] ?: notReady(it) }
        val endTime = System.currentTimeMillis()

        MultiCaptureResult(
            sessionId = sessionId,
            totalCameras = cameras.size,
            results = results,
            allSucceeded = results.all { it.success },
            totalTimeMs = endTime - startTime,
            mode = mode
        )
    }

    /**
     * Pre-focus all cameras at center point.
     * Should be called before capture for faster response.
     */
    suspend fun preFocusAll(cameras: List<CameraConnection>): Boolean =
        withContext(Dispatchers.IO) {
            val focusJobs = cameras.filter { it.isReady() }.map { camera ->
                async { camera.focus(128, 128) } // Center point
            }

            focusJobs.awaitAll().all { it }
        }

    /**
     * Refresh status of all cameras.
     */
    suspend fun refreshAllStatus(cameras: List<CameraConnection>) =
        withContext(Dispatchers.IO) {
            cameras.filter { it.isReady() }.map { camera ->
                async { camera.refreshStatus() }
            }.awaitAll()
        }

    companion object {
        /**
         * How long a ready camera waits at the barrier for the others before sending anyway
         * (e.g. one camera's request lock is held by a slow command).
         */
        const val DEFAULT_BARRIER_TIMEOUT_MS = 300L
    }
}

/**
 * Result of a multi-camera capture operation.
 *
 * INV-MULTI-003: Provides detailed per-camera status reporting.
 * INV-MULTI-004: Includes the host-side trigger spread (not shutter sync).
 */
data class MultiCaptureResult(
    val sessionId: String,
    val totalCameras: Int,
    val results: List<CaptureResult>,
    val allSucceeded: Boolean,
    val totalTimeMs: Long,
    val mode: CaptureMode,
    val error: String? = null
) {
    val succeededCount: Int get() = results.count { it.success }
    val failedCount: Int get() = results.count { !it.success }

    val failedCameras: List<CaptureResult>
        get() = results.filter { !it.success }

    val succeededCameras: List<CaptureResult>
        get() = results.filter { it.success }

    /**
     * Host-side trigger spread: latest minus earliest [CaptureResult.triggerWrittenAtNanos]
     * across the cameras that captured successfully, in nanoseconds. Null with fewer than two
     * such cameras. This is when the requests left the host, not when the shutters fired.
     */
    val triggerSpreadNanos: Long?
        get() {
            val written = results.filter { it.success }.mapNotNull { it.triggerWrittenAtNanos }
            if (written.size < 2) return null
            return written.max() - written.min()
        }

    /** [triggerSpreadNanos] as e.g. "0.42 ms", or null. */
    val triggerSpreadText: String?
        get() = triggerSpreadNanos?.let { String.format(Locale.US, "%.2f ms", it / 1_000_000.0) }

    /**
     * One-line summary for a snackbar or notification. Never claims shutter sync: the only
     * timing figure is the host-side trigger spread.
     */
    fun summaryText(): String {
        error?.let { return "Capture failed: $it" }
        val counts = if (allSucceeded) {
            "Captured $succeededCount ${if (succeededCount == 1) "photo" else "photos"}"
        } else {
            "Captured $succeededCount/$totalCameras photos"
        }
        return triggerSpreadText?.let { "$counts (trigger spread, host-side: $it)" } ?: counts
    }
}

/**
 * Capture modes.
 *
 * INV-MULTI-004: neither mode synchronizes shutters; only the host-side trigger spread is
 * measured (see [MultiCaptureResult.triggerSpreadNanos]).
 */
enum class CaptureMode {
    /**
     * All cameras' requests are released together from a barrier (best effort). The
     * host-side trigger spread is typically small; the shutter spread is unmeasured.
     */
    PARALLEL,

    /**
     * Each camera captures after the previous one has replied. Simpler on the USB bus, but
     * the cameras fire far apart.
     */
    SEQUENTIAL
}
