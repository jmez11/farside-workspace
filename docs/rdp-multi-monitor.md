# RDP desktop windows

The floating toolbar's **Add desktop** icon opens another remote monitor in the same RDP connection. It appears after a full-desktop session negotiates Display Control and GFX monitor topology with support for more than one monitor. The icon stays disabled while a change is pending or the display budget is exhausted. Ordinary launcher connection clicks still create independent sessions, including repeated clicks on the same saved profile.

## Ownership and lifecycle

`RdpMonitorSessions` indexes process-local session UUIDs. Each `RdpMonitorSession` owns one FreeRDP bridge/connection, certificate verifier, tunnel, clipboard subscription, reconnect decision, media state and capture grant. `RdpMonitor` owns a stable monitor ID, window host, requested/committed geometry and transient surface. Intents contain session/monitor IDs, never native handles or secrets. Missing runtime IDs after process death finish safely; opening the saved profile from the launcher starts a fresh session.

Creating a monitor reserves an ID/task slot, launches a document Activity, and waits for a presented frame and attached surface. Unpresented/missing-surface launches expire after ten seconds. Cancellation removes the reservation and ignores late Intents. Existing native presentation observation remains in use; no hardcoded Horizon window count or global window-management permission is required.

Closing an Activity removes its monitor and sends the surviving layout. Closing primary promotes the oldest surviving monitor and compacts its origin to zero. Last closure disconnects once. Surface loss, configuration recreation, Back/minimize and restore preserve the monitor reservation. Surface ownership prevents an obsolete composition from detaching its replacement. Held input is released on detach; button releases precede topology changes. Closing any monitor cancels an active cross-window drag. Key ownership prevents one window's cleanup from releasing another window's modifier.

Reconnect is a session operation. It disposes the old connection before creating one replacement, preserves shared mute settings and surviving windows, and rebuilds their topology from fresh capabilities. Canceling the reconnect dialog closes the session's windows together. Initial permission and certificate decisions are not repeated for additional monitors.

## Rendering, input and negotiation

The native renderer takes one stride-aware CPU atlas snapshot under FreeRDP's update lock, then posts a separate crop to each retained Android native window outside the session window lock. Each crop carries the confirmed GFX revision. Posts from a stale revision wait for matching painted content. Pointer mapping uses the same committed rectangle: inverse local zoom/pan, clamp inside the monitor, then add its atlas origin. Keyboard and clipboard remain session-wide.

Monitor IDs and primary ownership stay in creation order, independently of geometric order. Every 500 ms, `RdpPanelPose` reads only each owned window's Home pose, without shell/global window permissions. Poses with a common parent are projected onto the primary panel's right/up axes; coincident/unavailable poses preserve the existing arrangement. Horizontal bands (25 cm vertical tolerance) become top-aligned rows with touching logical edges. Physical depth, rotation and air gaps are not reproduced as remote desktop gaps. A changed arrangement waits for stable poses (2.5 cm movement tolerance, 750 ms settling plus 300 ms coalescing). Active remote button holds and session drags defer client layout changes.

The toolbar's **Arrange desktops** opens a per-window fallback: select another numbered desktop, then place this desktop Left/Right/Above/Below it. This selects a manual session layout; **Follow panel placement** resumes automatic observation. Toolbar titles identify the numbered desktops. A newly added panel defaults to the right of the top row until comparable poses arrive. Manual arrangements and observed positions are transient, never saved to profiles.

The Display Control wire layout uses signed coordinates relative to primary `(0,0)`. Confirmed GFX coordinates are normalized to the atlas upper-left for rendering/input; primary can occupy any atlas position. Native and Kotlin validation reject overlapping rectangles and excessive bounds/area. Widths are even and 200-8192; heights are 200-8192. Limits include the server's monitor count and overflow-checked area budget, the protocol's 16-monitor bound, atlas width/height 32766, and a 32 Mi-pixel ceiling on both aggregate monitor area and atlas allocation. This pixel ceiling is a resource guard, not a measured high-resolution performance guarantee.

Only one layout request is in flight; resize requests coalesce for 300 ms. A successful channel write is not confirmation: the copied GFX ResetGraphics monitor array must match all monitor rectangles and primary flags. Paint revision gates presentation, and replacement video can arrive later. Production accepts black desktops. The colorful saved-desktop test separately waits for real video before image capture.

If a layout is rejected or no matching usable response arrives in 15 seconds, additions are removed and primary exposes the full remote atlas so a retained remote monitor cannot strand content. An unsupported unsolicited topology also uses this fallback. Additional allocation stays disabled until reconnect. Independent window DPI and exact room geometry are not represented.

## Cross-window content drags

