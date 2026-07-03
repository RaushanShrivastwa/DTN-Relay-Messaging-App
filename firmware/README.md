# DTN LoRa Hub firmware

Custom firmware for the LoRa hubs — an **ESP32-S3 + SX1262** (Heltec WiFi LoRa 32 V3). Each hub is
a WiFi SoftAP that several phones join, bridged to a long-range LoRa hub-to-hub backbone. Independent
of Meshtastic.

## Build & flash (PlatformIO)

```bash
cd firmware
pio run                 # build
pio run -t upload       # flash over USB
pio device monitor      # serial log @115200
```

(Or open this folder in VS Code with the PlatformIO extension.)

## Per-hub configuration

Edit the top of `src/main.cpp` for each device before flashing:

| Constant   | Purpose                        | Example (Hub A / Hub B)         |
|------------|--------------------------------|---------------------------------|
| `HUB_ID`   | Unique 32-bit hub node id      | `0xA0000001` / `0xA0000002`     |
| `AP_SSID`  | SoftAP name (prefix `DTN-HUB-`)| `"DTN-HUB-A"` / `"DTN-HUB-B"`   |
| `AP_PASS`  | SoftAP passphrase (≥ 8 chars)  | must match app `HUB_PASSPHRASE` |
| `LORA_FREQ`| LoRa band — **set to your law**| `868.0` EU / `915.0` US / `433.0` |

The SSID prefix (`DTN-HUB-`), passphrase, TCP port (`9740`), and frame protocol must stay in sync
with `LoRaHubTransportAdapter.kt` in the Android app.

## Protocol (matches the Android app)

Framed TCP on port 9740: `[1B type][2B length BE][payload]`

- `HELLO` (0x03) hub→phone, 4B `HUB_ID`, sent once on connect so the phone treats the hub as a peer.
- `REGISTER` (0x01) phone→hub, 4B phone node id, so the hub can deliver bundles back to that phone.
- `BUNDLE` (0x02) either direction, a self-contained DTN bundle (32-byte header + payload).

Over LoRa the hub floods bundles with message-ID **dedup**, **TTL** and a **hop limit** (≤ 5).

## Known limitations (research reference, not production)

- No LoRa duty-cycle pacing — fine for 2–4 hubs; add before scaling.
- No over-the-air encryption (WPA2 protects the SoftAP link only).
- SoftAP serves ~4–8 concurrent phones.
- `HDR_MIN`/offsets in `main.cpp` must track any change to `DtnWireCodec` header layout.
