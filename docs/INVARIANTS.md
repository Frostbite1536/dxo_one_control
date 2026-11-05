# Invariants

This document defines the non-negotiable rules for dxo1control. These invariants must hold true across all code changes, features, and refactoring.

---

## Data Integrity Invariants

### INV-DATA-001: USB Message Integrity

**Rule**: All USB messages sent to the camera MUST be valid Uint8Array instances with proper length fields.

**Rationale**: Invalid message formats can cause camera to enter undefined states, potentially requiring power cycle or causing data corruption.

**Examples**:

Valid:
```javascript
const message = new Uint8Array([0x01, 0x02, 0x03]);
// length field matches actual array length
```

Invalid:
```javascript
const message = [0x01, 0x02, 0x03];  // Plain array, not Uint8Array
const message = new Uint8Array([0x01, 0x02, 0x03]);
message.length = 5;  // Length mismatch
```

**Enforcement (as implemented)**:
- `encodeRpcRequest` (`DxoTransport.js`) builds every outgoing JSON-RPC frame as a `Uint8Array` and throws `RangeError` for payloads over 0xFFFF bytes (the length field is 16-bit, so it would wrap). Test: `test/transport.test.mjs`.
- Android: `DxoOneUsbProtocol` writes through one write lock and checks each write transferred its full length.
- Responses: `DxoStreamDemux` drops packets it cannot parse (unparseable JSON, oversized buffers) instead of throwing.

**Not yet enforced** (intent kept; nothing in the code does this):
- Runtime type assertions or TypeScript types for callers of the low-level send path (callers are internal; only the length check exists).
- Runtime assertions in development mode.

### INV-DATA-002: DNG File Colorspace Preservation

**Rule**: Post-processing operations MUST preserve the original colorspace of DNG files. No automatic colorspace conversion is allowed.

**Rationale**: Photographers rely on preserving the original color profile from the camera sensor. Automatic conversion can degrade image quality and alter artistic intent.

**Examples**:

Valid:
```javascript
// Read original colorspace
const originalColorspace = dng.getColorspace();
// Process without modification
resizeImage(dng, { preserveColorspace: originalColorspace });
```

Invalid:
```javascript
// Automatic conversion to sRGB
resizeImage(dng, { convertToSRGB: true });
// Ignore original colorspace
processImage(dng);  // defaults to different colorspace
```

**Enforcement (as implemented)**:
- `resizeDNG.mjs` converts with a bare `sips -s format jpeg ... --resampleHeightWidth` call and does not touch colorspace options itself, so it does not *deliberately* convert; whether `sips` preserves the embedded profile is unverified.

**Not yet enforced** (intent kept; nothing in the code does this):
- Explicit colorspace parameter or inspection of input/output profiles.
- Any tests (the script has none and is macOS-only).
- Integration tests comparing input/output colorspace metadata.

### INV-DATA-003: Connection State Consistency

**Rule**: USB connection state MUST accurately reflect the actual hardware connection status. No phantom connections or stale state.

**Rationale**: Acting on stale connection state can lead to command failures, user confusion, and potential data loss if user assumes connection is active.

**Examples**:

Valid:
```javascript
// Check actual USB connection before operations
if (await device.isConnected()) {
  await sendCommand(cmd);
}

// Update state on disconnect events
device.on('disconnect', () => {
  connectionState = 'disconnected';
  clearCommandQueue();
});
```

Invalid:
```javascript
// Assume connection from stale flag
if (wasConnectedBefore) {
  sendCommand(cmd);  // May fail if device disconnected
}

// Don't update state on disconnect
// connectionState remains 'connected' after device removed
```

**Enforcement (as implemented)**:
- JS `CameraDevice`: `isConnected` is cleared on `close()`, `markDisconnected()` and on a fatal transport error (`_onTransportFailure`); `CameraManager` listens for the WebUSB `disconnect` event and removes the camera. Requests on a disconnected camera throw.
- Android `CameraConnection`: `ConnectionState` moves to ERROR on a fatal transport error (`onTransportFailed`) and to DISCONNECTED on `disconnect()`; `UsbDeviceManager` handles `USB_DEVICE_DETACHED`.
- Tests: transport tests cover unplug failing the in-flight request (`test/transport.test.mjs`).

**Not yet enforced** (intent kept; nothing in the code does this):
- Pre-command hardware liveness probe (the code relies on events and transfer failures, not a check before each command).
- UI state is only as fresh as the flows that feed it; the Android UI path from `ConnectionState` to screens was not re-audited.

---

## Security Invariants

