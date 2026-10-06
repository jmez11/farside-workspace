# Architecture: MultiDesk Explorer

## Product Vision
**MultiDesk Explorer** is a native RDP and SSH client for Meta Quest (Horizon OS). Its defining feature is that every remote session and RemoteApp is its own spatial window, which can be placed freely in the standard Home/passthrough environment without entering a custom VR world. It is designed as a plain-language, no-bells-and-whistles productivity utility.

## Module Map
1. **App / UI Layer (`app`)**: Jetpack Compose and Meta VR Layout SDK for Compose Spatial Windows. Manages the connection launcher (main panel) and spawns new Spatial Windows for each session.
2. **Session Manager**: Coordinates active connections, handles panel lifecycle (spawning/closing windows), and routes input. Responsible for deciding whether to spawn a new FreeRDP instance or mirror an existing session's video decode.
3. **RDP Core (`freerdp-jni`)**: JNI bridge to FreeRDP 3.27.1+. Handles session lifecycle, video stream decoding (MediaCodec AVC/H.264), input event injection, RAIL window mapping, and camera redirection (`rdpecam`).
4. **SSH / Terminal Core (`ssh-core`)**: SSHJ for secure transport, combined with Termux's GPLv3 terminal-emulator stack for best-in-class VT100/xterm emulation.
5. **Settings & Storage**: Encrypted credential storage via Android Keystore (biometric/PIN unlock), saved host configurations, and user preferences (e.g., camera choice).
6. **Network & Proxy Subsystem**: Manages spawning local child processes (such as a bundled `cloudflared` binary or custom proxy commands). Supports both ephemeral tunnels tied to a session's lifecycle and persistent background tunnel services (via Android Foreground Services).

## Windowing Model
- **Spatial Windows**: Built using the VR Layout SDK. The main app panel acts as a connection manager. Connecting to a host spawns a new independent Spatial Window.
- **RAIL Support**: Each RemoteApp published via RAIL appears as its own floating window, with server-authoritative z-order and transparency support.
- **Lifecycle**: Closing a session terminates its connection. FreeRDP's event-loop exit reports Error Info, last-error text or a disconnect ultimatum through a per-bridge JNI callback. An established session shows a non-dismissible Disconnected dialog with the available reason; OK closes its Activity. Local teardown suppresses that callback, and ended sessions do not reconnect on surface replacement. Mirrored-window shutdown remains planned.
- **Remembered placement**: `SessionWindowPlacement.kt` stores observed position+quaternion per profile ID/session ordinal in app-private `session_window_positions` preferences; stop/destroy flush the final pose. The launcher allocates the lowest free ordinal, checking active windows/tasks (including restored tasks), and requests the saved pose with Horizon ActivityOptions. The Activity also makes one unprivileged transaction request on first resume for direct launches. Home may adjust/deny requests; absent APIs or rejected options fall back to ordinary placement. No global window-management permission is requested. Two simultaneous windows of one profile keep separate slots, reused when reopened. Room/recenter continuity is not guaranteed. [Meta transaction policy](https://developers.meta.com/vr/reference/metavr-os-jsdk/v207/class_metavr_app_volumetricwindow_volumetric_window_manager/).

## Input Pipeline
- **Quest Controllers**: Raycast to window coordinates and translated into RDP mouse events.
- **RDP gestures**: Controller trigger/hand pinch arrive as Android touch events. The clockwise square stays hidden for 0.5 seconds, then draws over the next second. A stationary hold of at least one second sends right-click on release; short taps click left and motion beyond touch slop starts left drag. `inputOnly` checks indicator timing at the delay/drawing/completion boundaries.
- Nearby second touch taps within Android's double-tap timeout reuse the first click position within twice touch slop, allowing ray jitter while the remote OS still owns double-click recognition. Drag/hold/mouse input and cancellation clear the candidate; drag threshold stays unchanged. `inputOnly` checks diagonal/radius/time boundaries and cancellation.
- **Keyboard**: Meta IME plus `RemoteExtraKeys.kt`: Win, Shift, Ctrl, Alt, AltGr, Del, Esc, Tab, 123. First five latch RDP down/up with teal held indicators and release on IME closure. `123` opens the second level below the strip and changes to `ABC` to close it: F1-F12, right-click at the last remote pointer position, Print Screen, Backspace, keypad Enter/arithmetic, and Home/End/Page/Insert/Delete/Enter/arrows. Number Keys swaps the function bank for digits/decimal; Function Keys restores it. Expansion preserves held modifiers; narrow panels scroll. Extended RDP scancodes distinguish right Alt/Win/navigation/keypad keys.
- RDP surface touches request container focus only when the software keyboard is closed. While open, the hidden IME editor retains focus through remote taps, holds, and drags; explicit toolbar/IME dismissal still removes it and restores desktop focus.

## Camera Redirection (`rdpecam`)
- Uses the Horizon OS Passthrough Camera API (`HEADSET_CAMERA` + `CAMERA` permissions).
- **Primary Source**: Avatar virtual webcam (front/selfie camera). This is the default so remote Teams/Zoom participants see the user's Meta Avatar.
- **Secondary Source**: Outward room/passthrough cameras.
- **UX**: An in-app camera picker allows users to choose the camera source per session.

## Security
- **RDP certificates**: FreeRDP `VerifyX509Certificate` uses external management; ignore-certificate and legacy RDP security fallback are disabled. JNI sends the full PEM chain to `RdpCertificateVerifier`; Android's default X509 trust manager checks server trust/validity and OpenSSL checks DNS/IP identity against the original profile host (not a tunnel's loopback endpoint).
- Unverifiable certificates show Connect/Cancel, an unchecked remember option, reasons and expandable issuer/dates/SHA-256 details. App-private `rdp_certificates` preferences persist the exact leaf fingerprint and PEM/details per case-insensitive host+port. Matching saved exceptions are reused only after attempting verification; a changed invalid certificate prompts again and shows the prior fingerprint. Valid replacements pass without an exception. Session-only Connect never updates trust; Cancel/dismiss rejects. Session disposal cancels a pending confirmation before worker teardown.
- **Credentials**: Stored in the Android Keystore.
- **NLA/CredSSP**: FreeRDP must be built with `WITH_INTERNAL_MD4`, `WITH_INTERNAL_RC4`, and `WITH_INTERNAL_MD5` to ensure NLA succeeds.
- **SSH**: Trust On First Use (TOFU) host-key verification.

