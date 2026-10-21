# TODOs

## RDP multiple desktop windows: wearer checks

James confirms the layout/carry build works well on Quest 3S, including the hand icon, and the Adjusting transitions and loading-ring timing (2026-10-20). The checks below remain open; automated results and implementation limits are in [RDP desktop windows](docs/rdp-multi-monitor.md).

- [ ] With controllers and hand tracking, add, move, resize and close desktop windows; minimize/restore through Home and confirm the shared session survives.
- [ ] Repeat opening four desktops, wall-place the fourth and restore the automatically minimized panel. Confirm all views/input remain independent and layout recovers automatically; Add stays an Add button.
- [x] Check the frozen, dimmed loading-spinner view during resize, layout and desktop add/remove, including rapid successive changes; confirm one smooth fade back to usable desktops. Wearer confirms the ring and fade timing (2026-10-20).
- [ ] Move panels left/right/above/below, including primary and mixed sizes, and confirm the host follows after settling. Automated host-reset/crop/surface fixtures do not establish physical wall placement or Home gestures.
- [ ] Cross-gap controller/hand content dragging: wearer reports it did not work (2026-10-19); native handoff now starts 64px from an edge and reaches both windows in automation, but physical gap traversal needs retesting. Separate hand-icon pick-up/drop received positive wearer feedback; keep ordinary in-panel dragging functional.
- [ ] Use zoom and keyboard input across monitors; check focus changes and held modifiers.
- [ ] Listen while moving primary and other windows and switching focus. Confirm sound stays anchored to primary, then transfers cleanly when primary closes.
- [ ] Keep a remote application actively using the avatar camera and microphone while adding/resizing/removing desktops, including primary closure. Confirm stream continuity, a single camera/microphone path and synchronized mute controls.
- [ ] Repeat add/remove and primary closure with a granted Headset Display stream. Confirm capture continues in surviving windows and stops when the last window closes; check consent behavior after reconnect.
