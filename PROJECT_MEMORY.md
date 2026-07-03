# DTN Mesh Relay — Project Memory & Pivot Context

> Living handoff document. Two parts:
> **Part A** = the target architecture we are building toward (the "why").
> **Part B** = exactly what has changed so far and what still remains (the "what/where/next").

---

# PART A — Target Architecture

## A.1 The core idea

A **Delay-Tolerant Network (DTN)** messaging system built from three transports, where a **single LoRa
device is a WiFi Access-Point hub** that many phones connect to at once. Long-range links are handled by a
**LoRa hub-to-hub backbone**; short-range links are **phone-to-phone (BLE / WiFi Direct)**; and messages
survive the lack of an end-to-end path via **store-carry-forward** and **data-mule** phones. The system is
**independent of Meshtastic** and runs **custom firmware** on the LoRa modules.

The old model (each phone owns one LoRa radio, driven through the Meshtastic app over AIDL) is abandoned.

## A.2 Topology

```
   REGION A (Hub A = ESP32 SoftAP)              REGION B (Hub B = ESP32 SoftAP)
   SSID "DTN-HUB-A", 192.168.4.1                SSID "DTN-HUB-B", 192.168.4.1
        ^   ^   ^  (phones join over WiFi,             ^   ^   ^
        |   |   |   framed TCP :9740)                   |   |   |
      A1  A2  A3                                       B1  B2  B3
        \__ BLE / WiFi-Direct __/                        \__ BLE / WiFi-Direct __/
                     |                                          ^
                     \___ phone A2 physically carries __________/  (data mule)

        Hub A  <========== long-range LoRa RF (SX1262) ==========>  Hub B
                controlled flooding + msg-ID dedup + TTL + hop-limit
```

## A.3 Requirements this architecture must satisfy

1. A single LoRa device connects to **multiple phones simultaneously over WiFi**.
2. All phones can talk **directly peer-to-peer** over WiFi or BLE.
3. Phones exchange messages directly; when a LoRa node comes in range, it **synchronizes all pending
   messages** from connected phones and forwards them across the **LoRa-to-LoRa** network.
4. A phone can receive & temporarily store messages, or forward immediately to a nearby phone. Phones on the
   same LoRa node sync pending messages to it; later, in another node's coverage, they sync there too —
   letting messages propagate through the LoRa network.
5. Even with **no LoRa node available**, phones still sync with each other over WiFi/BLE. Long-range =
   LoRa backbone; nearby = WiFi/BLE.
6. A third phone acts as a **data mule** (mobile store-carry-forward): A→C buffered while B is unreachable;
   C carries it; when B later meets C (via LoRa mesh or direct P2P), C delivers it. Reliable delivery
   without a contemporaneous end-to-end path.

## A.4 Roles & division of labor

- **Phone = the brain.** Creates messages, runs the store-carry-forward buffer, and does the *adaptive*
  encounter-based routing (PRoPHET / MaxProp / Double Q-Learning). Connects opportunistically to a hub
  (WiFi) and to other phones (BLE primary, WiFi Direct fallback).
- **LoRa hub = the bridge + backbone.** ESP32 + SX1262. Runs a WiFi SoftAP + framed TCP server for phones,
  keeps its own bundle buffer, and forwards hub-to-hub with **simple controlled flooding** (message-id
  dedup + TTL + hop-limit). Deliberately *not* smart — the intelligence lives on the phones.

## A.5 Key design decisions

- **BLE is the primary phone-to-phone transport** (WiFi Direct is fallback). While a phone is joined to a
  hub SoftAP as a WiFi STA, concurrent WiFi Direct is unreliable on many devices; BLE coexists cleanly.
- **Self-contained bundle format.** Because the hub-to-hub LoRa link has no outer envelope to carry
  addressing, origin + destination node ids live **inside** the DTN header (see B.1).
- **One stable identity per phone** shared across all transports (`LocalNodeIdentity`).
- **Broadcast** uses a reserved wire address `0xFFFFFFFF` so the hub can fan a bundle out to every phone.

## A.6 Self-contained wire header (32 bytes, big-endian)

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0  | 16 | messageId (UUID) | dedup key on hubs |
| 16 | 4  | originNodeId (uint32) | `NodeId.toNodeNum32()` |
| 20 | 4  | destinationNodeId (uint32) | `0xFFFFFFFF` = broadcast |
| 24 | 2  | ttlMinutes (uint16) | counts down |
| 26 | 1  | hopCount | incremented per LoRa re-flood |
| 27 | 1  | messageType | `DtnMessageType.wireValue` |
| 28 | 1  | channel | 0 = primary |
| 29 | 1  | fragmentIndex | 0 = single frame |
| 30 | 1  | fragmentTotal | 1 = no fragmentation |
| 31 | 1  | reserved | 0x00 |

