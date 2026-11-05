# State Machine Diagrams

This document provides comprehensive state machine diagrams for all stateful components in the DXO One Control codebase, covering both the JavaScript WebUSB implementation and the Android native app.

---

## Table of Contents

1. [Connection State Machine](#1-connection-state-machine)
2. [Camera Device Lifecycle](#2-camera-device-lifecycle)
3. [Multi-Camera Manager State](#3-multi-camera-manager-state)
4. [Capture Operation State Machine](#4-capture-operation-state-machine)
5. [Live View Streaming State](#5-live-view-streaming-state)
   - [Transport and Request Lifecycle](#transport-and-request-lifecycle)
6. [USB Permission Flow](#6-usb-permission-flow)
7. [UI State Machine](#7-ui-state-machine)
8. [Error Recovery State Machine](#8-error-recovery-state-machine)
9. [Sync Capture Engine States](#9-sync-capture-engine-states)
10. [Service Lifecycle](#10-service-lifecycle)

---

## 1. Connection State Machine

**Files:**
- `android-app/.../usb/CameraConnection.kt` (ConnectionState enum)
- `CameraDevice.js` (no state enum: "state" is derived from the `isConnected` / `isInitialized` / `isLiveViewActive` booleans)

> **Reading note.** Only the Kotlin `ConnectionState` enum is a real, explicit state machine. The JS classes have no state field; the state names used for `CameraDevice` below (READY, IDLE, CAPTURING, CLOSED, ...) are descriptive labels for combinations of booleans, not values in the code. The Kotlin initial value is **INITIALIZING** (`CameraConnection` constructs `_connectionState` in INITIALIZING); DISCONNECTED is only entered through `disconnect()`.

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
    │  • Status fetched once on connect (no polling)        │    │                         │
    └──────────────────────┬───────────────────────────────┘    └────────────┬────────────┘
                           │                                                  │
                           │ USB detach / disconnect()                        │ disconnect()
                           │ (fatal transport error -> ERROR, left)           │
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
    ERROR          // Initialization or transport failed
}
// Initial value in CameraConnection: INITIALIZING
```

### Transition Table

| From State | Event | To State | Actions |
|------------|-------|----------|---------|
| DISCONNECTED | `connectDevice()` | INITIALIZING | Open USB, claim interfaces |
| (construction) | - | INITIALIZING | Initial value of `_connectionState` |
| INITIALIZING | Success | CONNECTED | Reader started, ACK sent, init signature awaited (<= 2 s), interface 1 alt-setting 1 selected |
| INITIALIZING | Failure | ERROR | Hardware released (reader stopped, interfaces released, connection closed) |
| CONNECTED | USB detach (`UsbDeviceManager.handleDeviceDetached`) | DISCONNECTED | `disconnect()` |
| CONNECTED | `disconnect()` | DISCONNECTED | Stop live view, stop reader, release interfaces, close (idempotent) |
| CONNECTED | Fatal transport error (`onTransportFailed`: dead device, read exception) | ERROR | Live-view accumulation disabled; the in-flight request fails with `DxoTransportClosedException`; hardware is released later by `disconnect()` |
| ERROR | `disconnect()` | DISCONNECTED | Release remaining resources |

`CameraConnection` has no `cleanup()` and no `powerOff()` method; `disconnect()` is the only teardown entry point. (`UsbDeviceManager.cleanup()` exists, but it unregisters receivers and is not a per-camera call.)

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
        │    │  CREATED    │  new CameraDevice(usbDevice, nickname)                      │
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
        │    │  6. Start DxoTransport read pump; ACK init signature (<= 2 s)   │        │
        │    │  7. Fetch initial status and settings                           │        │
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
        │               │ close() / markDisconnected() / USB detach                              │
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

Labels READY / IDLE / LIVE_VIEW / CAPTURING / CLOSED / INIT_FAILED above are derived, not stored: READY = `isConnected && isInitialized`; LIVE_VIEW = `isLiveViewActive`; CLOSED = `!isConnected`. CAPTURING has no flag; a capture is just a pending `dxo_photo_take` request (40 s timeout). A fatal transport error calls `_onTransportFailure`, which clears `isConnected` and `isLiveViewActive` and sets `lastError`.

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
        │    │                         CREATED                                      │    │
        │    │                                                                      │    │
        │    │  JS: new CameraManager() (no initialize(); the constructor adds the  │    │
        │    │      WebUSB 'disconnect' listener, dispose() removes it)             │    │
        │    │  cameras = new Map() [empty], syncMode = 'parallel'                  │    │
        │    └────────────────────────────────┬────────────────────────────────────┘    │
        │                                     │                                          │
        │                                     │ Android: initialize() (idempotent)       │
        │                                     ▼                                          │
        │    ┌─────────────────────────────────────────────────────────────────────┐    │
        │    │                            READY                                     │    │
        │    │                                                                      │    │
        │    │  Android: USB receivers registered once per process                  │    │
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
                               │ unmeasured*    │         │ Higher variance│
                               │ Best effort    │         │ One at a time  │
                               └────────────────┘         └────────────────┘
                                       │                          │
                                       │    setSyncMode()         │
                                       └──────────────────────────┘
```

### Camera Map State

\* No sync-variance figure has been measured on either platform (INV-MULTI-004).

```
cameras: Map<String, CameraDevice>

Operations:
  ┌─────────────────────────────────────────────────────────────────────┐
  │                                                                     │
  │  connectCamera(nickname)                                            │
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
        │    │  unmeasured variance    │             │                         │        │
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
- `DxoTransport.js` (`setFrameHandler`, `DxoStreamDemux`), `CameraDevice.js` (`startLiveView` / `stopLiveView`)
- `android-app/.../usb/CameraConnection.kt` (`startLiveView` / `stopLiveView` / decoder), `DxoOneUsbProtocol.kt`, `DxoOneStreamDemux.kt`

There is **no per-frame polling loop** in the live-view code and no `shouldStopLiveView` flag. The single reader (JS read pump / Kotlin reader thread) is always reading the bulk-IN endpoint; JPEG data is just another thing its demux routes. Live view only decides whether that video data is kept or discarded.

```
 INACTIVE
   isLiveViewActive = false; frame handler unset; demux video disabled
     │  startLiveView(callback)   [connected]
     ▼
 STARTING
   1. register frame handler / listener and enable video accumulation
      (before the mode switch, so early frames are not lost)
   2. RPC dxo_camera_mode_switch { param: "view" }   (queued behind any in-flight RPC)
     │  reply received                      │ RPC failed / timed out
     ▼                                      ▼
 STREAMING                              handler removed, state back to INACTIVE,
   reader -> demux -> JPEG reassembler   error rethrown (JS) / logged (Kotlin)
   -> complete FFD8..FFD9 frame
   -> JS: Blob URL passed to callback
   -> Kotlin: conflated channel -> decoder coroutine -> Bitmap StateFlow / listeners
   RPCs (takePhoto, settings) keep working: the demux separates them by JSON-RPC id
     │  stopLiveView() / disconnect() / USB detach / fatal transport error
     ▼
 INACTIVE
   handler removed, video accumulation disabled; any further video bytes are discarded
```

**Stop is host-side only.** `stopLiveView()` detaches the handler and disables accumulation. No command to take the camera out of view mode is known (TODO in both `CameraDevice.js` / `dxo1usb.js` and `CameraConnection.kt`), so the camera may keep streaming and the transport discards the bytes.

Starting while already active reuses the running session (JS: just replaces the handler; Kotlin: adds the listener under `liveViewMutex`).

---

## Transport and Request Lifecycle

**Files:** `DxoTransport.js`, `android-app/.../usb/DxoOneUsbProtocol.kt`, `DxoOneStreamDemux.kt`

One transport per camera, same rules on both platforms:

```
 open ──► read pump / reader thread starts ──► ACK init signature (wait <= 2 s) ──► OPEN
                                                                                     │
   request(method, params)                                                           │
     ├─ queued behind the in-flight request (JS promise chain / Kotlin request mutex)
     ├─ encode (refuses payload > 0xFFFF), write under the write lock, arm timeout
     └─ pump delivers packet ─► demux ─┬─ reply, id matches (or no id, single in flight, safe*) ─► resolve
                                       ├─ stale id ─► dropped
                                       ├─ message with `method` (dxo_setting_applied, ...) ─► event handler
                                       └─ not RPC ─► JPEG reassembler (live view)

 per-request outcomes: reply | timeout (JS 5 s, 40 s for dxo_photo_take;
                       Android 10 s, 40 s for dxo_photo_take) | closed
 halted endpoint (Kotlin): after 5 immediate bulk-IN failures send CLEAR_FEATURE(ENDPOINT_HALT)
     (max 3 clears between successful reads); a failed bulk-OUT gets one clear + retry
     (JS: res.status === 'stall' -> device.clearHalt('in'))
 fatal USB error / dead device (Kotlin: 50 immediate bulk-IN failures, 250 ms polls otherwise)
     ──► in-flight request fails, transport CLOSED, owner notified
         (JS CameraDevice._onTransportFailure; Kotlin CameraConnection.onTransportFailed -> ERROR)
 close() ──► pump stopped (Kotlin: reader joined before interfaces are released)
 * "safe": the camera has never sent a reply with an id. After a timeout, the next id-less
   reply is taken to be the late reply to the timed-out request and is dropped; if that
   makes the current request time out, the flag is not re-armed, so a reply that is truly
   lost costs at most one extra request.
```

A JSON-RPC `error` reply resolves normally on both platforms (JS: check `.error`; Kotlin: `isSuccess == false`); only transport problems reject / throw.

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
                              │               (INV-MULTI-004: variance unmeasured)     │
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
        │                   Sync variance: not measured (illustrative)                   │
        │                                                                                │
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
- `android-app/.../service/CameraManagerService.kt`, `DxoOneMultiCamApp.kt`, `usb/UsbDeviceManager.kt`

The service does not own the cameras. Connections and USB receivers belong to the process-wide `UsbDeviceManager` (initialized in `DxoOneMultiCamApp.onCreate`, idempotent). The service exists to keep the process in the foreground while at least one camera is connected.

```
 NOT_CREATED
   │  DxoOneMultiCamApp observes UsbDeviceManager.connectedCameras;
   │  first camera connects -> CameraManagerService.start() (startForegroundService)
   ▼
 CREATED          onCreate: notification channel, _isRunning = true,
   │              collector on connectedCameras (ignored until foreground)
   │  onStartCommand
   ▼
 FOREGROUND       startForeground(..., FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
   │              (Android 14 needs USB permission first, which a connected camera implies)
   │              notification shows camera count / last capture
   │              if startForeground throws -> stopSelf(), START_NOT_STICKY
   │  connectedCameras becomes empty
   ▼
 STOPPING         stopForeground(REMOVE) + stopSelf()
   ▼
 DESTROYED        onDestroy: _isRunning = false, serviceScope.cancel()
                  (does NOT call usbDeviceManager.cleanup() or disconnect cameras)
```

`onStartCommand` returns `START_NOT_STICKY`: after process death there are no USB connections left to keep alive. The service also exposes (via a local binder) `captureAll()`, `setCaptureMode()`, `preFocusAll()` and related helpers over `SyncCaptureEngine`; capture itself does not require the service to be in a particular state beyond cameras being connected.

---

## Summary

### All Stateful Components

| Component | Type | States | File |
|-----------|------|--------|------|
| ConnectionState | Enum | 4 | `CameraConnection.kt` |
| CaptureMode | Enum | 2 | `SyncCaptureEngine.kt` |
| CameraEvent | Sealed Class | 9 events | `CameraUiState.kt` |
| CameraDevice | Class | boolean flags (`isConnected`, `isInitialized`, `isLiveViewActive`); no enum | `CameraDevice.js` |
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
- **INV-MULTI-004**: Synchronization variance is documented as best effort; the figure is unmeasured
- **INV-CONS-003**: Error state recovery with clear messaging
- **INV-DATA-003**: Connection state must match actual hardware status

---

*Document Version: 2.0*
*Created: January 2025; updated 2026-11-04 to match the shared-transport and Android lifecycle code*
