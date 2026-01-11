# State Machine Diagrams

This document provides comprehensive state machine diagrams for all stateful components in the DXO One Control codebase, covering both the JavaScript WebUSB implementation and the Android native app.

---

## Table of Contents

1. [Connection State Machine](#1-connection-state-machine)
2. [Camera Device Lifecycle](#2-camera-device-lifecycle)
3. [Multi-Camera Manager State](#3-multi-camera-manager-state)
4. [Capture Operation State Machine](#4-capture-operation-state-machine)
5. [Live View Streaming State](#5-live-view-streaming-state)
6. [USB Permission Flow](#6-usb-permission-flow)
7. [UI State Machine](#7-ui-state-machine)
8. [Error Recovery State Machine](#8-error-recovery-state-machine)
9. [Sync Capture Engine States](#9-sync-capture-engine-states)
10. [Service Lifecycle](#10-service-lifecycle)

---

## 1. Connection State Machine

**Files:**
- `android-app/.../usb/CameraConnection.kt` (ConnectionState enum)
- `CameraDevice.js` (isConnected, isInitialized flags)

### State Diagram

```
                              ┌─────────────────────────────────────────────────────────┐
                              │                    CONNECTION STATE MACHINE              │
                              └─────────────────────────────────────────────────────────┘

                                                    ┌─────────────┐
                                                    │   START     │
                                                    └──────┬──────┘
                                                           │
                                                           │ Device detected
                                                           ▼
                              ┌──────────────────────────────────────────────────────────┐
                              │                      DISCONNECTED                         │
                              │                                                          │
                              │  • No USB connection                                     │
                              │  • Resources released                                    │
                              │  • Safe to reconnect                                     │
                              └──────────────────────────────┬───────────────────────────┘
                                                             │
                                                             │ connectDevice() / initialize()
                                                             │ [USB permission granted]
                                                             ▼
                              ┌──────────────────────────────────────────────────────────┐
                              │                      INITIALIZING                         │
                              │                                                          │
                              │  • Opening USB device                                    │
                              │  • Claiming interfaces (0 and 1)                         │
                              │  • Setting alternate interface                           │
                              │  • Sending METADATA_INIT_RESPONSE                        │
                              │  • Draining RX buffer                                    │
                              └───────────────┬──────────────────────────┬───────────────┘
                                              │                          │
                                   Success    │                          │ Failure
                                              │                          │ (timeout, USB error,
                                              │                          │  interface claim failed)
                                              ▼                          ▼
    ┌─────────────────────────────────────────────────────┐    ┌─────────────────────────┐
    │                      CONNECTED                       │    │         ERROR           │
    │                                                      │    │                         │
    │  • USB communication active                          │    │  • lastError set        │
    │  • Commands can be sent                              │    │  • Connection unusable  │
    │  • Live view available                               │    │  • Requires cleanup     │
    │  • Status polling active                             │    │                         │
    └──────────────────────┬───────────────────────────────┘    └────────────┬────────────┘
                           │                                                  │
                           │ Communication error /                            │
                           │ USB disconnect /                                 │
                           │ powerOff() / disconnect()                        │ cleanup()
                           │                                                  │
                           └──────────────────────┬───────────────────────────┘
                                                  │
                                                  ▼
                              ┌──────────────────────────────────────────────────────────┐
                              │                      DISCONNECTED                         │
                              │                      (returns to initial state)           │
                              └──────────────────────────────────────────────────────────┘
```

### State Definitions (Kotlin)

```kotlin
enum class ConnectionState {
    DISCONNECTED,  // No active connection
    INITIALIZING,  // Connection setup in progress
    CONNECTED,     // Fully operational
    ERROR          // Connection failed, needs cleanup
}
```

### Transition Table

| From State | Event | To State | Actions |
|------------|-------|----------|---------|
| DISCONNECTED | `connectDevice()` | INITIALIZING | Open USB, claim interfaces |
| INITIALIZING | Success | CONNECTED | Send ACK, drain buffer |
| INITIALIZING | Failure | ERROR | Set lastError, close device |
| CONNECTED | USB disconnect | DISCONNECTED | Release resources |
| CONNECTED | `powerOff()` | DISCONNECTED | Send poweroff command |
| CONNECTED | `disconnect()` | DISCONNECTED | Close connection |
| CONNECTED | Communication error | ERROR | Set lastError |
| ERROR | `cleanup()` | DISCONNECTED | Release all resources |

---

## 2. Camera Device Lifecycle

**Files:**
- `CameraDevice.js`
- `android-app/.../usb/CameraConnection.kt`

### State Diagram

```
                              ┌─────────────────────────────────────────────────────────┐
                              │                  CAMERA DEVICE LIFECYCLE                 │
                              └─────────────────────────────────────────────────────────┘


        ┌────────────────────────────────────────────────────────────────────────────────┐
        │                                                                                │
        │    ┌─────────────┐                                                             │
        │    │  CREATED    │  new CameraDevice(device, usbBackend)                       │
        │    │             │  • id assigned (serial or generated)                        │
        │    │             │  • nickname = null                                          │
        │    │             │  • isConnected = false                                      │
        │    └──────┬──────┘                                                             │
        │           │                                                                    │
        │           │ initialize()                                                       │
        │           ▼                                                                    │
        │    ┌─────────────────────────────────────────────────────────────────┐        │
        │    │                        INITIALIZING                             │        │
        │    │                                                                 │        │
        │    │  1. Open USB device                                             │        │
        │    │  2. Select configuration 1                                      │        │
        │    │  3. Claim interface 0 (control)                                 │        │
        │    │  4. Claim interface 1 (data)                                    │        │
        │    │  5. Select alternate interface 1                                │        │
        │    │  6. Send METADATA_INIT_RESPONSE_SIGNATURE                       │        │
        │    │  7. Drain pre-existing RX buffer                                │        │
        │    │  8. Fetch initial settings and status                           │        │
        │    │                                                                 │        │
        │    └──────────────────────────┬──────────────────────────────────────┘        │
        │                               │                                                │
        │              ┌────────────────┴────────────────┐                              │
        │              │                                 │                              │
        │              ▼ Success                         ▼ Failure                      │
        │    ┌─────────────────────┐           ┌─────────────────────┐                  │
        │    │      READY          │           │    INIT_FAILED      │                  │
        │    │                     │           │                     │                  │
        │    │ • isConnected=true  │           │ • lastError set     │                  │
        │    │ • isInitialized=true│           │ • Device closed     │                  │
        │    │ • settings cached   │           │                     │                  │
        │    │ • status cached     │           └─────────────────────┘                  │
        │    └──────────┬──────────┘                                                    │
        │               │                                                                │
        │               │                                                                │
        │    ┌──────────┴──────────────────────────────────────────────┐                │
        │    │                                                          │                │
        │    │                    OPERATIONAL STATES                    │                │
        │    │                                                          │                │
        │    │  ┌────────────────┐    ┌────────────────┐               │                │
        │    │  │     IDLE       │◄──►│  LIVE_VIEW     │               │                │
        │    │  │                │    │                │               │                │
        │    │  │ Awaiting       │    │ Streaming      │               │                │
        │    │  │ commands       │    │ JPEG frames    │               │                │
        │    │  └───────┬────────┘    └────────────────┘               │                │
        │    │          │                                               │                │
        │    │          │ takePhoto()                                   │                │
        │    │          ▼                                               │                │
        │    │  ┌────────────────┐                                      │                │
        │    │  │   CAPTURING    │                                      │                │
        │    │  │                │                                      │                │
        │    │  │ Photo capture  │                                      │                │
        │    │  │ in progress    │                                      │                │
        │    │  └───────┬────────┘                                      │                │
        │    │          │                                               │                │
        │    │          │ Complete                                      │                │
        │    │          ▼                                               │                │
        │    │  ┌────────────────┐                                      │                │
        │    │  │     IDLE       │                                      │                │
        │    │  └────────────────┘                                      │                │
        │    │                                                          │                │
        │    └──────────────────────────────────────────────────────────┘                │
        │               │                                                                │
        │               │ close() / powerOff() / USB detach                              │
        │               ▼                                                                │
        │    ┌─────────────────────┐                                                     │
        │    │      CLOSED         │                                                     │
        │    │                     │                                                     │
        │    │ • isConnected=false │                                                     │
        │    │ • Resources freed   │                                                     │
        │    └─────────────────────┘                                                     │
        │                                                                                │
        └────────────────────────────────────────────────────────────────────────────────┘
```

### State Properties

| Property | Type | Description |
|----------|------|-------------|
| `isConnected` | boolean | USB connection active |
| `isInitialized` | boolean | Initialization complete |
| `isLiveViewActive` | boolean | Live view streaming |
| `lastError` | Error? | Last error encountered |
| `batteryLevel` | int? | Cached battery percentage |
| `settings` | object? | Cached camera settings |
| `status` | object? | Cached camera status |

---

## 3. Multi-Camera Manager State

**Files:**
- `CameraManager.js`
- `android-app/.../usb/UsbDeviceManager.kt`
- `android-app/.../service/CameraManagerService.kt`

### State Diagram

```
                              ┌─────────────────────────────────────────────────────────┐
                              │               MULTI-CAMERA MANAGER STATE                 │
                              └─────────────────────────────────────────────────────────┘


        ┌────────────────────────────────────────────────────────────────────────────────┐
        │                                                                                │
        │                              MANAGER STATES                                    │
        │                                                                                │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                         UNINITIALIZED                                │    │
        │    │                                                                      │    │
        │    │  cameras = new Map() [empty]                                         │    │
        │    │  syncMode = 'parallel'                                               │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │ initialize()                             │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                            READY                                     │    │
        │    │                                                                      │    │
        │    │  USB receivers registered                                            │    │
        │    │  Device detection active                                             │    │
        │    │  Awaiting camera connections                                         │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │                                          │
        │    ┌────────────────────────────────┴────────────────────────────────────┐    │
        │    │                                                                      │    │
        │    │                    CAMERA COUNT STATES                               │    │
        │    │                    (INV-MULTI-002: max 4)                            │    │
        │    │                                                                      │    │
        │    │         ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐       │    │
        │    │         │ 0 cams  │──►│ 1 cam   │──►│ 2 cams  │──►│ 3 cams  │──┐    │    │
        │    │         │         │◄──│         │◄──│         │◄──│         │  │    │    │
        │    │         └─────────┘   └─────────┘   └─────────┘   └─────────┘  │    │    │
        │    │              │                                                  │    │    │
        │    │              │ canCapture = false                               ▼    │    │
        │    │              │                                         ┌─────────┐  │    │
        │    │              │                                         │ 4 cams  │  │    │
        │    │              │                                         │ (MAX)   │◄─┘    │
        │    │              │                                         └─────────┘       │
        │    │              │                                              │            │
        │    │              │                                              │            │
        │    │              │         canCapture = true (count > 0)        │            │
        │    │              │◄─────────────────────────────────────────────┘            │
        │    │                                                                          │
        │    └─────────────────────────────────────────────────────────────────────────┘    │
        │                                                                                │
        └────────────────────────────────────────────────────────────────────────────────┘


                              ┌─────────────────────────────────────────────────────────┐
                              │                   SYNC MODE STATE                        │
                              └─────────────────────────────────────────────────────────┘

                               ┌────────────────┐         ┌────────────────┐
                               │    PARALLEL    │◄───────►│   SEQUENTIAL   │
                               │                │         │                │
                               │ ~20-65ms sync  │         │ Higher variance│
                               │ Best effort    │         │ One at a time  │
                               └────────────────┘         └────────────────┘
                                       │                          │
                                       │    setSyncMode()         │
                                       └──────────────────────────┘
```

### Camera Map State

```
cameras: Map<String, CameraDevice>

Operations:
  ┌─────────────────────────────────────────────────────────────────────┐
  │                                                                     │
  │  connectCamera(device)                                              │
  │  ┌──────────────────┐                                               │
  │  │ cameras.size < 4 │──► Create CameraDevice ──► Add to Map        │
  │  │                  │                                               │
  │  │ cameras.size = 4 │──► Reject (INV-MULTI-002)                    │
  │  └──────────────────┘                                               │
  │                                                                     │
  │  disconnectCamera(id)                                               │
  │  ┌──────────────────┐                                               │
  │  │ id exists        │──► Close device ──► Remove from Map          │
  │  └──────────────────┘                                               │
  │                                                                     │
  │  disconnectAll()                                                    │
  │  ┌──────────────────┐                                               │
  │  │ For each camera  │──► Close device ──► Clear Map                │
  │  └──────────────────┘                                               │
  │                                                                     │
  └─────────────────────────────────────────────────────────────────────┘
```

---

## 4. Capture Operation State Machine

**Files:**
- `android-app/.../service/SyncCaptureEngine.kt`
- `android-app/.../ui/viewmodel/MultiCameraViewModel.kt`
- `CameraManager.js`

### State Diagram

```
                              ┌─────────────────────────────────────────────────────────┐
                              │               CAPTURE OPERATION STATE MACHINE            │
                              └─────────────────────────────────────────────────────────┘


        ┌────────────────────────────────────────────────────────────────────────────────┐
        │                                                                                │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                            IDLE                                      │    │
        │    │                                                                      │    │
        │    │  isCapturing = false                                                 │    │
        │    │  Awaiting capture command                                            │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │ captureAll() [cameras.count > 0]         │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                         PREPARING                                    │    │
        │    │                                                                      │    │
        │    │  isCapturing = true                                                  │    │
        │    │  Generate sessionId                                                  │    │
        │    │  Record startTime                                                    │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                 ┌───────────────────┴───────────────────┐                      │
        │                 │                                       │                      │
        │                 ▼ PARALLEL mode                         ▼ SEQUENTIAL mode      │
        │    ┌─────────────────────────┐             ┌─────────────────────────┐        │
        │    │   CAPTURING_PARALLEL    │             │  CAPTURING_SEQUENTIAL   │        │
        │    │                         │             │                         │        │
        │    │  ┌─────────────────┐    │             │  ┌─────────────────┐    │        │
        │    │  │ async {         │    │             │  │ for (camera) {  │    │        │
        │    │  │   cam1.capture()│    │             │  │   camera.take() │    │        │
        │    │  │   cam2.capture()│    │             │  │   await result  │    │        │
        │    │  │   cam3.capture()│    │             │  │ }               │    │        │
        │    │  │   cam4.capture()│    │             │  └─────────────────┘    │        │
        │    │  │ }.awaitAll()    │    │             │                         │        │
        │    │  └─────────────────┘    │             │  Higher time variance   │        │
        │    │                         │             │                         │        │
        │    │  ~20-65ms variance      │             │                         │        │
        │    └────────────┬────────────┘             └────────────┬────────────┘        │
        │                 │                                       │                      │
        │                 └───────────────────┬───────────────────┘                      │
        │                                     │                                          │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                      AGGREGATING_RESULTS                             │    │
        │    │                                                                      │    │
        │    │  Collect all CaptureResults                                          │    │
        │    │  Calculate syncVarianceMs                                            │    │
        │    │  Calculate totalTimeMs                                               │    │
        │    │  Determine allSucceeded                                              │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                 ┌───────────────────┴───────────────────┐                      │
        │                 │                                       │                      │
        │                 ▼ All succeeded                         ▼ Partial failure      │
        │    ┌─────────────────────────┐             ┌─────────────────────────┐        │
        │    │    CAPTURE_SUCCESS      │             │   CAPTURE_PARTIAL       │        │
        │    │                         │             │                         │        │
        │    │  allSucceeded = true    │             │  allSucceeded = false   │        │
        │    │  Results stored         │             │  failedCameras tracked  │        │
        │    │                         │             │  (INV-MULTI-003)        │        │
        │    └────────────┬────────────┘             └────────────┬────────────┘        │
        │                 │                                       │                      │
        │                 └───────────────────┬───────────────────┘                      │
        │                                     │                                          │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                            IDLE                                      │    │
        │    │                                                                      │    │
        │    │  isCapturing = false                                                 │    │
        │    │  lastCaptureResult = MultiCaptureResult                              │    │
        │    │                                                                      │    │
        │    └─────────────────────────────────────────────────────────────────────┘    │
        │                                                                                │
        └────────────────────────────────────────────────────────────────────────────────┘
```

### CaptureResult State

```
                              ┌─────────────────────────────────────────────────────────┐
                              │                 PER-CAMERA CAPTURE RESULT                │
                              └─────────────────────────────────────────────────────────┘

                                    ┌────────────────────────────┐
                                    │      takePhoto()           │
                                    └─────────────┬──────────────┘
                                                  │
                                                  ▼
                                    ┌────────────────────────────┐
                                    │  Send dxo_photo_take       │
                                    │  Record timestamp          │
                                    └─────────────┬──────────────┘
                                                  │
                                  ┌───────────────┴───────────────┐
                                  │                               │
                                  ▼ Success                       ▼ Failure
                    ┌────────────────────────┐      ┌────────────────────────┐
                    │   CaptureResult        │      │   CaptureResult        │
                    │                        │      │                        │
                    │   success = true       │      │   success = false      │
                    │   timestamp = T        │      │   timestamp = T        │
                    │   filePath = "..."     │      │   error = "..."        │
                    │   captureTimeMs = X    │      │   captureTimeMs = 0    │
                    │   error = null         │      │   filePath = null      │
                    └────────────────────────┘      └────────────────────────┘
```

---

## 5. Live View Streaming State

**Files:**
- `CameraDevice.js` (lines 339-405)
- `android-app/.../usb/CameraConnection.kt` (lines 248-288)

### State Diagram

```
                              ┌─────────────────────────────────────────────────────────┐
                              │                LIVE VIEW STREAMING STATE                 │
                              └─────────────────────────────────────────────────────────┘


        ┌────────────────────────────────────────────────────────────────────────────────┐
        │                                                                                │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                          INACTIVE                                    │    │
        │    │                                                                      │    │
        │    │  isLiveViewActive = false                                            │    │
        │    │  shouldStopLiveView = false                                          │    │
        │    │  liveViewJob = null                                                  │    │
        │    │  _liveViewFrame = null                                               │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │ startLiveView(callback)                  │
        │                                     │ [connectionState == CONNECTED]           │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                         STARTING                                     │    │
        │    │                                                                      │    │
        │    │  Send: dxo_camera_mode_switch { param: 'view' }                      │    │
        │    │  shouldStopLiveView = false                                          │    │
        │    │  Launch streaming coroutine/loop                                     │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │ Mode switch successful                   │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                          STREAMING                                   │    │
        │    │                                                                      │    │
        │    │  isLiveViewActive = true                                             │    │
        │    │                                                                      │    │
        │    │  ┌───────────────────────────────────────────────────────────────┐  │    │
        │    │  │                    FRAME RECEIVE LOOP                         │  │    │
        │    │  │                                                               │  │    │
        │    │  │   while (!shouldStopLiveView) {                               │  │    │
        │    │  │       1. transferInJPEG() / receiveJpegFrame()                │  │    │
        │    │  │       2. Validate JPEG (FFD8...FFD9)                          │  │    │
        │    │  │       3. Create Blob/Bitmap                                   │  │    │
        │    │  │       4. callback(frame) / _liveViewFrame.emit(frame)         │  │    │
        │    │  │   }                                                           │  │    │
        │    │  │                                                               │  │    │
        │    │  └───────────────────────────────────────────────────────────────┘  │    │
        │    │                                                                      │    │
        │    │  Frame processing states:                                            │    │
        │    │                                                                      │    │
        │    │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐              │    │
        │    │  │ RECEIVING   │───►│ VALIDATING  │───►│ EMITTING    │──┐           │    │
        │    │  │             │    │             │    │             │  │           │    │
        │    │  │ USB bulk    │    │ Check JPEG  │    │ Callback/   │  │           │    │
        │    │  │ transfer    │    │ markers     │    │ StateFlow   │  │           │    │
        │    │  └─────────────┘    └──────┬──────┘    └─────────────┘  │           │    │
        │    │         ▲                  │ Invalid frame              │           │    │
        │    │         │                  ▼                            │           │    │
        │    │         │           ┌─────────────┐                     │           │    │
        │    │         │           │  DROPPING   │                     │           │    │
        │    │         │           │  (bad frame)│                     │           │    │
        │    │         │           └─────────────┘                     │           │    │
        │    │         │                                               │           │    │
        │    │         └───────────────────────────────────────────────┘           │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │ stopLiveView() /                         │
        │                                     │ disconnect() /                           │
        │                                     │ USB detach                               │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                          STOPPING                                    │    │
        │    │                                                                      │    │
        │    │  shouldStopLiveView = true                                           │    │
        │    │  Cancel liveViewJob (if Kotlin)                                      │    │
        │    │  Wait for loop to exit                                               │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │ Loop exited                              │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                          INACTIVE                                    │    │
        │    │                                                                      │    │
        │    │  isLiveViewActive = false                                            │    │
        │    │  liveViewJob = null                                                  │    │
        │    │                                                                      │    │
        │    └─────────────────────────────────────────────────────────────────────┘    │
        │                                                                                │
        └────────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. USB Permission Flow

**Files:**
- `android-app/.../usb/UsbDeviceManager.kt`

### State Diagram

```
                              ┌─────────────────────────────────────────────────────────┐
                              │                  USB PERMISSION FLOW                     │
                              │                  (INV-SEC-002)                           │
                              └─────────────────────────────────────────────────────────┘


        ┌────────────────────────────────────────────────────────────────────────────────┐
        │                                                                                │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                     DEVICE_DETECTED                                  │    │
        │    │                                                                      │    │
        │    │  USB device with vendorId = 0x2B8F detected                          │    │
        │    │  (INV-SEC-001: Only DXO One vendor ID)                               │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │ User requests connection                 │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                    CHECK_PERMISSION                                  │    │
        │    │                                                                      │    │
        │    │  usbManager.hasPermission(device)                                    │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                 ┌───────────────────┴───────────────────┐                      │
        │                 │                                       │                      │
        │                 ▼ true (already granted)                ▼ false                │
        │    ┌─────────────────────────┐             ┌─────────────────────────┐        │
        │    │   PERMISSION_GRANTED    │             │  REQUEST_PERMISSION     │        │
        │    │                         │             │                         │        │
        │    │   Proceed to connect    │             │  Show system dialog     │        │
        │    │                         │             │  PendingIntent created  │        │
        │    └────────────┬────────────┘             └────────────┬────────────┘        │
        │                 │                                       │                      │
        │                 │                          ┌────────────┴────────────┐         │
        │                 │                          │                         │         │
        │                 │                          ▼ User grants             ▼ User denies
        │                 │             ┌─────────────────────────┐  ┌─────────────────────────┐
        │                 │             │  PERMISSION_GRANTED     │  │  PERMISSION_DENIED      │
        │                 │             │                         │  │                         │
        │                 │             │  Broadcast received     │  │  error = "USB          │
        │                 │             │  EXTRA_PERMISSION=true  │  │  permission denied"    │
        │                 │             └────────────┬────────────┘  └─────────────────────────┘
        │                 │                          │                                   │
        │                 │                          │                                   │
        │                 └──────────────────────────┤                                   │
        │                                            │                                   │
        │                                            ▼                                   │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                      CONNECT_DEVICE                                  │    │
        │    │                                                                      │    │
        │    │  usbManager.openDevice(device)                                       │    │
        │    │  Create CameraConnection                                             │    │
        │    │  Initialize protocol                                                 │    │
        │    │                                                                      │    │
        │    └─────────────────────────────────────────────────────────────────────┘    │
        │                                                                                │
        └────────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. UI State Machine

**Files:**
- `android-app/.../domain/model/CameraUiState.kt`
- `android-app/.../ui/viewmodel/MultiCameraViewModel.kt`

### State Diagram

```
                              ┌─────────────────────────────────────────────────────────┐
                              │                     UI STATE MACHINE                     │
                              └─────────────────────────────────────────────────────────┘


        ┌────────────────────────────────────────────────────────────────────────────────┐
        │                                                                                │
        │                              MultiCameraUiState                                │
        │                                                                                │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                                                                      │    │
        │    │  cameras: List<CameraUiState>     ──► Camera grid display           │    │
        │    │  availableDeviceCount: Int        ──► "X Available" indicator       │    │
        │    │  isCapturing: Boolean             ──► Capture button state          │    │
        │    │  captureMode: CaptureMode         ──► Mode selector                 │    │
        │    │  lastCaptureResult: Result?       ──► Result display                │    │
        │    │  error: String?                   ──► Error dialog                  │    │
        │    │  isUsbHostSupported: Boolean      ──► Feature warning               │    │
        │    │                                                                      │    │
        │    └─────────────────────────────────────────────────────────────────────┘    │
        │                                                                                │
        └────────────────────────────────────────────────────────────────────────────────┘


                              ┌─────────────────────────────────────────────────────────┐
                              │                    UI INTERACTION FLOW                   │
                              └─────────────────────────────────────────────────────────┘


                                           ┌──────────────┐
                                           │  UI EVENT    │
                                           └──────┬───────┘
                                                  │
                                                  ▼
        ┌─────────────────────────────────────────────────────────────────────────────────┐
        │                              EVENT DISPATCH                                     │
        │                                                                                 │
        │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐                 │
        │  │ RefreshDevices  │  │ ConnectDevice   │  │ DisconnectCamera│                 │
        │  │                 │  │ (deviceIndex)   │  │ (cameraId)      │                 │
        │  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘                 │
        │           │                    │                    │                           │
        │           ▼                    ▼                    ▼                           │
        │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐                 │
        │  │ Scan USB        │  │ Check max (4)   │  │ Remove from map │                 │
        │  │ Update list     │  │ Request perm    │  │ Update UI       │                 │
        │  └─────────────────┘  │ Connect device  │  └─────────────────┘                 │
        │                       └─────────────────┘                                       │
        │                                                                                 │
        │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐                 │
        │  │ CaptureAll      │  │ SetCaptureMode  │  │ DismissError    │                 │
        │  │                 │  │ (mode)          │  │                 │                 │
        │  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘                 │
        │           │                    │                    │                           │
        │           ▼                    ▼                    ▼                           │
        │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐                 │
        │  │ isCapturing=true│  │ Update mode     │  │ error = null    │                 │
        │  │ Execute capture │  │ (PARALLEL or    │  │ Update UI       │                 │
        │  │ Store result    │  │  SEQUENTIAL)    │  │                 │                 │
        │  │ isCapturing=fls │  └─────────────────┘  └─────────────────┘                 │
        │  └─────────────────┘                                                            │
        │                                                                                 │
        └─────────────────────────────────────────────────────────────────────────────────┘


                              ┌─────────────────────────────────────────────────────────┐
                              │                  CAPTURE BUTTON STATES                   │
                              └─────────────────────────────────────────────────────────┘


                    ┌────────────────────────────────────────────────────────────┐
                    │                                                            │
                    │   ┌──────────────┐         ┌──────────────┐               │
                    │   │   DISABLED   │         │   ENABLED    │               │
                    │   │              │         │              │               │
                    │   │ cameras = 0  │◄───────►│ cameras > 0  │               │
                    │   │ OR           │         │ AND          │               │
                    │   │ isCapturing  │         │ !isCapturing │               │
                    │   │              │         │              │               │
                    │   │ [Grayed out] │         │ [Active]     │               │
                    │   └──────────────┘         └──────┬───────┘               │
                    │                                   │                        │
                    │                                   │ onClick()              │
                    │                                   ▼                        │
                    │                           ┌──────────────┐                 │
                    │                           │  CAPTURING   │                 │
                    │                           │              │                 │
                    │                           │ isCapturing  │                 │
                    │                           │ = true       │                 │
                    │                           │              │                 │
                    │                           │ [Animated]   │                 │
                    │                           └──────────────┘                 │
                    │                                                            │
                    └────────────────────────────────────────────────────────────┘
```

---

## 8. Error Recovery State Machine

**Files:**
- `android-app/.../usb/CameraConnection.kt`
- `android-app/.../domain/model/CameraUiState.kt`

### State Diagram

```
                              ┌─────────────────────────────────────────────────────────┐
                              │                ERROR RECOVERY STATE MACHINE              │
                              │                (INV-CONS-003)                            │
                              └─────────────────────────────────────────────────────────┘


        ┌────────────────────────────────────────────────────────────────────────────────┐
        │                                                                                │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                          NORMAL                                      │    │
        │    │                                                                      │    │
        │    │  error = null                                                        │    │
        │    │  Operations executing normally                                       │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │ Error occurs                             │
        │                                     │ (USB, timeout, protocol)                 │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                       ERROR_DETECTED                                 │    │
        │    │                                                                      │    │
        │    │  Classify error type:                                                │    │
        │    │  • USB_DISCONNECTED                                                  │    │
        │    │  • COMMUNICATION_TIMEOUT                                             │    │
        │    │  • PROTOCOL_ERROR                                                    │    │
        │    │  • PERMISSION_DENIED                                                 │    │
        │    │  • CAPTURE_FAILED                                                    │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                 ┌───────────────────┼───────────────────┐                      │
        │                 │                   │                   │                      │
        │                 ▼                   ▼                   ▼                      │
        │    ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐              │
        │    │  RECOVERABLE    │  │  USER_ACTION    │  │  FATAL          │              │
        │    │                 │  │  REQUIRED       │  │                 │              │
        │    │ • Timeout       │  │                 │  │ • USB detached  │              │
        │    │ • Temp failure  │  │ • Permission    │  │ • Device error  │              │
        │    │                 │  │ • Settings      │  │                 │              │
        │    └────────┬────────┘  └────────┬────────┘  └────────┬────────┘              │
        │             │                    │                    │                        │
        │             │ Auto retry         │ Show dialog        │ Force disconnect       │
        │             ▼                    ▼                    ▼                        │
        │    ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐              │
        │    │   RETRYING      │  │  AWAITING_USER  │  │  DISCONNECTED   │              │
        │    │                 │  │                 │  │                 │              │
        │    │ Attempt again   │  │ Display error   │  │ Camera removed  │              │
        │    │ (up to 3x)      │  │ Wait for action │  │ Cleanup done    │              │
        │    └────────┬────────┘  └────────┬────────┘  └─────────────────┘              │
        │             │                    │                                             │
        │             │                    │ DismissError                                │
        │             │                    ▼                                             │
        │             │           ┌─────────────────┐                                    │
        │             │           │  ERROR_CLEARED  │                                    │
        │             │           │                 │                                    │
        │             │           │  error = null   │                                    │
        │             │           └────────┬────────┘                                    │
        │             │                    │                                             │
        │             └────────────────────┴─────────────────────────┐                   │
        │                                                            │                   │
        │                                                            ▼                   │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                          NORMAL                                      │    │
        │    │                                                                      │    │
        │    │  Resume normal operations                                            │    │
        │    │                                                                      │    │
        │    └─────────────────────────────────────────────────────────────────────┘    │
        │                                                                                │
        └────────────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Sync Capture Engine States

**Files:**
- `android-app/.../service/SyncCaptureEngine.kt`
- `CameraManager.js`

### State Diagram

```
                              ┌─────────────────────────────────────────────────────────┐
                              │               SYNC CAPTURE ENGINE STATES                 │
                              │               (INV-MULTI-004: ~50ms variance)            │
                              └─────────────────────────────────────────────────────────┘


        PARALLEL CAPTURE MODE
        ─────────────────────

        ┌────────────────────────────────────────────────────────────────────────────────┐
        │                                                                                │
        │    Time ───────────────────────────────────────────────────────────────►      │
        │                                                                                │
        │    T+0ms        T+10ms       T+30ms       T+50ms       T+70ms                 │
        │      │            │            │            │            │                     │
        │      │            │            │            │            │                     │
        │    ┌─┴─┐        ┌─┴─┐        ┌─┴─┐        ┌─┴─┐        ┌─┴─┐                  │
        │    │ P │        │   │        │   │        │   │        │ A │                  │
        │    │ R │        │ D │        │ C │        │ C │        │ G │                  │
        │    │ E │        │ I │        │ A │        │ A │        │ G │                  │
        │    │ P │        │ S │        │ M │        │ M │        │ R │                  │
        │    │ A │        │ P │        │ 2 │        │ 4 │        │ E │                  │
        │    │ R │        │ A │        │   │        │   │        │ G │                  │
        │    │ E │        │ T │        │   │        │   │        │ A │                  │
        │    │   │        │ C │        │   │        │   │        │ T │                  │
        │    └───┘        │ H │        │   │        │   │        │ E │                  │
        │                 └───┘        └───┘        └───┘        └───┘                  │
        │                   │            │            │                                  │
        │                   │ CAM 1      │ CAM 3      │                                  │
        │                   │ captures   │ captures   │                                  │
        │                   │            │            │                                  │
        │                   └────────────┴────────────┘                                  │
        │                         ▲                                                      │
        │                         │                                                      │
        │                   Sync variance: ~20-65ms                                      │
        │                   (typical ~50ms)                                              │
        │                                                                                │
        └────────────────────────────────────────────────────────────────────────────────┘


        SEQUENTIAL CAPTURE MODE
        ───────────────────────

        ┌────────────────────────────────────────────────────────────────────────────────┐
        │                                                                                │
        │    Time ───────────────────────────────────────────────────────────────►      │
        │                                                                                │
        │    T+0ms       T+200ms      T+400ms      T+600ms      T+800ms                 │
        │      │            │            │            │            │                     │
        │      │            │            │            │            │                     │
        │    ┌─┴─┐                                                                       │
        │    │CAM│        ┌───┐                                                          │
        │    │ 1 │        │CAM│        ┌───┐                                             │
        │    │   │        │ 2 │        │CAM│        ┌───┐                                │
        │    │   │        │   │        │ 3 │        │CAM│        ┌───┐                   │
        │    └───┘        │   │        │   │        │ 4 │        │AGG│                   │
        │                 └───┘        │   │        │   │        │   │                   │
        │                              └───┘        │   │        │   │                   │
        │                                           └───┘        └───┘                   │
        │                                                                                │
        │    Higher time variance, but guaranteed sequential order                       │
        │                                                                                │
        └────────────────────────────────────────────────────────────────────────────────┘


        CAPTURE RESULT AGGREGATION
        ──────────────────────────

        ┌────────────────────────────────────────────────────────────────────────────────┐
        │                                                                                │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                    MultiCaptureResult                                │    │
        │    ├─────────────────────────────────────────────────────────────────────┤    │
        │    │                                                                      │    │
        │    │  sessionId: "uuid-..."                                               │    │
        │    │  totalCameras: 4                                                     │    │
        │    │  mode: PARALLEL | SEQUENTIAL                                         │    │
        │    │                                                                      │    │
        │    │  results: [                                                          │    │
        │    │    CaptureResult(cam1, success=true,  T+12ms)                        │    │
        │    │    CaptureResult(cam2, success=true,  T+28ms)                        │    │
        │    │    CaptureResult(cam3, success=false, error="timeout")              │    │
        │    │    CaptureResult(cam4, success=true,  T+47ms)                        │    │
        │    │  ]                                                                   │    │
        │    │                                                                      │    │
        │    │  ┌─────────────────────────────────────────────────────────────┐    │    │
        │    │  │ Computed Properties:                                         │    │    │
        │    │  │                                                              │    │    │
        │    │  │  allSucceeded: false  (one failure)                          │    │    │
        │    │  │  succeededCount: 3                                           │    │    │
        │    │  │  failedCount: 1                                              │    │    │
        │    │  │  syncVarianceMs: 35  (47 - 12)                               │    │    │
        │    │  │  totalTimeMs: 523                                            │    │    │
        │    │  │  failedCameras: ["cam3"]                                     │    │    │
        │    │  │  succeededCameras: ["cam1", "cam2", "cam4"]                   │    │    │
        │    │  │                                                              │    │    │
        │    │  └─────────────────────────────────────────────────────────────┘    │    │
        │    │                                                                      │    │
        │    └─────────────────────────────────────────────────────────────────────┘    │
        │                                                                                │
        └────────────────────────────────────────────────────────────────────────────────┘
```

---

## 10. Service Lifecycle

**Files:**
- `android-app/.../service/CameraManagerService.kt`

### State Diagram

```
                              ┌─────────────────────────────────────────────────────────┐
                              │              CAMERA MANAGER SERVICE LIFECYCLE            │
                              └─────────────────────────────────────────────────────────┘


        ┌────────────────────────────────────────────────────────────────────────────────┐
        │                                                                                │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                         NOT_CREATED                                  │    │
        │    │                                                                      │    │
        │    │  Service class not instantiated                                      │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │ startService() / bindService()           │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                          CREATED                                     │    │
        │    │                                                                      │    │
        │    │  onCreate() called:                                                  │    │
        │    │  • Create notification channel                                       │    │
        │    │  • Initialize UsbDeviceManager                                       │    │
        │    │  • _isRunning = true                                                 │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │ onStartCommand()                         │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                      FOREGROUND_STARTED                              │    │
        │    │                                                                      │    │
        │    │  startForeground(NOTIFICATION_ID, notification):                     │    │
        │    │  • Notification: "DXO Multi-Cam"                                     │    │
        │    │  • Persistent while cameras connected                                │    │
        │    │  • Shows connection count                                            │    │
        │    │                                                                      │    │
        │    │  Service can now:                                                    │    │
        │    │  • Manage camera connections                                         │    │
        │    │  • Receive USB events                                                │    │
        │    │  • Execute capture operations                                        │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │                                          │
        │    ┌────────────────────────────────┴────────────────────────────────────┐    │
        │    │                                                                      │    │
        │    │                      OPERATIONAL STATES                              │    │
        │    │                                                                      │    │
        │    │   ┌─────────────┐                    ┌─────────────┐                │    │
        │    │   │    IDLE     │◄──────────────────►│  CAPTURING  │                │    │
        │    │   │             │    captureAll()    │             │                │    │
        │    │   │ Monitoring  │                    │ Executing   │                │    │
        │    │   │ connections │                    │ sync capture│                │    │
        │    │   └─────────────┘                    └─────────────┘                │    │
        │    │                                                                      │    │
        │    │   Notification updates:                                              │    │
        │    │   • "N camera(s) connected"                                          │    │
        │    │   • "Captured X photos (sync: Yms)"                                  │    │
        │    │                                                                      │    │
        │    └─────────────────────────────────────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │ stopService() / unbindService()          │
        │                                     │ (all clients gone)                       │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                         DESTROYING                                   │    │
        │    │                                                                      │    │
        │    │  onDestroy() called:                                                 │    │
        │    │  • _isRunning = false                                                │    │
        │    │  • usbDeviceManager.cleanup()                                        │    │
        │    │  • serviceScope.cancel()                                             │    │
        │    │  • Disconnect all cameras                                            │    │
        │    │                                                                      │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                         DESTROYED                                    │    │
        │    │                                                                      │    │
        │    │  Service instance destroyed                                          │    │
        │    │  All resources released                                              │    │
        │    │                                                                      │    │
        │    └─────────────────────────────────────────────────────────────────────┘    │
        │                                                                                │
        └────────────────────────────────────────────────────────────────────────────────┘
```

---

## Summary

### All Stateful Components

| Component | Type | States | File |
|-----------|------|--------|------|
| ConnectionState | Enum | 4 | `CameraConnection.kt` |
| CaptureMode | Enum | 2 | `SyncCaptureEngine.kt` |
| CameraEvent | Sealed Class | 9 events | `CameraUiState.kt` |
| CameraDevice | Class | 5 flags | `CameraDevice.js` |
| CameraConnection | Class | 4 StateFlows | `CameraConnection.kt` |
| CameraManager | Class | 3 properties | `CameraManager.js` |
| UsbDeviceManager | Class | 2 StateFlows | `UsbDeviceManager.kt` |
| CameraManagerService | Service | 3 StateFlows | `CameraManagerService.kt` |
| MultiCameraViewModel | ViewModel | 2 StateFlows | `MultiCameraViewModel.kt` |

### Key Invariants Referenced

- **INV-SEC-001**: Only connect to verified DXO One vendor ID (0x2B8F)
- **INV-SEC-002**: User permission required for USB access
- **INV-MULTI-002**: Maximum 4 cameras supported
- **INV-MULTI-003**: Partial failure handling with detailed status
- **INV-MULTI-004**: Synchronization variance ~20-65ms (typical ~50ms)
- **INV-CONS-003**: Error state recovery with clear messaging
- **INV-DATA-003**: Connection state must match actual hardware status

---

*Document Version: 1.0*
*Created: January 2025*
