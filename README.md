# DTN Mesh Relay Messaging App

An off-grid, peer-to-peer (P2P) delay-tolerant messaging application for Android. It operates without cellular coverage or internet connectivity by employing a Store-Carry-Forward routing model across multiple physical transports. 

The application optimizes forwarding decisions in real time by combining classical routing protocols (**PRoPHET** and **MaxProp**) with a modern **Double Q-Learning** reinforcement learning (RL) engine.

---

## Architecture Overview

The core system components are structured using clean, modular architectural principles coordinated by a centralized thread-safe orchestrator.

```mermaid
graph TD
    A[DtnForegroundService] -->|Lifecycle control| B[DtnOrchestrator]
    B -->|Ingests events| C[Single Event Channel]
    C -->|Single-threaded loop| D[EventHandler]
    D -->|Query/Update| E[(Room Database)]
    D -->|Fetch candidates| F[MessageQueueManager]
    D -->|Route selection| G[StrategySelector]
    D -->|Forwarding Decisions| H[DoubleQLearningEngine]
    D -->|Send/Receive Packets| I[MultiTransportManager]
    
    I --> J[BleTransportAdapter]
    I --> K[WifiDirectTransportAdapter]
    I --> L[LoRaHubTransportAdapter]
```

### Key Components

1. **`DtnForegroundService`**: Maintains the application stack in the background, prevents OS process reclamation, handles system broadcast notifications, and manages power locks during mesh synchronization.
2. **`DtnOrchestrator`**: A single-threaded event loop driven by a Kotlin Coroutine `Channel`. It processes all transport events (encounters, packet receptions, delivery statuses) sequentially to guarantee race-free mutations of the Q-tables and the message queue database.
3. **`MultiTransportManager`**: Abstractly merges heterogeneous transport adapters (BLE, Wi-Fi Direct, LoRa Hub) into a unified send/receive API.
4. **`MessageQueueManager`**: Manages store-and-forward bundles, checks message validity, enforces storage quotas, and provides candidate message lists for encountered peers.
5. **`StrategySelector`**: Manages active classical routing models like PRoPHET and MaxProp, computing current transit delivery probabilities for each peer.
6. **`DoubleQLearningEngine`**: The reinforcement learning kernel that decides whether to immediately **FORWARD** a message to an encountered peer or **STORE/WAIT** for a better future encounter.

---

## Heterogeneous Transport Layers

The application operates over three independent physical layers:

### 1. Bluetooth Low Energy (BLE) (`BleTransportAdapter`)
* **Split Advertising**: To fit within the 31-byte legacy advertising payload limit:
  * The **Main Advertisement Packet** contains the Service UUID.
  * The **Scan Response Packet** carries the `Service Data` which encapsulates the local Node ID.
* **GATT Server**: Opens a local GATT Server featuring a single Characteristic with WRITE properties (`PROPERTY_WRITE` and `PROPERTY_WRITE_NO_RESPONSE`) allowing centals to write serialized DTN packets.
* **Central/Peripheral Roles**: Alternates between advertising (Peripheral mode) and scanning (Central mode). When it finds a peer, it connects as a central, transmits outstanding payloads, and disconnects.

### 2. Wi-Fi Direct P2P (`WifiDirectTransportAdapter`)
* Used for high-bandwidth bulk message exchanges.
* Coordinates Group Owner (GO) negotiation and uses raw TCP sockets (`ServerSocket` and `Socket`) to synchronize queue indices and dump large message batches.

### 3. LoRa Hub Bridge (`LoRaHubTransportAdapter`)
* Connects to a local LoRa gateway/hardware shield (e.g., Meshtastic radio device) to bridge short-range IP networks with long-range, low-bandwidth sub-GHz links.

---

## Routing & Reinforcement Learning Engine

The core forwarding path uses a hybrid model where classical probabilities feed as inputs into a Double Q-Learning state formulation.

### Double Q-Learning Model