### INV-SEC-001: USB Device Vendor ID Verification

**Rule**: WebUSB connection MUST only be established with devices matching the DXO One vendor ID (0x2b8f; a product ID check is desirable but not currently made). No wildcard or unverified device access.

**Rationale**: Prevents accidental or malicious control of non-camera USB devices. Protects users from unintended device access.

**Examples**:

Valid:
```javascript
const filters = [{
  vendorId: 0x1234,    // DXO One vendor ID
  productId: 0x5678    // DXO One product ID
}];
const device = await navigator.usb.requestDevice({ filters });
```

Invalid:
```javascript
// No filters - any USB device
const device = await navigator.usb.requestDevice({});

// Wildcard vendor ID
const filters = [{ vendorId: undefined }];
```

**Enforcement (as implemented)**:
- JS: `PARAMS_DEVICE_REQUEST = { filters: [{ vendorId: 0x2b8f }] }` in `dxo1usb.js` and `CameraManager.js`.
- Android: `UsbDeviceManager` filters on `DxoOneConstants.VENDOR_ID` (0x2B8F) both when enumerating and in `connectDevice`.
- **Note:** the filter is **vendorId only**. No product ID check is made on either platform, so any device with that vendor ID is accepted. (The code examples above that show a product ID illustrate the stricter rule.)

**Not yet enforced** (intent kept; nothing in the code does this):
- Product ID matching.
- A shared constant for the filter (it is duplicated in two JS files).

### INV-SEC-002: User Permission Required for USB Access

**Rule**: USB access MUST require explicit user interaction and permission grant. No automatic or background connection attempts.

**Rationale**: WebUSB security model requires user consent. Attempting to bypass violates browser security and user trust.

**Examples**:

Valid:
```javascript
// Triggered by user click/button press
button.onclick = async () => {
  const device = await navigator.usb.requestDevice({ filters });
};
```

Invalid:
```javascript
// Automatic connection on page load
window.onload = async () => {
  const device = await navigator.usb.requestDevice({ filters });
};

// Background polling for devices
setInterval(async () => {
  try { await navigator.usb.requestDevice({ filters }); } catch {}
}, 1000);
```

**Enforcement (as implemented)**:
- JS: WebUSB `requestDevice()` itself requires a user gesture; the demo pages call it from button handlers.
- Android: `UsbDeviceManager.connectDevice` returns null unless `hasPermission(device)`, and permission is requested through the system dialog.

**Not yet enforced** (intent kept; nothing in the code does this):
- Automated checks for auto-connection patterns (code review only).

### INV-SEC-003: Command Validation Before Transmission

**Rule**: All camera commands MUST be validated against a known-safe command set before transmission to the device.

**Rationale**: Sending arbitrary or malformed commands could damage the camera hardware, corrupt firmware, or cause undefined behavior.

**Examples**:

Valid:
```javascript
const SAFE_COMMANDS = {
  CAPTURE: 0x01,
  GET_STATUS: 0x02,
  SET_ISO: 0x03
};

function sendCommand(cmdType, payload) {
  if (!Object.values(SAFE_COMMANDS).includes(cmdType)) {
    throw new Error('Unknown command type');
  }
  // Proceed with transmission
}
```

Invalid:
```javascript
// Accept arbitrary command codes
function sendCommand(cmdType, payload) {
  device.transferOut(endpoint, new Uint8Array([cmdType, ...payload]));
}

// No validation of command parameters
sendCommand(userInput, userPayload);  // Direct user input
```

**Enforcement (as implemented)**:
- **Android:** `DxoOneMethods.isValidCommand` is an allowlist (`VALID_COMMANDS`), checked in `DxoOneUsbProtocol.sendCommand` before anything is written.
- **JS:** `KNOWN_METHODS` / `isKnownMethod` in `DxoTransport.js` (same set as Android); `CameraDevice.transferOutRPC` (and so `CameraManager.sendCommandToAll`) and `dxo1usb.js` reject anything else before sending. Test: `test/transport.test.mjs`.

**Not yet enforced** (intent kept; nothing in the code does this):
- Parameter validation (method names are checked; params are not).
- Fuzz tests for command rejection (unit tests exist: `test/transport.test.mjs`).
- (The `0x01`-style byte opcode examples above are illustrative; the real protocol is JSON-RPC with `dxo_*` method names.)

---

## Consistency Invariants

### INV-CONS-001: Browser WebUSB Support Check

**Rule**: The application MUST check for WebUSB API availability before attempting any USB operations and provide clear error messaging if unavailable.

