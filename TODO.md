# TODOs

## RDP multiple desktop windows: wearer checks

Initial wearer feedback is positive. The checks below remain open; automated results and implementation limits are in [RDP desktop windows](docs/rdp-multi-monitor.md).

- [ ] With controllers and hand tracking, add, move, resize and close desktop windows; minimize/restore through Home and confirm the shared session survives.
- [ ] Repeat opening four desktops, wall-place the fourth and restore the automatically minimized panel. Confirm all views/input remain independent; use the immediate **Follow panel placement** icon if needed. On a persistent layout error the Add icon becomes **Retry desktop layout**; verify it returns to Add after recovery.
- [ ] Move panels left/right/above/below, including primary and mixed sizes, and confirm the host follows after settling. Automated host-reset/crop/surface fixtures do not establish physical wall placement or Home gestures.
- [ ] Cross-gap controller/hand content dragging: wearer reports it did not work (2026-10-19); nice-to-have, deferred while fixing layout recovery. Native injected drags were also canceled by Home; keep ordinary in-panel dragging functional.
- [ ] Use zoom and keyboard input across monitors; check focus changes and held modifiers.
- [ ] Listen while moving primary and other windows and switching focus. Confirm sound stays anchored to primary, then transfers cleanly when primary closes.
- [ ] Keep a remote application actively using the avatar camera and microphone while adding/resizing/removing desktops, including primary closure. Confirm stream continuity, a single camera/microphone path and synchronized mute controls.
- [ ] Repeat add/remove and primary closure with a granted Headset Display stream. Confirm capture continues in surviving windows and stops when the last window closes; check consent behavior after reconnect.
