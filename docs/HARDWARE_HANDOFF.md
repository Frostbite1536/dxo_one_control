# Hardware Handoff

**Purpose:** continue this project on real DXO One cameras. Everything up to this point was done and verified **without hardware**, against scripted fake cameras, a JVM harness and CI. This document lists what has been verified and what hasn't, how to test on real devices, and the protocol questions that only a real camera can answer.

**Status (2026-11-05):** `main` contains the audit (`docs/AUDIT-2026-11.md`) and three remediation PRs: Frostbite1536/dxo_one_control#5, #6 and #7. CI (JS tests; Android build, unit tests and lint) is green.

---

## 1. What is verified, and how

| Area | Verified by | Not verified |
|---|---|---|
| JS transport (`DxoTransport.js`): framing, demux, id matching, timeouts, late replies, live-view reassembly, barrier | `npm test` (35 tests against `test/fakeDxoOne.mjs`), mutation-checked | Real camera byte layout and timing |
| Web pages (`usb.html`, `multi-camera.html`) | Chromium with a stubbed `navigator.usb`: load under the CSP, error paths | Real WebUSB pairing, permissions, unplug events |
| Android transport (`DxoOneStreamDemux`, `DxoOneUsbProtocol` behind `UsbBulkIo`) | JUnit in CI: 27 demux tests, 11 protocol tests, 6 capture/barrier tests, barrier and result unit tests | Real `UsbDeviceConnection` behaviour, stalls, detach |
| Android connection, service, UI | Builds, lints and passes unit tests in CI. A scratch JVM harness drives `CameraConnection` against stub Android classes | Running on a phone at all |
| `resizeDNG.mjs` | Fake `sips` on Linux | Real macOS `sips` output, colour profile |

**Nothing has ever talked to a real DXO One since these changes.** The original upstream JS (`jsyang/dxo1control`) did work on hardware. The byte framing was kept identical to it, so that is the strongest evidence available.

---

## 2. Test setup

### Hardware
- **Cameras:** 1 to 4 DXO One cameras, **microUSB variant**. The Lightning variant is untested and probably not supported.
- **Web:** a desktop running Chrome, Edge or Opera, plus a powered USB hub for multiple cameras.
- **Android:** a phone with USB host (OTG) running Android 8 or later, ideally one on Android 13 or 14 (notification permission and foreground-service rules). Add a **powered** OTG hub for multiple cameras, since 4 cameras can exceed phone bus power.

### Capturing traffic (do this for every protocol question in §4)
- **Chrome:** open `chrome://usb-internals` to see devices and descriptors. For transfers:
  - Linux: `sudo modprobe usbmon`, then Wireshark on `usbmonN`.
  - Windows: USBPcap.
  - macOS: Wireshark USB capture needs extra setup; Linux is easiest.
- **JS logs:** the transport logs drops and stale replies with `console.debug`. Set the DevTools level to "Verbose".
- **Android:**
  ```bash
  adb logcat -s DxoOneUsbProtocol CameraConnection UsbDeviceManager CameraManagerService SyncCaptureEngine
  ```
- **Raw reply dump:** to see every reply in full, add a temporary log line in `DxoStreamDemux._finishRpc` (JS) or `DxoOneStreamDemux.finishRpc` (Kotlin). Don't commit it.

### Running things
```bash
npm test
```
```bash
python3 -m http.server 8000
```
Then open `http://localhost:8000/usb.html` or `http://localhost:8000/multi-camera.html`. To build and install the Android app:
```bash
cd android-app && ./gradlew installDebug
```

---

## 3. Hardware test plan

Do these in order. Each row says what to look for; record results and logs.

