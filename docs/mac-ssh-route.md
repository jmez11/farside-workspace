# Mac connection through SSH

Implemented on current master (`e9e3613c` base), 2026-10-24. Device/live validation status is in PROGRESS.md.

## Setup

1. Save an SSH connection that signs in to the viewed Mac, using its Cloudflare route or another supported transport. Existing SSH jump routes remain usable.
2. In the Mac/VNC connection, open Review & Options → Companion Remote Login. Select that SSH connection and enable the existing SSH media relay.
3. Enable **Use SSH for the full Mac connection** to use only the SSH route. The Server port must be the actual Screen Sharing service port on the Mac, normally5900.

With the option off, the existing separate VNC and SSH routes remain in use. Existing profiles default to off. Turning it on retains the saved VNC address/proxy settings but does not use them; turning it off restores their use.

No manually configured SOCKS proxy is needed for Mac control or media. An optional dynamic forward saved on the SSH profile starts when the shared SSH transport authenticates, including when the Mac window opens it without a terminal. Files/SFTP pairing is independent of media-helper selection.

## Transport and lifetime

- The selected final SSH endpoint must be the viewed Mac. Connector placement is irrelevant; there is no topology detection or automatic target guessing.
- Streaming reuses the existing Perl helper’s TCP control and framed UDP media over SSH. Ordinary-VNC fallback in full-SSH mode uses its new `control` mode through that same authenticated SSH transport. It opens no separate VNC Cloudflare process and does not bypass SSH on failure.
- Control-only mode connects to Mac-local loopback, needs no encoder interface discovery or UDP binds, preserves TCP half-close/backpressure, and receives an owner heartbeat every30s for idle desktops. Both modes exit on owner EOF/termination and retain the600s owner-input expiry.
- The desktop owns only its helper/channels/SSH lease. Dynamic forwarding belongs to the shared SSH transport, surviving closure of a terminal or desktop while another lease remains. The last owner releases listeners and the transport.
- Authentication/trust rejection and cancellation stop the attempt. Missing/deleted SSH selections fail rather than using the saved VNC route in full-SSH mode. Known TCP-only Cloudflare/HTTP/SOCKS routes skip direct UDP streaming attempts.
- Legacy profiles retain their previously preferred Files/SSH companion. Newly saved profiles distinguish an explicitly empty Files pairing from legacy absent metadata.

## Verification

- Host Perl: `python tools/verify-mac-relay-control.py --perl <perl>` checks the unmodified control-only helper with occupied UDP ports, custom TCP port, fragmented owner heartbeat, lossless1MiB/half-close, termination and owner EOF. `tools/verify-mac-relay-backpressure.py --loopback-fixture` checks the existing media reactor under stdout pressure; only that fixture relaxes the loopback encoder guard.
- Quest: `vncMode macSshPathsFixture` checks actual SSHJ/native VNC, helper failure→control fallback, separate SOCKS fallback, rejected SSH credentials, automatic dynamic forwarding, shared lifetime and cleanup. Its SSH exec fixture simulates helper readiness; the host tests execute the real Perl.
- `vncMode macSshPathsCleanup` removes only the named loopback/invalid-endpoint profiles from interrupted route fixtures and their loopback SSH host keys.
- `vncMode macDefaults` checks migration/JSON; `macRelayToggle -e vncProfile <owned-id>` checks actual setup controls and captures `mac-through-ssh-options.png`. `sshForwardingOnly`, `sshImeOnly`, and native VNC fixtures cover surrounding forwarding/input.
- Live: `vncMode macAutomatic -e vncProfile <saved-Mac-id> -e macThroughSsh true|false` uses a temporary in-memory route choice, exercises hardware video/audio/resize and ordinary Mac VNC before/after, and restores the recorded mute state. `macNativeBaseline` accepts the same override for a standalone native check. Saved profiles remain unchanged.
- Use the CLI build/install and sequential instrumentation instructions in ARCHITECTURE.md. Evidence belongs in `build/verification/mac-routing/`; do not publish host identities or secrets. Physical Horizon interaction, perceived latency, correct desktop appearance and audible output remain wearer checks.
