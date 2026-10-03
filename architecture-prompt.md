# Architecture prompt — Quest RDP + SSH client

(new app, no name chosen yet; repo to be created under jmez11)

## Session goal

You are running an **architecture and design session** for a brand-new native
Quest app. Do not write implementation code yet. Your deliverable is a
complete architecture document plus a phased build plan the user can hand to a
coding agent afterward.

## Product vision

A combo **RDP + SSH client for Meta Quest** (Horizon OS). The defining
feature: **every session is its own window, and windows can be placed freely
in the standard Home / passthrough environment** — stuck wherever the user
wants, like virtual monitors, without entering a custom VR world.

Planned phases, in order:

1. **Basic RDP** — connect to one Windows PC/server, full desktop in a
   window, mouse/keyboard input from controllers, credentials saved securely.
2. **Multiple sessions/desktops for the same server** — N concurrent RDP
   sessions, each in its own window. (Design question: N independent
   sessions vs. one session mirrored/duplicated across panels — decide and
   justify.)
3. **RemoteApp support** — RAIL seamless windows; each published remote app
   appears as its own floating window in Home/passthrough.
4. **Webcam support** — redirect the Quest cameras into the RDP session so
   remote apps (Teams/Zoom on the remote PC) can use them.
5. **SSH windows** — terminal sessions, each in its own window, alongside the
   RDP windows.

Plain-language, no-bells-and-whistles UX in the spirit of the user's other
app (DeadEasy Player). This is a productivity utility, not a game.

## Mandated stack and constraints

- Native Android app (Kotlin, Jetpack Compose), Meta Spatial SDK (0.14+).
- Windowing: **Meta VR Layout SDK for Compose "Spatial Windows"**
  (declare additional windows alongside the main panel, each rendering its
  own content, in the Horizon OS home environment) and/or Spatial SDK
  multi-panel. Windows must be user-movable/pinnable in Home and passthrough.
- **Do NOT implement the RDP protocol from scratch.** Build on **FreeRDP 3.x
  (Apache-2.0)** via the NDK/JNI, using the aFreeRDP Android client
  (`client/Android` in the FreeRDP repo) as the reference integration.
- **Do NOT implement the SSH protocol from scratch.** Use **SSHJ
  (Apache-2.0)** for SSH transport.
- Git identity is `jmez11 <40301782+jmez11@users.noreply.github.com>`.
  **Never run `git config user.name` / `git config user.email`, and never
  change the git identity for any reason.** If it is wrong, stop and report
  it instead of changing it.
- Target the same build/release conventions as the user's other native Quest
  app where they fit (arm64-only, signed release CI); note where RDP/SSH
  changes that (e.g. NDK components in CI).

## Research findings you must incorporate

- FreeRDP 3.27.1 (June 2026) added **RAIL/RemoteApp window support to the
  Android client** (upstream PR #12887: remote windows as floating overlays,
  server-authoritative z-order, move/resize, transparency for menus/tooltips)
  and **camera redirection (`rdpecam`) for the Android client** (#12894).
  Prefer a FreeRDP version ≥ 3.27.1 and lean on these instead of reinventing
  them.
- Android NDK gotchas (from a project shipping FreeRDP on Android):
  `setenv("HOME", filesDir)` before first init (Android never exports HOME);
  build with `WITH_INTERNAL_MD4`, `WITH_INTERNAL_RC4`, `WITH_INTERNAL_MD5`
  set to ON, otherwise NLA/CredSSP collapses with a generic
  `CONNECT_TRANSPORT_FAILED`.
- Multiple concurrent RDP sessions to one server are just N parallel
  FreeRDP instances — the limit is server-side licensing: Windows client
  SKUs allow 2 concurrent remote sessions; more needs Windows Server + RDS
  CALs. A second connection to the same user account can take over the
  existing session depending on server config — the design must handle
  session-takeover semantics explicitly.
- Quest camera access is now public: **Passthrough Camera API**,
  `horizonos.permission.HEADSET_CAMERA` + `android.permission.CAMERA`, via
  the standard Android Camera2 API, shippable to the Horizon Store. **But the
  preferred camera source is the avatar virtual webcam:** since Horizon OS
  v76, any Android app requesting the **selfie camera** gets a system
  virtual webcam rendering the user's **Meta Avatar** (head rotation, hand
  movement, mic-driven lip sync; background/FOV configurable in the OS
  'Selfie cam' settings). FreeRDP's `rdpecam` should therefore default to
  the front/selfie camera so remote Teams/Zoom participants see the user's
  avatar, not their room. The app must let the user **choose between camera
  sources** (avatar virtual webcam vs. outward passthrough cameras) per
  session, via an in-app camera picker. Offer the outward passthrough cameras only as a
  secondary "room camera" option. The architecture must account for the
  privacy-sensitive permission (runtime consent, store review justification)
  and the perf cost of camera streaming + RDP decode simultaneously.