### Single camera, web (`usb.html`)
1. **Connect:** the status/settings JSON shows. Log whether replies carry `"id"` (question Q1).
2. **Take photo:** a reply arrives and the photo is on the camera. Measure the reply time (Q7).
3. **Live view start:** frames show smoothly, with no corrupt or partial frames.
4. **Take photo during live view:** the reply is matched, live view keeps going, and no "stale reply" or "rpc-parse" drops appear in the logs. This exercises the core demux assumption (Q2).
5. **Live view stop:** the image freezes. The camera presumably keeps streaming; check the USB capture (Q3).
6. **Settings:** run `setSettings.*` calls from the console (e.g. `iso.iso800()`, `shootingMode.night()`, `imageFormat.tnrOff()`). Each should resolve `true`. Log the `dxo_setting_applied` notification and the reply shape (Q5, Q6).
7. **Unplug while idle, and during live view:** the UI shows "Camera disconnected" and can reconnect.

### Multi-camera, web (`multi-camera.html`)
8. **Connect 2–4 cameras:** each gets its serial as its id.
9. **Capture All, parallel:** all succeed, and the "trigger spread (host-side)" is shown. Compare it with the shutter measurement in §5.
10. **Capture All while one camera has live view on:** all succeed, with no cross-talk.
11. **Unplug one camera mid-capture:** that camera fails and the others succeed.

### Android
12. **Install and open:** on Android 13 or later, the notification permission prompt appears.
13. **Plug in a camera:** the device is listed. Grant USB permission and connect. Saved settings are applied (watch the "not confirmed" errors for Q6). Live view starts.
14. **Background the app with a camera connected:** the foreground-service notification shows and the connection survives, including with the screen off.
15. **Disconnect all:** the service stops and the notification goes away.
16. **Repeat the web tests 4, 9 and 11 on Android.** Watch logcat for "bulk-IN halted; cleared" (Q10) and "Device not responding".
17. **Re-plug the same camera quickly after disconnecting:** it reconnects. This exercises the release-wait path.
18. **Rotate and navigate during and after a capture:** the capture summary snackbar shows once.

---

## 4. Protocol questions (answer with USB captures)

Each question lists where the current assumption lives in the code and what to change once it's answered.

**Q1. Does the camera echo the JSON-RPC `id` in replies?**
- **Assumption:** yes, if ids are present. A reply without an id is accepted for the single request in flight. After a timeout, as many id-less replies are dropped as there are timed-out requests that may still answer.
- **Code:** `DxoTransport._onMessage` and the `_sawReplyId` / `_lateIdlessExpected` fields in `DxoTransport.js`; `DxoOneStreamDemux` (`sawReplyId`, `lateIdlessExpected`) in Kotlin.
- **If ids are always echoed:** keep it as is. The fallback never triggers.
- **If ids are never echoed:** the fallback is the only matching. Test the timeout and recovery behaviour on hardware.

**Q2. Exact message header layout, and can messages interleave?**
- **Known:** requests use an 8-byte header `A3 BA D1 10 17 08 00 0C`, a 2-byte little-endian length and a 22-byte trailer, followed by the JSON and a NUL.
- **Assumptions for incoming data:**
  - A packet starting with `A3 BA D1 10` and then `{` (at byte 32, or in the next packet after a bare 32-byte header) is an RPC reply.
  - Everything else is live-view video.
  - The reply size is in bytes 8–9.
- **Please capture:**
  - the full 32-byte header of a reply and of a video packet (what are bytes 4–7 and 10–31?);
  - what bytes 8–9 hold in video headers (frame size?);
  - whether a reply can arrive in the middle of a JPEG frame.
- **Code:** `DxoStreamDemux.push` in `DxoTransport.js`; `DxoOneStreamDemux.feed` in Kotlin.
- **If the header carries a message type:** classify on that instead of the `{` heuristic.

**Q3. How do you stop live view on the camera?**
- **Known:** `dxo_camera_mode_switch {param:"view"}` starts it.
- **Unknown:** no stop command is known. Stopping is host-side only, and the video is discarded.
- **Try:** `dxo_camera_mode_switch` with `param` set to `"photo"`, `"still"`, `"capture"` or `"idle"`, and `dxo_idle`. Watch whether bulk-IN video stops and the camera stays responsive.
- **Code:** the TODOs in `dxo1usb.js` (`stopLiveView`), `CameraDevice.stopLiveView` and `CameraConnection.stopLiveView`.
- **If a stop command is found:** add it to `KNOWN_METHODS` / `DxoOneMethods.VALID_COMMANDS` if it's a new method, and send it on stop.

