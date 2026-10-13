# Farside SSH SFTP file access — implementation scope

Goal: browse, download, and upload files on any saved SSH session from inside Farside, with an
SSH-session toggle modeled on the RDP "Redirect Storage" toggle. If the Horizon OS Files app
lists third-party DocumentsProviders, the end state is a "Farside SSH" root in the native Files
app (under Google Drive in Locations) plus a launcher folder icon that is purely a shortcut
opening Files at that root — no Farside-owned browser. If the spike fails, the fallback is a
Farside-owned file browser opened from that same folder icon.

## Verified facts (checked against the repo 2026-10-11)

- SSHJ is already a dependency: `app/build.gradle.kts` line 77 —
  `implementation("com.hierynomus:sshj:0.38.0")`. SSHJ 0.38.0 ships `net.schmizz.sshj.sftp.SFTPClient`
  (`ssh.newSFTPClient()`); no new dependency is needed for SFTP.
- `ConnectionProfile` (`app/src/main/java/com/farside/workspace/data/ConnectionProfile.kt`) is a
  `data class` serialized via `toJson()`/`fromJson()`; RDP-only fields already coexist with SSH
  profiles. The pattern for the new toggle: add `val enableSftp: Boolean = false`, write it in
  `toJson()`, read it in `fromJson()` with a default for legacy profiles (same as other fields).
- The RDP "Redirect Storage" toggle in `LauncherUI.kt` (~lines 1041–1047, 1374–1377) is permission-
  gated: enabling without `hasStoragePermission()` fires `requestStoragePermission()` via a
  settings `Intent` launcher. The SSH toggle mirrors this exactly.
- `hasStoragePermission()` / `requestStoragePermission()` live in
  `app/src/main/java/com/farside/workspace/StoragePermission.kt`: API 30+ requires
  `Environment.isExternalStorageManager()` → `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`.
  Reuse as-is for SFTP download destinations and upload sources.
- The full SSH connect flow already exists in
  `app/src/main/java/com/termux/terminal/SshTerminalSession.kt` (~lines 100–190): CloudflaredTunnel
  with `accessSubcommand = "ssh"` when `useCloudflare || proxyType == CLOUDFLARED`, `PromiscuousVerifier`
  host-key handling, saved-key auth via `ProfileManager.getSshKeys()` + BouncyCastle (`sshKeyId`),
  password auth (`credentialId`). **Do not duplicate this block — extract it.**
- Cloudflare browser auth is already handled: `CloudflaredTunnel.drain()` watches process output for
  an auth URI and fires `Intent(ACTION_VIEW)` with `CATEGORY_BROWSABLE` + `FLAG_ACTIVITY_NEW_TASK`
  (`app/src/main/java/com/farside/workspace/tunnel/CloudflaredTunnel.kt` ~lines 120–143). The
  SFTP browser gets browser-pop auth for free by reusing this tunnel path.
- Credentials go through the existing Keystore AES-GCM system (`credentialId` / `sshKeyId`,
  `CredentialManager`). There is an app-wide PIN (commit 1f9c640). Never store SFTP creds or
  key material anywhere else, never plaintext in profile JSON.

## Phase 0 — empirical gate: does Horizon OS Files list third-party DocumentsProviders? (spike, ≤1h)

The native Files-app integration (`"Farside SSH"` root under Google Drive) depends on Meta's Files
app enumerating third-party `DocumentsProvider`s. Meta's Files app is custom; this is unverified.
Do NOT build the feature on the assumption it works.

Spike: implement a minimal `DocumentsProvider` (one static root + static children, no network),
declare it in the manifest with `android:grantUriPermissions` + `exported="true"`, sideload to the
Quest 3, and check (a) whether it appears in Files → Locations/picker, and (b) whether
`Intent(ACTION_VIEW, DocumentsContract.buildRootUri(authority, rootId))` opens the Horizon OS
Files app landed on our root. Record PASS/FAIL for both with a photo in PROGRESS.md.
The build path branches on the result:

- **Spike passes → native path.** No Farside-owned file browser is built. The launcher folder
  icon is purely a shortcut that opens the Horizon OS Files app at the "Farside SSH" root.
- **Spike fails → fallback path.** Build the Farside-owned file browser (Phase 1-fallback).

## Phase 1 — native Files integration (spike passed; no in-app browser)

Implement `FarsideSshDocumentsProvider`:

- Root: single `ROOT_ID` "Farside SSH", `FLAG_SUPPORTS_IS_CHILD` etc.
- Per enabled session, a directory document under the root; `queryChildDocuments` opens (cached)
  `SshConnectionManager` connections and lists via SFTP. `openDocument` returns a
  `ParcelFileDescriptor` pipe fed by `sftp.get`; write path streams via `sftp.put`.
- Auth failures inside the provider: surface as a document named `⚠ sign-in required` whose open
  launches Farside's sign-in/activity — the provider itself must never block on UI.
- Declare `exported="true"` with `android.permission.MANAGE_DOCUMENTS` guard per Android docs.

Launcher shortcut: the folder icon next to the settings icon fires the `ACTION_VIEW` intent on
the root URI verified in Phase 0, opening the Horizon OS Files app landed on "Farside SSH". If
Files honors the provider but not the VIEW intent, note it in PROGRESS.md and fall back to
whatever intent the spike showed working — do not build a Farside-owned browser in this path.

