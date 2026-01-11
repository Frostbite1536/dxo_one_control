package com.dxoone.multicam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dxoone.multicam.R
import com.dxoone.multicam.ui.MainActivity
import com.dxoone.multicam.usb.CameraConnection
import com.dxoone.multicam.usb.DxoOneConstants
import com.dxoone.multicam.usb.UsbDeviceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service for managing DXO One camera connections.
 *
 * Runs as a foreground service to maintain USB connections while the app
 * is in the background. Provides multi-camera orchestration and
 * synchronized capture capabilities.
 *
 * Invariants:
 * - INV-MULTI-002: Maximum 4 cameras supported
 * - INV-DATA-003: Connection state must match actual hardware status
 */
@AndroidEntryPoint
class CameraManagerService : Service() {

    @Inject
    lateinit var usbDeviceManager: UsbDeviceManager

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val syncCaptureEngine = SyncCaptureEngine()

    // Service state
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _captureMode = MutableStateFlow(CaptureMode.PARALLEL)
    val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

    private val _lastCaptureResult = MutableStateFlow<MultiCaptureResult?>(null)
    val lastCaptureResult: StateFlow<MultiCaptureResult?> = _lastCaptureResult.asStateFlow()

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "dxo_camera_service"
        private const val TAG = "CameraManagerService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): CameraManagerService = this@CameraManagerService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        usbDeviceManager.initialize()
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        usbDeviceManager.cleanup()
        serviceScope.cancel()
    }

    /**
     * Get list of connected cameras.
     */
    fun getConnectedCameras(): List<CameraConnection> {
        return usbDeviceManager.connectedCameras.value.values.toList()
    }

    /**
     * Get a specific camera by ID.
     */
    fun getCamera(cameraId: String): CameraConnection? {
        return usbDeviceManager.getCamera(cameraId)
    }

    /**
     * Get the number of connected cameras.
     */
    fun getConnectedCount(): Int = usbDeviceManager.getConnectedCount()

    /**
     * Check if maximum camera limit is reached.
     * INV-MULTI-002: Maximum 4 cameras.
     */
    fun isAtMaxCapacity(): Boolean {
        return getConnectedCount() >= DxoOneConstants.MAX_CAMERAS
    }

    /**
     * Set the capture synchronization mode.
     */
    fun setCaptureMode(mode: CaptureMode) {
        _captureMode.value = mode
    }

    /**
     * Capture photos from all connected cameras.
     *
     * Uses the currently set capture mode (parallel or sequential).
     */
    suspend fun captureAll(): MultiCaptureResult {
        val cameras = getConnectedCameras()

        val result = when (_captureMode.value) {
            CaptureMode.PARALLEL -> syncCaptureEngine.captureAllParallel(cameras)
            CaptureMode.SEQUENTIAL -> syncCaptureEngine.captureAllSequential(cameras)
        }

        _lastCaptureResult.value = result
        updateNotification(result)

        return result
    }

    /**
     * Pre-focus all cameras at center.
     */
    suspend fun preFocusAll(): Boolean {
        return syncCaptureEngine.preFocusAll(getConnectedCameras())
    }

    /**
     * Refresh status of all cameras.
     */
    suspend fun refreshAllStatus() {
        syncCaptureEngine.refreshAllStatus(getConnectedCameras())
    }

    /**
     * Disconnect a specific camera.
     */
    fun disconnectCamera(cameraId: String) {
        usbDeviceManager.disconnectCamera(cameraId)
        updateNotification()
    }

    /**
     * Disconnect all cameras.
     */
    fun disconnectAll() {
        usbDeviceManager.disconnectAll()
        updateNotification()
    }

    /**
     * Apply a setting to all cameras.
     */
    suspend fun applySettingToAll(settingType: String, value: String): Boolean {
        val cameras = getConnectedCameras()
        val results = cameras.map { camera ->
            serviceScope.launch(Dispatchers.IO) {
                camera.setSetting(settingType, value)
            }
        }
        // Simplified - in production would collect actual results
        return true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DXO Camera Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains connection to DXO One cameras"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(result: MultiCaptureResult? = null): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val connectedCount = getConnectedCount()
        val title = "DXO Multi-Cam"
        val text = when {
            result != null -> {
                if (result.allSucceeded) {
                    "Captured ${result.succeededCount} photos (sync: ${result.syncVarianceMs}ms)"
                } else {
                    "Captured ${result.succeededCount}/${result.totalCameras} photos"
                }
            }
            connectedCount > 0 -> "$connectedCount camera(s) connected"
            else -> "No cameras connected"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(result: MultiCaptureResult? = null) {
        val notification = createNotification(result)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