**Rationale**: Not all browsers support WebUSB. Attempting operations without the API causes confusing errors and poor user experience.

**Examples**:

Valid:
```javascript
if (!navigator.usb) {
  showError('WebUSB not supported. Please use Chrome, Edge, or Opera.');
  disableUSBFeatures();
  return;
}
// Proceed with USB operations
```

Invalid:
```javascript
// Assume WebUSB exists
await navigator.usb.requestDevice({ filters });  // May throw in Safari/Firefox
```

**Enforcement (as implemented)**:
- JS: `open()` in `dxo1usb.js` and `CameraManager.connectCamera` throw a clear "browser / JS environment does not support WebUSB" error when no USB backend is available; `CameraManager.isWebUSBAvailable()` exposes the check.
- Both accept an injected `usbBackend`, so tests use a fake instead of `navigator.usb`.

**Not yet enforced** (intent kept; nothing in the code does this):
- Page-level graceful degradation in `usb.html` / `multi-camera.html` was not verified here.

### INV-CONS-002: microUSB Connection Warning

**Rule**: All documentation and UI MUST prominently warn that only microUSB connections are tested. Lightning connector usage is at user's risk.

**Rationale**: Lightning protocol is not reverse-engineered. Users attempting Lightning connection should be warned of untested behavior.

**Examples**:

Valid:
```javascript
// In UI:
<div class="warning">
  ⚠️ Only microUSB connection tested. Lightning connector not supported.
</div>

// In README:
> **Warning**: Only tested with microUSB connection! Lightning connector support is unknown.
```

Invalid:
```javascript
// Generic connection message
<div>Connect your DXO One camera via USB</div>

// No mention of connection type limitations
```

**Enforcement (as implemented)**:
- `usb.html`, `multi-camera.html` and `README.md` carry the microUSB-only warning.

**Not yet enforced** (intent kept; nothing in the code does this):
- A documentation review checklist (none exists).

### INV-CONS-003: Error State Recovery

**Rule**: All error conditions MUST either auto-recover or provide clear user actions for recovery. No silent failures or stuck states.

**Rationale**: Camera control involves hardware that can disconnect, fail, or timeout. Users need clear feedback and recovery paths.

**Examples**:

Valid:
```javascript
try {
  await sendCommand(cmd);
} catch (error) {
  if (error.name === 'NetworkError') {
    // Auto-recover: retry once
    await sendCommand(cmd);
  } else {
    // User action needed
    showError('Command failed. Please disconnect and reconnect camera.');
    resetConnectionState();
  }
}
```

Invalid:
```javascript
try {
  await sendCommand(cmd);
} catch (error) {
  // Silent failure
  console.log(error);
}

try {
  await sendCommand(cmd);
} catch (error) {
  // Stuck state - no recovery
  isProcessing = true;  // Never cleared
  throw error;
}
```

**Enforcement (as implemented)**:
- Every transport request has a timeout (commands 5 s in JS, 10 s on Android; 40 s for `dxo_photo_take` on both), so a silent camera cannot hang a caller; a fatal USB error fails the in-flight request and closes the transport (`DxoTransport`, `DxoOneUsbProtocol`). Tests: `test/transport.test.mjs`.
- `CameraDevice.initialize()` releases the device on failure; `CameraManager.captureAll` / `sendCommandToAll` convert per-camera failures into error results instead of throwing.
- Android: transport failure sets `ConnectionState.ERROR`; `disconnect()` is idempotent.

**Not yet enforced** (intent kept; nothing in the code does this):
- Automatic reconnect / retry (nothing retries; recovery means reconnecting the camera).
- Guaranteed user-visible message for every error path in the UIs.

---

## API Contract Invariants

### INV-API-001: Backward Compatible Exports

**Rule**: Public exports from `dxo1usb.js` and utility modules MUST maintain backward compatibility. Breaking changes require major version bump.

**Rationale**: External users and demo page depend on stable API. Breaking changes without version updates cause integration failures.

**Examples**:

Valid:
```javascript
// Add new optional parameter (backward compatible)
function sendCommand(type, payload, options = {}) { }

// Add new export (backward compatible)
export { sendCommand, newHelper };
```

Invalid:
```javascript
// Remove existing export (breaking)
export { sendCommand };  // Previously exported newHelper removed

// Change function signature (breaking)
function sendCommand(commandObject) { }  // Was (type, payload)

// Rename export (breaking)
export { sendCommand as transmitCommand };
```

**Enforcement (as implemented)**:
- Convention only: `dxo1usb.js` still default-exports `{ open }` and additionally re-exports `CameraDevice` and `CameraManager`.

