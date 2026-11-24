# Farside release process

CI/CD is GitHub Actions, tag-only, arm64-only.

## CI (every PR, every push to master)

`.github/workflows/ci.yml` runs `assembleDebug`, `lintDebug`, and `testDebugUnitTest`
on a pinned `ubuntu-24.04` runner and uploads the debug APK as an artifact
(14-day retention) — handy for pushing a PR build to the headset with
`adb install -r`.

On-device instrumentation tests are **not** run in CI (no headset attached);
run them locally before tagging.

## Release (push a `v*` tag)

`.github/workflows/release.yml` triggers on tags like `v1.0.0`:

- Checks out with `submodules: recursive` (FreeRDP 3.32.x and LibVNC must check
  out and compile; the Termux terminal modules are vendored in `termux-app/`).
- Installs the pinned NDK (`26.1.10909125`) and CMake (`3.22.1`) via sdkmanager.
- Derives `versionName` from the tag (`v1.0.0` → `1.0.0`) and `versionCode`
  from the commit count — monotonic, no manual bumping.
- Writes the upload keystore from the `ANDROID_KEYSTORE_BASE64` secret plus
  the three password/alias secrets, builds a signed release APK, wipes the
  keystore, uploads the APK to private R2, optionally uploads to the Meta
  Horizon Store via `ovr-platform-util`, and creates the GitHub Release entry
  with generated notes.

## Meta Horizon Store upload (automated in `release.yml`)

When configured, `release.yml` downloads Meta's official Linux
`ovr-platform-util` binary and runs `upload-quest-build` with the signed
release APK and generated changelog notes.

### Required GitHub Secrets / Variables
Add these under **Settings → Secrets and variables → Actions**:
- `META_APP_ID` (Variable or Secret): numeric Meta App ID from the Developer Dashboard (**Development → API**).
- `META_APP_SECRET` (Secret): Meta App Secret from the same API tab (alternatively, `META_ACCESS_TOKEN` for a user access token).

### Optional GitHub Variables
- `META_RELEASE_CHANNEL`: target channel for stable `v*` tags (default: `RC`; set to `LIVE` to publish directly).
- `META_PRERELEASE_CHANNEL`: target channel for prerelease tags containing `-` (default: `ALPHA`).
- `META_AGE_GROUP`: age-group certification flag passed to `ovr-platform-util` (`TEENS_AND_ADULTS`, `MIXED_AGES`, or `CHILDREN`; default: `TEENS_AND_ADULTS`).

If `META_APP_ID` or `META_APP_SECRET` is not set, tag releases still succeed
and skip the Meta upload with a warning. Manual `workflow_dispatch` runs let
you pick `meta_channel` (`auto`, `ALPHA`, `BETA`, `RC`, `LIVE`, `skip`),
`meta_draft`, or `meta_validate_only` (`--validate` dry run).

## One-time setup (manual)

1. Generate the upload keystore **on your machine** (never commit it). Fully
   non-interactive — replace the two `<...>` passwords with your own
   (using the same password for both is fine and keeps the secrets simple):
   ```powershell
   keytool -genkeypair -v `
     -keystore farside-keystore.jks `
     -alias farside-upload `
     -keyalg RSA -keysize 2048 -validity 9125 `
     -storepass <store-password> -keypass <key-password> `
     -dname "CN=farside, OU=farside, O=OceanSideVR, C=US"
   ```
2. Add four repository secrets (Settings → Secrets → Actions):
   `ANDROID_KEYSTORE_BASE64` (base64 of the .jks),
   `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, `ANDROID_STORE_PASSWORD`.
3. Back the keystore up somewhere safe (password manager). Losing it means a
   new app identity on the store.

## Cutting a release

```powershell
git tag v1.0.0
git push origin v1.0.0
```

For a prerelease, tag with a hyphen suffix — the workflow marks the GitHub
Release as a prerelease automatically:

```powershell
git tag v0.1.0-rc1
git push origin v0.1.0-rc1
```

Watch the Release workflow, then find the APK on the GitHub Releases page.
Source for every release is the tagged commit itself, which satisfies the
GPLv3 source-availability requirement.
