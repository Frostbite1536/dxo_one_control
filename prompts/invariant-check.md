# Invariant Check

Use this prompt to verify that code changes respect dxo1control's system invariants and contracts.

---

## Prompt

You are performing an invariant check on **dxo1control** code changes. Your job is to compare proposed changes against the 11 system invariants and identify violations, ambiguities, or required updates.

**Changes to review**: [describe changes, paste diff, or specify files]

**Invariants document**: [INVARIANTS.md](../docs/INVARIANTS.md)

**Project context**: dxo1control controls DXO One cameras via WebUSB and processes DNG image files.

---

## Review Process

### 1. Read the Invariants

Read [INVARIANTS.md](../docs/INVARIANTS.md) completely. Understand all 15 invariants across 5 categories (11 core in 4 categories, plus 4 multi-camera):

**Data Integrity** (3 invariants):
- INV-DATA-001: USB Message Integrity
- INV-DATA-002: DNG File Colorspace Preservation
- INV-DATA-003: Connection State Consistency

**Security** (3 invariants):
- INV-SEC-001: USB Device Vendor ID Verification
- INV-SEC-002: User Permission Required for USB Access
- INV-SEC-003: Command Validation Before Transmission

**Consistency** (3 invariants):
- INV-CONS-001: Browser WebUSB Support Check
- INV-CONS-002: microUSB Connection Warning
- INV-CONS-003: Error State Recovery

**API Contracts** (2 invariants):
- INV-API-001: Backward Compatible Exports
- INV-API-002: Uint8Array for Binary Data

**Multi-Camera** (4 invariants):
- INV-MULTI-001: Camera Identification
- INV-MULTI-002: Maximum Camera Limit
- INV-MULTI-003: Partial Failure Handling
- INV-MULTI-004: Synchronization Accuracy Documentation

### 2. Analyze Each Change

For each code change, systematically check **all 15 invariants**:

---

## Systematic Invariant Checklist

### Data Integrity Invariants

#### INV-DATA-001: USB Message Integrity

**Rule**: All USB messages must be valid Uint8Array with proper length fields.

**Check**:
- [ ] Are all USB messages created as `new Uint8Array(...)`?
- [ ] Is length validated before transmission?
- [ ] No plain Array or other types used for USB data?

**Red Flags**:
- ❌ `const message = [0x01, 0x02]` (plain Array instead of `encodeRpcRequest(...)` output)
- ❌ `message.length = 5` after creation (length mismatch)
- ❌ Missing length validation

#### INV-DATA-002: DNG Colorspace Preservation

**Rule**: Post-processing must preserve original colorspace. No automatic conversion.

**Check**:
- [ ] Is colorspace read from input file?
- [ ] Is colorspace explicitly preserved in processing?
- [ ] No automatic sRGB or other conversion?
- [ ] Is output colorspace verified to match input?

**Red Flags**:
- ❌ `convertToSRGB: true`
- ❌ Processing without colorspace parameter
- ❌ No colorspace verification

#### INV-DATA-003: Connection State Consistency

**Rule**: USB connection state must accurately reflect hardware status.

**Check**:
- [ ] Is connection state updated on connect/disconnect events?
- [ ] Are commands blocked when disconnected?
- [ ] Is state checked before operations?
- [ ] Are disconnect event listeners in place?

**Red Flags**:
- ❌ Using stale `wasConnected` flag
- ❌ No disconnect event listener
- ❌ State not cleared on disconnect

---

### Security Invariants

#### INV-SEC-001: USB Device Verification

**Rule**: Only connect to devices matching DXO One vendor/product IDs.

**Check**:
- [ ] Are `filters` with vendor/product ID used in `requestDevice()`?
- [ ] No wildcard or unspecified device filters?
- [ ] Vendor/product IDs hardcoded (not user-configurable)?

**Red Flags**:
- ❌ `requestDevice({})` (no filters)
- ❌ `vendorId: undefined`
- ❌ User-provided device ID

#### INV-SEC-002: User Permission Required

**Rule**: USB access must require explicit user interaction and permission.

**Check**:
- [ ] Is `requestDevice()` triggered by user action (click, button)?
- [ ] No automatic connection on page load?
- [ ] No background polling for devices?

