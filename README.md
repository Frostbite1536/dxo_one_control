# dxo1control

Tools to control your DXO One camera over USB, from a Chromium browser (WebUSB) or from an Android phone/tablet (USB OTG). This repo is a fork of [jsyang/dxo1control](https://github.com/jsyang/dxo1control) that adds multi-camera control and an Android app.

Only the microUSB DXO One is supported: the device filter matches vendor ID `0x2b8f`, and the Lightning variant has not been tested.

## Entry points

| Entry point | What it does | Needs |
|---|---|---|
| `usb.html` | Single camera: Connect, live view, Take photo | Chromium browser (Chrome, Edge, Opera) |
| `multi-camera.html` | Up to 4 cameras: capture all (parallel or sequential), per-camera live view, rename | Chromium browser |
| `android-app/` | Native Kotlin/Compose app controlling up to 4 cameras over USB OTG | Android device with USB host, see [android-app/README.md](android-app/README.md) |

Upstream live demo (may not reflect this fork): [https://dxo1demo.jsyang.ca/usb.html](https://dxo1demo.jsyang.ca/usb.html)

## Quick start

### Web

WebUSB only works on `https://` pages or `http://localhost`, so serve the folder rather than opening the file:

```bash
python3 -m http.server 8000
```

Then open `http://localhost:8000/usb.html` (single camera) or `http://localhost:8000/multi-camera.html` (multi-camera) in Chrome, Edge or Opera, click **Connect**, and pick the camera in the browser dialog.

Programmatic use (`dxo1usb.js` exports `{ open }` and re-exports `CameraDevice` and `CameraManager`):

```javascript
import DXOONE from './dxo1usb.js';
const camera = await DXOONE.open();   // must run from a user gesture
await camera.command.takePhoto();
```

Code layout: `dxo1usb.js` (single-camera API), `CameraDevice.js`, `CameraManager.js`, and `DxoTransport.js` (shared transport: read pump, one command at a time per camera, timeouts).

### Android

```bash
cd android-app
./gradlew assembleDebug     # JDK 17, Android SDK 34
./gradlew installDebug
```

### Post-processing

```bash
node resizeDNG.mjs <directory-of-dngs>
```

Converts every `.dng` in the directory to a quarter-size JPEG in `<directory>/resized/`. macOS only (uses `sips`). See the [User Guide](docs/USER_GUIDE.md#post-processing).

## Tests

```bash
npm test
```

Syntax-checks every JS file, then runs `node --test test/*.test.mjs` (transport tests against a fake camera, `test/fakeDxoOne.mjs`). Requires Node.js 18 or newer. Android unit tests: `cd android-app && ./gradlew testDebugUnitTest`.

## Documentation

- [User Guide](docs/USER_GUIDE.md)
- [Architecture](docs/ARCHITECTURE.md), [State machines](docs/STATE_MACHINES.md), [Invariants](docs/INVARIANTS.md)
- [Decisions](docs/DECISIONS.md), [Roadmap](docs/ROADMAP.md)
- [Android app](android-app/README.md) and [its design notes](docs/ANDROID_MULTI_CAMERA_APP.md)

## Credits

This work is made possible thanks to these previous findings:
  - https://github.com/rickdeck/DxO-One allowing microUSB port to be used
  - https://github.com/yeongrokgim/dxo-one-firmware-study figuring out debug output

## License
All code in this repo is open source licensed under GPL3.0. See the LICENSE file.
