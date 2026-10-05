# Progress Checklist: MultiDesk Explorer

## Current Status
- [x] Initial architecture and design session completed.
- [x] Standing rules (`AGENTS.md`), architecture (`ARCHITECTURE.md`), and progress notes initialized.

## Next Steps (Immediate)
- [x] Initialize the Android project (Command-line only).
- [x] **Spike**: Set up FreeRDP NDK build and JNI bridge.
- [x] **Spike**: Verify basic RDP connection and video decode on an Android Surface.

## Known Working Features
- Spatial Windowing: Sessions launch as independent, repositionable Android Document Activities inside Horizon OS (only applies to *different* connections currently).
- RDP Rendering: Stream displays correctly via FreeRDP/JNI onto the `SurfaceView` overlay.
- Profile Management: Connections can be added, saved, and edited, with fields mapped properly to FreeRDP connection flags.

## Reported Issues (To be fixed)
- Taskbar Integration: Sessions still display the generic app name instead of the connection name via `setTaskDescription` (may be ignored by Horizon OS, code is implemented).
- RDP Touch/Click Inputs: Clicks still unresponsive/unverified; needs further refinement.
- RDP Dynamic Display/Zoom: Scaling UI via `SendMonitorLayout` unresponsive/unverified.
- Floating Overlay: Auto-fade logic and repositioning behavior not functioning perfectly.

## Unverified / Stubbed Logic
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