The toggle (1b) and connection layer (1a) below still apply — the provider consumes them.

## Phase 1 (fallback) — own file browser (only if the spike failed)

### 1a. Shared SSH connection helper (extract, don't copy)

Create `SshConnectionManager` (new file, `com.farside.workspace.ssh` package) holding a connected
`SSHClient`, its `CloudflaredTunnel` (if any), and a reference count. Move the connect/auth logic
out of `SshTerminalSession` into `SshConnectionManager.connect(profile, onStatus): SSHClient`;
`SshTerminalSession` becomes a caller. The SFTP browser calls the same `connect()`.

- One manager instance per profile id; reuse across transfers in the same browser session.
- Close tunnel + disconnect on last release or after N minutes idle.
- All blocking IO off the main thread (existing code uses `thread {}`; coroutines are fine too —
  match surrounding style).

### 1b. Session toggle + permission

- `ConnectionProfile.enableSftp` (SSH profiles only; hidden/disabled for RDP profiles).
- LauncherUI settings: `SettingsSwitchRow("Enable SFTP file access", …)` mirroring the
  `redirectStorage` row exactly — enabling requests `MANAGE_EXTERNAL_STORAGE` when missing.
- A session appears in the Farside file browser only if `type == SSH && enableSftp`.

### 1c. Farside Files UI

- Entry point: folder icon in the launcher toolbar next to the settings icon (same row, same
  icon-button style). Opens a Farside Files panel (match the dark Files-app aesthetic in the
  reference screenshot: dark cards, grid/list toggle feel — at minimum a clean list).
- Level 0: one folder row per SSH profile with `enableSftp` ("Farside SSH" is the implicit root
  header; no extra nesting needed in our own browser). Tapping a session connects (shows status
  line from `onStatus`, including the Cloudflare browser message) and lists the remote home dir.
- Level 1+: breadcrumb path, dirs-first sorted listing (name, size, modified time), tap dir to
  descend, back to ascend. Long-press or overflow menu: Download, Rename, Delete, New folder.
- Downloads: stream `sftp.get(remote, outputStream)` chunked to the app's Downloads target
  (MediaStore insert on API 29+, app-private dir otherwise — permission already held). Progress
  bar + cancel. No full-file buffering in memory.
- Uploads: headset-side picker constrained to locations the app can read under
  `MANAGE_EXTERNAL_STORAGE`; `sftp.put(inputStream, remote)` with progress + cancel.
- Overwrite confirmation dialog on both directions. Symlinks shown with target hint; broken links
  and permission-denied entries render as dimmed rows, never crash the listing.
- Error states as inline banners: auth failed (offer to re-check saved credential/key), SFTP
  subsystem missing on server, tunnel exited, connection lost (offer reconnect).
- If the app-wide PIN is set, require unlock before the browser opens (same gate as credentials
  views).

### 1d. Concurrency and lifecycle

- One `SFTPClient` per transfer operation; share the underlying `SSHClient`.
- Cancel = close the `SFTPClient`/stream, keep the SSH connection alive.
- Screen-off / panel close releases idle connections.

## Non-goals (v1)

- SCP fallback; SSH agent forwarding; in-app key generation; remote file thumbnails/previews;
  multi-select bulk transfer; background transfers after panel close; DocumentsProvider write
  support beyond download/upload parity (flag Phase 2 write ops as stretch).
- Changing host-key policy: keep `PromiscuousVerifier` behavior identical to the terminal session.
  (Pinning is a separate security decision, not this feature.)

## Acceptance (all on-device, physical Quest 3)

1. Toggle appears only on SSH profiles; enabling without storage permission opens the
   all-files-access settings and reflects the grant on return.
2. **Native path:** "Farside SSH" appears in the Horizon OS Files app; the launcher folder icon
   opens Files landed on that root; PIN gate enforced when set (at provider-open time via the
   sign-in document, and at shortcut tap).
3. **Fallback path:** folder icon opens Farside Files; only `enableSftp` SSH sessions list; PIN
   gate enforced when set.
4. Against a real SSH server (key auth): list home, descend, download a 500MB file with progress
   + cancel, upload a file from headset storage, mkdir/rename/delete.
5. Against a Cloudflare-tunneled SSH session: browser pops for Access sign-in, then listing works
   without a second auth.
6. Server without SFTP subsystem: clean inline error, no crash.
7. Results recorded in PROGRESS.md with timestamps and server types (no hostnames/secrets).

## Execution notes

- Never run `git config user.name` / `git config user.email`. Git identity must stay
  `jmez11 <40301782+jmez11@users.noreply.github.com>`.
- Follow the repo's working-notes pattern: update AGENTS.md/ARCHITECTURE.md/PROGRESS.md.
- De-risk order: 0 (provider spike, both checks) → 1a (shared connect, terminal regression-
  tested) → 1b (toggle) → native path (provider + shortcut) OR fallback path (1c + 1d). Do not
  start the fallback browser until 1a is extracted and the terminal session passes its existing
  on-device checks.
- On-device validation is mandatory before any PR; note exact Quest build + server combos in
  PROGRESS.md.
