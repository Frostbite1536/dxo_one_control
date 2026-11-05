package com.dxoone.multicam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dxoone.multicam.domain.model.Screen
import com.dxoone.multicam.ui.screens.MainScreen
import com.dxoone.multicam.ui.screens.SettingsScreen
import com.dxoone.multicam.ui.theme.DxoOneMultiCamTheme
import com.dxoone.multicam.usb.UsbDeviceManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main Activity for the DXO One Multi-Camera app.
 *
 * Handles USB device attachment intents and hosts the Compose navigation. Declared
 * singleTop in the manifest, so a USB attach while the app is open arrives here via
 * onNewIntent instead of creating a second Activity (and ViewModel).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var usbDeviceManager: UsbDeviceManager

    // Android 13+: needed for the camera-connection service notification to be visible.
    // Denial is fine: the foreground service still runs, its notification is just hidden.
    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle USB device attachment intent
        handleUsbIntent(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            DxoOneMultiCamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = Screen.Main.route
                    ) {
                        composable(Screen.Main.route) {
                            MainScreen(
                                onNavigateToSettings = {
                                    navController.navigate(Screen.Settings.route)
                                }
                            )
                        }

                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    /**
     * Handle USB device attachment/detachment intents.
     */
    private fun handleUsbIntent(intent: Intent?) {
        when (intent?.action) {
            // The attach that launched or resumed us may predate the receiver seeing it.
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> usbDeviceManager.refreshDevices()
            // Detach is handled by UsbDeviceManager's receiver.
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