## RDP Audio
- FreeRDP owns `rdpsnd`/`audin` negotiation and DSP; `quest_audio.cpp` supplies Android AAudio PCM devices. Static `rdpdr`/`rdpsnd` load through `LoadChannels`; `audin` loads only for a microphone-enabled, permitted connection. Playback always redirects to Quest, independent of the legacy saved sound-redirection field.
- Setup checks `RECORD_AUDIO`; enabling the mic requests permission and denial leaves it off. A saved mic-enabled session waits for the permission result before creating its surface/connection, then connects without capture if denied. This override does not rewrite the saved profile.
- Toolbar speaker mute always appears; mic mute appears for effective microphone-enabled sessions. Mute sends silence per session. The mic turns red only while unmuted and the capture backend receives samples after a remote recording request; channel registration alone does not indicate recording. Capture stops on channel closure/session teardown.
- Confirmed unsupported capture shows a white mic with a red circle/slash; selecting it opens the host-support message and leaves mute unchanged. Host evidence is read from app-private `files/rdp-host-capabilities.json`, matching the profile host against verified aliases and a successful Windows `TSAudioCaptureAllowed=0` result. Missing evidence/idle capture stays unknown, with normal mute controls; actual capture clears a stale blocked state for that session. RDP has no general idle-host capture-support advertisement, so silent refusal cannot be automatically classified from a timeout.

## Threading Model & Performance
- FreeRDP owns protocol decoding and GDI painting. A render worker copies the completed RGBA framebuffer under FreeRDP's update lock, releases the lock, then posts to Android. Producer buffer geometry follows actual desktop dimensions; copies honor source and destination stride.
- A full-frame TextureView scales immediately with its containing window. After a 300 ms debounce, DisplayControl requests matching remote resolution. Zoom/pan are Compose transforms; pointer coordinates map from the same local view bounds to desktop pixels.
- Home panel pose observation uses the headset framework's activity-scoped volumetric window listener, loaded reflectively for Android compatibility. Position/orientation changes reveal the session toolbar; registration is removed on Activity destruction. IME insets control the keyboard modifier row and hidden text-field lifecycle.
- Surface recreation replaces the native surface while retaining the connection. Session disposal joins native network/render workers before freeing the context.
- **Codecs:** FreeRDP 3.x static channels load through `LoadChannels` and a custom addin provider. Hardware AVC444v2 decoding through Qualcomm's low-latency MediaCodec decoder is verified on Quest 3. AVC444 reconstruction and YUV/RGBA conversion remain CPU work; Progressive/RemoteFX software decoders remain available.
- **Codec logging:** `RdpContext` owns the original SurfaceCommand handler and 16 atomic throttle counters, constructed/destructed with the native context and reset on GFX channel connection. GDI owns `RdpgfxClientContext::custom` as `rdpGdi*`; the wrapper recovers its session through `gdi->context`. Codec log lines include the session pointer.
- **MediaCodec implementation:** `app/src/main/cpp/h264_mediacodec.c` replaces the upstream experimental backend through the root CMake configuration; the FreeRDP submodule stays unchanged. Supports padded I420/NV12 output, bounded waits, and lazy decoder allocation. Only I420 output has been exercised on-device.
- **Build:** arm64 only; native debug builds retain symbols and use `-O2` so codec/conversion performance is representative.
- **Target:** 2-4 concurrent sessions on Quest 3. Background throttling remains planned.

