# Implementation Plan

## Phase A — Shared contracts and build configuration

- [x] 1. Load `trellis-before-dev` plus the Android TV interaction, Android client/CI, error handling and cross-layer specs before editing code.
- [x] 2. Add schema-v1 fixtures for `release-metadata.json` and `update.json`; define one authoritative field naming/validation contract used by CI, Worker tests and Android parser tests.
- [x] 3. Add sideload/store BuildConfig gates for `SELF_UPDATE_ENABLED` and `UPDATE_MANIFEST_URL`; make sideload Release fail closed on a missing/non-HTTPS URL while keeping unconfigured debug builds network-inactive.
- [x] 4. Add only the sideload manifest permission/receiver needed for package installation; verify the merged store manifest contains neither self-update permission nor receiver.

## Phase B — CI release metadata

- [x] 5. Extend `.github/workflows/android.yml` after existing APK verification to compute final file size/SHA256, extract the verified signer certificate digest and generate schema-v1 `release-metadata.json` with build time and commit.
- [x] 6. Validate metadata against `version.properties`, `aapt`, `apksigner` and the final APK name; attach it to both the Actions artifact and GitHub Release.
- [x] 7. Add a deterministic local validation script/fixture test for metadata generation so quoting and digest normalization do not depend solely on a live GitHub run.

## Phase C — Cloudflare publisher backend

- [x] 8. Scaffold `tool/update-publisher/` as a minimal TypeScript Worker with Static Assets, R2 binding, Vitest Workers integration, example config and ignored local deploy config.
- [x] 9. Implement strict shared contract validators and R2 repository operations for config, immutable APKs, version manifests, history, highest-version tracking and conditional `update.json` writes.
- [x] 10. Implement GitHub repository configuration and Release listing: stable by default, explicit prerelease visibility, unconditional draft rejection, pagination/rate-limit handling and deterministic APK/metadata asset selection.
- [x] 11. Implement Release preflight: fetch metadata, validate package/version/commit/asset digest, reject missing or ambiguous assets, and return a safe preview model.
- [x] 12. Implement publish transaction: fetch the chosen GitHub APK, upload with R2 SHA256 validation and immutable cache metadata, write history/snapshot, then ETag-conditionally publish no-store `update.json`.
- [x] 13. Implement idempotent retry, historical-highest-version enforcement and ETag-conditional “撤回当前版本” without deleting objects.
- [x] 14. Protect mutating APIs with same-origin/CSRF checks and request ids; add structured errors/logging that never expose cookies, tokens or upstream response bodies.

## Phase D — Cloudflare publisher UI and deployment

- [x] 15. Build the Chinese single-app dashboard using static HTML/CSS/JS: initial configuration, current production version, GitHub Release list/filter, selected Release detail, editable notes and publish history.
- [x] 16. Add preflight progress, final preview and mandatory second confirmation; prerelease publishing receives an additional explicit warning and double submit is disabled.
- [x] 17. Add “撤回当前版本” confirmation and clearly explain that installed devices are not downgraded.
- [x] 18. Add `scripts/setup.mjs` and README: `wrangler login`, bucket creation/binding, Worker deploy, R2 custom download domain, `.publisher/*` public blocking, Cloudflare Access email policy, and App manifest URL output. Do not request CF/R2 tokens in the UI.

## Phase E — Android update core

- [x] 19. Implement strict `UpdateManifest` parsing and endpoint policy: known schema, bounded body/text, fixed package, HTTPS, same host, positive size/version and lowercase SHA256.
- [x] 20. Implement device-global `UpdatePreferences` ignored-version set and startup cleanup, fully separate from account-bound `AppPreferences`/Room.
- [x] 21. Implement `UpdateClient` with cancellation, bounded timeouts, no cross-host redirects and explicit UpToDate/Available/Error results.
- [x] 22. Implement `UpdateCoordinator` single-flight state machine and monotonic in-memory schedule: startup, +12h after any success, +30m after automatic failure, unlimited manual checks, visible-host gating and silent automatic failures.
- [x] 23. Implement pending prompt behavior so automatic updates wait during full-screen Player while manual Settings checks immediately surface even an ignored version.

## Phase F — Android download, verification and installation

- [x] 24. Implement foreground-only download with progress, one active call, `.part` cleanup, cancellation on back/cancel/background/exit and no retry resume or notification.
- [x] 25. Implement SHA256, byte count, archive package/version and installed-vs-candidate signer verification; retain only an atomically renamed verified APK.
- [x] 26. Implement unknown-source permission effect and resume handling; preserve the verified APK only during an explicit settings/installer handoff.
- [x] 27. Implement `PackageInstaller.Session`, non-exported status receiver, `STATUS_PENDING_USER_ACTION` launch, terminal cleanup and user-facing cancel/failure mapping.
- [x] 28. Wire Activity lifecycle and explicit app shutdown to coordinator visibility, timer, download cancellation and cleanup behavior.

## Phase G — Android TV UI

- [x] 29. Refactor `SettingsScreen` to the approved vertical layout, preserving existing settings and initial focus; add BuildConfig version, author `Tag mig hånden`, repository address and manual update action for sideload.
- [x] 30. Implement the approved update dialog with current/latest version, notes, ignored badge, three independent callbacks, default “立即更新” focus and explicit D-pad neighbors.
- [x] 31. Implement download/verifying/permission/error states in the same dialog surface with touch-safe commands and predictable focus restoration.
- [x] 32. Integrate prompt rendering at Login and authenticated route hosts; suppress only automatic prompt rendering on `LibraryRoute.Player` and display the pending prompt on return.

## Phase H — Verification and rollout checks

