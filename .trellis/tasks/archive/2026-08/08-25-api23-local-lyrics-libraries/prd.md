# Vendor API 23 compatible lyrics libraries

## Goal

Restore installation and supported runtime behavior on Android 6.0 (API 23) without removing or
reimplementing the existing karaoke lyrics experience. The external lyrics UI and its shape
dependency will become source-controlled local Android library modules so their Android minimum SDK
can match the application contract.

## Background

- `app/build.gradle.kts:53` currently sets `minSdk = 29`.
- `gradle/libs.versions.toml:26` resolves `com.mocharealm.accompanist:lyrics-ui:1.0.19`; its
  published Android AAR declares `minSdkVersion=29`.
- That library resolves `com.mocharealm.gaze:capsule:2.1.1-patch2`; its Android AAR declares
  `minSdkVersion=24`.
- The remaining production dependency graph, including current Compose, Room, Media3, and updater
  artifacts, does not require more than API 23.
- Upstream `accompanist-lyrics-ui` commit
  `b0499d8cd1b6f4904d5c45ab7d0a8576edb4e72a` lowers its own Android minimum to 21 and adds guarded
  Unicode handling. Upstream `gaze-capsule` commit
  `9ba8ac7250908192ad17f964bc0e4c9c944be3d1` remains configured for API 24, while its shipped source
  is Compose/Kotlin geometry code without direct Android framework calls.
- The application uses the public `KaraokeLyricsView` and `KaraokeBreathingDotsDefaults` API and
  explicitly disables the optional blur effect.

## Requirements

- Vendor the Android-relevant source from the pinned upstream commits under `third_party/`.
- Expose two ordinary local Android library modules for Gaze Capsule and Accompanist Lyrics UI,
  both with `minSdk = 23`, using the repository's existing Kotlin and Compose toolchain.
- Preserve the upstream package names and the public lyrics UI API used by `PlayerScreen.kt` so
  application presentation code does not change.
- Make the local lyrics UI module depend on the local Gaze Capsule module and the existing external
  `lyrics-core` parser/model artifact.
- Remove the remote `lyrics-ui` artifact from the application runtime dependency graph; the
  external `lyrics-core` dependency remains unchanged.
- Restore the application minimum SDK to 23 for both `store` and `sideload` distributions.
- Record upstream URLs, pinned revisions, licenses, copied source scope, and every compatibility
  adaptation next to the vendored modules.
- Preserve current karaoke timing, translation, line wrapping, scrolling, focus exclusion, and
  rendering behavior.

## Acceptance Criteria

- [ ] `storeDebug` and `sideloadDebug` APKs build with application `minSdkVersion=23`.
- [ ] A minified `storeRelease` build completes with the repository's documented unsigned/test
      configuration, exercising R8 over the vendored source.
- [ ] The resolved runtime graph contains the local lyrics UI and Gaze projects and contains no
      external `lyrics-ui-android` or `capsule-android` artifact.
- [ ] Android lint reports no unguarded API usage above 23 in the vendored modules or application.
- [ ] Existing unit tests and the lyrics layout instrumentation test sources compile successfully.
- [ ] An API 23 runtime check covers application startup and opening a playing track with synced
      lyrics; when no API 23 emulator/device is available, the handoff explicitly records that
      remaining device-validation gap rather than claiming it ran.
- [ ] The repository contains license and provenance documentation for both vendored libraries.
- [ ] Existing Android 10+ behavior and both distribution flavors remain build-compatible.

## Out Of Scope

- Replacing the lyrics parser/model library or changing online lyrics matching behavior.
- Redesigning the lyrics UI or changing its visible styling.
- Publishing the forks to Maven Central, GitHub Packages, JitPack, or another remote registry.
- Supporting Android versions below API 23.
- Maintaining non-Android targets from the upstream Kotlin Multiplatform projects.
