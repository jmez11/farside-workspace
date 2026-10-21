# Connection + sign-in setup UI revamp — implementation prompt

Paste this whole block to your coding agent verbatim. It implements the approved
interactive mockup at `docs/connection-setup-revamp/mockup.html` (open it in a
browser — the 5 combo pills, 4-step flow, edit mode, vault view, and the
"What changed & why" section are the spec).

## 1. What you are building

Replace the connection/profile setup UI with the redesigned 4-step flow from the
mockup:

- Step 1 **Protocol**: three large cards — RDP ("Windows Remote Desktop"),
  SSH ("Secure shell & terminal"), VNC ("VNC servers & Mac Screen Sharing").
  Selecting VNC expands an inline segmented control
  "VNC password | Username + password" (hint: e.g. the macOS user account for
  Mac Screen Sharing). Auth mode is decided here, not mid-form.
- Step 2 **Server**: Connection name, Host, Port (prefilled 3389/22/5900).
  "Find local PCs" (RDP) / "Find local devices" (VNC) shows discovered results
  **filtered to the selected protocol only**, as selectable cards — picking one
  fills name/host/port. SSH has no discovery: name/host/port only, no hint text.
- Step 3 **Sign in** — different per combo, exactly as mocked:
  - RDP: saved sign-in cards + "+ New sign-in" sheet, or "type just this once"
    (username + password, not saved). Sign-in is **required**.
  - VNC password: saved password cards + "+ New", or type-here password with a
    live n/8 counter; enforce ≤ 8 chars, Latin-1 only, inline errors. Required.
  - VNC username+password: saved login cards + "+ New", or typed
    username + password. Required. Note: "Use your macOS user account — not the
    VNC password" appears only as the Mac Screen Sharing example hint.
  - SSH user+password: Username segmented [Saved | Type | Ask at connect], then
    Password: saved credential cards or a **password-only** type-here field
    (the username lives only in the Username group). Optional — never blocks.
  - SSH user+key: same Username group, then Auth method [Password | SSH key];
    key cards (name, key type, PIN badge) + "+ Import key" sheet (name, PEM
    paste, passphrase, require-PIN toggle).
- "+ New" / "+ Import" open **inline bottom sheets** — never navigate away to
  Settings. Sheet variants are contextual: New sign-in (name auto-suggests from
  username, username, password, require-PIN toggle), New VNC password (name,
  password with n/8 counter), Import SSH key (name, private-key textarea,
  passphrase, require-PIN toggle). Saving adds to the vault and selects it.
- Step 4 **Review & options**: summary card
  ("Office PC · RDP · host:port · signed in as …") with Edit jump-links back to
  steps 1/2/3; per-protocol option cards (RDP: display & audio incl. sound
  segmented Headset/Remote/Off + mic/camera(+resolution)/clipboard/storage/
  console/admin toggles; SSH: jump-host picker with route preview chip,
  SFTP toggle, port-forwards row with count badge, auto-copy toggle; VNC:
  "Pair with an existing SSH connection" dropdown with **plain connection names
  only**); Network card for all (proxy segmented Direct/Cloudflare/SOCKS5/HTTP
  with conditional fields, reconnect-automatically toggle).
- **Edit mode**: "New connection | Edit existing" toggle; picking a saved
  profile prefills all four steps; header becomes "Edit connection"; footer is
  "Save changes" (no delete — deletion lives on the launcher).
- **Validation**: blank required fields show red inline text + red field border,
  and Continue / Save changes stays disabled until valid. Required: name, host,
  port (1–65535) on step 2 for all; sign-in details on step 3 for RDP and both
  VNC modes; SSH step 3 never blocks. Live validation as the user types.
- **Vault manager**: rework the Settings credentials/keys tabs into the mocked
  vault — search field, "Sign-ins" group (name, user, kind badges RDP/SSH/
  VNC, PIN badge, "Used by N connections"), "SSH keys" group (name, key type,
  PIN badge, public-key view button), "+ New sign-in" / "+ Import key" opening
  the same sheets. **Private keys are replace-only**: the edit-key sheet shows
  an empty field ("The saved key is never shown. Leave blank to keep it."),
  mirroring the password fields. Public-key viewing stays.

## 2. Non-negotiable rules

- **Git identity**: every commit as `jmez11 <40301782+jmez11@users.noreply.github.com>`.
  Set it per-command via `git -c user.name=... -c user.email=...`. NEVER run
  `git config user.name` / `git config user.email`. If the identity differs,
  stop and report.
