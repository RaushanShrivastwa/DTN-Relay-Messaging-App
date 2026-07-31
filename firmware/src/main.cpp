// ============================================================================
//  DTN LoRa Hub — Heltec WiFi LoRa 32 V3 (ESP32-S3 + SX1262)
//  Role: WiFi SoftAP for many phones  +  LoRa hub-to-hub backbone.
//  Framework: Arduino / PlatformIO   Library: RadioLib
//
//  Protocol (must match the Android app — unchanged in this revision):
//   * Framed TCP on port 9740:  [1B type][2B length BE][payload]
//       - HELLO    0x03  hub -> phone   payload = 4B HUB_ID (sent on connect)
//       - REGISTER 0x01  phone -> hub   payload = 4B phone node id
//       - BUNDLE   0x02  either way     payload = DTN bundle (32B header + data)
//   * Self-contained 32-byte DTN header (see DtnWireCodec.kt):
//       0:16 msgId  16:4 origin  20:4 dest  24:2 ttlMin  26:1 hop
//       27:1 msgType  28:1 channel  29:1 fragIdx  30:1 fragTotal  31:1 reserved
//     msgType values (app enum DtnMessageType, wire values):
//       0x00 DATA, 0x01 ROUTING_SUMMARY, 0x02 ROUTING_ACK,
//       0x03 BUNDLE_OFFER, 0x04 BUNDLE_REQUEST
//
//  New in this revision (all additive — wire protocol is untouched):
//   * Real buffer with per-msg metadata (msgId, origin, dest, hop, ttl,
//     bufferedAt, waitingForAck) — replaces the raw byte queue.
//   * ACK-driven immediate buffer cleanup by parsing ROUTING_ACK payloads
//     (which are already concatenated 16-byte UUIDs — see the app's
//     DeliveryReceiptStore / DtnOrchestrator.processInboundReceipts).
//   * ACK-dedup ring so a re-flooded ACK doesn't repeat cleanup work.
//   * Heartbeat (empty ROUTING_SUMMARY with our HUB_ID as origin) so peer
//     hubs learn we exist. The app already ignores empty summaries.
//   * Per-peer last-seen tracking → live ACTIVE/INACTIVE table.
//   * Log-distance RSSI→meters estimate (matches the phone-side estimator).
//   * Serial monitor dashboards every DISTANCE_UPDATE_INTERVAL_MS:
//       - Distance table (peer node id + estimated metres + status)
//       - Buffer state (all currently-buffered bundles)
//       - Runtime stats (counters + peak buffer)
//   * Detailed message-lifecycle logging on Serial.
//
//  Not done here (intentionally — would require breaking the fixed header):
//   * Appending a per-hop identity list to bundles for full cross-hub route
//     display on the phone UI. The hub still logs its own view of each hop
//     locally to the serial monitor.
// ============================================================================
#include <WiFi.h>
#include <RadioLib.h>
#include <string.h>
#include <math.h>
// esp_random() ships with the ESP-IDF that Arduino-ESP32 wraps; header location
// has moved between versions, so declare the symbol directly to avoid tying this
// file to a specific SDK layout.
extern "C" uint32_t esp_random(void);

// ---- Per-hub identity (CHANGE PER DEVICE) ----------------------------------
static const uint32_t HUB_ID   = 0xA0000001;      // unique 32-bit hub node id
static const char*     AP_SSID = "DTN-HUB-A";     // "DTN-HUB-B" on the other hub
static const char*     AP_PASS = "dtnmesh123";    // must match app HUB_PASSPHRASE (>= 8 chars)
static const uint8_t   MAX_CLIENTS = 6;           // ESP32 SoftAP: keep <= 8

// ---- SX1262 pins for Heltec WiFi LoRa 32 V3 --------------------------------
SX1262 radio = new Module(8 /*NSS*/, 14 /*DIO1*/, 12 /*RST*/, 13 /*BUSY*/);
static const float   LORA_FREQ = 868.0;  // 915.0 US / 433.0 many-Asia — MATCH LAW
static const float   LORA_BW   = 125.0;
static const uint8_t LORA_SF   = 9;
static const uint8_t LORA_CR   = 5;

// ---- Tunables --------------------------------------------------------------
// Dashboard refresh cadence. Serial is expensive at 115200 baud, so keep this
// sane; every 5 s is a good compromise for a hub with a few peers.
static const uint32_t DISTANCE_UPDATE_INTERVAL_MS = 5000;
// How often each hub broadcasts a keep-alive heartbeat over LoRa.
static const uint32_t HEARTBEAT_INTERVAL_MS      = 5000;
// A peer that hasn't been heard from within this window is marked INACTIVE.
static const uint32_t NODE_TIMEOUT_MS            = 15000;
// Bundles buffered for longer than this are TTL-expired locally. (The header's
// ttlMinutes is authoritative for the mesh; this is a floor for hub-side eviction.)
static const uint32_t BUFFER_TTL_FLOOR_MS        = 15UL * 60UL * 1000UL;

// ---- TCP server + connected phones -----------------------------------------
WiFiServer tcpServer(9740);
struct DtnClient {
    WiFiClient sock;
    uint32_t   nodeId;
    bool       used;
    bool       greeted;
    uint32_t   lastSeenMs;
};
DtnClient clients[MAX_CLIENTS];

