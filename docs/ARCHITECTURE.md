# Architecture

## Overview

**dxo1control** is a collection of tools for controlling and managing DXO One cameras. The project consists of three components:

1. **JavaScript USB control stack** - WebUSB control of one or several DXO One cameras (browser; Node with a WebUSB backend such as node-usb is possible), plus two demo pages (`usb.html`, `multi-camera.html`)
2. **Android multi-camera app** (`android-app/`) - native Kotlin/Compose app that drives up to 4 cameras over USB host mode, ported from the JS protocol code
3. **Post-processing tool** (`resizeDNG.mjs`) - batch DNG-to-JPG resizing (macOS only)

**Type**: Library/Tools Collection
**Primary Users**: Developers and photographers using DXO One cameras
**Environment**: Browser (WebUSB), Android 5.0+ (USB host), Node.js >= 18 (tests and `resizeDNG.mjs`)

## High-Level Architecture

```
 JavaScript (repo root)                         Android (android-app/)
 ──────────────────────                         ──────────────────────
 usb.html          multi-camera.html            ui/ (Compose, MainActivity singleTop)
     │                   │                          MultiCameraViewModel, SettingsViewModel
     ▼                   ▼                              │                 │
 dxo1usb.js ──re-exports──► CameraManager.js    service/SyncCaptureEngine  service/CameraSettingsApplier
 (single-camera       (up to 4 cameras)             │                       │
  open() API)              │                        ▼                       ▼
     │                     ▼                    usb/UsbDeviceManager (process-wide, connects serialized)
     │               CameraDevice.js                │          ▲
     │               (one per camera)               ▼          │ started when first camera connects,
     │                     │                    usb/CameraConnection     stops itself when none remain
     │                     │                        │          service/CameraManagerService
     ▼                     ▼                        ▼          (foreground, connectedDevice)
          DxoTransport.js                       usb/DxoOneUsbProtocol (reader thread, request mutex)
   (read pump, DxoStreamDemux,                      │
    serialized RPC, timeouts)                   usb/DxoOneStreamDemux
          │  u8a.js                                 │
          ▼                                         ▼
      WebUSB (navigator.usb)                  android.hardware.usb
                          │                         │
                          └──────────┬──────────────┘
                                     ▼
                      DXO One cameras (1-4, microUSB)

 Post-processing (independent): resizeDNG.mjs ──► macOS `sips` ──► resized/*.jpg
```

`dxo1usb.js` is the package entry point: its default export is `{ open }` (single-camera API) and it re-exports `CameraDevice` and `CameraManager`. `CameraDevice.js` does **not** use `dxo1usb.js`; both sit on `DxoTransport.js`.

## Component Breakdown

### 1. Multi-Camera Layer

#### CameraManager (`CameraManager.js`)

**Responsibilities:**
- Manage connections to up to 4 DXO One cameras simultaneously
- Provide synchronized capture across all cameras
- Handle partial failures gracefully (INV-MULTI-003)
- Support parallel and sequential capture modes
- Track camera list and notify on changes

**Key Methods:**
- `connectCamera(nickname)` - Connect a new camera with optional name
- `disconnectCamera(cameraId)` - Disconnect a specific camera
- `captureAll()` - Capture on all cameras (parallel or sequential)
- `sendCommandToAll(method, params)` - Send command to all cameras

**Dependencies:**
- CameraDevice for individual camera management
- WebUSB API (browser standard); device chooser filter is `vendorId: 0x2b8f` only
- `MAX_CAMERAS = 4` is checked in `connectCamera()` (INV-MULTI-002)

#### CameraDevice (`CameraDevice.js`)

**Responsibilities:**
- Manage a single DXO One camera connection
- Track per-camera state (connected, live view, battery, errors)
- Provide unique identification (serial number or generated ID)
- Own one `DxoTransport` for USB communication with that camera
- Support live view with callback

**Key Properties:**
- `id` - Unique camera identifier (INV-MULTI-001)
- `displayName` - User-friendly name (nickname or generated)
- `isConnected` / `isInitialized` / `isLiveViewActive` - state flags (there is no explicit state enum in JS)
- `batteryLevel` - Cached battery status

