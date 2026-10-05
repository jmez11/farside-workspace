# Standing Rules: MultiDesk Explorer

## Mandated Stack and Constraints
- **Platform**: Native Android app for Meta Quest (Horizon OS).
- **Language/UI**: Kotlin, Jetpack Compose, Meta Spatial SDK (0.14+).
- **Windowing**: Meta VR Layout SDK for Compose "Spatial Windows". Windows must be user-movable/pinnable in Home and passthrough.
- **RDP Stack**: FreeRDP 3.x (>= 3.27.1) (Apache-2.0) via NDK/JNI. Do NOT implement RDP from scratch. Reference `client/Android` in the FreeRDP repo.
- **SSH Stack**: SSHJ (Apache-2.0); Termux terminal-emulator (GPLv3) for VT100/xterm. App remains open-source, including store releases.
- **Tunneling/Proxy**: Support bundling the `cloudflared` Android arm64 binary. Must support both ephemeral child processes for per-session proxy commands and persistent Android Foreground Services for always-on Cloudflare tunnels.
- **Build/Release**: arm64-only, signed release CI. Command-line builds only, no Android Studio.

## Git Identity Rule (CRITICAL)
- **Identity**: `jmez11 <40301782+jmez11@users.noreply.github.com>`
- Never change git identity or run `git config user.name` / `git config user.email`. If identity differs, stop and report; do not fix it.

## General Practices
- **Scope**: Do exactly the asked task; don't audit neighboring code. Avoid vague "be thorough" instructions.
- **Autonomy**: Complete all requested tasks in a logical order without asking to continue or prioritize. Ask only for significant risk or ambiguity; stay within the requested scope.
- **Device logs**: Fetch `adb logcat` automatically when useful for debugging.
- **Device validation preference**: For app changes, proactively build, install updated APKs on the connected Quest via ADB, create or extend relevant on-device automated regression checks, run them, inspect results/logs/screenshots, fix failures within scope, and repeat affected checks. The user explicitly wants this workflow to continue without asking permission for each build, install, test, or fix.
- **Validation evidence**: Use the commands and harness in `ARCHITECTURE.md`; record results, artifact paths, and remaining manual checks in `PROGRESS.md`. Distinguish synthetic input/inset checks from actual Horizon gestures/keyboard behavior. If the device or required profile is unavailable, report the unverified behavior and continue independent work. Documentation-only edits do not require device deployment.
- Verify working-note claims against relevant code.
- Read `PROGRESS.md` and `ARCHITECTURE.md` before starting work; keep both up to date with implementation changes, validation results, and remaining work.
- Keep `AGENTS.md`, `ARCHITECTURE.md`, and `PROGRESS.md` concise and current; rewrite stale sections instead of appending rolling notes.

## Context and Session Discipline
- Keep instruction files lean: every token is paid on every turn. Prefer command/path pointers over prose. Build: `./gradlew assembleDebug`.
- Search with `rg` before reading; read narrow line ranges instead of whole files.
- Batch independent tool calls into one block; keep dependent operations sequential.
- Summarize tool results; don't paste raw output.
- Put detailed status in `PROGRESS.md`; keep chat updates to three lines.
- Prefer `codex exec "bounded prompt"` for one-shot tasks over interactive sessions that accumulate chat context.