**Not yet enforced** (intent kept; nothing in the code does this):
- Semantic versioning discipline (`package.json` is at 1.0.0 with no release process).
- API compatibility tests (the `dxo1usb: ...` tests in `test/transport.test.mjs` cover part of the surface).
- Changelog / deprecation process.

### INV-API-002: Uint8Array for Binary Data

**Rule**: All binary data interfaces MUST use Uint8Array. No mixing with Array, Buffer, or other types.

**Rationale**: Consistent typing prevents confusion, enables type checking, and ensures browser/Node.js compatibility.

**Examples**:

Valid:
```javascript
function processData(data: Uint8Array): Uint8Array {
  return new Uint8Array(data.length);
}
```

Invalid:
```javascript
function processData(data) {  // Accepts any type
  return Array.from(data);   // Returns Array instead of Uint8Array
}

function sendBytes(data: Buffer) {  // Node.js Buffer not browser-compatible
  // ...
}
```

**Enforcement (as implemented)**:
- `u8a.js` helpers and `DxoTransport` operate on `Uint8Array`; JSDoc type comments document the interfaces.

**Not yet enforced** (intent kept; nothing in the code does this):
- Runtime type validation at module boundaries.
- TypeScript (not adopted).

---

## Multi-Camera Invariants

These invariants apply when using the multi-camera API (CameraManager).

### INV-MULTI-001: Camera Identification

**Rule**: Each connected camera MUST be uniquely identifiable by a stable ID (serial number or generated ID).

**Rationale**: Users need to know which physical camera is which in a multi-camera setup. Without unique identification, users cannot reliably configure or troubleshoot individual cameras.

**Examples**:

Valid:
```javascript
// Use serial number when available
const cameraId = device.serialNumber || generateUniqueId(device);

// Display identifiable name to user
camera.displayName = camera.nickname || `Camera (${camera.serialNumber.slice(-4)})`;
```

Invalid:
```javascript
// Use array index as ID (changes on reconnect)
cameras[0].capture();  // Which physical camera is this?

// No way to identify cameras
const cameras = manager.getAllCameras();  // All look the same
```

**Enforcement (as implemented)**:
- JS `CameraDevice.id` = `usbDevice.serialNumber`, falling back to `vendor-product-deviceVersion`; Android `CameraConnection.id` = serial, falling back to `vendor-product`. Both are stable across reconnects; if two serial-less cameras would collide, `CameraManager` / `UsbDeviceManager` append `#2`, `#3`, ... in connection order.
- `CameraManager` rejects duplicates by device object or serial number; `UsbDeviceManager` reuses an already-connected device node. `displayName` and `nickname` are supported on both platforms.

**Not yet enforced** (intent kept; nothing in the code does this):
- Distinguishing two serial-less cameras across reconnects (their suffixes follow connection order).

### INV-MULTI-002: Maximum Camera Limit

**Rule**: The system MUST support a maximum of 4 cameras. Connection attempts beyond this limit MUST be rejected with a clear error message.

**Rationale**: Prevents resource exhaustion (USB bandwidth, memory, CPU). 4 cameras is sufficient for most multi-camera use cases (360°, stereo, product photography) while remaining manageable.

**Examples**:

Valid:
```javascript
const MAX_CAMERAS = 4;

async connectCamera() {
  if (this.cameras.size >= MAX_CAMERAS) {
    throw new Error('Maximum 4 cameras supported. Disconnect a camera to add another.');
  }
  // Proceed with connection
}
```

Invalid:
```javascript
// No limit check
async connectCamera() {
  const device = await requestDevice();
  this.cameras.push(device);  // Could add unlimited cameras
}
```

**Enforcement (as implemented)**:
- JS: `MAX_CAMERAS = 4`, checked at the top of `CameraManager.connectCamera()`; `multi-camera.html` disables the Connect button at 4.
- Android: `DxoOneConstants.MAX_CAMERAS = 4`, checked in `UsbDeviceManager.connectDevice`, which runs inside a mutex so concurrent connects are serialized and cannot overshoot; an already-connected device node is reused rather than opened twice.

**Not yet enforced** (intent kept; nothing in the code does this):
- JS: `connectCamera()` awaits the device chooser and `initialize()` after the size check, so two overlapping calls could in principle exceed the limit (the page serializes via user clicks; the class does not).

### INV-MULTI-003: Partial Failure Handling

**Rule**: If a multi-camera operation fails on some cameras but succeeds on others, the system MUST clearly report which cameras succeeded and which failed.

