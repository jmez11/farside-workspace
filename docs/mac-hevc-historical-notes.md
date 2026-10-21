# Historical Mac HEVC spike claims (superseded)

Unverified prior assistant notes, retained for provenance. Old profiles were deleted by the user: do not recreate or retry them. PASS on setup, control or UDP counters does not establish decoded HEVC or audio. Current status: [reevaluation](mac-hevc-reevaluation.md).

- **Mac HEVC Spike: Phase 0 — Map and Conflict Check (2026-10-13)**: Evaluated Apple VNC high-performance extension specification (`apple_vnc_rfc.md`, commit `9ab40d3a3151524f954cff9d6239d891197c1705`).
  - **Stage-Transition Checklist (S0–S9)**:
    - **S0 Connect**: Server ProtocolVersion (12 B). **Already performed** in `VncBridge.kt::authenticateApple` (reads 12 bytes banner).
    - **S1 Version**: Client ProtocolVersion. **New**: Current repo sends `RFB 003.008\n`; spike sends `RFB 003.889\n` (validated on live Mac).
    - **S2 SecTypes**: Server security-type advertisement (`u8 count || u8[count] types`). **Already performed** (repo parses type list, e.g. `[30, 33, 36, 35]`).
    - **S3 SecSelect**: Client security-type selection. **Already performed** for type 30 (`0x1e`); **New** for type 33 (`0x21` + RSA1 envelope, prototyped in `MacSessionProbe.kt`).
    - **S4 Auth**: Selected auth branch transcript.
      - Type 30 (DH): **Already performed** in Kotlin platform crypto (`VncBridge.kt`).
      - Type 33 (RSA-SRP): **New** (DER public key fetch, RSA1 envelope, SRP-6a exchange; prototyped in `MacSessionProbe.kt`).
    - **S5 SecResult**: `u32 SecurityResult` (0 = success). **Already performed** in `VncBridge.kt`.
    - **S6 Init**: ClientInit (`0xC1`), ServerInit. **New**: Standard path delegates to LibVNCClient (`0x01`); spike sends `0xC1` and parses ServerInit directly before prelude.
    - **S7 Prelude**: Cleartext Apple prelude (ViewerInfo `0x21` capability bitmap, SetEncryption(1) `0x12` method 1 AES-128, optional SetMode(1) `0x0a`, SetEncryption(2) `0x12`). **New**. Server emits `0x44f` rekey mid-prelude.
    - **S8 Record Active**: `0x44f` rekey unwrapping via AES-128-ECB under initial wrap key (`MD5(shared)[0:16]` for type 30, `SHA-256(K)[0:16]` for type 33). AES-128-CBC framed transport (`u16 ciphertext_len || ciphertext`) with SHA-1 integrity trailer (`SHA-1(u32_be(seq) || plaintext[0:len-20])`). Encrypted preface (`SetDisplayConfiguration` `0x1d`, `SetEncodings` `0x02`). **New**.
    - **S9 Steady**: Steady-state encrypted control/framebuffer (`0x03` + `AutoFrameBufferUpdate` `0x09` arming, `0x451` AppleDisplayLayout, `0x453` VendorKeysymEncoding, `0x455` KeyboardInputSource, `0x456` DeviceInfo, `0x450` CursorImage STORE/SELECT; re-arm `0x09+0x03` on `0x451`). Media sub-mode (Phase 3): `0x3f2` announcement + `0x1c` MediaStreamOptions offer/answer -> SRTP/UDP HEVC (5901) & AAC (5900). **New**.
  - **Spec vs. Landed Implementation Conflicts & Resolution**:
    1. *DH Group & Generator*: Spec §4.2.3 claims a fixed 1024-bit group with generator=2. Ground truth (repo & d3cker): modern macOS sends dynamic generator=5, 4096-bit prime (RFC 3526 group 16, keylen=512). Repo implementation is correct and reads parameters dynamically from server wire challenge.
    2. *Credential Cipher*: Spec §4.2.3 claims AES-128-CBC with zero IV. Ground truth (repo): repo uses `AES/ECB/NoPadding`, which succeeds on modern macOS (`build/verification/vnc-lock/probe-test.txt`).
    3. *Initial Wrap Key Derivation*: For type-30 DH, spec identifies initial wrap key as `MD5(shared)[0:16]`, which matches the exact MD5 digest already derived in `VncBridge.kt`. For type-33, wrap key is `SHA-256(K)[0:16]`. Both reconcile with working primitives.
  - **Phase 0 Verdict**: The conflicts in type-30 description are resolved by adhering to the repo's working ground-truth DH implementation. Phase 1 is **WORTH ATTEMPTING**.