`RdpPointerGesture` preserves ordinary in-panel touch/controller/mouse dragging. At a panel edge facing another monitor, it attempts an Android global drag carrying only a random opaque token and private MIME type. `RdpSessionDrag` adopts the existing held RDP left button; the Android touch cancellation caused by handoff does not release it. Only views in the same live session with the matching active token accept the drag. Drag locations use the destination panel's current inverse zoom/pan mapping and committed atlas offset. Air gaps send no pointer movement and keep the remote button held; a destination drop sends its final move and releases once.

Android drag cancellation/end, source surface teardown, monitor closure, reconnect and a 60-second inactivity timeout release the button. Foreign sessions never receive remote content or control from this token. A rejected initial native request retains the ordinary local drag; a later Home cancellation ends the attempted handoff safely. Source/destination Activities stay separate; no global input hook or cross-session file transfer is introduced.

Quest 3S automated injection can start the native drag and receive START/END events, but Home has canceled injected operations with "another active drag" in its spatial drag system. Native acceptance/cancellation and simulated destination events are separate test results. Continuous physical trigger/pinch traversal across real Home gaps remains a wearer check; simulated events do not establish that behavior.

## Spatial audio and cameras

- One RDP session audio mix and playback stream, anchored to the primary window's tracked Home space. Moving another window or switching focus does not move the audio source. Primary closure transfers the anchor to the oldest survivor, without creating another playback stream. Missing OS spatial routing retains ordinary playback.
- One microphone redirection path and one camera redirection consumer per RDP connection. Opening a desktop adds no avatar-camera or microphone stream. Avatar pose remains headset/user based; there is no camera per desktop. Speaker/microphone/camera mute state is shared and reflected by every toolbar.
- Headset Display capture consent/capture belongs to the session. A live surviving Activity hosts any future consent UI; the application context owns service/projection resources. Primary destruction unregisters only that Activity's result launcher. Last session closure releases capture. Consent results retain their original request identity; a result from before reconnect cannot authorize the replacement session. This also avoids retaining a destroyed Activity as a service owner.

## Automated validation

Build and install app/test APKs with the repository CLI workflow. Always supply the intended device serial when two headsets are connected:

```powershell
adb -s <QUEST_3S_SERIAL> shell am instrument -w -e rdpMonitorsOnly true com.farside.workspace.test/com.farside.workspace.RdpResizeInstrumentation
```

The test uses the first saved full-desktop RDP connection and existing trust/authentication policy. It does not replace the saved profile. Cache evidence: `rdp-monitor-windows/results.json` and per-monitor PNG captures; ignored host copies: `build/verification/rdp-monitor-windows/`. The diagnostic temporarily adopts shell identity to read native visible/drawn fields on SDK 207; production uses owned-window APIs without global monitor permission. It checks the real Add desktop and arrangement toolbar/dialog actions; live left/right/above/below signed layouts and normalized crops; automatic topology derived from ordinary owned-window poses; interior drag preservation; native Android START/END/cancellation in both windows; separately simulated destination mapping, drop/cancel, and wrong-token/session rejection; one connection through 1 -> 2 -> 3 -> 2 -> 1 monitors, mixed secondary size, committed crops/pointer bounds, shared speaker/native camera mute, isolation of another runtime for the same profile, canceled launch/late Intent, one shared reconnect with both windows retained, primary promotion/audio token transfer, capture-owner survival, surface reattachment and final teardown.

Input/zoom mapping (`inputOnly`), launch/timeout (`windowLaunchOnly`), RDP/SSH/VNC Back/restore fixtures (`lifecycleMode backFixture`), native camera isolation (`cameraMode session-isolation`), display conversion (`cameraMode display-conversion`) and VNC transport/shared-screen fixtures (`vncMode fixture`) also PASS on the 3S. The initial refactor startup exception was corrected before these final runs; the crash buffer has no later application exception.

Quest 3S SDK 207 passed with modest 1280x800 and 1024x768 monitors. These captures and native geometry checks establish presentation and transport behavior, not subjective quality or physical hit testing. The earlier standalone protocol diagnostic is historical in commit `705a9cd`; production replaces its entry points with this test. Historical findings and merge precautions are in [the spike record](rdp-multimon-spike.md).

Initial wearer feedback is positive. Remaining physical interaction, spatial audio, active avatar-camera/microphone and Headset Display checks are tracked in [TODOs](../TODO.md#rdp-multiple-desktop-windows-wearer-checks). Live camera frames and perceptual spatial localization are not established by the shared-state/token tests. Rejected-server/time-out fallback is implemented but not exercised against a deliberately noncompliant live server. Large/high-resolution monitor performance remains unmeasured.
