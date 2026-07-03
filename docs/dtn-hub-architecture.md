# DTN Mesh Relay — Target Architecture (LoRa WiFi-AP Hubs)

**Status:** Design proposal · **Date:** 2026-07-02 · **Scope:** architecture only, no code changed yet
**Decision inputs:** Hub hardware = Heltec WiFi LoRa 32 V3 · Phone-to-phone P2P = BLE primary, WiFi Direct fallback

---

## 1. Why this document exists

The app was originally built around the idea that **each phone owns one LoRa radio**, driven through the
unmodified Meshtastic app over AIDL. That is *not* the target. The target is:

> A **LoRa device is a WiFi Access Point (hub)** that several phones connect to over WiFi at once.
> Hubs talk to each other over long-range LoRa RF. Phones also talk directly to each other over BLE /
> WiFi Direct. Messages survive lack of end-to-end connectivity via **store-carry-forward** and
> **data-mule** phones. The system is **independent of Meshtastic**, running custom firmware on the LoRa modules.

This document specifies that target architecture, the required changes, and the hub firmware.

---

## 2. Current vs. target

| Aspect | Current (Meshtastic-based) | Target (independent hubs) |
|---|---|---|
| LoRa access | 1 phone ⇄ 1 radio over BLE/serial, via Meshtastic app AIDL | Many phones ⇄ 1 hub over WiFi; hub ⇄ hub over LoRa |
| LoRa radio owner | Meshtastic app | Custom ESP32 firmware (this repo) |
| Phone↔LoRa link | `MeshtasticTransportAdapter` (AIDL + broadcasts) | `LoRaHubTransportAdapter` (WiFi STA → hub SoftAP, framed TCP) |
| Phone↔phone link | `WifiDirectTransportAdapter` | BLE primary + WiFi Direct fallback |
| Bundle envelope | src/dest carried by Meshtastic `DataPacket.from/to` | **Self-contained DTN header** (src/dest inside the bundle) |
| DTN intelligence | On the phone | On the phone (unchanged); LoRa backbone stays "dumb" |

**Key insight:** the DTN engine (`DtnOrchestrator`, PRoPHET/MaxProp, `DoubleQLearningEngine`,
`MessageQueueManager`, scheduler, DB, export, UI) only depends on the `MeshTransport` / `DtnTransport`
interfaces. It is fully transport-agnostic. **The pivot is localized to the transport layer.**

---

## 3. Roles

