# RDP dynamic monitor investigation

## Scope and isolation

The requested behavior is one RDP connection with dynamically allocated remote monitors, each presented in an independent Horizon desktop window. A monitor-plus action on the floating toolbar will create another monitor/window; resize and close will update the same remote session.

The investigation checkout is based on default branch `origin/master` at `c7eadd3`; this repository has no `main` branch. The separate branch is `codex/rdp-multimon-investigation`. The concurrent `exp/mac-hevc` checkout is left in place. This change adds an RDP protocol diagnostic and test parameters; it does not implement the production toolbar or per-monitor spatial presentation, and changes no VNC/Mac implementation files.

Device commands target the Quest 3S explicitly. The Quest 3 is reserved for the concurrent Mac/VNC spike. The 3S's installed app had a different debug signer; the user authorized replacing it. One saved RDP profile and its linked credential were transferred privately and re-encrypted under the new installation's Android Keystore. Transfer material and device evidence stay in ignored local storage.

## Observed results

Quest 3S: Android API 34, Meta OS SDK 207, Home `207.0.0.836.436`.

The saved RDP host advertised Display Control capabilities of 16 monitors and monitor-area factors 8192 and 8192. These are server protocol limits, not a validated Quest window/decoder capacity.

The debug probe freezes ordinary single-monitor resize requests, sends complete layouts through the existing network thread, and observes actual server monitor definitions through GFX ResetGraphics. It preserves GDI's callback and ownership of `RdpgfxClientContext.custom`. Layout submission alone is not treated as acceptance.

| Stage | Server-confirmed layout | Framebuffer |
|---|---|---|
| Initial | One monitor, 1920x1080 | 1920x1080 |
| Add | Two monitors, each 1280x800 | 2560x800 |
| Resize secondary | 1280x800 + 1024x768 | 2304x800 |
| Add third | 1280x800 + 1024x768 + 1024x768 | 3328x800 |
| Remove third | 1280x800 + 1024x768 | 2304x800 |
| Restore | One monitor, 1920x1080 | 1920x1080 |

The same native connection handle remains active throughout. Final cleanup restores the initial topology and removes the owned test profile. The probe uses one existing atlas presentation window, so it establishes protocol/decoder feasibility rather than independent spatial monitor presentation.

**Rendering readiness:** ResetGraphics arrives before the replacement desktop video is ready. Initial fixed-delay captures after the third-monitor transition were blank even though the server topology was correct. The final harness waits for nonblank content on this known colorful test desktop, captures every stage, and fails if content does not arrive. The image-validated run passes without forcing redraws. Production readiness should follow frame/update generations rather than use a color-count heuristic, since a legitimate desktop may be black.

**Home capacity:** The existing historical six-main-window fixture fails on this SDK 207 device because an early window becomes not visible/drawn. A scoped three-main-window fixture passes, including one and two active keyboard companion panels, and all main windows remain visible/drawn. This observation does not establish a universal count limit. Ordinary-app global window enumeration remains permission-denied; shell observation is test-only.

Existing window-launch presentation/timeout/canceled-drag checks and Back/Up minimize/restore fixtures pass. These use synthetic input and native observations; physical Home gestures and wearer appearance remain unverified. No new app/native crash is observed during the passing runs.

Local evidence: `build/verification/rdp-multimon-quest3s/`. The final protocol report/images are under `final/`; the three-window capacity result is under `render-check/`. Initial failed six-window and premature-capture evidence is retained separately.

## Implementation plan

1. **Shared session ownership.** Extract an application-scoped `RdpSessionRuntime` and registry. Give each independent connection a session-instance ID distinct from the saved profile ID; each monitor receives a stable client ID. Runtime ownership covers the native connection, tunnel, verifier, reconnect state, clipboard, media controls, desired/confirmed layouts and input coordination. A desktop Activity attaches to an existing runtime. Closing/recreating one view must not close surviving monitors' connection.

2. **Capability-gated toolbar.** Add an accessible, localized monitor-plus action named **Add desktop** to each RDP desktop toolbar. Start with one actual monitor; allocate no invisible placeholders. Enable adding only with live Display Control capabilities, a reliable topology confirmation path, available count/area/memory budget and no conflicting layout transaction. Initially support the tested GFX path. Hide for VNC and RemoteApp. Expose the reason when a supported session cannot currently add another monitor; do not invent a fixed Home window cap.

3. **Central display controller.** Replace per-screen width/height requests with one serialized controller owning desired, in-flight, confirmed and last-working layouts. Coalesce resize intents using the existing approximately 300 ms debounce. Submit the entire current monitor array. Start with a contiguous, top-aligned logical row in creation order; Home owns physical placement independently. Validate one primary at (0,0), adjacency/non-overlap, even widths, 200..8192 dimensions, negotiated count/area and combined framebuffer/memory limits before calling FreeRDP, which otherwise clamps some values silently.

