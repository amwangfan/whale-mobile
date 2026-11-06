<div align="center">

# whale-mobile

*身无彩凤双飞翼，心有灵犀一点通*

Whale's Android body and senses: perception, notifications, VoIP, glasses interaction and autonomous device control.

[中文](README.md) · [Architecture](docs/ARCHITECTURE.md) · [AI developer guide](docs/AI_DEVELOPMENT_GUIDE.md) · [Status](docs/STATUS.md)

</div>

whale-mobile is the Android node for [whale-core](https://github.com/amwangfan/whale-core). The phone collects signals, presents information, handles real-time interactions and executes actions. Core provides persona, memory, decisions and multi-device orchestration. They communicate through the versioned [contracts/v1](contracts/v1/) protocol.

The goal is not another chat app: Whale gains useful senses and a way to act. A daily-use phone and a dedicated Root phone can have different permission profiles.

## Current capabilities

| Module | Implemented | Notes |
| --- | --- | --- |
| Background and transport | Application-level NodeRuntime, foreground service, network/boot recovery, authenticated WSS, encrypted offline queue and action receipts | Does not depend on a Flutter screen staying open |
| Everyday perception | ScreenMemo MCP, built-in shadow capture, ML Kit OCR, indexing, data-source switching and notification listener | Capture sources and permissions are configurable |
| Notifications | Post/cancel, device status, pause and receipts | Suitable for a daily phone |
| Calls | Android Telecom integration and PCM audio bridge | Needs platform permissions and a configured bridge |
| Glasses | RayNeo iO notification/HUD transport and acknowledgement plumbing | Actual on-glass display depends on device integration |
| Dedicated AI phone | UI tree, screenshots, tap/swipe/text, app launching, media controls, persistent Root Shell sessions, output reading and emergency stop | Requires Root access, both local agent toggles and paired Core agent scopes |

Core has an Android agent-loop implementation with simulated tests. Production decision wiring and the full Bilibili flow on a Root phone still require end-to-end verification.

## Code map

| Location | Purpose |
| --- | --- |
| [`app/lib/`](app/lib/) | Flutter UI, module controls, permissions and transport client |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/) | NodeRuntime, NodeService, startup/background recovery |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/) | Native agent, capture, glasses, notifications, VoIP and related modules |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/) | Identity, WSS, offline queue and Flutter bridge |
| [`contracts/v1/`](contracts/v1/) | Device/cloud protocol, schemas and examples |
| [`server/`](server/) | Server-side audio bridge and integration tools, not bundled in the Android APK |
| [`docs/`](docs/) | Architecture, device tests, deployment and migration |

Top-level `modules/` and `transport/` also contain design or standalone module material; the primary Android runtime implementation is under `app/android/app/src/main/`.

## Run and develop

Install Flutter, the Android SDK and a suitable Java toolchain.

```bash
git clone https://github.com/amwangfan/whale-mobile.git
cd whale-mobile
python3 tests/contract_validate.py
cd app
flutter pub get
flutter test
flutter build apk --debug
```

The app generates a device identity, pairs with Core and lets you enable modules. See [Magic8 Pro setup](docs/SETUP-MAGIC8.md) for deployment and [status](docs/STATUS.md) for device-test coverage.

## Device permissions

- **Daily-use phone:** perception uses selected capture sources and Android permissions. Actions follow the actual Core/device grants; routine notifications need no extra artificial approval.
- **Dedicated Root phone:** enable "Device Agent" and "Persistent Root Shell" in the app to allow a paired Core to observe, control the UI and use Root Shell. There is no arbitrary command allowlist; the phone has an emergency stop.
- **Action outcomes:** rely on terminal receipts. Success, timeout, offline and unknown are different outcomes; do not blindly repeat an action with side effects.

## CI (on demand)

The [ci workflow](.github/workflows/ci.yml) runs via manual `workflow_dispatch`: choose `contracts`, `app`, `secret-scan` or `all` under **Actions → ci → Run workflow**. A merge is not a substitute for a real build or test run.

## Credits and licenses

Capture/search capabilities draw on [ScreenMemo](https://github.com/woijfwefw/ScreenMemo) (AGPL-3.0); part of the VoIP and monitoring lineage comes from whale-io. Third-party code and assets remain subject to their respective licenses.