**Dependencies:**
- WebUSB API (browser standard)
- `DxoTransport.js` (which uses `u8a.js`)

### 2. Single-Camera API and Transport (`dxo1usb.js`, `DxoTransport.js`)

#### `dxo1usb.js`

**Responsibilities:**
- Open one DXO One over WebUSB (`open()`, the default export) and expose command groups (capture, settings, live view, file access)
- Re-export `CameraDevice` and `CameraManager` for multi-camera use

**Dependencies:** WebUSB API, `DxoTransport.js`

**Note:** The original `open()` function is maintained for backward compatibility. New applications should use CameraManager for multi-camera support.

#### `DxoTransport.js`

One `DxoTransport` per opened camera. It is the only code that reads the bulk-IN endpoint. Rules (the Android `DxoOneStreamDemux` / `DxoOneUsbProtocol` follow the same ones):

- **One read pump per camera.** Every bulk-IN packet goes through `DxoStreamDemux`, which routes it to:
  - the in-flight RPC, matched by `id` (a reply without an `id` is accepted for the single in-flight request only while the camera has never sent an id; after a timeout the next id-less reply is treated as the late reply and dropped (a truly lost reply costs at most one extra request); stale ids are dropped);
  - an event handler, for messages carrying a `method` (e.g. `dxo_setting_applied`; `dxo_usb_flush_forced` is ignored);
  - the live-view JPEG reassembler, for everything else.
- **One RPC in flight per camera**; further requests queue behind it.
- **Per-request timeouts**: 5 s by default, 40 s for `dxo_photo_take`. Fatal USB errors fail the in-flight request and close the transport (`TransportError` with code `timeout`, `closed` or `io`).
- **Handshake** waits at most 2 s for the camera's init signature, then ACKs it.
- `encodeRpcRequest` refuses payloads larger than 0xFFFF bytes (16-bit length field, INV-DATA-001).

Stop of live view is host-side only: the frame handler is detached and the transport discards further video bytes. No camera-side "leave view mode" command is known.

### 3. Utility Layer (`u8a.js`)

**Responsibilities:**
- Uint8Array manipulation utilities
- Data conversion helpers
- Binary data handling for USB communication

**Dependencies:** None (pure JavaScript utilities); used by `DxoTransport.js`

### 4. Web Interface (`usb.html`, `multi-camera.html`)

**Responsibilities:**
- Provide user interface for camera control
- Demonstrate WebUSB API usage
- Live camera control demo
- Multi-camera management UI (multi-camera.html)

**Dependencies:**
- `dxo1usb.js` (single-camera mode, `usb.html`)
- `CameraManager.js` (multi-camera mode, `multi-camera.html`)
- WebUSB-compatible browser

### 5. Post-Processing Tool (`resizeDNG.mjs`)

**Responsibilities:**
- Take a directory of DXO One `.dng` files and write quarter-size JPGs to `<dir>/resized/`
- Convert to JPG using macOS `sips`, without any onboard-firmware colorspace alteration

**Dependencies:**
- Node.js >= 18 (`engines` in `package.json`); only `fs`, `path` and `child_process` (`execFileSync`)
- macOS `sips` on the PATH (no npm packages, no image libraries). The script is therefore macOS-only.

### 6. Android App (`android-app/`)

Package `com.dxoone.multicam` (Kotlin, Jetpack Compose, Hilt, DataStore). It reimplements the JS protocol natively; see `docs/ANDROID_MULTI_CAMERA_APP.md` for detail.

