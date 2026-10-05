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
  - **Things Attempted to Fix This:**
    1. Enabled FreeRDP GFX/H264 flags (/gfx:AVC420:on, /rfx, /bpp:16, /compression, /network:auto) via command-line parsing to stop raw GDI bandwidth saturation.
    2. Explicitly hardcoded protocol configurations in JNI PreConnect (FreeRDP_FastPathInput, FreeRDP_RemoteFxCodec, FreeRDP_SupportGraphicsPipeline, FreeRDP_GfxH264) and disabled FreeRDP_FrameAcknowledge.
    3. Throttled Compose UI mouse event dispatches to 30Hz (32ms) to prevent event flooding.
    4. Removed the lastMouseEventTime throttle counter from the Compose State system to prevent a catastrophic 60Hz UI Recomposition Storm during dragging.
    5. Identified that client_end_paint was locking ANativeWindow and copying an 8MB buffer on the FreeRDP thread.
    6. Decoupled rendering completely by spawning a dedicated 60 FPS std::thread that periodically locks the window and copies the gdi->primary_buffer, ensuring the FreeRDP networking thread is never blocked by Android's compositor.
  - *Result*: Lag persists unaffected. The issue might be inherent to SoftwareGdi = TRUE in FreeRDP 3 failing to leverage hardware decoding, or an unresolved bufferbloat issue.

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