**Q4. USB product ID or IDs.**
- **Current filter:** vendor `0x2B8F` only (`PARAMS_DEVICE_REQUEST` in `dxo1usb.js` and `CameraManager.js`; `DxoOneConstants.VENDOR_ID`; `res/xml/device_filter.xml`).
- **Please record:** the product ID from `lsusb` or `chrome://usb-internals` for each variant, and whether the Lightning model enumerates at all.
- **Then:** add `productId` to the filters (INV-SEC-001).

**Q5. Setting confirmation flow.**
- **Assumption:** `dxo_setting_set` produces a `dxo_setting_applied` notification, then a reply with `result.type` equal to the setting type.
- **Please capture:**
  - both messages for a few settings;
  - whether the notification always comes;
  - what `params` it carries;
  - what an invalid value returns (`error` object? a `result`?).
- **Code:** `bindApplySetting` in `dxo1usb.js`; `CameraConnection.setSetting` and `CameraSettingsApplier` on Android.

**Q6. Valid setting values.**
- **Context:** the value tables come from the original reverse engineering (`dxo1usb.js` `setSettings`; Android `domain/model/CameraSettings.kt`). They're unverified on both platforms.
- **Specifically check:**
  - `iso` `"auto"`;
  - `exposure_time` `"auto"`, which Android deliberately does not send (`CameraSettingsApplier`);
  - the `shooting_mode` values;
  - `ev_bias` format;
  - `still_focusing_mode` / `af_mode` values;
  - `lighting_intensity`;
  - `photo_quality`;
  - `selftimer`;
  - `mf_inv_distance` (0–5 with 6 decimals?);
  - `copyright` / `artist` max length.
- **Note:** Android sends exactly what the Settings screen holds, so every value the screen can choose should be tried.

**Q7. Capture latency and timeouts.**
- **Please measure:**
  - time from the `dxo_photo_take` write to the reply (short exposure; 30 s exposure; RAW+TNR; nearly full card);
  - ordinary command reply times.
- **Current timeouts:** 5 s (JS) and 10 s (Android) for commands, 40 s for capture (`DEFAULT_TIMEOUT_MS` / `CAPTURE_TIMEOUT_MS` in `DxoTransport.js`; `DxoOneConstants`). Tune these from the data and align the two platforms.

**Q8. Init signature and `dxo_usb_flush_forced`.**
- **Assumption:** the camera sends the 32-byte init signature (`A3 BA D1 10 AB CD AB CD …02…`) after open, and the host must ACK it with the response signature.
- **Please capture:**
  - when it is sent;
  - whether it repeats mid-session, and if so when;
  - what `dxo_usb_flush_forced` means. It is currently ignored (dropped).
- **Code:** `DxoTransport.open` and `_onInitSignature`; `DxoOneUsbProtocol.initialize`.

**Q9. Interfaces and alternate settings.**
- **Assumption:** claim interfaces 0 and 1, select interface 1 alternate setting 1 (as upstream JS does), and use bulk endpoints on interface 0.
- **Please record:** the full descriptor dump (`lsusb -v` or `chrome://usb-internals`).
- **Code:** `dxo1usb.js` `open()`, `CameraDevice.initialize`, and `CameraConnection.initialize` / `findInterface`.

**Q10. Does the camera ever STALL an endpoint, and does CLEAR_FEATURE recover it?**
- **Android:** after 5 immediate bulk-IN failures it sends CLEAR_FEATURE(ENDPOINT_HALT) (`DxoOneUsbProtocol.clearHalt`; `STALL_CLEAR_AFTER_FAILURES`). After 50 immediate failures it declares the device dead.
- **JS:** handles `status: 'stall'` with `clearHalt('in')`.
- **Please check:** whether stalls happen (logcat "bulk-IN halted") and whether recovery works.

