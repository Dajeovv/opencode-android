# OpenCode Android

**Unofficial Android client for [opencode](https://github.com/anomalyco/opencode).**
Not affiliated with, endorsed by, or sponsored by the upstream project.

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![Android 8+](https://img.shields.io/badge/Android-8%2B-blue)
![arm64](https://img.shields.io/badge/arch-arm64-blue)

> [!IMPORTANT]
> **OpenCode Android 4.0.1 runs the agent entirely on your phone — no Termux, no PC, no root, no setup.**
> One APK. Open it and the chat starts. The runtime and the full toolchain are already inside.

[opencode](https://github.com/anomalyco/opencode) is a real coding agent, not just a chatbot. It can inspect and edit files, run commands, and — with wireless ADB — control apps and the UI, all from your phone.

## Download

**[Download OpenCode Android 4.0.1](https://github.com/Dajeovv/opencode-android/releases/latest)** — `OpenCodeAndroid-v4.0.1.apk`

arm64-v8a · Android 8.0+ · ~133 MB

## Demo

<p align="center">
  <a href="docs/demo.gif"><img src="docs/demo.gif" alt="OpenCode Android demo: native ARM64 check, Fibonacci script, edit, and Android toast" width="360"></a>
</p>

<p align="center"><em>opencode 1.19.4 running natively on the phone: ARM64 check, Fibonacci script, edit, and an Android toast — no Termux, no PC, no root.</em></p>

## Screenshots

<table align="center">
  <tr>
    <th align="center">Chat</th>
    <th align="center">Sessions</th>
    <th align="center">Launch</th>
  </tr>
  <tr>
    <td align="center"><img src="docs/chat.png" alt="Chat" width="250"></td>
    <td align="center"><img src="docs/sessions.png" alt="Sessions" width="250"></td>
    <td align="center"><img src="docs/splash.png" alt="Launch" width="250"></td>
  </tr>
</table>

## What you get

- **The app is the chat.** There is no home panel and no wizard. Open the app, the server starts by itself, and the chat appears as soon as it is healthy.
- **Native ARM64 runtime.** opencode 1.19.4 is embedded in the APK and runs as a native process. No Termux, no root, no PC.
- **A full toolchain, already installed.** `git`, `python3` + `pip3`, `node`, `ssh`, `make`, `curl`, `wget`, `bash`, `coreutils`, and the real `apt`/`dpkg`/`pkg`.
- **Extensible.** `pkg install clang`, `rust`, `go`, `ffmpeg`, `cmake`… straight from the chat.
- **Phone access.** Battery, clipboard, sensors, Wi-Fi, brightness, volume, toasts, and vibration through `termux-*` commands.
- **Share to opencode.** Send text or files from any app, review the preview, choose a new or existing conversation, and add an optional instruction.
- **Self-updating.** opencode and the app itself update from GitHub Releases; opencode updates verify the checksum and roll back if the new binary does not start.
- **Watchdog and notifications.** Optional restart/notify if the server goes down; boot start; daily update check.
- **Bilingual ES/EN**, following the system language and the light/dark theme.

## Requirements

- Android 8.0+ (API 26), **arm64**.
- Internet on first run (npm dependencies and the package index) and for updates.
- ~133 MB download; the toolbox takes ~200 MB more once unpacked.
- No Termux, no PC, no root.

## Installation

1. Download `OpenCodeAndroid-v4.0.1.apk` from [Releases](https://github.com/Dajeovv/opencode-android/releases/latest).
2. Open the file. If Android blocks it, allow **Install unknown apps** for your browser or file manager.
3. Install and open **OpenCode Android**.
4. Grant **All files access** when asked (Android 11+), or storage access on Android 8-10. This lets the agent read `/sdcard` and receive shared files.
5. Wait for the first run. The app unpacks the toolbox, prepares the package index, and starts the server. No wizard, no commands.

Optional integrity check:

```sh
sha256sum -c SHA256SUMS.txt
```

On Windows PowerShell:

```powershell
Get-FileHash .\OpenCodeAndroid-v4.0.1.apk -Algorithm SHA256
```

The package id is `com.opencode.launcher.next`; the `.next` suffix is historical. The app is standalone.

## First run

- The first start needs internet: it unpacks ~200 MB of packages, runs `apt-get update`, and installs the npm dependencies used by the agent. It can take a few minutes.
- The default model is the free `opencode.ai` model. You can connect other providers from the opencode settings.
- Everything lives in the app's private storage. Uninstalling the app removes its runtime, toolbox, and sessions.

## Using the app

- **Chat** is the home screen. The splash stays until the local server answers.
- **Controls menu** (the floating button, bottom right): log, restart server, stop server, update opencode, check for a new app version, auto-approve, options, usage, and report a problem.
- **Sessions** (grid icon): browse projects and sessions, search, or start a new session.
- **Share**: from any app, share text or files to OpenCode Android. The preview lets you pick a new or existing conversation and add an optional instruction.

## The toolbox

The APK carries the aarch64 packages and installs them on first run — no second app:

- Real `apt`, `dpkg`, and `pkg`, with a real package database and index.
- `git`, `python3` (with `pip3`), `node`, `ssh` (OpenSSH), `make`, `curl`, `wget`, `bash`, `coreutils`, `tar`, `gzip`, and their dependencies (~200 MB installed).
- `pkg install`, `pkg remove`, `pkg upgrade`, `pkg list-installed`, `pkg search`, `pkg files`.
- Anything else from the package repository on demand: `pkg install clang` (brings `gcc`, `g++`, `cc`), `rust`, `go`, `ffmpeg`, `cmake`…
- The tools run in the same sandbox as the agent, under the app's private prefix.

## Device commands

The app exposes the phone to the agent with Termux:API-compatible command names, but without a companion app:

```
termux-battery-status        # level, charging, temperature, voltage
termux-clipboard-get|set     # clipboard
termux-toast "saved"         # on-screen notice
termux-vibrate 200           # vibration
termux-brightness            # screen brightness
termux-volume [stream] [v]   # volume
termux-wifi-connectioninfo   # active network
termux-sensor-list           # device sensors
termux-sensor accelerometer  # one sensor sample
```

They return JSON, like their Termux:API counterparts. Only safe permissions are exposed: SMS, contacts, camera, microphone, location, the notification shade, and the fingerprint are intentionally left out.

`termux-wifi-connectioninfo` uses `ConnectivityManager`, `LinkProperties`, `NetworkCapabilities`, and `WifiManager`. It returns transport, IPv4/IPv6, DNS, routes, gateway, RSSI, signal level, frequency, link speed, Wi-Fi standard, DHCP data, and every visible network. SSID/BSSID are omitted because the app does not request location.

## Updates

- **opencode**: checks GitHub every 24 h and on every open. Tap the notification to download the official `opencode-linux-arm64-musl` release, verify its checksum, and install it in place. The server restarts on the new binary; if it does not answer, the app rolls back to the bundled runtime. Old runtimes are removed after a successful update.
- **This app**: the controls menu and a notification check for a new GitHub Release; the APK downloads and installs through the system installer.

## How it works

- The opencode binary is embedded in the APK and runs as a **native ARM64 process**. Core tools (`git`, `python`, `grep`, `find`…) are ELF binaries from the bundled prefix, launched through Android's `/system/bin/linker64`.
- `LD_LIBRARY_PATH` is scoped to those launches, so `dumpsys`, `pm`, `cmd`, and other system binaries load their own libraries.
- `proot` is bundled only for `pkg`/`apt`/`dpkg` and for scripts that expect the conventional prefix. If the native runtime does not become healthy, the app falls back to proot.
- The bundled packages expect the conventional prefix path, so the app mounts its own prefix there inside the sandbox. The tools see the paths they expect.
- The packages are registered with the real dpkg database, and `apt-get update` fills the index, so `apt` and `pkg` behave normally.

## Permissions & privacy

- **All files access** (Android 11+): lets the agent read `/sdcard` and copies shared files to `Downloads/opencode-shares`.
- **Storage** (Android 8-10): `READ/WRITE_EXTERNAL_STORAGE` for the same access. If Android 10 keeps scoped storage, shared files go to the app's private external folder (`Android/data/com.opencode.launcher.next/files/Download/opencode-shares`), which the agent can still read. Sharing asks once and retries automatically.
- **Wi-Fi state**: reads transport, IPs, DNS, routes, RSSI, and link speed through Android APIs. SSID/BSSID stay hidden because location is not requested.
- **Install unknown apps**: installs APKs downloaded from Releases.
- **Notifications**: update and watchdog notices.
- **Network**: the app contacts `api.github.com` (versions and the official opencode installer) and the package/npm mirrors used to build the toolbox on first run. Prompts and code go to the model provider you configure. There is no analytics or telemetry. Diagnostics mask tokens (`sk-***`, `ghp_***`, `Bearer ***`).
- **Root is not required.** Nothing in 4.0.1 uses it. `dumpsys wifi`, `settings get/put`, and `ip`/`ifconfig`/`netstat` still need root in a normal app sandbox; the app exposes Android APIs instead.

## Known limitations

- **Test coverage**: verified on one physical Android 15 arm64 device. Android 8-14, other OEMs, tablets, and 16 KB-page devices are untested. The APK and libraries are 16 KB-aligned, but there is no 16 KB hardware evidence yet.
- **Android 8-10 storage**: the legacy-permission and private-folder fallback compiles but has not run on real Android 8-10 hardware.
- **Wi-Fi details**: SSID/BSSID require location, which the app deliberately does not request.
- **Root-only commands**: `dumpsys wifi`, `settings`, and `ip`/`ifconfig`/`netstat` fail in the app sandbox. Use `termux-wifi-connectioninfo` or root if the phone has it.
- **Memory**: the app and server are memory-heavy; phones with 4 GB or less may see the server killed in the background.
- **First run needs internet**: npm dependencies and the package index are downloaded then. Offline first runs are not supported.
- **Background limits**: some vendors freeze long background downloads; the watchdog and battery settings help.
- **Install unknown apps**: the direct settings screen is not available on every vendor; the app falls back to the app-details page.
- **File sharing**: up to 10 files, 50 MB each, 100 MB total. Copies stay in `Downloads/opencode-shares` (or the private folder on Android 10) and are cleaned after 7 days.
- **Distribution**: GitHub Releases only; the app is not on Google Play.

## Troubleshooting

- **The chat does not open**: the first run may still be unpacking. Wait a few minutes, check the connection, and open the controls menu → **Log**. If needed, **Restart server**.
- **Android blocks the APK**: allow **Install unknown apps** for the app you are installing from.
- **Sharing fails**: grant All files access (Android 11+) or storage (Android 8-10); the app retries the pending share after the permission prompt.
- **The server stops in the background**: disable battery optimization for OpenCode Android and keep the watchdog enabled.
- **`dumpsys`/`ip` fail**: expected without root. Use the Android APIs through `termux-wifi-connectioninfo`.
- **Low memory**: close other apps; the agent and server are heavy.

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md). Security reports go through the private channel in [SECURITY.md](SECURITY.md). Human authorship of this AI-assisted project is documented in [AUTHORSHIP.md](AUTHORSHIP.md).

## License

MIT — see [LICENSE](LICENSE).
