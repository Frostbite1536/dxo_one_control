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

DXO One Control is a web-based application for controlling DXO One cameras via USB. It provides programmatic control over your camera, enabling live view, photo capture, and advanced settings management directly from your web browser.

### Features

- **Single Camera Control**: Connect and control one DXO One camera
- **Multi-Camera Support**: Control up to 4 cameras simultaneously for:
  - 360° photography (camera array)
  - Stereoscopic/3D photography
  - Multi-angle product photography
  - Scientific/research capture
- **Live View**: Real-time preview from your camera
- **Full Camera Control**: Adjust ISO, aperture, exposure, focus, and more
- **Synchronized Capture**: Take photos across multiple cameras with minimal delay
- **DNG Post-Processing**: Resize and convert RAW files to JPEG

---

## Requirements

### Hardware

- **DXO One Camera**: One or more DXO One cameras
- **microUSB Cable**: REQUIRED - only microUSB connection is supported
  - ⚠️ **Lightning connector is NOT supported**
- **USB Ports**: One available USB port per camera for multi-camera setup

### Software

- **Web Browser**: Chrome, Edge, or Opera (WebUSB support required)
  - Chrome is recommended for best compatibility
  - Firefox and Safari do NOT support WebUSB
- **Operating System**: Windows, macOS, or Linux
- **Node.js**: Version 14.0.0 or higher (for post-processing tools only)

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

#### Option A: Using a Web Server

If you have a local web server running:

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

#### Option B: Using the Live Demo

Visit the online demo at: [https://dxo1demo.jsyang.ca/usb.html](https://dxo1demo.jsyang.ca/usb.html)

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

**Note**: Live view uses JPEG streaming and updates continuously. Performance depends on your USB connection and computer speed.

### Disconnecting

1. Stop live view if active
2. Click **"Disconnect"**
3. Your camera is now safe to unplug

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
- Best effort synchronization (~50ms variance)
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

The DXO One Control API provides extensive programmatic control. While the basic web interface shows status, you can access advanced settings through the JavaScript API.

### Available Settings

#### Image Format

- **RAW**: Enable/disable RAW (DNG) file capture
- **TNR (Temporal Noise Reduction)**: Combine 4 RAW files into SuperRAW Plus

#### Focus Settings

- **Focus Mode**:
  - `AF` (Auto Focus)
  - `MF` (Manual Focus)
- **AF Mode**:
  - `AF-S` (Single Shot)
  - `AF-C` (Continuous)
  - `AF-OD` (On Demand)
- **Manual Focus Distance**: 0-5 meters (inverse distance)
- **Tap to Focus**: Click coordinates to focus

#### Exposure Settings

- **Shooting Mode**:
  - Scene modes: Sport, Portrait, Landscape, Night
  - Priority modes: Program (P), Aperture (A), Shutter (S), Manual (M)

- **ISO**: Auto, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200

- **Aperture**: f/1.8 to f/11 (in 1/3 stops)

- **Exposure Time**: 1/20000s to 30s

- **EV Compensation**: -3.0 to +3.0 (in 1/3 stops)

- **Max ISO Limit**: Set upper ISO boundary for auto modes

- **Max Shutter Speed**: Set longest exposure for auto modes

#### Drive Mode

- **Shutter Mode**:
  - Single shot
  - Timelapse

- **Self Timer**: 0s, 2s, 10s delay

#### Image Quality

- **JPEG Quality**:
  - Fine (100%)
  - Normal (95%)
  - Basic (70%)

#### White Balance

- **Auto White Balance Intensity**:
  - Off
  - Slight
  - Medium
  - Strong

#### Metadata

- **Copyright**: Embed copyright text in EXIF
- **Artist**: Embed artist name in EXIF
- **GPS Data**: Set location information

#### Video

- **Video Quality**:
  - Standard (16 Mbps)
  - Better (22 Mbps)
  - Highest (30 Mbps)

### Programmatic Access

To access settings programmatically, use the JavaScript API:

```javascript
// Single camera mode
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
```

For multi-camera mode:

```javascript
// Create manager
const manager = new CameraManager();

// Connect camera
const camera = await manager.connectCamera();

// Access camera directly
await camera.transferOutRPC('dxo_setting_set', {
    type: 'iso',
    param: 'iso800'
});

// Send command to all cameras
await manager.sendCommandToAll('dxo_setting_set', {
    type: 'iso',
    param: 'iso800'
});
```

---

## Post-Processing

### resizeDNG.mjs

The `resizeDNG.mjs` script resizes and converts DNG (RAW) files to JPEG without modifying the color space.

#### Requirements

- Node.js 14.0.0 or higher
- DNG files from your DXO One camera

#### Usage

```bash
# Basic usage
node resizeDNG.mjs input.dng output.jpg

# Process multiple files
node resizeDNG.mjs input1.dng output1.jpg
node resizeDNG.mjs input2.dng output2.jpg

# Use in a script
for file in *.dng; do
    node resizeDNG.mjs "$file" "${file%.dng}.jpg"
done
```

#### Features

- Preserves original color space
- Maintains image quality
- Automatic resizing for efficient storage
- No external dependencies required

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
3. Check you're accessing via HTTP/HTTPS (not file://)
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
1. Use Parallel sync mode for best synchronization
2. Expect ~50ms variance (this is normal for software sync)
3. Connect cameras to different USB controllers if possible
4. Close other applications to reduce CPU load
5. For critical sync, consider Sequential mode

### Photo Not Captured

**Problem**: "Take photo" command doesn't work

**Solutions**:
1. Check camera has available storage space
2. Verify camera is in proper mode (not in review mode)
3. Wait for camera to complete previous operation
4. Check camera battery level
5. Disconnect and reconnect

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

- **microUSB only**: Lightning connector is not supported
- **USB bandwidth**: Multiple cameras on same USB controller may affect performance
- **Camera limit**: Maximum 4 cameras supported simultaneously
- **Cable quality**: Poor cables can cause connection issues

### Software Limitations

- **Browser dependency**: Requires WebUSB (Chrome/Edge/Opera only)
- **No mobile support**: Mobile browsers don't support WebUSB
- **Sync variance**: Multi-camera capture has ~50ms variance (software sync)
- **Platform specific**: Some features may behave differently on Windows/Mac/Linux

### Camera Limitations

- **Battery drain**: USB connection drains camera battery
- **Firmware dependent**: Some features depend on camera firmware version
- **Storage access**: Direct file retrieval not fully tested
- **Live view quality**: JPEG stream quality may vary

### API Limitations

- **Undocumented protocol**: DXO One USB protocol is reverse-engineered
- **Limited testing**: Some settings combinations untested
- **No official support**: Not officially supported by DXO
- **Experimental features**: Some features marked as TODO in code

---

## Additional Resources

### Project Links

- **Repository**: [https://github.com/jsyang/dxo1control](https://github.com/jsyang/dxo1control)
- **Live Demo**: [https://dxo1demo.jsyang.ca/usb.html](https://dxo1demo.jsyang.ca/usb.html)
- **License**: GPL 3.0

### Credits

This project builds upon:
- [rickdeck/DxO-One](https://github.com/rickdeck/DxO-One) - microUSB port enablement
- [yeongrokgim/dxo-one-firmware-study](https://github.com/yeongrokgim/dxo-one-firmware-study) - debug output

### Related Documentation

- [ARCHITECTURE.md](./ARCHITECTURE.md) - System architecture and design
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
node resizeDNG.mjs input.dng output.jpg
```

---

**Version**: 1.1.0
**Last Updated**: 2026-01-15
**Compatibility**: DXO One cameras via microUSB connection (Web and Android)