enum : uint8_t { FRAME_REGISTER = 0x01, FRAME_BUNDLE = 0x02, FRAME_HELLO = 0x03 };

// DTN self-contained header offsets & message types
static const int      HDR_MIN         = 32;
static const int      OFF_MSGID       = 0;
static const int      OFF_ORIGIN      = 16;
static const int      OFF_DEST        = 20;
static const int      OFF_TTL_MIN     = 24;
static const int      OFF_HOP         = 26;
static const int      OFF_MSGTYPE     = 27;
static const uint8_t  MSGTYPE_DATA            = 0x00;
static const uint8_t  MSGTYPE_ROUTING_SUMMARY = 0x01;
static const uint8_t  MSGTYPE_ROUTING_ACK     = 0x02;

// Reserved destination meaning "deliver to every node" (matches NodeId.BROADCAST_NODE_NUM32).
static const uint32_t BROADCAST_DEST = 0xFFFFFFFFu;
// Hop count at which we stop re-flooding — protects the LoRa band from runaway packets.
static const uint8_t  HOP_LIMIT      = 5;

// ---- Dedup caches ----------------------------------------------------------
// Two rings: one for recently-seen bundle IDs (any msgType), one for ACKed msg IDs
// (so an ACK that has already caused cleanup here doesn't fire again on the
// re-flooded copy that eventually loops back).
static const int DEDUP_N = 128;
uint8_t  dedupKey[DEDUP_N][16];
int      dedupHead = 0;
uint8_t  ackDedupKey[DEDUP_N][16];
int      ackDedupHead = 0;

// ---- Buffer with per-message metadata --------------------------------------
// Every bundle we intend to forward over LoRa is kept here until an ACK clears it
// (or the hop / TTL / capacity guards evict it). Indexed by msgId — findInBuffer()
// is O(N) with N ≤ BUFFER_CAPACITY. Storage of the full wire bytes lets us
// re-transmit intact without re-parsing the header.
struct BufferEntry {
    bool     valid;
    uint8_t  data[256];       // wire bundle (header + payload)
    uint16_t len;
    uint8_t  msgId[16];
    uint32_t origin;
    uint32_t dest;
    uint8_t  hopCount;
    uint16_t ttlMin;          // as observed at insert time
    uint8_t  msgType;
    uint32_t bufferedAtMs;
    bool     waitingForAck;   // true for unicast DATA still awaiting an ACK
};
static const int BUFFER_CAPACITY = 32;
BufferEntry buffer[BUFFER_CAPACITY];
uint32_t peakBufferUsage = 0;

// ---- Known peers (other hubs learned from LoRa headers + heartbeats) -------
struct KnownPeer {
    bool     valid;
    uint32_t nodeId;
    int      lastRssi;
    uint32_t lastSeenMs;
    bool     active;
};
static const int MAX_KNOWN_PEERS = 8;
KnownPeer knownPeers[MAX_KNOWN_PEERS];

// ---- Runtime statistics ----------------------------------------------------
struct Stats {
    uint32_t msgsGenerated;      // hub-originated (heartbeats)
    uint32_t msgsForwarded;      // pushed to the LoRa TX queue
    uint32_t msgsDelivered;      // handed to a local phone
    uint32_t acksGenerated;      // hub-originated ACKs (0 — hubs never mint them)
    uint32_t acksReceived;       // ROUTING_ACK bundles processed
    uint32_t duplicatesIgnored;  // bundles matched by the primary dedup ring
    uint32_t duplicateAcksIgnored;
    uint32_t ttlExpired;         // dropped because ttlMinutes == 0
    uint32_t hopDropped;         // dropped because hop ≥ HOP_LIMIT
    uint32_t bufferedNow;
    uint32_t bufferInserts;
    uint32_t bufferEvictions;
};
Stats stats = {};

// ---- LoRa RX ISR flag ------------------------------------------------------
volatile bool loraRxFlag = false;
void IRAM_ATTR onLoRaRx() { loraRxFlag = true; }

// ============================================================================
//  Byte helpers
// ============================================================================
uint32_t rd32(const uint8_t* p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}
uint16_t rd16(const uint8_t* p) { return ((uint16_t)p[0] << 8) | p[1]; }

void wr32(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24); p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);  p[3] = (uint8_t)v;
}

// Short printable form of a 16-byte msg id — first 8 hex chars for logging.
void msgIdShort(const uint8_t* id, char out[9]) {
    static const char hx[] = "0123456789abcdef";
    for (int i = 0; i < 4; i++) {
        out[i * 2 + 0] = hx[(id[i] >> 4) & 0xF];
        out[i * 2 + 1] = hx[id[i] & 0xF];
    }
    out[8] = 0;
}

// Format a 32-bit node id as "!xxxxxxxx" to match the app's NodeId convention.
void formatNodeId(uint32_t v, char out[10]) {
    static const char hx[] = "0123456789abcdef";
    out[0] = '!';
    for (int i = 0; i < 8; i++) out[1 + i] = hx[(v >> ((7 - i) * 4)) & 0xF];
    out[9] = 0;
}