---

## Development and Device Validation
- Controller scrolling: `SessionActivity.dispatchGenericMotionEvent` routes RDP joystick X/Y and Android scroll axes through `RdpControllerScroll` to FreeRDP vertical/horizontal wheel flags at the last remote pointer. Joystick uses a 0.2/device deadzone and 100ms repeat; neutral/pause/focus loss stop it. Remote applications control wheel targeting. `inputOnly` covers synthetic four directions, diagonal repeat, deadzone, stop and unrelated-source rejection; physical Horizon delivery and remote application wheel effects remain manual checks.
- Build/cache/scratch outputs are disposable; the 2026-10-05 cleanup removed local `build/verification/` evidence. Historical validation outcomes remain in `PROGRESS.md`; rerun the relevant harness to regenerate artifacts.
- Store artwork lives in root `assets/`, separate from packaged Android resources. Manifest icon/roundIcon reference `app/src/main/res/mipmap-nodpi/ic_launcher.png`, an exact copy of `assets/spatialized/farside-tile-preview-512x512.png`. Validate the installed APK's launcher path with `aapt dump badging` and compare packaged image bytes to that source; Home presentation still requires visual inspection.
- Fresh checkout: apply the checkpoint's existing Termux edit with `git -C termux-app apply ../tools/termux-terminal-session.patch` if `TerminalSession` is still final; this workspace already has it applied.
- Default workflow for app changes: command-line build, ADB install on the connected Quest, relevant on-device regression tests, log/screenshot inspection, fixes, and rerun of affected checks. Extend the harness when the requested behavior needs new coverage.
- Build app and test APKs: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`. Install each with `adb install -r` from `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- Harness: `app/src/androidTest/java/com/multidesk/explorer/RdpResizeInstrumentation.kt`. Run `adb shell am instrument -w com.multidesk.explorer.test/com.multidesk.explorer.RdpResizeInstrumentation`; use `-e inputOnly true` or `-e toolbarOnly true` before the component for focused checks. RDP/toolbar runs use the first saved RDP profile and require its server to be reachable.
- Coverage: real task resizing, texture bounds/nonblank frame captures, session shutdown, pointer gesture routing, toolbar visibility/pinning, and panel pose changes. `toolbarOnly` also checks modifier latching/reset after dismissal, 123/ABC expansion/collapse, second-level key clicks and Number Keys/Function Keys switching, plus the IME editor's actual Compose focus callback through remote touch down/up, hold, and drag, plus dismissal/reopening. When Horizon rejects automatic keyboard display, visibility checks use synthetic IME insets; physical trigger/pinch and floating-keyboard behavior still need headset validation.
- Captures: app cache `rdp-verification-<width>x<height>.png` and `keyboard-{expanded,numbers,collapsed}.png` (`toolbarOnly`, PixelCopy including Compose); collect logs and review artifacts under `build/verification/`. `tools/rdp-motion-probe.ps1` provides a temporary moving window on the Windows RDP host for decode/render observation.
- Record outcomes and limits in `PROGRESS.md`; a successful build alone does not establish device behavior.
- Lifecycle harness: `-e lifecycleMode disconnect|placement` before the instrumentation component. `disconnect` temporarily forwards the saved RDP host through an app-local TCP proxy, accepts its certificate for that session only, resets the sockets, checks the reason/OK-close behavior, and verifies normal closure has no remote-disconnect callback. `placement` uses a temporary unreachable test profile to create two actual Home windows, shifts them ?15cm with shell permission only during setup, drops that permission, and checks saved slots and unprivileged reopen requests. Preferences/profiles are restored; crash recovery backup stays only in app-private cache. Captures/results: cache `rdp-disconnected.png`, `window-placement-results.json`. These tests do not exercise physical gestures, real wall placement, recentering or play-space changes.
- Certificate harness: `-e certificateMode policy|live` before the instrumentation component. `policy` uses public PEM fixtures under `app/src/androidTest/assets/certificates/` for actual Android trust-manager and OpenSSL hostname checks, session-only/persistent/changed/rejected exceptions, host+port isolation, expired/malformed certificates and cancellation. Its test CA exists only in an in-memory test trust manager. `live` checks the first RDP profile's real TLS/JNI/dialog path, Cancel, session-only Connect, saved reconnect and a simulated stale fingerprint; restores prior exception preferences. Other RDP harness modes require a reachable profile with a valid certificate or a user-saved exception. Dialog capture: app cache `rdp-certificate-prompt.png` (PixelCopy).
- Audio harness: add `-e audioMode setup-deny|setup-grant|session-deny|session-grant|session-off|playback|stream` to the instrumentation command (one mode per run). Permission tests require `adb shell pm revoke com.multidesk.explorer android.permission.RECORD_AUDIO` and `adb shell pm clear-permission-flags com.multidesk.explorer android.permission.RECORD_AUDIO user-set user-fixed` before each denied-permission run. Restore the original permission afterward.
- Run `tools/rdp-audio-probe.ps1` in the Windows session during `playback`/`stream`; it plays a quiet bounded tone and requests recording, discarding captured audio. Playback checks actual AAudio writes, silence while muted, and resumed samples after unmute. `stream` requires an available Windows recording endpoint; packet counts do not establish audible quality or remote recording fidelity.
- Missing Windows recording device: run `tools/rdp-recording-diagnostics.ps1` during the active RDP session. Default mode is read-only; `-QuestSerial <adb-serial>` also copies verified host evidence into the debug app for the unsupported icon (reopen the session to load it). Re-run after a host configuration/edition/address change. It reports edition, endpoint enumerator, capture policy, the distinct `TSAudioCaptureAllowed` edition policy, services, and WinMM devices. Current Home/RDP Wrapper host returns 0 for the edition policy even when listener policy allows capture.
- Icon checks: `-e audioMode blocked-ui` uses a synthetic unsupported state and validates overlay pixels/dialog/no mute action; `host-unsupported` uses the real host report and RDP session. `host-unknown` temporarily supplies evidence for a different host, verifies ordinary mic controls after 15 seconds of idle capture, then restores evidence. Microphone permission must be granted for the two host checks.

