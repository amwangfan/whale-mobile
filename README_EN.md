<div align="center">

# whale-mobile

*身无彩凤双飞翼，心有灵犀一点通*

Whale's Android body and senses: perception, notifications, VoIP, glasses interaction and autonomous device control.

[中文](README.md) · [Architecture](docs/ARCHITECTURE.md) · [AI developer guide](docs/AI_DEVELOPMENT_GUIDE.md) · [Status](docs/STATUS.md)

</div>

whale-mobile is the Android node for [whale-core](https://github.com/amwangfan/whale-core). The phone collects signals, presents information, handles real-time interactions and executes actions. Core provides persona, memory, decisions and multi-device orchestration. They communicate through the versioned [contracts/v2](contracts/v2/) protocol.

The goal is not another chat app: Whale gains useful senses and a way to act. A daily-use phone and a dedicated Root phone can have different permission profiles.

## Current capabilities

| Module | Implemented | Notes |
| --- | --- | --- |
| Background and transport | Application-level NodeRuntime, foreground service, network/boot recovery, authenticated WSS (Ed25519 device identity), encrypted offline queue and action receipts | Runs natively without a Flutter screen staying open; receipts distinguish five terminal states (succeeded/failed/timeout/cancelled/unknown) and are idempotent |
| Everyday perception | ScreenMemo MCP, built-in shadow capture, ML Kit OCR, indexing, data-source switching and notification listener | Capture sources and permissions are configurable |
| Notifications | Post/cancel, multiple channels, approval buttons, deep links, pause and receipts | Suitable for a daily phone; a `displayed` receipt means the notification was submitted to the Android notification system, not that the user has seen it |
| Calls | Android Telecom integration, PCM audio bridge and call-state events | Needs platform permissions and a configured bridge |
| Glasses | Card validation, rate limiting, sensitive content kept off the lens, and a pluggable card-bridge interface | Requires a configured glasses bridge; without a connection, cards automatically degrade to phone notifications |
| Device control (daily/root) | UI tree, screenshots, tap/swipe/text input, app launching; the Root flavor adds Shell, file, logcat and screen-recording capabilities | daily needs the Whale accessibility service enabled manually; actions remain subject to paired Core scopes and the emergency stop |

V2 handshake, notifications, queries/receipts, Root execution and the full Bilibili chain (open → search → play → report the title) are verified on real devices — see [Status](docs/STATUS.md). For glasses calendar, cards and follow-up integration see the [Core glasses integration doc](https://github.com/amwangfan/whale-core/blob/main/docs/glasses-joint-integration.md).

## Code map

| Location | Purpose |
| --- | --- |
| [`app/lib/`](app/lib/) | Flutter UI, module controls, permissions and transport client |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/v2/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/v2/) | NodeRuntimeV2, NodeServiceV2, startup/background recovery |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/) | Native agent, capture, glasses, notifications, VoIP and related modules |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/v2/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/v2/) | V2 identity, WSS, offline queue and Flutter bridge |
| [`contracts/v2/`](contracts/v2/) | Device/cloud protocol, schemas and examples (synced with Core) |
| [`server/`](server/) | Server-side audio bridge and integration tools, not bundled in the Android APK |
| [`docs/`](docs/) | Architecture, device tests, deployment and integration records |

Top-level `modules/` and `transport/` hold design material for standalone modules; the shipping APK is assembled under `app/android/app/src/main/`.

## Run and develop

Install Flutter, the Android SDK and a suitable Java toolchain.

```bash
git clone https://github.com/amwangfan/whale-mobile.git
cd whale-mobile
pip install -r requirements-test.txt
python3 tests/contract_validate.py
cd app
flutter pub get
flutter test
flutter build apk --debug --flavor daily
```

The app generates a device identity, pairs with Core and lets you enable modules. See [Magic8 Pro setup](docs/SETUP-MAGIC8.md) for deployment on a daily phone and [Status](docs/STATUS.md) for device-test coverage.

## Structure and tech stack

| Layer | Code | Technology |
| --- | --- | --- |
| App screens | `app/lib/features/`, `app/lib/modules/` | Flutter / Dart |
| Device background | `app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/v2/` | Kotlin, Android Service, Lifecycle |
| Transport and cache | `app/android/.../transport/v2/` | V2 WSS, Ed25519, encrypted SQLite offline queue |
| Perception and indexing | `app/android/.../modules/capture/`, ScreenMemo client | Android Accessibility, ML Kit OCR, SQLite / FTS |
| Automation | `app/android/.../modules/agent2/` | UI tree, accessibility actions, Root Shell |
| Calls and voice | `app/android/.../modules/voip/`, `server/audio-bridge/` | Android Telecom, PCM/TCP |
| Notifications and glasses | `app/android/.../modules/notification2/`, `app/android/.../modules/glasses/` | Android notifications, pluggable device bridge |

```bash
cd app
flutter build apk --debug --flavor daily
flutter build apk --debug --flavor root
cd android
./gradlew :app:testDailyDebugUnitTest :app:testRootDebugUnitTest
```

## Device permissions

- **Daily-use phone:** perception uses the selected data sources, notification/capture permissions and device scopes. Actions that need approval follow the actual Core and on-device grants; routine notifications need no extra blocking.
- **Daily-use phone (accessibility):** after manually enabling the "Whale screen perception and control" accessibility service in Android settings, the phone supports structured UI observation, tap/swipe, setting text on the focused input, back/home/recents and app launching — no Root needed. Only launcher-visible apps can be listed; some special keys require Root/ADB.
- **Dedicated Root phone:** provides Root Shell, input commands, file, logcat and recording capabilities, with normal UI operations preferring Root; without Root it uses the enabled accessibility service. The emergency stop remains.
- **Action outcomes:** rely on terminal receipts and device feedback. Timeout, offline and unknown are different outcomes from success; do not blindly repeat an action with side effects.

## Planned

- RayNeo iO real on-lens display: glasses-bridge integration and on-device verification
- End-to-end verification of continuous autonomous app operation
- Fallback-chain degradation hints for calls and approval wiring for in-call tools
- Long-run battery baselines and broader device-compatibility coverage

## CI (on demand)

The [ci workflow](.github/workflows/ci.yml) runs via manual `workflow_dispatch`: choose `contracts`, `app`, `secret-scan` or `all` under **Actions → ci → Run workflow**.

## Credits and licenses

Capture/search capabilities draw on [ScreenMemo](https://github.com/woijfwefw/ScreenMemo) (AGPL-3.0); part of the VoIP and foreground-monitoring code originates from whale-io. Third-party code and assets remain subject to their respective licenses.