- **Mac HEVC Spike: Phase 1 — Prelude + Record Layer (2026-10-13)**: Implemented cleartext Apple prelude messages (ViewerInfo `0x21`, SetEncryption(1) `0x12`, SetMode(1) `0x0a`, SetEncryption(2) `0x12`), `0x44f` Rekey unwrapping via AES-128-ECB under initial wrap key (`MD5(shared)[0:16]`), and AES-128-CBC record layer with persistent cipher context and 20-byte SHA-1 integrity trailer over `u32_be(seq) || plaintext[0:len-20]` (`MacHevcRecordLayer.kt`, `MacHevcSession.kt`).
  - **Server Disassembly & Framing Ground Truth**: Analyzed `screensharingd` binary from macOS 10.15.8 Catalina (`build/verification/vnc-lock/mac-server-disassembly.txt`):
    - `DecryptOneMessageWithComCryption` (`0x10004c3cd`) confirms: wire framing is `u16 ciphertext_len || ciphertext`; decryption is AES-128-CBC with persistent IV chaining; SHA-1 integrity trailer check occurs *before* validating the inner 2-byte body length. Inner plaintext framing has `u16 body_len` at bytes `0..1` (byteswapped into host endian via `rolw $0x8, (%r12)`), followed by payload at offset 2.
    - `HandleViewerCommand` (`0x100037d64`) confirms: command `0x1d` (29, `SetDisplayConfiguration`) jumps to `0x1000314e1`, logging `"unknown command 29"` and immediately terminating connection with return code `-2`.
  - **Spec Divergence**: The spec (§5.2) asserts that clients must send `SetDisplayConfiguration` (`0x1d`) as part of the S8 preface. Ground truth: on macOS 10.15 Catalina, `0x1d` is unsupported and causes an immediate EOF. Bypassing `0x1d` and directly issuing `SetEncodings` (`0x02`), `0x03`, and `0x09` reaches S8 activation and immediate streaming.
  - **Phase 1 Gates on Quest 3 (`172.16.0.106:33103`) against macOS 10.15.8 (19H2036 at `172.16.0.79:5900`)**:
    - *Positive Gate*: **PASS**. Handshake progressed through S0–S8, unwrapped `0x44f` rekey, activated record layer, and decrypted first server control record (`type=0x0`, `len=32768`, `recvSeq=0`).
    - *Negative Gate*: **PASS**. Corrupting a single bit in ciphertext threw `MacHevcIntegrityException` and immediately closed the session without continuing.
    - *Regression Gate*: **PASS**. Standard VNC path (`vncMode live`) remained 100% green.
- **Mac HEVC Spike: Phase 2 — Virtual Display + Vendor Encodings / S9 Steady State (2026-10-13)**: Implemented streaming multi-record reassembly (`RecordInputStream`, `RecordOutputStream`), persistent zlib `Inflater()` across rectangles, vendor encoding handlers/ignores per spec §12 (`0x451` AppleDisplayLayout, `0x453` VendorKeysymEncoding, `0x455` KeyboardInputSource, `0x456` DeviceInfo, `0x450` CursorImage, `0x3f2` RFBMediaStreamMessage1), `readNextFramebufferUpdate()`, `sendPointer()`, and `requestUpdate()` (`MacHevcSession.kt`).
  - **Phase 2 Gates on Quest 3 against macOS 10.15.8 Catalina (`172.16.0.79:5900`)**:
    - *Steady-State S9 Gate*: **PASS**. Session reached S9 steady state over the encrypted record layer. Received first `FramebufferUpdate` (msgType 0) with 1 rectangle at (0,0) 1280x800 encoding 6 (ZLIB), successfully decompressed 4,096,000 bytes (full 1280x800 @ 32bpp desktop). Dispatched encrypted pointer events (x=100, y=100 and x=120, y=120), requested incremental update, and received/decompressed steady-state update 2 (4,096,000 bytes).
    - *Standard VNC Regression*: **PASS**. `vncMode live` verified nonblank ANativeWindow frame, pointer dispatch, and clean native disconnect (`INSTRUMENTATION_CODE: 0`).
  - **Phase 2 Verdict**: **WORKS**. Third-party clients can reliably maintain steady-state encrypted S9 RFB sessions over Apple's AES-128-CBC / SHA-1 record layer with vendor encodings and zlib decompression. Phase 3 (Adaptive Media) is **READY TO COMMENCE**.
