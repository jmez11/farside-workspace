# Standing Rules: MultiDesk Explorer

These are the standing rules and constraints for the MultiDesk Explorer repository. Read these before starting any session.

## Mandated Stack and Constraints
- **Platform**: Native Android app for Meta Quest (Horizon OS).
- **Language/UI**: Kotlin, Jetpack Compose, Meta Spatial SDK (0.14+).
- **Windowing**: Meta VR Layout SDK for Compose "Spatial Windows". Windows must be user-movable/pinnable in Home and passthrough.
- **RDP Stack**: FreeRDP 3.x (>= 3.27.1) (Apache-2.0) via NDK/JNI. Do NOT implement RDP from scratch. Reference `client/Android` in the FreeRDP repo.
- **SSH Stack**: SSHJ (Apache-2.0) for SSH transport. Termux terminal-emulator (GPLv3) for best-in-class VT100/xterm emulation, since the app is open-source (even though it will be sold in stores).
- **Tunneling/Proxy**: Support bundling the `cloudflared` Android arm64 binary. Must support both ephemeral child processes for per-session proxy commands and persistent Android Foreground Services for always-on Cloudflare tunnels.
- **Build/Release**: arm64-only, signed release CI. Command-line builds only, no Android Studio.

## Git Identity Rule (CRITICAL)
- **Identity**: `jmez11 <40301782+jmez11@users.noreply.github.com>`
- **NEVER** run `git config user.name` or `git config user.email`.
- **NEVER** change the git identity for any reason.
- If the git identity is wrong, stop immediately and report it to the user. Do not attempt to fix it yourself.

## General Practices
- **Autonomy**: Always proceed with the next step in the project automatically. Do not ask for permission to continue unless there is significant risk or ambiguity requiring user input. Do not ask for phase prioritization if the user requests a feature out of order; implement it and continue.
- **Multiple Requests**: If the user requests multiple distinct features or tasks in a single prompt, implement ALL of them before stopping. Do NOT ask the user which one they would like to do first or ask for prioritization. Pick a logical order yourself and execute them.
- **Device Logs**: If looking at logs from the device (e.g. via `adb logcat`) ever helps to debug an issue, always go get them automatically. However, do not retrieve them if they would not be helpful.
- Never trust old working notes blindly. Verify claims against actual code.
- Keep `AGENTS.md`, `ARCHITECTURE.md`, and `PROGRESS.md` concise and up to date. Do not let them accumulate endless rolling notes; rewrite sections as needed.