| Layer | Files | Role |
|-------|-------|------|
| USB | `usb/DxoOneStreamDemux.kt` | Packet demux, same rules as the JS demux |
| | `usb/DxoOneUsbProtocol.kt` | Dedicated reader thread (250 ms bulk polls, dead-device detection), write lock, request mutex, typed timeout / closed exceptions |
| | `usb/CameraConnection.kt` | One camera: claims interface 0 and interface 1 (alt-setting 1), `ConnectionState`, live view fed by the reader |
| | `usb/UsbDeviceManager.kt` | Process-wide; idempotent `initialize()`; permission flow; connects serialized by a mutex; max 4 cameras; vendorId 0x2B8F only |
| Service | `service/CameraManagerService.kt` | Foreground service (`connectedDevice`), started by `DxoOneMultiCamApp` when the first camera connects, stops itself when none remain, `START_NOT_STICKY` |
| | `service/SyncCaptureEngine.kt` | Parallel / sequential capture across cameras |
| | `service/CameraSettingsApplier.kt` | Applies a settings profile and checks each result; used by Settings and on connect |
| UI / data | `ui/` (Compose, `MainActivity` is `singleTop`), `data/SettingsPreferences.kt` (DataStore) | Screens, ViewModels, persisted settings |

Not present: Room DB, repositories, a use-case layer, a real Gallery screen (only a placeholder composable in `MainActivity.kt`), image download. JUnit tests live in `app/src/test`.

### 7. Tests and CI

- `test/` - `node:test` suites run against `test/fakeDxoOne.mjs`, a scripted fake WebUSB device (`npm test` also runs `node --check` on every `*.js` / `*.mjs`).
- `android-app/app/src/test` - JUnit tests (currently the stream demux).
- `.github/workflows/ci.yml` - `js` job (`npm test`), `validate-structure` (required files exist), `android` job (`assembleDebug`, `testDebugUnitTest`, `lintDebug`).

## Data Models

### USB RPC Frame (host to camera and back)
```
8-byte header   A3 BA D1 10 17 08 00 0C   (RPC_HEADER)
2 bytes         payload length, little endian (16-bit, max 0xFFFF)
22-byte trailer 00 00 03 00 ... 00        (RPC_HEADER_TRAILER)
payload         JSON-RPC 2.0 text + NUL   {"jsonrpc":"2.0","id":n,"method":"dxo_...","params":{...}}
```
Packets are at most 512 bytes (`MAX_PACKETSIZE`); one message may span packets. Live view is a stream of raw JPEG data on the same endpoint.

### JSON-RPC Reply
```javascript
{ jsonrpc: "2.0", id: Number, result: {...} }     // or { ..., error: { message } }
{ method: "dxo_setting_applied", params: {...} }  // notification, no id
```

## External Dependencies

### WebUSB API
- **Purpose**: Browser API for USB device communication
- **Failure Mode**: Graceful degradation - show error message if not supported
- **Constraint**: Only works in WebUSB-compatible browsers (Chrome, Edge, Opera)
- **Constraint**: Only tested with microUSB connection (Lightning not tested)

### Node.js Runtime
- **Purpose**: Execute post-processing scripts
- **Failure Mode**: Require Node.js installation for CLI tools
- **Version Requirements**: Node.js >= 18 (`engines` in `package.json`; CI runs Node 22)

### macOS `sips`
- **Purpose**: DNG decoding, resize and JPG conversion for `resizeDNG.mjs`
- **Constraint**: macOS only; no npm image libraries are used

### Android platform
- **Purpose**: USB host API (`android.hardware.usb`) for the Android app
- **Constraint**: Device must support USB host mode; Android 14 requires USB permission before a `connectedDevice` foreground service can start

## Key Design Decisions

### Decision: WebUSB for Camera Control
**Rationale**: Enables browser-based camera control without native drivers or applications. Provides cross-platform compatibility and easy demo deployment.

**Trade-offs:**
- ✅ No driver installation required
- ✅ Cross-platform (any WebUSB browser)
- ✅ Easy to demonstrate and share
- ❌ Browser-only on its own (the Android app, see DECISIONS.md, covers native use)
- ❌ Limited to WebUSB-compatible browsers

### Decision: Separate USB and Post-Processing Tools
**Rationale**: Different use cases with different runtime requirements. USB control needs a browser (or Android), post-processing needs Node.js on macOS.

**Trade-offs:**
- ✅ Clear separation of concerns
- ✅ Each tool optimized for its environment
- ✅ Can use tools independently
- ❌ No integrated workflow (yet)
- ❌ Different runtime requirements

### Decision: microUSB Connection Only
**Rationale**: Based on community research (rickdeck/DxO-One), microUSB protocol is understood. Lightning connection not yet reverse-engineered.

