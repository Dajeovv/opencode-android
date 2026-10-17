# Changelog

All notable changes to this project will be documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/), and this project
adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Community health files: CONTRIBUTING, CODE_OF_CONDUCT, SECURITY and AUTHORSHIP.
- GitHub templates (issue forms, PR template, Dependabot) and a CI workflow that builds the debug APK.

## [3.122] - 2026-10-16

First public release.

### Added

- One-tap chat: starts `opencode serve` in Termux and opens the local UI (`http://127.0.0.1:4096`).
- First-run wizard, with or without root: installs Termux, configures permissions, installs opencode.
- Background opencode updates with real MB progress and automatic restart when done.
- Update notifications for opencode and for the launcher itself (daily background check).
- Optional watchdog: notifies or restarts when the server goes down; boot start.
- Bilingual ES/EN, following the system light/dark theme.