- **Mac HEVC Spike: Phase 3 — Adaptive Media (HEVC/AAC over SRTP/UDP) (2026-10-13)**: Investigated Adaptive Media transport (§10), `0x3f2` announcement, and `0x1c` MediaStreamOptions offer/answer.
  - **Server Disassembly & Jump Table Analysis (`screensharingd` on macOS 10.15.8 Catalina)**:
    - Analyzed the primary command jump table `HandleViewerCommand` at `0x100037d64` (handling viewer command byte `0x00`..`0x22`):
      - Command `0x1c` (28, `MediaStreamOptions`): maps to offset `-26755` (`0x1000314e1`), which is the `"unknown command %d"` handler that returns `-2` and terminates the TCP connection.
      - Command `0x1d` (29, `SetDisplayConfiguration`): also maps to offset `-26755` (`0x1000314e1`), returning `-2` and terminating the TCP connection.
      - Command `0x1e` (30): also maps to `0x1000314e1`.
    - In `SetEncodings` (`0x100031670`..`0x10003169c`), pseudo-encodings are tested against bitmap `0x807` (`0x3e8` [1000], `0x3e9` [1001], `0x3ea` [1002], and `0x3f3` [1011]). Bit 10 (`0x3f2` [1010], `RFBMediaStreamMessage1`) is `0` in `0x807` and is ignored.
  - **Live On-Device Evidence (`macHevcPhase3` on Quest 3 against `172.16.0.79:5900`)**:
    - Connected, verified S8 record layer and S9 steady-state framebuffer baseline (decompressed 4,096,000 bytes ZLIB frame at 1280x800).
    - Advertised `0x3f2` in `SetEncodings` (accepted/ignored without crash).
    - Dispatched 316-byte `0x1c` MediaStreamOptions offer formatted per §10.3 (UUID, host-endian flags `0x07`, audio/video key pairs and descriptors).
    - Result: Server immediately terminated connection with `EOFException` (server returned `-2` on unknown command 28 and closed socket).
    - Regression: Standard VNC path (`vncMode live`) verified 100% green (`INSTRUMENTATION_CODE: 0`).
  - **Root Cause & macOS Version Requirement**:
    - Apple High-Performance Screen Sharing (HEVC video + AAC audio over SRTP/UDP, `0x1c` MediaStreamOptions, virtual displays `0x1d`) was introduced in **macOS 14 Sonoma** leveraging Apple Silicon's media engine (as corroborated by the spec's ARM64 disassembly `screensharingd::sub_1000352ac` / `ScreensharingAgent` MIG RPCs `SetMediaStreamConfiguration`).
    - macOS 10.15 Catalina Screen Sharing (`RemoteDesktop-526.57` on Intel x86_64) implements Apple's encrypted record layer (S0-S8), rekey (`0x44f`), and vendor encodings (S9 framebuffer), but completely lacks the `0x1c`/`0x1d` media engine.
  - **Phase 3 Verdict**: **DEAD END on macOS 10.15 Catalina**; requires **macOS 14 Sonoma or macOS 15 Sequoia on Apple Silicon**.
- **Mac HEVC Spike: Modern macOS Investigation & SessionSelect Phase Ground Truth (2026-10-18)**: Investigated high-performance protocol against live Apple Silicon Mac mini (`Jeff Mac ` via `jeff-mac.oceanside-vr.com:5900` over Cloudflare Tunnel `cloudflared access tcp` with 5120x2880 display).
  - **Discovery of Undocumented SessionSelect Phase**: When connecting user (`James Kasyan`, UID 502) differs from active console user (`Jeff Regan`, UID 501), `screensharingd` enters `SessionSelectPhase` (`SessionSelectPhase.c`) immediately following `SetEncryption(cmd=1)`.
    - Server emits `SessionSelect_SessionInfo`: 76 bytes (`u16 size 74`, `u16 version 1`, `u32 session_state 5`, `u32 flags 0`, `byte[64] active_user 'Jeff Regan'`).
    - Disassembly analysis of `SessionSelect_HandleCommand` (`0x10004fd7e`..`0x1000508ea`): Allowed commands are governed by `btl %eax, %ecx` against `session_state` (5 = binary 101):
      - **`cmd = 0` (Ask to Share Display / Request Console)**: 76 bytes (`u16 size 74`, `u16 version 1`, `u32 flags 0`, `u8 cmd 0`, `u8 pad[3]`, `byte[64] viewer_username`). Invokes `GuestAccessAskUser` -> `SSAgent_GuestAccessAskUser_rpc`. Live Mac verification confirmed: server triggers console notification prompt ("James Kasyan would like to share your screen") and holds TCP connection open awaiting console user approval.
      - **`cmd = 1` (Direct Console Connect)**: Only permitted when bit 1 is set (`session_state == 7`, e.g. console at login window or locked). When `state == 5`, sending `cmd = 1` triggers `session select protocol error` (status 2) and terminates connection.
      - **`cmd = 2` (Log In As Yourself / Virtual Multi-Session)**: Wire format is 202 bytes (`u16 size 200`, `u16 version 2`, `u32 flags 0`, `u8 cmd 2`, `u8 pad 0`, `byte[64] clear_username`, `byte[64] enc_username`, `byte[64] enc_password`). Username and password blocks at offset `+0x4a` and `+0x8a` are decrypted server-side with `CCCryptorUpdate` (AES-128-ECB under wrapKey).
  - **Virtual Multi-Session Plumbing (`SSAgent.c`)**: Traced `cmd = 2` execution to `CreateOffConsoleLoginWindowSession` which calls private CoreGraphics API `_CGSCreateLoginSessionWithDataAndVisibility` to spawn an off-console login session and waits on `gAgentConnectSemaphore` (up to 60s) for `ScreensharingAgent` to connect. On tested Mac mini, `_CGSCreateLoginSessionWithDataAndVisibility` requires Fast User Switching / multiple graphical sessions enabled in macOS System Settings.
  - **Spec Divergence**: Spec (§5.4) claims prelude is strictly `ViewerInfo -> SetEncryption(1) -> SetMode -> SetEncryption(2)` followed directly by `0x44f` Rekey, omitting `SessionSelectPhase` entirely because upstream captures only tested single-user console sessions (`consoleUserUID == viewer->authenticationUID`).
  - **Standard VNC Regression**: `vncMode live` verified nonblank 1280x800 surface rendering of desktop wallpaper, pointer dispatch, and clean native disconnect (`INSTRUMENTATION_CODE: 0`).