**Rationale**: Users need to know exactly what happened during multi-camera capture. Silent partial failures could result in missing critical shots without the user's knowledge.

**Examples**:

Valid:
```javascript
async captureAll() {
  // Use Promise.allSettled to capture all results
  const results = await Promise.allSettled(
    cameras.map(camera => camera.takePhoto())
  );

  const succeeded = results.filter(r => r.status === 'fulfilled');
  const failed = results.filter(r => r.status === 'rejected');

  return {
    totalCameras: cameras.length,
    succeeded: succeeded.length,
    failed: failed.length,
    details: results.map(r => ({
      cameraId: r.cameraId,
      status: r.status,
      error: r.reason?.message
    }))
  };
}
```

Invalid:
```javascript
async captureAll() {
  // Promise.all fails fast - don't know which succeeded
  try {
    await Promise.all(cameras.map(c => c.capture()));
    return { success: true };
  } catch (e) {
    return { success: false };  // No details about which failed
  }
}
```

**Enforcement (as implemented)**:
- JS: `CameraManager.captureAll()` (parallel and sequential) and `sendCommandToAll()` return one entry per camera with `status: 'success' | 'error'`; this is done with `Promise.all` plus a per-camera `.catch`, not literally `Promise.allSettled` (equivalent effect; the example above is the idealized form).
- `CameraDevice.takePhoto()` throws when the camera answers with a JSON-RPC error, so a refused capture is reported as an error, and transport timeouts / unplug failures also surface as per-camera errors. Tests: `CameraManager: ...` cases in `test/transport.test.mjs`.
- Android: `SyncCaptureEngine` aggregates per-camera `CaptureResult`s into `MultiCaptureResult` (`failedCameras`, `allSucceeded`).

**Not yet enforced** (intent kept; nothing in the code does this):
- Per-camera timing log lines beyond the single total-time `console.log`.

### INV-MULTI-004: Synchronization Accuracy Documentation

**Rule**: The system MUST document and log the actual synchronization accuracy. Users MUST be informed that software sync is best-effort (expected tens of milliseconds, not hardware-level < 1ms). The commonly quoted "~50 ms" figure has not been measured.

**Rationale**: Users making assumptions about "simultaneous" capture need realistic expectations. Scientific or professional use cases may require hardware sync which this software cannot provide.

**Examples**:

Valid:
```javascript
// Document sync mode and expected variance
<select id="syncMode">
  <option value="parallel">Parallel (Best Effort ~50ms)</option>
  <option value="sequential">Sequential (Reliable)</option>
</select>

// Log actual timing
async captureAll() {
  const startTime = performance.now();
  const results = await this._captureParallel();
  const totalTime = performance.now() - startTime;

  console.log(`Capture completed in ${totalTime}ms (mode: ${this.syncMode})`);
  return results;
}
```

Invalid:
```javascript
// Claim "simultaneous" without qualification
button.textContent = "Simultaneous Capture";  // Misleading

// No timing information
async captureAll() {
  await Promise.all(cameras.map(c => c.capture()));
  return { success: true };  // No timing data
}
```

**Enforcement (as implemented)**:
- JS: `captureAll()` logs total capture time and mode; each result carries a `timestamp`. `multi-camera.html` labels the modes "Parallel (Best Effort)" / "Sequential (Reliable)".
- Android: `SyncCaptureEngine` holds every camera at a `CaptureBarrier` (bounded wait) so their `dxo_photo_take` writes start together, records when each write finished (`CaptureResult.triggerWrittenAtNanos`), and reports `MultiCaptureResult.triggerSpreadNanos` (max - min) as "trigger spread (host-side)" in the UI and notification.
- **The "~50 ms" figure is unmeasured** on either platform (nothing was run against real hardware). The host-side trigger spread is not the shutter spread: the camera's own latency after receiving the command is unknown.

**Not yet enforced** (intent kept; nothing in the code does this):
- A measured variance figure.

---

## Notes

- These invariants should be reviewed and updated as the project evolves
- Breaking an invariant requires explicit justification and team discussion
- CI runs `npm test` (syntax check plus transport/manager tests against a fake device), the Android build, unit tests and lint. The earlier advisory grep "invariant checks" were removed; there is no automated invariant checker, so compliance is by tests where listed above and by review
- New features should consider which invariants apply and document compliance

---

**Last Updated**: 2026-11-04
**Document Version**: 2.0
**Total Invariants**: 15 (11 core: 3 data, 3 security, 3 consistency, 2 API; plus 4 multi-camera). The Android app applies the same invariants; it has none of its own.
