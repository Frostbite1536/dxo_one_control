# Roadmap

This roadmap outlines the planned development phases for dxo1control. The project already has working USB control and post-processing tools - this roadmap focuses on improvements, documentation, and new features.

---

## Phase 1: Foundation & Documentation ✅ (Current Phase)

**Goal**: Establish Safe Vibe Coding infrastructure and comprehensive documentation for the existing codebase.

**Status**: In Progress

### Features Included

- [x] Core USB control functionality (`dxo1usb.js`)
- [x] WebUSB demo interface (`usb.html`)
- [x] DNG post-processing tool (`resizeDNG.mjs`)
- [x] Uint8Array utilities (`u8a.js`)
- [x] **ARCHITECTURE.md** - System design documentation
- [x] **INVARIANTS.md** - Non-negotiable rules and constraints
- [x] **ROADMAP.md** - This implementation plan
- [x] **prompts/engineering.md** - Customized coding guidelines
- [x] **Multi-camera support** - CameraManager, CameraDevice (early delivery from Phase 4)
- [x] **Multi-camera UI** - multi-camera.html interface
- [x] **Android App** - Native Android multi-camera application
- [x] **CI/CD Pipeline** - Automated testing and Android build validation
- [ ] **API Documentation** - JSDoc comments and generated docs
- [ ] **Usage Examples** - Code samples and tutorials

### Early Delivery: Multi-Camera Support 🎉

Multi-camera support has been implemented ahead of schedule (originally planned for Phase 4):

- **CameraManager.js** - Manages up to 4 cameras simultaneously
- **CameraDevice.js** - Per-camera state and communication
- **multi-camera.html** - Web UI for multi-camera control
- **Synchronized capture** - Parallel (~50ms) or sequential modes
- **New invariants** - INV-MULTI-001 through INV-MULTI-004

Use cases enabled:
- 360° photography (camera array)
- Stereoscopic/3D photography
- Multi-angle product photography
- Scientific/research capture

### Explicit Exclusions (Not in Phase 1)

- Lightning connector support
- Native desktop application
- Automated testing suite (comes in Phase 2)
- Performance optimizations
- Advanced camera features
- Mobile app support

### Success Criteria

- All existing code is documented
- Architecture and invariants are clearly defined
- CI pipeline runs successfully
- Contributors have clear guidelines
- Demo site is stable and accessible

---

## Phase 2: Quality & Testing

**Goal**: Establish comprehensive testing, improve code quality, and ensure reliability.

**Status**: Planned

### Features Included

- **Test Suite Setup**
  - Unit tests for USB communication functions
  - Integration tests for camera command/response flow
  - Tests for DNG processing utilities
  - Browser compatibility tests
  - Mock USB device for testing

- **Code Quality Improvements**
  - ESLint/Prettier configuration
  - TypeScript type definitions (optional)
  - Code coverage tracking (target: 80%+)
  - Error handling improvements
  - Logging and debugging utilities

- **Enhanced Error Recovery**
  - Auto-reconnect on disconnect
  - Command retry logic
  - Better error messages
  - State recovery mechanisms

- **Documentation Expansion**
  - API reference documentation
  - Camera command protocol documentation
  - Troubleshooting guide
  - Contributing guidelines
  - Code of conduct

### Explicit Exclusions (Not in Phase 2)

- New camera features
- Lightning connector support
- GUI improvements
- Performance optimizations
- Multi-camera support

### Success Criteria

- Test coverage ≥ 80%
- All CI checks pass
- Zero known critical bugs
- All public APIs documented
- Contributing guide available

---

## Phase 3: Enhancement & Features

**Goal**: Add new features, improve user experience, and expand camera control capabilities.

**Status**: Planned

### Features Included

- **Enhanced Camera Control**
  - Real-time preview/live view
  - Advanced settings control (ISO, shutter, aperture)
  - Burst mode support
  - Custom shooting modes
  - Camera settings persistence
  - Settings presets