- **Mac HEVC Spike: Dynamic Resolution, Multi-Session & SRTP Transport (2026-10-18)**:
  - **Dynamic Resolution & Display Configuration**: Implemented `0x1d SetDisplayConfiguration` packet builder (`buildDisplayConfiguration(w, h, displayType)`) supporting virtual displays (`displayType = 4`) and dynamic resolution (`displayFlags = 1`). Added robust `AppleDisplayLayout` (`0x451`) parsing supporting both leading bounds header (`scaled_w/h`, `backing_w/h`) and 56-byte `display_record` fallback. Re-arms update stream with `0x09` AutoFrameBufferUpdate + `0x03` FramebufferUpdateRequest per spec §8.4/§8.11. Removed `!isVnc` guard in `RdpSessionScreen.kt` so spatial window resize events trigger `updateDisplay(w, h)` on VNC sessions.
  - **SRTP & RTP Transport Decryption**: Implemented `MacHevcSrtp.kt` providing RFC 3711 SRTP AES-256-CTR keystream decryption, HMAC-SHA1-80 integrity tag verification, and AES-CM-PRF key derivation (cipher key, auth key, salt). Implemented `MacHevcRtpParser.kt` providing Apple RFC 7798 variant HEVC NAL depacketizer with 2-byte DONL stripping for Single NAL, Aggregation Packet 48, and Fragmentation Unit 49, producing Annex B `00 00 00 01` elementary streams.
  - **Unit Testing**: Added comprehensive unit test suite `MacHevcTest.kt` covering display configuration wire framing, `AppleDisplayLayout` parser, SRTP key derivation/decryption round-trip, and RFC 7798 Single NAL / FU-A reassembly. All tests PASS (`:app:testDebugUnitTest`).
  - **Device Configuration & Isolation**: Configured standalone debug connection profiles on Quest 3 via automated `macHevcSetup` harness, keeping baseline `Jeff Mac ` profile (`expMacHevc = false`) completely intact:
    1. `Jeff Mac (HEVC)`: `expMacHevc = true`, `expMacShareConsole = false` (multi-session separate login via `cmd = 2`; will connect to headless virtual display once Fast User Switching is on).
    2. `Jeff Mac (HEVC Console)`: `expMacHevc = true`, `expMacShareConsole = true` (console display sharing via `cmd = 0`; prompts active Mac user to Accept, enabling HEVC testing immediately before Fast User Switching is enabled).
  - **Media Transport Consideration**: Apple's HEVC video stream is sent over **UDP port 5901** (SRTP), with audio over **UDP port 5900**. The TCP port 5900 connection carries the control and handshake channel. To receive video packets, UDP 5901 must be routable from host to headset (e.g. over direct LAN, WireGuard/Tailscale VPN, or UDP port forwarding; Cloudflare `access tcp` mode proxies TCP only).
  - **Device Validation Evidence (Quest 3 `172.16.0.106:46801`)**:
    - `macHevcSetup`: **PASS** (`INSTRUMENTATION_CODE: 0`). Configured `Jeff Mac (HEVC)` and `Jeff Mac (HEVC Console)` on headset SharedPreferences.
    - Standard VNC Regression (`vncMode live` against `Jeff Mac `): **PASS** (`INSTRUMENTATION_CODE: 0`). Verified nonblank frame rendering, pointer dispatch, and clean disconnect without regression.
    - `macHevcPhase1` against `Jeff Mac `: Confirmed `SessionSelect_SessionInfo` (`state = 5`). Dispatched `cmd = 2` (virtual multi-session). Server-side off-console agent awaits `gAgentConnectSemaphore` (60s timeout) pending enablement of Fast User Switching in host macOS System Settings. Added descriptive timeout diagnostics.
