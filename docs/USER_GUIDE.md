# DXO One Control - User Guide

## Table of Contents

1. [Introduction](#introduction)
2. [Requirements](#requirements)
3. [Getting Started](#getting-started)
4. [Single Camera Mode](#single-camera-mode)
5. [Multi-Camera Mode](#multi-camera-mode)
6. [Camera Settings](#camera-settings)
7. [Post-Processing](#post-processing)
8. [Troubleshooting](#troubleshooting)
9. [Known Limitations](#known-limitations)

---

## Introduction

DXO One Control controls DXO One cameras over USB. It has two web pages (`usb.html` for one camera, `multi-camera.html` for up to four) that run in a Chromium-based browser using WebUSB, plus a JavaScript API for scripting settings. A separate native Android app (see [android-app/README.md](../android-app/README.md)) does multi-camera capture from a phone or tablet.

### Features

- **Single Camera Control**: Connect and control one DXO One camera
- **Multi-Camera Support**: Control up to 4 cameras simultaneously for:
  - 360° photography (camera array)
  - Stereoscopic/3D photography
  - Multi-angle product photography
  - Scientific/research capture
- **Live View**: Real-time preview from your camera
- **Camera Settings API**: Adjust ISO, aperture, exposure, focus, and more from JavaScript (the web pages themselves do not expose these controls)
- **Multi-Camera Capture**: Trigger photos on all connected cameras in parallel or one after another
- **DNG Post-Processing**: Resize and convert RAW files to JPEG (macOS only)

---

## Requirements

### Hardware

- **DXO One Camera**: One or more DXO One cameras
- **microUSB Cable**: REQUIRED - only microUSB connection has been tested
  - ⚠️ The Lightning variant is **not tested and not supported**: the device filter only matches vendor ID `0x2b8f`
- **USB Ports**: One available USB port per camera for multi-camera setup

### Software

- **Web Browser**: Chrome, Edge, or Opera (WebUSB support required)
  - Firefox and Safari do NOT support WebUSB
  - The page must be served over HTTPS or from `localhost` (not `file://`)
- **Operating System**: Windows, macOS, or Linux
- **Node.js**: Version 18 or higher (for `npm test` and `resizeDNG.mjs`)
- **macOS**: required for `resizeDNG.mjs` only (it uses the `sips` tool)

### Permissions

- **USB Device Access**: Your browser will request permission to access the camera
- **User Interaction**: WebUSB requires explicit user permission for each camera connection

---

## Getting Started

### Step 1: Connect Your Camera

1. Connect your DXO One camera to your computer using a **microUSB cable**
2. Ensure the camera is powered on
3. Wait for your operating system to recognize the USB device

### Step 2: Launch the Web Interface

#### Option A: Using a Local Web Server

WebUSB only works on HTTPS or `localhost`, so serve the folder:

```bash
# Navigate to the project directory
cd dxo_one_control

# Start a simple HTTP server (Python 3)
python3 -m http.server 8000

# Or using Node.js
npx http-server -p 8000
```

Then open your browser to:
- Single camera: `http://localhost:8000/usb.html`
- Multi-camera: `http://localhost:8000/multi-camera.html`

#### Option B: Upstream Demo

The upstream project hosts a demo at [https://dxo1demo.jsyang.ca/usb.html](https://dxo1demo.jsyang.ca/usb.html). It is run by the upstream author and may not reflect this fork's code.

---

## Single Camera Mode

Single camera mode (`usb.html`) provides a simple interface for controlling one DXO One camera.

### Connecting Your Camera

1. Open `usb.html` in your browser
2. Click the **"Connect"** button
3. In the browser dialog, select your DXO One camera (vendor ID: 0x2b8f)
4. Click **"Connect"** in the dialog
5. Wait for the connection to complete

Once connected:
- The **Connect** button changes to **Disconnect**
- Camera settings and status are displayed
- Live view and photo controls become enabled

### Taking Photos

1. Ensure your camera is connected
2. Click the **"Take a photo"** button
3. The photo is saved to your camera's internal storage
4. A confirmation message appears when complete

### Using Live View

1. Click **"Start live view"** to begin streaming
2. A real-time preview appears from your camera
3. Use this to frame your shots and check focus
4. Click **"Stop live view"** when finished

**Note**: Live view uses JPEG streaming and updates continuously. Performance depends on your USB connection and computer speed. Stopping live view stops the page from displaying frames, but the camera may keep streaming: no command to take it out of view mode is known.

### Disconnecting

1. Stop live view if active
2. Click **"Disconnect"**
3. Your camera is now safe to unplug

If you unplug a camera without disconnecting, any command in progress on it fails with an error.

---

## Multi-Camera Mode

Multi-camera mode (`multi-camera.html`) enables controlling up to 4 DXO One cameras simultaneously.

### Connecting Multiple Cameras

1. Open `multi-camera.html` in your browser
2. Click **"+ Connect Camera"**
3. Select the first camera in the browser dialog
4. Wait for initialization to complete
5. Repeat steps 2-4 for additional cameras (up to 4 total)

Each camera appears in the left panel with:
- **Display name**: Camera serial number (last 4 digits) or custom nickname
- **Status**: Connected/Disconnected indicator
- **Battery level**: If available
- **Live view indicator**: Shows if live view is active

### Camera Management

#### Renaming Cameras

1. Select a camera from the list
2. Click **"✏️ Rename"** in the detail panel
3. Enter a friendly nickname (e.g., "Front", "Left", "Right", "Back")
4. The nickname appears in the camera list

#### Selecting a Camera

- Click any camera in the left panel to select it
- Selected camera details appear in the right panel
- You can control each camera individually when selected

#### Disconnecting Cameras

- **Single camera**: Select it and click **"Disconnect"**
- **All cameras**: Click **"Disconnect All"** (confirmation required)

### Synchronized Multi-Camera Capture

#### Capture Modes

**Parallel Mode (Default)**
- All cameras trigger simultaneously
- Best-effort synchronization (the commands are sent back-to-back; the actual spread has not been measured)
- Fastest option for time-sensitive captures
- Recommended for most use cases

**Sequential Mode**
- Cameras trigger one at a time
- More reliable but slower
- Use if parallel mode causes issues

To change modes:
1. Use the **"Sync Mode"** dropdown in the left panel
2. Select "Parallel" or "Sequential"

#### Capturing on All Cameras

1. Ensure all desired cameras are connected
2. Click **"📸 Capture All"**
3. Wait for completion (time displayed in results)
4. Review the results showing success/failure for each camera

The results display shows:
- Total capture time
- Number of successful captures
- Number of failed captures
- Per-camera status with any error messages

### Individual Camera Control

When a camera is selected, you can:

- **📸 Take Photo**: Capture on this camera only
- **Start/Stop Live View**: View real-time preview
- **✏️ Rename**: Set a custom nickname
- **Disconnect**: Remove this camera

### Live View in Multi-Camera Mode

1. Select a camera from the list
2. Click **"Start Live View"**
3. The preview appears in the main content area
4. Only one camera can show live view at a time
5. Starting live view on another camera automatically stops the previous one

---

## Camera Settings

The web pages only offer connect, live view, and photo capture. Camera settings are available through the JavaScript API (`setSettings` in `dxo1usb.js`). Each entry below is a function that sends one setting command to the camera.

### Available Settings

All of these live under `camera.command.setSettings`:

- **`imageFormat`**: `rawOn`, `rawOff`, `tnrOn`, `tnrOff` (TNR combines four RAW files into one SuperRAW Plus file)
- **`stillFocusingMode`**: `AF`, `MF`
- **`afMode`**: `AF_S` (single shot), `AF_C` (continuous), `AF_OD` (on demand)
- **`mfInvDistance(value)`**: manual-focus inverse distance, clamped to 0–5 and sent with 6 decimals; without an argument it sends `0.000000`
- **`shootingMode`**: `sport`, `portrait`, `landscape`, `night`, `program`, `aperture`, `shutter`, `manual`
- **`iso`**: `auto`, `iso100` ... `iso51200`
- **`aperture`**: `f1_8` through `f11` (1.8, 2, 2.2, 2.5, 2.8, 3.2, 3.5, 4, 4.5, 5, 5.6, 6.3, 7.1, 8, 9, 10, 11)
- **`exposureTime`**: `t1_20000` ... `t1_1`, `t2_1`, `t4_1`, `t8_1`, `t15_1`, `t30_1`
- **`evBias`**: `m3_0` ... `zero` ... `p3_0` (-3.0 to +3.0 in roughly 1/3 steps)
- **`maxIso`**: `auto` (no limit), `iso100` ... `iso51200`
- **`maxShutter`**: `auto`, `t15_1`, `t2_1`, `t1_3`, `t1_6`, `t1_13`, `t1_25`, `t1_50`, `t1_100`, `t1_200`, `t1_400`
- **`shutterMode`**: `single`, `timelapse`, and self-timer `timer0s`, `timer2s`, `timer10s`
- **`imageQuality`**: `fine` (100), `normal` (95), `basic` (70)
- **`autoWhiteBalance`**: `off`, `slight`, `medium`, `strong`
- **`metadata`**: `copyright(text)`, `artist(text)`; without an argument they send the placeholder strings `COPYRIGHT` / `ARTIST`
- **`videoQuality`**: `standard` (16 Mbps), `better` (22 Mbps), `highest` (30 Mbps)

Other commands under `camera.command`:

- `getAllSettings()`, `getStatus()`, `takePhoto()`, `sleep()`
- `focus(x, y)` (tap to focus; origin is the bottom-left corner), `flushFocus()`
- `getDigitalZoom()`
- `liveView.start(callback)`, `liveView.stop()`
- `setGPSData()` and `fs.*` are experimental: GPS sends a placeholder string and file retrieval is marked as untested in the code.

### Timeouts and Errors

- Commands time out after 5 seconds by default, and after 40 seconds for taking a photo (long exposures go up to 30 s).
- Each camera handles one command at a time; further commands wait their turn.
- Unplugging a camera fails its pending command.

### Programmatic Access

Single camera (from a module script, with the page served over HTTPS or localhost; `open()` must be called from a user gesture such as a click):

```javascript
import DXOONE from './dxo1usb.js';

const camera = await DXOONE.open();

// Get all current settings
const settings = await camera.command.getAllSettings();

// Get camera status
const status = await camera.command.getStatus();

// Change ISO
await camera.command.setSettings.iso.iso800();

// Change aperture
await camera.command.setSettings.aperture.f2_8();

// Enable RAW
await camera.command.setSettings.imageFormat.rawOn();

// Disconnect
await camera.close();
```

Multi-camera:

```javascript
import { CameraManager } from './CameraManager.js';

const manager = new CameraManager();

// Connect cameras (each call shows the browser's device picker)
const camera = await manager.connectCamera();

// Access a camera directly
await camera.transferOutRPC('dxo_setting_set', {
    type: 'iso',
    param: 'iso800'
});

// Send a command to all cameras
await manager.sendCommandToAll('dxo_setting_set', {
    type: 'iso',
    param: 'iso800'
});

// Capture on all cameras
manager.setSyncMode('parallel'); // or 'sequential'
const results = await manager.captureAll();
```

---

## Post-Processing

### resizeDNG.mjs

The `resizeDNG.mjs` script converts every DNG (RAW) file in a directory to a quarter-size JPEG.

#### Requirements

- **macOS** (it shells out to the built-in `sips` tool, so it does not work on Windows or Linux)
- Node.js 18 or higher
- A directory containing `.dng` files from your DXO One camera

#### Usage

```bash
node resizeDNG.mjs <directory>
```

- Every file in `<directory>` with a `.dng` extension (case-insensitive) is converted.
- Output goes to `<directory>/resized/<name>.jpg`, at 25% of 5540x3688 pixels (1385x922).
- If one file fails, the error is printed and the rest are still processed; the exit code is 1 at the end.
- Run with no argument to print usage.

#### Notes

- The script does no explicit colorspace or profile handling; it relies on `sips` defaults. Whether the original colorspace is preserved has not been verified.
- No npm dependencies are required.

---

## Troubleshooting

### Camera Not Detected

**Problem**: Browser doesn't show camera in device picker

**Solutions**:
1. Verify you're using a **microUSB cable** (not Lightning)
2. Check camera is powered on
3. Try a different USB port
4. Check cable is data-capable (not charge-only)
5. Restart your camera
6. Try a different USB cable

### WebUSB Not Supported

**Problem**: Error message about WebUSB not available

**Solutions**:
1. Switch to Chrome, Edge, or Opera browser
2. Ensure browser is up to date
3. Check the page is served over HTTPS or from `localhost` (not `file://` or plain HTTP on another host)
4. Verify WebUSB isn't disabled in browser flags

### Connection Drops Unexpectedly

**Problem**: Camera disconnects during use

**Solutions**:
1. Check USB cable connection is secure
2. Ensure camera battery isn't depleted
3. Try a different USB port (preferably directly on computer, not via hub)
4. Disable USB power management in OS settings
5. Close other applications using USB resources

### Live View Not Working

**Problem**: Live view doesn't start or shows black screen

**Solutions**:
1. Stop live view and restart it
2. Disconnect and reconnect the camera
3. Check camera lens cap is removed
4. Verify camera isn't in sleep mode
5. Close and reopen the browser

### Multi-Camera Synchronization Issues

**Problem**: Cameras don't capture at the same time

**Solutions**:
1. Use Parallel sync mode for the closest timing (it is software-triggered, so exact simultaneity is not guaranteed)
2. Connect cameras to different USB controllers if possible
3. Close other applications to reduce CPU load
4. If parallel mode causes errors, try Sequential mode

### Photo Not Captured

**Problem**: "Take photo" command doesn't work

**Solutions**:
1. Check camera has available storage space
2. Verify camera is in proper mode (not in review mode)
3. Wait for camera to complete previous operation
4. Check camera battery level
5. Disconnect and reconnect
6. A command that gets no reply fails with a timeout error (5 s, or 40 s for photos)

### Browser Permissions Error

**Problem**: Permission denied for USB access

**Solutions**:
1. Reload the page and try again
2. Check browser permissions settings
3. Clear browser cache and cookies
4. Try incognito/private browsing mode
5. Restart browser

### Maximum Cameras Reached

**Problem**: Can't connect more than 4 cameras

**Solution**: This is by design. Disconnect a camera to add another. The 4-camera limit prevents resource exhaustion and ensures stable operation.

---

## Known Limitations

### Hardware Limitations

- **microUSB only**: the Lightning variant is not tested or supported
- **USB bandwidth**: Multiple cameras on same USB controller may affect performance
- **Camera limit**: Maximum 4 cameras supported simultaneously
- **Cable quality**: Poor cables can cause connection issues

### Software Limitations

- **Browser dependency**: Requires WebUSB (Chrome/Edge/Opera only)
- **No mobile browser support**: Mobile browsers don't support WebUSB; on Android use the native app in [android-app/README.md](../android-app/README.md)
- **Software sync only**: Multi-camera capture is triggered by software; timing spread has not been measured
- **Post-processing is macOS only**: `resizeDNG.mjs` needs `sips`
- **Platform specific**: Some features may behave differently on Windows/Mac/Linux

### Camera Limitations

- **Battery drain**: USB connection drains camera battery
- **Firmware dependent**: Some features depend on camera firmware version
- **Storage access**: Direct file retrieval (`fs.*`) is untested
- **Live view quality**: JPEG stream quality may vary

### API Limitations

- **Undocumented protocol**: DXO One USB protocol is reverse-engineered
- **Limited testing**: Some settings combinations untested
- **No official support**: Not officially supported by DXO
- **Experimental features**: Some features marked as TODO in code

---

## Additional Resources

### Project Links

- **Upstream repository**: [https://github.com/jsyang/dxo1control](https://github.com/jsyang/dxo1control)
- **Upstream demo** (may not reflect this fork): [https://dxo1demo.jsyang.ca/usb.html](https://dxo1demo.jsyang.ca/usb.html)
- **Android app**: [android-app/README.md](../android-app/README.md)
- **License**: GPL 3.0

### Credits

This project builds upon:
- [rickdeck/DxO-One](https://github.com/rickdeck/DxO-One) - microUSB port enablement
- [yeongrokgim/dxo-one-firmware-study](https://github.com/yeongrokgim/dxo-one-firmware-study) - debug output

### Related Documentation

- [ARCHITECTURE.md](./ARCHITECTURE.md) - System architecture and design
- [STATE_MACHINES.md](./STATE_MACHINES.md) - State machines
- [INVARIANTS.md](./INVARIANTS.md) - Code invariants and constraints
- [ROADMAP.md](./ROADMAP.md) - Future development plans
- [DECISIONS.md](./DECISIONS.md) - Design decisions and rationale

---

## Quick Reference

### Single Camera Mode

```
1. Open usb.html in Chrome/Edge/Opera
2. Click "Connect"
3. Select DXO One camera
4. Click "Start live view" or "Take a photo"
```

### Multi-Camera Mode

```
1. Open multi-camera.html in Chrome/Edge/Opera
2. Click "+ Connect Camera" for each camera
3. Optionally rename cameras
4. Select sync mode (Parallel/Sequential)
5. Click "Capture All" to shoot on all cameras
```

### Post-Processing

```bash
node resizeDNG.mjs <directory>   # macOS only
```

### Tests

```bash
npm test   # Node.js 18+
```

---

**Version**: 1.0.0 (package.json)
**Compatibility**: DXO One cameras via microUSB connection
