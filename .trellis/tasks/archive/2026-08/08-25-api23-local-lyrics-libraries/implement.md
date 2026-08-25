# Implementation Plan

## 1. Vendor Gaze Capsule

- Create `third_party/gaze-capsule` as an Android library with namespace preserved and API 23.
- Copy the pinned upstream common Kotlin source without behavioral edits.
- Add the required Compose dependencies from the root version catalog.
- Add upstream license and provenance documentation.

## 2. Vendor Accompanist Lyrics UI

- Create `third_party/accompanist-lyrics-ui` as an Android Compose library with namespace preserved
  and API 23.
- Copy pinned common lyrics UI source and the Android Unicode implementation.
- Merge the KMP `expect`/`actual` string helpers into the Android source set without changing their
  behavior.
- Depend on the local Gaze project, external `lyrics-core`, and root Compose libraries.
- Add upstream license and provenance documentation.

## 3. Integrate The Local Modules

- Register both projects in `settings.gradle.kts`.
- Replace the app's external lyrics UI dependency with the local project.
- Remove the now-unused lyrics UI version/catalog alias while retaining `lyrics-core`.
- Change application `minSdk` from 29 to 23.
- Search the repository for stale external lyrics UI or API 29 compatibility assumptions.

## 4. Verify Compatibility

- Run Gradle dependency reporting for both application flavors and confirm no external lyrics UI or
  Gaze Android artifacts remain.
- Build `storeDebug` and `sideloadDebug`; inspect the APK or merged manifest for minimum SDK 23.
- Compile instrumentation tests and run the full unit test suite.
- Run lint for the application and both vendored modules.
- Build a minified unsigned/test-configured `storeRelease` to exercise R8.
- Run an API 23 emulator/device startup and synced-lyrics smoke check when available; otherwise
  record the exact unexecuted runtime check.

## 5. Review And Documentation

- Review the vendored diff against the pinned upstream revisions.
- Confirm licensing/provenance files cover every copied source file.
- Run the Trellis quality check, update the Android client contract with the API 23/local-library
  build boundary if the implementation establishes a durable project convention, then prepare the
  task for commit and archive.

## Rollback Points

- After steps 1-2, the local modules are unreferenced and can be removed without affecting the app.
- After step 3, rollback consists of restoring the external dependency and `minSdk = 29` before
  removing the local modules.
