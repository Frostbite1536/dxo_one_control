# DXO One Multi-Camera Android App

Native Android application for capturing simultaneous photos from up to 4 DXO One cameras.

## Features

- Connect up to 4 DXO One cameras via USB hub
- Synchronized photo capture with ~50ms variance
- Live preview from all cameras
- Battery level monitoring
- Camera renaming for easy identification
- Parallel or sequential capture modes

## Requirements

### Hardware

- Android device with USB Host (OTG) support
- Android 5.0 (Lollipop) or higher
- Powered USB hub (4+ ports, external power required)
- USB OTG adapter (USB-C or microUSB to USB-A)
- Up to 4 DXO One cameras (microUSB variant only)

### Important Notes

- **Only microUSB DXO One cameras are supported** - Lightning variant is not tested
- **Use a powered USB hub** - Cameras require adequate power supply
- Expected sync variance: 20-65ms (typically ~50ms)

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
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

## Architecture

The app follows Clean Architecture with MVVM pattern:

```
app/
├── di/              # Hilt dependency injection
├── data/            # Data layer (repositories, database)
├── domain/          # Business logic (use cases, models)
├── usb/             # USB protocol layer
│   ├── DxoOneConstants.kt    # Protocol constants
│   ├── DxoOneUsbProtocol.kt  # USB communication
│   ├── JsonRpcMessage.kt     # JSON-RPC messages
│   ├── CameraConnection.kt   # Camera wrapper
│   └── UsbDeviceManager.kt   # Device discovery
├── service/         # Background services
│   ├── CameraManagerService.kt
│   └── SyncCaptureEngine.kt
└── ui/              # Presentation layer (Compose)
    ├── theme/
    ├── components/
    ├── screens/
    └── viewmodel/
```

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
- **INV-MULTI-004**: ~50ms sync variance documented

## License

GPL 3.0 - See LICENSE file in root directory.

## Related Documentation

- [Multi-Camera App Plans](../docs/ANDROID_MULTI_CAMERA_APP.md)
- [Architecture](../docs/ARCHITECTURE.md)
- [Invariants](../docs/INVARIANTS.md)