### 3.1 Phone (DTN node — the brain)
- Creates messages; runs the store-carry-forward buffer (`MessageQueueManager` + Room).
- Runs encounter-based routing: PRoPHET / MaxProp / Double Q-Learning.
- Connects opportunistically to:
  - a **LoRa hub** over WiFi (STA joins the hub's SoftAP) — new `LoRaHubTransportAdapter`;
  - **other phones** over BLE (primary) or WiFi Direct (fallback) — encounter-based P2P.
- Acts as a **data mule**: buffers others' messages and carries them between regions.

### 3.2 LoRa hub (ESP32 — the bridge + backbone)
- Runs a **WiFi SoftAP**; several phones join simultaneously.
- Runs a **framed TCP server** on port 9740 for phones to push/pull bundles.
- Keeps its **own bundle buffer** (LoRa is slow → must queue).
- Bridges WiFi ⇄ LoRa and forwards hub-to-hub using **controlled flooding**:
  message-ID **dedup**, **TTL**, and a **hop limit**. No Q-learning on the hub.

**Division of labor:** the phones are smart (adaptive routing on the volatile P2P/mule layer);
the LoRa backbone is simple and reliable (flood-with-dedup on the stable infrastructure layer).

---

## 4. Topology & message flows

```
   REGION A (Hub A = ESP32 SoftAP)              REGION B (Hub B = ESP32 SoftAP)
   SSID "DTN-HUB-A", 192.168.4.1                SSID "DTN-HUB-B", 192.168.4.1
        ^   ^   ^  (WiFi STA join,                     ^   ^   ^
        |   |   |   framed TCP :9740)                   |   |   |
      A1  A2  A3                                       B1  B2  B3
        \__ BLE / WiFi-Direct __/                        \__ BLE / WiFi-Direct __/
                     |                                          ^
                     \___ phone A2 physically carries __________/  (data mule)

        Hub A  <========== long-range LoRa RF (SX1262) ==========>  Hub B
                controlled flooding + msg-ID dedup + TTL + hop-limit
```

1. **Phone → Hub:** phone opens TCP to hub, sends `REGISTER` (announces its nodeId) then `BUNDLE` frames.
2. **Hub buffering + dedup:** hub records the message-ID; if the destination phone is connected locally,
   it delivers over WiFi immediately; otherwise it queues the bundle for LoRa.
3. **Hub ⇄ Hub (LoRa):** hub transmits queued bundles; receiving hubs dedup, deliver to any locally
   connected destination phone, and re-flood if hops/TTL remain.
4. **Hub → Phone:** when the destination phone is connected, the hub pushes buffered bundles to it.
5. **No hub present:** phones still exchange bundles directly over BLE / WiFi Direct (encounter-based),
   and mules carry them between regions. Long-haul is the LoRa backbone; local is BLE/WiFi Direct.

---

## 5. Self-contained bundle wire format (REQUIRED CHANGE)

Today `DtnWireCodec` uses a 24-byte header and relies on Meshtastic's `DataPacket.from/to` for addressing.
Independent hub-to-hub LoRa frames have **no outer envelope**, so origin and destination must move **into**
the header. Proposed 32-byte header (big-endian):

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0  | 16 | `messageId` | UUID; used for dedup on hubs |
| 16 | 4  | `originNodeId` | uint32 (was in `DataPacket.from`) |
| 20 | 4  | `destinationNodeId` | uint32 (was in `DataPacket.to`) |
| 24 | 2  | `ttlMinutes` | uint16, counts down |
| 26 | 1  | `hopCount` | incremented per LoRa re-flood |
| 27 | 1  | `messageType` | `DtnMessageType.wireValue` |
| 28 | 1  | `channel` | 0 = primary |
| 29 | 1  | `fragmentIndex` | 0 = single frame |
| 30 | 1  | `fragmentTotal` | 1 = no fragmentation |
| 31 | 1  | `reserved` | 0x00 |

**Payload budget:** SX126x max single LoRa frame = 255 bytes → usable payload = 255 − 32 = **223 bytes**.
Keep bundles ≲ 200 bytes to limit airtime; fragment above that. Node IDs are now **app-generated 32-bit**
values (no longer Meshtastic `!hex`), but `NodeId` can keep its string form and map to/from uint32.

Impacted files: `model/DtnMessage.kt`, `receiver/DtnWireCodec.kt`, `test/.../DtnWireCodecTest.kt`.

---

## 6. Independence from Meshtastic — exact scope

**Remove / retire:** `MeshtasticTransportAdapter`, `app/src/main/aidl/org/meshtastic/**`,
`org.meshtastic.core.model.*` Parcelables, Meshtastic broadcast wiring. Keep `docs/meshtastic-ref/` only as
historical reference.

**Add:** `LoRaHubTransportAdapter : DtnTransport` — connects phone (WiFi STA) to hub SoftAP, speaks the
framed TCP protocol below. Structurally mirrors the existing `WifiDirectTransportAdapter` (length-prefixed
TCP framing over sockets). Also a `BleTransportAdapter : DtnTransport` for P2P (see §8).

**Unchanged (transport-agnostic):** `service/DtnOrchestrator`, `queue/`, `routing/` (PRoPHET, MaxProp,
StrategySelector), `learning/` (Double Q-Learning), `scheduler/`, `database/`, `export/`, UI.
`MultiTransportManager` already merges multiple transports — just register the new adapters.

---

## 7. Phone ⇄ Hub protocol (framed TCP, port 9740)

Frame: `[1B type][2B length BE][payload...]`

| Type | Value | Payload | Meaning |
|---|---|---|---|
| `REGISTER` | 0x01 | 4B nodeId | Phone announces its id so the hub can map nodeId → socket for direct delivery |
| `BUNDLE` | 0x02 | DTN bundle (32B header + payload) | A DTN message in either direction |

- Phone sends `REGISTER` immediately after connecting, then `BUNDLE`s for anything it wants to inject.
- Hub sends `BUNDLE`s to a phone when a buffered bundle's destination matches that phone's registered id.
- Delivery over this reliable TCP link is treated as success (mirrors the WiFi Direct adapter's model).

---

## 8. Phone ⇄ Phone (BLE primary, WiFi Direct fallback)

**Why BLE primary:** while a phone is joined to a hub's SoftAP (WiFi STA), running WiFi Direct
concurrently is unreliable on many Android devices. BLE coexists cleanly with WiFi STA, so BLE is the
default P2P path when attached to a hub; WiFi Direct is used when no hub is present (higher throughput).

**Planned `BleTransportAdapter : DtnTransport`:**
- Each phone runs a **GATT server** advertising a DTN service UUID + a "bundle" characteristic, and also
  **scans** for that UUID to connect as a GATT client — symmetric, encounter-based.
- On connect: emit a `NodeEncounterEvent` (drives the orchestrator's forwarding evaluation, exactly like
  today). Exchange bundles by writing the DTN wire bytes to the characteristic (chunked to the MTU, ~185–244B).
- `MultiTransportManager.sendMessage` picks per-peer: BLE if the peer is a BLE neighbor, else WiFi Direct,
  else the LoRa hub. (The current WiFi-Direct-then-LoRa priority generalizes to BLE → WiFi Direct → LoRa.)

BLE's ~10 m range and small MTU suit short, opportunistic encounters; WiFi Direct's ~100 m / higher
throughput suits bulk sync when a phone is not tied to a hub.

---

## 9. Hub firmware (Heltec WiFi LoRa 32 V3 — ESP32-S3 + SX1262)

PlatformIO + Arduino + RadioLib. One build per hub; change `HUB_ID` and `AP_SSID` per device.

### 9.1 `platformio.ini`
```ini
[env:heltec_wifi_lora_32_V3]
platform = espressif32
board = heltec_wifi_lora_32_V3
framework = arduino
lib_deps = jgromes/RadioLib@^6.6.0
monitor_speed = 115200
```

### 9.2 `src/main.cpp`
```cpp
// ============================================================================
//  DTN LoRa Hub — Heltec WiFi LoRa 32 V3 (ESP32-S3 + SX1262)
//  WiFi SoftAP for many phones  +  LoRa hub-to-hub backbone
//  Arduino / PlatformIO + RadioLib. Starting reference, not hardened firmware.
// ============================================================================
#include <WiFi.h>
#include <RadioLib.h>

// ---- Per-hub identity (CHANGE PER DEVICE) ----------------------------------
static const uint32_t HUB_ID   = 0xA0000001;      // unique 32-bit hub node id
static const char*     AP_SSID = "DTN-HUB-A";     // "DTN-HUB-B" on the other hub
static const char*     AP_PASS = "dtnmesh123";    // >= 8 chars
static const uint8_t   MAX_CLIENTS = 6;           // ESP32 SoftAP: keep <= 8

// ---- SX1262 pins for Heltec WiFi LoRa 32 V3 --------------------------------
SX1262 radio = new Module(8 /*NSS*/, 14 /*DIO1*/, 12 /*RST*/, 13 /*BUSY*/);
static const float   LORA_FREQ = 868.0;  // 915.0 US / 433.0 many-Asia — MATCH LAW
static const float   LORA_BW   = 125.0;
static const uint8_t LORA_SF   = 9;      // higher SF = longer range, slower
static const uint8_t LORA_CR   = 5;

WiFiServer tcpServer(9740);
struct Client { WiFiClient sock; uint32_t nodeId; bool used; };
Client clients[MAX_CLIENTS];

enum : uint8_t { FRAME_REGISTER = 0x01, FRAME_BUNDLE = 0x02 };

// DTN self-contained header (must match Android DtnWireCodec, §5)
static const int HDR_MIN=32, OFF_MSGID=0, OFF_ORIGIN=16, OFF_DEST=20,
                 OFF_TTL_MIN=24, OFF_HOP=26;

static const int DEDUP_N=128; uint8_t dedupKey[DEDUP_N][16]; int dedupHead=0;
struct Bundle { uint8_t* data; uint16_t len; };
static const int QN=48; Bundle txQueue[QN]; int qHead=0, qTail=0;

uint32_t rd32(const uint8_t*p){return (p[0]<<24)|(p[1]<<16)|(p[2]<<8)|p[3];}
uint16_t rd16(const uint8_t*p){return (p[0]<<8)|p[1];}

bool seenBefore(const uint8_t*id){
  for(int i=0;i<DEDUP_N;i++) if(memcmp(dedupKey[i],id,16)==0) return true;
  memcpy(dedupKey[dedupHead],id,16); dedupHead=(dedupHead+1)%DEDUP_N; return false;
}
bool qPush(const uint8_t*data,uint16_t len){
  int nxt=(qTail+1)%QN; if(nxt==qHead) return false;      // full -> drop
  txQueue[qTail].data=(uint8_t*)malloc(len); if(!txQueue[qTail].data) return false;
  memcpy(txQueue[qTail].data,data,len); txQueue[qTail].len=len; qTail=nxt; return true;
}
bool deliverLocal(uint32_t dest,const uint8_t*data,uint16_t len){
  for(int i=0;i<MAX_CLIENTS;i++)
    if(clients[i].used&&clients[i].nodeId==dest&&clients[i].sock.connected()){
      uint8_t h[3]={FRAME_BUNDLE,(uint8_t)(len>>8),(uint8_t)(len&0xFF)};
      clients[i].sock.write(h,3); clients[i].sock.write(data,len); return true;
    }
  return false;
}
void routeBundle(const uint8_t*data,uint16_t len,bool fromLoRa){
  if(len<HDR_MIN) return;
  if(seenBefore(data+OFF_MSGID)) return;                  // dedup
  if(rd16(data+OFF_TTL_MIN)==0) return;                   // expired
  uint8_t hop=data[OFF_HOP]; uint32_t dest=rd32(data+OFF_DEST);
  bool delivered=deliverLocal(dest,data,len);
  if(!fromLoRa){ qPush(data,len); }                       // from phone -> backbone
  else if(!delivered && hop<5){                           // from LoRa -> re-flood
    uint8_t*c=(uint8_t*)malloc(len);
    if(c){ memcpy(c,data,len); c[OFF_HOP]=hop+1; qPush(c,len); free(c); }
  }
}
void readClient(Client&c){
  while(c.sock.available()>=3){
    uint8_t h[3]; c.sock.readBytes(h,3);
    uint8_t type=h[0]; uint16_t len=(h[1]<<8)|h[2];
    if(len==0||len>512){ c.sock.stop(); c.used=false; return; }
    uint8_t buf[512]; int got=0;
    while(got<len&&c.sock.connected()) got+=c.sock.readBytes(buf+got,len-got);
    if(type==FRAME_REGISTER&&len>=4) c.nodeId=rd32(buf);
    else if(type==FRAME_BUNDLE) routeBundle(buf,len,false);
  }
}
void acceptAndPoll(){
  WiFiClient nc=tcpServer.available();
  if(nc) for(int i=0;i<MAX_CLIENTS;i++) if(!clients[i].used){clients[i]={nc,0,true};break;}
  for(int i=0;i<MAX_CLIENTS;i++) if(clients[i].used){
    if(!clients[i].sock.connected()){clients[i].sock.stop();clients[i].used=false;}
    else readClient(clients[i]);
  }
}
volatile bool loraRxFlag=false;
void IRAM_ATTR onLoRaRx(){ loraRxFlag=true; }
void pumpLoRaTx(){
  if(qHead==qTail) return; Bundle&b=txQueue[qHead];
  if(radio.transmit(b.data,b.len)==RADIOLIB_ERR_NONE){ free(b.data); qHead=(qHead+1)%QN; }
  radio.startReceive();
}
void pumpLoRaRx(){
  if(!loraRxFlag) return; loraRxFlag=false;
  uint8_t buf[256]; int len=radio.getPacketLength();
  if(len>0&&radio.readData(buf,len)==RADIOLIB_ERR_NONE) routeBundle(buf,len,true);
  radio.startReceive();
}
void setup(){
  Serial.begin(115200);
  WiFi.mode(WIFI_AP); WiFi.softAP(AP_SSID,AP_PASS,1,0,MAX_CLIENTS); tcpServer.begin();
  Serial.printf("SoftAP %s at %s\n",AP_SSID,WiFi.softAPIP().toString().c_str());
  int st=radio.begin(LORA_FREQ,LORA_BW,LORA_SF,LORA_CR);
  if(st!=RADIOLIB_ERR_NONE){ Serial.printf("LoRa init fail %d\n",st); while(true) delay(1000);}
  radio.setDio1Action(onLoRaRx); radio.startReceive();
}
void loop(){ acceptAndPoll(); pumpLoRaRx(); pumpLoRaTx(); delay(2); }
```

### 9.3 Firmware caveats before relying on it
- Set `LORA_FREQ` to your **legally allowed band** and respect regional **duty-cycle** limits. The current
  flooding has no duty-cycle pacer — fine for 2–4 hubs, add pacing before scaling.
- Header offsets **must** match the finalized `DtnWireCodec` (§5).
- ESP32 SoftAP reliably serves ~4 (up to ~8) concurrent clients — matches ~3 phones/region.
- No LoRa encryption here; add AES if confidentiality is required over the air.

---

## 10. Implementation plan (when approved — not started)

1. Extend wire header with origin + destination (`DtnMessage`, `DtnWireCodec`, update `DtnWireCodecTest`).
2. Add `LoRaHubTransportAdapter : DtnTransport` (WiFi STA → SoftAP, framed TCP :9740, REGISTER + BUNDLE).
3. Add `BleTransportAdapter : DtnTransport` (GATT server + scanner, encounter-based).
4. Update `TransportModule` / `MultiTransportManager`: register LoRa-hub + BLE + WiFi-Direct; retire Meshtastic AIDL path; set send priority BLE → WiFi Direct → LoRa hub.
5. Finalize + flash firmware (`platformio.ini` + `src/main.cpp`), one per hub with unique `HUB_ID`/`AP_SSID`.

Steps 1–4 reuse patterns already in `WifiDirectTransportAdapter`; the DTN engine is untouched.

---

## 11. Constraints & open risks

- **WiFi STA + WiFi Direct concurrency** is device-dependent → mitigated by BLE-primary P2P.
- **LoRa airtime / duty cycle** limits throughput; keep bundles small, add pacing.
- **SoftAP client cap** (~4–8) bounds phones-per-hub.
- **Flooding scalability**: dedup + hop-limit are enough for a few hubs; a smarter hub routing scheme
  would be needed for many hubs.
- **Security**: WiFi WPA2 on the SoftAP and BLE pairing cover the local links; add payload/LoRa encryption
  if end-to-end confidentiality is required.
```