- **Improved Post-Processing**
  - Batch processing UI
  - Multiple output formats (TIFF, PNG, WebP)
  - Custom resize algorithms
  - Metadata preservation and editing
  - EXIF data manipulation
  - Processing queue management

- **Better Web Interface**
  - Modern UI redesign
  - Settings management panel
  - Image gallery/preview
  - Download manager
  - Connection status indicators
  - Keyboard shortcuts

- **Integration Features**
  - Export to cloud storage
  - Integration with photo editing tools
  - Webhook support for automation
  - REST API for camera control

### Explicit Exclusions (Not in Phase 3)

- Lightning connector (requires reverse engineering)
- Native desktop app
- Mobile app
- Multi-camera support
- Firmware updates
- Video capture

### Success Criteria

- Real-time preview works reliably
- Batch processing handles 100+ images
- Web UI is responsive and intuitive
- Settings persist across sessions
- At least 2 integration examples

---

## Phase 4: Polish & Advanced Features

**Goal**: Optimize performance, add advanced features, and explore new possibilities.

**Status**: Future

### Features Included

- **Performance Optimizations**
  - Web Workers for image processing
  - Streaming image transfer
  - Memory usage optimization
  - Parallel batch processing
  - Lazy loading for large galleries

- **Advanced Features**
  - ~~Multi-camera support~~ ✅ (Delivered in Phase 1)
  - Remote camera control over network
  - Custom post-processing filters/plugins
  - RAW histogram analysis
  - Focus peaking visualization
  - Timelapse automation

- **Native Applications**
  - Electron desktop app
  - Cross-platform packaging
  - Native file system integration
  - System tray integration
  - Auto-update mechanism

- **Community Features**
  - Plugin/extension system
  - Community preset sharing
  - User-contributed filters
  - Example gallery
  - Community documentation

- **Research & Exploration**
  - Lightning connector protocol research
  - Firmware update capabilities (if safe)
  - Advanced camera diagnostics
  - Custom firmware features

### Explicit Exclusions (Not in Phase 4)

- Firmware modification (too risky)
- Cloud hosting of the service
- Commercial features
- Paid plugin marketplace

### Success Criteria

- Desktop app available for Windows/Mac/Linux
- Performance benchmarks show 2x improvement
- Plugin system has at least 5 community plugins
- ~~Multi-camera support works with 4+ cameras~~ ✅ (Delivered in Phase 1)
- No critical performance bottlenecks

---

## Long-term Vision

### Potential Future Directions

1. **Mobile Support**
   - Android app using WebUSB
   - iOS app (if Lightning protocol is reverse-engineered)
   - Mobile-optimized web interface

2. **Advanced Automation**
   - Scripting support for camera control
   - Event-driven automation
   - Integration with smart home systems
   - Scheduled captures

3. **Professional Features**
   - Tethered shooting for studio work
   - Color calibration tools
   - Advanced RAW processing
   - Professional workflow integration

4. **Community & Ecosystem**
   - Plugin marketplace
   - Preset library
   - User forums
   - Tutorial videos
   - Community challenges

### Research Areas

- **Lightning Connector**: Reverse-engineer the Lightning protocol for iOS connectivity
- **Firmware Analysis**: Deep dive into camera firmware for advanced features
- **Protocol Extensions**: Discover undocumented camera commands
- **Performance**: Profile and optimize critical paths

---

## Versioning Strategy

- **v1.x**: Phase 1-2 (Foundation & Testing)
- **v2.x**: Phase 3 (Enhancement & Features)
- **v3.x**: Phase 4 (Polish & Advanced)
- **v4.x**: Future phases (Mobile, Professional)

### Semantic Versioning

- **Major**: Breaking API changes, major feature additions
- **Minor**: New features, backward-compatible changes
- **Patch**: Bug fixes, documentation updates

---

## Contributing to the Roadmap