Usable single-frame payload over LoRa (SX126x, 255 B max): **223 bytes**. Keep ≲ 200 for airtime.

## A.7 Phone ⇄ Hub protocol (framed TCP, port 9740)

Frame = `[1B type][2B length BE][payload]`
- `HELLO` (0x03): hub → phone, 4B hub id, sent on connect (phone then treats the hub as a peer).
- `REGISTER` (0x01): phone → hub, 4B phone id (hub routes bundles back to this phone).
- `BUNDLE` (0x02): either direction, a self-contained DTN bundle.

## A.8 Phone ⇄ Phone protocols

- **BLE:** each phone runs a GATT server (advertises the DTN service UUID + its 4B id as service data)
  and scans/connects as client. Bundles are `[2B totalLen][wire]` chunked to the negotiated MTU.
- **WiFi Direct:** framed TCP on port 9734. `[4B wireLen][wire][4B idLen][id]`; `wireLen == 0` is a
  **HELLO** (identity-only) used to learn each peer's real id ↔ IP.

---

# PART B — Changes Made So Far & What Remains

Repo: `D:\PROJECTS\DTN CN CAPSTONE` · Android module `com.dtn.mesh` ("DtnMeshRelay").
`docs/meshtastic-ref/` is a read-only Meshtastic clone kept only as AIDL reference.

**Build/test status:** unit tests pass — DoubleQLearningEngine 13/13, DtnWireCodec 9/9, ProphetStrategy
6/6, AirtimeBudgetTracker 5/5. (Full Gradle assemble not yet run in this environment.)

## B.1 DONE — Self-contained wire header

- **`model/NodeId.kt`** — added `toNodeNum32()` (exact for `!hex`, FNV-1a hash otherwise), reserved
  `BROADCAST_NODE_NUM32 = 0xFFFFFFFF`; `fromNodeNum()` maps that back to `BROADCAST`; `toNodeNum32()`
  special-cases broadcast.
- **`model/DtnMessage.kt`** — header 24 → **32 bytes**; de-Meshtastic'd constants: `LORA_MAX_FRAME_BYTES=255`,
  `DTN_HEADER_BYTES=32`, `MAX_SINGLE_PACKET_PAYLOAD=223`. (`PORT_NUM_PRIVATE_APP` kept as a harmless legacy
  field.)
- **`receiver/DtnWireCodec.kt`** — rewritten. `encode()` writes origin+dest; `decode(wireBytes, rssi, snr)`
  reads them from the header (old `fromNodeId`/`destinationNodeId` params removed). Removed
  `estimateCreationTime()`.
- **`test/.../DtnWireCodecTest.kt`** — updated to the new signature; added origin/dest and non-hex-id
  round-trip tests.

## B.2 DONE — New transports

- **`model/LocalNodeIdentity.kt`** (NEW) — one stable `!hex` node id per install (derived from ANDROID_ID /
  build fingerprint), injected into all transports.
- **`receiver/LoRaHubTransportAdapter.kt`** (NEW) — phone joins hub SoftAP via `WifiNetworkSpecifier`
  (SSID prefix `DTN-HUB-`, bound socket keeps cellular default); framed TCP :9740 with HELLO/REGISTER/BUNDLE;
  treats the hub as an encountered peer so the existing orchestrator forwards buffered bundles to it.
- **`receiver/BleTransportAdapter.kt`** (NEW) — symmetric GATT server + advertiser + scanner + client;
  identity in advertisement service data; `[2B len][wire]` chunked to MTU. Primary P2P path.

## B.3 DONE — Transport layer rewired, Meshtastic retired

- **`receiver/MultiTransportManager.kt`** — now aggregates **BLE + WiFi Direct + LoRa hub**; send priority
  **BLE → WiFi Direct → LoRa hub**; helper methods `connectHub()`, `connectBle()`, `connectWifiDirect()`.
- **`receiver/WifiDirectTransportAdapter.kt`** — injects `LocalNodeIdentity` (single shared identity);
  removed the `!p2p_<name>` fabricated ids everywhere; added the **HELLO handshake** so peers are keyed by
  their **real** node id ↔ IP; removed unused `Build` import.
- **`ui/DtnViewModel.kt`** — `connect()` text updated; methods renamed/added: `connectHubOnly()`,
  `connectBleOnly()`, `connectWifiDirectOnly()`.
- **`receiver/MeshtasticTransportAdapter.kt`** — reduced to a retirement note (no code).
- **`AndroidManifest.xml`** — removed the Meshtastic `<queries>`; added `CHANGE_NETWORK_STATE`, BLE
  permissions (`BLUETOOTH_ADVERTISE/SCAN/CONNECT` + legacy), and `uses-feature bluetooth_le`.
