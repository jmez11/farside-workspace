# Progress: MultiDesk Explorer

## Current Status (2026-10-05)
- Android/Compose app and FreeRDP JNI spike run on Quest 3; phase 2 (multiple sessions) remains in progress.
- Latest arm64 debug APK is installed and verified on Quest 3. Native debug code uses `-O2` with symbols.
- GFX now negotiates through `instance->LoadChannels`: static `drdynvc` Ex loader, dynamic `rdpgfx`/`disp`, and GDI pipeline lifecycle hooks. `RDP_CODEC` records actual server SurfaceCommand codecs.

## Resize, Rendering, and Input
- User confirmed resizing, dragging, and pointer accuracy. Touch/controller-trigger/hand-pinch hold now defers left down: a growing clockwise square completes in 1 second, then release sends only right-button down/up. Short release clicks left; movement beyond Android touch slop starts left drag; cancellation releases any held button. Hover retains the 16 ms throttle.
- Session toolbar starts 16dp below the top, shows profile name/host, and supports pinning. Unpinned toolbar fades completely after 3 seconds with no minimized button. `WindowMovementObserver.kt` observes the Home panel pose through the headset framework's activity-scoped volumetric listener; pose changes reveal the full toolbar and restart its timeout. Focus changes, resizing, and hover near the toolbar also reveal it. No global window permission is requested by the app.
- Replaced the fixed SurfaceView with a full-frame TextureView. It scales immediately during resizing; a 300 ms debounce requests matching remote resolution through DisplayControl after server capabilities arrive, with bounded retries if Windows ignores the initial layout. Zoom remains local, with pan clamped to avoid exposed edges.
- Keyboard modifier buttons follow IME visibility. Session uses edge-to-edge inset delivery with `adjustResize`; after observed IME closure the hidden text field is removed and RDP focus restored, allowing the keyboard to reopen.
- Session declares the standard 1024dp x 640dp landscape initial panel with resizability enabled; orientation remains unrestricted so landscape and portrait resizing still fill content bounds.
- Fixed the missing `gdi_resize` callback and read framebuffer rows using GDI stride. The render thread snapshots under FreeRDP's update lock, then releases it before waiting for Android buffers.
- Fixed a second resize bug: `ANativeWindow_getWidth/Height` can report new consumer defaults while locked producer buffers retain their old size. The renderer now tracks configured producer geometry explicitly and reapplies it when desktop dimensions change. Transitional buffers are filled completely.
- Rendering checks actual invalid regions, paces from frame start, and logs aggregate copy/post timings instead of logging every blit.
- Surface recreation detaches/reattaches the texture without disconnecting RDP. Closing a session joins native workers before freeing GDI/context resources.

## MediaCodec Upgrade
- Enabled `WITH_MEDIACODEC`; upstream CMake supplies `mediandk`. FFmpeg/OpenH264 remain disabled.
- `app/src/main/cpp/h264_mediacodec.c` replaces the upstream experimental backend at build time without modifying the FreeRDP submodule. It fixes initialization/output-layout issues, configures at actual coded dimensions, handles padded I420/NV12, bounds decoder waits, and requests low latency.
- Hardware resources are created only for actual AVC packets. Prefer `c2.qti.avc.decoder.low_latency`, with the platform AVC decoder as fallback.
- Advertise AVC420/AVC444/v2 with thin-client preferences. The original Progressive preference caused Windows to keep choosing Progressive despite H.264 support. Frame acknowledgements remain at FreeRDP defaults; caches stay enabled.
- **Observed on Quest 3:** `AVC444v2(H.264) (0x000f)` decoded by `c2.qti.avc.decoder.low_latency`, with I420 output. AVC444 reconstruction, YUV-to-RGBA conversion, and framebuffer copies still run on CPU.

## Verification
- **Standing user preference:** Continue proactively building and deploying app updates to Quest, creating/extending on-device automated tests, running them, inspecting logs/captures, and fixing discovered failures within the requested scope. Routine iterations need no renewed permission. Workflow and commands are documented in `AGENTS.md` and `ARCHITECTURE.md`.
- Toolbar build and Quest UI checks passed: 16dp offset, full fade/no minimized control, pin/unpin, and toolbar reappearance after a real 5cm position-only panel transaction (restored afterward). No resize or focus change is needed. Final five-size landscape/portrait resize and close regression also passed with the inset changes. Updated debug APK is installed on Quest 3. Logs: `build/verification/toolbar-build.log`, `toolbar-ui.log`, `toolbar-window.log`, `toolbar-regression.log`.
- Horizon rejected automated keyboard show (`PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE`). The test therefore dispatched Android IME inset visibility transitions: modifier buttons appeared, disappeared on closure, and appeared on reopening. Actual floating keyboard dismissal via its close control still needs a worn-headset check; tests do not claim that was exercised.
- Current panel/input build: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` passed; updated APKs installed on Quest 3. Input-only device instrumentation passed short tap, 999/1000/1500 ms hold boundaries, hold-to-drag, cancellation, multitouch cancellation, and physical secondary-button release. The full five-size landscape/portrait resize and session-close regression also passed. Logs: `build/verification/panel-build.log`, `panel-regression.log`, `panel-device.log`.
- Manual checks pending: initial size for a fresh Home task and clockwise hold feedback with real trigger/pinch. Automated MotionEvents validate RDP button routing, not Horizon gesture delivery or perceived animation.
- Build: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` passed. APK installed with `adb install -r`.
- Device regression: `adb shell am instrument -w com.multidesk.explorer.test/com.multidesk.explorer.RdpResizeInstrumentation` passed all five steps: 1280x800 -> 1000x600 -> 1600x900 -> 800x1000 -> 1280x800, then session close.
- Test checks texture/content bounds and nonblank frames, saving PNGs for visual review. Latest landscape/portrait captures have correct proportions and no outside black border. One connection survived the resize sequence; native event-loop shutdown completed without a logged crash.
- Moving-window source: run `tools/rdp-motion-probe.ps1` on the Windows RDP host. Its temporary window closes automatically and leaves documents untouched.
- Artifacts: `build/verification/quest-rdp.log` (motion), `quest-resize.log` (final resize/close run), and resolution-labelled PNGs (local build output).
- Final moving-window run: sampled warm decode calls approximately 4-6 ms; render snapshot/post averages approximately 1.3-1.9 ms across tested sizes. Decoder startup/resolution changes took roughly 68-164 ms. These are processing timings, not end-to-end controller latency or a controlled comparison against the old build.
- Remaining validation: user's controller drag feel and zoomed selection on the new TextureView path; NV12 output handling (device exercised I420 only). Automated window motion does not establish subjective controller responsiveness.

## Known Working Features
- Independent repositionable session activities in Horizon OS; saved connection profiles.
- RDP desktop display, mouse input, modifier controls, and soft keyboard.

## Stubbed / Backlog
- Local PC discovery: DNS-SD/SMB scanning remains stubbed.
- Cloudflared: per-session and persistent foreground-service integration remains incomplete.
- Phase 2: multiple sessions/mirroring; then RemoteApp, camera redirection, SSH, tunnels/proxies.
