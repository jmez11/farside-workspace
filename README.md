# Farside Workspace

A native RDP, SSH, and VNC client for Meta Quest (Horizon OS). Every remote session opens as its own spatial window, placed freely in your Home or passthrough environment — no custom VR world required.

## Features

- **RDP** via FreeRDP 3.x (full window dragging, clipboard, audio)
- **SSH** terminal via SSHJ + Termux terminal emulator (jump hosts, SFTP file browser)
- **VNC** via LibVNCServer (including macOS Screen Sharing)
- **Spatial windows** — each session is an independent, resizable, movable window
- **Proxy support** — HTTP CONNECT, SOCKS5, and Cloudflare Tunnels
- **Encrypted credential storage** via Android Keystore
- Localized in 22 languages

## Building

Requires Android SDK 35, NDK 26.1.10909125, CMake 3.22.1, and JDK 17.

```bash
./gradlew assembleDebug
```

CI builds on every push; tagged pushes (`v*`) produce signed release APKs via the Release workflow.

## Project Layout

- `app/` — Android app (Jetpack Compose UI, Meta Spatial SDK)
- `app/src/main/cpp/` — FreeRDP, LibVNCServer, and JNI bridges (git submodules)
- `termux-app/` — Vendored Termux terminal emulator (GPLv3)
- `docs/` — Release process and misc notes
- `ARCHITECTURE.md` — Full technical architecture
- `PROGRESS.md` — Development log

## License

GPLv3. See `LICENSE`. Bundled third-party licenses (FreeRDP, SSHJ, Termux, LibVNCServer, libjpeg-turbo, Cloudflared) are in `app/src/main/assets/licenses/` and attributed in the app's About screen.

## Privacy

See [PRIVACY.html](PRIVACY.html).
