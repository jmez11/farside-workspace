# Progress: Farside Workspace

## Current Status (2026-10-06)
- **Zoomed pointer input accuracy & selection targeting**: Pointer input is captured via an unscaled screen-space overlay `Box` (`Modifier.fillMaxSize().pointerInteropFilter`) layered over `AndroidView`. Raw screen coordinates `(eventX, eventY)` are inverted through `mapScreenToDesktop`: `tx = (eventX - panX - w/2f) / zoom + w/2f; ty = (eventY - panY - h/2f) / zoom + h/2f; rdpX = (tx * actualWidth / w); rdpY = (ty * actualHeight / h)`. The right-click hold indicator renders at unscaled screen coordinates directly over the controller pointer ray dot. Dragging selection boxes and right-click positioning are accurately targeted across all zoom levels (1.0x to 3.0x). On-device test `zoomTest` instrumented on Quest across 6 zoom levels (1.0x, 1.25x, 1.42x, 1.5x, 2.0x, 3.0x) confirmed unscaled screen coordinates delivered with zero drops. `inputOnly` passed gesture timing, double-tap tolerance, controller scrolling, and zoomed mapping.
- **Quest keyboard Enter key**: Intercepted `KEYCODE_ENTER` in `RemoteImeEditor.kt` via `Modifier.onPreviewKeyEvent`, dispatching single XT scancode `0x1C` down/up and consuming the key event. Newline characters (`\n`) in `BasicTextField.onValueChange` are debounced within 200ms to guarantee exactly one remote Enter event.
- **App-wide PIN & Credential Protection**: Single App-wide PIN configured in Settings (Tunnels & Global) App Security (salted PBKDF2-SHA256/600k encrypted by Keystore). Credentials feature a "Require PIN" toggle without per-credential PIN fields. Toggling off Require PIN on an existing protected credential enforces entering the App PIN before setting to no. Enabling Require PIN requires an App PIN configured. Sessions with Require PIN enabled gate connection startup on entering the App PIN; unprotected credentials launch directly. Credential edit/delete do not prompt for a PIN. On-device `credentialsOnly` passed on Quest.
- **Spatial Audio**: Audio streams explicitly set `AAudioStreamBuilder_setSessionId(b, AAUDIO_SESSION_ID_ALLOCATE)` in `quest_audio.cpp` ensuring independent spatial audio localization per session window. User verified 3D audio origin in space (2026-10-06). Verified and closed.
- **Display Resolution Setting**: Removed "Customize Display Resolution" setting and submenu from `LauncherUI.kt` per user request.
- **Password Security & Management**: Saved passwords use randomized AES-GCM via Android Keystore (`CredentialManager`); credentials excluded from cloud backup. Edits start with blank password field (blank preserves, typing replaces). User Management supports Edit/Save/Cancel with UUID persistence.
- **Proxy Options**: Limited to None, Cloudflared, SOCKS5, and HTTP (SOCKS5/HTTP mark unavailable and reject RDP). Bundled arm64 `cloudflared` binary with loopback proxy and browser handoff verified on Quest.
- **RDP Certificates & Diagnostics**: Platform X509 trust manager and OpenSSL identity verification with photo-matched warning dialog and persistent leaf fingerprint exceptions.
- **Keyboard Extra Keys**: Separate attached spatial window (76dp/314dp), unshifted desktop surface with `adjustNothing`, synchronized compositor compaction/reveal (`KeyboardPanelResize.kt`), dual `RemoteImeEditor` native focus handoff.

## Verification Evidence
- Build: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` and `adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- On-device test passes on Quest (`172.16.0.106:34001`):
  - `zoomTest`: PASS across 1.0x, 1.25x, 1.42x, 1.5x, 2.0x, 3.0x.
  - `inputOnly`: PASS (pointer gestures, double-tap tolerance, 4-direction controller scroll, zoomed mapping).
  - `credentialsOnly`: PASS (App PIN setup, confirmation, gate, backoff, requirePin toggle, unprotected direct connection).
  - `toolbarOnly`: PASS (toolbar offset/fade/movement/pin, keyboard companion, native focus handoff, desktop click/drag focus).
  - `proxiesOnly`: PASS (proxy choices, legacy decoding, unsupported proxy rejection).
  - Default resize suite: PASS (5 resolutions: 1280x800 -> 1000x600 -> 1600x900 -> 800x1000 -> 1280x800, nonblank captures, session close).

## Known Working Features
- Independent repositionable session activities in Horizon OS; saved connection profiles.
- Spatial audio: 3D audio source correctly localized to each session's own window in space.
- RDP desktop display, zoom/pan controls, mouse input, modifier controls, and soft keyboard.
- App-wide PIN security with per-credential Require PIN gating and unlock verification before removal.

## Stubbed / Backlog
- Local PC discovery: DNS-SD/SMB scanning remains stubbed.
- Cloudflared: persistent settings/service validation and SSH forwarding remain incomplete; saved RDP per-session implementation is present above.
- Phase 2: multiple sessions/mirroring; then RemoteApp, camera redirection, SSH and other proxies.
