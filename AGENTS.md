# Standing Rules: Farside Workspace

## Mandated Stack and Constraints
- **Platform**: Native Android app for Meta Quest (Horizon OS).
- **Language/UI**: Kotlin, Jetpack Compose, Meta Spatial SDK (0.14+).
- **Windowing**: Meta VR Layout SDK for Compose "Spatial Windows". Windows must be user-movable/pinnable in Home and passthrough.
- **RDP Stack**: FreeRDP 3.x (>= 3.32.1) (Apache-2.0) via NDK/JNI. Do NOT implement RDP from scratch. Reference `client/Android` in the FreeRDP repo.
- **SSH Stack**: SSHJ (Apache-2.0); Termux terminal-emulator (GPLv3) for VT100/xterm. App remains open-source, including store releases.
- **Tunneling/Proxy**: Support bundling the `cloudflared` Android arm64 binary. Must support both ephemeral child processes for per-session proxy commands and persistent Android Foreground Services for always-on Cloudflare tunnels.
- **Build/Release**: arm64-only, signed release CI. Command-line builds only, no Android Studio.

## Git Identity Rule (CRITICAL)
- **Identity**: `jmez11 <40301782+jmez11@users.noreply.github.com>`
- Never change git identity or run `git config user.name` / `git config user.email`. If identity differs, stop and report; do not fix it.

## General Practices
- **Scope**: Do exactly the asked task; don't audit neighboring code. Avoid vague "be thorough" instructions.
- **Autonomy**: Complete all requested tasks and backlog items in a single uninterrupted chain of execution. NEVER stop or yield execution back to the user between tasks or items unless explicitly requested, or if you hit a fatal unrecoverable error. Move directly from one task to the next autonomously.
- **Device logs**: Fetch `adb logcat` automatically when useful for debugging.
- **Device validation preference (MANDATORY)**: After building the app, you MUST automatically push and install the updated APK to the connected Quest headset using `adb install -r <path_to_apk>`, and MUST run the on-device automated instrumentation tests *for any functionality that can be tested automatically*. You are required to do this autonomously without asking for permission. Failure to push to the device and run tests for automatable features means the task is incomplete. Inspect results/logs/screenshots, fix failures, and repeat affected checks autonomously. For changes that *cannot* be automatically tested (e.g., physical Horizon OS gestures, headset-wearing appearance, or subjective visual rendering), you must still run automated checks for surrounding logic, but then clearly report back to the user detailing the specific manual checks they need to perform.
- **Validation evidence**: Use the commands and harness in `ARCHITECTURE.md`; record results, artifact paths, and remaining manual checks in `PROGRESS.md`. Distinguish synthetic input/inset checks from actual Horizon gestures/keyboard behavior. If the device or required profile is unavailable, report the unverified behavior and continue independent work. Documentation-only edits do not require device deployment.
- **SFTP validation**: See `ARCHITECTURE.md` → SSH SFTP files for fixture/live modes, permission-test setup and cleanup. Use owned remote scratch only; keep server identities and secrets out of validation notes.
- **Working connections**: If you need a live connection that works, ask the user which one to use. For this session, use the first one (`<LAPTOP>`).
- **Setup values**: Never guess setup-specific values (hostnames, IPs, usernames, domains). The `<QUEST>` / `<MAC>` / `<LAPTOP>` / `<CAM_HOST>` / `<JUMP_TARGET>` / `<BASTION>` / `<USER>` / `<USER_DOMAIN>` tokens in these docs map to the user's real setup in `local/setup-values.md` (gitignored; see `docs/local-setup.template.md`). If you need a value, ask the user — do not invent one.
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
