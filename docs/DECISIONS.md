# Decisions Log

This document records significant technical and architectural decisions made during the development of dxo1control. Each entry captures the context, options considered, and rationale for the chosen approach.

---

## Decision Format

Each decision should follow this template:

```markdown
## DEC-###: [Decision Title]

**Date**: YYYY-MM-DD
**Status**: [Proposed | Accepted | Deprecated | Superseded]
**Deciders**: [Who made the decision]
**Context**: [What led to this decision]

### Options Considered

1. **Option A**: Description
   - Pros: ...
   - Cons: ...

2. **Option B**: Description
   - Pros: ...
   - Cons: ...

### Decision

[Chosen option and explanation]

### Consequences

- Positive: ...
- Negative: ...
- Neutral: ...
```

---

## DEC-001: Use WebUSB for Camera Control

**Date**: Pre-project (inherited from community research)
**Status**: Accepted
**Deciders**: Original project authors
**Context**: Need to control DXO One camera from a web browser without requiring native drivers or applications.

### Options Considered

1. **WebUSB Browser API**
   - Pros: Cross-platform, no driver installation, easy deployment, works in browser
   - Cons: Limited browser support, requires user permission, browser-only

2. **Native USB Libraries (libusb/node-usb)**
   - Pros: More control, works in any environment, no browser limitations
   - Cons: Requires Node.js, platform-specific builds, harder deployment

3. **Serial Port Communication**
   - Pros: Simpler protocol, better browser support
   - Cons: Camera doesn't expose serial interface, not applicable

### Decision

Use WebUSB API for camera control. This aligns with the project goal of providing easy-to-use browser-based camera control and leverages existing community research.

### Consequences

