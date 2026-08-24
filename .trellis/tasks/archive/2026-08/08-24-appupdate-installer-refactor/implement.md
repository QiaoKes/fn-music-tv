# Implementation Plan

## Phase A - Dependency and manifest boundary

- [x] 1. Run `trellis-before-dev` and reload the update distribution spec before product edits.
- [x] 2. Add pinned AppUpdate/AppUpdate no-op aliases to `gradle/libs.versions.toml`.
- [x] 3. Wire the full artifact only to sideload and the no-op artifact only to store.
- [x] 4. Update the sideload manifest: retain install permission and provider, remove the legacy
  receiver plus AppUpdate's unused service/activity through merger rules; override provider metadata
  with an internal `cache-path` limited to `updates/`.
- [x] 5. Inspect sideload/store merged manifests and confirm the merged provider paths resource is
  the narrow app-owned definition.

## Phase B - Installer and coordinator refactor

- [x] 6. Replace `UpdateInstaller`'s PackageInstaller session with the framework's
  `ApkUtil.installApk` and dynamic `${packageName}.fileProvider` authority.
- [x] 7. Delete `UpdateInstallReceiver` and remove its action/status contract.
- [x] 8. Replace the Boolean system handoff flag with distinct permission/installer handoff states.
- [x] 9. Remove PackageInstaller status handling and implement launch failure, permission denial and
  installer-return cleanup as retryable errors.
- [x] 10. Keep the existing secure verification pipeline and foreground download lifecycle unchanged.
- [x] 11. Review/update installation status copy without changing TV dialog layout or focus behavior.

## Phase C - Tests and executable contract

- [x] 12. Add installer/coordinator unit tests for dynamic authority, permission return, ACTION_VIEW
  launch error, installer cancellation return and APK cleanup.
- [x] 13. Update `UpdateInstallCapabilityTest` to assert AppUpdate provider and the absence of the old
  receiver; cover both distribution flavors.
- [x] 14. Add merged-manifest assertions that sideload excludes the unused library service/activity
  and store excludes all install capability.
- [x] 15. Update `.trellis/spec/backend/self-update-distribution.md` from PackageInstaller callbacks
  to the verified FileProvider/ACTION_VIEW handoff contract.

## Phase D - Quality gate and test package

- [x] 16. Run focused update tests, then sideload/store compile, unit test, lint and assemble tasks.
- [x] 17. Inspect the generated APK manifest, provider authority and signature with Android tools.
- [x] 18. Exercise the install confirmation flow on an available emulator/device and record any
  environment limitation explicitly.
- [x] 19. Build a testable APK signed with the same identity required for covering the installed test
  version, report its absolute path and checksum, then hand it to the user for Vidda C3 Pro coverage.
- [x] 20. Run `trellis-check`; fix all in-scope findings and update task acceptance evidence.

## Phase E - Vidda artifact compatibility

- [x] 21. Compare FNTV 1.3.2/1.3.4 and Fn Music TV 1.0.4-1.0.7 manifests, SDK levels,
  certificate identities and signing schemes.
- [x] 22. Identify the 1.0.4 -> 1.0.5 regression boundary: minSdk 23 -> 29 and v1+v2 -> v2-only.
- [x] 23. Explicitly enable v1+v2 release signing and add a final-artifact CI assertion using a
  compatibility minimum SDK.
- [x] 24. Build and inspect a non-debuggable, official-package, official-certificate, higher-version
  v1+v2 test APK.
- [x] 25. Confirm external click and cover-install behavior on the user's Vidda C3 Pro.

## Phase F - Re-release metadata

- [x] 26. Bump the re-release to version 1.0.8 (versionCode 24).
- [x] 27. Keep the user's final 1.0.8 changelog copy covering the dual-signature install fix,
  AppUpdate handoff refactor, and the selected user-facing changes from 1.0.7.

## Phase G - Upgrade-flow test artifact

- [x] 28. Build a non-debuggable, official-package, release-signed test APK with versionCode 22 so
  it detects the live 1.0.7 (versionCode 23) update manifest.
- [x] 29. Verify the test APK embeds the production update manifest URL and includes v1+v2 signing,
  then restore the source version/build configuration to the 1.0.8 release state.

## Verification Evidence

- `testSideloadDebugUnitTest` and `testStoreDebugUnitTest`: passed.
- `lintSideloadDebug`, `lintStoreDebug`, `assembleSideloadDebug`, and `assembleStoreDebug`: passed.
- `compileSideloadDebugAndroidTestKotlin` and `compileStoreDebugAndroidTestKotlin`: passed.
- `UpdateInstallCapabilityTest`: passed on the connected Android 13 device for both sideload and
  store variants.
- APK inspection confirmed `REQUEST_INSTALL_PACKAGES`, non-exported
  `com.azhon.appupdate.config.AppUpdateFileProvider`, authority `com.fnmusic.tv.fileProvider`, and
  the narrow `verified_updates -> cacheDir/updates/` resource. The AppUpdate service/activity and
  legacy receiver are absent.
- Cover-install test APK: `app/build/outputs/apk/vidda-test/fn-music-tv-1.0.8-appupdate-install-test.apk`;
  package `com.fnmusic.tv`, version code `24`, non-debuggable, release signer SHA-256
  `087469b178ef3fff7e0653007197e771905cd687985ae44ad86393920cb51489`, APK SHA-256
  `91a3f47c8e2f0882e433abc06824efaecd532f7180b063be4b9d71419ad6fb31`.
- A normal sideload Release build remains gated by missing local `FN_CONNECT_AUTHX_PREFIX` and
  `FN_CONNECT_API_KEY`; the test APK was built from an isolated debug-compatible build type with
  the official package/signing identity. Final system-confirmation and cover-install behavior still
  requires the user's Vidda C3 Pro test.
- Dual-signature test APK: `app/build/outputs/apk/vidda-test/fn-music-tv-1.0.9-v1-v2-install-test.apk`;
  package `com.fnmusic.tv`, version code `25`, non-debuggable, v1+v2, release signer SHA-256
  `087469b178ef3fff7e0653007197e771905cd687985ae44ad86393920cb51489`.
- The user confirmed on a Hisense Vidda C3 Pro that the v1+v2 test APK opens from the file manager
  and covers the installed official build successfully. This isolates v2-only signing as the
  external-click compatibility regression.
- Upgrade-flow test APK: `app/build/outputs/apk/upgrade-test/fn-music-tv-1.0.6-upgrade-test.apk`;
  package `com.fnmusic.tv`, version `1.0.6-upgrade-test` (code 22), non-debuggable, v1+v2, release
  signer SHA-256 `087469b178ef3fff7e0653007197e771905cd687985ae44ad86393920cb51489`,
  APK SHA-256 `f1dd9925450e144c2410e5ecf5550119eaecb3d78bb2b7b5b5a2540984ae2368`.
  The APK embeds `https://fnmusic-update.myvsco.de/update.json`, which currently advertises 1.0.7
  (code 23). The temporary lower version and build type were removed after packaging; source remains
  on 1.0.8 (code 24).
