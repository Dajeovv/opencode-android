# OpenCode Android

Android launcher for [opencode](https://github.com/anomalyco/opencode) running on Termux: one tap opens the chat on your phone, with guided setup, background updates with real progress, and update notifications.

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![minSdk 26](https://img.shields.io/badge/minSdk-26-blue)
![targetSdk 34](https://img.shields.io/badge/targetSdk-34-blue)

## Screenshots

| Launch | Panel |
|---|---|
| ![Launch](docs/splash.png) | ![Panel](docs/panel.png) |
| Sessions | Chat |
| ![Sessions](docs/sessions.png) | ![Chat](docs/chat.png) |

## Features

- **One-tap chat**: starts `opencode serve` in Termux and opens the local UI (`http://127.0.0.1:4096`).
- **First-run wizard**: guides you with or without root (installs Termux, configures permissions, installs opencode).
- **Background opencode updates**: downloads with real MB progress, no frozen screen; auto-restarts when done.
- **Update notifications**: panel button + system notification (even with the app closed, daily check), for new opencode releases and for new versions of this app.
- **Optional watchdog**: notifies or restarts if the server goes down; boot start.
- **Bilingual ES/EN**, follows the system light/dark theme.

## Requirements

- Android 8.0+ (API 26). Tested on Android 15.
- [Termux](https://github.com/termux/termux-app) (the app downloads it if missing).
- Internet connection for the first install and updates.

## Installation

1. Download the APK from the latest [Release](../../releases) and install it.
2. Open the app and follow the wizard (it will ask for 2–3 permissions).
3. Tap **Start and open**.

> On some phones you must enable "Install unknown apps" and "All files access" in Settings; the app takes you there.

## Updates

- **opencode**: the app checks GitHub every 24 h (and on every open). When a new version is out, an `Update opencode X → Y` button appears plus a notification.
- **this app**: same, with a `New app version X — Download` button + notification. The APK downloads from the GitHub Release itself and installs via the system installer.

## Permissions & privacy

- **All files access**: reads the installed version and Termux progress markers.
- **Install unknown apps**: installs the APK downloaded from a Release.
- **Notifications**: update and watchdog notices.
- Nothing leaves your phone except public queries to `api.github.com` (versions) and opencode installer downloads. Diagnostics mask tokens (`sk-***`, `ghp_***`, `Bearer ***`) before display.

## Build from source

- JDK 17, Android SDK with `compileSdk 34`; the repo ships the Gradle wrapper (Gradle 8.7 + Android Gradle Plugin 8.5.2).
- Create `local.properties` with `sdk.dir=/path/to/your/sdk` (never committed).
- Debug: `./gradlew :app:assembleDebug` (the first run downloads dependencies).
- Signed release: `./gradlew :app:assembleRelease` (needs a `keystore.properties` next to this README with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`; that file is never committed — keep a copy of the `.jks` and its passwords outside the repo).

## Publishing a version

1. Bump `versionCode`/`versionName` in `app/build.gradle`.
2. Build the APK, create a GitHub **Release** tagged `vX.Y` (e.g. `v3.119`) and attach the `.apk`. The app detects it on its own (it uses the first `.apk` asset).

## Known limitations

- On some phones the app freezer can pause long background downloads (see the watchdog interval).
- The direct "install unknown apps" screen does not exist on all vendors: the app falls back to the app-details page, where the same toggle lives.

## License

MIT — see [LICENSE](LICENSE).
