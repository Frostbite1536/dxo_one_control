package com.dxoone.multicam.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dxoone.multicam.domain.model.*
import com.dxoone.multicam.ui.viewmodel.SettingsViewModel

/**
 * Settings screen for configuring camera settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show messages
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(SettingsEvent.DismissError)
        }
    }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(SettingsEvent.DismissSuccess)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camera Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.onEvent(SettingsEvent.ResetToDefaults) }
                    ) {
                        Icon(Icons.Filled.Refresh, "Reset to defaults")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Apply to all toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Apply to all cameras",
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = uiState.applyToAllCameras,
                            onCheckedChange = {
                                viewModel.onEvent(SettingsEvent.SetApplyToAllCameras(it))
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Apply button
                    Button(
                        onClick = { viewModel.onEvent(SettingsEvent.ApplySettings) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isApplying && uiState.connectedCameraIds.isNotEmpty()
                    ) {
                        if (uiState.isApplying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Applying...")
                        } else {
                            Icon(Icons.Filled.Check, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            val count = uiState.connectedCameraIds.size
                            Text(
                                if (uiState.applyToAllCameras) {
                                    "Apply to All ($count cameras)"
                                } else {
                                    "Apply Settings"
                                }
                            )
                        }
                    }

                    if (uiState.connectedCameraIds.isEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "No cameras connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            val settings = uiState.currentSettings

            // Image Format Section
            SettingsSection(title = "Image Format") {
                SettingsDropdown(
                    label = "Format",
                    value = settings.imageFormat.displayName,
                    options = ImageFormat.entries.map { it.displayName },
                    onSelect = { index ->
                        viewModel.onEvent(SettingsEvent.SetImageFormat(ImageFormat.entries[index]))
                    }
                )

                Text(
                    text = when (settings.imageFormat) {
                        ImageFormat.JPEG_ONLY -> "Standard JPEG output only"
                        ImageFormat.RAW_PLUS_JPEG -> "DNG RAW file + JPEG preview"
                        ImageFormat.SUPERRAW_PLUS_JPEG -> "SuperRAW (4-frame TNR) + JPEG"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
            }

            HorizontalDivider()

            // Shooting Mode Section
            SettingsSection(title = "Shooting Mode") {
                SettingsDropdown(
                    label = "Mode",
                    value = settings.shootingMode.displayName,
                    options = ShootingMode.entries.map { it.displayName },
                    onSelect = { index ->
                        viewModel.onEvent(SettingsEvent.SetShootingMode(ShootingMode.entries[index]))
                    }
                )
            }

            HorizontalDivider()

            // Exposure Settings Section
            SettingsSection(title = "Exposure") {
                // ISO
                SettingsDropdown(
                    label = "ISO",
                    value = settings.iso.displayName,
                    options = IsoSetting.entries.map { it.displayName },
                    onSelect = { index ->
                        viewModel.onEvent(SettingsEvent.SetIso(IsoSetting.entries[index]))
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Aperture (enabled for aperture priority or manual)
                val apertureEnabled = settings.shootingMode == ShootingMode.APERTURE_PRIORITY ||
                        settings.shootingMode == ShootingMode.MANUAL
                SettingsDropdown(
                    label = "Aperture",
                    value = settings.aperture.displayName,
                    options = ApertureSetting.entries.map { it.displayName },
                    enabled = apertureEnabled,
                    onSelect = { index ->
                        viewModel.onEvent(SettingsEvent.SetAperture(ApertureSetting.entries[index]))
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Shutter Speed (enabled for shutter priority or manual)
                val shutterEnabled = settings.shootingMode == ShootingMode.SHUTTER_PRIORITY ||
                        settings.shootingMode == ShootingMode.MANUAL
                SettingsDropdown(
                    label = "Shutter Speed",
                    value = settings.exposureTime.displayName,
                    options = ExposureTimeSetting.entries.map { it.displayName },
                    enabled = shutterEnabled,
                    onSelect = { index ->
                        viewModel.onEvent(SettingsEvent.SetExposureTime(ExposureTimeSetting.entries[index]))
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // EV Compensation
                SettingsDropdown(
                    label = "Exposure Compensation",
                    value = settings.evBias.displayName,
                    options = EvBiasSetting.entries.map { it.displayName },
                    onSelect = { index ->
                        viewModel.onEvent(SettingsEvent.SetEvBias(EvBiasSetting.entries[index]))
                    }
                )
            }

            HorizontalDivider()

            // Focus Settings Section
            SettingsSection(title = "Focus") {
                SettingsDropdown(
                    label = "Focus Mode",
                    value = settings.focusMode.displayName,
                    options = FocusMode.entries.map { it.displayName },
                    onSelect = { index ->
                        viewModel.onEvent(SettingsEvent.SetFocusMode(FocusMode.entries[index]))
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                val afEnabled = settings.focusMode == FocusMode.AUTO_FOCUS
                SettingsDropdown(
                    label = "AF Mode",
                    value = settings.afMode.displayName,
                    options = AfMode.entries.map { it.displayName },
                    enabled = afEnabled,
                    onSelect = { index ->
                        viewModel.onEvent(SettingsEvent.SetAfMode(AfMode.entries[index]))
                    }
                )
            }

            HorizontalDivider()

            // Image Quality Section
            SettingsSection(title = "Image Quality") {
                SettingsDropdown(
                    label = "JPEG Quality",
                    value = settings.jpegQuality.displayName,
                    options = JpegQuality.entries.map { it.displayName },
                    onSelect = { index ->
                        viewModel.onEvent(SettingsEvent.SetJpegQuality(JpegQuality.entries[index]))
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsDropdown(
                    label = "White Balance Intensity",
                    value = settings.whiteBalanceIntensity.displayName,
                    options = WhiteBalanceIntensity.entries.map { it.displayName },
                    onSelect = { index ->
                        viewModel.onEvent(
                            SettingsEvent.SetWhiteBalanceIntensity(WhiteBalanceIntensity.entries[index])
                        )
                    }
                )
            }

            HorizontalDivider()

            // Capture Settings Section
            SettingsSection(title = "Capture") {
                SettingsDropdown(
                    label = "Self Timer",
                    value = settings.selfTimer.displayName,
                    options = SelfTimerSetting.entries.map { it.displayName },
                    onSelect = { index ->
                        viewModel.onEvent(SettingsEvent.SetSelfTimer(SelfTimerSetting.entries[index]))
                    }
                )
            }

            HorizontalDivider()

            // Live View Section
            SettingsSection(title = "Live View") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.onEvent(SettingsEvent.SetLiveViewEnabled(!settings.liveViewEnabled))
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Live View",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Stream camera preview to device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.liveViewEnabled,
                        onCheckedChange = {
                            viewModel.onEvent(SettingsEvent.SetLiveViewEnabled(it))
                        }
                    )
                }
            }

            // Bottom spacing
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            enabled = enabled,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )

        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    }
                )
            }
        }
    }
}
