# Progress Checklist: MultiDesk Explorer

## Current Status
- [x] Initial architecture and design session completed.
- [x] Standing rules (`AGENTS.md`), architecture (`ARCHITECTURE.md`), and progress notes initialized.

## Next Steps (Immediate)
- [x] Initialize the Android project (Command-line only).
- [x] **Spike**: Set up FreeRDP NDK build and JNI bridge.
- [x] **Spike**: Verify basic RDP connection and video decode on an Android Surface.

## Known Working Features
- Spatial Windowing: Sessions launch as independent, repositionable Android Document Activities inside Horizon OS.
- RDP Rendering: Stream displays correctly via FreeRDP/JNI onto the `SurfaceView` overlay.
- Profile Management: Connections can be added, saved, and edited, with fields mapped properly to FreeRDP connection flags.
- Taskbar integration: Sessions correctly show connection name via `setTaskDescription`.

## Pending Fixes & Unverified Logic
- RDP Touch/Click Inputs: Needs further refinement (clicks still unverified in latest build).
- RDP Dynamic Display/Zoom: Scaling UI via `SendMonitorLayout` unverified.
- Floating Overlay: Auto-fade logic and repositioning behavior not functioning perfectly.
- Local PC Discovery: Implementation stubbed but requires DNS-SD/SMB scanning logic.
- Tunneling: Cloudflared integration stubbed but not yet running background foreground services effectively.
- Keyboard Input: UI placeholder exists but JNI key dispatch is not yet implemented.

## Blocked
- None.

## Backlog
- [x] Phase 1: Basic RDP Spatial Window implementation.
- [ ] Phase 2: Multiple Sessions. (In Progress - Architecture built)
- [ ] Phase 3: RemoteApp (RAIL).
- [ ] Phase 4: Webcam support (rdpecam).
- [ ] Phase 5: SSH Windows.
- [ ] Phase 6: Tunneling & Proxies (Cloudflared).
