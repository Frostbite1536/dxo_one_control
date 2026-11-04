package com.dxoone.multicam

import android.app.Application
import com.dxoone.multicam.service.CameraManagerService
import com.dxoone.multicam.usb.UsbDeviceManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application class for DXO One Multi-Camera app.
 *
 * Initializes Hilt and owns the process-wide camera lifecycle:
 * - registers the USB receivers exactly once ([UsbDeviceManager.initialize]);
 * - starts [CameraManagerService] when the first camera connects, so connections survive
 *   the UI being backgrounded. The service stops itself when the last camera disconnects.
 */
@HiltAndroidApp
class DxoOneMultiCamApp : Application() {

    @Inject
    lateinit var usbDeviceManager: UsbDeviceManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate() // Hilt injects fields here
        usbDeviceManager.initialize()

        appScope.launch {
            usbDeviceManager.connectedCameras
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { anyConnected ->
                    if (anyConnected) CameraManagerService.start(this@DxoOneMultiCamApp)
                }
        }
    }
}
