# Connection drag placement compatibility

Investigation: 2026-10-17. Physical tests used Quest 3S / Horizon SDK 207 / Home 207.0.0.836.436 and Quest 3 PTC / SDK 209 / Home 209.0.0.321.433.

## Findings

Clicking a connection calls `SessionWindowLauncher.launch`, which starts a separate `SessionActivity` task. Dragging calls `ConnectionSpatialDrag.start`, which uses Android `View.startDragAndDrop` with `DRAG_FLAG_GLOBAL`. Its activity-MIME ClipData contains an immutable one-shot PendingIntent and the current Android user. Home must receive the drop, send that PendingIntent and supply the spatial placement options. Drawing the preview does not create a session.

The Android drag API is old; the activity MIME/PendingIntent payload is an AOSP hidden framework contract. This path is not a call to the newer Compose SpatialWindow creation API. Neither general SpatialWindow support nor class/method presence proves that Home can route this drop.

| Stage | SDK/Home 207 | SDK/Home 209 PTC |
| --- | --- | --- |
| Native preview | Starts | Starts |
| Physical release | Android reports no valid drop target | Android sends DROP to Home's FocusPlaceholderActivity |
| Receiver result | false | true |
| Session launch | None | PendingIntent launches SessionActivity; displayed in 456ms |

The 3S failed with hands and controllers, including empty space. Ordinary clicks worked. Keeping its launch token alive for 15 seconds after release did not help; the experimental change was reverted. The captured failure is before Farside receives a session launch, not an RDP connection error.

Installed Home binaries have substantially the same Java activity-MIME receiver. Their native bridges differ: 209 adds the drag shadow window ID/dimensions and an explicit Android placement path. Its successful physical trace uses that path. These observations establish a routing difference, but not the first Meta release containing a fix.

No supported ordinary-app workaround or documented per-feature capability query was found. Launching on every `result=false` would also launch canceled drags and drops onto the source, without recovering Home's missing placement pose.

## Implemented gate

- `WindowPlacementSupport.isAvailable` calls public `metavr.os.Build.Version.getApiVersion()` and enables placement on SDK 209 or later. Missing/incompatible OS APIs and runtime access failures return false. This is a conservative tested-support threshold: 207 fails, 209 works, and 208 is untested; it is not a documented feature introduction version.
- `ConnectionSpatialDrag.start` exits before preparing a request or token on unsupported releases. Connection cards consume unsupported long presses without starting placement or turning the release into a click. Ordinary clicks and the independent Reorder gesture remain available.
- The launcher shows a localized click-only hint below 209; the existing placement hint remains on supported releases. All existing resource locales include the new hint.
- No cancellation fallback or persisted failure-based capability inference was added. Home's normal grab-bar movement is unchanged. Keep Home version in diagnostics and reassess if it updates independently from the SDK.

## Validation

- Main app/test CLI builds and `adb install -r` on both headsets. `windowLaunchOnly` PASS on 207 and 209: version policy boundaries/missing APIs, real click-to-open, guarded direct entry, held/moved card with no window/request on 207, native drag start/cancel on 209, and existing synthetic PendingIntent pose/ordinal/timeout checks. Captured hints inspected.
- `launcherOnly` PASS on 3S. The broad PTC run reached a later unrelated Add Connection hover assertion; the bounded `launcherArrangementOnly` mode stops after click/favorites/order/drag/edge-scroll coverage, before compact/empty-state tooltip checks. Final bounded runs PASS on both devices; crash buffers are empty, owned fixtures are removed and launchers restored. Evidence: `build/verification/window-placement-gate/`.
- Physical successful PTC drop and failing 207 hand/controller drops were captured during investigation. Automated gate tests do not establish post-change physical gestures/appearance; wearer checks remain: no preview on a 3S connection hold/drag, normal clicks and reorder on 3S, and free-space/docked placement with hands/controllers on PTC.

## Evidence and references

- Local evidence: `build/verification/quest3s-compat/` contains failing release/retained-token traces, installed Home binary comparisons, successful `ptc-physical-drop.log` / `ptc-physical-drop-summary.txt`, and capability probe results. `build/verification/quest3s-debug/` records replacement of the 3S release and validation on its main debug package.
- Harness: `adb -s <device> shell am instrument -w -r -e lifecycleMode capability com.farside.workspace.test/com.farside.workspace.RdpResizeInstrumentation`; results in app cache `window-capability-results.json`. It inventories version inputs, not physical support.
- [Meta OS SDK versioning](https://developers.meta.com/vr/documentation/android-apps/metavr-os-sdk-versioning/)
- [AOSP hidden activity drag contract](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/content/ClipDescription.java)