**Red Flags**:
- ❌ `window.onload = connectToCamera`
- ❌ `setInterval(tryConnect, 1000)`
- ❌ Auto-connect without user gesture

#### INV-SEC-003: Command Validation

**Rule**: All commands must be validated against known-safe command set before transmission.

**Check**:
- [ ] Is command type checked against whitelist?
- [ ] Are command parameters validated?
- [ ] No raw user input sent directly to camera?

**Red Flags**:
- ❌ `sendCommand(userInput)`
- ❌ No whitelist checking
- ❌ Accepting arbitrary command codes

---

### Consistency Invariants

#### INV-CONS-001: WebUSB Support Check

**Rule**: Application must check for WebUSB availability before attempting USB operations.

**Check**:
- [ ] Is `navigator.usb` existence checked?
- [ ] Are clear error messages shown if unavailable?
- [ ] Are USB features disabled when unsupported?

**Red Flags**:
- ❌ Direct `navigator.usb.requestDevice()` without check
- ❌ No error message for unsupported browsers
- ❌ Assuming WebUSB exists

#### INV-CONS-002: microUSB Connection Warning

**Rule**: All documentation and UI must warn that only microUSB is tested.

**Check**:
- [ ] Does UI show microUSB warning?
- [ ] Does README mention limitation?
- [ ] Are users warned before connection attempt?

**Red Flags**:
- ❌ Generic "Connect USB" message
- ❌ No mention of connector type
- ❌ Implying Lightning support works

#### INV-CONS-003: Error State Recovery

**Rule**: All errors must either auto-recover or provide clear user actions for recovery.

**Check**:
- [ ] Do error handlers attempt recovery when possible?
- [ ] Do non-recoverable errors show clear user actions?
- [ ] Is connection state cleaned up on errors?
- [ ] No silent failures?

**Red Flags**:
- ❌ `catch (e) { console.log(e) }` (silent)
- ❌ Error shown but state left stuck
- ❌ No recovery mechanism

---

### API Contract Invariants

#### INV-API-001: Backward Compatible Exports

**Rule**: Public exports must maintain backward compatibility. Breaking changes require major version bump.

**Check**:
- [ ] Are existing exports still present?
- [ ] Are function signatures unchanged?
- [ ] If changed, is it additive (optional params)?
- [ ] Are deprecations warned before removal?

**Red Flags**:
- ❌ Removing existing export
- ❌ Changing required parameters
- ❌ Renaming exported function
- ❌ Changing return type

#### INV-API-002: Uint8Array for Binary Data

**Rule**: All binary data interfaces must use Uint8Array. No Array, Buffer, or other types.

**Check**:
- [ ] Do all binary data functions accept Uint8Array?
- [ ] Do all binary data functions return Uint8Array?
- [ ] No mixing with Array or Buffer?
- [ ] Are types documented in JSDoc?

**Red Flags**:
- ❌ `function(data: Array)` for binary
- ❌ Returning plain Array
- ❌ Using Node.js Buffer in browser code

---

## Output Format

### Invariant Compliance Report

#### ✅ Invariants Maintained

For each invariant properly maintained:

**INV-XXX-###**: [Invariant Name]
- **Status**: ✅ Maintained
- **How**: [explanation of how change maintains it]

Example:
**INV-DATA-001**: USB Message Integrity
- **Status**: ✅ Maintained
- **How**: All new USB commands use `new Uint8Array()` and validate length before transmission

---

#### ⚠️ Invariants Requiring Attention

For invariants needing clarification:

