# Design: local API 23 lyrics libraries

## Architecture

Add two root Gradle subprojects:

```text
app
  -> third_party:accompanist-lyrics-ui
       -> third_party:gaze-capsule
       -> com.mocharealm.accompanist:lyrics-core
       -> repository Compose libraries
```

Each vendored project is an Android-only `com.android.library` module. This deliberately avoids
embedding independent Kotlin Multiplatform builds and their plugin/version catalogs inside the app
repository. The application needs only the Android variants, and using the root toolchain keeps one
Compose/Kotlin resolution model.

## Source Import

### Gaze Capsule

- Source: `https://github.com/6xingyv/gaze-capsule`
- Revision: `9ba8ac7250908192ad17f964bc0e4c9c944be3d1`
- Import the upstream `commonMain` Kotlin source into the local Android module unchanged.
- Configure namespace `com.mocharealm.gaze.capsule` and `minSdk = 23`.

### Accompanist Lyrics UI

- Source: `https://github.com/6xingyv/accompanist-lyrics-ui`
- Revision: `b0499d8cd1b6f4904d5c45ab7d0a8576edb4e72a`
- Import the upstream `commonMain` lyrics composables and the Android Unicode implementation.
- Flatten only the KMP `expect`/`actual` string helpers into one Android source file; keep their
  behavior and the upstream API packages intact.
- Configure namespace `com.mocharealm.accompanist.lyrics.ui` and `minSdk = 23`.
- Replace the upstream remote Gaze dependency with the local project dependency.

Each module receives a provenance document and upstream license copy. The provenance document is
the synchronization boundary: revision, imported paths, and local adaptations must be updated
together during any future upstream refresh.

## Application Integration

- Register both local modules in `settings.gradle.kts`.
- Replace `implementation(libs.accompanist.lyrics.ui)` with the local project dependency.
- Remove only the unused remote lyrics UI alias/version from the version catalog.
- Keep `lyrics-core` external because it is a pure Kotlin model/parser dependency and already works
  at API 23.
- Set `app` minimum SDK to 23. Existing core and baseline-profile Android modules are already 23.

No `PlayerScreen.kt` import or call-site changes are expected because the vendored package names and
public symbols remain identical.

## Compatibility

- Compile and target SDK remain 36 for consistency with the application.
- JVM bytecode remains target 17, matching the root project; Android desugaring continues to be
  owned by the application toolchain.
- The upstream Unicode implementation gates newer Unicode blocks on the runtime SDK and catches
  lookup failures. This updated implementation is included rather than copying the older 1.0.19
  binary behavior.
- `BlurEffect` remains available in the API for source compatibility. The application continues to
  pass `useBlurEffect = false`; lint and API 23 runtime validation guard against verifier/runtime
  regressions.

## Validation And Rollback

Validation covers manifest minimum SDK, dependency resolution, debug flavors, minified Release,
lint, tests, and an API 23 smoke check when a device is available.

Rollback is mechanical: restore the remote lyrics UI dependency, remove the two `third_party`
module registrations, and restore application `minSdk = 29`. No application data, API, database, or
user preference migration is involved.