// ============================================================================
//  Dedup rings
// ============================================================================
bool seenBefore(const uint8_t* id) {
    for (int i = 0; i < DEDUP_N; i++)
        if (memcmp(dedupKey[i], id, 16) == 0) return true;
    memcpy(dedupKey[dedupHead], id, 16);
    dedupHead = (dedupHead + 1) % DEDUP_N;
    return false;
}

bool ackSeenBefore(const uint8_t* id) {
    for (int i = 0; i < DEDUP_N; i++)
        if (memcmp(ackDedupKey[i], id, 16) == 0) return true;
    memcpy(ackDedupKey[ackDedupHead], id, 16);
    ackDedupHead = (ackDedupHead + 1) % DEDUP_N;
    return false;
}

// ============================================================================
//  Buffer management
// ============================================================================
int findInBuffer(const uint8_t* msgId) {
    for (int i = 0; i < BUFFER_CAPACITY; i++)
        if (buffer[i].valid && memcmp(buffer[i].msgId, msgId, 16) == 0) return i;
    return -1;
}

int findFreeSlot() {
    for (int i = 0; i < BUFFER_CAPACITY; i++)
        if (!buffer[i].valid) return i;
    return -1;
}

// Evict the oldest entry (by bufferedAtMs) — used only when the buffer is at
// capacity and a new bundle arrives. TTL floor also evicts periodically.
int evictOldest() {
    int oldest = -1;
    uint32_t oldestAt = 0xFFFFFFFF;
    for (int i = 0; i < BUFFER_CAPACITY; i++) {
        if (buffer[i].valid && buffer[i].bufferedAtMs < oldestAt) {
            oldest = i;
            oldestAt = buffer[i].bufferedAtMs;
        }
    }
    if (oldest >= 0) {
        buffer[oldest].valid = false;
        stats.bufferEvictions++;
        if (stats.bufferedNow > 0) stats.bufferedNow--;
    }
    return oldest;
}

void updatePeak() {
    if (stats.bufferedNow > peakBufferUsage) peakBufferUsage = stats.bufferedNow;
}

// Insert or refresh a bundle in the buffer. Returns true if a NEW entry was created.
// If the msgId is already present, the existing entry is left untouched (single valid
// copy per node, as required) and this returns false.
bool bufferInsert(const uint8_t* data, uint16_t len) {
    if (len < HDR_MIN || len > 256) return false;

    if (findInBuffer(data + OFF_MSGID) >= 0) return false; // already buffered

    int slot = findFreeSlot();
    if (slot < 0) slot = evictOldest();
    if (slot < 0) return false;

    BufferEntry& e = buffer[slot];
    memcpy(e.data, data, len);
    e.len = len;
    memcpy(e.msgId, data + OFF_MSGID, 16);
    e.origin      = rd32(data + OFF_ORIGIN);
    e.dest        = rd32(data + OFF_DEST);
    e.ttlMin      = rd16(data + OFF_TTL_MIN);
    e.hopCount    = data[OFF_HOP];
    e.msgType     = data[OFF_MSGTYPE];
    e.bufferedAtMs = millis();
    e.waitingForAck = (e.msgType == MSGTYPE_DATA && e.dest != BROADCAST_DEST);
    e.valid = true;

    stats.bufferedNow++;
    stats.bufferInserts++;
    updatePeak();
    return true;
}

// Remove a bundle by msgId. Returns true if an entry was actually removed.
bool bufferRemove(const uint8_t* msgId) {
    int idx = findInBuffer(msgId);
    if (idx < 0) return false;
    buffer[idx].valid = false;
    if (stats.bufferedNow > 0) stats.bufferedNow--;
    return true;
}

// Sweep out entries whose observed TTL has aged past our local floor.
void bufferSweepExpired() {
    uint32_t now = millis();
    for (int i = 0; i < BUFFER_CAPACITY; i++) {
        if (!buffer[i].valid) continue;
        if (now - buffer[i].bufferedAtMs > BUFFER_TTL_FLOOR_MS) {
            char idStr[9]; msgIdShort(buffer[i].msgId, idStr);
            char origStr[10]; formatNodeId(buffer[i].origin, origStr);
            char destStr[10]; formatNodeId(buffer[i].dest, destStr);
            Serial.printf("[LC] Expired         msg=%s origin=%s dest=%s hop=%u\n",
                          idStr, origStr, destStr, buffer[i].hopCount);
            buffer[i].valid = false;
            if (stats.bufferedNow > 0) stats.bufferedNow--;
            stats.ttlExpired++;
            stats.bufferEvictions++;
        }
    }
}

// ============================================================================
//  Peer tracking (other hubs and phones we've heard from)
// ============================================================================
void touchPeer(uint32_t nodeId, int rssi) {
    if (nodeId == HUB_ID) return; // never track ourselves as a remote peer
    // Refresh if known.
    for (int i = 0; i < MAX_KNOWN_PEERS; i++) {
        if (knownPeers[i].valid && knownPeers[i].nodeId == nodeId) {
            knownPeers[i].lastRssi   = rssi;
            knownPeers[i].lastSeenMs = millis();
            knownPeers[i].active     = true;
            return;
        }
    }
    // Insert new — evict the stalest slot if we're full.
    int slot = -1;
    for (int i = 0; i < MAX_KNOWN_PEERS; i++) {
        if (!knownPeers[i].valid) { slot = i; break; }
    }
    if (slot < 0) {
        uint32_t oldestAt = 0xFFFFFFFF; int oldest = 0;
        for (int i = 0; i < MAX_KNOWN_PEERS; i++)
            if (knownPeers[i].lastSeenMs < oldestAt) { oldestAt = knownPeers[i].lastSeenMs; oldest = i; }
        slot = oldest;
    }
    knownPeers[slot].valid      = true;
    knownPeers[slot].nodeId     = nodeId;
    knownPeers[slot].lastRssi   = rssi;
    knownPeers[slot].lastSeenMs = millis();
    knownPeers[slot].active     = true;
}

