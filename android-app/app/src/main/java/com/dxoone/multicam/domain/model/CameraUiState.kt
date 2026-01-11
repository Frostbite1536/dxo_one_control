package com.dxoone.multicam.domain.model

import android.graphics.Bitmap
import com.dxoone.multicam.service.CaptureMode
import com.dxoone.multicam.service.MultiCaptureResult
import com.dxoone.multicam.usb.ConnectionState

/**
 * UI state for a single camera.
 */
data class CameraUiState(
    val id: String,
    val displayName: String,
    val nickname: String?,
    val connectionState: ConnectionState,
    val batteryLevel: Int?,
    val liveViewFrame: Bitmap?,
    val isSelected: Boolean = false
)

/**
 * Overall UI state for the multi-camera screen.
 */
data class MultiCameraUiState(
    val cameras: List<CameraUiState> = emptyList(),
    val availableDeviceCount: Int = 0,
    val isCapturing: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.PARALLEL,
    val lastCaptureResult: MultiCaptureResult? = null,
    val error: String? = null,
    val isUsbHostSupported: Boolean = true
) {
    val connectedCount: Int get() = cameras.count {
        it.connectionState == ConnectionState.CONNECTED
    }

    val canCapture: Boolean get() = connectedCount > 0 && !isCapturing

    val allCamerasReady: Boolean get() = cameras.all {
        it.connectionState == ConnectionState.CONNECTED
    }
}

/**
 * Events from the UI to the ViewModel.
 */
sealed class CameraEvent {
    data object RefreshDevices : CameraEvent()
    data class ConnectDevice(val deviceIndex: Int) : CameraEvent()
    data class DisconnectCamera(val cameraId: String) : CameraEvent()
    data object DisconnectAll : CameraEvent()
    data class RenameCamera(val cameraId: String, val newName: String) : CameraEvent()
    data object CaptureAll : CameraEvent()
    data class SetCaptureMode(val mode: CaptureMode) : CameraEvent()
    data object DismissError : CameraEvent()
    data object PreFocusAll : CameraEvent()
}

/**
 * Navigation destinations.
 */
sealed class Screen(val route: String) {
    data object Main : Screen("main")
    data object Settings : Screen("settings")
    data object Gallery : Screen("gallery")
    data object CaptureResult : Screen("capture_result/{sessionId}") {
        fun createRoute(sessionId: String) = "capture_result/$sessionId"
    }
}
