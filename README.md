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

## 📡 NIP Compatibility

Legend: ✅ Supported &nbsp;|&nbsp; 🚧 Partial &nbsp;|&nbsp; 🔌 Plugin &nbsp;|&nbsp; planned &nbsp;|&nbsp; — N/A

### Built into the app

| NIP | Name | Status | Notes |
|---|---|---|---|
| [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md) | Basic protocol & event model | ✅ | Event, Filter, relay WebSocket pool, kind-0 profile metadata |
| [NIP-02](https://github.com/nostr-protocol/nips/blob/master/02.md) | Contact lists | ✅ | Follow list drives the home feed |
| [NIP-19](https://github.com/nostr-protocol/nips/blob/master/19.md) | bech32-encoded entities | ✅ | npub, note encode/decode |
| [NIP-44](https://github.com/nostr-protocol/nips/blob/master/44.md) | Versioned encryption | ✅ | ChaCha20 + HMAC-SHA256, HKDF |
| [NIP-46](https://github.com/nostr-protocol/nips/blob/master/46.md) | Nostr Connect (nsecBunker) | 🚧 | Signer implemented; onboarding UI pending |
| [NIP-55](https://github.com/nostr-protocol/nips/blob/master/55.md) | Android signer (Amber) | ✅ | Sign, encrypt, decrypt via intent |

### NIPs with plugins available

*No plugins exist yet. This section will list community-built plugins as they are published.*

### Common NIPs

| NIP | Name | Status | Notes |
|---|---|---|---|
| [NIP-04](https://github.com/nostr-protocol/nips/blob/master/04.md) | Encrypted direct messages (legacy) | 🔌 | Deprecated; widely used |
| [NIP-05](https://github.com/nostr-protocol/nips/blob/master/05.md) | DNS-based identifiers | planned | Verification badge on profiles |
| [NIP-09](https://github.com/nostr-protocol/nips/blob/master/09.md) | Event deletion | planned | |
| [NIP-10](https://github.com/nostr-protocol/nips/blob/master/10.md) | Text note references & replies | planned | Thread context |
| [NIP-11](https://github.com/nostr-protocol/nips/blob/master/11.md) | Relay information document | planned | Relay metadata / limits |
| [NIP-17](https://github.com/nostr-protocol/nips/blob/master/17.md) | Private direct messages | 🔌 | Replaces NIP-04 |
| [NIP-18](https://github.com/nostr-protocol/nips/blob/master/18.md) | Reposts | planned | kind 6 |
| [NIP-21](https://github.com/nostr-protocol/nips/blob/master/21.md) | `nostr:` URI scheme | planned | Deep links from other apps |
| [NIP-25](https://github.com/nostr-protocol/nips/blob/master/25.md) | Reactions | 🔌 | Likes, emoji reactions |
| [NIP-36](https://github.com/nostr-protocol/nips/blob/master/36.md) | Sensitive content | planned | Content warnings |
| [NIP-42](https://github.com/nostr-protocol/nips/blob/master/42.md) | Relay authentication | planned | AUTH challenge/response |
| [NIP-51](https://github.com/nostr-protocol/nips/blob/master/51.md) | Lists | 🔌 | Mute lists, pin lists, bookmarks |
| [NIP-57](https://github.com/nostr-protocol/nips/blob/master/57.md) | Lightning zaps | 🔌 | |
| [NIP-65](https://github.com/nostr-protocol/nips/blob/master/65.md) | Relay list metadata | planned | Outbox model |

### Uncommon NIPs

| NIP | Name | Status | Notes |
|---|---|---|---|
| [NIP-13](https://github.com/nostr-protocol/nips/blob/master/13.md) | Proof of work | — | Anti-spam; relay-dependent |
| [NIP-23](https://github.com/nostr-protocol/nips/blob/master/23.md) | Long-form content | 🔌 | Articles / blogs |
| [NIP-40](https://github.com/nostr-protocol/nips/blob/master/40.md) | Expiration timestamp | planned | |
| [NIP-47](https://github.com/nostr-protocol/nips/blob/master/47.md) | Wallet Connect | 🔌 | NWC; pay invoices in-app |
| [NIP-50](https://github.com/nostr-protocol/nips/blob/master/50.md) | Search | 🔌 | Relay-dependent full-text search |
| [NIP-52](https://github.com/nostr-protocol/nips/blob/master/52.md) | Calendar events | 🔌 | |
| [NIP-53](https://github.com/nostr-protocol/nips/blob/master/53.md) | Live activities | 🔌 | Streams, live audio |
| [NIP-58](https://github.com/nostr-protocol/nips/blob/master/58.md) | Badges | 🔌 | |
| [NIP-59](https://github.com/nostr-protocol/nips/blob/master/59.md) | Gift wrap | planned | Used by NIP-17 DMs |
| [NIP-72](https://github.com/nostr-protocol/nips/blob/master/72.md) | Moderated communities | 🔌 | Reddit-style communities |
| [NIP-84](https://github.com/nostr-protocol/nips/blob/master/84.md) | Highlights | 🔌 | |
| [NIP-89](https://github.com/nostr-protocol/nips/blob/master/89.md) | Recommended app handlers | 🔌 | |
| [NIP-90](https://github.com/nostr-protocol/nips/blob/master/90.md) | Data vending machines | 🔌 | AI/compute marketplace |
| [NIP-94](https://github.com/nostr-protocol/nips/blob/master/94.md) | File metadata | 🔌 | |
| [NIP-96](https://github.com/nostr-protocol/nips/blob/master/96.md) | HTTP file storage | 🔌 | Image/file uploads |
| [NIP-99](https://github.com/nostr-protocol/nips/blob/master/99.md) | Classifieds | 🔌 | |

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
│       │       ├── feed/           # FeedScreen, FeedViewModel, NoteCard
│       │       ├── onboarding/     # OnboardingScreen, OnboardingViewModel
│       │       ├── components/     # AccountSwitcher
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