To avoid the maximization bias inherent in standard Q-learning, the engine maintains two independent Q-tables ($Q^A$ and $Q^B$), updating them randomly on incoming experience tuples.

#### 1. State Space ($S$)
The state is a 9-dimensional normalized vector representing the current context:
* `rssiNorm`: Normalized RSSI of the connection $[0, 1]$.
* `snrNorm`: Normalized SNR of the connection $[0, 1]$.
* `encounterFrequency`: Number of direct encounters with this peer in the last 24 hours.
* `encounterDuration`: Running average duration of previous encounters.
* `deliveryProbability`: Composite delivery probability from classical models (PRoPHET/MaxProp).
* `remainingTtlFraction`: Remaining Time-To-Live fraction of the bundle $[0, 1]$.
* `bufferOccupancy`: Local queue storage occupancy fraction $[0, 1]$.
* `historicalSuccessRate`: Historical percentage of successful deliveries via this peer.
* `duplicateCountNorm`: Normalized count of identical packets seen in the mesh.

#### 2. Action Space ($A$)
* `FORWARD`: Transmit the packet to the encountered peer.
* `STORE_WAIT`: Keep the packet in local storage and wait for a better contact.

#### 3. Reward Function ($R$)
Rewards are backfilled dynamically using a differentiated credit assignment:
* **Direct Delivery**: $+1.0$ when the packet is successfully delivered to its destination.
* **Intermediate Forwarding**: Positive fraction $+0.3$ is assigned to intermediate nodes that successfully routed the message.
* **Failure / Expiry**: $-0.5$ if the packet fails transmission, and $-0.2$ if it expires in queue without delivery.

---

## Database Schema

The persistence layer is managed by Room (`AppDatabase`).

### 1. `contacts` Table
Stores historical contact profiles for known mesh nodes.
* `node_id` (String, Primary Key)
* `delivery_probability` (Double)
* `total_encounters` (Int)
* `total_encounter_duration_ms` (Long)
* `best_rssi` / `last_rssi` (Int)
* `best_snr` / `last_snr` (Float)
* `delivery_success_count` (Int)
* `delivery_attempt_count` (Int)

### 2. `encounters` Table
Logs individual connection windows.
* `id` (Long, Auto-Generate Primary Key)
* `peer_node_id` (String)
* `start_time_ms` / `end_time_ms` (Long)
* `best_rssi` (Int)
* `best_snr` (Float)

### 3. `messages` Table
Maintains the Store-Carry-Forward message store.
* `id` (String, Primary Key)
* `origin_node_id` / `dest_node_id` (String)
* `payload_bytes` (Blob)
* `ttl_ms` / `created_at_ms` (Long)
* `hop_count` / `duplicate_count` (Int)
* `last_forwarded_to` (String, Nullable)

### 4. `forwarding_decisions` Table
An instrumentation table recording RL training steps.
* `id` (Long, Primary Key)
* `timestamp_ms` (Long)
* `message_id` (String)
* `peer_node_id` (String)
* `state_*` (Normalized state features)
* `action` (String - FORWARD/STORE_WAIT)
* `q_value_selected` (Double)
* `reward` (Double, Nullable)

---

## Compilation and Deployment

### Prerequisites
* Android SDK (API 34+)
* Java Development Kit (JDK) 17
* Gradle 8.2+

### Building the Project
To compile the application and generate a debug APK:
```bash
# Set your Android SDK path (if needed)
$env:ANDROID_HOME="C:\Users\<YourUsername>\AppData\Local\Android\Sdk"

# Compile and package the debug build
./gradlew :app:assembleDebug
```
The output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Installation
Deploy the APK to a connected Android device via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Monitoring Logs
Filter the system logs to inspect connection states, split advertising packets, or routing decisions:
```bash
adb logcat -s BleTransport:* MultiTransport:* DtnOrchestrator:* WifiDirectTransport:*
```
