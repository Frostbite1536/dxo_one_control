# Android Multi-Camera App: 4x DXO One Simultaneous Capture

## Implementation Status

**Status: PARTIALLY IMPLEMENTED.** Multi-camera connection, live view, settings control and synchronized capture exist in `android-app/` (build instructions: [Android App README](../android-app/README.md)). Image download from the cameras, a gallery, and thumbnails/sharing are **not** implemented. The plan sections below are the original design; where they differ from the code, the tables, "Project Structure (implemented)" and the checklists are authoritative. Nothing here has been run against real cameras.

### Components

| Component | Status | Location |
|-----------|--------|----------|
| Stream demux (packet routing, JPEG reassembly) | Done, unit-tested | `usb/DxoOneStreamDemux.kt`, `app/src/test/.../DxoOneStreamDemuxTest.kt` |
| USB protocol (reader thread, serialized RPC, timeouts) | Done | `usb/DxoOneUsbProtocol.kt`, `usb/JsonRpcMessage.kt` (incl. `DxoOneMethods` allowlist) |
| Camera connection (`ConnectionState`, live view, status) | Done | `usb/CameraConnection.kt` |
| Device manager (process-wide, permission flow, max 4) | Done | `usb/UsbDeviceManager.kt` |
| Sync capture engine (parallel / sequential) | Done; sync variance is not a true shutter-time measure | `service/SyncCaptureEngine.kt` |
| Foreground service | Done | `service/CameraManagerService.kt` |
| Settings applier (checks each result; used on connect) | Done | `service/CameraSettingsApplier.kt` |
| Settings persistence | Done (DataStore) | `data/SettingsPreferences.kt` |
| Compose UI (main grid, settings) | Done | `ui/MainActivity.kt`, `ui/screens/`, `ui/components/`, `ui/viewmodel/` |
| Image download | Not implemented | - |
| Gallery | Placeholder composable only (`GalleryScreen` in `MainActivity.kt`) | - |

Runtime lifecycle: `DxoOneMultiCamApp` initializes the process-wide `UsbDeviceManager` (idempotent) and starts `CameraManagerService` (foreground, `connectedDevice`, `START_NOT_STICKY`) when the first camera connects; the service stops itself when none remain. `MainActivity` is `singleTop`. See `docs/STATE_MACHINES.md` sections 1, 5 and 10.

---

## Overview

