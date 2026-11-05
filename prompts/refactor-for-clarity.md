# Refactor for Clarity

Use this prompt when dxo1control code is working but hard to understand or maintain.

---

## Prompt

You are refactoring **dxo1control** code to improve clarity and maintainability **without changing behavior**.

**Target code**: [paste code or specify file/function]

**Constraints**:
- **Do not change behavior**: All functionality must work identically
- **Do not change APIs**: External interfaces remain the same (INV-API-001)
- **Do not add features**: This is purely a clarity refactor
- **Maintain all invariants**: All 15 invariants must still hold

---

## Refactoring Goals

### 1. Improve Naming

**Current issues to fix**:
- Abbreviations: `dev`, `msg`, `cmd`, `resp`, `buf`
- Vague names: `data`, `result`, `temp`, `x`
- Type-based names: `array1`, `string2`

**Better naming**:
- Use full words: `device`, `message`, `command`, `response`, `buffer`
- Descriptive: `cameraResponse`, `batteryLevel`, `connectionState`
- Intent-based: `isConnected` not `connectedFlag`

**Examples**:
```javascript
// Before: Unclear
let dev, msg, resp;

// After: Clear
let cameraDevice, usbMessage, cameraResponse;
```

---

### 2. Simplify Control Flow

**Reduce nesting**:
```javascript
// Before: Deep nesting
async function sendCommand(cmd) {
  if (isConnected) {
    if (cmd !== null) {
      if (isValidCommand(cmd)) {
        try {
          return await transmit(cmd);
        } catch (e) {
          handleError(e);
        }
      }
    }
  }
}

// After: Early returns, flatter
async function sendCommand(command) {
  if (!isConnected) {
    throw new Error('Camera not connected');
  }
  if (command === null) {
    throw new Error('Command is null');
  }
  if (!isValidCommand(command)) {
    throw new Error('Invalid command');
  }

  try {
    return await transmit(command);
  } catch (error) {
    handleError(error);
  }
}
```

**Extract complex conditions**:
```javascript
// Before: Complex condition
if (navigator.usb && !isConnected && userClickedButton && deviceId !== null) {
  connect();
}

// After: Named condition
const canConnect = navigator.usb
  && !isConnected
  && userClickedButton
  && deviceId !== null;

if (canConnect) {
  connect();
}
```

---

### 3. Reduce Complexity

**Break large functions**:
- Aim for < 50 lines per function
- Each function does one thing
- Extract logical units into named functions

```javascript
// Before: Large function doing multiple things
async function processImage(file) {
  // 100 lines of: read file, validate, parse metadata,
  // transform image, preserve colorspace, write output
}

// After: Broken into focused functions
async function processImage(file) {
  const imageData = await readImageFile(file);
  validateImageData(imageData);
  const metadata = parseImageMetadata(imageData);
  const transformed = transformImage(imageData, metadata);
  const output = preserveColorspace(transformed, metadata.colorspace);
  return writeOutputFile(output);
}
```

---

### 4. Eliminate Redundancy

**Remove duplicate code**:
```javascript
// Before: Duplication
async function getBattery() {
  if (!isConnected) throw new Error('Not connected');
  const msg = encodeRpcRequest(nextId++, 'dxo_camera_status_get');
  return await sendUSB(msg);
}

async function getModel() {
  if (!isConnected) throw new Error('Not connected');
  const msg = encodeRpcRequest(nextId++, 'dxo_all_settings_get');
  return await sendUSB(msg);
}

// After: Extract common pattern
async function sendCameraCommand(method, params) {
  if (!isConnected) {
    throw new Error('Camera not connected');
  }
  const message = encodeRpcRequest(nextId++, method, params);
  return await sendUSB(message);
}

async function getBattery() {
  return await sendCameraCommand('dxo_camera_status_get');
}

async function getModel() {
  return await sendCameraCommand('dxo_all_settings_get');
}
```

**But avoid premature abstraction**:
- Wait for 3 uses before extracting
- Don't abstract single-use patterns
- Keep simple code simple

---

### 5. Improve Data Structures

**Use appropriate structures**:
```javascript
// Before: Array for lookup
const commands = [
  { method: 'dxo_photo_take', name: 'CAPTURE' },
  { method: 'dxo_camera_status_get', name: 'STATUS' }
];
function findCommand(method) {
  return commands.find(cmd => cmd.method === method);
}

// After: Map for O(1) lookup
const COMMANDS = new Map([
  ['dxo_photo_take', 'CAPTURE'],
  ['dxo_camera_status_get', 'STATUS']
]);
function getCommandName(method) {
  return COMMANDS.get(method);
}
```

**Group related data**:
```javascript
// Before: Scattered state
let isConnected = false;
let deviceId = null;
let lastError = null;
let connectionTime = null;

// After: Grouped state
const connectionState = {
  isConnected: false,
  deviceId: null,
  lastError: null,
  connectedAt: null
};
```

---

### 6. Clarify Intent

**Use expressive language features**:
```javascript
// Before: Manual loop
const validCommands = [];
for (let i = 0; i < commands.length; i++) {
  if (isValid(commands[i])) {
    validCommands.push(commands[i]);
  }
}

// After: Expressive filter
const validCommands = commands.filter(isValid);
```

**Make assumptions explicit**:
```javascript
// Before: Implicit assumption
function processResponse(response) {
  return response[0];  // Assumes response has data
}

// After: Explicit check
function processResponse(response) {
  if (!response || response.length === 0) {
    throw new Error('Empty response from camera');
  }
  return response[0];
}
```

---

### 7. Remove Dead Code

**Remove**:
- Unused variables and functions
- Commented-out code
- Unreachable code
- Unused imports

