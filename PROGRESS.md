# Progress Checklist: MultiDesk Explorer

## Current Status
- [x] Initial architecture and design session completed.
- [x] Standing rules (AGENTS.md), architecture (ARCHITECTURE.md), and progress notes initialized.
- [x] Initialize the Android project (Command-line only).
- [x] **Spike**: Set up FreeRDP NDK build and JNI bridge.
- [x] **Spike**: Verify basic RDP connection and video decode on an Android Surface.

## Known Working Features
- Spatial Windowing: Sessions launch as independent, repositionable Android Document Activities inside Horizon OS.
- RDP Rendering: Stream displays correctly via FreeRDP/JNI onto the SurfaceView overlay.
- Profile Management: Connections can be added, saved, and edited.
- RDP Panning/Zooming: Visual scaling via Compose graphicsLayer (instead of protocol-level DisplayControl) is implemented and functioning correctly.
- RDP Touch Pointers: Letterboxing and touch coordinates are mapped correctly to FreeRDP (PTR_FLAGS_DOWN/UP/MOVE).
- Soft Keyboard: Custom row of PC modifiers securely overlayed over the system keyboard, sending scancodes via JNI.

## Reported Issues (To be fixed)
- **SEVERE Window Dragging Lag**: 
  - Dragging a window in the RDP session lags multiple seconds behind the pointer.
  - **Root Cause & Discoveries (from Logcat & Source Auditing):**
    1. **Frame Flow Control**: Disabling `FreeRDP_FrameAcknowledge` broke server-side flow control. Restoring default `FrameAcknowledge` (> 0) restored backpressure.
    2. **Static Channel Loading Failure**: On Android with `BUILD_SHARED_LIBS=OFF`, FreeRDP builds channel plugins (`drdynvc`, `rdpgfx`, `disp`) as static libraries. FreeRDP's default client loader relies on `dlopen` which fails silently. Furthermore, `freerdp_connect()` calls `utils_reload_channels()` immediately after `PreConnect`, destroying and rebuilding `context->channels` via `instance->LoadChannels`. Any channels loaded in `PreConnect` were discarded before connection established.
    3. **Missing GDI Pipeline Initialization**: Even when `rdpgfx` connects, `gdi_graphics_pipeline_init` must be wired to `PubSub_SubscribeChannelConnected` to connect the GFX pipeline to the GDI surface.
    4. **Codec Negotiation Reality**: FreeRDP was falling back entirely to raw legacy uncompressed GDI over Wi-Fi, completely flooding the network. Because `WITH_FFMPEG=OFF` and `WITH_OPENH264=OFF`, H.264 decoders were not compiled into FreeRDP; advertising H.264 would cause unhandled AVC packets.
  - **Things Attempted & Implemented:**
    1. Re-enabled cache flags (`+glyph-cache`, `+bitmap-cache`, `+offscreen-cache`) and restored `FrameAcknowledge` defaults.
    2. Set color depth to 32bpp (`FreeRDP_ColorDepth = 32`) required for RDPGFX negotiation.
    3. Decoupled rendering: `client_end_paint` sets an atomic `dirty = true` flag; a dedicated background thread blits `gdi->primary_buffer` to `ANativeWindow` in ~6ms without stalling the FreeRDP network/packet-processing loop.
    4. Implemented `my_addin_provider` to map static entry points (`drdynvc_VirtualChannelEntryEx`, `rdpgfx_DVCPluginEntry`, `disp_DVCPluginEntry`).
    5. Moved static channel loading into `instance->LoadChannels` (`client_load_channels`) using `freerdp_channels_client_load_ex` with proper settings context.
    6. Registered dynamic channels (`rdpgfx`, `disp`) via `freerdp_client_add_dynamic_channel`.
    7. Attached `gdi_graphics_pipeline_init` / `gdi_graphics_pipeline_uninit` in channel lifecycle events.
    8. Intercepted `gfx->SurfaceCommand` to log ground-truth negotiated server codecs (`RDP_CODEC: server sent codec ...`).
    9. Disabled H.264 advertisement temporarily in favor of Progressive / RemoteFX while software-only decoders are present.
  - **Immediate Next Steps & Plan:**
    1. Deploy current build to Quest device once reconnected to verify `drdynvc` and `rdpgfx` channel negotiation and examine the live `RDP_CODEC` log to see whether Progressive/RemoteFX runs smoothly.
    2. **Hardware Decoding Upgrade (MediaCodec)**: Enable `WITH_MEDIACODEC` in `app/src/main/cpp/freerdp/libfreerdp/codec/CMakeLists.txt` and link `mediandk` to enable hardware-accelerated H.264 decoding directly on Quest 3's Snapdragon XR2 Gen 2 DSP/VPU without third-party licensing baggage (avoiding FFmpeg GPL/LGPL issues and OpenH264 runtime binary distribution constraints).
    3. Re-enable `FreeRDP_GfxH264`, `FreeRDP_GfxAVC444`, and test drag performance under native hardware decode.

## Unverified / Stubbed Logic
- Local PC Discovery: Implementation stubbed but requires DNS-SD/SMB scanning logic.
- Tunneling: Cloudflared integration stubbed but not yet running background foreground services effectively.

## Backlog
- [x] Phase 1: Basic RDP Spatial Window implementation.
- [ ] Phase 2: Multiple Sessions. (In Progress)
- [ ] Phase 3: RemoteApp (RAIL).
- [ ] Phase 4: Webcam support (rdpecam).
- [ ] Phase 5: SSH Windows.
- [ ] Phase 6: Tunneling & Proxies (Cloudflared).