## Phased Build Plan

**Build-ordering rule: De-risk first.** The highest-risk item is the FreeRDP NDK build and the JNI bridge. This must be spiked and validated before anything else.

### Spike Phase: JNI & Native Bridge
- **Goal**: Successfully compile FreeRDP 3.27.1+ via Android NDK and render a single session frame to an Android Surface.
- **Gotchas**: Set `HOME` env dir to `filesDir` before init; compile with internal crypto flags.
- **Acceptance**: A rudimentary Android activity connects to an RDP server and renders the desktop.

### Phase 1: Basic RDP
- **Goal**: Connect to one Windows PC, full desktop in a spatial window, mouse/keyboard input, securely saved credentials.
- **Acceptance**: Shippable single-session RDP client on Horizon OS.
- **Deferred**: Multiple sessions, RemoteApp, Webcam, SSH.

### Phase 2: Multiple Sessions
- **Goal**: N concurrent RDP sessions.
- **Implementation**: Allow user choice when reconnecting to an active host:
  - *New Independent Session*: Spawns a new FreeRDP instance (requires server CALs or multiple accounts).
  - *Mirror Session*: Spawns a new Spatial Window observing the same video decode stream from an existing session.
- **Acceptance**: Two desktop windows visible simultaneously in passthrough.

### Phase 3: RemoteApp Support (RAIL)
- **Goal**: Individual remote applications appear as their own floating windows.
- **Acceptance**: Launching a RemoteApp opens a new Spatial Window without the full desktop background.

### Phase 4: Webcam Support
- **Goal**: Redirect Quest cameras into the RDP session (`rdpecam`).
- **Acceptance**: Remote Teams/Zoom can access the Meta Avatar virtual webcam or room camera.

### Phase 5: SSH Windows
- **Goal**: Terminal sessions alongside RDP windows.
- **Acceptance**: SSH connection opens a window running the Termux VT emulator.

### Phase 6: Tunneling & Proxies
- **Goal**: Support tunneling both RDP and SSH traffic through Cloudflare Tunnels (`cloudflared`) and arbitrary proxy commands (similar to PuTTY), plus persistent background tunnels.
- **Implementation**: Bundle the `cloudflared` arm64 binary. For ephemeral tunnels, spawn the proxy process on a local port before connection and direct FreeRDP/SSHJ to `localhost:<port>`. For persistent tunnels, host `cloudflared` inside an Android Foreground Service so the connection remains active even when no session windows are open.
- **Acceptance**: Users can specify an ephemeral proxy command per connection, or configure persistent `cloudflared` tunnel services that run in the background.

---

## Open Decisions & Resolutions
- **App Name**: Resolved as **MultiDesk Explorer**.
- **Phase 2 (Multiple Sessions)**: Resolved to support **both** true independent sessions and duplicated/mirrored sessions, depending on user choice at launch time.
- **Business Model & SSH Emulator**: App will be **Open-Source (Paid in store)**. Therefore, we will use the **Termux (GPLv3)** stack for terminal emulation, which provides superior emulation and is compatible with an open-source model.
