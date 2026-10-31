# UI refs — Honor Magic8 Pro (BKQ-AN10)

## Packages
- Default dialer: `com.hihonor.contacts`
- InCallUI: `com.android.incallui` (`/system/priv-app/InCallUI/InCallUI.apk`)
- Overlays: `com.hihonor.newcall.overlay`, `com.newcall`, `com.os.callservice`

## Bugfix (1.8 → 1.8.1)
- Root cause: ConnectionService `startActivity(IncomingCallActivity)` often BAL-blocked on Honor → no answer UI; felt “already answered” when bridge/InCall opened early.
- Fix: high-priority CATEGORY_CALL notification + `setFullScreenIntent` → IncomingCallActivity; keep `setRinging()` until explicit answer; gate AudioBridge until ACTIVE/`answered=true`.
- Icons: `ic_call_answer.xml` / `ic_call_end.xml` on green/red circular buttons.

## Screenshots
- `fake-incoming-icons.png` — 1.8.1-user incoming with phone glyphs

## Gap vs native
- No live native PSTN ring capture (avoided calling strangers). InCallUI APK resources are obfuscated; layout inferred from colors + MagicOS norms.
