# OpenCode Android

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

- **One-tap chat**: starts `opencode serve` in Termux and opens the local UI (`http://127.0.0.1:4096`).
- **Device control over wireless ADB (optional)**: with Android 11+ wireless debugging paired inside Termux and `android-tools` installed, opencode can launch apps, tap, type, read the UI and take screenshots — no PC (OEM support varies).
- **First-run wizard**: guides you with or without root (installs Termux, configures permissions, installs opencode).
- **Share to opencode**: send text or files from any app; check the preview, choose a new or existing conversation, add an optional instruction, and it opens that chat with the content.
- **Background opencode updates**: downloads with real MB progress, no frozen screen; auto-restarts when done.
- **Self-updating bundled runtime**: tap the opencode update notification to download and install the official arm64 release in place, with checksum verification and automatic rollback.
- **Update notifications**: panel button + system notification (even with the app closed, daily check), for new opencode releases and for new versions of this app.
- **Optional watchdog**: notifies or restarts if the server goes down; boot start.
- **Bilingual ES/EN**, follows the system light/dark theme.

## Requirements

- Android 8.0+ (API 26). Tested on Android 15.
- [Termux](https://github.com/termux/termux-app) (the app downloads it if missing).
- Internet connection for the first install and updates.

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

> On some phones you must enable "Install unknown apps" and "All files access" in Settings; the app takes you there.

## Updates

- **opencode**: the app checks GitHub every 24 h (and on every open). When a new version is out, a notification appears.
  - **Running in Termux**: an `Update opencode X → Y` button is also shown and installs it there.
  - **Running inside the app (bundled runtime)**: tap the notification to download the official `opencode-linux-arm64-musl` release, with real progress, checksum verification and automatic installation. The local server restarts on the new binary; if the new runtime does not answer, the app rolls back to the bundled one automatically.
  - The downloaded runtime lives in the app's private storage, is used only when it is newer than the bundled one, and old runtimes are removed after a successful update.
- **this app**: same, with a `New app version X — Download` button + notification. The APK downloads from the GitHub Release itself and installs via the system installer.

## Permissions & privacy

- **All files access**: reads the installed version and Termux progress markers, and copies files you share to `Downloads/opencode-shares`. The app opens this permission page by itself the first time it needs it.
- **Install unknown apps**: installs the APK downloaded from a Release.
- **Notifications**: update and watchdog notices.
- Nothing leaves your phone except public queries to `api.github.com` (versions) and opencode installer downloads. Diagnostics mask tokens (`sk-***`, `ghp_***`, `Bearer ***`) before display.

## Build from source

- JDK 17, Android SDK with `compileSdk 34`; the repo ships the Gradle wrapper (Gradle 8.7 + Android Gradle Plugin 8.5.2).
- Create `local.properties` with `sdk.dir=/path/to/your/sdk` (never committed).
- Debug: `./gradlew :app:assembleDebug` (the first run downloads dependencies).
- Signed release: `./gradlew :app:assembleRelease` (needs a `keystore.properties` next to this README with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`; that file is never committed — keep a copy of the `.jks` and its passwords outside the repo).
- Beta (Next): `./gradlew :app:assembleBeta` — same as the release but with the `.next` package id, its own port and the beta version suffix, signed with the release key.
- The embedded opencode runtime (`app/src/main/jniLibs/arm64-v8a/libopencode2_bin.so`, ~186 MB) is **not in the repository**: it is above git's 100 MB per-file limit. It ships inside the published APK. To build the runtime-enabled variants, put the binary in that path first (take it from a published release APK).
- The Termux packages bundled as assets (`app/src/main/assets/toolbox/`, ~50 MB of `.deb`) are not in the repository either. They come from the official Termux mirror (`packages.termux.dev`, `binary-aarch64`): download the closure of `git python python-pip python-ensurepip-wheels nodejs openssh make curl wget bash coreutils` and save each one as `<package name>.deb`, plus a `packages.txt` with `name<TAB>version` lines.

## Beta (Next)

`v4.0.0-beta.3` is the first **Next** build with a real package manager inside the
app: opencode runs inside the app (proot, no Termux, no root), the app starts the
server by itself and lands straight in the chat, the Termux toolchain is already
installed, and tapping the opencode update notification downloads, verifies and
installs the official binary in place.

- Package: `com.opencode.launcher.next` (it installs next to the current app).
- Download: [OpenCodeAndroid-v4.0.0-beta.3.apk](https://github.com/Dajeovv/opencode-android/releases/tag/v4.0.0-beta.3) from the pre-release page.
- arm64 only, Android 8+.

### The toolbox

The app carries the Termux packages for aarch64 inside the APK and installs them
by itself the first time it runs — no extra step, no second app:

- The real `apt` and `dpkg`, with a real package database and index.
- `git`, `python3` (with `pip3`), `node`, `ssh` (OpenSSH), `make`, `curl`, `wget`, `bash`, `coreutils`, `tar`, `gzip` and their dependencies (~200 MB installed in the app's private storage).
- The real Termux `pkg` command: `pkg install`, `pkg remove`, `pkg upgrade`, `pkg list-installed`, `pkg search`, `pkg files`.
- Anything else from the Termux repository can be added on demand from the chat: `pkg install clang` (which brings `gcc`, `g++` and `cc`), `rust`, `go`, `ffmpeg`, `cmake`… The app fetches it in the background and it is ready for the next command.
- The tools run inside the same sandbox as the agent, under `/data/data/com.termux/files/usr`.

## Known limitations

- On some phones the app freezer can pause long background downloads (see the watchdog interval).
- The direct "install unknown apps" screen does not exist on all vendors: the app falls back to the app-details page, where the same toggle lives.
- File sharing accepts up to 10 files (50 MB each, 100 MB total). Copies stay in `Downloads/opencode-shares` and are cleaned after 7 days.

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md). Security reports go through the private channel described in [SECURITY.md](SECURITY.md). Human authorship of this AI-assisted project is documented in [AUTHORSHIP.md](AUTHORSHIP.md).

## License

MIT — see [LICENSE](LICENSE).
