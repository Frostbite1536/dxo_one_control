# DXO One Multi-Camera Android App

Native Android application for capturing simultaneous photos from up to 4 DXO One cameras.

## Features

- Connect up to 4 DXO One cameras via USB hub
- Photo capture on all cameras at once (best-effort software sync; timing spread not measured)
- Live preview (toggle in Settings)
- Camera settings (image format, shooting mode, exposure, focus, quality, self-timer) are saved and applied to each camera as it connects
- A foreground service keeps cameras connected while the app is in the background
- Battery level monitoring
- Camera renaming for easy identification
- Parallel or sequential capture modes

## Requirements

### Hardware

- Android device with USB Host (OTG) support
- Android 5.0 (API 21) or higher (`minSdk` 21, `targetSdk` 34)
- On Android 13+ the app asks for notification permission (needed for the foreground-service notification)
- Powered USB hub (4+ ports, external power required)
- USB OTG adapter (USB-C or microUSB to USB-A)
- Up to 4 DXO One cameras (microUSB variant only)

### Important Notes

- **Only microUSB DXO One cameras are supported** - the device filter matches vendor ID 0x2B8F only; the Lightning variant is not tested
- **Use a powered USB hub** - Cameras require adequate power supply
- Capture timing across cameras is best-effort; the spread has not been measured

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later (optional; the committed Gradle wrapper works from the command line)
- JDK 17
- Android SDK 34

### Build Steps

```bash
cd android-app
./gradlew assembleDebug
```

The APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Install on Device

```bash
./gradlew installDebug
```

### Unit Tests

```bash
./gradlew testDebugUnitTest
```

CI builds the app, runs the unit tests and runs lint.

## Architecture

Kotlin, Jetpack Compose UI with ViewModels, Hilt for dependency injection. Source is under `app/src/main/java/com/dxoone/multicam/`:

```
multicam/
├── DxoOneMultiCamApp.kt        # Application class
├── di/AppModule.kt             # Hilt module
├── data/
│   └── SettingsPreferences.kt  # DataStore-backed saved settings
├── domain/model/               # CameraUiState, CameraSettings, SettingsState
├── usb/
│   ├── DxoOneConstants.kt      # Protocol constants
│   ├── DxoOneUsbProtocol.kt    # USB communication
│   ├── DxoOneStreamDemux.kt    # Splits the bulk stream into RPC replies and video
│   ├── JsonRpcMessage.kt       # JSON-RPC messages
│   ├── CameraConnection.kt     # Per-camera wrapper
│   └── UsbDeviceManager.kt     # Device discovery and permission
├── service/
│   ├── CameraManagerService.kt # Foreground service
│   ├── CameraSettingsApplier.kt# Applies saved settings to a camera
│   └── SyncCaptureEngine.kt    # Parallel / sequential capture
└── ui/
    ├── MainActivity.kt
    ├── theme/
    ├── components/             # ConnectionStatusBar, CaptureButton, CameraPreviewCard
    ├── screens/                # MainScreen, SettingsScreen
    └── viewmodel/              # MultiCameraViewModel, SettingsViewModel
```

There is no database, repository layer, use-case layer or gallery; photos stay on the cameras. The only unit test so far is `DxoOneStreamDemuxTest` under `app/src/test/`.

The foreground service starts when the first camera connects and stops when none are connected.

## USB Protocol

The app communicates with DXO One cameras using:

- **Vendor ID**: 0x2B8F (DXO Labs)
- **Protocol**: JSON-RPC 2.0 over USB bulk transfers
- **Packet Size**: 512 bytes max

### Supported Commands

| Command | Description |
|---------|-------------|
| `dxo_photo_take` | Capture a photo |
| `dxo_camera_status_get` | Get camera status |
| `dxo_all_settings_get` | Get all settings |
| `dxo_setting_set` | Change a setting |
| `dxo_camera_mode_switch` | Switch camera mode |
| `dxo_tap_to_focus` | Focus at coordinates |

## Invariants

This app follows the dxo1control project invariants:

- **INV-SEC-001**: Only connect to verified DXO One vendor ID
- **INV-SEC-002**: User permission required for USB access
- **INV-MULTI-002**: Maximum 4 cameras supported
- **INV-MULTI-003**: Partial failure handling with detailed status
- **INV-MULTI-004**: Software sync is best-effort, not hardware-level; users are told so

## License

GPL 3.0 - See LICENSE file in root directory.

## Related Documentation

- [Multi-Camera App Plans](../docs/ANDROID_MULTI_CAMERA_APP.md)
- [Architecture](../docs/ARCHITECTURE.md)
- [Invariants](../docs/INVARIANTS.md)