// Refresh ACTIVE/INACTIVE flags before we print the dashboard.
void refreshPeerActivity() {
    uint32_t now = millis();
    for (int i = 0; i < MAX_KNOWN_PEERS; i++) {
        if (!knownPeers[i].valid) continue;
        knownPeers[i].active = (now - knownPeers[i].lastSeenMs) < NODE_TIMEOUT_MS;
    }
}

// Log-distance RSSI→metres estimate. Same formula as the phone-side
// RadioDistanceEstimator (Transport.LORA calibration).
double estimateDistanceM(int rssi) {
    if (rssi == 0) return -1.0; // unknown
    // LoRa calibration: txPowerAt1m = -40 dBm, pathLossExponent = 3.0.
    // d = 10^((TxPower_ref - RSSI) / (10 * n))
    int clamped = rssi;
    if (clamped < -120) clamped = -120;
    if (clamped > -20)  clamped = -20;
    double d = pow(10.0, ((-40.0) - (double)clamped) / 30.0);
    if (!isfinite(d)) return -1.0;
    if (d < 0.5)      d = 0.5;
    if (d > 500.0)    d = 500.0;
    return d;
}

// ============================================================================
//  Lifecycle logging
// ============================================================================
void logLifecycle(const char* event, const uint8_t* msgId,
                  uint32_t origin, uint32_t dest, uint8_t hop, uint16_t ttlMin) {
    char idStr[9]; msgIdShort(msgId, idStr);
    char origStr[10]; formatNodeId(origin, origStr);
    char destStr[10]; formatNodeId(dest,   destStr);
    Serial.printf("[LC] %-15s msg=%s origin=%s dest=%s hop=%u ttlMin=%u t=%lu\n",
                  event, idStr, origStr, destStr, hop, ttlMin, (unsigned long)millis());
}

// ============================================================================
//  TX queue → LoRa
// ============================================================================
// We keep a lightweight ring of raw wire bundles queued for TX. The full metadata
// lives in the main buffer; this ring holds pointers-into-buffer so a single physical
// copy is stored.
struct TxItem { int bufferIndex; uint32_t enqueuedAtMs; };
static const int TX_QUEUE_N = BUFFER_CAPACITY;
TxItem txQueue[TX_QUEUE_N];
int txHead = 0, txTail = 0;

bool txEnqueue(int bufferIndex) {
    int nxt = (txTail + 1) % TX_QUEUE_N;
    if (nxt == txHead) return false; // full
    txQueue[txTail].bufferIndex = bufferIndex;
    txQueue[txTail].enqueuedAtMs = millis();
    txTail = nxt;
    return true;
}

// Enqueue a fresh copy of a bundle with hopCount bumped by 1. Used when we relay a
// LoRa-inbound bundle further; the original stays in our buffer with its own hop
// count so a subsequent ACK can still find it.
bool txEnqueueForwardCopy(const uint8_t* data, uint16_t len) {
    // We need a mutable copy with hop+1. Use the buffer as storage.
    if (len < HDR_MIN) return false;
    // If the incremented hop matches or exceeds the limit, don't bother.
    uint8_t nextHop = (uint8_t)(data[OFF_HOP] + 1);
    if (nextHop >= HOP_LIMIT) { stats.hopDropped++; return false; }
    // Store the forward-copy under a synthetic distinct msgId? No — must keep the
    // same msgId so end-to-end dedup works. Instead, we key the buffer by msgId
    // and use the copy's live bytes for TX. Since a forward-copy of a bundle we
    // already relay-buffered would collide, we just refresh the existing entry.
    int idx = findInBuffer(data + OFF_MSGID);
    if (idx < 0) {
        // Not yet in buffer — insert then modify. bufferInsert copies raw bytes.
        if (!bufferInsert(data, len)) return false;
        idx = findInBuffer(data + OFF_MSGID);
        if (idx < 0) return false;
    }
    buffer[idx].data[OFF_HOP] = nextHop;
    buffer[idx].hopCount = nextHop;
    return txEnqueue(idx);
}

// ============================================================================
//  Framing to phones
// ============================================================================
void sendFrame(WiFiClient& sock, uint8_t type, const uint8_t* payload, uint16_t len) {
    uint8_t hdr[3] = { type, (uint8_t)(len >> 8), (uint8_t)(len & 0xFF) };
    sock.write(hdr, 3);
    if (len) sock.write(payload, len);
}