- **Terminal emulator license fork (SSH phase):** Termux's
  `terminal-view`/`terminal-emulator` is the best Android VT emulator but is
  **GPLv3** — incompatible with a paid closed-source app. ConnectBot's
  emulation stack is Apache-2.0 but dated. Writing a VT100/xterm emulator
  from scratch is months of work. The architecture must pick one of these
  three explicitly and justify it.
- Input: Quest controllers → RDP mouse/keyboard events; on-screen
  Ctrl/Alt/Win modifiers and a full PC keyboard for RDP; an extra-keys row
  (Esc/Tab/Ctrl/arrows) above the IME for SSH. Pinch/scroll gestures for
  pan/zoom of desktop windows.
- Each RDP session decodes its own video stream (AVC/H.264 via MediaCodec
  where possible) — per-session CPU/battery cost is the main scaling limit
  on how many concurrent windows are practical on Quest 3 hardware. The
  design should state a target (e.g. 2–4 concurrent sessions) and how it
  degrades past that (pause background windows, drop to lower fps).

## Deliverable

1. **Architecture document**: module map (session manager, per-window
   rendering, FreeRDP JNI bridge, RAIL window mapper, camera redirect,
   SSH/terminal stack, credential storage via Android Keystore, settings
   persistence), windowing model (how sessions map to Spatial Windows /
   panels, lifecycle when windows are closed/moved), input pipeline,
   threading model, and security notes (credential storage, NLA, host-key
   verification for SSH).
2. **Phased build plan**: the five phases above, each with acceptance
   criteria and what is explicitly deferred. Phase 1 must be a shippable
   single-session RDP client. **Build-ordering rule: de-risk first.** The
   highest-risk integration is the FreeRDP NDK build plus the first session
   rendering on-device through the JNI bridge. Spike and validate that
   before designing or building the windowing layer, session manager, or
   anything else — if the native bridge fights back, we find out in week
   one, not in phase 3.
3. **Open decisions list**: anything you could not resolve (e.g. the
   terminal-emulator license choice, session-vs-mirror for phase 2),
   each with your recommendation.

Ask the user at most three clarifying questions before writing, only for
things that materially change the architecture (e.g. app name, whether
phase 2 needs true independent sessions vs. mirroring, paid vs. free).
Everything else, decide yourself and document the decision.

## Agent working notes (maintain these yourself)

You will work across many sessions and models, and context windows expire.
Maintain your own markdown notes in the repo so any future session can pick
up without reloading full transcripts:

- `AGENTS.md` — standing rules only (stack, conventions, the git-identity
  rule). Written once, amended rarely. Never let rolling progress notes
  accumulate here.
- `ARCHITECTURE.md` — decisions you made and why. Update it when a decision
  changes; rewrite sections rather than appending endless entries.
- `PROGRESS.md` — what is done, what is next, what is blocked. Keep it
  tight: a checklist, not a diary. Update it at every natural checkpoint
  (end of a task, before a long build, when you stop).

Rules for these notes:

- When you start a session, read all three before doing anything else.
- Never trust old notes blindly — verify claims against the actual code
  before acting on them. If a note is wrong, fix the note.
- Keep each file short enough to read in one go. If one grows past ~200
  lines, condense it.
- The deliverable architecture document from this session becomes the first
  version of `ARCHITECTURE.md`.