4. **Transactional allocation.** Reserve an ID and launch token, launch a new document window with loading content, and wait for actual presentation plus a usable surface before expanding the server layout. Track local launch success, server topology acceptance and first valid monitor frame separately. Extend existing launch monitoring with cancellation generations: a late Activity for a canceled request must not allocate a monitor. Reconcile late server changes after timeout. Restore the last accepted configuration when possible and report one failure. An add action should not reconnect automatically.

5. **Native outputs per monitor.** Replace mirror-only `addSurface`/`removeSurface` remnants with records keyed by monitor ID and attachment generation. `setSurface` currently clears every output and `removeSurface` does nothing. Retain FreeRDP's shared framebuffer/graphics pipeline, snapshot once and crop each confirmed monitor into its own Android surface. Preserve both strides and validate all crop bounds. Acquire retained output references under short locks; avoid holding the registry lock while waiting for a compositor buffer. Replacing one surface must preserve its siblings.

6. **Atomic render/input geometry.** Keep protocol coordinates distinct from normalized framebuffer coordinates. Server monitor right/bottom coordinates are inclusive; use half-open rectangles internally. Normalize using minimum left/top before cropping or sending unsigned pointer coordinates. Invert per-window zoom/pan, map into that monitor's confirmed dimensions, then add its normalized origin. Bind crops, framebuffer dimensions and pointer offsets to the same committed generation. Release held buttons/gestures when transforms or monitor ownership change. Preserve wheel/hover/right-click routing and test seams and mixed sizes.

7. **Shared controls and window lifecycle.** Use one clipboard subscription and shared speaker/microphone/camera state. Coordinate modifier/keyboard ownership, with one active companion per session. Separate Activity consent presentation from session-owned display capture so primary closure does not stop it. Current default-branch audio binds a playback session to a window's tracked space on focus/movement: elect one owner, initially primary, and transfer the binding on primary closure so desktop windows do not fight over spatial audio placement. SDK 207 Back/minimize/restore visibility changes must not remove a remote monitor.

8. **Close and reconnect.** Closing any desktop removes only its monitor. Promote the oldest survivor when primary closes and rebase the full layout. Last closure tears down once. Temporary surface loss/recreation keeps the monitor reserved. If the server retains a monitor whose local window closed, expose recovery/reopen rather than allocate another hidden monitor; a full-desktop fallback can keep stranded content accessible if Home rejects a replacement window. Reconnect once per runtime using a fresh verifier and current capabilities, restoring the surviving layout when supported.

9. **Validation.** Add host tests for layout constraints, primary promotion, coordinate transforms, state transitions, timeouts/late callbacks and exactly-once ownership. Test crop/stride helpers independently with malformed and overflowing bounds. On the 3S, verify the real toolbar and separately rendered monitor windows, mixed-size resize, independent monitor content, close/promote, failed-launch rollback, keyboard/clipboard/media ownership and reconnect. Measure memory/frame time at larger resolutions; the current modest three-monitor topology is not a high-resolution capacity benchmark. Physical gestures and wearer appearance remain a final manual check.

## Reproducing the diagnostic

Build and install app/test APKs using the repository's CLI workflow. Every device command must name the intended serial; do not use an unqualified ADB install or instrumentation command when both headsets are connected.

```powershell
adb -s <QUEST_3S_SERIAL> shell am instrument -w -r -e rdpMonitorProbe run com.farside.workspace.test/com.farside.workspace.RdpResizeInstrumentation
adb -s <QUEST_3S_SERIAL> shell am instrument -w -r -e windowCapacityOnly true -e windowCapacityMainCount 3 com.farside.workspace.test/com.farside.workspace.RdpResizeInstrumentation
```

The RDP probe selects the first saved RDP profile, requires it to be a full desktop, and honors its existing authentication/certificate policy. It disables camera, microphone, storage and clipboard redirection on its disposable profile, disables automatic reconnect, restores its initial layout, and closes only its own connection. A fresh trust/login prompt needs user action. `rdpMonitorProbe import` is test-only setup for an encrypted local connection transfer; it is not a production import feature. Release native builds refuse the probe's layout operations.

Keep the native/test probe commit separate from documentation to simplify review or cherry-picking. The Mac/VNC work has historical edits in the native bridge, Kotlin RDP bridge and instrumentation runner as well as its VNC files; independent worktrees prevent file interference, but the final merge should still review those shared files. Do not merge or overwrite the other agent's uncommitted work.

The code is isolated in `705a9cd` (`test(rdp): probe dynamic monitors on one connection`). A `git merge-tree --write-tree` preview compared committed `exp/mac-hevc` against both the default baseline and this probe commit, without changing either worktree or index. Both previews reported the same 19 conflicted files: no additional conflicted file was introduced by the probe. This does not resolve the Mac branch's pre-existing divergence, guarantee individual hunks are conflict-free, or include its ongoing uncommitted work. Preview logs and a compact comparison are in the ignored evidence directory.