This roadmap is a living document. Contributions and suggestions are welcome!

### How to Suggest Features

1. Open an issue with the `feature-request` label
2. Describe the feature and its benefits
3. Indicate which phase it might fit into
4. Discuss implementation approach

### Prioritization Criteria

Features are prioritized based on:
- **Impact**: How many users benefit?
- **Effort**: How complex is implementation?
- **Risk**: Could it introduce bugs or instability?
- **Dependencies**: Does it block other features?
- **Community Interest**: How many users requested it?

---

## Notes

- Phases are flexible and may be adjusted based on community feedback
- Phase completion criteria must be met before moving to the next phase
- Critical bugs can be fixed in any phase (not limited to Phase 2)
- Community contributions can accelerate the roadmap

---

## Research Discoveries from Community Repositories

**Sources**: [rickdeck/DxO-One](https://github.com/rickdeck/DxO-One) & [yeongrokgim/dxo-one-firmware-study](https://github.com/yeongrokgim/dxo-one-firmware-study)

This section documents discoveries from community DXO One reverse-engineering efforts that could enhance dxo_one_control.

### Hardware & Architecture Insights

**Dual-OS Architecture** (Not Currently Leveraged)
- **RTOS (ThreadX)**: Handles main camera functionality, display, and image processing
- **Linux OS**: Manages Wi-Fi connectivity, runs Dropbear SSH server
- **SoC**: Ambarella A9S35 (same as GoPro Hero ≤5)
- **Storage Paths**: C:\ for SD card, A:\ and B:\ for internal calibration data

*Potential Use*: Understanding the dual-OS architecture could enable simultaneous USB + Wi-Fi control, or SSH-based debugging during development.

### Autoexec Script System 🎯 HIGH PRIORITY

**Discovery**: Camera executes `autoexec.ash` files from SD card on boot
- **Format**: AmbaShell scripts (DOS-like syntax)
- **Capabilities**: Configure camera settings, enable logging, switch USB modes
- **Requirements**: Unix line endings (LF), trailing line break mandatory

*Potential Use*:
- Automated camera initialization for multi-camera rigs
- Pre-configure cameras before USB control takeover
- Enable diagnostic logging for troubleshooting
- Switch to USB networking mode automatically

**Example Applications**:
```ash
# C:\autoexec.ash - Example configuration script
t app msg_q 1                    # Enable message queue logging
t dxo set_param focus_mode 2     # Set hyperfocal distance mode
t usb net                        # Enable USB networking mode
```

### SSH Access for Advanced Control

**Discovery**: Dropbear SSH server runs when Wi-Fi is active
- **Authentication**: Uses cryptographically-signed `authorized_keys`
- **Access Level**: Root shell access to Linux OS
- **Limitation**: Requires firmware modification to bypass key validation

*Potential Use*:
- Advanced debugging during development
- Direct filesystem access for extracting camera metadata
- Real-time monitoring of camera internals
- Alternative control channel alongside USB

**Implementation Risk**: Medium - requires firmware modification

### Wi-Fi Multi-Camera Control (Alternative to USB)

**Discovery**: JSON-RPC protocol available over Wi-Fi
- **Protocol**: Same JSON-RPC as USB, but over network
- **Status**: Currently non-functional for cross-platform (Android ↔ iOS variants)
- **Potential**: Multi-camera control without USB hub requirements

*Potential Use*:
- Wireless camera arrays for 360° photography
- Remote camera control at distance
- Hybrid USB + Wi-Fi control (more than 4 cameras)
- Mobile device control without Lightning connector

**Current Implementation**: dxo1usb.js already uses JSON-RPC over USB; could be adapted for network transport.

### Firmware Analysis Tools

**Discovery**: Community has developed firmware extraction/analysis pipeline
- **Tools**: gopro-fw-tools, ubi_reader, binwalk, strings
- **Capabilities**: Extract Linux partition, discover undocumented commands
- **Findings**: "Many potential commands and device-specific commands in RTOS"

*Potential Use*:
- Discover additional camera commands beyond current API
- Document complete camera command protocol
- Find advanced features (exposure bracketing, HDR, etc.)
- Enable features disabled in stock firmware

**Risk Level**: Low (read-only analysis), High (if firmware modification attempted)

### Advanced Shooting Modes

**Discovery**: Firmware contains undocumented advanced shooting capabilities
- **Exposure Bracketing**: Multiple shots with different exposures
- **Burst Capture**: High-speed sequential shooting
- **Hyperfocal Distance Mode**: Calculated optimal focus based on parameters
- **Custom Parameter Control**: Numeric parameters for fine-tuning

*Potential Use*:
- HDR photography (exposure bracketing + merge)
- Action photography (burst mode)
- Landscape photography (hyperfocal distance calculations)
- Professional tethered shooting workflows

**Status**: Commands likely exist in firmware but need to be discovered/documented.

### USB Networking Mode

**Discovery**: Camera supports USB networking mode for data + charging
- **Activation**: Via autoexec.ash script (`t usb net`)
- **Benefits**: Simultaneous operation and charging
- **Protocol**: Unknown if exposes camera control over network interface

*Potential Use*:
- Eliminate battery concerns during long shoots
- Potential alternative control protocol
- Network-based multi-camera control over USB hub

**Investigation Needed**: Test if camera API is accessible over USB network interface.

### Cross-Platform Hardware Compatibility

**Discovery**: Lightning-variant cameras work via microUSB port on Android
- **Hardware**: All variants have microUSB port (not just USB-C models)
- **Limitation**: Wi-Fi communication doesn't work cross-platform
- **Implication**: USB control is platform-agnostic

*Current Status*: dxo_one_control already leverages this via WebUSB.

### Firmware Logging & Debugging

**Discovery**: Comprehensive logging can be redirected to SD card
- **Method**: Autoexec scripts configure message queue logging
- **Output**: Real-time camera internals written to C:\logs\
- **Use Case**: Debugging communication issues, understanding camera state

*Potential Use*:
- Troubleshooting guide for users with connection issues
- Developing new features with visibility into camera internals
- Validating command behavior and responses
- Bug reports with comprehensive diagnostic data

### Implementation Recommendations

**Quick Wins** (Low effort, high value):
1. ✅ **Multi-camera USB control** - Already implemented!
2. 📝 **Document autoexec.ash capabilities** - User guide section for SD card scripts
3. 🔍 **Firmware analysis** - Extract and document additional commands

**Medium-term Opportunities**:
4. 📡 **Wi-Fi JSON-RPC transport** - Network-based alternative to USB
5. 🎯 **Advanced shooting modes** - Discover and implement bracketing/burst commands
6. 🔌 **USB networking mode** - Test and document capabilities

**Research Projects** (Long-term):
7. 🔬 **SSH access workflow** - Developer tool for advanced debugging
8. 🧮 **Hyperfocal distance calculator** - UI helper for landscape photography
9. 📊 **Firmware logging integration** - Automatic diagnostic data collection

### Exclusions & Risks

**Will NOT Pursue**:
- ❌ **Firmware modification** - Too risky, could brick devices
- ❌ **Lightning connector reverse-engineering** - USB works universally
- ❌ **SSH key validation bypass** - Security/legal concerns

**Risks to Consider**:
- Autoexec scripts could interfere with USB control if misconfigured
- Undocumented commands may behave unexpectedly or cause crashes
- Firmware analysis is read-only; writing custom firmware is out of scope

---

**Last Updated**: 2026-01-15
**Document Version**: 1.3
**Current Phase**: Phase 1 (Foundation & Documentation)
**Early Deliveries**: Multi-camera support (from Phase 4), Android app
**New Research**: Community discoveries integrated
**CI/CD**: Android build validation added to pipeline