// Deliver a bundle to locally-connected phone(s). Fan-out for broadcast; unicast
// only to the phone whose registered nodeId matches dest.
bool deliverLocal(uint32_t dest, const uint8_t* data, uint16_t len) {
    if (dest == BROADCAST_DEST) {
        bool any = false;
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (clients[i].used && clients[i].sock.connected()) {
                sendFrame(clients[i].sock, FRAME_BUNDLE, data, len);
                clients[i].lastSeenMs = millis();
                any = true;
            }
        }
        return any;
    }
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i].used && clients[i].nodeId == dest && clients[i].sock.connected()) {
            sendFrame(clients[i].sock, FRAME_BUNDLE, data, len);
            clients[i].lastSeenMs = millis();
            return true;
        }
    }
    return false;
}

// ============================================================================
//  ACK handling
// ============================================================================
// A ROUTING_ACK bundle's payload is a concatenation of 16-byte UUIDs — one for each
// message the receipt covers. We iterate the payload, drop matching entries from
// our buffer, and update stats. The bundle itself keeps propagating via the normal
// re-flood path so peer hubs also see the receipt.
void processInboundAck(const uint8_t* data, uint16_t len) {
    if (len < HDR_MIN) return;
    const uint8_t* payload = data + HDR_MIN;
    uint16_t plen = len - HDR_MIN;
    int count = plen / 16;
    if (count == 0) return;

    stats.acksReceived++;
    int cleared = 0;
    for (int i = 0; i < count; i++) {
        const uint8_t* mid = payload + i * 16;
        if (ackSeenBefore(mid)) { stats.duplicateAcksIgnored++; continue; }
        char idStr[9]; msgIdShort(mid, idStr);
        // Locate and drop from buffer if we were still carrying a copy.
        int bufIdx = findInBuffer(mid);
        if (bufIdx >= 0) {
            char origStr[10]; formatNodeId(buffer[bufIdx].origin, origStr);
            char destStr[10]; formatNodeId(buffer[bufIdx].dest,   destStr);
            Serial.printf("[LC] AckReceived     msg=%s origin=%s dest=%s hop=%u\n",
                          idStr, origStr, destStr, buffer[bufIdx].hopCount);
            Serial.printf("[LC] BufferCleared   msg=%s\n", idStr);
            buffer[bufIdx].valid = false;
            if (stats.bufferedNow > 0) stats.bufferedNow--;
            cleared++;
        } else {
            Serial.printf("[LC] AckReceived     msg=%s (no local buffer entry)\n", idStr);
        }
    }
    (void)cleared;
}

// ============================================================================
//  Central routing decision
// ============================================================================
// Called every time a bundle arrives — from a locally-connected phone (fromLoRa=false)
// or from the LoRa backbone (fromLoRa=true). Implements the intelligent-buffering
// requirement:
//   1. Primary dedup ring rejects repeats.
//   2. TTL / hop-limit sanity checks.
//   3. Locally deliver if the destination matches a connected phone (or broadcast).
//   4. Buffer + queue for LoRa TX only if the message still has somewhere else to go.
//   5. ROUTING_ACK bundles trigger buffer cleanup via processInboundAck.
void routeBundle(const uint8_t* data, uint16_t len, bool fromLoRa, int rssi) {
    if (len < HDR_MIN) return;

    // Learn the sender hub / origin phone id (via LoRa) with fresh RSSI.
    uint32_t originId = rd32(data + OFF_ORIGIN);
    if (fromLoRa) touchPeer(originId, rssi);

    if (seenBefore(data + OFF_MSGID)) {
        stats.duplicatesIgnored++;
        logLifecycle("DupIgnored", data + OFF_MSGID, originId, rd32(data + OFF_DEST),
                     data[OFF_HOP], rd16(data + OFF_TTL_MIN));
        return;
    }

    uint16_t ttl  = rd16(data + OFF_TTL_MIN);
    if (ttl == 0) {
        stats.ttlExpired++;
        logLifecycle("Expired", data + OFF_MSGID, originId, rd32(data + OFF_DEST),
                     data[OFF_HOP], ttl);
        return;
    }

    uint8_t  hop  = data[OFF_HOP];
    uint8_t  mt   = data[OFF_MSGTYPE];
    uint32_t dst  = rd32(data + OFF_DEST);
    bool isBroadcast = (dst == BROADCAST_DEST);

    // First-observation log — every new msg-id we see enters the log once.
    logLifecycle("Observed", data + OFF_MSGID, originId, dst, hop, ttl);

    // ACK bundles clear matching buffered messages and keep propagating.
    if (mt == MSGTYPE_ROUTING_ACK) {
        processInboundAck(data, len);
        // Also re-flood ACKs onward so upstream carriers can clear too.
        if (fromLoRa && hop < HOP_LIMIT) {
            if (txEnqueueForwardCopy(data, len)) {
                stats.msgsForwarded++;
                logLifecycle("Forwarded", data + OFF_MSGID, originId, dst, (uint8_t)(hop + 1), ttl);
            } else if (hop + 1 >= HOP_LIMIT) {
                stats.hopDropped++;
                logLifecycle("HopMax", data + OFF_MSGID, originId, dst, hop, ttl);
            }
        } else if (!fromLoRa) {
            // ACK originated on our local phone — put it on the LoRa backbone.
            if (bufferInsert(data, len)) {
                int idx = findInBuffer(data + OFF_MSGID);
                if (idx >= 0 && txEnqueue(idx)) {
                    stats.msgsForwarded++;
                    logLifecycle("Forwarded", data + OFF_MSGID, originId, dst, hop, ttl);
                }
            }
        }
        return;
    }

    // Heartbeats: an empty ROUTING_SUMMARY tells us a peer hub is alive. No further
    // routing needed — we've already touched the peer's last-seen above.
    if (mt == MSGTYPE_ROUTING_SUMMARY && len == HDR_MIN) {
        // (No lifecycle log here — heartbeats are chatty; keep the log signal-heavy.)
        return;
    }

    // Deliver locally if destination is (a) a connected phone, or (b) broadcast.
    bool delivered = deliverLocal(dst, data, len);
    if (delivered) {
        stats.msgsDelivered++;
        logLifecycle("Delivered", data + OFF_MSGID, originId, dst, hop, ttl);
    }

    if (!fromLoRa) {
        // From phone → the LoRa backbone. If this bundle was delivered locally and is
        // NOT a broadcast, the source phone's peer is on our own SoftAP — no need to
        // burn LoRa airtime forwarding a copy that would only echo back. Broadcasts
        // still forward so other hubs' phones can receive them.
        if (isBroadcast || !delivered) {
            if (bufferInsert(data, len)) {
                int idx = findInBuffer(data + OFF_MSGID);
                if (idx >= 0 && txEnqueue(idx)) {
                    stats.msgsForwarded++;
                    logLifecycle("Buffered", data + OFF_MSGID, originId, dst, hop, ttl);
                    logLifecycle("Forwarded", data + OFF_MSGID, originId, dst, hop, ttl);
                }
            } else {
                // Already had this in buffer — enqueue for TX without re-inserting.
                int idx = findInBuffer(data + OFF_MSGID);
                if (idx >= 0 && txEnqueue(idx)) {
                    stats.msgsForwarded++;
                    logLifecycle("Forwarded", data + OFF_MSGID, originId, dst, hop, ttl);
                }
            }
        }
        // else: unicast delivered locally → intentionally NOT buffered.
        return;
    }

    // Inbound from LoRa. Re-flood onto our own backbone only if:
    //   - it's a broadcast (needs to reach every region), or
    //   - the destination is not on our SoftAP (someone else may hold the recipient).
    if ((isBroadcast || !delivered) && hop < HOP_LIMIT) {
        if (txEnqueueForwardCopy(data, len)) {
            stats.msgsForwarded++;
            logLifecycle("Forwarded", data + OFF_MSGID, originId, dst, (uint8_t)(hop + 1), ttl);
        }
    } else if (hop >= HOP_LIMIT) {
        stats.hopDropped++;
        logLifecycle("HopMax", data + OFF_MSGID, originId, dst, hop, ttl);
    }
}

