package com.dxoone.multicam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dxoone.multicam.domain.model.CameraEvent
import com.dxoone.multicam.service.CaptureMode
import com.dxoone.multicam.ui.components.CameraPreviewCard
import com.dxoone.multicam.ui.components.CaptureButton
import com.dxoone.multicam.ui.components.ConnectionStatusBar
import com.dxoone.multicam.ui.components.MicroUsbWarningBanner
import com.dxoone.multicam.ui.viewmodel.MultiCameraViewModel
import com.dxoone.multicam.usb.DxoOneConstants

/**
 * Main screen with multi-camera grid and capture controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MultiCameraViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showConnectMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Show error as snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.onEvent(CameraEvent.DismissError)
        }
    }

    // Show capture result
    LaunchedEffect(uiState.lastCaptureResult) {
        uiState.lastCaptureResult?.let { result ->
            val message = if (result.allSucceeded) {
                "Captured ${result.succeededCount} photos (sync: ${result.syncVarianceMs}ms)"
            } else {
                "Captured ${result.succeededCount}/${result.totalCameras} photos"
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DXO Multi-Cam") },
                actions = {
                    // Sync mode selector
                    var showModeMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { showModeMenu = true }) {
                            Text(
                                when (uiState.captureMode) {
                                    CaptureMode.PARALLEL -> "Parallel"
                                    CaptureMode.SEQUENTIAL -> "Sequential"
                                }
                            )
                        }
                        DropdownMenu(
                            expanded = showModeMenu,
                            onDismissRequest = { showModeMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Parallel (~50ms sync)") },
                                onClick = {
                                    viewModel.onEvent(CameraEvent.SetCaptureMode(CaptureMode.PARALLEL))
                                    showModeMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sequential (reliable)") },
                                onClick = {
                                    viewModel.onEvent(CameraEvent.SetCaptureMode(CaptureMode.SEQUENTIAL))
                                    showModeMenu = false
                                }
                            )
                        }
                    }

                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.connectedCount < DxoOneConstants.MAX_CAMERAS &&
                uiState.availableDeviceCount > uiState.connectedCount
            ) {
                Box {
                    FloatingActionButton(
                        onClick = { showConnectMenu = true }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add camera")
                    }

                    DropdownMenu(
                        expanded = showConnectMenu,
                        onDismissRequest = { showConnectMenu = false }
                    ) {
                        // Show available devices
                        for (i in 0 until (uiState.availableDeviceCount - uiState.connectedCount)) {
                            DropdownMenuItem(
                                text = { Text("Connect Camera ${i + 1}") },
                                onClick = {
                                    viewModel.onEvent(CameraEvent.ConnectDevice(i))
                                    showConnectMenu = false
                                }
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Warning banner (INV-CONS-002)
            MicroUsbWarningBanner()

            Spacer(modifier = Modifier.height(12.dp))

            // Connection status bar
            ConnectionStatusBar(
                connectedCount = uiState.connectedCount,
                availableCount = uiState.availableDeviceCount,
                isUsbHostSupported = uiState.isUsbHostSupported
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Camera grid
            if (uiState.cameras.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No cameras connected",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Connect up to 4 DXO One cameras via USB hub",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.onEvent(CameraEvent.RefreshDevices) }
                        ) {
                            Text("Refresh Devices")
                        }
                    }
                }
            } else {
                // Camera grid (2x2 layout)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = uiState.cameras,
                        key = { it.id }
                    ) { camera ->
                        CameraPreviewCard(
                            camera = camera,
                            onDisconnect = {
                                viewModel.onEvent(CameraEvent.DisconnectCamera(camera.id))
                            },
                            onRename = {
                                renameText = camera.nickname ?: ""
                                showRenameDialog = camera.id
                            },
                            onToggleSelection = {
                                viewModel.onEvent(CameraEvent.ToggleCameraSelection(camera.id))
                            }
                        )
                    }
                }

                // Selection controls (shown when any cameras are selected)
                if (uiState.selectedCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "${uiState.selectedCount}/${uiState.connectedCount} cameras selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        TextButton(
                            onClick = { viewModel.onEvent(CameraEvent.SelectAllCameras) }
                        ) {
                            Text("Select All")
                        }
                        TextButton(
                            onClick = { viewModel.onEvent(CameraEvent.DeselectAllCameras) }
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Capture button area
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                CaptureButton(
                    cameraCount = uiState.connectedCount,
                    selectedCount = uiState.selectedCount,
                    isCapturing = uiState.isCapturing,
                    enabled = uiState.canCapture,
                    onClick = { viewModel.onEvent(CameraEvent.CaptureAll) }
                )
            }

            // Last capture info
            uiState.lastCaptureResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Last capture: ${result.succeededCount}/${result.totalCameras} " +
                            "(${result.syncVarianceMs}ms variance, ${result.totalTimeMs}ms total)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }

    // Rename dialog
    if (showRenameDialog != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Camera") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Camera Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRenameDialog?.let { cameraId ->
                            viewModel.onEvent(CameraEvent.RenameCamera(cameraId, renameText))
                        }
                        showRenameDialog = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
