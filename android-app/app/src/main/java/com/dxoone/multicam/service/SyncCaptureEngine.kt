package com.dxoone.multicam.service

import com.dxoone.multicam.usb.CameraConnection
import com.dxoone.multicam.usb.CaptureResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Engine for synchronized multi-camera capture.
 *
 * Provides parallel and sequential capture modes with timing analysis.
 *
 * Invariants:
 * - INV-MULTI-003: Partial failure handling with detailed status reporting
 * - INV-MULTI-004: Synchronization accuracy documentation (~50ms variance)
 */
class SyncCaptureEngine {

    /**
     * Capture photos from all cameras simultaneously using parallel dispatch.
     *
     * Expected synchronization variance: ~20-65ms (typically ~50ms)
     * This is due to USB scheduling, hub arbitration, and camera processing delays.
     *
     * @param cameras List of connected cameras to capture from
     * @return MultiCaptureResult with per-camera status and timing data
     */
    suspend fun captureAllParallel(
        cameras: List<CameraConnection>
    ): MultiCaptureResult = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        // Filter to ready cameras only
        val readyCameras = cameras.filter { it.isReady() }

        if (readyCameras.isEmpty()) {
            return@withContext MultiCaptureResult(
                sessionId = sessionId,
                totalCameras = cameras.size,
                results = emptyList(),
                allSucceeded = false,
                syncVarianceMs = 0,
                totalTimeMs = 0,
                mode = CaptureMode.PARALLEL,
                error = "No cameras ready for capture"
            )
        }

        // Launch parallel capture jobs for all cameras
        val captureJobs = readyCameras.map { camera ->
            async {
                camera.takePhoto()
            }
        }

        // INV-MULTI-003: Use awaitAll to get all results (doesn't fail-fast)
        val results = captureJobs.awaitAll()
        val endTime = System.currentTimeMillis()

        // Calculate synchronization variance from successful captures
        val successfulTimestamps = results
            .filter { it.success }
            .map { it.timestamp }

        val syncVariance = if (successfulTimestamps.size > 1) {
            successfulTimestamps.max() - successfulTimestamps.min()
        } else {
            0L
        }

        MultiCaptureResult(
            sessionId = sessionId,
            totalCameras = cameras.size,
            results = results,
            allSucceeded = results.all { it.success },
            syncVarianceMs = syncVariance,
            totalTimeMs = endTime - startTime,
            mode = CaptureMode.PARALLEL
        )
    }

    /**
     * Capture photos sequentially from each camera.
     *
     * More reliable but with higher timing variance between captures.
     *
     * @param cameras List of connected cameras to capture from
     * @return MultiCaptureResult with per-camera status
     */
    suspend fun captureAllSequential(
        cameras: List<CameraConnection>
    ): MultiCaptureResult = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        val readyCameras = cameras.filter { it.isReady() }

        if (readyCameras.isEmpty()) {
            return@withContext MultiCaptureResult(
                sessionId = sessionId,
                totalCameras = cameras.size,
                results = emptyList(),
                allSucceeded = false,
                syncVarianceMs = 0,
                totalTimeMs = 0,
                mode = CaptureMode.SEQUENTIAL,
                error = "No cameras ready for capture"
            )
        }

        // Capture sequentially
        val results = readyCameras.map { camera ->
            camera.takePhoto()
        }

        val endTime = System.currentTimeMillis()

        // Calculate timing variance
        val successfulTimestamps = results
            .filter { it.success }
            .map { it.timestamp }

        val syncVariance = if (successfulTimestamps.size > 1) {
            successfulTimestamps.max() - successfulTimestamps.min()
        } else {
            0L
        }

        MultiCaptureResult(
            sessionId = sessionId,
            totalCameras = cameras.size,
            results = results,
            allSucceeded = results.all { it.success },
            syncVarianceMs = syncVariance,
            totalTimeMs = endTime - startTime,
            mode = CaptureMode.SEQUENTIAL
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
}

/**
 * Result of a multi-camera capture operation.
 *
 * INV-MULTI-003: Provides detailed per-camera status reporting.
 * INV-MULTI-004: Includes synchronization timing data.
 */
data class MultiCaptureResult(
    val sessionId: String,
    val totalCameras: Int,
    val results: List<CaptureResult>,
    val allSucceeded: Boolean,
    val syncVarianceMs: Long,
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
}

/**
 * Capture synchronization modes.
 *
 * INV-MULTI-004: Document sync mode and expected variance.
 */
enum class CaptureMode {
    /**
     * Parallel dispatch - Best effort synchronization.
     * Expected variance: ~20-65ms (typically ~50ms)
     */
    PARALLEL,

    /**
     * Sequential capture - More reliable but higher variance.
     * Each camera captures after the previous completes.
     */
    SEQUENTIAL
}