// ============================================================================
//  Heartbeats
// ============================================================================
// A tiny (header-only) ROUTING_SUMMARY bundle whose origin is our HUB_ID. Peers
// treat empty summaries as no-op learning packets; hubs use the origin to update
// their last-seen table (see touchPeer). Sent every HEARTBEAT_INTERVAL_MS.
uint32_t nextHeartbeatMs = 0;

void buildHeartbeatHeader(uint8_t* hdr) {
    // Fresh msgId — first 4 bytes are HUB_ID for readability, rest randomised so the
    // dedup ring on peer hubs doesn't collapse successive heartbeats into one.
    wr32(hdr + OFF_MSGID + 0, HUB_ID);
    for (int i = 4; i < 16; i++) hdr[OFF_MSGID + i] = (uint8_t)(esp_random() & 0xFF);
    wr32(hdr + OFF_ORIGIN, HUB_ID);
    wr32(hdr + OFF_DEST,   BROADCAST_DEST);
    hdr[OFF_TTL_MIN + 0] = 0; hdr[OFF_TTL_MIN + 1] = 5; // 5 minutes
    hdr[OFF_HOP]         = 0;
    hdr[OFF_MSGTYPE]     = MSGTYPE_ROUTING_SUMMARY;
    hdr[28] = 0; hdr[29] = 0; hdr[30] = 1; hdr[31] = 0;
}

void maybeSendHeartbeat() {
    uint32_t now = millis();
    if (now < nextHeartbeatMs) return;
    nextHeartbeatMs = now + HEARTBEAT_INTERVAL_MS;
    uint8_t hb[HDR_MIN];
    buildHeartbeatHeader(hb);
    // Send synchronously — heartbeats are ~32 B so they clear fast even at SF9.
    int st = radio.transmit(hb, HDR_MIN);
    radio.startReceive();
    if (st == RADIOLIB_ERR_NONE) {
        stats.msgsGenerated++;
        // Also self-remember the msg id so a boomerang copy is ignored.
        seenBefore(hb + OFF_MSGID);
    }
}

