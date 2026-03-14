# 🦴 Bony

> A bare-bones, Google-free Nostr client for Android. Fast, lean, and extensible via plugins.

**Name is a placeholder** — Bony captures the bare-bones philosophy for now.

---

## 🧘 Philosophy

Do the minimum well. No analytics, no tracking, no Google Services. Authentication is always delegated to an external signer — the app never touches your private key. Features you don't want don't ship with the app; they're plugins you install by choice.

---

## ⚡ Core Features

- Multi-account support with fast account switching
- External signer authentication: [Amber](https://github.com/greenart7c3/Amber) (NIP-55) and nsecBunker (NIP-46)
- Android Keystore as a local fallback (last resort only)
- Push notifications via [UnifiedPush](https://unifiedpush.org/) (no FCM)
- Distribution via F-Droid and GitHub Releases

---

## 🛠️ Tech Stack

| Concern | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| WebSocket | OkHttp |
| Database | Room (SQLite) |
| Crypto | Bouncy Castle / tink (secp256k1) |
| DI | Hilt |
| Preferences | DataStore |
| Min SDK | API 26 (Android 8.0) |

No Firebase. No Google Play Services.

---

## 📡 NIP Support (Core)

| NIP | Description |
|---|---|
| NIP-01 | Basic protocol, event model |
| NIP-02 | Contact lists |
| NIP-19 | bech32 entities (npub, note, nprofile...) |
| NIP-44 | Versioned encryption |
| NIP-46 | Nostr Connect / nsecBunker |
| NIP-55 | Android signer application (Amber) |
| NIP-65 | Relay list metadata |

Everything else (DMs, zaps, communities, etc.) is a plugin.

---

## 🧩 Plugin System

Bony is intentionally minimal. Extended functionality is delivered via plugins — separate APKs that implement a defined AIDL interface. The host app binds to plugin services; Android enforces process isolation.

### 🔐 Plugin Permissions

Plugins declare only what they need:

| Permission | Description |
|---|---|
| `READ_EVENTS` | Read cached events from the feed |
| `READ_PROFILE` | Access contact/profile metadata |
| `INJECT_UI` | Render UI into defined host slots |
| `PUBLISH_EVENT` | Request to publish an event (proxied through the app's signer — plugin never sees keys) |
| `READ_DMs` | Access decrypted DMs (high-trust, explicit user grant required) |

### 📦 Plugin Registry

Plugins are discovered through an in-app registry. Two approaches are under consideration:

- **Option A (GitHub JSON):** A curated `registry.json` in a public repo. PRs = vetting. Simple to bootstrap.
- **Option B (Nostr-native):** A specific event kind for plugin listings, published by vetted curator pubkeys. Stacks are playlist-style events grouping related plugins.

Option B is philosophically consistent with Nostr. Option A is simpler to start with. Likely: start with A, migrate to B.

### 🥞 Stacks

Stacks are curated plugin bundles for common use cases, e.g.:

- **Newb Stack** — DMs, zaps, image display
- **Power User Stack** — relay management, advanced filters, NIP-72 communities

Stacks lower the barrier for new users while keeping the core app lean.

### 🛡️ Security Model

- Plugins **never** receive private keys or signed events
- `PUBLISH_EVENT` submits an unsigned template → app signer signs → app broadcasts
- Plugin identity is verified by package name + signing certificate
- Unverified (sideloaded) plugins trigger a prominent warning
- UI-injecting (WebView) plugins run with strict Content-Security-Policy, no shared storage

---

## 🚀 Getting Started

```bash
# Clone and open in Android Studio — the Gradle wrapper will be generated automatically.
# Min SDK: API 26 (Android 8.0). No Google Play Services required.
```

## 📁 Repository Layout

```
bony/
├── app/
│   └── src/main/
│       ├── kotlin/social/bony/
│       │   ├── BonyApp.kt          # Hilt application entry point
│       │   ├── MainActivity.kt
│       │   ├── nostr/
│       │   │   ├── Event.kt        # NIP-01 event model + UnsignedEvent
│       │   │   ├── EventKind.kt    # Known event kind constants
│       │   │   ├── Tag.kt          # Tag wrapper + helpers
│       │   │   ├── Filter.kt       # Subscription filters
│       │   │   └── Crypto.kt       # BIP-340 Schnorr verification
│       │   └── ui/
│       │       ├── BonyNavHost.kt
│       │       ├── FeedScreen.kt   # Placeholder
│       │       └── theme/
│       └── res/
├── plugin-api/       # (planned) AIDL interfaces for plugin developers
├── plugins/
│   └── example/     # (planned) Reference plugin implementation
└── docs/
    └── plugin-dev-guide.md  # (planned)
```

---

## 🤝 Contributing

Plugins extend Bony. Core PRs should keep the app lean — if a feature can be a plugin, it should be.

---

## 📄 License

MIT
