# Architecture: MultiDesk Explorer

## Product Vision
**MultiDesk Explorer** is a native RDP and SSH client for Meta Quest (Horizon OS). Its defining feature is that every remote session and RemoteApp is its own spatial window, which can be placed freely in the standard Home/passthrough environment without entering a custom VR world. It is designed as a plain-language, no-bells-and-whistles productivity utility.

## Module Map
1. **App / UI Layer (`app`)**: Jetpack Compose and Meta VR Layout SDK for Compose Spatial Windows. Manages the connection launcher (main panel) and spawns new Spatial Windows for each session.
2. **Session Manager**: Coordinates active connections, handles panel lifecycle (spawning/closing windows), and routes input. Responsible for deciding whether to spawn a new FreeRDP instance or mirror an existing session's video decode.
3. **RDP Core (`freerdp-jni`)**: JNI bridge to FreeRDP 3.27.1+. Handles session lifecycle, video stream decoding (MediaCodec AVC/H.264), input event injection, RAIL window mapping, and camera redirection (`rdpecam`).
4. **SSH / Terminal Core (`ssh-core`)**: SSHJ for secure transport, combined with Termux's GPLv3 terminal-emulator stack for best-in-class VT100/xterm emulation.
5. **Settings & Storage**: Encrypted credential storage via Android Keystore (biometric/PIN unlock), saved host configurations, and user preferences (e.g., camera choice).
6. **Network & Proxy Subsystem**: Manages spawning local child processes (such as a bundled `cloudflared` binary or custom proxy commands). Supports both ephemeral tunnels tied to a session's lifecycle and persistent background tunnel services (via Android Foreground Services).

## Windowing Model
- **Spatial Windows**: Built using the VR Layout SDK. The main app panel acts as a connection manager. Connecting to a host spawns a new independent Spatial Window.
- **RAIL Support**: Each RemoteApp published via RAIL appears as its own floating window, with server-authoritative z-order and transparency support.
- **Lifecycle**: Closing a session window terminates the connection. For mirrored windows, closing one mirror panel only destroys that panel; closing the last panel terminates the connection.

## Input Pipeline
- **Quest Controllers**: Raycast to window coordinates and translated into RDP mouse events.
- **Gestures**: Pinch and scroll gestures for panning and zooming desktop windows.
- **Keyboard**: Meta virtual keyboard (IME) integration with an on-screen extra-keys row (Ctrl, Alt, Win, Esc, Tab, arrows).

## Camera Redirection (`rdpecam`)
- Uses the Horizon OS Passthrough Camera API (`HEADSET_CAMERA` + `CAMERA` permissions).
- **Primary Source**: Avatar virtual webcam (front/selfie camera). This is the default so remote Teams/Zoom participants see the user's Meta Avatar.
- **Secondary Source**: Outward room/passthrough cameras.
- **UX**: An in-app camera picker allows users to choose the camera source per session.

## Security
- **Credentials**: Stored in the Android Keystore.
- **NLA/CredSSP**: FreeRDP must be built with `WITH_INTERNAL_MD4`, `WITH_INTERNAL_RC4`, and `WITH_INTERNAL_MD5` to ensure NLA succeeds.
- **SSH**: Trust On First Use (TOFU) host-key verification.

## Threading Model & Performance
- Each RDP session decodes its own video stream on a background thread using hardware acceleration (MediaCodec) where possible.
- **Target**: 2–4 concurrent sessions on Quest 3.
- **Degradation strategy**: Background windows drop to lower framerates or pause decoding if resource limits are reached.

---

## Phased Build Plan

**Build-ordering rule: De-risk first.** The highest-risk item is the FreeRDP NDK build and the JNI bridge. This must be spiked and validated before anything else.

### Spike Phase: JNI & Native Bridge
- **Goal**: Successfully compile FreeRDP 3.27.1+ via Android NDK and render a single session frame to an Android Surface.
- **Gotchas**: Set `HOME` env dir to `filesDir` before init; compile with internal crypto flags.
- **Acceptance**: A rudimentary Android activity connects to an RDP server and renders the desktop.

### Phase 1: Basic RDP
- **Goal**: Connect to one Windows PC, full desktop in a spatial window, mouse/keyboard input, securely saved credentials.
- **Acceptance**: Shippable single-session RDP client on Horizon OS.
- **Deferred**: Multiple sessions, RemoteApp, Webcam, SSH.

### Phase 2: Multiple Sessions
- **Goal**: N concurrent RDP sessions.
- **Implementation**: Allow user choice when reconnecting to an active host:
  - *New Independent Session*: Spawns a new FreeRDP instance (requires server CALs or multiple accounts).
  - *Mirror Session*: Spawns a new Spatial Window observing the same video decode stream from an existing session.
- **Acceptance**: Two desktop windows visible simultaneously in passthrough.

### Phase 3: RemoteApp Support (RAIL)
- **Goal**: Individual remote applications appear as their own floating windows.
- **Acceptance**: Launching a RemoteApp opens a new Spatial Window without the full desktop background.

### Phase 4: Webcam Support
- **Goal**: Redirect Quest cameras into the RDP session (`rdpecam`).
- **Acceptance**: Remote Teams/Zoom can access the Meta Avatar virtual webcam or room camera.

### Phase 5: SSH Windows
- **Goal**: Terminal sessions alongside RDP windows.
- **Acceptance**: SSH connection opens a window running the Termux VT emulator.

### Phase 6: Tunneling & Proxies
- **Goal**: Support tunneling both RDP and SSH traffic through Cloudflare Tunnels (`cloudflared`) and arbitrary proxy commands (similar to PuTTY), plus persistent background tunnels.
- **Implementation**: Bundle the `cloudflared` arm64 binary. For ephemeral tunnels, spawn the proxy process on a local port before connection and direct FreeRDP/SSHJ to `localhost:<port>`. For persistent tunnels, host `cloudflared` inside an Android Foreground Service so the connection remains active even when no session windows are open.
- **Acceptance**: Users can specify an ephemeral proxy command per connection, or configure persistent `cloudflared` tunnel services that run in the background.

---

## Open Decisions & Resolutions
- **App Name**: Resolved as **MultiDesk Explorer**.
- **Phase 2 (Multiple Sessions)**: Resolved to support **both** true independent sessions and duplicated/mirrored sessions, depending on user choice at launch time.
- **Business Model & SSH Emulator**: App will be **Open-Source (Paid in store)**. Therefore, we will use the **Termux (GPLv3)** stack for terminal emulation, which provides superior emulation and is compatible with an open-source model.