// ============================================================================
//  TCP <-> phones
// ============================================================================
void readClient(DtnClient& c) {
    while (c.sock.available() >= 3) {
        uint8_t h[3];
        c.sock.readBytes(h, 3);
        uint8_t  type = h[0];
        uint16_t len  = ((uint16_t)h[1] << 8) | h[2];
        if (len == 0 || len > 512) { c.sock.stop(); c.used = false; return; }
        uint8_t buf[512];
        int got = 0;
        while (got < len && c.sock.connected()) got += c.sock.readBytes(buf + got, len - got);
        c.lastSeenMs = millis();
        if (type == FRAME_REGISTER && len >= 4) {
            c.nodeId = rd32(buf);
            touchPeer(c.nodeId, /*rssi*/ 0); // phones have no RSSI toward the hub
            char nStr[10]; formatNodeId(c.nodeId, nStr);
            Serial.printf("[NET] Phone registered node=%s\n", nStr);
        } else if (type == FRAME_BUNDLE) {
            routeBundle(buf, len, /*fromLoRa*/ false, /*rssi*/ 0);
        }
    }
}

void acceptAndPoll() {
    WiFiClient nc = tcpServer.available();
    if (nc) {
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (!clients[i].used) {
                clients[i] = { nc, 0, true, false, millis() };
                break;
            }
        }
    }
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (!clients[i].used) continue;
        if (!clients[i].sock.connected()) {
            if (clients[i].nodeId != 0) {
                char nStr[10]; formatNodeId(clients[i].nodeId, nStr);
                Serial.printf("[NET] Phone dropped   node=%s\n", nStr);
            }
            clients[i].sock.stop();
            clients[i].used = false;
            continue;
        }
        if (!clients[i].greeted) {
            uint8_t id[4];
            wr32(id, HUB_ID);
            sendFrame(clients[i].sock, FRAME_HELLO, id, 4);
            clients[i].greeted = true;
        }
        readClient(clients[i]);
    }
}

// ============================================================================
//  LoRa pumps
// ============================================================================
void pumpLoRaTx() {
    if (txHead == txTail) return;
    TxItem& it = txQueue[txHead];
    BufferEntry& e = buffer[it.bufferIndex];
    if (!e.valid) {
        // Entry got cleared (e.g. an ACK removed it) between enqueue and TX — skip.
        txHead = (txHead + 1) % TX_QUEUE_N;
        return;
    }
    int st = radio.transmit(e.data, e.len);
    if (st == RADIOLIB_ERR_NONE) txHead = (txHead + 1) % TX_QUEUE_N;
    radio.startReceive();
}

void pumpLoRaRx() {
    if (!loraRxFlag) return;
    loraRxFlag = false;
    uint8_t buf[256];
    int len = radio.getPacketLength();
    if (len > 0 && radio.readData(buf, len) == RADIOLIB_ERR_NONE) {
        int rssi = (int)radio.getRSSI();
        routeBundle(buf, len, /*fromLoRa*/ true, rssi);
    }
    radio.startReceive();
}

// ============================================================================
//  Serial monitor dashboards (Distance / Buffer / Stats)
// ============================================================================
uint32_t nextDashboardMs = 0;

int countActivePeers() {
    int n = 0;
    for (int i = 0; i < MAX_KNOWN_PEERS; i++) if (knownPeers[i].valid && knownPeers[i].active) n++;
    return n;
}
int countInactivePeers() {
    int n = 0;
    for (int i = 0; i < MAX_KNOWN_PEERS; i++) if (knownPeers[i].valid && !knownPeers[i].active) n++;
    return n;
}
int countConnectedPhones() {
    int n = 0;
    for (int i = 0; i < MAX_CLIENTS; i++) if (clients[i].used && clients[i].sock.connected()) n++;
    return n;
}

