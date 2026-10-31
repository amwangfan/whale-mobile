<div align="center">

# whale-mobile

*身无彩凤双飞翼，心有灵犀一点通*
*(No phoenix wings to fly as one — yet minds linked, a touch and it's done.)*

The Whale's body and senses on Android: perception, notifications, VoIP, glasses interaction and device execution.

</div>

---

**whale-mobile** is the Whale's Android phone node. It provides authorized on-device perception (screen, foreground app, notifications) and executes cloud-authorized outputs (notifications, realtime calls, glasses HUD, device actions) from [whale-core](https://github.com/amwangfan/whale-core) — the brain.

Division of labor: **sense, organize, interact and execute on the phone; understand, remember, decide in the Core.** The two sides talk only through the versioned protocol in [`contracts/`](contracts/); neither depends on the other's internals.

## Layout

| Path | Contents | Stack |
|------|----------|-------|
| [`app/`](app/) | Flutter host UI: module toggles, pairing, status, permission wizard | Flutter / Dart |
| [`modules/screenmemo/`](modules/screenmemo/) | ScreenMemo MCP security adapter (screen.context / search / evidence) | Dart + Kotlin |
| [`modules/notifications/`](modules/notifications/) | Android notification post/update/cancel with receipts | Kotlin |
| [`modules/voip/`](modules/voip/) | Self-managed Telecom calls + PCM audio bridge (migrated from whale-io call-bridge) | Java |
| [`transport/`](transport/) | WSS control channel, device identity (Keystore Ed25519), offline queue | Kotlin |
| [`server/`](server/) | Server-side audio bridge + dev reference gateway | Python |
| [`contracts/`](contracts/) | Device-cloud protocol v1: JSON Schemas + golden examples | JSON Schema |
| [`docs/`](docs/) | Architecture, migration, baselines, gate runbooks | Markdown |

## Security & privacy baseline

- Raw screenshots, OCR text, credentials and notification bodies **never leave the device by default**; cross-device access requires explicit scopes.
- Device commands are authenticated, capability-whitelisted, auditable and revocable; the phone re-verifies independently — no arbitrary cloud shell/intents.
- Basic features need no Root; Root is an optional add-on.
- Four honesty levels for capability claims: `unit-tested` / `integration-tested` / `device-tested` / `production-ready`.

## License & credits

- © the whale-mobile authors.
- Screen capture/search capability builds on [ScreenMemo](https://github.com/woijfwefw/ScreenMemo) (AGPL-3.0) with the author's written permission.
- VoIP & foreground-monitor infrastructure originated in the whale-io project.