**Trade-offs:**
- ✅ Working implementation available now
- ✅ Builds on existing community knowledge
- ❌ Doesn't support Lightning connector
- ❌ Warning needed in documentation

### Decision: Multi-Camera Support with Software Synchronization
**Rationale**: Enable control of up to 4 DXO One cameras for 360°, stereo, and multi-angle photography. Software-based synchronization is expected to give tens of milliseconds of variance (not measured on hardware), which is sufficient for most use cases.

**Trade-offs:**
- ✅ Enables new use cases (360°, stereo, product photography)
- ✅ No hardware modifications required
- ✅ Works with existing cameras
- ✅ Backward compatible with single-camera API
- ❌ Tens of ms of sync variance expected (unmeasured; not suitable for < 1ms requirements)
- ❌ USB bandwidth may be shared (recommend multiple USB controllers)
- ❌ Requires multiple cameras (cost)

**Invariants Applied:**
- INV-MULTI-001: Camera Identification
- INV-MULTI-002: Maximum Camera Limit (4)
- INV-MULTI-003: Partial Failure Handling
- INV-MULTI-004: Synchronization Accuracy Documentation

## Security Considerations

### USB Access Control
- **Risk**: Malicious websites could request USB access
- **Mitigation**: WebUSB requires explicit user permission per session
- **Mitigation**: The WebUSB chooser and the Android manager filter on DXO vendor ID 0x2b8f only (no product ID check)

### File Processing
- **Risk**: Processing malicious DNG files could cause crashes or exploits
- **Mitigation**: `resizeDNG.mjs` calls `sips` via `execFileSync` with an argument array (no shell), and only touches `.dng` files in the given directory
- **Not implemented**: DNG content validation or sandboxing (decoding is delegated to `sips`)

### Command Injection
- **Risk**: Invalid commands could damage camera or cause undefined behavior
- **Mitigation (Android)**: `DxoOneMethods.isValidCommand` allowlist is checked in `DxoOneUsbProtocol.sendCommand`
- **Not implemented (JS)**: no method allowlist; `transferOutRPC` / `sendCommandToAll` send any method name

## Performance Considerations

### USB Communication
- **Latency**: USB 2.0 communication latency varies by host
- **Throughput**: Limited by USB 2.0 bandwidth for image transfer
- **Command queuing**: implemented as one in-flight RPC per camera (`DxoTransport`, `DxoOneUsbProtocol`)

### Image Processing
- **Memory**: Large DNG files (20+ MB) require sufficient RAM
- **CPU**: Image resizing is CPU-intensive
- **Optimization**: Possible: streaming/chunked processing (not done)
- **Optimization**: Possible: parallel processing for batch operations (not done; `resizeDNG.mjs` is sequential)

### Browser Performance
- **Memory**: Keep connection state minimal
- **UI**: Ensure camera commands don't block UI thread
- **Optimization**: Use Web Workers if heavy processing needed (not currently done)

## Open Questions

- [x] Minimum Node.js version: 18 (`engines`)
- [x] Image processing libraries in resizeDNG.mjs: none, macOS `sips` via `child_process`
- [ ] What is the complete USB command protocol for DXO One? (a camera-side "leave live view" command is still unknown)
- [ ] Are there plans to support Lightning connector?
- [ ] Should there be integration between USB control and post-processing?
- [ ] What are the camera's supported image formats and settings?
- [ ] Real-hardware validation of the transport rework (tests use a fake device only)

## Future Considerations

### Potential Enhancements
1. Native desktop application (Electron) for integrated workflow (the Android app already covers mobile)
2. Lightning connector support (requires protocol reverse-engineering)
3. Real-time preview during USB control
4. Batch operation UI for post-processing
5. Camera settings management and persistence
6. Firmware update capabilities (if safe to implement)

### Integration Opportunities
1. Integration with photo management software
2. Plugin architecture for custom post-processing filters
3. Remote camera control over network
4. Image download / gallery in the Android app

---

**Last Updated**: 2026-11-04
**Document Version**: 2.0
**Status**: Aligned with code after the Nov 2026 audit (shared transport, Android app)
