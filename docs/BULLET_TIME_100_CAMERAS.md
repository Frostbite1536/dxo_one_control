# Poor Man's Bullet Time: 100 DXO One Camera Array

## Executive Summary

This document outlines a detailed, actionable plan for building a "bullet time" photography rig using 100 DXO One cameras. The system captures simultaneous images from multiple angles, enabling the iconic frozen-moment, rotating-camera effect popularized by The Matrix.

**Target Specifications:**
- 100 DXO One cameras arranged in a 180° arc (or full 360° ring)
- Synchronized capture within ≤100ms total variance
- 20.2MP images from each camera (1" sensor, RAW capable)
- Total cost: ~$5,000-15,000 (using used/refurbished DXO One cameras)

---

## Table of Contents

1. [System Architecture Overview](#1-system-architecture-overview)
2. [Hardware Requirements](#2-hardware-requirements)
3. [Physical Rig Design](#3-physical-rig-design)
4. [Control System Architecture](#4-control-system-architecture)
5. [Software Implementation](#5-software-implementation)
6. [Synchronization Strategy](#6-synchronization-strategy)
7. [Power Management](#7-power-management)
8. [Capture Workflow](#8-capture-workflow)
9. [Post-Processing Pipeline](#9-post-processing-pipeline)
10. [Budget Breakdown](#10-budget-breakdown)
11. [Build Phases](#11-build-phases)
12. [Risk Mitigation](#12-risk-mitigation)

---

## 1. System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        BULLET TIME SYSTEM ARCHITECTURE                       │
└─────────────────────────────────────────────────────────────────────────────┘

                              ┌──────────────┐
                              │   OPERATOR   │
                              │   CONSOLE    │
                              │  (Laptop/PC) │
                              └──────┬───────┘
                                     │
                    ┌────────────────┼────────────────┐
                    │                │                │
              ┌─────▼─────┐   ┌──────▼──────┐   ┌─────▼─────┐
              │  Master   │   │   Trigger   │   │  Storage  │
              │ Controller│   │   System    │   │  Server   │
              │ (RPi 5)   │   │ (Arduino)   │   │  (NAS)    │
              └─────┬─────┘   └──────┬──────┘   └───────────┘
                    │                │
        ┌───────────┼───────────┬────┴────┬───────────┬───────────┐
        │           │           │         │           │           │
   ┌────▼────┐ ┌────▼────┐ ┌────▼────┐ ┌──▼──┐  ┌────▼────┐ ┌────▼────┐
   │ Node 1  │ │ Node 2  │ │ Node 3  │ │ ... │  │ Node 24 │ │ Node 25 │
   │ (RPi 4) │ │ (RPi 4) │ │ (RPi 4) │ │     │  │ (RPi 4) │ │ (RPi 4) │
   └────┬────┘ └────┬────┘ └────┬────┘ └─────┘  └────┬────┘ └────┬────┘
        │           │           │                    │           │
   ┌────┴────┐ ┌────┴────┐ ┌────┴────┐         ┌────┴────┐ ┌────┴────┐
   │USB Hub 1│ │USB Hub 2│ │USB Hub 3│   ...   │USB Hub24│ │USB Hub25│
   │(4-port) │ │(4-port) │ │(4-port) │         │(4-port) │ │(4-port) │
   └─┬─┬─┬─┬─┘ └─┬─┬─┬─┬─┘ └─┬─┬─┬─┬─┘         └─┬─┬─┬─┬─┘ └─┬─┬─┬─┬─┘
     │ │ │ │     │ │ │ │     │ │ │ │             │ │ │ │     │ │ │ │
    ┌┴┐┌┴┐┌┴┐┌┴┐ ┌┴┐┌┴┐┌┴┐┌┴┐ ┌┴┐┌┴┐┌┴┐┌┴┐       ┌┴┐┌┴┐┌┴┐┌┴┐ ┌┴┐┌┴┐┌┴┐┌┴┐
    │1││2││3││4│ │5││6││7││8│ │9││A││B││C│  ...  │X││X││X││X│ │X││X││X││X│
    └─┘└─┘└─┘└─┘ └─┘└─┘└─┘└─┘ └─┘└─┘└─┘└─┘       └─┘└─┘└─┘└─┘ └─┘└─┘└─┘└─┘
       DXO One Cameras (100 total, 4 per node × 25 nodes)
```

### Key Design Decisions

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **Node Size** | 4 cameras per node | USB hub limit, proven stable in current app |
| **Node Count** | 25 Raspberry Pi nodes | 100 cameras ÷ 4 = 25 nodes |
| **Network** | Gigabit Ethernet | Reliable, low latency for sync signals |
| **Trigger** | Hardware + Software hybrid | Sub-10ms sync achievable |
| **Storage** | Local SD + NAS backup | Fast capture, safe storage |

---

## 2. Hardware Requirements

### 2.1 Camera Equipment

| Item | Quantity | Unit Price | Total | Notes |
|------|----------|------------|-------|-------|
| DXO One Camera | 100 | $50-150 | $5,000-15,000 | Used/refurbished from eBay |
| MicroSD Cards (32GB) | 100 | $8 | $800 | Class 10 minimum |
| USB-A to Micro-USB cables (short) | 100 | $3 | $300 | 6-12 inch cables |
| **Subtotal** | | | **$6,100-16,100** | |

### 2.2 Computing Hardware

| Item | Quantity | Unit Price | Total | Notes |
|------|----------|------------|-------|-------|
| Raspberry Pi 4 (4GB) | 25 | $55 | $1,375 | Camera control nodes |
| Raspberry Pi 5 (8GB) | 1 | $80 | $80 | Master controller |
| MicroSD Cards (64GB) | 26 | $12 | $312 | For Pis |
| USB 3.0 Hub (4-port, powered) | 25 | $25 | $625 | One per node |
| Gigabit Ethernet Switch (32-port) | 1 | $150 | $150 | Network backbone |
| Ethernet Cables (Cat6) | 30 | $5 | $150 | Short runs |
| **Subtotal** | | | **$2,692** | |

### 2.3 Trigger & Sync Hardware

| Item | Quantity | Unit Price | Total | Notes |
|------|----------|------------|-------|-------|
| Arduino Mega 2560 | 1 | $45 | $45 | Hardware trigger controller |
| GPS Module (NEO-6M) | 26 | $15 | $390 | PPS sync for each node |
| Trigger Button (Big Red) | 1 | $25 | $25 | Manual trigger |
| Wireless Trigger Receiver | 1 | $50 | $50 | Remote trigger option |
| **Subtotal** | | | **$510** | |

### 2.4 Power System

| Item | Quantity | Unit Price | Total | Notes |
|------|----------|------------|-------|-------|
| USB Power Supply (10-port, 60W) | 10 | $40 | $400 | 10 cameras each |
| Raspberry Pi Power (5V 3A) | 26 | $10 | $260 | Official PSU |
| Power Distribution Unit (20A) | 2 | $100 | $200 | Main power |
| Extension Cords (heavy duty) | 10 | $20 | $200 | Power distribution |
| UPS (1500VA) | 1 | $200 | $200 | Backup power |
| **Subtotal** | | | **$1,260** | |

### 2.5 Physical Rig Structure

| Item | Quantity | Unit Price | Total | Notes |
|------|----------|------------|-------|-------|
| Aluminum Truss (curved sections) | 10 | $150 | $1,500 | 180° arc frame |
| Camera Mounting Brackets | 100 | $5 | $500 | 3D printed or aluminum |
| Adjustable Ball Heads (mini) | 100 | $8 | $800 | Fine aiming |
| Cable Management Clips | 200 | $0.50 | $100 | Organization |
| Portable Case/Road Cases | 5 | $150 | $750 | Transport |
| **Subtotal** | | | **$3,650** | |

### Total Hardware Budget

| Category | Cost Range |
|----------|------------|
| Cameras | $6,100 - $16,100 |
| Computing | $2,692 |
| Trigger/Sync | $510 |
| Power | $1,260 |
| Physical Rig | $3,650 |
| **TOTAL** | **$14,212 - $24,212** |

---

## 3. Physical Rig Design

### 3.1 Arc Configuration (180°)

```
                           TOP VIEW - 180° ARC
                          (Subject at Center)

                    Camera 50 (apex)
                         ▼
              45 ○─────○─────○ 55
             ╱                   ╲
           ○                       ○
         40                         60
        ╱                             ╲
       ○                               ○
      35                               65
     ╱                                   ╲
    ○                                     ○
   30                                     70
   │                                       │
   ○                                       ○
  25                    ●                 75    ← Subject Position
   │               (Subject)               │
   ○                                       ○
  20                                       80
    ╲                                     ╱
     ○                                   ○
     15                                 85
       ╲                               ╱
        ○                             ○
        10                           90
          ╲                         ╱
           ○                       ○
            5 ○─────○─────○ 95
                    1   100

            Radius: 3 meters (10 feet)
            Camera Spacing: ~3.6° apart
            Arc Length: ~9.4 meters
```

### 3.2 Vertical Arrangement Options

```
    OPTION A: Single Row              OPTION B: Stacked Rows (50 + 50)
    (100 cameras horizontal)
                                           ○ ○ ○ ○ ○ ○  ← Upper row (angled down)
    ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○                │ │ │ │ │ │
    Height: 1.5m (chest level)             ○ ○ ○ ○ ○ ○  ← Lower row (angled up)

                                      Height: 1.2m and 1.8m
                                      Convergence at subject


    OPTION C: Full 360° Ring          OPTION D: Dome (advanced)
    (100 cameras surround)
                                                ○
         ○ ○ ○ ○ ○ ○ ○ ○                     ○ ○ ○
        ○               ○                  ○   ●   ○
       ○                 ○                  ○ ○ ○
       ○       ●         ○                    ○
       ○    (subject)    ○
        ○               ○             20 cameras × 5 rings
         ○ ○ ○ ○ ○ ○ ○ ○
```

### 3.3 Camera Mount Design

```
                    CAMERA MOUNT ASSEMBLY

    ┌───────────────────────────────┐
    │      Truss Mounting Clamp     │ ← Attaches to aluminum truss
    └───────────┬───────────────────┘
                │
        ┌───────┴───────┐
        │   Ball Head   │ ← Allows ±15° adjustment
        │   (Mini)      │
        └───────┬───────┘
                │
        ┌───────┴───────┐
        │ DXO One Mount │ ← 3D printed bracket
        │   Bracket     │   Secures camera body
        └───────┬───────┘
                │
            ┌───┴───┐
            │ DXO   │ ← Camera faces subject
            │ ONE   │   USB cable exits bottom
            └───┬───┘
                │
            USB Cable → To USB Hub


    BRACKET DIMENSIONS:
    ┌──────────────┐
    │              │  Width:  45mm
    │   ┌──────┐   │  Height: 70mm
    │   │ DXO  │   │  Depth:  30mm
    │   │ ONE  │   │
    │   │      │   │  Material: PETG or Aluminum
    │   └──┬───┘   │
    │      │USB    │
    └──────┴───────┘
```

### 3.4 Cable Management

```
    CABLE ROUTING STRATEGY

    Truss Section (8 cameras)
    ═══════════════════════════════════════════════════

    ○      ○      ○      ○      ○      ○      ○      ○   ← Cameras
    │      │      │      │      │      │      │      │
    └──┬───┴──┬───┴──┬───┴──────┴──┬───┴──┬───┴──┬───┘
       │      │      │             │      │      │
       ╰──────┴──────┴─────────────┴──────┴──────╯
                          │
                    ┌─────┴─────┐
                    │  USB Hub  │ ← Mounted on truss
                    │  (4-port) │
                    └─────┬─────┘
                          │
                    ┌─────┴─────┐
                    │  USB Hub  │ ← Daisy-chained
                    │  (4-port) │
                    └─────┬─────┘
                          │
                    ┌─────┴─────┐
                    │ Raspberry │ ← Node controller
                    │   Pi 4    │
                    └─────┬─────┘
                          │
                    Ethernet to Switch
```

---

## 4. Control System Architecture

### 4.1 Network Topology

```
                        NETWORK ARCHITECTURE

                    ┌─────────────────┐
                    │  Operator PC    │
                    │  192.168.1.100  │
                    └────────┬────────┘
                             │
                    ┌────────┴────────┐
                    │ Gigabit Switch  │
                    │ 192.168.1.1     │
                    └────────┬────────┘
                             │
        ┌──────────┬─────────┼─────────┬──────────┐
        │          │         │         │          │
   ┌────┴────┐┌────┴────┐┌───┴───┐┌────┴────┐┌────┴────┐
   │ Master  ││ Node 1  ││Node 2 ││  ...    ││ Node 25 │
   │   Pi    ││  Pi     ││  Pi   ││         ││   Pi    │
   │ .2      ││ .10     ││ .11   ││         ││ .34     │
   └─────────┘└─────────┘└───────┘└─────────┘└─────────┘

   IP Scheme: 192.168.1.x
   - Master:  192.168.1.2
   - Nodes:   192.168.1.10 - 192.168.1.34
   - Cameras: Identified by Node + Port (e.g., N01P1 = Node 1, Port 1)
```

### 4.2 Communication Protocol

```
                    MESSAGE FLOW DIAGRAM

    Operator        Master          Nodes (25)       Cameras (100)
       │               │                │                  │
       │──PREPARE────▶│                │                  │
       │               │──PREPARE────▶│                  │
       │               │               │──INIT──────────▶│
       │               │               │◀─READY──────────│
       │               │◀─READY───────│                  │
       │◀─ALL_READY───│                │                  │
       │               │                │                  │
       │──CAPTURE────▶│                │                  │
       │               │══TRIGGER═════▶│ (broadcast)      │
       │               │               │──CAPTURE───────▶│
       │               │               │◀─CAPTURED───────│
       │               │◀─COMPLETE────│                  │
       │◀─RESULTS─────│                │                  │
       │               │                │                  │

    Protocol: UDP multicast for trigger, TCP for status/data
    Trigger latency target: <5ms from master to all nodes
```

### 4.3 Software Components per Node

```
    RASPBERRY PI NODE SOFTWARE STACK

    ┌─────────────────────────────────────────────────────────┐
    │                    Application Layer                     │
    │  ┌─────────────────────────────────────────────────────┐│
    │  │            DXO Multi-Camera Controller              ││
    │  │  ┌───────────┐ ┌───────────┐ ┌───────────────────┐ ││
    │  │  │  Camera   │ │  Network  │ │    GPS/PPS        │ ││
    │  │  │  Manager  │ │  Listener │ │  Time Sync        │ ││
    │  │  └───────────┘ └───────────┘ └───────────────────┘ ││
    │  └─────────────────────────────────────────────────────┘│
    ├─────────────────────────────────────────────────────────┤
    │                    Service Layer                         │
    │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────┐   │
    │  │   libusb    │ │   gpsd      │ │   chronyd       │   │
    │  │  (USB I/O)  │ │ (GPS time)  │ │ (NTP client)    │   │
    │  └─────────────┘ └─────────────┘ └─────────────────┘   │
    ├─────────────────────────────────────────────────────────┤
    │                    OS: Raspberry Pi OS (64-bit)          │
    └─────────────────────────────────────────────────────────┘
```

---

## 5. Software Implementation

### 5.1 Master Controller (Python)

```python
# master_controller.py - Orchestrates 100-camera capture

import asyncio
import socket
import json
from dataclasses import dataclass
from typing import List, Dict
import time

@dataclass
class NodeStatus:
    node_id: int
    ip_address: str
    cameras_ready: int
    last_heartbeat: float

class BulletTimeMaster:
    """Master controller for 100-camera bullet time rig."""

    MULTICAST_GROUP = '239.255.1.1'
    MULTICAST_PORT = 5000
    STATUS_PORT = 5001

    def __init__(self, num_nodes: int = 25):
        self.num_nodes = num_nodes
        self.nodes: Dict[int, NodeStatus] = {}
        self.capture_in_progress = False

    async def initialize(self):
        """Initialize all nodes and cameras."""
        print(f"Initializing {self.num_nodes} nodes...")

        # Send PREPARE command to all nodes
        await self._broadcast_command({
            'cmd': 'PREPARE',
            'timestamp': time.time()
        })

        # Wait for all nodes to report ready
        ready_count = 0
        timeout = time.time() + 60  # 60 second timeout

        while ready_count < self.num_nodes and time.time() < timeout:
            await asyncio.sleep(0.1)
            ready_count = sum(1 for n in self.nodes.values()
                            if n.cameras_ready == 4)

        if ready_count == self.num_nodes:
            print(f"All {self.num_nodes} nodes ready!")
            return True
        else:
            print(f"Warning: Only {ready_count}/{self.num_nodes} nodes ready")
            return False

    async def capture(self, session_id: str) -> Dict:
        """Trigger synchronized capture across all cameras."""
        if self.capture_in_progress:
            return {'error': 'Capture already in progress'}

        self.capture_in_progress = True
        capture_time = time.time() + 0.5  # Schedule 500ms in future

        # Broadcast trigger with precise timestamp
        await self._broadcast_command({
            'cmd': 'CAPTURE',
            'session_id': session_id,
            'trigger_time': capture_time,  # Absolute time to fire
            'settings': {
                'format': 'RAW+JPEG',
                'iso': 'auto',
                'aperture': 'f/2.8'
            }
        })

        # Collect results
        results = await self._collect_results(session_id, timeout=30)
        self.capture_in_progress = False

        return {
            'session_id': session_id,
            'total_cameras': 100,
            'successful': len([r for r in results if r['success']]),
            'sync_variance_ms': self._calculate_variance(results),
            'results': results
        }

    async def _broadcast_command(self, command: dict):
        """Send command to all nodes via UDP multicast."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)

        message = json.dumps(command).encode()
        sock.sendto(message, (self.MULTICAST_GROUP, self.MULTICAST_PORT))
        sock.close()

    def _calculate_variance(self, results: List[dict]) -> float:
        """Calculate sync variance across all captures."""
        timestamps = [r['capture_timestamp'] for r in results if r['success']]
        if len(timestamps) < 2:
            return 0.0
        return (max(timestamps) - min(timestamps)) * 1000  # ms
```

### 5.2 Node Controller (Python)

```python
# node_controller.py - Controls 4 cameras per Raspberry Pi

import asyncio
import usb.core
import usb.util
import socket
import json
import time
from typing import List, Optional
from dxo_protocol import DxoOneProtocol

class CameraNode:
    """Controls 4 DXO One cameras on a single Raspberry Pi."""

    DXO_VENDOR_ID = 0x2B8F
    MAX_CAMERAS = 4

    def __init__(self, node_id: int, master_ip: str):
        self.node_id = node_id
        self.master_ip = master_ip
        self.cameras: List[DxoOneProtocol] = []
        self.gps_time_offset = 0.0

    async def initialize(self):
        """Discover and initialize all connected DXO One cameras."""
        # Find all DXO One devices
        devices = usb.core.find(
            find_all=True,
            idVendor=self.DXO_VENDOR_ID
        )

        for device in devices:
            if len(self.cameras) >= self.MAX_CAMERAS:
                break

            try:
                camera = DxoOneProtocol(device)
                await camera.initialize()
                self.cameras.append(camera)
                print(f"Node {self.node_id}: Camera {len(self.cameras)} initialized")
            except Exception as e:
                print(f"Node {self.node_id}: Failed to init camera: {e}")

        return len(self.cameras)

    async def listen_for_commands(self):
        """Listen for multicast commands from master."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('', 5000))

        # Join multicast group
        mreq = socket.inet_aton('239.255.1.1') + socket.inet_aton('0.0.0.0')
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        sock.setblocking(False)

        while True:
            try:
                data, addr = sock.recvfrom(4096)
                command = json.loads(data.decode())
                await self._handle_command(command)
            except BlockingIOError:
                await asyncio.sleep(0.001)  # 1ms poll

    async def _handle_command(self, command: dict):
        """Handle command from master."""
        cmd = command.get('cmd')

        if cmd == 'PREPARE':
            await self._prepare_cameras()

        elif cmd == 'CAPTURE':
            trigger_time = command['trigger_time']
            session_id = command['session_id']

            # Wait until trigger time (using GPS-synced clock)
            now = time.time() + self.gps_time_offset
            wait_time = trigger_time - now
            if wait_time > 0:
                await asyncio.sleep(wait_time)

            # Fire all cameras simultaneously
            results = await self._capture_all(session_id)
            await self._report_results(results)

    async def _capture_all(self, session_id: str) -> List[dict]:
        """Capture from all cameras as simultaneously as possible."""
        # Create capture tasks for all cameras
        tasks = [
            self._capture_single(i, camera, session_id)
            for i, camera in enumerate(self.cameras)
        ]

        # Execute all captures concurrently
        results = await asyncio.gather(*tasks, return_exceptions=True)
        return results

    async def _capture_single(self, port: int, camera: DxoOneProtocol,
                              session_id: str) -> dict:
        """Capture from a single camera."""
        start_time = time.time()

        try:
            await camera.take_photo()
            end_time = time.time()

            return {
                'node_id': self.node_id,
                'port': port,
                'camera_id': f"N{self.node_id:02d}P{port}",
                'session_id': session_id,
                'success': True,
                'capture_timestamp': start_time,
                'duration_ms': (end_time - start_time) * 1000
            }
        except Exception as e:
            return {
                'node_id': self.node_id,
                'port': port,
                'camera_id': f"N{self.node_id:02d}P{port}",
                'session_id': session_id,
                'success': False,
                'error': str(e)
            }
```

### 5.3 DXO Protocol Wrapper (Python)

```python
# dxo_protocol.py - USB protocol for DXO One

import usb.core
import usb.util
import json
import struct
from typing import Optional, Dict, Any

class DxoOneProtocol:
    """Low-level USB protocol handler for DXO One camera."""

    RPC_HEADER = bytes([0xA3, 0xBA, 0xD1, 0x10, 0x17, 0x08, 0x00, 0x0C])
    INIT_SIG = bytes([0xA3, 0xBA, 0xD1, 0x10, 0xAB, 0xCD, 0xAB, 0xCD])
    INIT_RESP = bytes([0xA3, 0xBA, 0xD1, 0x10, 0xDC, 0xBA, 0xDC, 0xBA])

    def __init__(self, device: usb.core.Device):
        self.device = device
        self.seq = 0
        self.ep_in = None
        self.ep_out = None

    async def initialize(self) -> bool:
        """Initialize USB connection to camera."""
        self.device.set_configuration()

        # Claim interfaces
        cfg = self.device.get_active_configuration()
        intf = cfg[(0, 0)]

        usb.util.claim_interface(self.device, 0)
        usb.util.claim_interface(self.device, 1)

        # Find endpoints
        self.ep_out = usb.util.find_descriptor(intf, bEndpointAddress=0x02)
        self.ep_in = usb.util.find_descriptor(intf, bEndpointAddress=0x81)

        # Send init response
        self._write(self.INIT_RESP + bytes(24))

        # Drain buffer and complete handshake
        while True:
            data = self._read(512)
            if data[:8] == self.INIT_SIG:
                self._write(self.INIT_RESP + bytes(24))
                break
            if len(data) == 0:
                break

        return True

    async def take_photo(self) -> dict:
        """Trigger photo capture."""
        return await self._send_command('dxo_photo_take', {})

    async def get_status(self) -> dict:
        """Get camera status including battery."""
        return await self._send_command('dxo_camera_status_get', {})

    async def set_setting(self, setting_type: str, value: str) -> dict:
        """Set a camera setting."""
        return await self._send_command('dxo_setting_set', {
            'type': setting_type,
            'param': value
        })

    async def _send_command(self, method: str, params: dict) -> dict:
        """Send JSON-RPC command to camera."""
        self.seq += 1

        rpc_msg = json.dumps({
            'jsonrpc': '2.0',
            'method': method,
            'params': params,
            'id': self.seq
        }).encode()

        # Build packet with header
        packet = self.RPC_HEADER + struct.pack('<H', len(rpc_msg))
        packet += bytes(22)  # Padding
        packet += rpc_msg

        # Pad to 512-byte boundary
        if len(packet) % 512:
            packet += bytes(512 - (len(packet) % 512))

        self._write(packet)
        response = self._read(512)

        # Parse response
        if len(response) > 32:
            json_start = response.find(b'{')
            if json_start >= 0:
                json_end = response.rfind(b'}') + 1
                return json.loads(response[json_start:json_end])

        return {'error': 'Invalid response'}

    def _write(self, data: bytes):
        self.ep_out.write(data)

    def _read(self, size: int) -> bytes:
        try:
            return bytes(self.ep_in.read(size, timeout=1000))
        except usb.core.USBTimeoutError:
            return bytes()
```

### 5.4 Operator UI (Web Interface)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  BULLET TIME CONTROL CENTER                                   [●] CONNECTED │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  SYSTEM STATUS                              CAMERA GRID (100 cameras)       │
│  ┌─────────────────────────────┐           ┌───────────────────────────┐   │
│  │ Nodes Online:    25/25  ●   │           │ ● ● ● ● ● ● ● ● ● ●      │   │
│  │ Cameras Ready:   100/100 ●  │           │ ● ● ● ● ● ● ● ● ● ●      │   │
│  │ Sync Accuracy:   ±3ms   ●   │           │ ● ● ● ● ● ● ● ● ● ●      │   │
│  │ Storage Free:    1.2 TB     │           │ ● ● ● ● ● ● ● ● ● ●      │   │
│  │ Battery Avg:     87%        │           │ ● ● ● ● ● ● ● ● ● ●      │   │
│  └─────────────────────────────┘           │ ● ● ● ● ● ● ● ● ● ●      │   │
│                                            │ ● ● ● ● ● ● ● ● ● ●      │   │
│  CAPTURE SETTINGS                          │ ● ● ● ● ● ● ● ● ● ●      │   │
│  ┌─────────────────────────────┐           │ ● ● ● ● ● ● ● ● ● ●      │   │
│  │ Format: [RAW + JPEG    ▼]   │           │ ● ● ● ● ● ● ● ● ● ●      │   │
│  │ ISO:    [Auto          ▼]   │           └───────────────────────────┘   │
│  │ Mode:   [Program       ▼]   │           ● = Ready  ○ = Offline  ◐ = Busy│
│  └─────────────────────────────┘                                           │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │                    [████  CAPTURE  ████]                            │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  LAST CAPTURE: Session_20240115_143022                                     │
│  ├── Success: 98/100 cameras                                               │
│  ├── Sync Variance: 47ms                                                   │
│  └── Files: /nas/sessions/Session_20240115_143022/                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Synchronization Strategy

### 6.1 Time Sync Hierarchy

```
                    SYNCHRONIZATION ARCHITECTURE

    ┌─────────────────────────────────────────────────────────────┐
    │                      GPS SATELLITES                          │
    │                           ▼                                  │
    │                    ┌─────────────┐                          │
    │                    │ GPS Antenna │                          │
    │                    └──────┬──────┘                          │
    │                           │                                  │
    │           ┌───────────────┼───────────────┐                 │
    │           ▼               ▼               ▼                 │
    │     ┌──────────┐   ┌──────────┐   ┌──────────┐             │
    │     │ GPS Mod  │   │ GPS Mod  │   │ GPS Mod  │  ... ×26    │
    │     │ (Master) │   │ (Node 1) │   │ (Node 2) │             │
    │     └────┬─────┘   └────┬─────┘   └────┬─────┘             │
    │          │              │              │                    │
    │          ▼              ▼              ▼                    │
    │     ┌──────────┐   ┌──────────┐   ┌──────────┐             │
    │     │ PPS Pin  │   │ PPS Pin  │   │ PPS Pin  │             │
    │     │ Interrupt│   │ Interrupt│   │ Interrupt│             │
    │     └────┬─────┘   └────┬─────┘   └────┬─────┘             │
    │          │              │              │                    │
    │          ▼              ▼              ▼                    │
    │     ┌──────────┐   ┌──────────┐   ┌──────────┐             │
    │     │ chronyd  │   │ chronyd  │   │ chronyd  │             │
    │     │ (±1µs)   │   │ (±1µs)   │   │ (±1µs)   │             │
    │     └──────────┘   └──────────┘   └──────────┘             │
    │                                                             │
    │     All nodes synchronized to GPS time within ±1µs         │
    └─────────────────────────────────────────────────────────────┘


    TRIGGER SEQUENCE (Target: <50ms total variance)

    T-500ms: Master broadcasts CAPTURE command with trigger_time
             ↓
    T-100ms: All nodes have received command, preparing
             ↓
    T-10ms:  Nodes begin USB command transmission
             ↓
    T=0:     All cameras receive capture command
             ↓
    T+50ms:  All cameras have triggered (within 50ms window)
             ↓
    T+500ms: All captures complete, results collected
```

### 6.2 Sync Variance Budget

| Component | Target | Worst Case | Mitigation |
|-----------|--------|------------|------------|
| GPS PPS accuracy | ±1µs | ±10µs | Use quality GPS modules |
| Network latency | ±1ms | ±5ms | UDP multicast, same switch |
| USB command time | ±10ms | ±30ms | Pre-stage commands |
| Camera shutter lag | ±10ms | ±20ms | All same model = consistent |
| **Total** | **±22ms** | **±65ms** | Stay under 100ms |

### 6.3 Pre-Trigger Optimization

```
    OPTIMIZED CAPTURE SEQUENCE

    Traditional:           Optimized (Pre-staged):

    TRIGGER ──┐            PRE-STAGE ──┐
              │                        │
              ▼                        ▼
    ┌─────────────────┐    ┌─────────────────┐
    │ Send USB cmd    │    │ Send USB "ready"│  T-100ms
    │ (10-30ms)       │    │ (10-30ms)       │
    └────────┬────────┘    └────────┬────────┘
             │                      │
             ▼                      ▼
    ┌─────────────────┐    ┌─────────────────┐
    │ Camera processes│    │ Camera waits    │  T-50ms
    │ (20-50ms)       │    │ (ready state)   │
    └────────┬────────┘    └────────┬────────┘
             │                      │
             ▼              TRIGGER │
    ┌─────────────────┐            ▼
    │ Shutter fires   │    ┌─────────────────┐
    │                 │    │ Shutter fires   │  T=0
    └─────────────────┘    │ (minimal delay) │
                           └─────────────────┘

    Total: 30-80ms         Total: 5-15ms
```

---

## 7. Power Management

### 7.1 Power Distribution Diagram

```
    POWER DISTRIBUTION SYSTEM

    ┌─────────────────────────────────────────────────────────────────┐
    │                     MAIN POWER INPUT                            │
    │                       (20A, 120V)                               │
    └──────────────────────────┬──────────────────────────────────────┘
                               │
                    ┌──────────┴──────────┐
                    │    UPS (1500VA)     │
                    │   Backup: 15 min    │
                    └──────────┬──────────┘
                               │
            ┌──────────────────┼──────────────────┐
            │                  │                  │
    ┌───────▼───────┐  ┌───────▼───────┐  ┌───────▼───────┐
    │   PDU Strip   │  │   PDU Strip   │  │  PDU Strip    │
    │   (Zone A)    │  │   (Zone B)    │  │  (Zone C)     │
    │  Cameras 1-36 │  │ Cameras 37-72 │  │ Cameras 73-100│
    └───────┬───────┘  └───────┬───────┘  └───────┬───────┘
            │                  │                  │
      ┌─────┼─────┐      ┌─────┼─────┐      ┌─────┼─────┐
      │     │     │      │     │     │      │     │     │
      ▼     ▼     ▼      ▼     ▼     ▼      ▼     ▼     ▼
    ┌───┐ ┌───┐ ┌───┐  ┌───┐ ┌───┐ ┌───┐  ┌───┐ ┌───┐ ┌───┐
    │60W│ │60W│ │60W│  │60W│ │60W│ │60W│  │60W│ │60W│ │60W│
    │USB│ │USB│ │USB│  │USB│ │USB│ │USB│  │USB│ │USB│ │USB│
    │PSU│ │PSU│ │PSU│  │PSU│ │PSU│ │PSU│  │PSU│ │PSU│ │PSU│
    └─┬─┘ └─┬─┘ └─┬─┘  └─┬─┘ └─┬─┘ └─┬─┘  └─┬─┘ └─┬─┘ └─┬─┘
      │     │     │      │     │     │      │     │     │
    10cam 10cam 12cam  10cam 10cam 12cam  10cam 10cam 8cam


    POWER CONSUMPTION ESTIMATE:

    Component               Count    Watts Each    Total
    ──────────────────────────────────────────────────────
    DXO One (charging)      100      2.5W          250W
    DXO One (idle)          100      0.5W          50W
    Raspberry Pi 4          25       5W            125W
    USB Hubs (powered)      25       5W            125W
    Network Switch          1        20W           20W
    Master Pi + Misc        1        30W           30W
    ──────────────────────────────────────────────────────
    Peak (all charging):                          600W
    Operating (idle):                             350W
    Capture burst:                                ~400W
```

### 7.2 Battery Strategy

```
    BATTERY MANAGEMENT OPTIONS

    OPTION A: Always Powered (Recommended)
    ────────────────────────────────────
    - Cameras always connected to USB power
    - Internal batteries topped up
    - No battery concerns during shoots
    - Higher base power consumption

    OPTION B: Battery-Only Capture
    ────────────────────────────────────
    - Charge all cameras before session
    - Disconnect power during capture (cleaner cables)
    - Risk: Inconsistent battery levels affect sync
    - DXO One battery: ~200 shots

    OPTION C: Hybrid
    ────────────────────────────────────
    - Power connected for standby
    - Quick-disconnect for capture
    - Best of both worlds
    - More complex cable management

    RECOMMENDED: Option A (Always Powered)
    - Simplest operation
    - Most reliable
    - Power consumption acceptable
```

---

## 8. Capture Workflow

### 8.1 Session Workflow

```
    COMPLETE CAPTURE SESSION WORKFLOW

    ┌─────────────────────────────────────────────────────────────────┐
    │                      PRE-SESSION SETUP                          │
    │                        (30-60 minutes)                          │
    └─────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 1. Power On Sequence                                            │
    │    ├── Turn on PDUs                                             │
    │    ├── Wait 30s for USB hubs to initialize                      │
    │    ├── Power on all Raspberry Pis                               │
    │    └── Wait 2min for boot and network                           │
    └─────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 2. System Health Check                                          │
    │    ├── Run diagnostic script on master                          │
    │    ├── Verify all 25 nodes respond                              │
    │    ├── Check camera count on each node (expect 4)               │
    │    ├── Verify GPS lock and time sync (<10µs)                    │
    │    └── Check storage availability (>10GB per camera)            │
    └─────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 3. Camera Initialization                                        │
    │    ├── Send PREPARE command to all nodes                        │
    │    ├── Each node initializes USB connections                    │
    │    ├── Apply camera settings (ISO, aperture, format)            │
    │    ├── Verify all 100 cameras report READY                      │
    │    └── Display status grid on operator UI                       │
    └─────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 4. Position Subject                                             │
    │    ├── Guide subject to center mark                             │
    │    ├── Optionally display live view composite                   │
    │    └── Fine-tune any camera angles                              │
    └─────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │                        CAPTURE PHASE                            │
    │                      (Repeat as needed)                         │
    └─────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 5. Execute Capture                                              │
    │    ├── Operator presses CAPTURE button                          │
    │    ├── Master broadcasts trigger with timestamp                 │
    │    ├── All cameras fire within sync window                      │
    │    ├── Results collected (success/fail, timing)                 │
    │    └── Display capture summary                                  │
    └─────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 6. Data Collection                                              │
    │    ├── Images stored locally on each Pi's SD card               │
    │    ├── Background transfer to NAS begins                        │
    │    ├── Verify file integrity (checksums)                        │
    │    └── Log session metadata                                     │
    └─────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
                        (Repeat steps 4-6 for more takes)
                                   │
                                   ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │                      POST-SESSION                               │
    └─────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 7. Data Backup                                                  │
    │    ├── Verify all files transferred to NAS                      │
    │    ├── Run RAW file validation                                  │
    │    ├── Create session archive                                   │
    │    └── Optional: Cloud backup                                   │
    └─────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 8. Shutdown                                                     │
    │    ├── Safe shutdown command to all Pis                         │
    │    ├── Wait for shutdown complete                               │
    │    ├── Power off PDUs                                           │
    │    └── Cover/protect rig                                        │
    └─────────────────────────────────────────────────────────────────┘
```

### 8.2 File Naming Convention

```
    FILE NAMING SCHEME

    Format: {Session}_{CameraID}_{Sequence}.{ext}

    Example:
    ├── BT_20240115_143022/
    │   ├── BT_20240115_143022_N01P1_001.DNG
    │   ├── BT_20240115_143022_N01P1_001.JPG
    │   ├── BT_20240115_143022_N01P2_001.DNG
    │   ├── BT_20240115_143022_N01P2_001.JPG
    │   ├── ...
    │   ├── BT_20240115_143022_N25P4_001.DNG
    │   └── BT_20240115_143022_N25P4_001.JPG
    │
    │   Metadata:
    │   ├── session_metadata.json
    │   ├── sync_report.json
    │   └── camera_positions.json

    Camera ID Format: N{node:02d}P{port}
    - N01P1 = Node 1, Port 1 (Camera #1)
    - N25P4 = Node 25, Port 4 (Camera #100)
```

---

## 9. Post-Processing Pipeline

### 9.1 Processing Workflow

```
    POST-PROCESSING PIPELINE

    ┌──────────────────────────────────────────────────────────────────┐
    │                         RAW PROCESSING                           │
    │                      (Adobe Camera Raw / DxO)                    │
    └──────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
    ┌──────────────────────────────────────────────────────────────────┐
    │  1. Batch RAW Development                                        │
    │     ├── Apply consistent color profile across all 100 images     │
    │     ├── Auto white balance or manual preset                      │
    │     ├── Lens correction (DXO One profile)                        │
    │     ├── Noise reduction (if high ISO)                            │
    │     └── Export as 16-bit TIFF                                    │
    └──────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
    ┌──────────────────────────────────────────────────────────────────┐
    │                      IMAGE ALIGNMENT                             │
    │                    (Photoshop / Hugin)                           │
    └──────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
    ┌──────────────────────────────────────────────────────────────────┐
    │  2. Subject Alignment                                            │
    │     ├── Identify subject center point in each image              │
    │     ├── Calculate offset from ideal position                     │
    │     ├── Apply translation/rotation to align                      │
    │     └── Crop to common frame                                     │
    └──────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
    ┌──────────────────────────────────────────────────────────────────┐
    │                     COLOR MATCHING                               │
    │                  (Color calibration)                             │
    └──────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
    ┌──────────────────────────────────────────────────────────────────┐
    │  3. Exposure/Color Normalization                                 │
    │     ├── Use reference image as target                            │
    │     ├── Match histogram across all frames                        │
    │     ├── Correct vignetting differences                           │
    │     └── Match white balance exactly                              │
    └──────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
    ┌──────────────────────────────────────────────────────────────────┐
    │                      VIDEO CREATION                              │
    │                  (FFmpeg / After Effects)                        │
    └──────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
    ┌──────────────────────────────────────────────────────────────────┐
    │  4. Sequence Assembly                                            │
    │     ├── Import aligned frames in camera order                    │
    │     ├── Interpolate between frames (optical flow)                │
    │     ├── Smooth camera path with spline                           │
    │     ├── Add speed ramping (slow-mo in/out)                       │
    │     └── Export as video (4K, 60fps)                              │
    └──────────────────────────────────────────────────────────────────┘


    OUTPUT OPTIONS:

    A. Standard Bullet Time
       ├── 100 frames → 3.3 second video @ 30fps
       └── Smooth rotation around frozen subject

    B. Interpolated Bullet Time
       ├── 100 frames → 1000 interpolated frames
       ├── 33 second video @ 30fps
       └── Silky smooth slow-motion rotation

    C. Interactive 360° View
       ├── Web-based viewer
       ├── Drag to rotate around subject
       └── Mobile-friendly
```

### 9.2 Automation Scripts

```python
# process_session.py - Automated post-processing pipeline

import os
import subprocess
from pathlib import Path
import json

class BulletTimeProcessor:
    """Automated processing pipeline for bullet time sessions."""

    def __init__(self, session_path: str):
        self.session_path = Path(session_path)
        self.raw_dir = self.session_path / "raw"
        self.processed_dir = self.session_path / "processed"
        self.output_dir = self.session_path / "output"

    def process_session(self):
        """Run complete processing pipeline."""
        print("Starting bullet time processing...")

        # Step 1: Develop RAW files
        self.develop_raw_files()

        # Step 2: Align images
        self.align_images()

        # Step 3: Color match
        self.color_match()

        # Step 4: Create video
        self.create_video()

        print("Processing complete!")

    def develop_raw_files(self):
        """Batch process RAW files to TIFF."""
        print("Developing RAW files...")

        # Using rawtherapee-cli for batch processing
        raw_files = sorted(self.raw_dir.glob("*.DNG"))

        for raw_file in raw_files:
            output_file = self.processed_dir / f"{raw_file.stem}.tiff"

            subprocess.run([
                "rawtherapee-cli",
                "-o", str(output_file),
                "-p", "bullet_time_profile.pp3",  # Custom profile
                "-b16",  # 16-bit output
                "-t",    # TIFF format
                str(raw_file)
            ])

    def align_images(self):
        """Align all images to common center."""
        print("Aligning images...")

        # Using ImageMagick for alignment
        tiff_files = sorted(self.processed_dir.glob("*.tiff"))

        # Find subject center in reference image (middle camera)
        ref_image = tiff_files[len(tiff_files) // 2]
        # ... alignment code using feature detection ...

    def color_match(self):
        """Match colors across all frames."""
        print("Color matching...")
        # Using ImageMagick histogram matching
        # ... color matching code ...

    def create_video(self):
        """Assemble frames into video."""
        print("Creating video...")

        # Using FFmpeg
        subprocess.run([
            "ffmpeg",
            "-framerate", "30",
            "-pattern_type", "glob",
            "-i", str(self.processed_dir / "aligned_*.tiff"),
            "-c:v", "libx264",
            "-crf", "18",
            "-pix_fmt", "yuv420p",
            str(self.output_dir / "bullet_time.mp4")
        ])

        # Create interpolated version with optical flow
        subprocess.run([
            "ffmpeg",
            "-i", str(self.output_dir / "bullet_time.mp4"),
            "-vf", "minterpolate='mi_mode=mci:mc_mode=aobmc:vsbmc=1:fps=300'",
            "-c:v", "libx264",
            "-crf", "18",
            str(self.output_dir / "bullet_time_interpolated.mp4")
        ])
```

---

## 10. Budget Breakdown

### 10.1 Complete System Cost

```
    BUDGET BREAKDOWN - POOR MAN'S BULLET TIME

    ┌─────────────────────────────────────────────────────────────────┐
    │                    MINIMUM VIABLE SYSTEM                        │
    │                   (Used cameras @ $50/ea)                       │
    └─────────────────────────────────────────────────────────────────┘

    CAMERAS & ACCESSORIES
    ├── 100× DXO One (used, good condition)     $5,000
    ├── 100× MicroSD 32GB                         $800
    └── 100× USB cables (short)                   $300
                                           Subtotal: $6,100

    COMPUTING
    ├── 25× Raspberry Pi 4 (4GB)                $1,375
    ├── 1× Raspberry Pi 5 (8GB)                    $80
    ├── 26× MicroSD 64GB                          $312
    ├── 25× USB 3.0 Hub (4-port, powered)         $625
    └── 1× Gigabit Switch (32-port)               $150
                                           Subtotal: $2,542

    SYNC & TRIGGER
    ├── 26× GPS Module (NEO-6M)                   $390
    ├── 1× Arduino Mega                            $45
    └── Trigger buttons/receivers                  $75
                                           Subtotal:   $510

    POWER
    ├── 10× USB PSU (10-port, 60W)                $400
    ├── 26× Pi Power Supplies                     $260
    ├── 2× PDU Strips                             $200
    └── 1× UPS (1500VA)                           $200
                                           Subtotal: $1,060

    PHYSICAL RIG
    ├── Aluminum truss sections                 $1,500
    ├── 100× Camera mounts (3D printed)           $200
    ├── 100× Mini ball heads                      $800
    ├── Cable management                          $100
    └── Cases/transport                           $500
                                           Subtotal: $3,100

    ─────────────────────────────────────────────────────
    TOTAL (MINIMUM):                           $13,312
    ─────────────────────────────────────────────────────


    ┌─────────────────────────────────────────────────────────────────┐
    │                    RECOMMENDED SYSTEM                           │
    │                  (Used cameras @ $100/ea)                       │
    └─────────────────────────────────────────────────────────────────┘

    Add to minimum:
    ├── Higher quality cameras (+$50/ea)        +$5,000
    ├── Spare cameras (10%)                     +$1,000
    ├── Better USB hubs (industrial)              +$250
    ├── Professional truss system               +$1,000
    ├── NAS for storage                           +$500
    └── Contingency (10%)                       +$2,000

    ─────────────────────────────────────────────────────
    TOTAL (RECOMMENDED):                       $23,062
    ─────────────────────────────────────────────────────
```

### 10.2 Cost Comparison

```
    COST COMPARISON: BULLET TIME SYSTEMS

    ┌────────────────────────────────────────────────────────────────┐
    │                                                                │
    │   Professional (DSLR)         ████████████████████ $150,000+  │
    │   100× Canon 5D + sync                                        │
    │                                                                │
    │   Mid-Range (Mirrorless)      ██████████████ $75,000         │
    │   100× Sony α6000 + sync                                      │
    │                                                                │
    │   Budget (Action Cam)         ████████ $40,000                │
    │   100× GoPro Hero                                             │
    │                                                                │
    │   Poor Man's (DXO One)        ████ $15,000-25,000             │
    │   This project! ◄──────────────────────                       │
    │                                                                │
    │   Ultra Budget (Webcam)       ██ $5,000                       │
    │   100× Logitech C920                                          │
    │   (much lower quality)                                        │
    │                                                                │
    └────────────────────────────────────────────────────────────────┘

    VALUE PROPOSITION:

    DXO One Advantages:
    ✓ 1" sensor (larger than GoPro, most action cams)
    ✓ 20.2MP resolution
    ✓ RAW capture (DNG)
    ✓ f/1.8 aperture (great low light)
    ✓ Compact size (easy mounting)
    ✓ USB control (proven protocol)
    ✓ Very affordable used ($50-150)

    DXO One Limitations:
    ✗ Discontinued (no support)
    ✗ Fixed focal length (32mm equiv.)
    ✗ No zoom
    ✗ Limited availability
```

---

## 11. Build Phases

### Phase 1: Proof of Concept (2-4 weeks)

```
    PHASE 1: PROOF OF CONCEPT
    Budget: ~$2,000

    Goals:
    ├── Validate sync timing with 8 cameras
    ├── Test software on 2 Raspberry Pi nodes
    └── Prove concept before full investment

    Hardware:
    ├── 8× DXO One cameras
    ├── 2× Raspberry Pi 4
    ├── 2× USB hubs
    ├── 2× GPS modules
    └── Basic mounting (tripods)

    Deliverables:
    ├── Working 8-camera sync capture
    ├── Measured sync variance (<100ms)
    ├── Basic bullet time video (8 frames)
    └── Go/no-go decision for full build
```

### Phase 2: Scaled Prototype (4-6 weeks)

```
    PHASE 2: SCALED PROTOTYPE
    Budget: ~$6,000 (cumulative)

    Goals:
    ├── Scale to 25 cameras (quarter system)
    ├── Build partial physical rig (45° arc)
    └── Develop production workflow

    Hardware:
    ├── 25× DXO One cameras (add 17)
    ├── 7× Raspberry Pi 4 (add 5)
    ├── Full network infrastructure
    └── First truss section

    Deliverables:
    ├── 25-camera working system
    ├── Validated sync (<50ms)
    ├── Post-processing pipeline
    └── Refined cost/time estimates
```

### Phase 3: Full System (6-8 weeks)

```
    PHASE 3: FULL 100-CAMERA SYSTEM
    Budget: ~$20,000 (cumulative)

    Goals:
    ├── Complete 100-camera array
    ├── Full 180° arc rig
    └── Production-ready system

    Hardware:
    ├── 100× DXO One cameras (add 75)
    ├── 25× Raspberry Pi 4 (add 18)
    ├── Complete truss system
    └── All power/network infrastructure

    Deliverables:
    ├── Production bullet time captures
    ├── Documentation & training
    └── Portable/deployable system
```

### Phase 4: Optimization (Ongoing)

```
    PHASE 4: OPTIMIZATION

    Improvements:
    ├── Reduce sync variance (<25ms)
    ├── Faster capture cycle (<10 seconds)
    ├── Automated alignment pipeline
    ├── Real-time preview system
    └── Mobile deployment capability

    Possible Additions:
    ├── WiFi control (no USB cables)
    ├── Battery-only operation
    ├── 360° full ring configuration
    └── Automated subject tracking
```

---

## 12. Risk Mitigation

### 12.1 Technical Risks

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| USB sync variance >100ms | High | Medium | Pre-stage commands, GPS time sync |
| Camera failures | Medium | Medium | 10% spare cameras, hot-swap design |
| Network latency | High | Low | Dedicated switch, multicast, short cables |
| Power issues | High | Low | UPS backup, staged power-on |
| Data loss | High | Low | Local + NAS storage, checksums |
| Raspberry Pi failures | Medium | Low | SD card backups, spare Pis |

### 12.2 Operational Risks

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Camera availability | High | Medium | Buy all cameras at once, have spares |
| Setup time too long | Medium | Medium | Practice, checklists, parallel work |
| Transport damage | High | Medium | Road cases, careful packing |
| Operator error | Medium | Medium | Training, clear UI, confirmation dialogs |

### 12.3 Fallback Strategies

```
    DEGRADED OPERATION MODES

    Full System (100 cameras):
    └── Normal operation, full bullet time effect

    Degraded Mode A (75+ cameras):
    └── Wider angle spacing, still smooth rotation

    Degraded Mode B (50+ cameras):
    └── 90° arc instead of 180°, half rotation

    Minimum Viable (25 cameras):
    └── 45° arc, subtle but usable effect

    Emergency Mode (8 cameras):
    └── Very basic effect, jerky rotation
```

---

## Appendix A: Parts List (Detailed)

### Cameras
- 100× DXO One Camera (eBay, ~$50-150 each)
- 10× DXO One Camera (spares)
- 110× SanDisk Ultra 32GB MicroSD

### Computing
- 25× Raspberry Pi 4 Model B (4GB)
- 1× Raspberry Pi 5 (8GB)
- 26× Raspberry Pi Official Power Supply
- 26× SanDisk 64GB MicroSD (A2 rated)
- 25× Anker 4-Port USB 3.0 Hub (powered)
- 1× Netgear 24-port Gigabit Smart Switch
- 30× Cat6 Ethernet Cables (various lengths)

### Sync Hardware
- 26× u-blox NEO-6M GPS Module
- 1× Arduino Mega 2560
- 1× Big Red Button (arcade style)
- 1× Wireless trigger receiver

### Power
- 10× Anker 60W 10-Port USB Charger
- 2× Tripp Lite 12-Outlet PDU
- 1× CyberPower 1500VA UPS
- 10× Heavy-duty extension cords

### Rig Structure
- 10× Global Truss F34 curved sections
- 100× Custom 3D-printed camera brackets
- 100× Neewer Mini Ball Head
- 500× Cable clips and ties
- 5× SKB Road Cases

---

## Appendix B: Raspberry Pi Node Setup

```bash
#!/bin/bash
# setup_node.sh - Configure Raspberry Pi as camera node

# Update system
sudo apt update && sudo apt upgrade -y

# Install dependencies
sudo apt install -y \
    python3-pip \
    python3-libusb1 \
    gpsd \
    gpsd-clients \
    chrony \
    git

# Install Python packages
pip3 install \
    pyusb \
    asyncio \
    aiohttp

# Configure GPS
sudo systemctl enable gpsd
sudo systemctl start gpsd

# Configure chrony for GPS sync
cat << 'EOF' | sudo tee /etc/chrony/chrony.conf
refclock PPS /dev/pps0 lock NMEA
refclock SHM 0 offset 0.5 delay 0.2 refid NMEA noselect
makestep 1 -1
EOF

sudo systemctl restart chrony

# Clone camera control software
git clone https://github.com/user/bullet-time-control.git
cd bullet-time-control

# Set up as systemd service
sudo cp bullet-time-node.service /etc/systemd/system/
sudo systemctl enable bullet-time-node
sudo systemctl start bullet-time-node

echo "Node setup complete!"
```

---

## Appendix C: Quick Reference Card

```
    ┌─────────────────────────────────────────────────────────────────┐
    │                  BULLET TIME QUICK REFERENCE                    │
    ├─────────────────────────────────────────────────────────────────┤
    │                                                                 │
    │  STARTUP SEQUENCE:                                              │
    │  1. Power on PDUs (wait 30s)                                    │
    │  2. Power on Pis (wait 2min)                                    │
    │  3. Run health check: ./check_system.sh                         │
    │  4. Initialize cameras: ./init_cameras.sh                       │
    │  5. Open control UI: http://192.168.1.2:8080                    │
    │                                                                 │
    │  CAPTURE:                                                       │
    │  - Position subject at center mark                              │
    │  - Press big green CAPTURE button                               │
    │  - Wait for "Capture Complete" message                          │
    │  - Check sync variance (should be <50ms)                        │
    │                                                                 │
    │  SHUTDOWN:                                                      │
    │  1. Run: ./shutdown_all.sh                                      │
    │  2. Wait for all Pis to power off (LEDs dark)                   │
    │  3. Turn off PDUs                                               │
    │                                                                 │
    │  TROUBLESHOOTING:                                               │
    │  - Camera not responding: Unplug/replug USB                     │
    │  - Node offline: Check network cable                            │
    │  - High sync variance: Check GPS antenna position               │
    │  - Capture failed: Check SD card space                          │
    │                                                                 │
    │  CONTACTS:                                                      │
    │  - System Admin: admin@example.com                              │
    │  - Emergency: 555-0123                                          │
    │                                                                 │
    └─────────────────────────────────────────────────────────────────┘
```

---

## Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2024-01-15 | Claude | Initial comprehensive plan |

---

*This document provides a complete blueprint for building a 100-camera bullet time system using DXO One cameras. The "poor man's" approach leverages the excellent image quality of discontinued DXO One cameras available at low prices on the used market, combined with commodity computing hardware (Raspberry Pi) and open-source software.*
