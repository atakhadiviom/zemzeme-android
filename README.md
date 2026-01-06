<p align="center">
  <img src="docs/logo.png" alt="Zemzeme" width="160"/>
</p>

<h1 align="center">Zemzeme</h1>

<p align="center"><strong>Private, serverless messaging — no accounts, no internet required.</strong></p>

Zemzeme is a fork of [bitchat](https://github.com/permissionlesstech/bitchat-android) (v1.7.0) for Android, extended with a second internet-capable transport layer built on [libp2p](https://libp2p.io). It supports three independent communication methods that can operate simultaneously: offline Bluetooth mesh, direct peer-to-peer over the internet (via libp2p + ICE), and relay-based messaging via the Nostr protocol. All messages are end-to-end encrypted. No registration, no phone number, no central server.

---

## Acknowledgements

Zemzeme stands on the shoulders of the [**bitchat team**](https://github.com/permissionlesstech/bitchat-android) and its contributors. The core BLE mesh architecture, Noise protocol integration, gossip-based relay, and the original application design are their work. We are grateful for their commitment to open, private, decentralised communication.

> **Forked from bitchat v1.7.0**

---

## Features

### Messaging
- Text messages with formatting and `@mention` support
- Voice notes (compressed audio)
- Image and file sharing (chunked binary transfer)
- Read receipts and delivery indicators
- Password-protected channels

### Transport Layers
| Layer | Medium | Internet Required |
|---|---|---|
| BLE Mesh | Bluetooth LE | No |
| P2P / ICE | Wi-Fi / Mobile | Yes |
| Nostr | Wi-Fi / Mobile (relay) | Yes |

All three layers operate simultaneously; the app routes messages through whichever paths are available and falls back automatically.

### Location & Channels
- Geohash-based public channels (city → country granularity)
- Custom named group chats
- Geographic group auto-discovery

### Security
- **Noise Protocol** (Noise_NK, Curve25519 + ChaCha20-Poly1305) for all peer sessions
- **Ed25519** signed QR codes for peer identity verification
- **BouncyCastle PBKDF2** PIN hashing
- **EncryptedSharedPreferences** (AES-256-GCM) for all local secrets
- Biometric + PIN app lock (optional)
- No GPS coordinates transmitted — only coarse geohash

### Privacy
- No account or phone number required
- Zero central server; all state is local or peer-to-peer
- BLE mesh relays use hop-by-hop encryption
- Tor integration (optional, in progress)

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    Jetpack Compose UI                    │
├──────────────────────────────────────────────────────────┤
│                      ChatViewModel                       │
├───────────────────┬──────────────────┬───────────────────┤
│     BLE Mesh      │  P2P (libp2p)    │      Nostr        │
│                   │                  │                   │
│ BluetoothMesh     │ P2PTransport     │ NostrTransport    │
│ Service           │ P2PLibraryRepo   │ NostrRelayManager │
│                   │ golib.aar (Go)   │ NostrClient       │
├───────────────────┴──────────────────┴───────────────────┤
│              EncryptionService (Noise Protocol)          │
│              NoiseEncryptionService                      │
└──────────────────────────────────────────────────────────┘
```

### BLE Mesh
The original bitchat mesh engine. Devices discover each other via BLE advertising and scanning, exchange messages over GATT, and relay packets through nearby devices using TTL-based store-and-forward. Duplicate suppression uses a GCS Bloom filter (Gossip Sync).

### P2P Transport (libp2p)
An additional transport layer, unique to this fork. A pre-compiled Go library (`golib.aar`) built with [gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) wraps [**libp2p**](https://libp2p.io) — the open-source peer-to-peer networking library originally developed by Protocol Labs and used by IPFS, Filecoin, and Ethereum 2. It provides:

- **Direct ICE connections** between peers through NATs and firewalls (no relay server needed once connected)
- **DHT peer discovery** using the IPFS bootstrap network
- **GossipSub** pub/sub for topic-based channels
- **Media chunking** — files split into ≤ 200 KB chunks sent in parallel

libp2p is an independent open-source project. Source: [https://libp2p.io](https://libp2p.io) · GitHub: [libp2p/go-libp2p](https://github.com/libp2p/go-libp2p)

### Nostr Transport
Uses the [Nostr](https://nostr.com) protocol for relay-based messaging. Supports private DMs (Curve25519-XChaCha20-Poly1305), geohash channels, proof-of-work, and fragment reassembly for large messages.

---

## Requirements

| Requirement | Details |
|---|---|
| Android | 8.0 (API 26) or higher |
| Bluetooth | Bluetooth LE (BLE) capable device |
| Permissions | See [Permissions](#permissions) |

---

## Building from Source

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17
- Android SDK with API 35

### Clone and Build

```bash
git clone https://github.com/whisperbit-labs/zemzeme-android.git
cd zemzeme-android
```

Open in Android Studio and sync Gradle, or build from the command line:

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config in local.properties)
./gradlew assembleRelease
```

### Signing (Release)

Create `local.properties` (not committed) and add:

```properties
KEYSTORE_FILE=/path/to/your/keystore.jks
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=your_key_alias
KEY_PASSWORD=your_key_password
```

### APK Splits

The build produces architecture-specific APKs automatically:

| ABI | File suffix |
|---|---|
| arm64-v8a | `...-arm64-v8a-release.apk` |
| armeabi-v7a | `...-armeabi-v7a-release.apk` |
| x86_64 | `...-x86_64-release.apk` |

---

## Installation

### From Release APK
1. Download the APK matching your device architecture from the [Releases](../../releases) page
2. Enable **Install from unknown sources** in Android settings
3. Open the APK and install

### From Source
Build the debug APK and install via ADB:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN / CONNECT / ADVERTISE` | BLE mesh peer discovery and communication |
| `ACCESS_FINE_LOCATION` | Required by Android for BLE scanning |
| `ACCESS_BACKGROUND_LOCATION` | Keep mesh active while app is backgrounded |
| `INTERNET` | P2P (libp2p) and Nostr transport |
| `FOREGROUND_SERVICE` | Persistent background mesh relay |
| `RECEIVE_BOOT_COMPLETED` | Auto-start mesh on device boot |
| `POST_NOTIFICATIONS` | Message notifications |
| `CAMERA` | QR code scanning for peer verification |
| `RECORD_AUDIO` | Voice notes |
| `READ_MEDIA_IMAGES / AUDIO / VIDEO` | Media sharing |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Reliable background operation |

Zemzeme does **not** access contacts, call logs, SMS, or any external account.

---

## Getting Started

1. **Launch** the app — no registration needed
2. **Grant permissions** when prompted (Bluetooth, location, notifications)
3. **Pick a nickname** — used only locally and in messages to peers
4. The **Bluetooth** group is the local BLE mesh; nearby Zemzeme users appear automatically
5. Tap **+** on the home screen to join a geohash channel or start a private chat
6. Scan a peer's **QR code** (from the verification sheet) to confirm their identity

### App Lock (optional)
Go to **Settings → App Lock** to enable PIN + biometric protection. The lock screen appears every time the app returns to the foreground.

---

## Project Structure

```
app/src/main/java/com/roman/zemzeme/
├── crypto/            Noise protocol & symmetric encryption
├── favorites/         Peer bookmarking & persistence
├── geohash/           Location channels and geohash utilities
├── mesh/              BLE mesh engine (BluetoothMeshService, GATT managers)
├── nostr/             Nostr client, relay manager, DM handler
├── onboarding/        First-run flow, app lock setup
├── p2p/               libp2p/ICE transport, chunk assembler
├── security/          AppLockManager, PIN hashing
├── services/          QR verification, identity, background services
├── ui/                Jetpack Compose screens and sheets
│   ├── theme/         Color scheme (ElectricCyan), typography (Nunito)
│   └── media/         Image viewer, file animations
└── MainActivity.kt    Entry point, navigation, lifecycle
```

---

## Technology Stack

| Component | Library / Version |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Font | Nunito (SIL OFL) |
| BLE | Nordic Semiconductor BLE library 2.6.1 |
| P2P | libp2p (Go, via gomobile · golib.aar) |
| Nostr | Custom Kotlin client over OkHttp WebSocket |
| Encryption | BouncyCastle 1.70, Google Tink 1.10.0 |
| QR Generation | ZXing 3.5.4 |
| QR Scanning | ML Kit Barcode Scanning 17.3.0 |
| Camera | CameraX 1.5.2 |
| Location | Google Play Services Location 21.3.0 |
| Biometric | AndroidX Biometric 1.1.0 |
| Secure Storage | AndroidX Security Crypto 1.1.0-beta01 |
| JSON | Gson 2.13.1 |
| Coroutines | Kotlin Coroutines 1.10.2 |

---

## Contributing

Pull requests are welcome. For significant changes, please open an issue first to discuss the approach.

```bash
# Create a feature branch
git checkout -b feature/your-feature

# Make your changes, then push
git push origin feature/your-feature
```

---

## License

This project inherits the open-source license of the original [bitchat](https://github.com/permissionlesstech/bitchat-android) project. The bundled `golib.aar` is compiled from [go-libp2p](https://github.com/libp2p/go-libp2p) which is licensed under the MIT License.

---

## Credits

- **[bitchat](https://github.com/permissionlesstech/bitchat-android)** — The original project this fork is based on. Thank you to the entire bitchat team for building a solid, privacy-first foundation and for making it open source.
- **[libp2p](https://libp2p.io)** — The modular peer-to-peer networking stack powering the P2P/ICE transport layer.
- **[Protocol Labs](https://protocol.ai)** — Creators of libp2p, IPFS, and the broader decentralised web infrastructure that Zemzeme benefits from.
- **[Nostr Protocol](https://nostr.com)** — Simple, open protocol for decentralised social applications.