This document provides comprehensive plans for developing a native Android application capable of connecting to **4 DXO One cameras simultaneously** and capturing synchronized photos from all devices.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Hardware Requirements](#hardware-requirements)
3. [System Architecture](#system-architecture)
4. [USB Communication Protocol](#usb-communication-protocol)
5. [Android App Architecture](#android-app-architecture)
6. [Synchronized Capture System](#synchronized-capture-system)
7. [User Interface Design](#user-interface-design)
8. [Implementation Roadmap](#implementation-roadmap)
9. [Risk Analysis](#risk-analysis)

---

## Executive Summary

### Goal
Build a native Android application that:
- Connects to 4 DXO One cameras via USB simultaneously
- Provides live preview from all cameras
- Captures synchronized photos (target <100ms variance; unmeasured)
- Downloads and manages captured images (**planned, not implemented**)

### Key Challenges
1. **USB Host Mode**: Android must act as USB host for 4 devices
2. **Power Delivery**: 4 cameras require adequate power supply
3. **Synchronization**: Minimize capture timing variance
4. **Bandwidth**: Handle 4 concurrent USB data streams

---

## Hardware Requirements

### Required Components

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        HARDWARE SETUP DIAGRAM                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│     ┌──────────────┐                                                        │
│     │   Android    │                                                        │
│     │   Device     │                                                        │
│     │  (USB-C/     │                                                        │
│     │   microUSB)  │                                                        │
│     └──────┬───────┘                                                        │
│            │                                                                │
│            │ USB OTG Cable                                                  │
│            │                                                                │
│     ┌──────▼───────┐                                                        │
│     │  Powered     │◄──────── External 5V Power Supply (≥3A)               │
│     │  USB Hub     │          (Critical for camera power)                   │
│     │  (4+ ports)  │                                                        │
│     └──────────────┘                                                        │
│       │    │    │    │                                                      │
│       │    │    │    │  USB-A to microUSB cables                           │
│       │    │    │    │                                                      │
│    ┌──▼─┐┌─▼──┐┌▼───┐┌▼───┐                                                │
│    │CAM1││CAM2││CAM3││CAM4│                                                 │
│    │DXO ││DXO ││DXO ││DXO │                                                 │
│    │ONE ││ONE ││ONE ││ONE │                                                 │
│    └────┘└────┘└────┘└────┘                                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Component List

| Component | Specification | Quantity | Notes |
|-----------|---------------|----------|-------|
| **Android Device** | Android 5.0+, USB Host support | 1 | Must support OTG |
| **USB OTG Adapter** | USB-C or microUSB to USB-A female | 1 | Match phone port |
| **Powered USB Hub** | USB 3.0, 4+ ports, external power | 1 | **Must be powered** |
| **USB Hub Power Supply** | 5V, ≥3A (15W minimum) | 1 | For camera power |
| **USB-A to microUSB Cables** | Data-capable, ≤1m length | 4 | One per camera |
| **DXO One Cameras** | microUSB variant (not Lightning) | 4 | Firmware compatible |

### Recommended Hardware

#### USB Hubs (Tested Compatible)
1. **Anker 4-Port USB 3.0 Hub** (powered) - Recommended
2. **Sabrent 4-Port USB 3.0 Hub** with power adapter
3. **Amazon Basics 4-Port USB 3.0 Hub** (powered model)

#### Android Devices (Recommended)
- Samsung Galaxy S21/S22/S23 series
- Google Pixel 6/7/8 series
- OnePlus 9/10/11 series
- Any device with USB Host (OTG) support

### Power Budget Analysis

```
┌─────────────────────────────────────────────────────────────────┐
│                    POWER REQUIREMENTS                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   DXO One Power Consumption:                                    │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │ Mode              │ Current Draw │ × 4 Cameras          │  │
│   ├───────────────────┼──────────────┼──────────────────────┤  │
│   │ Idle/Connected    │ ~100mA       │ 400mA                │  │
│   │ Live View         │ ~300mA       │ 1.2A                 │  │
│   │ Photo Capture     │ ~500mA peak  │ 2.0A peak            │  │
│   │ File Transfer     │ ~250mA       │ 1.0A                 │  │
│   └─────────────────────────────────────────────────────────┘  │
│                                                                 │
│   Minimum Hub Power Supply: 5V @ 3A (15W)                      │
│   Recommended: 5V @ 4A (20W) for headroom                      │
│                                                                 │
│   ⚠️  WARNING: Unpowered hubs will cause connection failures   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## System Architecture

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SYSTEM ARCHITECTURE                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        ANDROID APPLICATION                           │   │
│  │  ┌───────────────────────────────────────────────────────────────┐  │   │
│  │  │                      UI LAYER (Jetpack Compose)               │  │   │
│  │  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────┐ │  │   │
│  │  │  │ Camera   │ │ Preview  │ │ Capture  │ │ Gallery          │ │  │   │
│  │  │  │ Grid View│ │ Controls │ │ Button   │ │ View             │ │  │   │
│  │  │  └──────────┘ └──────────┘ └──────────┘ └──────────────────┘ │  │   │
│  │  └───────────────────────────────────────────────────────────────┘  │   │
│  │                              │                                       │   │
│  │  ┌───────────────────────────▼───────────────────────────────────┐  │   │
│  │  │                    VIEWMODEL LAYER                            │  │   │
│  │  │  ┌─────────────────┐  ┌─────────────────┐  ┌───────────────┐ │  │   │
│  │  │  │ MultiCamera     │  │ CaptureState    │  │ Settings      │ │  │   │
│  │  │  │ ViewModel       │  │ ViewModel       │  │ ViewModel     │ │  │   │
│  │  │  └─────────────────┘  └─────────────────┘  └───────────────┘ │  │   │
│  │  └───────────────────────────────────────────────────────────────┘  │   │
│  │                              │                                       │   │
│  │  ┌───────────────────────────▼───────────────────────────────────┐  │   │
│  │  │                    SERVICE LAYER                              │  │   │
│  │  │  ┌─────────────────────────────────────────────────────────┐ │  │   │
│  │  │  │              CameraManagerService                       │ │  │   │
│  │  │  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐       │ │  │   │
│  │  │  │  │Camera 1 │ │Camera 2 │ │Camera 3 │ │Camera 4 │       │ │  │   │
│  │  │  │  │Handler  │ │Handler  │ │Handler  │ │Handler  │       │ │  │   │
│  │  │  │  └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘       │ │  │   │
│  │  │  │       │           │           │           │             │ │  │   │
│  │  │  │       └───────────┴─────┬─────┴───────────┘             │ │  │   │
│  │  │  │                         │                               │ │  │   │
│  │  │  │              ┌──────────▼──────────┐                    │ │  │   │
│  │  │  │              │ SyncCaptureEngine   │                    │ │  │   │
│  │  │  │              │ (Coroutines)        │                    │ │  │   │
│  │  │  │              └─────────────────────┘                    │ │  │   │
│  │  │  └─────────────────────────────────────────────────────────┘ │  │   │
│  │  └───────────────────────────────────────────────────────────────┘  │   │
│  │                              │                                       │   │
│  │  ┌───────────────────────────▼───────────────────────────────────┐  │   │
│  │  │                    USB PROTOCOL LAYER                         │  │   │
│  │  │  ┌─────────────────────────────────────────────────────────┐ │  │   │
│  │  │  │              DxoOneUsbProtocol                          │ │  │   │
│  │  │  │  • JSON-RPC 2.0 message encoding/decoding              │ │  │   │
│  │  │  │  • Binary framing (8-byte header + payload)            │ │  │   │
│  │  │  │  • Command queue management                            │ │  │   │
│  │  │  │  • Live view JPEG stream parsing                       │ │  │   │
│  │  │  └─────────────────────────────────────────────────────────┘ │  │   │
│  │  └───────────────────────────────────────────────────────────────┘  │   │
│  │                              │                                       │   │
│  │  ┌───────────────────────────▼───────────────────────────────────┐  │   │
│  │  │                    ANDROID USB HOST API                       │  │   │
│  │  │  ┌─────────────────────────────────────────────────────────┐ │  │   │
│  │  │  │ UsbManager │ UsbDevice │ UsbDeviceConnection │ UsbEndpoint│ │  │   │
│  │  │  └─────────────────────────────────────────────────────────┘ │  │   │
│  │  └───────────────────────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

> The diagram above is the original design. In the code there is no `CaptureStateViewModel` (only `MultiCameraViewModel` and `SettingsViewModel`), the cameras are owned by `UsbDeviceManager` rather than by the service, and the protocol layer is `DxoOneStreamDemux` + `DxoOneUsbProtocol` with a dedicated reader thread per camera (no command queue beyond a per-camera request mutex).

### Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DATA FLOW DIAGRAM                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  USER ACTION                                                                │
│      │                                                                      │
│      ▼                                                                      │
│  ┌────────────┐    ┌──────────────┐    ┌─────────────────────────────────┐ │
│  │  Capture   │───►│ ViewModel    │───►│ CameraManagerService            │ │
│  │  Button    │    │ dispatches   │    │                                 │ │
│  └────────────┘    │ capture      │    │  ┌─────────────────────────┐   │ │
│                    │ intent       │    │  │ SyncCaptureEngine       │   │ │
│                    └──────────────┘    │  │                         │   │ │
│                                        │  │  1. Prepare all cameras │   │ │
│                                        │  │  2. Send capture cmds   │   │ │
│                                        │  │     in parallel         │   │ │
│                                        │  │  3. Await confirmations │   │ │
│                                        │  │  4. Download images     │   │ │
│                                        │  └────────────┬────────────┘   │ │
│                                        └───────────────┼────────────────┘ │
│                                                        │                   │
│                                                        ▼                   │
│  ┌───────────────────────────────────────────────────────────────────────┐│
│  │                    PARALLEL USB COMMANDS                              ││
│  │                                                                       ││
│  │  Camera 1          Camera 2          Camera 3          Camera 4      ││
│  │  ┌──────────┐      ┌──────────┐      ┌──────────┐      ┌──────────┐  ││
│  │  │ USB Bulk │      │ USB Bulk │      │ USB Bulk │      │ USB Bulk │  ││
│  │  │ Transfer │      │ Transfer │      │ Transfer │      │ Transfer │  ││
│  │  └────┬─────┘      └────┬─────┘      └────┬─────┘      └────┬─────┘  ││
│  │       │                 │                 │                 │        ││
│  │       ▼                 ▼                 ▼                 ▼        ││
│  │  ┌──────────┐      ┌──────────┐      ┌──────────┐      ┌──────────┐  ││
│  │  │ JSON-RPC │      │ JSON-RPC │      │ JSON-RPC │      │ JSON-RPC │  ││
│  │  │ Response │      │ Response │      │ Response │      │ Response │  ││
│  │  └────┬─────┘      └────┬─────┘      └────┬─────┘      └────┬─────┘  ││
│  │       │                 │                 │                 │        ││
│  │       └─────────────────┴────────┬────────┴─────────────────┘        ││
│  │                                  │                                    ││
│  │                                  ▼                                    ││
│  │                     ┌─────────────────────────┐                       ││
│  │                     │ Aggregate Results      │                       ││
│  │                     │ • Success/failure      │                       ││
│  │                     │ • Timing metadata      │                       ││
│  │                     │ • File references      │                       ││
│  │                     └────────────┬────────────┘                       ││
│  │                                  │                                    ││
│  └──────────────────────────────────┼────────────────────────────────────┘│
│                                     │                                     │
│                                     ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────────┐ │
│  │                    IMAGE DOWNLOAD (Sequential)                       │ │
│  │                                                                      │ │
│  │  Camera 1 ──► Download JPEG/DNG ──► Save to Gallery                 │ │
│  │  Camera 2 ──► Download JPEG/DNG ──► Save to Gallery                 │ │
│  │  Camera 3 ──► Download JPEG/DNG ──► Save to Gallery                 │ │
│  │  Camera 4 ──► Download JPEG/DNG ──► Save to Gallery                 │ │
│  │                                                                      │ │
│  └─────────────────────────────────────────────────────────────────────┘ │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## USB Communication Protocol

### DXO One USB Identification

```kotlin
object DxoOneUsb {
    const val VENDOR_ID = 0x2B8F    // DXO Labs; the only identity check (no product ID)

    // USB interface configuration (DxoOneConstants.kt)
    const val INTERFACE_CONTROL = 0      // bulk endpoints
    const val INTERFACE_DATA = 1         // claimed; alternate setting 1 is selected
    const val ALTERNATE_SETTING = 1

    // Endpoint configuration
    const val ENDPOINT_IN = 0x81         // Bulk IN
    const val ENDPOINT_OUT = 0x02        // Bulk OUT
    const val MAX_PACKET_SIZE = 512      // bytes
}
```

### JSON-RPC Protocol Implementation

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      USB MESSAGE FORMAT                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  OUTGOING MESSAGE (Android → Camera)                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Byte   │ 0-7        │ 8-9        │ 10-31      │ 32-N       │ N+1   │   │
│  │ Field  │ RPC_HEADER │ MSG_SIZE   │ TRAILER    │ JSON-RPC   │ NULL  │   │
│  │ Value  │ 0x00...    │ little-end │ 0x00...    │ payload    │ 0x00  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  RPC_HEADER (8 bytes): Fixed header identifying RPC message                 │
│  MSG_SIZE (2 bytes): Size of payload in little-endian (max 0xFFFF)                       │
│  TRAILER (22 bytes): Padding/metadata                                       │
│  JSON-RPC: The actual command payload                                       │
│  NULL: Null terminator                                                      │
│                                                                             │
│  ───────────────────────────────────────────────────────────────────────   │
│                                                                             │
│  JSON-RPC COMMAND EXAMPLES:                                                 │
│                                                                             │
│  Take Photo:                                                                │
│  {                                                                          │
│    "jsonrpc": "2.0",                                                        │
│    "method": "dxo_photo_take",                                              │
│    "params": {},                                                            │
│    "id": 1                                                                  │
│  }                                                                          │
│                                                                             │
│  Get Camera Status:                                                         │
│  {                                                                          │
│    "jsonrpc": "2.0",                                                        │
│    "method": "dxo_camera_status_get",                                       │
│    "params": {},                                                            │
│    "id": 2                                                                  │
│  }                                                                          │
│                                                                             │
│  Set Setting:                                                               │
│  {                                                                          │
│    "jsonrpc": "2.0",                                                        │
│    "method": "dxo_setting_set",                                             │
│    "params": {"type": "iso", "param": "100"},                         │
│    "id": 3                                                                  │
│  }                                                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Available Commands

| Command | Description | Parameters |
|---------|-------------|------------|
| `dxo_photo_take` | Capture a photo | None |
| `dxo_camera_status_get` | Get camera status | None |
| `dxo_all_settings_get` | Get all settings | None |
| `dxo_setting_set` | Change a setting (camera first sends a `dxo_setting_applied` notification, then the reply) | type, param |
| `dxo_camera_mode_switch` | Switch camera mode (`view` starts live view) | param |
| `dxo_tap_to_focus` | Focus at point | x, y |
| `dxo_fs_last_file_get` | Get last captured file | None |
| `dxo_camera_poweroff` | Power off camera | None |

---

## Android App Architecture

### Technology Stack

| Layer | Technology | Rationale |
|-------|------------|-----------|
| **UI** | Jetpack Compose | Modern, declarative, efficient |
| **Architecture** | MVVM (no repository / use-case layer) | ViewModels talk to `UsbDeviceManager`, `SyncCaptureEngine` and `CameraSettingsApplier` directly |
| **Async** | Kotlin Coroutines + Flow | Non-blocking USB operations |
| **DI** | Hilt | Standard Android DI |
| **USB** | Android USB Host API | Direct hardware access |
| **Storage** | DataStore (preferences) | Settings persistence. Room is declared in `build.gradle.kts` but unused; MediaStore is not used (no image download yet) |
| **Image** | Coil | Efficient image loading |

### Project Structure (implemented)

```
app/src/main/java/com/dxoone/multicam/
├── DxoOneMultiCamApp.kt               # Application: initializes UsbDeviceManager, starts the service
├── di/AppModule.kt                    # Hilt module
├── data/SettingsPreferences.kt        # DataStore-backed settings
├── domain/model/
│   ├── CameraSettings.kt
│   ├── CameraUiState.kt
│   └── SettingsState.kt
├── usb/
│   ├── DxoOneConstants.kt
│   ├── DxoOneStreamDemux.kt           # Packet routing / JPEG reassembly
│   ├── DxoOneUsbProtocol.kt           # Reader thread, request mutex, timeouts
│   ├── JsonRpcMessage.kt              # JSON-RPC classes + DxoOneMethods allowlist
│   ├── CameraConnection.kt            # Per-camera connection, ConnectionState, live view
│   └── UsbDeviceManager.kt            # Process-wide device manager
├── service/
│   ├── CameraManagerService.kt        # Foreground service
│   ├── CameraSettingsApplier.kt
│   └── SyncCaptureEngine.kt
└── ui/
    ├── MainActivity.kt                # singleTop; nav host; placeholder GalleryScreen
    ├── theme/Theme.kt
    ├── components/                    # CameraPreviewCard, CaptureButton, ConnectionStatusBar
    ├── screens/                       # MainScreen, SettingsScreen
    └── viewmodel/                     # MultiCameraViewModel, SettingsViewModel
app/src/test/java/com/dxoone/multicam/usb/DxoOneStreamDemuxTest.kt
app/src/main/res/xml/device_filter.xml
```

Not present (were in the original plan): `UsbModule.kt`, `data/repository/`, Room database/DAO, `domain/usecase/`, `LiveViewStream.kt`, a real `GalleryScreen.kt`, `CaptureViewModel.kt`.

### USB Device Filter (device_filter.xml)

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device vendor-id="11151" />
    <!-- 0x2B8F = 11151 decimal (DXO Vendor ID); vendor only, product ID varies -->
</resources>
```

### AndroidManifest.xml Configuration

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- USB Host feature -->
    <uses-feature android:name="android.hardware.usb.host" />

    <!-- Permissions -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <!-- USB permission is a runtime UsbManager grant, not a manifest permission.
         Storage permissions are also declared but unused until image download exists. -->

    <application ...>
        <activity android:name=".ui.MainActivity"
                  android:exported="true"
                  android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <!-- USB device attachment -->
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/device_filter" />
        </activity>

        <!-- Background camera service -->
        <service android:name=".service.CameraManagerService"
                 android:exported="false"
                 android:foregroundServiceType="connectedDevice" />
    </application>
</manifest>
```

---

## Synchronized Capture System

### Synchronization Strategy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SYNCHRONIZED CAPTURE SEQUENCE                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Timeline (milliseconds)                                                    │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  T+0ms    ┌─────────────────────────────────────────────────────────────┐  │
│           │ PHASE 1: PREPARATION                                        │  │
│           │ • Verify all 4 cameras connected                            │  │
│           │ • Pre-focus all cameras (if auto-focus enabled)            │  │
│           │ • Set identical capture settings                            │  │
│           │ • Allocate command buffers                                  │  │
│           └─────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  T+100ms  ┌─────────────────────────────────────────────────────────────┐  │
│           │ PHASE 2: COMMAND QUEUING                                    │  │
│           │ • Build JSON-RPC capture commands for all cameras           │  │
│           │ • Create coroutine jobs for parallel execution              │  │
│           └─────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  T+110ms  ┌─────────────────────────────────────────────────────────────┐  │
│           │ PHASE 3: SIMULTANEOUS DISPATCH                              │  │
│           │                                                             │  │
│           │     ┌────────────────────────────────────────────────┐     │  │
│           │     │      async { camera1.sendCaptureCommand() }    │     │  │
│           │     │      async { camera2.sendCaptureCommand() }    │     │  │
│           │     │      async { camera3.sendCaptureCommand() }    │     │  │
│           │     │      async { camera4.sendCaptureCommand() }    │     │  │
│           │     │                      │                          │     │  │
│           │     │            awaitAll()                           │     │  │
│           │     └────────────────────────────────────────────────┘     │  │
│           │                                                             │  │
│           │ Variance: ~10-50ms due to USB scheduling                   │  │
│           └─────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  T+150ms  ┌─────────────────────────────────────────────────────────────┐  │
│           │ PHASE 4: CAPTURE CONFIRMATION                               │  │
│           │ • Wait for acknowledgment from each camera                  │  │
│           │ • Record actual capture timestamps                          │  │
│           │ • Handle any capture failures                               │  │
│           └─────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  T+500ms+ ┌─────────────────────────────────────────────────────────────┐  │
│           │ PHASE 5: IMAGE DOWNLOAD (Sequential)                        │  │
│           │ • Download JPEG/DNG from each camera                        │  │
│           │ • Save with session ID and camera ID                        │  │
│           │ • Update gallery                                            │  │
│           └─────────────────────────────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Kotlin Implementation

> Illustrative sketch of the original plan. The real engine is `service/SyncCaptureEngine.kt`: it takes the camera list as an argument (`captureAllParallel` / `captureAllSequential`), calls `CameraConnection.takePhoto()`, and returns `MultiCaptureResult`. Its `syncVarianceMs` is the spread of per-camera command start times, not of shutter times.

```kotlin
class SyncCaptureEngine(
    private val cameraConnections: List<CameraConnection>
) {

    /**
     * Capture photos from all connected cameras simultaneously.
     *
     * @return CaptureResult containing success/failure status for each camera
     */
    suspend fun captureAll(): CaptureResult = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        // Phase 1: Validate all cameras ready
        val readyCameras = cameraConnections.filter { it.isReady() }
        if (readyCameras.size < cameraConnections.size) {
            return@withContext CaptureResult.PartialFailure(
                sessionId = sessionId,
                message = "Only ${readyCameras.size}/${cameraConnections.size} cameras ready"
            )
        }

        // Phase 2: Pre-focus all cameras in parallel (optional)
        readyCameras.map { camera ->
            async { camera.preFocus() }
        }.awaitAll()

        // Phase 3: Send capture commands simultaneously
        val captureJobs = readyCameras.map { camera ->
            async {
                try {
                    val timestamp = System.currentTimeMillis()
                    camera.sendCaptureCommand()
                    CameraCaptureStatus.Success(
                        cameraId = camera.id,
                        timestamp = timestamp
                    )
                } catch (e: Exception) {
                    CameraCaptureStatus.Failure(
                        cameraId = camera.id,
                        error = e.message ?: "Unknown error"
                    )
                }
            }
        }

        // Phase 4: Await all results
        val results = captureJobs.awaitAll()
        val endTime = System.currentTimeMillis()

        // Calculate synchronization variance
        val successTimestamps = results
            .filterIsInstance<CameraCaptureStatus.Success>()
            .map { it.timestamp }
        val variance = if (successTimestamps.size > 1) {
            successTimestamps.max() - successTimestamps.min()
        } else 0L

        CaptureResult.Complete(
            sessionId = sessionId,
            cameraResults = results,
            totalTimeMs = endTime - startTime,
            syncVarianceMs = variance
        )
    }
}
```

### Synchronization Variance Analysis

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    EXPECTED SYNCHRONIZATION VARIANCE                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Factors Contributing to Variance:                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Factor                    │ Typical Delay │ Notes                   │   │
│  ├───────────────────────────┼───────────────┼─────────────────────────┤   │
│  │ USB scheduling            │ 1-10ms        │ OS USB stack latency    │   │
│  │ Hub arbitration           │ 1-5ms         │ Per-port scheduling     │   │
│  │ Camera processing         │ 5-20ms        │ Command parsing         │   │
│  │ Shutter actuation         │ 10-30ms       │ Mechanical variance     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Expected Total Variance: unmeasured; 20-65ms is a design estimate │
│                                                                             │
│  ───────────────────────────────────────────────────────────────────────   │
│                                                                             │
│  Acceptable Use Cases by Variance:                                          │
│                                                                             │
│  <50ms  : Product photography, portraits, landscapes, architecture         │
│  <100ms : General multi-angle capture, documentation                       │
│  <200ms : Time-lapse sequences, comparison shots                           │
│  >200ms : NOT RECOMMENDED for motion-sensitive applications                │
│                                                                             │
│  ⚠️  For sub-10ms sync: Hardware trigger required (not supported)          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## User Interface Design

### Main Screen Layout

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         MAIN SCREEN WIREFRAME                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ ≡  DXO Multi-Cam                                    ⚙️  📁          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────┐   ┌─────────────────────────┐                 │
│  │                         │   │                         │                 │
│  │      CAMERA 1           │   │      CAMERA 2           │                 │
│  │     [Live View]         │   │     [Live View]         │                 │
│  │                         │   │                         │                 │
│  │  ─────────────────────  │   │  ─────────────────────  │                 │
│  │  📷 Front Left          │   │  📷 Front Right         │                 │
│  │  🔋 87%  ●  Connected   │   │  🔋 92%  ●  Connected   │                 │
│  └─────────────────────────┘   └─────────────────────────┘                 │
│                                                                             │
│  ┌─────────────────────────┐   ┌─────────────────────────┐                 │
│  │                         │   │                         │                 │
│  │      CAMERA 3           │   │      CAMERA 4           │                 │
│  │     [Live View]         │   │     [Live View]         │                 │
│  │                         │   │                         │                 │
│  │  ─────────────────────  │   │  ─────────────────────  │                 │
│  │  📷 Back Left           │   │  📷 Back Right          │                 │
│  │  🔋 78%  ●  Connected   │   │  🔋 85%  ●  Connected   │                 │
│  └─────────────────────────┘   └─────────────────────────┘                 │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  Settings Bar                                                       │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐               │   │
│  │  │ISO: AUTO│  │ f/1.8   │  │ 1/125s  │  │ RAW+JPG │               │   │
│  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│                        ┌─────────────────────┐                              │
│                        │                     │                              │
│                        │    ◉ CAPTURE ALL   │                              │
│                        │     (4 cameras)     │                              │
│                        │                     │                              │
│                        └─────────────────────┘                              │
│                                                                             │
│  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐               │
│  │ Last Shot │  │ Last Shot │  │ Last Shot │  │ Last Shot │               │
│  │  [thumb]  │  │  [thumb]  │  │  [thumb]  │  │  [thumb]  │               │
│  │   Cam 1   │  │   Cam 2   │  │   Cam 3   │  │   Cam 4   │               │
│  └───────────┘  └───────────┘  └───────────┘  └───────────┘               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Connection Screen

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      CONNECTION SCREEN WIREFRAME                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ ≡  Connect Cameras                                           ⚙️    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│                                                                             │
│                    DETECTED DXO ONE CAMERAS                                 │
│                    ─────────────────────────                                │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  ┌─────┐                                                           │   │
│  │  │ 📷  │   DXO One #1 (Serial: DX0A2F3B)                          │   │
│  │  └─────┘   ● Connected                           [Disconnect]     │   │
│  │            Name: Front Left                      [Rename]         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  ┌─────┐                                                           │   │
│  │  │ 📷  │   DXO One #2 (Serial: DX0B4C5D)                          │   │
│  │  └─────┘   ● Connected                           [Disconnect]     │   │
│  │            Name: Front Right                     [Rename]         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  ┌─────┐                                                           │   │
│  │  │ 📷  │   DXO One #3 (Serial: DX0C6D7E)                          │   │
│  │  └─────┘   ○ Awaiting Permission                  [Connect]       │   │
│  │            Tap to grant USB access                                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  ┌─────┐                                                           │   │
│  │  │ 📷  │   DXO One #4 (Serial: DX0D8E9F)                          │   │
│  │  └─────┘   ○ Awaiting Permission                  [Connect]       │   │
│  │            Tap to grant USB access                                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     CONNECTION STATUS                               │   │
│  │                                                                     │   │
│  │     Connected: 2/4     │     Ready to Capture: No                  │   │
│  │                                                                     │   │
│  │     [Refresh Devices]        [Connect All Available]               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Capture Result Screen

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     CAPTURE RESULT SCREEN WIREFRAME                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ ←  Capture Session #42                                    💾  🗑️   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│                    CAPTURE SUCCESSFUL                                       │
│                    ──────────────────                                       │
│                    Sync Variance: 47ms                                      │
│                    Total Time: 1.2s                                         │
│                                                                             │
│  ┌─────────────────────────┐   ┌─────────────────────────┐                 │
│  │                         │   │                         │                 │
│  │                         │   │                         │                 │
│  │     [Full Preview]      │   │     [Full Preview]      │                 │
│  │                         │   │                         │                 │
│  │                         │   │                         │                 │
│  │  ─────────────────────  │   │  ─────────────────────  │                 │
│  │  ✓ Front Left           │   │  ✓ Front Right          │                 │
│  │  IMG_0142.DNG (24.3MB)  │   │  IMG_0089.DNG (24.1MB)  │                 │
│  │  T+0ms (reference)      │   │  T+23ms                  │                 │
│  └─────────────────────────┘   └─────────────────────────┘                 │
│                                                                             │
│  ┌─────────────────────────┐   ┌─────────────────────────┐                 │
│  │                         │   │                         │                 │
│  │                         │   │                         │                 │
│  │     [Full Preview]      │   │     [Full Preview]      │                 │
│  │                         │   │                         │                 │
│  │                         │   │                         │                 │
│  │  ─────────────────────  │   │  ─────────────────────  │                 │
│  │  ✓ Back Left            │   │  ✓ Back Right           │                 │
│  │  IMG_0203.DNG (24.5MB)  │   │  IMG_0177.DNG (24.2MB)  │                 │
│  │  T+31ms                 │   │  T+47ms                  │                 │
│  └─────────────────────────┘   └─────────────────────────┘                 │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  [Download All to Device]    [Share Session]    [Delete Session]   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Implementation Roadmap

Legend: `[x]` done, `[~]` partly done, `[ ]` not done.

### Phase 1: Foundation (Core USB Communication)

**Deliverables:**
- [x] Android project setup with Kotlin & Jetpack Compose
- [x] USB Host API integration
- [x] DXO One device detection and enumeration
- [x] Single camera connection and basic commands
- [x] JSON-RPC protocol implementation
- [x] Unit tests for protocol layer (stream demux only)

**Key Files:**
- `DxoOneUsbProtocol.kt`
- `UsbDeviceManager.kt`
- `JsonRpcMessage.kt`

### Phase 2: Multi-Camera Management

**Deliverables:**
- [x] Multi-device connection handling
- [x] Camera identification (serial number extraction)
- [x] Connection state management
- [x] Camera naming/labeling system
- [x] Live view streaming from multiple cameras
- [x] Battery level monitoring

**Key Files:**
- `CameraManagerService.kt`
- `CameraConnection.kt`
- `MultiCameraViewModel.kt`

### Phase 3: Synchronized Capture

**Deliverables:**
- [x] Parallel capture command dispatch
- [~] Synchronization timing measurement (computed from command start times; not a shutter-time measure)
- [x] Capture result aggregation
- [x] Error handling for partial failures
- [~] Capture session management (session id per capture; no persisted sessions)

**Key Files:**
- `SyncCaptureEngine.kt`
- (no use-case class; `CaptureResult` lives in `CameraConnection.kt`)

### Phase 4: Image Management

**Deliverables:**
- [ ] Image download from cameras
- [ ] Gallery integration (MediaStore)
- [ ] Session-based organization
- [ ] JPEG and DNG support
- [ ] Thumbnail generation
- [ ] Share functionality

**Status: NOT IMPLEMENTED.** None of the Phase 4 files exist; the Gallery route shows a placeholder.

### Phase 5: Polish & Optimization

**Deliverables:**
- [x] Settings persistence
- [ ] Camera preset configurations
- [ ] Performance optimization
- [~] Error recovery mechanisms (timeouts, ERROR state on transport failure; no auto-reconnect)
- [ ] User documentation
- [ ] Play Store preparation

---

## Risk Analysis

### Technical Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| USB hub compatibility issues | Medium | High | Test multiple hub models; maintain compatibility list |
| Power delivery problems | High | High | Document powered hub requirement prominently |
| Sync variance >100ms | Low | Medium | Document expected variance; set user expectations |
| Android USB Host API limitations | Medium | Medium | Test on multiple devices; document compatibility |
| Camera firmware variations | Low | Medium | Test with multiple DXO One units |

### User Experience Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Complex hardware setup | High | Medium | Provide detailed setup guide with photos |
| USB permission confusion | Medium | Medium | Clear onboarding flow; permission explanations |
| Live view performance | Medium | Medium | Optimize frame rates; allow quality settings |
| Large file downloads | Low | Low | Progress indicators; background download option |

### Hardware Compatibility Matrix

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    COMPATIBILITY MATRIX                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Android Version Support:                                                   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Version      │ USB Host │ Multi-Device │ Status                    │   │
│  ├──────────────┼──────────┼──────────────┼───────────────────────────┤   │
│  │ Android 5.0  │ ✓        │ Limited      │ Minimum supported         │   │
│  │ Android 6.0  │ ✓        │ ✓            │ Supported                 │   │
│  │ Android 7.0  │ ✓        │ ✓            │ Supported                 │   │
│  │ Android 8.0+ │ ✓        │ ✓            │ Recommended               │   │
│  │ Android 12+  │ ✓        │ ✓            │ Best experience           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Device-Specific Notes:                                                     │
│  • Samsung: Generally excellent USB Host support                           │
│  • Google Pixel: Full support, recommended for development                 │
│  • OnePlus: Good support, test power delivery                              │
│  • Xiaomi: May require enabling USB debugging                              │
│  • Budget devices: Test USB OTG support before purchase                    │
│                                                                             │
│  DXO One Requirements:                                                      │
│  • microUSB variant ONLY (not Lightning/iOS version)                       │
│  • Firmware version: Any (protocol is consistent)                          │
│  • SD card: Recommended for image storage                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Appendix A: Complete Hardware Shopping List

| Item | Recommended Model | Approx. Price | Link |
|------|-------------------|---------------|------|
| Powered USB 3.0 Hub | Anker 4-Port USB 3.0 | $25-35 | Amazon |
| USB-C OTG Adapter | Anker USB-C to USB-A | $10-15 | Amazon |
| USB-A to microUSB cables (×4) | Amazon Basics 1ft | $8-12 | Amazon |
| DXO One Cameras (×4) | Used/Refurbished | $50-150 each | eBay |

**Total Estimated Cost (excluding cameras):** $45-65

---

## Appendix B: Troubleshooting Guide

### Camera Not Detected
1. Verify USB OTG adapter is connected
2. Check hub power supply is plugged in
3. Try different USB port on hub
4. Ensure camera is powered on
5. Check cable integrity

### Capture Sync Variance Too High
1. Use shorter USB cables (<1m)
2. Ensure adequate hub power
3. Close other apps consuming USB bandwidth
4. Try different USB hub

### Image Download Failures
1. Check camera SD card space
2. Verify USB connection stability
3. Retry download operation
4. Check Android storage permissions

---

## Appendix C: JSON-RPC Command Reference

The commands the app uses are listed under "Available Commands" above and in `DxoOneMethods` (`usb/JsonRpcMessage.kt`); the JS side sends them from `dxo1usb.js`. There is no separate full command reference.

---

*Document Version: 2.0*
*Created: January 2025*
*Last Updated: 2026-11-04*