- **Stage by path, never `git add -A`.** Commit locally after each phase.
  **Never push.** James says "push" explicitly or it doesn't happen.
- **Presentation only.** Do not change the data model: `data/ConnectionProfile.kt`,
  `data/ProfileManager.kt`, credential/key stores, `JumpChains`, `CloudflaredOptions`
  are read-only. Reuse `profileCredentialErrorRes` validation rules.
- **New code goes in new files** under
  `app/src/main/java/com/farside/workspace/ui/setup/` (e.g.
  `ConnectionSetupFlow.kt`, `SetupStepSignIn.kt`, `CredentialSheets.kt`,
  `SignInVault.kt`). Do not rewrite `LauncherUI.kt` in place. Wire the new flow
  in where `AddProfileForm` is invoked; the old form and old Settings
  credential/key tabs are deleted only in Phase 4, after the new flow is
  verified on-device.
- **l10n**: reuse existing string resources wherever the copy matches. New
  strings go in `res/values/strings.xml` in English ONLY — do not hand-translate
  other locales; the strings watch flags them for review.
- **No mock data in the app.** The mockup's sample names/hosts are illustration
  only. Real lists come from `ProfileManager.getCredentials()`,
  `getSshKeys()`, `getProfiles()`; discovery from `NetworkScanner`.

## 3. Phases and empirical gates

Do exactly one phase, then `./gradlew assembleDebug`. It must be green before
the next phase. After each green build, install on the Quest and run that
phase's on-device checklist below. A red build or failed check: fix, rebuild,
re-verify — revert-and-retry per file; two strikes on one file and you skip it,
list it as incomplete, and move on. Commit locally per phase with a message
like `setup-revamp: phase 2 sign-in step`.

- **Phase 1 — shell + Protocol + Server.** Stepper with Back/Continue, step 1
  cards (+ VNC auth segmented), step 2 fields + protocol-filtered discovery,
  step-2 validation (name/host required, port 1–65535), wired to the existing
  save path with a minimal step 3/4 stub. On-device: create one RDP, one SSH,
  one VNC profile; discovery lists only matching types; blank name/host/port
  blocks Continue with red text.
- **Phase 2 — Sign-in step + sheets.** All five combo variants, saved pickers,
  inline sheets (credential create, VNC password with counter, key import),
  step-3 validation incl. the 8-char Latin-1 rule and the SSH-never-blocks rule.
  On-device: for each combo, create a sign-in/key inline without leaving the
  flow; verify RDP/VNC block without sign-in and SSH does not; verify the
  PIN-gate toggle persists.
- **Phase 3 — Review & options + edit mode.** Summary card with Edit links,
  per-protocol option cards, proxy conditionals, Cloudflare params validation,
  jump-host route preview, VNC SSH-pairing dropdown (plain names), edit-mode
  prefill for an RDP, a VNC, and an SSH-key profile, "Save changes" gating.
  On-device: edit each of the three profiles, change one option, save, confirm
  it persisted; key edit shows empty private-key field.
- **Phase 4 — vault manager + cleanup.** Rework Settings tabs into the vault
  screen; delete `AddProfileForm` and the old inline credential/key forms from
  `LauncherUI.kt`. On-device: full pass — new + edit for all five combos,
  vault search/edit, key replace-only. Final `./gradlew assembleDebug` green.

## 4. Ground truth — verify in code, never from memory

- Ports: RDP 3389, SSH 22, VNC 5900. Port valid range 1–65535.
- `VncAuthMode`: `VNC_PASSWORD` (pass ≤ 8 chars, Latin-1) vs `MAC_USER`
  (username + password).
- RDP requires a credential with non-blank user (or legacy typed user);
  SSH credential is optional (interactive / ask-at-connect valid).
- Jump-host candidates and route preview: `JumpChains.candidates()` /
  `JumpChains.preview()` — cycle refused, 3-hop cap.
- Proxy choices: NONE, CLOUDFLARED, SOCKS5, HTTP. Cloudflare hostname/params
  validated via `CloudflaredOptions`; keep the existing permission-request
  flows for microphone/camera/storage.
- VNC file pairing (`sftpPairProfileId`) lists SSH profiles with SFTP enabled.

## 5. Done criteria

All four phases green, on-device checklists pass, no `git add -A`, no push,
commits carry the exact identity above, new strings English-only, old form code
removed, and `docs/connection-setup-revamp/mockup.html` still matches what you
built (flag any deliberate deviation in the phase commit message).