- `ui/MainScreen.kt` — unchanged (only uses `connect()`).

## B.4 DONE — Hub firmware

- **`firmware/platformio.ini`**, **`firmware/src/main.cpp`**, **`firmware/README.md`** (NEW) — Heltec WiFi
  LoRa 32 V3 (ESP32-S3 + SX1262), RadioLib. SoftAP + framed TCP + LoRa flood-with-dedup/TTL/hop-limit;
  sends HELLO on connect; **broadcast fan-out** to all connected phones for dest `0xFFFFFFFF`.

## B.5 DONE — Follow-ups

- Broadcast delivery end-to-end: reserved `0xFFFFFFFF` in `NodeId` + firmware `deliverLocal`/`routeBundle`
  fan-out and always-reflood for broadcasts.
- WiFi Direct real-identity handshake (see B.3).

## B.6 Design doc

- **`docs/dtn-hub-architecture.md`** — the original architecture proposal. NOTE: its firmware snippet
  predates the `HELLO` frame and the broadcast fan-out; the authoritative firmware is in `firmware/`.

---

# PART C — What Still Needs to Change (to fully realize the architecture)

Ordered roughly by priority.

### C.1 Cleanup / correctness
- **Delete dead Meshtastic files** (shell was unavailable, so left in place as compiling dead code):
  `app/src/main/aidl/org/meshtastic/**`, `app/src/main/kotlin/org/meshtastic/core/model/*.kt`,
  `app/src/main/kotlin/.../MeshtasticTransportAdapter.kt`. After deleting the AIDL, remove `aidl = true`
  from `app/build.gradle.kts` `buildFeatures`.
- **Run a full Gradle build** (`:app:assembleDebug`) — only unit tests have been run here.
- **Update `docs/dtn-hub-architecture.md`** firmware section to match `firmware/src/main.cpp`.

### C.2 Real-device validation (transports need hardware testing)
- **BLE** (`BleTransportAdapter`): validate MTU negotiation, chunk pacing/back-pressure (current writes are
  fire-and-forget — likely need `onCharacteristicWrite`-driven sequential pacing), reconnection, and the
  `writeCharacteristic` deprecation path on Android 13+ (new API overload).
- **LoRa hub** (`LoRaHubTransportAdapter`): test `WifiNetworkSpecifier` auto-join on API 29+, the legacy
  (<29) manual-join path, socket reconnection when the hub drops, and multi-phone concurrency on the ESP32
  SoftAP (~4–8 clients).
- **WiFi Direct**: verify the HELLO handshake bootstraps both directions and that client-to-client (not just
  client-to-owner) delivery works within a legacy P2P group.

### C.3 Features still missing for the full architecture
- **Foreground service** — the manifest has a commented-out placeholder. Store-carry-forward while the app
  is backgrounded/screen-off requires a real `foregroundServiceType="connectedDevice"` service hosting the
  orchestrator + transports. Currently everything is tied to the ViewModel/UI lifecycle.
- **Fragmentation** for payloads > 223 bytes (flagged as future in `queue/`; header already has
  fragmentIndex/Total fields, but no reassembly logic exists).
- **Runtime permission requests** — BLE + location + nearby-devices permissions are declared but there is no
  in-app runtime request flow (needed on Android 12+).
- **End-to-end delivery semantics** — BLE, WiFi Direct, and the LoRa hub all emit a synthetic `DELIVERED`
  on successful *handoff* (hop-level ack, not end-to-end). If true end-to-end confirmation is required, add
  an ack bundle that routes back to the origin.
- **Hub → phone id learning for routing** — the hub is treated as a single peer; it does not advertise which
  destination phones are reachable through it. Fine for flooding, but a "route hint" would reduce airtime.

### C.4 Firmware hardening (before scaling past a few hubs)
- **LoRa duty-cycle pacing** (regional legal limits) — none yet.
- **Over-the-air encryption** (AES) — none yet; WPA2 only protects the SoftAP link.
- Per-hub config: set unique `HUB_ID` + `AP_SSID` before flashing each device; set `LORA_FREQ` to the legal
  band (868 EU / 915 US / 433 many-Asia).
- Keep `HDR_MIN` and offsets in `main.cpp` in sync with any `DtnWireCodec` header change.

### C.5 Cosmetic / tech-debt
- Doc comments across `database/`, `queue/`, `MeshTransport.kt` still mention Meshtastic ACK / packet-id
  semantics — harmless, worth a cleanup pass.
- `MeshTransport` interface KDoc still describes the old Meshtastic contract; `mesh_packet_id` column name is
  now generic.