- [ ] 33. Add JVM/Robolectric tests for manifest parsing, endpoint rejection, version/ignore decisions, timer transitions, silent/manual error behavior, cancellation cleanup and signer comparison helpers.
- [ ] 34. Add MockWebServer tests for update.json and APK length/hash/cancellation paths; assert no GitHub/proxy request can be emitted by App code.
- [ ] 35. Add Compose instrumentation tests for settings content, removed helper copy, manual states, ignored label, dialog callback uniqueness, download cancel and focus order.
- [ ] 36. Add Activity/installer integration coverage where feasible and manually verify unknown-source denied/allowed/cancel flows on representative Android TV hardware or emulator.
- [ ] 37. Run Worker unit/integration tests with local R2 for stable/prerelease/draft filters, metadata mismatch, immutable upload, publish ordering, ETag conflict, idempotency and rollback.
- [ ] 38. Deploy a test Worker/R2 environment, publish the current version as baseline, public-read `update.json`, then validate one higher-version end-to-end update without relying on GitHub from the App.
- [x] 39. Run the complete quality gate and `trellis-check`; update applicable Trellis specs before commit.

## Validation Commands

```sh
./gradlew :app:testSideloadDebugUnitTest
./gradlew :app:assembleSideloadDebug :app:assembleStoreDebug
./gradlew :app:lintSideloadDebug :app:lintStoreDebug
./gradlew :app:connectedSideloadDebugAndroidTest

cd tool/update-publisher
npm ci
npm run typecheck
npm test
npm run build
npx wrangler deploy --dry-run
```

Release-only validation additionally runs in CI with the configured APK signing identity and `UPDATE_MANIFEST_URL`. If no Android device is connected, record the device-only gap; unit tests, debug assemblies, lint, Worker tests/typecheck/build and manifest inspection remain mandatory.

### Android TV emulator validation — 2026-08-11

- `FnMusicTV_API36` (`sdk_google_atv64_arm64`, Android 16 / API 36): the complete sideload instrumentation suite passed, 38/38 tests.
- The update-dialog instrumentation tests passed, 2/2 tests, covering an ignored manual update, single update callback, default update focus, progress display and download cancellation.
- `UpdateInstallCapabilityTest` passed for both sideload and store. It verifies sideload-only install permission, a non-exported install receiver, resolvability of Android's unknown-source settings screen, and absence of the permission/receiver from store.
- A real higher-version install remains a release-environment check because the emulator debug package is `com.fnmusic.tv.debug`, while update APK validation intentionally requires the production package `com.fnmusic.tv` and its signing identity.

### Cloudflare production infrastructure — 2026-08-11

- Deployed Worker `fn-music-tv-update-publisher` at `https://<PUBLISHER_DOMAIN>` with R2 binding to the new `fn-music-tv-updates` bucket; the pre-existing `astrbot` bucket was left untouched.
- Bound `https://<R2_PUBLIC_DOMAIN>` as the public R2 custom domain. Cloudflare reports active ownership and SSL with minimum TLS 1.2.
- Added Cloudflare Access protection for the publisher host, restricted to `<ADMIN_EMAIL>`; an unauthenticated request redirects to the Access login page.
- Seeded the single-app publisher configuration for `QiaoKes/fn-music-tv` and blocked public `/.publisher` access with an active WAF rule. Direct origin verification returns 403 for `/.publisher/config.json`.
- `update.json` currently returns 404 by design because no baseline Release has been published. Step 38 remains open until the user selects the first Release and a signed higher-version install is validated end to end.
- Cloudflare authoritative DNS returns the expected proxied records. The local network's UDP DNS path temporarily returned stale/invalid answers, so the origin smoke checks used the authoritative record directly; this does not change the deployed DNS configuration.
- Configured GitHub Actions repository variable `FN_MUSIC_UPDATE_MANIFEST_URL=https://<R2_PUBLIC_DOMAIN>/update.json` and verified it by reading the value back through GitHub CLI.
- Fixed the production Worker GitHub fetch path by binding the runtime fetch function to the global scope. A receiver-sensitive regression test now covers the production-only `TypeError`; the deployed dashboard successfully lists stable Release `v1.0.5` without exposing diagnostic details in its API response.
- Added optional `GITHUB_TOKEN` Worker Secret support for all GitHub requests and a regression asserting the Bearer value never enters the URL. Created a repository-scoped Fine-grained PAT with Contents/Metadata read-only access, expiring 2027-08-11, deployed the Secret-bearing Worker version at 100%, and verified both Release listing and `v1.0.5` preflight without publishing to R2.

## Risky Files and Rollback Points

- `app/build.gradle.kts` and flavor manifests: verify store never acquires sideload permission and Release URL validation only gates sideload.
- `TvMusicApplication.kt`, `MainActivity.kt`, `AppUiDependencies.kt`: do not couple update lifetime to NAS login or leak Activity through application scope.
- `AuthenticatedApp.kt` / `SettingsScreen.kt`: preserve route-state focus and current settings behavior while adding the global dialog host.
- `com.fnmusic.tv.update/*`: cancellation must close network/file/session resources; installer system handoff is the only allowed background retention state.
- `.github/workflows/android.yml`: preserve current signing/secret scans and release idempotency while adding metadata.
- `tool/update-publisher/*`: write versioned objects before `update.json`; conditional failure must never report success or delete history.

Rollback is non-destructive: disable `SELF_UPDATE_ENABLED` for an emergency App build, redeploy/revert the Worker independently, or use publisher “撤回” to restore the prior manifest. Never delete historical R2 objects as part of rollback.

## Start Gate

- [x] PRD, design and implementation plan reviewed together.
- [x] `implement.jsonl` and `check.jsonl` contain real relevant spec entries.
- [x] User explicitly approves this latest planning summary in a subsequent message.
- [x] Only then run `task.py start`; task status must remain `planning` until approval.
