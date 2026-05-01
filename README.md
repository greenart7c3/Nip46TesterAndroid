# NIP-46 Tester

An Android app for exercising every side of [NIP-46](https://github.com/nostr-protocol/nips/blob/master/46.md)
("Nostr Connect") — the client, the remote signer (bunker), and the relay
underneath. Built with Jetpack Compose and
[Quartz](https://github.com/vitorpamplona/amethyst/tree/main/quartz) 1.08.0.

## What it does

Three tabs, each driving a different role in the protocol:

### Client

Connect to a remote signer and exercise the wire methods.

- **`bunker://` mode** — paste a bunker URL, hit *Connect*. The client
  encrypts requests with NIP-44 (or NIP-04), signs a kind 24133 event
  addressed to the bunker's pubkey, publishes, and waits for the reply.
- **`nostrconnect://` mode** — generate a `nostrconnect://` URL, share it,
  and listen. The bunker's pubkey is discovered from the first decryptable
  inbound event, after which all the methods work normally.

Buttons cover every standard NIP-46 method:

| | |
| --- | --- |
| `connect` | `ping` |
| `get_public_key` | `sign_event` |
| `nip44_encrypt` / `nip44_decrypt` | `nip04_encrypt` / `nip04_decrypt` |
| `switch_relays` | (custom method) |

The custom-method box sends any method name + newline-separated string
params through the same code path, so future or non-standard methods can
be exercised without recompiling.

### Bunker

A minimal remote signer.

- Configure relays / private key / secret, hit *Start bunker*. The app
  prints the `bunker://` URL for sharing.
- Listens for kind 24133 events tagging its pubkey, decrypts them, and
  answers `connect`, `ping`, `get_public_key`, `get_relays`,
  `sign_event` (echo — see "Caveats"), `nip44_encrypt/decrypt`,
  `nip04_encrypt/decrypt`, and `switch_relays`.
- Has a paste field for `nostrconnect://` URLs: on submit it joins the
  URL's relays, subscribes, and publishes a connect ack to the client.
- `switch_relays` actually unsubscribes from dropped relays and
  subscribes on new ones.

### Relay test

Probes whether a relay can carry NIP-46 traffic.

It publishes a real signed and NIP-44–encrypted kind 24133 event from
keypair A and listens for it via a `#p`-tagged subscription on keypair B.
The verdict reports both:

- `accepted` — relay returned `OK true` for the publish, and
- `routed` — the subscription actually received the event.

A relay that accepts the publish but silently drops ephemeral kinds (a
common failure mode) fails the routing check.

## Stack

- Kotlin 2.3.20, AGP 9.2.0, Gradle 9.4.1, JDK 21
- compileSdk / targetSdk 36, minSdk 26
- Jetpack Compose (BOM 2026.04.01) + Material 3
- [Quartz 1.08.0](https://central.sonatype.com/artifact/com.vitorpamplona.quartz/quartz)
  — `KeyPair`, `NostrSignerInternal` (signing + NIP-04/NIP-44)
- OkHttp 4.12 — relay WebSockets
- kotlinx.serialization 1.10.0, kotlinx.coroutines 1.10.2

## Building

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

You'll need an Android SDK with platform 36 / build-tools 36.0.0 and
JDK 17 or later (21 is what CI uses). Point Gradle at your SDK via
`local.properties` (`sdk.dir=/path/to/android-sdk`) or the
`ANDROID_HOME` env var.

## Project layout

```
app/src/main/java/com/greenart7c3/nip46tester/
├── MainActivity.kt              tab host
├── nip46/
│   ├── BunkerUrl.kt             bunker:// + nostrconnect:// parsers
│   ├── CryptoAdapter.kt         thin wrapper over Quartz crypto
│   ├── Nip46Bunker.kt           remote-signer state machine
│   ├── Nip46Client.kt           client state machine, both flows
│   ├── Nip46Models.kt           Nip46Request / Nip46Response / NostrEvent
│   ├── Nip46RelayTester.kt      kind 24133 publish-and-route probe
│   └── RelayConnection.kt       OkHttp WebSocket + Nostr framing
└── ui/
    ├── screens/{Client,Bunker,RelayTest}Screen.kt
    └── theme/Theme.kt
```

## Caveats

- The bunker's `sign_event` is intentionally an echo — it returns the
  unsigned event back rather than producing a real signature. Swap in a
  real `signer.sign(...)` call where the `// tester echo` comment marks
  it if you want a signing bunker.
- The default relay is `wss://nos.lol`. Anything you publish on a public
  relay is, well, public — even ephemeral kinds may be observed.
- Cleartext WebSockets (`ws://`) are disabled by the manifest. Use
  `wss://`.

## License

[MIT](LICENSE)
