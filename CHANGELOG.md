# Changelog

All notable changes to this project will be documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/), and this project
adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Community health files: CONTRIBUTING, CODE_OF_CONDUCT, SECURITY and AUTHORSHIP.
- GitHub templates (issue forms, PR template, Dependabot) and a CI workflow that builds the debug APK.

## [4.0.0] - 2026-10-25

First 4.0 release. Standalone package `com.opencode.launcher.next` (versionCode
171); it installs next to the 3.x app and does not replace it.

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
- First run needs internet (npm dependencies + apt index); low-RAM phones may see the server killed in the background.
- GitHub Releases only; not published on Google Play.

## [3.122] - 2026-10-16

First public release.

### Added

- One-tap chat: starts `opencode serve` in Termux and opens the local UI (`http://127.0.0.1:4096`).
- First-run wizard, with or without root: installs Termux, configures permissions, installs opencode.
- Background opencode updates with real MB progress and automatic restart when done.
- Update notifications for opencode and for the launcher itself (daily background check).
- Optional watchdog: notifies or restarts when the server goes down; boot start.
- Bilingual ES/EN, following the system light/dark theme.