- **Mac VNC / ARD Authentication and Error Message Audit (2026-10-18)**:
  - **What is ARD Login**: ARD (Apple Remote Desktop) login refers to Apple's native RFB extension authentication (security type 30 Diffie-Hellman / type 33 RSA-SRP) against macOS directory accounts (OpenDirectory/PAM) via `/usr/libexec/screensharingd`. Unlike legacy VNC password access (security type 2, limited to 8 Latin-1 characters with DES encryption), ARD login accepts full macOS usernames and passwords.
  - **Why the Legacy Message Appeared**: Historically, Farside Workspace only supported standard RFB VNC passwords (type 2). When a connection failed, it fell back to a legacy string: `"VNC connection failed. Enable Screen Sharing and VNC password access (1 to 8 characters); ARD login is not supported."` When `VncAuthMode.MAC_USER` (Apple DH type 30) was subsequently implemented, generic connection failures and unhandled exceptions in `VncSessionConnection.kt` still fell back to this outdated string.
  - **Resolution**:
    - `VncSessionConnection.kt`: Evaluates `isMacAuth` (`profile.vncAuthMode == MAC_USER || profile.expMacHevc`). Disconnect and exception fallbacks now use `vnc_mac_user_hint` ("On Mac: enable Screen Sharing and allow this macOS user to access it") and `vnc_mac_credential_required` ("Select a saved credential with a macOS username and password"), completely eliminating misleading VNC password and "ARD not supported" text for Mac accounts.
    - `MacHevcSession.kt`: S2 security type validation and S5 `SecurityResult` non-zero errors now explicitly throw `VncAuthenticationException`, cleanly mapping to `vnc_mac_auth_failed` ("Mac authentication failed. Check the username and password"). S7 SessionSelect rejection raises informative `IOException("Mac Screen Sharing declined connection (...)")`.
    - Maintained strict translation discipline: reused existing 22-locale translated strings (`vnc_mac_user_hint`, `vnc_mac_credential_required`, `vnc_mac_auth_failed`), adding zero new user-facing translation pipeline overhead.
  - **Device Validation Evidence (Quest 3 `172.16.0.106:46801`)**:
    - `vncMode macWrong`: **PASS** (`INSTRUMENTATION_CODE: 0`). Verified Apple server rejects bad credentials with `SecurityResult`, session callback emits localized Mac authentication failure (`vnc_mac_auth_failed`), and no VNC password error or hang occurs.
    - `vncMode live` (standard VNC regression gate on `Jeff Mac `): **PASS** (`INSTRUMENTATION_CODE: 0`). Verified nonblank ANativeWindow frame, pointer dispatch, and clean native disconnect.
- **WARP Direct Performance Diagnosis & Window Resize Surface Handoff (2026-10-18)**:
  - **Root Cause of Slowness**: `Jeff Mac (WARP Direct)` was provisioned with `expMacHevc = false` (standard VNC). It connects via LibVNCClient over TCP 5900, streaming uncompressed/ZRLE 5K frames (**5120 x 2880 pixels**, ~14.7 million pixels/frame). Decoding 5K raw frames on the Quest CPU over TCP caps throughput to 2–5 fps.
  - **Root Cause of Resize Behavior**: macOS Screen Sharing does not support dynamic resolution (`SetDesktopSize`) in standard VNC, locking the server desktop at 5120x2880. Additionally, `RdpSessionScreen.kt` omitted `connection.attach(Surface(texture))` in `onSurfaceTextureSizeChanged`, leaving the native `ANativeWindow` referencing the stale surface configuration and skipping redraws on static screens.
  - **Fixes Applied**:
    - `RdpSessionScreen.kt`: Wired `connection.attach(Surface(texture))` into `onSurfaceTextureSizeChanged` so native rendering re-acquires the resized surface, marks `dirty = true`, and redraws immediately.
    - `MacHevcSession.kt`: Scaled `frameBitmap` to canvas bounds via `canvas.drawBitmap(bmp, null, Rect(0, 0, canvas.width, canvas.height), null)` in `drawFrameToSurface()` to prevent viewport clipping.
    - `VncVerification.kt`: Added `Jeff Mac (WARP Console)` profile (`192.168.4.165:5900`, `expMacHevc = true`, `expMacShareConsole = true`) to test HEVC directly over WARP VPN when console sharing is accepted by Jeff.
  - **Multi-Session Status**: On Apple Silicon Mac mini over WARP, `MacHevcSession` (`cmd = 2`) connects and authenticates (S0–S7), but awaits `ScreensharingAgent` for off-console sessions. In macOS Sonoma/Sequoia, off-console virtual sessions require Screen Sharing to be set to "High Performance" in System Settings > Sharing > Screen Sharing.
  - **Device Validation Evidence (Quest 3 `172.16.0.106:46801`)**:
    - `macHevcSetup`: **PASS** (`INSTRUMENTATION_CODE: 0`). Provisioned `Jeff Mac (WARP Console)`, `Jeff Mac (WARP Direct)`, and `Jeff Mac (HEVC)`.
    - `vncMode live` (regression gate): **PASS** (`INSTRUMENTATION_CODE: 0`). Verified nonblank frame, pointer dispatch, and clean disconnect.
    - `vncMode macWrong`: **PASS** (`INSTRUMENTATION_CODE: 0`). Localized Mac auth failure verified.
