# TODOs

## RDP multiple desktop windows: wearer checks

Initial wearer feedback is positive. The checks below remain open; automated results and implementation limits are in [RDP desktop windows](docs/rdp-multi-monitor.md).

- [ ] With controllers and hand tracking, add, move, resize and close desktop windows; minimize/restore through Home and confirm the shared session survives.
- [ ] Use pointer drags, zoom and keyboard input across monitors; check monitor edges, focus changes and held modifiers.
- [ ] Listen while moving primary and other windows and switching focus. Confirm sound stays anchored to primary, then transfers cleanly when primary closes.
- [ ] Keep a remote application actively using the avatar camera and microphone while adding/resizing/removing desktops, including primary closure. Confirm stream continuity, a single camera/microphone path and synchronized mute controls.
- [ ] Repeat add/remove and primary closure with a granted Headset Display stream. Confirm capture continues in surviving windows and stops when the last window closes; check consent behavior after reconnect.
