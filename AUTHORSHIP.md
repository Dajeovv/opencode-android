# Authorship

*Last updated: 2026-10-25*

This file documents the **human creative contribution** behind OpenCode Android.
Copyright protection for AI-assisted works depends on human authorship and
jurisdiction-specific originality standards; this record distinguishes human
architecture, selection, arrangement, judgment, and direction from
machine-assisted implementation. See [Why This File Exists](#why-this-file-exists).

## Human Author

**Dajeovv** — Architecture, design, product decisions, and creative direction.
Contact: [@Dajeovv](https://github.com/Dajeovv)

## Decisions

- Chose **native Android (Java, launcher with a single main activity)** over
  Flutter or hybrid frameworks, for footprint and direct startup.
- Chose to **embed the opencode runtime and an aarch64 toolchain in the APK**
  and run the agent as a **native ARM64 process** — no Termux, no root, no PC.
  proot is kept only for package management (`pkg`/`apt`/`dpkg`) and scripts
  that expect the conventional prefix, with a native-to-proot fallback.
- Chose **self-hosted distribution with in-app updates from GitHub Releases**;
  rejected Google Play (prohibits self-update) and the official F-Droid
  repository (requires building from their own recipes).
- Chose a **chat-first app**: no home panel and no setup wizard; opening the app
  starts the server and lands directly in the chat.
- Chose a **bilingual ES/EN UI** following the system light/dark theme.

Evidence: README.md, CHANGELOG.md, release v4.0.0, and the project's
development records.

## Exercise of Judgment

- **Rejected a proot-first runtime** after the native path proved healthy;
  kept proot as a fallback for package management and prefix scripts.
- **Scoped `LD_LIBRARY_PATH`** to `linker64` launches so system binaries such as
  `dumpsys`, `pm`, and `cmd` load their own libraries.
- **Rejected root-dependent messaging**: nothing in 4.0.0 requires root, so no
  root warnings are surfaced to users.
- **Redesigned the opencode update downloader** after it froze the UI; enforced
  real MB-progress feedback, checksum verification, and automatic rollback.
- **Enforced presentation quality**: rejected the first screenshots for the
  README until the set was clean and representative.
- **Enforced a publication quality gate**: scanned the repository for secrets
  and internal references, removed internal notes, and cleaned `.gitignore`
  before making the project public.

## Goal Setting & Direction

- **Problem**: running opencode on Android used to take many manual steps and a
  PC or Termux; OpenCode Android reduces this to install, open, and chat.
- **Intended users**: people who want a real coding agent on their phone,
  without a PC, a second app, or root.
- **Goals**: one APK; zero setup; native performance; a full toolchain already
  installed; background updates with real progress; update notifications for
  both opencode and the launcher.
- **Non-goals**: a mobile code editor, Google Play distribution.

## Art Direction

- Chat-first native UI with no home panel; a controls menu for the server log,
  restart/stop, updates, auto-approve, options, and diagnostics.
- Light/dark theme following the system; ES/EN copy written for the project.
- Screenshots authored for `docs/` and the README — selection and composition
  of the representative screens (launch, chat, sessions).

## AI Implementation

Code implementation was assisted by **AI coding tools (opencode with multiple
models)**. The AI generated syntax, function bodies, and boilerplate under human
architectural direction and iterative review. The human author provided the
specifications, constraints, iterative review and refinement, and all debugging
and integration decisions — including on-device verification on a real Android
device (via adb and screenshots). Development session logs are retained
locally as contemporaneous evidence of the creative direction process.

## Legal & Copyright

Architecture and design copyright (c) 2026-present Dajeovv.

Implementation was assisted by AI coding tools under human direction, review,
and integration. Provider output terms are recorded for transparency but are
not treated as a substitute for source provenance, license-compatibility
review, or human authorship documentation:

- AI coding assistance was provided through opencode with multiple model
  providers; where provider terms grant the user ownership of outputs, that
  does not replace the human-authorship record above.

The project is operated independently; this record documents authorship to
the strictest commonly applied standard — the U.S. human-authorship bar, under
which copyright protects human contributions (selection, arrangement,
coordination, and creative modification) and does not extend to purely
AI-generated material lacking human creative control. Consult qualified local
counsel before relying on this record commercially.

The project does not intentionally vendor GPL, AGPL, or LGPL code, and
AI-generated output is reviewed as source code — not pasted blindly — to avoid
reproducing public code without compatible licensing and required notices.

## Why This File Exists

Copyright protection for AI-assisted works depends on human authorship and
jurisdiction-specific originality standards. This file documents the human
creative process behind OpenCode Android so the project can distinguish human
architecture, selection, arrangement, and review from machine-assisted
implementation details — and so that authorship can be evidenced if ever
questioned.