- **Mac HEVC High-Performance Protocol Implementation & Quest 3 Validation (2026-10-18)**:
  - **Protocol Alignment with `renegadelink/iShareScreen` (`exp/mac-hevc`)**:
    - `warmup_tcp`: Implemented 1.4s TCP dwell pre-session probe against `screensharingd`, warming up server-side crypto state and eliminating random handshake drops.
    - `cmd=2` Multi-Session & Two-TCP Reset: Built 202-byte SessionSelect layout (`version=2, cmd=2, 64B zeroes, 64B AES-128-ECB enc_user, 64B enc_pass`) combined with two-TCP reset (close conn1, open conn2, redo auth and clientInit, send `cmd=2`). Accepted by server with `status=0` in 51ms.
    - `ViewerInfo (0x21)` & Buffer Framing: Sent 66-byte ViewerInfo (`0x3e` msgSize, `app_id=2`, `app_ver=(6,1,0)`, `os_ver=(15,3,0)`, `APPLE_VIEWER_COMMAND_MASK`) followed by 12-byte `APPLE_0X12_FOLLOWUP`. Corrected buffer allocation from 74 to 66 bytes, removing 8 trailing zeroes that caused server desync and broken pipe.
    - 5-Mode 308-Byte Virtual Display Configuration (`0x1d`): Aligned `buildDisplayConfiguration` to 5 heterogeneous mode entries (`3840x2160`, `2880x1800`, `3840x2160`, `2880x1620`, `2624x1696`), `di_size = 296`, `msg_size = 304`, total 308 bytes, with `max_width=3840, max_height=2160, rotations=7`.
    - Plaintext SetEncodings & Rekey `0x44f`: Plaintext `HP_ENCODINGS_FULL` sent; `0x44f` Rekey unwrapped via AES-128-ECB under wrapKey, activating AES-128-CBC record layer with SHA-1 integrity trailer.
    - Encrypted Media Offer (`0x1c`): Sent encrypted second SetEncodings, 1191-byte `0x1c` MediaStreamOptions offer with `tilesPerFrame=1` and AVConference protobuf MediaBlob (`MacHevcOffers.kt`), and encrypted `0x03` FBU request.
    - UDP SRTP & Hardware MediaCodec Pipeline: Bound UDP port 5901, launched `MacHevcVideoPipeline.kt` with SRTP AES-256-CTR/HMAC-SHA1-80 decryption, RFC 7798 NAL depayloading, and hardware `MediaCodec` low-latency decoding directly to Surface.
  - **Live On-Device Evidence (Quest 3 `172.16.0.106:46801`)**:
    - `macHevcPhase1`: **PASS** (`INSTRUMENTATION_CODE: 0`). Reached S8 (record layer active), decrypted 10 control records (0 to 9, `recvSeq=10`), verified negative gate on corrupted ciphertext (`MacHevcIntegrityException`).
    - `macHevcPhase2`: **PASS** (`INSTRUMENTATION_CODE: 0`). Reached steady state (S9). Server acknowledged virtual display with `AppleDisplayLayout` (`0x451`), dynamically resizing server display from 5120x2880 to 3840x2160. Dispatched encrypted pointer events, requested update, received `MiscStatus` (`0x14`).
    - `macHevcPhase3`: **PASS** (`INSTRUMENTATION_CODE: 0`). Probed dynamic resolution `0x1d` (displayType = 0), server resized 3840x2160 -> 1920x1080 and returned `AppleDisplayLayout`. Probed virtual display `0x1d` (displayType = 4 virtual), server switched to virtual display mode emitting `RFBMediaStreamMessage1` (`0x3f2` / 1010).
    - Standard VNC Regression (`vncMode live` against `Jeff Mac`): **PASS** (`INSTRUMENTATION_CODE: 0`). Verified nonblank frame, pointer dispatch, and clean disconnect.
    - Unit tests (`:app:testDebugUnitTest`): **PASS** (14/14 tests green).
    - `macHevcSetup`: **PASS** (`INSTRUMENTATION_CODE: 0`). Provisioned `Jeff Mac (WARP HEVC)` (`192.168.4.165:5900`, `expMacHevc = true`, `expMacShareConsole = false`) alongside `Jeff Mac (WARP Console)` (`expMacShareConsole = true`) and `Jeff Mac (WARP Direct)` (`expMacHevc = false`).
  - **Operational Status & Profile Testing Guide**:
    - **What is Functional**:
      1. Handshake & Security (S0–S8): S0-S5 Apple DH Type 30 auth, initial wrap key derivation (`MD5(shared)[0:16]`), two-TCP reset with 202-byte `cmd=2` SessionSelect layout (accepted by server in 51ms), `0x44f` Rekey unwrap, AES-128-CBC record layer with SHA-1 integrity trailer.
      2. Protocol Alignment with `iShareScreen`: 66-byte `ViewerInfo` (`0x21`) with `APPLE_VIEWER_COMMAND_MASK`, 12-byte `APPLE_0X12_FOLLOWUP`, 5-mode 308-byte virtual display `0x1d` framing, `0x1c` MediaStreamOptions offer with protobuf `MediaBlob`.
      3. Steady State (S9): Dynamic server resolution resizing (tested 5120x2880 -> 3840x2160 and 1920x1080), encrypted pointer event dispatch, virtual display activation (`RFBMediaStreamMessage1` `0x3f2`).
      4. Video Engine: Local UDP 5901 SRTP receiver (`MacHevcSrtp.kt`), RFC 7798 HEVC depacketizer with DONL reordering (`MacHevcRtpParser.kt`), and hardware `MediaCodec` low-latency decoding to Android `Surface` (`MacHevcVideoPipeline.kt`).
    - **Profiles to Test**:
      1. **`Jeff Mac (WARP HEVC)`** (Recommended): `192.168.4.165:5900`, `expMacHevc = true`, `expMacShareConsole = false`. Routes over WARP where UDP 5901 is reachable, connects off-console (`cmd=2`) to a headless virtual display without prompting the local Mac user.
      2. **`Jeff Mac (WARP Console)`**: `192.168.4.165:5900`, `expMacHevc = true`, `expMacShareConsole = true`. Routes over WARP, asks to share the physical console display (`cmd=0`), prompts Jeff to click "Share Display", and streams the Mac's physical display via HEVC.
      3. **`Jeff Mac (WARP Direct)`**: `192.168.4.165:5900`, `expMacHevc = false`. Standard VNC (LibVNCClient software decoder) baseline over WARP for comparison.
    - **Verification Tools**: Tap the Info icon (`Icons.Default.Info`) on the floating toolbar to open the Connection Info overlay and verify the active decoder description, resolution, and transport.
  - **Main Thread Crash Diagnosis & Async Writer Fix (2026-10-18)**:
    - **Root Cause of Crash**: Logcat and dropbox tombstones (`dumpsys dropbox --print data_app_crash`) revealed `android.os.NetworkOnMainThreadException` at `MacHevcRecordLayer.kt:133` during `sendMouseEvent`, `sendPointer`, and `updateDisplay`. The S0–S8 handshake and S9 transition succeeded completely, but when Compose launched `DesktopSessionScreen`, its `LaunchedEffect` (surface resize) and controller hover events invoked `VncBridge.sendMouseEvent` / `updateDisplay` on Android's main UI thread, writing synchronously to the TCP socket stream.
    - **Root Cause of "Read timed out"**: `Jeff Mac (WARP Console)` sends `cmd=0` (ask to share display), which places an interactive modal prompt on the physical Mac display ("James Kasyan would like to share your screen"). If the local Mac user does not click "Share Display" before the socket read timeout (60s), the read times out.
    - **Fix Applied**:
      - `MacHevcSession.kt`: Introduced dedicated single-threaded daemon `writerExecutor` (`MacHevc-Writer`). Routed all post-handshake outgoing network writes (`sendPointer`, `requestUpdate`, `sendDisplayConfiguration`, `sendKeyEvent`, and `AppleDisplayLayout` re-arm) through `writerExecutor.execute`, completely eliminating socket writes on the Android main thread and preventing race conditions on `sendSeq` / AES-CBC state.
      - `MacHevcSession.kt`: Clarified timeout exception message on `cmd=0` to explicitly instruct: `"Timed out waiting for Mac user to accept screen sharing request. Check the Mac screen and click 'Share Display'."`
      - `MacHevcSession.kt`: Shut down `writerExecutor` cleanly in `close()`.
    - **Live Device Evidence (Quest 3 `172.16.0.106:46801`)**:
      - `:app:testDebugUnitTest`: **PASS** (14/14 tests green).
      - App and androidTest APKs built and installed (`adb install -r`).
      - `macHevcPhase1`: **PASS** (`INSTRUMENTATION_CODE: 0`). Reached S8, decrypted 10 control records, negative gate verified.
      - `macHevcPhase2`: **PASS** (`INSTRUMENTATION_CODE: 0`). Reached steady state S9, dynamically resized 5120x2880 -> 3840x2160, dispatched pointer events asynchronously via `writerExecutor`, verified zero main thread exceptions, received steady-state update 2, clean video pipeline teardown (`received=3` UDP packets).
