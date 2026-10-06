# Cloudflared for Android

`arm64-v8a/libcloudflared.so` is an Android arm64 PIE **executable**, packaged with
legacy native-library extraction so Android permits `exec` from nativeLibraryDir.
Do not copy executables to writable app storage (Android 10+ blocks execution there).

Upstream: https://github.com/cloudflare/cloudflared/tree/2026.5.1 (Apache-2.0).
Pinned source archive SHA-256:
`4618a12a8c4ea77954c8048e5eaff0e9b7bcb16711253f84bd03440d6843a07f`.
Build: Go 1.26.1, NDK 26.1.10909125, Android API 32, CGO enabled for Android DNS.
From the repository root: `tools/build-cloudflared.ps1 -Go <go.exe>`.
The script verifies the archive, uses vendored dependencies and reapplies
`tools/prepare-cloudflared.py`; its exact diff is `tools/cloudflared-android.patch`.
The adaptations permit quiet login preflight with service credentials, fall back to
the standard user-token flow, delegate browser launch to Android, and signal readiness
after binding the loopback listener. Missing CF-Access-Domain metadata falls back to
the original application hostname, with the audience still required. They do not
change RDP or token transfer protocols.
Executable SHA-256: `93b607e975f775db4eb2c3dc044fb65b4468b6f6b29553d9f295c98f7f869866`.

The app exports Android trust anchors for Go TLS verification and keeps HOME, token
cache and transfer keys in `noBackupFilesDir/cloudflared` (0700). Authentication tokens
use upstream 0600 files. Service credentials in saved profiles use Android Keystore
AES-GCM. Raw child output, auth URLs, and command arguments are never logged by the app.

`../assets/licenses/cloudflared-LICENSE` contains upstream's Apache license.
Dependency notices are in `../assets/licenses/cloudflared-NOTICES.txt`.
