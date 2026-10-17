# Authorship

*Last updated: 2026-10-16*

This file documents the **human creative contribution** behind OpenCode Android.
Copyright protection for AI-assisted works depends on human authorship and
jurisdiction-specific originality standards; this record distinguishes human
architecture, selection, arrangement, judgment, and direction from
machine-assisted implementation. See [Why This File Exists](#why-this-file-exists).

## Human Author

**Dajeovv** (Latin America) — Architecture, design, product decisions, and creative direction.
Contact: Dajeovv@users.noreply.github.com

## Decisions

- Chose **native Android (Java, launcher with a single main activity)** over
  Flutter or hybrid frameworks, for footprint and direct startup.
- Chose to orchestrate **Termux + `opencode serve` bound to `127.0.0.1:4096`**
  and render the local UI in the app's WebView — rather than embedding a server
  or requiring root. The app works with or without root.
- Chose **self-hosted distribution with in-app updates from GitHub Releases**;
  rejected Google Play (prohibits self-update) and the official F-Droid
  repository (requires building from their own recipes).
- Chose to **simplify first-run onboarding to a 3-step wizard** after the
  original flow proved too heavy.
- Chose a **bilingual ES/EN UI** following the system light/dark theme.

Evidence: README.md, CHANGELOG.md, release v3.122, and the project's
development records.

## Exercise of Judgment

- **Rejected and redesigned the opencode update downloader** after it froze the
  UI; enforced real MB-progress feedback as the quality bar.
- **Rejected root-dependent messaging**: the launcher must work with or without
  root, so no root warnings are surfaced to users.
- **Enforced presentation quality**: rejected the first screenshots for the
  README until the set was clean and representative.
- **Enforced a publication quality gate**: scanned the repository for secrets
  and internal references, removed internal notes, and cleaned `.gitignore`
  before making the project public.

## Goal Setting & Direction

- **Problem**: getting opencode running on Android takes many manual steps
  (install Termux, grant permissions, install opencode, start the server); the
  launcher reduces this to one tap — install, start, update.
- **Intended users**: people who use opencode (and Termux) and want their
  sessions from their phone.
- **Goals**: one-tap chat; guided setup with or without root; background
  updates with real progress; update notifications for both opencode and the
  launcher itself.
- **Non-goals**: a mobile code editor, multi-provider support, Google Play
  distribution.

## Art Direction

- Simple native launcher UI (panel / sessions / chat entry points) with
  restrained icons and tone.
- Light/dark theme following the system; ES/EN copy written for the project.
- Screenshots authored for `docs/` and the README — selection and composition
  of the four representative screens (launch, panel, sessions, chat).

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

The project is operated from Latin America; this record documents authorship to
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