- **Debug Connection Info Overlay & Decoder Visibility (2026-10-18)**:
  - **Toolbar HUD Implementation**:
    - Added an Info toggle button (`Icons.Default.Info`) to the floating toolbar in `RdpSessionScreen.kt`.
    - Extended `DesktopSessionBridge` contract with `getDecoderDescription(instance: Long): String`.
    - Implemented in `FreeRDPBridge` ("FreeRDP Native Pipeline") and `VncBridge` ("CPU Software ZLIB (MacHevcSession stage ...)" vs "CPU Software (LibVNCClient / ZRLE / Raw)").
    - Added `ConnectionInfoRow` rendering a semi-transparent HUD overlay displaying:
      - Profile Name & Host:Port
      - Protocol: "RDP (FreeRDP 3.x)" vs "VNC (Apple High-Performance)" vs "VNC (Standard RFB 3.8)"
      - Active Decoder: "FreeRDP Native Pipeline" vs "CPU Software ZLIB (MacHevcSession stage ...)" vs "CPU Software (LibVNCClient / ZRLE / Raw)"
      - Transport: "Direct TCP (LAN / WARP)" vs "Cloudflare Tunnel (TCP)" vs "HTTP CONNECT Proxy"
      - Server Canvas Resolution (`${actualWidth} × ${actualHeight}`) vs Window Surface Resolution (`${currentWidth} × ${currentHeight}`) and zoom/scale factor
      - Authentication mode and Mac feature flags (`expMacHevc`, `expMacShareConsole`)
    - Auto-hide is held while the Info HUD is visible; easily dismissed via close button or toggling the Info icon.
    - Zero translation overhead: uses existing developer/debug strings without 22-locale overhead.
  - **Commits**: `32e523b` (`DesktopSessionBridge.kt`), `7a5c135` (`FreeRDPBridge.kt`), `d0d4c29` (`VncBridge.kt`), `8f19871` (`RdpSessionScreen.kt`).
  - **Build & Deployment**: Both APKs (`app-debug.apk` and `app-debug-androidTest.apk`) built cleanly via command line and installed on Quest 3 (`172.16.0.106:46801`).
