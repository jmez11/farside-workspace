# Mac SSH startup and interaction latency

## Findings (2026-10-22)

Saved Mac streaming/authentication options match for the compared connections; their transport and server-selected session paths differ. The direct connection has no SessionSelect prompt in the captured run. The tunneled server reports state5, selecting a virtual GUI session with the two-TCP authentication sequence. This is a server/session difference, not evidence that latency alone hides login controls. No account, console-sharing preference, credentials or remote permissions were changed.

The tunneled connection also attempted direct media through the Cloudflare TCP endpoint first. Logs show a successful virtual login, roughly3.5s without decodable media, teardown, then another virtual login through the SSH helper. The first attempt used the saved Screen Sharing identity; same-Mac helper setup resolves the canonical short account name. This redundant login/display churn is avoidable. Cloudflare connections now use companion streaming first, then ordinary VNC; direct LAN streaming retains its existing preference. The separate Cloudflare VNC process is opened only for actual VNC fallback, so it cannot prematurely terminate an otherwise healthy helper stream.

The old Perl event loop synchronously wrote complete video packets to stdout and TCP data to its peer. A blocked media channel could therefore stop reads/forwarding of mouse/key control. A local stalled-stdout fixture reproduced the control timeout. The helper now uses nonblocking readiness-driven output, services control separately, and bounds media at512 queued packets/4MiB. It drops only complete unsent media frames; a partially written framing record is retained. TCP remains lossless through paused reads, partial writes and half-close propagation. Numeric queue/drop/control counters are logged every5s, without identities or payloads. SSH still carries ordered TCP traffic; real network head-of-line blocking and finite bandwidth remain physical limits.

Pointer traces showed about140-177 forwarded offers/s versus70-89 native generic events/s during movement. Activity and Compose both forward hover. Identical pure-motion position/mask duplicates are now removed; changed positions, button transitions and wheel ticks remain intact. Existing pending-motion coalescing and key/button ordering are retained. A new debug-only written-event counter distinguishes offers from actual encrypted sends. Polling/input frequencies were not raised; doing so would add traffic to a blocked path.

AVC assembly discarded incomplete pictures but could then submit dependent pictures built against damaged references, producing corruption until recovery. The decoder now holds its last clean output until a complete IDR after loss, source changes or input starvation. Keyframe requests are serviced on the100ms control loop with a500ms minimum interval rather than waiting for a two-second recovery slot. No idle keyframe polling is added after healthy startup. Media health logs include authenticated packets, decoded frames, local queue/input drops and assembly/IDR-suppression counters.

## Validation

Evidence: `build/verification/mac-latency/` (ignored). The original device logs and input traces remain private there; this report omits setup identifiers.

- CLI `assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`: PASS,85 JVM tests,0 lint errors; warnings remain.
- `tools/verify-mac-relay-backpressure.py --perl <perl>`: owned local TCP/UDP endpoints only. On this Windows host use `--loopback-fixture`, which relaxes only the loopback-target rejection in a temporary fixture copy; shipped target filtering is unchanged. Original helper timed out on control when media stdout stalled. Updated helper: control echo0ms during the deliberate stall,1MiB lossless TCP transfer and half-close,100 intact framed packets, queue2,050,048bytes and3,487 media drops under forced congestion. Git Perl syntax check PASS. This is a helper reactor test, not native macOS/Cloudflare latency validation.
- Quest3 app/test APKs installed. `vncMode macAvcSourceFixture`: hardware AVC holds the clean blue image through a missing packet and21 dependent pictures, then resumes with an IDR; source-overlap, wrap, duplicates and reorder checks PASS. Captures inspected.
- `vncMode macDualFixture`, `firstFrameFixture`, `inlineFixture`, and `inputOnly`: PASS concurrent bridge admission/decoder isolation, connecting cover, native VNC rendering/inputs/cleanup, and controller/pointer regressions. Launcher restored.

## Remaining live checks

Open both real Macs again. For the tunneled Mac, check initial login visibility without clicking/resizing, then slow/fast text selection, held-button dragging, release and scrolling. These symptoms have not been declared fixed by fixture checks. Its virtual-session/window-server login presentation remains an open defect if startup still needs a resize. Inspect new helper/media/input counters alongside the symptom before further tuning. Audio remains a separate unresolved investigation.