**INV-XXX-###**: [Invariant Name]
- **Status**: ⚠️ Needs Review
- **Issue**: [what's unclear or potentially problematic]
- **Recommendation**: [suggest next steps]

Example:
**INV-CONS-002**: microUSB Connection Warning
- **Status**: ⚠️ Needs Review
- **Issue**: New UI dialog doesn't show microUSB warning
- **Recommendation**: Add warning text to connection dialog before device selection

---

#### ❌ Invariant Violations

For clear violations:

**INV-XXX-###**: [Invariant Name]
- **Status**: ❌ **VIOLATED**
- **Violation**: [how change breaks the invariant]
- **Impact**: [what could go wrong]
- **Required Action**: [must fix or must update invariant with approval]

Example:
**INV-SEC-003**: Command Validation Before Transmission
- **Status**: ❌ **VIOLATED**
- **Violation**: New `sendRawCommand()` function sends arbitrary command codes without validation
- **Impact**: User input could send dangerous commands to camera, potentially damaging hardware
- **Required Action**: Add command whitelist validation or remove this function

---

#### 🆕 New Invariants Needed

For new behavior requiring invariants:

**New Invariant**: [Proposed Name]
- **ID**: INV-[CATEGORY]-###
- **Rule**: [what should always be true]
- **Rationale**: [why this is needed]
- **Enforcement**: [how it will be guaranteed]
- **Category**: [Data Integrity | Security | Consistency | API Contract]

Example:
**New Invariant**: Battery Level Validity
- **ID**: INV-DATA-004
- **Rule**: Battery level must be 0-100 or null (unknown)
- **Rationale**: Invalid battery values could confuse users or break UI
- **Enforcement**: Validate battery response before returning
- **Category**: Data Integrity

---

## Decision Framework

For each finding, recommend:

### ✅ Proceed
- All invariants maintained
- No violations found
- Code can be merged as-is

### ⚠️ Discuss
- Ambiguous whether invariant holds
- Edge cases need clarification
- May require minor adjustments

### ❌ Block
- Clear invariant violation
- Must either:
  1. Fix the code to maintain the invariant, OR
  2. Get explicit approval to update the invariant (with documentation)

### 🆕 Extend
- Change is fine but needs new invariants documented
- Add to INVARIANTS.md before merging

---

## Common Violation Patterns

### USB-Related

- Forgetting `new Uint8Array()` wrapper
- Not checking `navigator.usb` before use
- Auto-connecting without user permission
- Not validating commands
- Not updating connection state

### Image Processing-Related

- Automatic colorspace conversion
- Forgetting to preserve colorspace
- Not reading original colorspace
- Missing colorspace verification

### General

- Silent error handling
- Removing public exports
- Using Array instead of Uint8Array
- Not checking WebUSB availability

---

## Example Report

### Changes Reviewed

Added new battery status feature to dxo1usb.js

### ✅ Invariants Maintained (9)

**INV-DATA-001**: ✅ Battery request built with `encodeRpcRequest(id, 'dxo_camera_status_get')` (returns a Uint8Array)
**INV-DATA-003**: ✅ Checks connection state before sending
**INV-SEC-001**: ✅ Uses existing device connection (already verified)
**INV-SEC-002**: ✅ Requires prior connection (user permission)
**INV-SEC-003**: ✅ Battery method (`dxo_camera_status_get`) added to whitelist
**INV-CONS-001**: ✅ Uses existing WebUSB check
**INV-CONS-003**: ✅ Error handler provides retry or reconnect action
**INV-API-001**: ✅ New export added, no existing exports changed
**INV-API-002**: ✅ Returns Uint8Array (battery percentage)

### ⚠️ Needs Attention (1)

**INV-CONS-002**: microUSB Warning
- **Status**: ⚠️ Needs Review
- **Issue**: Battery feature works over microUSB only (untested on Lightning)
- **Recommendation**: Add note in JSDoc and README mentioning microUSB limitation

### ❌ Violations (0)

No invariant violations detected.

### 🆕 New Invariants (1)

**Battery Level Validity**
- **ID**: INV-DATA-004
- **Rule**: Battery response must be 0-100 or throw error
- **Rationale**: Invalid values could break UI or mislead users
- **Enforcement**: Validate response byte is <= 100
- **Category**: Data Integrity

### Decision: ⚠️ Discuss

Address microUSB warning and consider adding INV-DATA-004 to INVARIANTS.md before merging.

---

## Remember

**Invariants are non-negotiable** - they prevent bugs and protect users' cameras and data.

- Hardware can be damaged by invalid USB commands
- Image data can be lost by colorspace changes
- Security can be compromised by bypassing checks
- Users can be confused by inconsistent state

**When in doubt, maintain the invariant.**

If an invariant must change, document why and get explicit approval.

---

**Related Documentation**:
- [INVARIANTS.md](../docs/INVARIANTS.md) - All 11 system invariants
- [ARCHITECTURE.md](../docs/ARCHITECTURE.md) - System design
- [engineering.md](./engineering.md) - Coding standards