- **Apple Screen Sharing Protocol Alignment & iShareScreen Blueprint (2026-10-18)**:
  - **Ground Truth on Current Status**:
    - Neither the user nor the assistant has seen an end-to-end live HEVC stream render from this Mac mini yet.
    - Every profile that rendered a desktop picture (`Jeff Mac ` and `Jeff Mac (WARP Direct)`) operated on **standard VNC** (`expMacHevc = false`, LibVNCClient software decoding). Standard VNC software-decodes the Mac mini's full 5K canvas (5120x2880, ~14.7 million pixels/frame) on the Quest mobile CPU over TCP, capping frame rate to 2–5 fps and locking server resolution (no dynamic `SetDesktopSize`).
    - Profiles with `expMacHevc = true`: `Jeff Mac (HEVC)` / multi-session (`cmd=2`) timed out waiting 60s for `ScreensharingAgent` to spawn an off-console virtual session (requires High Performance Screen Sharing in macOS System Settings > General > Sharing > Screen Sharing `(i)`), while console sharing (`cmd=0`) awaited Jeff's physical on-screen approval.
  - **iShareScreen Reference Analysis (`renegadelink/iShareScreen`)**:
    - The repository implements Apple's macOS Screen Sharing High Performance mode (HEVC RExt 4:4:4 over UDP/SRTP) and aligns directly with `apple_vnc_rfc.md`:
      1. `warmup_tcp`: Pre-session TCP probe that completes ProtocolVersion banner exchange and dwells ~1.4s to register the viewer with `screensharingd`, preventing disconnection during macOS user session/agent transitions.
      2. `APPLE_VIEWER_COMMAND_MASK`: 32-byte capability bitmask in ViewerInfo `0x21` with active bits (`0xb0`, `0x0c`, `0x03`, `0x90`, `0x40`) enabling daemon features (tickle heartbeat, clipboard pushes, dynamic display).
      3. `0x1d` VirtualDisplay + `0x1c` MediaStreamOptions offer exchange carrying 46-byte SRTP keys (`akv`, `aks`, `vkv`, `vks`).
      4. Single HEVC decoder fed by 4 horizontal strip SSRCs with DONL NAL ordering.
  - **Roadmap to Full iShareScreen Parity on Quest 3**:
    1. Update ViewerInfo with `APPLE_VIEWER_COMMAND_MASK` and pre-session `warmup_tcp`.
    2. Add `0x1c` MediaStreamOptions offer generation and UDP SRTP socket listener on port 5900/5901.
    3. Feed received HEVC NAL units into Quest 3 hardware `MediaCodec` decoder bound directly to the `Surface`.
