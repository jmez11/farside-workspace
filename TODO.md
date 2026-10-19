# TODOs

## RDP multiple desktop windows: wearer checks

Initial wearer feedback is positive. The checks below remain open; automated results and implementation limits are in [RDP desktop windows](docs/rdp-multi-monitor.md).

- [ ] With controllers and hand tracking, add, move, resize and close desktop windows; minimize/restore through Home and confirm the shared session survives.
- [ ] Move same-session panels left/right/above/below and confirm the host follows after settling, including moving primary and mixed panel sizes. Try the numbered **Arrange desktops** controls and **Follow panel placement**.
- [ ] Hold controller trigger/hand pinch while dragging a remote app window or content across actual spatial gaps; drop in another same-session panel, reverse direction and repeat with zoom. Cancel over empty space and close a panel during dragging; verify no stuck remote button. Native injected drags were canceled by Home; physical traversal remains unverified.
- [ ] Use zoom and keyboard input across monitors; check focus changes and held modifiers.
- [ ] Listen while moving primary and other windows and switching focus. Confirm sound stays anchored to primary, then transfers cleanly when primary closes.
- [ ] Keep a remote application actively using the avatar camera and microphone while adding/resizing/removing desktops, including primary closure. Confirm stream continuity, a single camera/microphone path and synchronized mute controls.
- [ ] Repeat add/remove and primary closure with a granted Headset Display stream. Confirm capture continues in surviving windows and stops when the last window closes; check consent behavior after reconnect.