```javascript
// Before: Dead code
import { oldFunction } from './utils.js';  // Never used

function getData() {
  const x = 5;  // Unused variable
  // const y = 10;  // Commented out
  return fetchData();
}

// After: Cleaned up
function getData() {
  return fetchData();
}
```

---

## dxo1control-Specific Refactoring Patterns

### USB Communication

**Clear message construction**:
```javascript
// Before: Magic strings and hand-built frames
const msg = encodeRpcRequest(3, 'dxo_camera_mode_switch', { param: 'view' });

// After: Named constants
const METHOD_MODE_SWITCH = 'dxo_camera_mode_switch';
const MODE_VIEW = 'view';
const message = encodeRpcRequest(nextId++, METHOD_MODE_SWITCH, { param: MODE_VIEW });
```

**Clear state management**:
```javascript
// Before: Unclear state updates
let conn = false;
function setConn(val) {
  conn = val;
  if (!val) { /* cleanup */ }
}

// After: Clear intent
let isConnected = false;

function markConnected(device) {
  isConnected = true;
  currentDevice = device;
}

function markDisconnected() {
  isConnected = false;
  currentDevice = null;
  clearCommandQueue();
}
```

### Image Processing

**Clear colorspace handling**:
```javascript
// Before: Implicit colorspace
function processImage(data) {
  return transform(data);
}

// After: Explicit preservation
function processImage(imageData) {
  const originalColorspace = readColorspace(imageData);
  const transformed = transformImage(imageData);
  return preserveColorspace(transformed, originalColorspace);
}
```

---

## Anti-Patterns to Avoid

**Don't over-abstract**:
```javascript
// Bad: Unnecessary abstraction for single use
const createArrayOfSize = (size) => new Uint8Array(size);
const buffer = createArrayOfSize(10);

// Good: Direct and clear
const buffer = new Uint8Array(10);
```

**Don't add types everywhere**:
```javascript
// Bad: Type annotations don't add clarity here
/** @param {number} x - A number */
function double(x) {
  return x * 2;
}

// Good: Type annotation adds clarity here
/**
 * @param {Uint8Array} message - USB message to send
 * @returns {Promise<Uint8Array>} Camera response
 */
async function sendUSBMessage(message) {
  // ...
}
```

**Don't over-engineer simple logic**:
```javascript
// Bad: Over-engineered
const isConnectedChecker = {
  check: () => connectionState.isConnected,
  verify: () => connectionState.isConnected === true
};

// Good: Simple and clear
const isConnected = connectionState.isConnected;
```

---

## Process

1. **Understand current behavior**
   - Read the code thoroughly
   - Understand what it does
   - Note any edge cases

2. **Identify clarity issues**
   - List specific problems (naming, nesting, complexity)
   - Prioritize: What makes code hardest to understand?

3. **Propose refactoring**
   - Show before/after for each change
   - Explain why it's clearer
   - Maintain all invariants

4. **Verify behavior unchanged**
   - Check all code paths still work
   - Verify no logic changes
   - Confirm invariants maintained

---

## Output Format

### Clarity Issues Found

1. **Issue**: [description]
   - **Location**: [file:line]
   - **Problem**: [what's unclear]

2. **Issue**: [description]
   - **Location**: [file:line]
   - **Problem**: [what's unclear]

### Refactored Code

[Show improved code with comments explaining changes]

### Changes Made

1. **Renamed variables**: `dev` → `cameraDevice`, `msg` → `usbMessage`
2. **Extracted function**: `validateAndSend()` from duplicated validation logic
3. **Flattened nesting**: Used early returns in `sendCommand()`
4. **Removed dead code**: Unused `oldHelper()` function

### Invariants Confirmed

- ✅ INV-DATA-001: Still uses Uint8Array for USB messages
- ✅ INV-API-001: No changes to exported functions
- ✅ INV-API-002: Still uses Uint8Array for binary data
- ✅ All other invariants: Not affected by refactoring

### Verification

**Behavior is unchanged. All functionality works identically.**

- No logic changes
- No API changes
- No new features added
- Code is clearer and more maintainable

---

## Example Refactoring

### Before

```javascript
function proc(d) {
  if(d){
    if(d.jsonrpc==='2.0'){
      if(d.error){return 'err';}
      else if(d.result!==undefined){return 'ok';}
      else{return 'unk';}
    }
  }
  return null;
}
```

### After

```javascript
/**
 * Processes camera response and returns status.
 *
 * @param {Object} response - Parsed JSON-RPC 2.0 response from camera
 * @returns {string|null} Status: 'ok', 'error', 'unknown', or null
 */
function parseCameraResponse(response) {
  // Validate response exists
  if (!response || response.jsonrpc !== '2.0') {
    return null;
  }

  if (response.error) {
    return 'error';
  }

  if (response.result !== undefined) {
    return 'ok';
  }

  return 'unknown';
}
```

### What Changed
- **Explicit protocol fields**: `result`/`error` checked by name, not magic values
- **Descriptive names**: `d` → `response`
- **Early validation**: Check for null/empty upfront
- **Clearer structure**: One condition per if block
- **JSDoc comment**: Documents parameters and return value
- **Better spacing**: Readable formatting

---

## Remember

**The goal is clarity, not cleverness.**

- Code is read 10x more than it's written
- Future you (or future LLM) will thank you
- Simple beats clever
- Explicit beats implicit
- Maintainability > brevity

**All invariants must still hold after refactoring.**

---

**Related Documentation**:
- [INVARIANTS.md](../docs/INVARIANTS.md) - Rules to maintain
- [engineering.md](./engineering.md) - Coding standards
