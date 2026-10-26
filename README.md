# OpenCode Android

**Unofficial Android client for [opencode](https://github.com/anomalyco/opencode).**
Not affiliated with, endorsed by, or sponsored by the upstream project.

> [!IMPORTANT]
> ## OpenCode Android 4.0.0 — opencode now runs inside the app
>
> The 3.x release still installs opencode inside **Termux**. OpenCode Android
> 4.0.0 changes that: the app ships the opencode binary and the Termux toolchain,
> and runs the agent on its own — **no Termux, no root, no setup**.
>
> - **4.0.0** uses the bundled **native runtime**: `git`, `python3`, `grep`,
>   `find` and the rest of the core tools run directly. proot is only used for
>   `pkg`/`apt` and scripts that need the Termux prefix.
> - The app **is** the chat: open it and the server starts by itself, with the
>   splash showing until it answers. There is no home panel any more.
> - `git`, `python3` + `pip3`, `node`, `ssh`, `make`, `curl`, `wget` and the real `apt`/`dpkg`/`pkg` are already installed — and `pkg install <anything>` works for the rest of the Termux catalogue.
> - The agent can also read the phone: battery, clipboard, sensors, wifi, brightness, volume, toasts and vibration.
> - Tapping the opencode update notification installs the official binary in place, with checksum verification and automatic rollback.
>
> **[Download OpenCode Android 4.0.0](https://github.com/Dajeovv/opencode-android/releases)** ·
> arm64 only, Android 8+ · installs as a separate app (`com.opencode.launcher.next`)
> next to the 3.x app.
>
> Verified on Android 15 arm64. Android 8-10 storage (legacy permission or the
> app's private folder) is implemented but has not been tested on real hardware.
>
> The 3.x app is unchanged; 4.0.0 is a separate install and does not replace it.

Not just a chatbot: [opencode](https://github.com/anomalyco/opencode) is a real coding agent running on your Android phone. It can inspect and edit files, run commands, and — with wireless ADB — control apps and the UI, all without a PC.

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![minSdk 26](https://img.shields.io/badge/minSdk-26-blue)
![targetSdk 34](https://img.shields.io/badge/targetSdk-34-blue)

**[First time here? Jump to installation](#user-content-installation)** — includes the 3-part video tutorial (Español / English).

## Screenshots

| Launch | Panel |
|---|---|
| ![Launch](docs/splash.png) | ![Panel](docs/panel.png) |
| Sessions | Chat |
| ![Sessions](docs/sessions.png) | ![Chat](docs/chat.png) |

## Features

- **One-tap chat**: starts `opencode serve` inside the app (4.0.0) or in Termux (3.x) and opens the local UI.
- **Device control over wireless ADB (optional)**: with Android 11+ wireless debugging paired inside Termux and `android-tools` installed, opencode can launch apps, tap, type, read the UI and take screenshots — no PC (OEM support varies).
- **First-run wizard**: the 3.x app guides you with or without root (installs Termux, configures permissions, installs opencode). The 4.0.0 app needs no wizard: it unpacks its toolbox and starts.
- **Share to opencode**: send text or files from any app; check the preview, choose a new or existing conversation, add an optional instruction, and it opens that chat with the content.
- **Background opencode updates**: downloads with real MB progress, no frozen screen; auto-restarts when done.
- **Self-updating bundled runtime**: tap the opencode update notification to download and install the official arm64 release in place, with checksum verification and automatic rollback.
- **Update notifications**: panel button + system notification (even with the app closed, daily check), for new opencode releases and for new versions of this app.
- **Optional watchdog**: notifies or restarts if the server goes down; boot start.
- **Bilingual ES/EN**, follows the system light/dark theme.

## Requirements

- Android 8.0+ (API 26), arm64. Tested on Android 15.
- **3.x app**: [Termux](https://github.com/termux/termux-app) (the app downloads it if missing).
- **4.0.0 app**: no Termux. The app ships its own runtime and toolbox.
- Internet connection for the first run (npm dependencies and the apt index) and for updates.

> Trying [OpenCode Android 4.0.0](#opencode-android)? Termux is **not** needed
> there: the agent and its toolbox run inside the app.

## Installation

> [!TIP]
> **First time installing? Watch the video tutorial first.** It walks through Termux, permissions and the wizard, step by step.
>
> **This setup is done only once.** After that, just open the app and tap **Start and open**.

### Video tutorial

<details>
<summary><b>Video tutorial — Español</b> (3 parts)</summary>

Parte 1/3

https://github.com/user-attachments/assets/bab76268-42b4-4039-81fc-2e3e1e0ab68f

Parte 2/3

https://github.com/user-attachments/assets/f031866f-45fd-4493-bfd5-fe5abc8a8b55

Parte 3/3

https://github.com/user-attachments/assets/30d25b2b-4df4-4871-ac3d-e75c7ffaa7a7

Full quality: [install-es.mp4](https://github.com/Dajeovv/opencode-android/releases/download/install-videos/install-es.mp4)

</details>

<details>
<summary><b>Video tutorial — English</b> (3 parts)</summary>

Part 1/3

https://github.com/user-attachments/assets/eebc8aed-2833-441e-b2a3-b4c7716df40a

Part 2/3

https://github.com/user-attachments/assets/1106b77f-78a7-4408-9c00-1528e2e1da4d

Part 3/3

https://github.com/user-attachments/assets/7def2639-9e16-4b57-95fd-ec8cfca79cdb

Full quality: [install-en.mp4](https://github.com/Dajeovv/opencode-android/releases/download/install-videos/install-en.mp4)

</details>

1. Download the APK from the latest [Release](../../releases) and install it.
2. Open the app and follow the wizard (it will ask for 2–3 permissions).
3. Tap **Start and open**.

> **OpenCode Android 4.0.0:** there is no wizard. Install the APK, open it,
> grant "All files access" when asked, and the app unpacks its toolbox and opens
> the chat by itself.

> On some phones you must enable "Install unknown apps" and "All files access" in Settings; the app takes you there.

## Updates

- **opencode**: the app checks GitHub every 24 h (and on every open). When a new version is out, a notification appears.
  - **Running in Termux**: an `Update opencode X → Y` button is also shown and installs it there.
  - **Running inside the app (bundled runtime)**: tap the notification to download the official `opencode-linux-arm64-musl` release, with real progress, checksum verification and automatic installation. The local server restarts on the new binary; if the new runtime does not answer, the app rolls back to the bundled one automatically.
  - The downloaded runtime lives in the app's private storage, is used only when it is newer than the bundled one, and old runtimes are removed after a successful update.
- **this app**: same, with a `New app version X — Download` button + notification. The APK downloads from the GitHub Release itself and installs via the system installer.

## Permissions & privacy

- **All files access** (Android 11+): lets the agent read `/sdcard`, checks the installed version and Termux progress markers, and copies files you share to `Downloads/opencode-shares`. The app opens this permission page by itself the first time it needs it.
- **Storage** (Android 8-10): `READ/WRITE_EXTERNAL_STORAGE` for the same access. If Android 10 does not grant legacy storage, shared files go to the app's private external folder (`Android/data/com.opencode.launcher.next/files/Download/opencode-shares`), which the agent can still read. Sharing asks once and retries automatically.
- **Wi-Fi state**: reads transport, IP addresses, DNS, routes, RSSI and link speed through Android APIs. SSID and BSSID stay hidden because the app does **not** request location.
- **Install unknown apps**: installs the APK downloaded from a Release.
- **Notifications**: update and watchdog notices.
- **Network**: the app itself only contacts `api.github.com` (versions and the official opencode installer) and the Termux/npm mirrors used to build the toolbox on first run. Prompts and code you send go to the model provider you configure (the default is the free `opencode.ai` model). There is no analytics or telemetry. Diagnostics mask tokens (`sk-***`, `ghp_***`, `Bearer ***`) before display.
- **Root is optional and diagnostic**: nothing in 4.0.0 requires it. `dumpsys wifi`, `settings` and `ip`/`ifconfig`/`netstat` still need root on a normal app sandbox; the app uses Android APIs instead.

## Build from source

- JDK 17, Android SDK with `compileSdk 34`; the repo ships the Gradle wrapper (Gradle 8.7 + Android Gradle Plugin 8.5.2).
- Create `local.properties` with `sdk.dir=/path/to/your/sdk` (never committed).
- Debug: `./gradlew :app:assembleDebug` (the first run downloads dependencies).
- Signed release: `./gradlew :app:assembleRelease` (needs a `keystore.properties` next to this README with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`; that file is never committed — keep a copy of the `.jks` and its passwords outside the repo).
- Standalone 4.0.0 app: `./gradlew :app:assembleBeta` — same as the release but with the `.next` package id and its own port (`4098`), signed with the release key. The Gradle flavor is still called `beta`; the APK is version `4.0.0`.
- The embedded opencode runtime (`app/src/main/jniLibs/arm64-v8a/libopencode2_bin.so`, ~186 MB) is **not in the repository**: it is above git's 100 MB per-file limit. It ships inside the published APK. To build the runtime-enabled variants, put the binary in that path first (take it from a published release APK).
- The Termux packages bundled as assets (`app/src/main/assets/toolbox/`, ~50 MB of `.deb`) are not in the repository either. They come from the official Termux mirror (`packages.termux.dev`, `binary-aarch64`): download the closure of `git python python-pip python-ensurepip-wheels nodejs openssh make curl wget bash coreutils` and save each one as `<package name>.deb`, plus a `packages.txt` with `name<TAB>version` lines.

## OpenCode Android 4.0.0 (Next) — details

See the [4.0.0 notice at the top](#opencode-android) for the short version.

`v4.0.0` is the standalone **Next** build: opencode runs inside the app on the
bundled **native runtime** (no Termux, no root), the app starts the server by
itself and lands straight in the chat, the Termux toolchain is already installed,
and tapping the opencode update notification downloads, verifies and installs the
official binary in place. proot remains only for `pkg`/`apt` and scripts that
need the Termux prefix.

- Package: `com.opencode.launcher.next` (it installs next to the 3.x app; it does not replace it).
- Download: [OpenCode Android releases](https://github.com/Dajeovv/opencode-android/releases) (grab `OpenCodeAndroid-v4.0.0.apk`).
- arm64 only, Android 8+, ~133 MB to download and ~200 MB more once the toolbox is unpacked.
- Bundled opencode: `1.19.3`. Local port: `4098`.
- The UI is Spanish or English, following the system language; light/dark follows the system theme.

### Where the controls are

There is no home panel in the 4.0.0 build: the app opens straight into the chat.
Everything that used to live in the panel is in the **controls menu** (the
floating button in the bottom right corner): log, restart server, stop server,
update opencode, check for a new app version, auto-approve toggle, options,
usage and report a problem.

### The toolbox

The app carries the Termux packages for aarch64 inside the APK and installs them
by itself the first time it runs — no extra step, no second app:

- The real `apt` and `dpkg`, with a real package database and index.
- `git`, `python3` (with `pip3`), `node`, `ssh` (OpenSSH), `make`, `curl`, `wget`, `bash`, `coreutils`, `tar`, `gzip` and their dependencies (~200 MB installed in the app's private storage).
- The real Termux `pkg` command: `pkg install`, `pkg remove`, `pkg upgrade`, `pkg list-installed`, `pkg search`, `pkg files`.
- Anything else from the Termux repository can be added on demand from the chat: `pkg install clang` (which brings `gcc`, `g++` and `cc`), `rust`, `go`, `ffmpeg`, `cmake`… The app fetches it in the background and it is ready for the next command.
- The tools run inside the same sandbox as the agent, under `/data/data/com.termux/files/usr`.

### Device commands

The app also exposes the phone to the agent with the same command names as
Termux:API — but without the extra companion app, because the app itself is the
one doing the work:

```
termux-battery-status        # nivel, carga, temperatura, voltaje
termux-clipboard-get|set     # portapapeles
termux-toast "guardado"      # aviso en pantalla
termux-vibrate 200           # vibración
termux-brightness            # brillo de pantalla
termux-volume [stream] [v]   # volumen
termux-wifi-connectioninfo   # red activa
termux-sensor-list           # sensores del dispositivo
termux-sensor accelerometer  # una muestra de los sensores
```

They answer with JSON, exactly like their Termux:API counterparts. Only
permissions that are harmless are exposed on purpose: SMS, contacts, camera,
microphone, location, the notification shade and the fingerprint are left out.

`termux-wifi-connectioninfo` now uses `ConnectivityManager`, `LinkProperties`,
`NetworkCapabilities` and `WifiManager`: it returns the active transport,
IPv4/IPv6 addresses, DNS servers, routes, gateway, Wi-Fi RSSI/signal level,
frequency, link speed, Wi-Fi standard, DHCP data and every visible network.
SSID/BSSID are omitted without location permission; this is intentional.

### How it works

- The opencode binary is embedded in the APK and runs as a **native ARM64
  process**, with no Termux and no root. Core tools (`git`, `python`, `grep`,
  `find`…) are ELF binaries from the Termux prefix launched through Android's
  `/system/bin/linker64`; `LD_LIBRARY_PATH` is scoped to those launches so
  system binaries keep loading their own libraries.
- `proot` is still bundled and used only for `pkg`/`apt`/`dpkg` and for scripts
  that expect the Termux prefix at `/data/data/com.termux/files/usr`. If the
  native runtime fails to become healthy, the app falls back to proot.
- The Termux packages are built with the absolute prefix
  `/data/data/com.termux/files/usr`, so the app mounts its own prefix there inside
  the sandbox: the tools see exactly the paths they expect.
- The bundled packages are registered with the **real dpkg** database and
  `apt-get update` fills the index, so `apt` and `pkg` behave as in Termux.

## Known limitations

- **Test coverage**: verified on one device only — a Motorola moto g54 5G, Android 15, arm64. Android 8-14, other OEMs, tablets and 16 KB-page devices are untested. The APK and bundled libraries are 16 KB-aligned, but there is no 16 KB hardware evidence yet.
- **Android 8-10 storage**: sharing uses `WRITE_EXTERNAL_STORAGE` when granted; if Android 10 keeps scoped storage, files fall back to the app's private external folder. The path is implemented and compiles, but has not been exercised on real Android 8-10 hardware.
- **Wi-Fi details**: SSID and BSSID require location permission, which the app deliberately does not request. Transport, IPs, DNS, routes, gateway, RSSI and link speed are available.
- **Root-only commands**: `dumpsys wifi`, `settings get/put` and `ip`/`ifconfig`/`netstat` fail in the normal app sandbox. Use the Android APIs exposed through `termux-wifi-connectioninfo`, or root if the phone has it.
- **Memory**: on the test phone the app uses ~205 MB RSS and the server ~600 MB. Low-RAM phones (4 GB or less) may see the server killed in the background.
- **First run needs internet**: the server installs `@opencode-ai/plugin`, `zod` and the provider SDK, and `apt-get update` fills the package index. Offline first runs are not supported.
- On some phones the app freezer can pause long background downloads (see the watchdog interval).
- The direct "install unknown apps" screen does not exist on all vendors: the app falls back to the app-details page, where the same toggle lives.
- File sharing accepts up to 10 files (50 MB each, 100 MB total). Copies stay in `Downloads/opencode-shares` (or the app's private folder on Android 10) and are cleaned after 7 days.
- Distribution is GitHub Releases only for now; the app is not published on Google Play.

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md). Security reports go through the private channel described in [SECURITY.md](SECURITY.md). Human authorship of this AI-assisted project is documented in [AUTHORSHIP.md](AUTHORSHIP.md).

## License

MIT — see [LICENSE](LICENSE).
