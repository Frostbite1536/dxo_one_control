package com.dxoone.multicam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dxoone.multicam.R
import com.dxoone.multicam.ui.MainActivity
import com.dxoone.multicam.usb.CameraConnection
import com.dxoone.multicam.usb.DxoOneConstants
import com.dxoone.multicam.usb.UsbDeviceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service for managing DXO One camera connections.
 *
 * Keeps the process in the foreground while at least one camera is connected, so USB
 * connections survive the screen turning off or the app being backgrounded.
 *
 * Lifecycle / ownership:
 * - Camera connections and the USB receivers are owned by the process-wide
 *   [UsbDeviceManager] (initialized in DxoOneMultiCamApp), not by this service or any UI.
 * - DxoOneMultiCamApp starts this service when the first camera connects ([start]).
 * - The service stops itself when the last camera disconnects. It is START_NOT_STICKY:
 *   after process death there are no USB connections to keep alive.
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

    /** Set once startForeground() succeeded; main-thread only. */
    private var isForeground = false

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

        /**
         * Start the service in the foreground. Call when a camera has connected (the app
         * then holds USB permission, which Android 14 requires for connectedDevice FGS).
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CameraManagerService::class.java)
                )
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the
                // background on Android 12+. Cameras keep working while the app is visible.
                Log.w(TAG, "Could not start foreground service", e)
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): CameraManagerService = this@CameraManagerService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        _isRunning.value = true

        // Keep the notification current and stop once no camera is connected. Never stop
        // before startForeground() has run: stopping a service started with
        // startForegroundService() before that crashes the app.
        serviceScope.launch {
            usbDeviceManager.connectedCameras.collect { cameras ->
                if (!isForeground) return@collect
                if (cameras.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    updateNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must be called promptly after startForegroundService().
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            isForeground = true
        } catch (e: Exception) {
            // Android 14 throws SecurityException if the connectedDevice prerequisites
            // (USB permission) are not met.
            Log.w(TAG, "startForeground failed; stopping service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (getConnectedCount() == 0) {
            stopForegroundCompat()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        // Do not call usbDeviceManager.cleanup(): the manager is process-wide and the UI
        // may still be using the cameras.
        serviceScope.cancel()
    }

    private fun stopForegroundCompat() {
        isForeground = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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

        val result = syncCaptureEngine.capture(cameras, _captureMode.value)

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
    suspend fun applySettingToAll(settingType: String, value: String): Boolean = coroutineScope {
        getConnectedCameras()
            .map { camera -> async { camera.setSetting(settingType, value) } }
            .awaitAll()
            .all { it }
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
            result != null -> result.summaryText()
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
        // getSystemService(Class) is API 23+; minSdk is 21.
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
