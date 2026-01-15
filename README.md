# dxo1control

Tools to control your DXO One camera via USB - supporting both web browsers and Android devices.

> **Warning**: Only tested with microUSB connection! Lightning connector is NOT supported.

## Features

### Multi-Camera Control (up to 4 cameras)
- **CameraManager.js** - Orchestrate up to 4 DXO One cameras simultaneously
- **CameraDevice.js** - Per-camera state management and communication
- Synchronized capture with parallel (~50ms) or sequential modes
- Use cases: 360° photography, stereoscopic/3D, multi-angle product shots

### Web Interface (WebUSB)
- **usb.html** - Single camera control interface
- **multi-camera.html** - Multi-camera control interface
- Live demo: [https://dxo1demo.jsyang.ca/usb.html](https://dxo1demo.jsyang.ca/usb.html)
- Requires Chrome, Edge, or Opera (WebUSB support)

### Android App
- **android-app/** - Native Android application for multi-camera control
- USB Host (OTG) support for direct camera connection
- Material Design 3 UI with Jetpack Compose
- Background service for persistent connections

### Post-processing
- **resizeDNG.mjs** - Batch resize and convert DNGs to JPGs
- Preserves original colorspace (critical for RAW processing)

## Quick Start

### Web Interface
```bash
# Start a local server
python3 -m http.server 8000
# Or
npx http-server -p 8000

# Open in browser
# Single camera: http://localhost:8000/usb.html
# Multi-camera: http://localhost:8000/multi-camera.html
```

### Android App
```bash
cd android-app
./gradlew assembleDebug
# Install APK on device with USB OTG support
```

### Post-Processing
```bash
node resizeDNG.mjs /path/to/dng/directory
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md) - System design and components
- [Invariants](docs/INVARIANTS.md) - Code invariants and constraints
- [User Guide](docs/USER_GUIDE.md) - Usage instructions
- [Roadmap](docs/ROADMAP.md) - Future development plans

## Requirements

- **Hardware**: DXO One camera with microUSB cable
- **Web**: Chrome, Edge, or Opera (WebUSB support)
- **Android**: Device with USB OTG support (Android 5.0+)
- **Node.js**: 18.0.0+ for post-processing tools

## Credits

This repo is a direct fork of https://github.com/jsyang/dxo1control & uses AI coding agents to introduce new features.

This work is made possible thanks to these previous findings:
- [rickdeck/DxO-One](https://github.com/rickdeck/DxO-One) - microUSB port enablement
- [yeongrokgim/dxo-one-firmware-study](https://github.com/yeongrokgim/dxo-one-firmware-study) - debug output

## License

All code in this repo is open source licensed under GPL3.0. See the LICENSE file.
