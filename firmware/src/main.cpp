// ============================================================================
//  DTN LoRa Hub — Heltec WiFi LoRa 32 V3 (ESP32-S3 + SX1262)
//  Role: WiFi SoftAP for many phones  +  LoRa hub-to-hub backbone.
//  Framework: Arduino / PlatformIO   Library: RadioLib
//
//  Protocol (must match the Android app):
//   * Framed TCP on port 9740:  [1B type][2B length BE][payload]
//       - HELLO    0x03  hub -> phone   payload = 4B HUB_ID (sent on connect)
//       - REGISTER 0x01  phone -> hub   payload = 4B phone node id
//       - BUNDLE   0x02  either way     payload = DTN bundle (32B header + data)
//   * Self-contained 32-byte DTN header (see DtnWireCodec.kt):
//       0:16 msgId  16:4 origin  20:4 dest  24:2 ttlMin  26:1 hop
//       27:1 type  28:1 channel  29:1 fragIdx  30:1 fragTotal  31:1 reserved
//
//  This is a working reference, not hardened firmware. Set LORA_FREQ to your
//  legal band and add duty-cycle pacing before scaling beyond a few hubs.
// ============================================================================
#include <WiFi.h>
#include <RadioLib.h>

// ---- Per-hub identity (CHANGE PER DEVICE) ----------------------------------
static const uint32_t HUB_ID   = 0xA0000001;      // unique 32-bit hub node id
static const char*     AP_SSID = "DTN-HUB-A";     // "DTN-HUB-B" on the other hub
static const char*     AP_PASS = "dtnmesh123";    // must match app HUB_PASSPHRASE (>= 8 chars)
static const uint8_t   MAX_CLIENTS = 6;           // ESP32 SoftAP: keep <= 8

// ---- SX1262 pins for Heltec WiFi LoRa 32 V3 --------------------------------
SX1262 radio = new Module(8 /*NSS*/, 14 /*DIO1*/, 12 /*RST*/, 13 /*BUSY*/);
static const float   LORA_FREQ = 868.0;  // 915.0 US / 433.0 many-Asia — MATCH LAW
static const float   LORA_BW   = 125.0;
static const uint8_t LORA_SF   = 9;       // higher SF = longer range, slower
static const uint8_t LORA_CR   = 5;

WiFiServer tcpServer(9740);
struct Client { WiFiClient sock; uint32_t nodeId; bool used; bool greeted; };
Client clients[MAX_CLIENTS];

enum : uint8_t { FRAME_REGISTER = 0x01, FRAME_BUNDLE = 0x02, FRAME_HELLO = 0x03 };

// DTN self-contained header offsets
static const int HDR_MIN = 32, OFF_MSGID = 0, OFF_DEST = 20, OFF_TTL_MIN = 24, OFF_HOP = 26;

// Reserved destination meaning "deliver to every node" (matches NodeId.BROADCAST_NODE_NUM32).
static const uint32_t BROADCAST_DEST = 0xFFFFFFFFu;

// Dedup cache of recently seen message ids (first 16 bytes = UUID)
static const int DEDUP_N = 128;
uint8_t dedupKey[DEDUP_N][16];
int     dedupHead = 0;

// LoRa transmit queue (store-and-forward)
struct Bundle { uint8_t* data; uint16_t len; };
static const int QN = 48;
Bundle txQueue[QN];
int qHead = 0, qTail = 0;

uint32_t rd32(const uint8_t* p) { return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3]; }
uint16_t rd16(const uint8_t* p) { return ((uint16_t)p[0] << 8) | p[1]; }

bool seenBefore(const uint8_t* id) {
  for (int i = 0; i < DEDUP_N; i++) if (memcmp(dedupKey[i], id, 16) == 0) return true;
  memcpy(dedupKey[dedupHead], id, 16);
  dedupHead = (dedupHead + 1) % DEDUP_N;
  return false;
}

bool qPush(const uint8_t* data, uint16_t len) {
  int nxt = (qTail + 1) % QN;
  if (nxt == qHead) return false;                 // queue full -> drop
  txQueue[qTail].data = (uint8_t*)malloc(len);
  if (!txQueue[qTail].data) return false;
  memcpy(txQueue[qTail].data, data, len);
  txQueue[qTail].len = len;
  qTail = nxt;
  return true;
}

// Write a framed message to a connected phone.
void sendFrame(WiFiClient& sock, uint8_t type, const uint8_t* payload, uint16_t len) {
  uint8_t hdr[3] = { type, (uint8_t)(len >> 8), (uint8_t)(len & 0xFF) };
  sock.write(hdr, 3);
  if (len) sock.write(payload, len);
}