- ✅ Positive: Easy demo deployment (see: https://dxo1demo.jsyang.ca/usb.html)
- ✅ Positive: No installation required for end users
- ✅ Positive: Cross-platform compatibility (any WebUSB browser)
- ❌ Negative: Limited to Chrome, Edge, Opera (not Firefox/Safari)
- ❌ Negative: Requires user permission for each session
- ⚪ Neutral: Separate Node.js tools needed for post-processing

---

## DEC-002: Focus on microUSB Connection Only

**Date**: Pre-project (inherited from community research)
**Status**: Accepted
**Deciders**: Original project authors, based on github.com/rickdeck/DxO-One
**Context**: DXO One camera supports both microUSB and Lightning connectors. Lightning protocol is not publicly documented.

### Options Considered

1. **microUSB Only (Documented Protocol)**
   - Pros: Protocol understood from community research, working implementation exists
   - Cons: Doesn't support iOS/Lightning users

2. **Reverse-Engineer Lightning Protocol**
   - Pros: Would support iOS users, full camera connectivity
   - Cons: Significant reverse-engineering effort, potential legal issues, no guarantee of success

3. **Support Both Protocols**
   - Pros: Maximum compatibility
   - Cons: Can't do this without Lightning protocol documentation

### Decision

Focus on microUSB connection using the protocol documented by rickdeck/DxO-One. Clearly document this limitation and warn users about Lightning support status.

### Consequences

- ✅ Positive: Working implementation available immediately
- ✅ Positive: Builds on proven community research
- ✅ Positive: Legal and ethical clarity
- ❌ Negative: iOS users cannot use Lightning connection
- ❌ Negative: Requires prominent warnings in documentation
- ⚪ Neutral: Future opportunity to add Lightning support if protocol is documented

---

## DEC-003: Separate Tools for USB Control and Post-Processing

**Date**: Pre-project (original architecture)
**Status**: Accepted
**Deciders**: Original project authors
**Context**: Need both real-time camera control and offline image processing capabilities.

### Options Considered

1. **Separate Tools (Current Approach)**
   - Pros: Clear separation of concerns, optimized for each environment
   - Cons: No integrated workflow, different runtime requirements

2. **Integrated Application (Electron)**
   - Pros: Single application, integrated workflow, native features
   - Cons: More complex, requires installation, larger download

3. **Web-Only Solution**
   - Pros: Everything in browser, easy deployment
   - Cons: Limited file system access, performance constraints for image processing

### Decision

Maintain separate tools: `dxo1usb.js` for browser-based USB control, `resizeDNG.mjs` for Node.js-based post-processing. Each tool is optimized for its environment.

### Consequences

- ✅ Positive: Each tool can use appropriate APIs (WebUSB vs. Node.js file system)
- ✅ Positive: Lightweight browser demo doesn't need image processing dependencies
- ✅ Positive: Users can use tools independently
- ❌ Negative: No single integrated workflow
- ❌ Negative: Different installation/setup for each tool
- ⚪ Neutral: Future option to create Electron wrapper for integration (Phase 4)

---

## DEC-004: Adopt Safe Vibe Coding Infrastructure

**Date**: 2026-01-04
**Status**: Accepted
**Deciders**: Current maintainers
**Context**: Project needs better documentation, testing, and development guidelines to support community contributions and LLM-assisted development.

### Options Considered

1. **Safe Vibe Coding Bootstrap**
   - Pros: Comprehensive documentation structure, clear invariants, LLM-friendly
   - Cons: Initial documentation effort, overhead for small changes

2. **Minimal Documentation**
   - Pros: Less upfront work, faster to start coding
   - Cons: Harder for contributors, no clear guidelines, LLM context issues

3. **Standard OSS Practices Only**
   - Pros: Familiar to contributors, well-established patterns
   - Cons: Lacks LLM-specific optimizations, less explicit about invariants

### Decision

Adopt Safe Vibe Coding infrastructure including ARCHITECTURE.md, INVARIANTS.md, ROADMAP.md, and custom engineering prompts. This provides clear structure for both human and LLM contributors.

### Consequences

- ✅ Positive: Clear documentation of system design and constraints
- ✅ Positive: Better LLM-assisted development with explicit context
- ✅ Positive: Easier onboarding for new contributors
- ✅ Positive: Invariants prevent common bugs and breaking changes
- ❌ Negative: Initial time investment to create documentation
- ❌ Negative: Documentation maintenance overhead
- ⚪ Neutral: Sets foundation for Phase 1 completion

---

## DEC-005: Native Android App Alongside the Web App

**Date**: 2026-11-04
**Status**: Accepted
**Deciders**: Current maintainers
**Context**: Multi-camera rigs need a portable host. A phone or tablet with a USB hub is easier to carry than a laptop, and Android's USB host API reaches the camera's microUSB port directly.

### Options Considered

1. **Web app only (WebUSB in Android Chrome)**
   - Pros: One codebase
   - Cons: No background lifetime, no foreground service, poor handling of several cameras and hubs, tied to browser permission prompts

2. **Native Android app (Kotlin, Compose)**
   - Pros: Foreground service keeps USB connections alive, direct USB host control, persisted settings
   - Cons: Second implementation of the protocol to keep in sync with the JS one

### Decision

Add `android-app/` (package `com.dxoone.multicam`) as a native app next to the web code. It reimplements the JS protocol in Kotlin and follows the same transport rules (DEC-006). Image download and a gallery are not implemented.

### Consequences

- Positive: Reliable multi-camera operation on a phone; background lifetime (DEC-007)
- Negative: Two protocol implementations must stay aligned; Android needs its own build and CI job
- Neutral: Supersedes the earlier "browser-only" trade-off in DEC-001 for mobile use; the web app remains the demo

---

## DEC-006: Shared Single-Reader Transport With Id-Matched, Serialized RPC

**Date**: 2026-11-04
**Status**: Accepted
**Deciders**: Current maintainers (following the Nov 2026 audit, `docs/AUDIT-2026-11.md`)
**Context**: Live view and JSON-RPC share one bulk-IN endpoint. The original code read "whatever arrives next" after each command, so replies and JPEG packets were swapped, commands could overlap, and a silent camera hung callers.

### Options Considered

1. **Patch each call site** (per-call reads, flags)
   - Pros: Small diff
   - Cons: Races remain; JS had two diverging copies (`dxo1usb.js` and `CameraDevice.js`)

2. **One transport per camera** that owns the endpoint
   - Pros: One place for framing, routing, timeouts; testable without hardware
   - Cons: Larger refactor on both platforms

### Decision

Introduce `DxoTransport.js` (used by both `dxo1usb.js` and `CameraDevice.js`) and, on Android, `DxoOneStreamDemux` + `DxoOneUsbProtocol` with the same rules: one read pump / reader thread per camera; a demux routes each packet to the in-flight RPC (matched by id; an id-less reply is accepted for the single in-flight request only while the camera has never sent an id; after a timeout the next id-less reply is treated as the late reply and dropped (a truly lost reply costs at most one extra request); stale ids dropped), to an event handler (messages with a `method`), or to the JPEG reassembler; one RPC in flight per camera; per-request timeouts; fatal USB errors fail the in-flight request.

### Consequences

- Positive: No cross-talk between live view and commands; callers cannot hang; protocol logic is unit-testable
- Negative: Timeout values differ between platforms (commands: JS 5 s, Android 10 s; capture: 40 s on both) and have not been tuned on hardware
- Neutral: Live-view stop remains host-side only (no camera command known)

---

## DEC-007: Foreground Service Owns Background Lifetime on Android

**Date**: 2026-11-04
**Status**: Accepted
**Deciders**: Current maintainers
**Context**: USB connections must survive the screen turning off or the app being backgrounded, but camera connections should not depend on any Activity or ViewModel lifetime.

### Options Considered

1. **Cameras owned by the ViewModel / Activity**
   - Pros: Simple
   - Cons: Duplicate instances on re-launch, connections dropped on teardown

2. **Service owns the cameras**
   - Pros: Long-lived
   - Cons: UI and service fight over initialization and cleanup

3. **Process-wide `UsbDeviceManager` owns cameras; a foreground service only keeps the process alive**
   - Pros: One owner; service lifecycle is trivial
   - Cons: Cameras are lost on process death (acceptable: USB connections do not survive it anyway)

### Decision

Option 3. `UsbDeviceManager` is process-wide with an idempotent `initialize()` and serialized connects. `CameraManagerService` (foreground, type `connectedDevice`) is started by `DxoOneMultiCamApp` when the first camera connects, stops itself when none remain, and is `START_NOT_STICKY`. `MainActivity` is `singleTop`.

### Consequences

- Positive: No duplicate managers; Android 14 `connectedDevice` prerequisites (USB permission) are met before the service starts
- Negative: Needs the `POST_NOTIFICATIONS` runtime permission on newer Android to show the notification
- Neutral: The service exposes capture helpers but is not required for capture

---

## DEC-008: node:test With a Fake Device Instead of a Test Framework

**Date**: 2026-11-04
**Status**: Accepted
**Deciders**: Current maintainers
**Context**: The project has zero dependencies and tests were needed for the transport rework. The risky logic (framing, reassembly, timeouts, serialization) is pure or needs only a scripted USB endpoint.

### Options Considered

1. **Jest / Vitest / Mocha**
   - Pros: Rich tooling, coverage
   - Cons: Adds dependencies and config to a dependency-free repo

2. **Node's built-in `node:test` + `test/fakeDxoOne.mjs`**
   - Pros: No dependencies (Node >= 18); the fake device is injectable through the existing `usbBackend` parameters
   - Cons: Fewer conveniences, no coverage tooling configured

### Decision

Use `node:test` with a scripted fake DXO One. `npm test` runs `node --check` on every `*.js` / `*.mjs` file, then `node --test test/*.test.mjs`. Android uses plain JUnit in `app/src/test`.

### Consequences

- Positive: CI is real (`js` job) without adding dependencies
- Negative: No coverage numbers; tests prove the logic, not behavior on real cameras
- Neutral: Resolves the "Testing Framework" pending item; Jest / Mocha / Vitest were not adopted

---

## Future Decisions (To Be Made)

### Pending

- ~~**Testing Framework**~~ Decided: native `node:test` (DEC-008)
- **Type System**: TypeScript adoption vs. JSDoc comments vs. no types
- **Linting**: ESLint configuration and rules
- **Build System**: Whether to introduce build step or keep simple scripts
- **API Documentation**: JSDoc, TypeDoc, or manual documentation
- **Versioning**: When to tag v1.0.0

### Under Discussion

- None currently

---

## Deprecated Decisions

None yet.

---

## Notes

- Decisions should be recorded as they're made, not retroactively
- Include enough context for future developers to understand the reasoning
- Update status when decisions are superseded or deprecated
- Link to related issues, PRs, or discussions when available
- Use decision numbers (DEC-###) for easy reference in code comments

---

**Last Updated**: 2026-11-04
**Document Version**: 1.1
**Total Decisions**: 8 (3 inherited, 5 new)
