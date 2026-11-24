# Changelog

All notable changes to this project will be documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/), and this project
adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Community health files: CONTRIBUTING, CODE_OF_CONDUCT, SECURITY and AUTHORSHIP.
- GitHub templates (issue forms, PR template, Dependabot) and a CI workflow that builds the debug APK.

### Changed

- README and project docs now cover only the standalone 4.0.x app; the old
  setup guides were removed and the changelog starts at 4.0.0.

## [5.0.4] - 2026-11-24

### Added

- **Install new tools from the chat.** `pkg install` now uses the app's own
  installer, so packages such as Java and the Android build tools are
  downloaded safely and are ready to use as soon as the install finishes.
- **Building Android apps on the phone is now easier.** The app includes the
  Android platform files and gives the agent a ready-made recipe to compile,
  sign and install an APK.
- **Keep your opencode version when the app updates.** If you picked a newer
  or older opencode version, updating the app no longer switches you back to
  the bundled one.
- **Resume interrupted installs.** If a package install takes too long or
  loses the connection, running the same command again continues where it
  left off and skips what is already installed.
- **See the install progress.** The chat shows the current step and whether
  the install finished correctly.

### Changed

- **The app opens first, tools finish later.** You no longer have to wait for
  the whole toolbox before the chat appears; tools install in the background
  and become available when ready.
- **The version picker is faster.** It responds immediately, lets you go back
  to older opencode versions, and clearly marks the active and bundled
  versions.
- **The start screen progress no longer jumps backwards.**
- **The terminal always uses the app's built-in shell.**

### Fixed

- **Package installs are more reliable.** They no longer fail when the
  connection drops or another install is already running.
- **New commands work right away.** Tools installed from packages run
  directly in the shell; the old sandbox is only used when a command needs it.
- **Scripts and Java tools installed from packages now run correctly.**
- **The app repairs its tool paths automatically** after installing packages,
  without restarting the server.
- **Installing an APK and opening it in one step works again.**
- **opencode updates are safer.** The app waits for the old server to stop,
  checks the new version and rolls back if it does not start.

## [5.0.3] - 2026-11-19

Feature release for the 2.x edition (`com.opencode.android`, versionCode 178).

### Added

- Integrated terminal: `pkg`, `apt`, `git`, `python` and more, with touch
  scrolling.
- Built-in WARP for `opencode.ai` traffic, toggleable from the controls menu.
- Open apps, links and files from the chat, including by app name.
- Install APKs from the agent, with the system installer or silently on
  rooted devices.
- Faster shell tools and `pip` queries.

### Fixed

- The server password is no longer exposed in the agent's shell.
- The status bar stays visible and the page fits below it.

## [5.0.2] - 2026-11-12

Maintenance release for the 2.x edition (`com.opencode.android`, versionCode 176).

### Fixed

- **The app opens faster and feels smoother.** The screen used to flicker
  while it started; now it loads in one go.
- **Deleting a session works.** The confirmation window used to close on its
  own without deleting anything.
- **The update notice shows up again.** The check against GitHub could fail
  without a trace, and the app then reported that there was no update. It now
  retries and falls back to another source, so a real update is no longer
  missed.
- **QR codes can be scanned.** The "Scan QR code" button of the server panel
  did nothing, because the app never asked for camera access. It now asks for
  it when the button is pressed and hands the camera over to the page.
- **Files can be downloaded again.** When the agent creates a file, the
  download button did nothing at all: the page builds the file in memory and
  the app never picked it up. The file now lands in the Downloads folder.

### Changed

- The app keeps the files it needs on the device, so the second start is
  noticeably quicker.

## [5.0.1] - 2026-11-07

Maintenance release for the 2.x edition (`com.opencode.android`, versionCode 175).

### Added

- opencode runtime update notifications in v2: the app follows the 2.x release branch, notifies when a newer runtime is available, and installs it from the notification.

### Fixed

- The post-update health check now uses `/api/info` with Basic auth, so a runtime that started correctly is no longer rolled back as "did not start".

### Changed

- Magisk 31.1+ manual root grant flow, including opening the root manager and rechecking the permission on return.

## [4.0.1] - 2026-10-26

Maintenance release for the standalone app (versionCode 172).

### Changed

- Bundled **opencode 1.19.4** (was 1.19.3). The app no longer offers an update
  to 1.19.4 on every open because the bundled runtime is already current.
- Removed the duplicate **OpenCode AISDK** provider and the injected default
  model. The official `opencode` provider now supplies the free models and
  rotates them over time; the user's model choice is respected.

## [4.0.0] - 2026-10-25

First 4.0 release. Standalone package `com.opencode.launcher.next` (versionCode
171), with the agent running natively inside the app.

### Added

- Bundled **native runtime** for the standalone 4.0.0 app: opencode 1.19.3 runs inside the app without Termux or root. `git`, `python3`, `grep`, `find` and the rest of the core Termux tools are launched through `/system/bin/linker64`; proot is only used for `pkg`/`apt`/`dpkg` and prefix scripts.
- Expanded `termux-wifi-connectioninfo`: transport, IPv4/IPv6, DNS, routes, gateway, Wi-Fi RSSI/signal/frequency/standard, link speeds, DHCP and all visible networks via Android APIs. New `ACCESS_WIFI_STATE` permission.
- Android 8-10 storage support: legacy `READ/WRITE_EXTERNAL_STORAGE`, `requestLegacyExternalStorage`, a private external folder fallback for Android 10, and automatic retry of a pending share after the permission prompt.
- Share sheet footnote now shows the real destination (public Downloads or the app's private folder).

### Changed

- `LD_LIBRARY_PATH` is no longer exported to the agent shell or the server. It is set only while launching a Termux ELF with `linker64`, so `dumpsys`, `pm`, `cmd` and other system binaries load their own libraries.
- Commands with dashes (`termux-wifi-connectioninfo`, `termux-battery-status`…) are routed to proot instead of being silently skipped by the POSIX function generator.
- `oc-proot` clears `BASH_ENV`, `ENV` and `LD_PRELOAD` before entering the sandbox.
- Wi-Fi JSON omits `mtu` when the system reports 0.

### Fixed

- `dumpsys wifi` no longer fails with `cannot locate symbol "Xzs_Construct"` when the shell inherits Termux libraries.
- Sharing on Android 8-10 no longer blocks forever when the legacy permission is denied; it falls back to the app's private folder.

### Known limitations

- Verified on Android 15 arm64 only. Android 8-14, other OEMs, tablets and 16 KB-page devices are untested.
- Android 8-10 storage fallback compiles but has not run on real hardware.
- SSID/BSSID require location, which the app does not request. `dumpsys wifi`, `settings` and `ip`/`ifconfig`/`netstat` still need root.
- The first run installs the npm dependencies and apt index.
- GitHub Releases only; not published on Google Play.