**Q11. Battery level field. The platforms currently disagree.**
- **JS** reads `status.result.battery` (`CameraDevice._refreshStatus`).
- **Android** reads `result["battery_level"]` (`CameraConnection.refreshStatus`).
- **Please capture:** a real `dxo_camera_status_get` reply, then fix whichever is wrong. Also record the other status fields (charging, card space, temperature?).

**Q12. Multi-camera bandwidth and power.**
- **Questions:** can 4 cameras stream live view at once on one hub, and what frame rate does each get? Do cameras brown out on phone power?
- **Current behaviour:** the Android app starts live view on every camera that connects, if the saved profile has live view enabled.

**Q13. File transfer.**
- **Known:** `dxo_fs_last_file_get` and `dxo_fs_cancel_get` exist (`dxo1usb.js` `command.fs`) but are completely untested.
- **Please capture:** the parameters (`param`, `offset`, `fetch_size`) and how file data comes back (inline JSON? raw bulk data?). This blocks the Android gallery and download features.

**Q14. GPS data format.**
- **Known:** `dxo_gps_data_set` uses a placeholder string (TODO in `dxo1usb.js`).
- **Please capture:** what the official app sends.

**Q15. Packet size and zero-length packets.**
- **Assumption:** 512-byte reads (`MAX_PACKETSIZE` / `MAX_PACKET_SIZE`).
- **Please confirm:** the endpoint `wMaxPacketSize`, and whether the camera sends zero-length packets at message boundaries.

---

## 5. Measuring real shutter sync

The app reports a **host-side trigger spread**: the times at which each camera's `dxo_photo_take` finished writing over USB. It does **not** know when each shutter fired. To measure the real spread:

1. **Clock:** show a millisecond clock on a fast display (e.g. a 240 Hz monitor running a web page with a ms counter), or an LED array driven by a microcontroller at 1 ms steps.
2. **Capture:** point every camera at it and run a parallel Capture All. Use a fast shutter (1/1000 s or faster).
3. **Compare:** read the time each photo shows. The spread between them is the real shutter spread; compare it with the reported host-side spread.
4. **Repeat:** about 20 times, with and without live view running, and on web and Android.

If the gap between the real and host-side spread turns out to be large and stable, a per-camera offset could be calibrated. If it's large and random, the camera's own latency dominates, and the docs (INV-MULTI-004) should say so.

---

## 6. Deferred work, intentionally not done

- **Android dependency upgrades and `targetSdk` 35+, KSP instead of kapt.** These change runtime behaviour (edge-to-edge, foreground-service rules) and need device testing. Lint lists them as `GradleDependency`, `OldTargetApi` and `KaptUsageInsteadOfKsp` warnings.
- **Gallery and image download on Android.** Blocked on Q13.
- **Snackbar on rotation.** If the screen stops while a capture-summary snackbar is showing, that summary is lost. It is never duplicated; accepted as is.
- **Tests that need real Android classes.** `CameraConnection` live view, interface selection and the `SyncCaptureEngine` end-to-end tests run only in a scratch JVM harness against stub Android classes, not in CI. Moving them in would need Robolectric or a further seam around `UsbDevice` and `Bitmap`.

## 7. Where to start reading

- `docs/ARCHITECTURE.md`: components and the transport rules.
- `docs/STATE_MACHINES.md`: connection, request and live-view lifecycles.
- `docs/INVARIANTS.md`: what is enforced and what isn't yet.
- `docs/AUDIT-2026-11.md`: original findings and remediation status.
- **Code entry points:**
  - JS: `DxoTransport.js`.
  - Android: `android-app/.../usb/DxoOneStreamDemux.kt`, `DxoOneUsbProtocol.kt` and `CameraConnection.kt`.
