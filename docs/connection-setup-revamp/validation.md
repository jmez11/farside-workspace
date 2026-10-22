# Setup revamp validation (2026-10-21)

## Implemented scope

All four phases: new/edit flow, protocol-specific discovery, sign-in sheets,
Review/options, vault, protection/backup, launcher Vault/Info, and removal of the
old long form and credential/key tabs. The first shell's appearance was rejected;
the corrected screen follows the HTML's framed panel, horizontal icon cards,
numbered circle stepper, connecting lines, labeled fields and grouped cards.

**User override:** every protocol selects a complete stored credential or uses
connection-specific values. The mockup's independent SSH username/password
selection is deliberately superseded. Mac full/short names belong to one stored
credential. No independent SSH username-override fields were added. Explicit
connection-specific values use existing encrypted profile storage, including VNC;
`credentialSource` prevents automatic conversion to a matching vault entry.

## Visual/validation follow-up

User-requested polish: equal16dp outer margins, symmetric four-column stepper with
one-line centered Review & Options, consistent20sp bold headings, and required
errors delayed until Continue/step navigation (Save in credential sheets).
Clicking ahead routes to the first incomplete page; invalid values cannot save.
The vault has no search: top New/Import buttons, compact icon rows with
kind/Mac/PIN badges, usage summaries and right-side actions, grouped protection
and backup cards; Delete remains available inside Edit.

Require PIN opens app/headset protection setup directly in credential/key sheets.
Cancel clears the new requirement and keeps unsaved values. New app PIN setup
refreshes the vault's protection status. Owned-device fixtures exercise credential
PIN setup/cancel/save, key PIN setup/cancel, no-search rendering, edit/public-key,
app-PIN change/remove and encrypted backup/restore without touching real settings.
Latest results/artifacts: `build/verification/setup-polish/`:
- `build-verified.txt`: CLI app/test build and78 JVM checks PASS; final app/test
  APK install logs report Success on Quest3.
- `flow-shipping.txt`: all five new flows, fresh-page errors hidden, attempted
  navigation/Continue validation, forward routing and original profile/photo
  integrity PASS.
- `options-final.txt`, `signin-final.txt`: edit/options and all sign-in variants PASS.
- `vault-verified.txt`: compact vault, direct credential PIN creation/cancel/save,
  key PIN cancel, refreshed protection state, public keys, PIN change/remove and
  owned backup round-trip PASS; real preferences unchanged.
- `verified-setup-phase1-protocol.png`, `verified-setup-phase3-review.png`,
  `verified-setup-phase4-vault.png`: inspected device captures. The first discovery
  selection test hit its identically named text field; selecting the unique
  advertised endpoint corrected the fixture. Device testing also exposed stale
  protection status after inline PIN creation; external revision refresh fixed it.

The launcher is restored; no manual remote-Mac experiments were run.
Screenshots validate pixels, not physical Horizon input or headset-PIN confirmation.

## Commands

Use the configured Quest serial in place of `<QUEST>`; no live server is required.

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\dev'
.\gradlew.bat assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
adb -s <QUEST> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <QUEST> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <QUEST> shell am instrument -w -r -e setupPhaseOneOnly true com.farside.workspace.test/com.farside.workspace.RdpResizeInstrumentation
adb -s <QUEST> shell am instrument -w -r -e setupSignInOnly true com.farside.workspace.test/com.farside.workspace.RdpResizeInstrumentation
adb -s <QUEST> shell am instrument -w -r -e setupOptionsOnly true com.farside.workspace.test/com.farside.workspace.RdpResizeInstrumentation
adb -s <QUEST> shell am instrument -w -r -e setupVaultOnly true com.farside.workspace.test/com.farside.workspace.RdpResizeInstrumentation
adb -s <QUEST> shell am instrument -w -r -e vncMode inlineFixture com.farside.workspace.test/com.farside.workspace.RdpResizeInstrumentation
```

## Evidence and limits

Quest3 app/test APKs installed; final `release-check-build.txt` CLI build and78 JVM checks pass. Ignored artifacts:

- `build/verification/setup-phase1/`: initial/new visual captures, validation,
  protocol discovery injection and original profile/JPEG integrity.
- `build/verification/setup-phase2/`: five sign-in variants, real inline sheets,
  PIN metadata, VNC length/Latin1, Mac name derive/override/reset, short-name
  serialization/SSH resolution, and encrypted inline persistence without vault
  auto-link. The initially partial sheet hid lower fields; full expansion and
  bounded scrolling passed subsequent checks.
- `build/verification/setup-phase3/`: real edit/prefill/save/reload, route/proxy
  errors and replace-only key fields. The initial invalid-host test mistakenly
  used a parser-valid numeric hostname; corrected to an invalid URL.
- `build/verification/setup-phase4/new-five.txt`: all five actual new flows,
  required/range gating, protocol-specific injected discovery and cleanup.
- `setup-phase4/edit-five.txt`: all five edits plus a legacy stored login/key
  combination, preserving key references and hidden settings.
- `setup-phase4/sign-in-final.txt`: all five sign-in variants after vault cleanup.
- `setup-phase4/vault3.txt` and `vault-final.txt`: search/edit/public-key, empty secret edit fields,
  new VNC password, actual app-PIN set/change/remove dialogs, encrypted production
  URI export/import with a valid JPEG, profiles, short names, keys, PIN and inline
  VNC. Real profile/PIN preferences are compared unchanged. Fixtures use an
  isolated preference namespace and owned cache directory. Earlier vault attempts
  exposed a fixture ActivityResult owner omission and a stale accessibility frame;
  the owner and bounded click wait were corrected before the passing run. A
  settled capture is `setup-phase4/vault-settled.png`; screenshots are not
  physical-wearer acceptance.
- `setup-phase4/inline-vnc.txt`: actual SessionActivity/LibVNCClient/JNI password
  authentication, rendering, input/clipboard and teardown using an encrypted
  connection-specific VNC password; no vault entry is created.

The setup-specific selectors for the retired long form/tabs are not acceptance
gates for this UI. Their entrypoints compile against the new flow; use the modes
above for current setup validation. Native transport and file/terminal helpers
remain separate from setup UI validation.

## Remaining manual checks

- Wearer visual acceptance and physical Horizon pointer/keyboard behavior.
- Actual RDP/VNC LAN discovery (the flow gates inject discovery results).
- Camera, microphone and storage permission dialogs and actual redirection.
- SAF file-picker interaction and headset-passcode authentication. The exporter,
  importer, encryption, PIN dialogs and backup data are automatically checked in
  owned fixtures; no real headset protection is changed by the vault fixture.
- Mac joystick scrolling is **TO BE TESTED**:60-160 ticks at80-20ms,10x the prior
  curve. Synthetic direction/timing/stop checks pass. Prefer the working local Mac.

The unreliable remote double-Cloudflare Mac login/audio/input presentation is
explicitly shelved; the revamp does not claim to fix it. See `PROGRESS.md` and
`docs/mac-hevc-reevaluation.md` for that investigation's evidence and limits.