void printDashboard() {
    refreshPeerActivity();

    Serial.println();
    Serial.println("========================= DTN HUB DASHBOARD =========================");

    // Host header — always active while the firmware is running.
    char hostStr[10]; formatNodeId(HUB_ID, hostStr);
    Serial.printf("Host  : %s (HUB_ID) status=ACTIVE uptime=%lus\n",
                  hostStr, (unsigned long)(millis() / 1000));

    // ---- Distance table -----------------------------------------------------
    Serial.println("\n[ DISTANCE TABLE ]");
    Serial.println("+----------------+-----------+---------+---------+---------+");
    Serial.println("| Node ID        | Distance  |  RSSI   | LastSeen| Status  |");
    Serial.println("+----------------+-----------+---------+---------+---------+");
    Serial.printf("| %-14s | %8s m | %6s  | %7s | %7s |\n",
                  hostStr, "0.0", "-", "now", "ACTIVE");

    for (int i = 0; i < MAX_KNOWN_PEERS; i++) {
        if (!knownPeers[i].valid) continue;
        char idStr[10]; formatNodeId(knownPeers[i].nodeId, idStr);
        double d = estimateDistanceM(knownPeers[i].lastRssi);
        char distStr[16];
        if (d < 0) snprintf(distStr, sizeof(distStr), "%8s", "-");
        else       snprintf(distStr, sizeof(distStr), "%8.1f", d);
        char ageStr[16];
        snprintf(ageStr, sizeof(ageStr), "%lus",
                 (unsigned long)((millis() - knownPeers[i].lastSeenMs) / 1000UL));
        Serial.printf("| %-14s | %s m | %6d  | %7s | %7s |\n",
                      idStr, distStr, knownPeers[i].lastRssi, ageStr,
                      knownPeers[i].active ? "ACTIVE" : "INACTIVE");
    }
    // Locally-connected phones (no RSSI on WiFi from the hub side).
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (!clients[i].used || !clients[i].sock.connected() || clients[i].nodeId == 0) continue;
        char idStr[10]; formatNodeId(clients[i].nodeId, idStr);
        Serial.printf("| %-14s | %8s m | %6s  | %7s | %7s |\n",
                      idStr, "wifi", "-", "now", "ACTIVE");
    }
    Serial.println("+----------------+-----------+---------+---------+---------+");

    // ---- Buffer state -------------------------------------------------------
    Serial.println("\n[ BUFFER STATE ]");
    Serial.println("+----------+----------------+----------------+-----+-----+--------+-------+");
    Serial.println("| MsgId    | Origin         | Dest           | Hop | TTL | Age(s) | WaitAck|");
    Serial.println("+----------+----------------+----------------+-----+-----+--------+-------+");
    int shown = 0;
    for (int i = 0; i < BUFFER_CAPACITY; i++) {
        if (!buffer[i].valid) continue;
        char idStr[9]; msgIdShort(buffer[i].msgId, idStr);
        char oStr[10]; formatNodeId(buffer[i].origin, oStr);
        char dStr[10]; formatNodeId(buffer[i].dest,   dStr);
        uint32_t age = (millis() - buffer[i].bufferedAtMs) / 1000UL;
        Serial.printf("| %-8s | %-14s | %-14s | %3u | %3u | %6lu | %5s |\n",
                      idStr, oStr, dStr, buffer[i].hopCount, buffer[i].ttlMin,
                      (unsigned long)age, buffer[i].waitingForAck ? "yes" : "no");
        shown++;
    }
    if (shown == 0) Serial.println("| (buffer empty)                                                            |");
    Serial.println("+----------+----------------+----------------+-----+-----+--------+-------+");

    // ---- Runtime stats ------------------------------------------------------
    Serial.println("\n[ RUNTIME STATS ]");
    Serial.printf("  Generated=%lu  Forwarded=%lu  Delivered=%lu\n",
                  (unsigned long)stats.msgsGenerated,
                  (unsigned long)stats.msgsForwarded,
                  (unsigned long)stats.msgsDelivered);
    Serial.printf("  AcksReceived=%lu  DupIgnored=%lu  DupAcksIgnored=%lu\n",
                  (unsigned long)stats.acksReceived,
                  (unsigned long)stats.duplicatesIgnored,
                  (unsigned long)stats.duplicateAcksIgnored);
    Serial.printf("  TtlExpired=%lu  HopDropped=%lu\n",
                  (unsigned long)stats.ttlExpired,
                  (unsigned long)stats.hopDropped);
    Serial.printf("  BufferNow=%lu  PeakBuffer=%lu  Inserts=%lu  Evictions=%lu\n",
                  (unsigned long)stats.bufferedNow,
                  (unsigned long)peakBufferUsage,
                  (unsigned long)stats.bufferInserts,
                  (unsigned long)stats.bufferEvictions);
    Serial.printf("  ActivePeers=%d  InactivePeers=%d  ConnectedPhones=%d\n",
                  countActivePeers(), countInactivePeers(), countConnectedPhones());
    Serial.println("=====================================================================\n");
}

void maybePrintDashboard() {
    uint32_t now = millis();
    if (now < nextDashboardMs) return;
    nextDashboardMs = now + DISTANCE_UPDATE_INTERVAL_MS;
    printDashboard();
}

// ============================================================================
//  setup / loop
// ============================================================================
void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println("DTN LoRa Hub firmware booting");

    for (int i = 0; i < BUFFER_CAPACITY; i++) buffer[i].valid = false;
    for (int i = 0; i < MAX_KNOWN_PEERS; i++) knownPeers[i].valid = false;
    memset(dedupKey, 0, sizeof(dedupKey));
    memset(ackDedupKey, 0, sizeof(ackDedupKey));

    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASS, 1 /*channel*/, 0, MAX_CLIENTS);
    tcpServer.begin();
    Serial.printf("SoftAP %s up at %s\n", AP_SSID, WiFi.softAPIP().toString().c_str());

    int st = radio.begin(LORA_FREQ, LORA_BW, LORA_SF, LORA_CR);
    if (st != RADIOLIB_ERR_NONE) {
        Serial.printf("LoRa init fail %d — halting\n", st);
        while (true) delay(1000);
    }
    radio.setDio1Action(onLoRaRx);
    radio.startReceive();

    char hostStr[10]; formatNodeId(HUB_ID, hostStr);
    Serial.printf("Hub %s ready\n", hostStr);
    nextDashboardMs = millis() + DISTANCE_UPDATE_INTERVAL_MS;
    nextHeartbeatMs = millis() + HEARTBEAT_INTERVAL_MS;
}

void loop() {
    acceptAndPoll();          // WiFi: accept phones, greet, read frames
    pumpLoRaRx();             // LoRa: receive backbone bundles
    pumpLoRaTx();             // LoRa: transmit one queued bundle
    maybeSendHeartbeat();     // LoRa: periodic keep-alive
    bufferSweepExpired();     // TTL floor cleanup
    maybePrintDashboard();    // Serial: distance table + buffer + stats
    delay(2);
}
