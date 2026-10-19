# Contributing to OpenCode Android

Thanks for your interest in improving OpenCode Android!

## Ways to contribute

- **Report a bug** — open an issue with steps to reproduce.
- **Suggest a feature** — open an issue describing the use case.
- **Send a pull request** — for fixes and features (see below).

## Development setup

- JDK 17 and the Android SDK (`compileSdk 34`).
- Create `local.properties` with `sdk.dir=/path/to/your/sdk` (never committed).

```sh
git clone https://github.com/Dajeovv/opencode-android
cd opencode-android
./gradlew :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. Install it with
`adb install -r app/build/outputs/apk/debug/app-debug.apk` to test on a device
or emulator; the first run downloads dependencies.

Run the build before submitting:

```sh
./gradlew :app:assembleDebug
```

## Pull requests

1. Fork and branch off `main`.
2. Keep changes focused; one logical change per PR.
3. Rebuild and smoke-test on a device for behavior changes.
4. Ensure the build passes and the diff is clean.
5. Describe the *why* in the PR body, not just the *what*.

## Publishing a version (maintainers)

1. Bump `versionCode`/`versionName` in `app/build.gradle`.
2. Build the APK, create a GitHub **Release** tagged `vX.Y` (e.g. `v3.119`) and attach the `.apk`. The app detects it on its own (it uses the first `.apk` asset).
3. Do not mark non-version releases (e.g. video assets) as *Latest*: the in-app updater reads `releases/latest` and expects the `.apk`.

## Licensing of contributions

Unless you state otherwise in writing, contributions intentionally submitted for
inclusion are accepted under the project's [MIT](LICENSE) terms.

By contributing, you certify that you have the right to submit the work and that
it does not include code copied from unlicensed, source-available,
non-commercial, GPL, AGPL, LGPL, or otherwise incompatible sources.

AI-assisted contributions are allowed, but generated output must be reviewed as
source code, not pasted blindly. Do not submit generated code that matches public
code unless that public code has a compatible license and the required notices
are preserved. When a contribution is substantially AI-assisted, mention the tool
and the human review performed in the PR description.

## Code of conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md). By
participating, you are expected to uphold it.