// Deliver a bundle to locally-connected phone(s).
// For a broadcast destination, fan out to every connected phone (except the sender, whom we
// can't identify here — app-level dedup by message id absorbs any echo). Returns true if it
// was delivered to at least one local phone.
bool deliverLocal(uint32_t dest, const uint8_t* data, uint16_t len) {
  if (dest == BROADCAST_DEST) {
    bool any = false;
    for (int i = 0; i < MAX_CLIENTS; i++)
      if (clients[i].used && clients[i].sock.connected()) {
        sendFrame(clients[i].sock, FRAME_BUNDLE, data, len);
        any = true;
      }
    return any;
  }
  for (int i = 0; i < MAX_CLIENTS; i++)
    if (clients[i].used && clients[i].nodeId == dest && clients[i].sock.connected()) {
      sendFrame(clients[i].sock, FRAME_BUNDLE, data, len);
      return true;
    }
  return false;
}

// Route a bundle that just arrived (from a phone or from LoRa).
void routeBundle(const uint8_t* data, uint16_t len, bool fromLoRa) {
  if (len < HDR_MIN) return;
  if (seenBefore(data + OFF_MSGID)) return;        // dedup
  if (rd16(data + OFF_TTL_MIN) == 0) return;       // expired
  uint8_t  hop  = data[OFF_HOP];
  uint32_t dest = rd32(data + OFF_DEST);

  bool delivered = deliverLocal(dest, data, len);
  bool isBroadcast = (dest == BROADCAST_DEST);

  if (!fromLoRa) {
    qPush(data, len);                              // from phone -> onto the backbone
  } else if ((isBroadcast || !delivered) && hop < 5) {
    // From LoRa: re-flood if hops remain. Broadcasts always propagate onward (so every
    // region hears them); unicast only re-floods when the destination isn't local here.
    uint8_t* c = (uint8_t*)malloc(len);
    if (c) { memcpy(c, data, len); c[OFF_HOP] = hop + 1; qPush(c, len); free(c); }
  }
}

// ---- WiFi / TCP ------------------------------------------------------------
void readClient(Client& c) {
  while (c.sock.available() >= 3) {
    uint8_t h[3];
    c.sock.readBytes(h, 3);
    uint8_t  type = h[0];
    uint16_t len  = ((uint16_t)h[1] << 8) | h[2];
    if (len == 0 || len > 512) { c.sock.stop(); c.used = false; return; }
    uint8_t buf[512];
    int got = 0;
    while (got < len && c.sock.connected()) got += c.sock.readBytes(buf + got, len - got);
    if (type == FRAME_REGISTER && len >= 4)      c.nodeId = rd32(buf);
    else if (type == FRAME_BUNDLE)               routeBundle(buf, len, false);
  }
}

void acceptAndPoll() {
  WiFiClient nc = tcpServer.available();
  if (nc) {
    for (int i = 0; i < MAX_CLIENTS; i++)
      if (!clients[i].used) { clients[i] = { nc, 0, true, false }; break; }
  }
  for (int i = 0; i < MAX_CLIENTS; i++) {
    if (!clients[i].used) continue;
    if (!clients[i].sock.connected()) { clients[i].sock.stop(); clients[i].used = false; continue; }
    if (!clients[i].greeted) {                      // announce our HUB_ID once
      uint8_t id[4] = { (uint8_t)(HUB_ID >> 24), (uint8_t)(HUB_ID >> 16), (uint8_t)(HUB_ID >> 8), (uint8_t)HUB_ID };
      sendFrame(clients[i].sock, FRAME_HELLO, id, 4);
      clients[i].greeted = true;
    }
    readClient(clients[i]);
  }
}

// ---- LoRa ------------------------------------------------------------------
volatile bool loraRxFlag = false;
void IRAM_ATTR onLoRaRx() { loraRxFlag = true; }

void pumpLoRaTx() {
  if (qHead == qTail) return;
  Bundle& b = txQueue[qHead];
  if (radio.transmit(b.data, b.len) == RADIOLIB_ERR_NONE) { free(b.data); qHead = (qHead + 1) % QN; }
  radio.startReceive();
}

void pumpLoRaRx() {
  if (!loraRxFlag) return;
  loraRxFlag = false;
  uint8_t buf[256];
  int len = radio.getPacketLength();
  if (len > 0 && radio.readData(buf, len) == RADIOLIB_ERR_NONE) routeBundle(buf, len, true);
  radio.startReceive();
}

// ---- setup / loop ----------------------------------------------------------
void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS, 1 /*channel*/, 0, MAX_CLIENTS);
  tcpServer.begin();
  Serial.printf("SoftAP %s up at %s\n", AP_SSID, WiFi.softAPIP().toString().c_str());

  int st = radio.begin(LORA_FREQ, LORA_BW, LORA_SF, LORA_CR);
  if (st != RADIOLIB_ERR_NONE) { Serial.printf("LoRa init fail %d\n", st); while (true) delay(1000); }
  radio.setDio1Action(onLoRaRx);
  radio.startReceive();
  Serial.printf("Hub %08X ready\n", HUB_ID);
}

void loop() {
  acceptAndPoll();   // WiFi: accept phones, greet, read frames
  pumpLoRaRx();      // LoRa: receive backbone bundles
  pumpLoRaTx();      // LoRa: transmit one queued bundle
  delay(2);
}
