# Sideload Self-Update Distribution Contract

## 1. Scope / Trigger

Apply this contract whenever changing the sideload update client, update Compose state, release APK
workflow, `release-metadata.json`, the Cloudflare publisher, or R2 object layout. These parts form one
security and ordering boundary:

```text
version.properties → signed GitHub APK + metadata → publisher → R2 update.json → Android installer
```

The `store` flavor is outside this pipeline and must remain unable to request package installation.

## 2. Signatures

```text
Build inputs:
  FN_MUSIC_UPDATE_MANIFEST_URL=https://<r2-custom-domain>/update.json
  -PfnMusicUpdateManifestUrl=<same value>

BuildConfig:
  SELF_UPDATE_ENABLED: Boolean
  UPDATE_MANIFEST_URL: String

release-metadata.json schemaVersion: 1
update.json schemaVersion: 1

UpdateController.state: StateFlow<UpdateUiState>
UpdateController.checkManually()
UpdateController.ignoreAvailableVersion()
UpdateController.startDownload()
UpdateController.cancelDownload()
UpdateController.openInstallPermissionSettings()
UpdateController.setAutomaticPromptAllowed(Boolean)

Publisher API:
  GET  /api/bootstrap
  GET  /api/releases?prerelease=<boolean>
  POST /api/config
  POST /api/preflight
  POST /api/publish
  POST /api/rollback

Worker Secret:
  GITHUB_TOKEN=<fine-grained PAT scoped to QiaoKes/fn-music-tv>
```

R2 binding is named `UPDATE_BUCKET`. The App never receives this binding or Cloudflare credentials.

## 3. Contracts

### Release metadata

`release-metadata.json` has these required fields:

```json
{
  "schemaVersion": 1,
  "packageName": "com.fnmusic.tv",
  "versionName": "1.0.6",
  "versionCode": 22,
  "apk": {
    "fileName": "fn-music-tv-1.0.6-universal.apk",
    "size": 5560000,
    "sha256": "64 lowercase hex",
    "signingCertificateSha256": "64 lowercase hex"
  },
  "builtAt": "ISO-8601 UTC",
  "commitSha": "40 lowercase hex"
}
```

CI derives version fields from `version.properties`, file fields from the final renamed APK, signer
identity from successful `apksigner --print-certs`, and commit from `GITHUB_SHA`. `builtAt` must use
the exact UTC shape `YYYY-MM-DDTHH:MM:SSZ`; fixture overrides must pass the same validation. The
publisher must match metadata to exactly one APK asset and the GitHub asset digest before writing R2.

### Public update manifest

`update.json` has these required fields:

```json
{
  "schemaVersion": 1,
  "packageName": "com.fnmusic.tv",
  "versionName": "1.0.6",
  "versionCode": 22,
  "title": "v1.0.6",
  "notes": "plain text, at most 4000 characters",
  "apk": {
    "url": "https://download.example.com/releases/22/app.apk",
    "size": 5560000,
    "sha256": "64 lowercase hex"
  },
  "publishedAt": "ISO-8601 UTC",
  "githubReleaseUrl": "https://github.com/QiaoKes/fn-music-tv/releases/tag/v1.0.6"
}
```

The App accepts only schema 1, fixed package `com.fnmusic.tv`, HTTPS URLs, positive size/version,
lowercase SHA256, a bounded response, and an APK host equal to the build-fixed manifest host. It has
no GitHub, proxy, or user-configurable fallback.

### Timing, ignore, and lifecycle

- The first visible Activity start checks asynchronously.
- Every successful automatic or manual check schedules the next automatic check at monotonic now +
  12 hours. An automatic failure schedules +30 minutes and stays silent. Timing is process memory.
- Manual checks are unlimited. If an automatic single-flight is active, manual demand joins it and
  upgrades its result/error to manual-visible behavior.
- Ignore records are a device-global `versionCode` set in a dedicated SharedPreferences file, never
  an account preference or Room row. Automatic checks suppress only the exact ignored latest code;
  manual checks still show it with `ignored=true`.
- Automatic prompts move to pending memory while the full-screen Player route is active. Manual
  Settings prompts remain immediate.
- A download exists only while the Activity is foreground. Back, cancel, ordinary background, or
  explicit exit must call `Call.cancel()` promptly, including while a blocking response read is in
  progress, and delete both `.part` and any transient `.apk`. Only an explicit permission/installer
  handoff may retain the verified APK temporarily.

### Verification and installation

Before `PackageInstaller.Session` commit, require exact byte count and SHA256, archive package
`com.fnmusic.tv`, candidate `versionCode` equal to the manifest and above the installed version, and
equal non-empty installed/candidate signer digest sets. Empty signer sets are a verification failure,
not a match. Missing unknown-source permission opens only the current package's settings page. If the
user returns without granting permission, delete the verified APK and require a new download.
`STATUS_PENDING_USER_ACTION` launches the system-owned confirmation; there is no silent install.

### R2 publish ordering and retention

```text
releases/<versionCode>/<apk-file>  # immutable, one-year cache
manifests/<versionCode>.json       # immutable snapshot
update.json                        # public pointer, no-store, conditional write
.publisher/*                       # private management state/history
```

Upload and integrity-check the immutable APK before writing `update.json`. Write `update.json` with
the observed ETag so concurrent publishers cannot overwrite each other. Track the historical highest
`versionCode`; a retry of the exact live version/hash is idempotent and repairs missing highest/history
bookkeeping. Rollback conditionally restores the previous manifest but never deletes objects and never
lowers the historical maximum.

Mutating publisher endpoints require same-origin, JSON, and double-submit CSRF validation. Cloudflare
Access protects both static assets and `/api/*`; the UI must not request Cloudflare/R2 tokens.

Production GitHub requests use the optional `GITHUB_TOKEN` Worker Secret as `Authorization: Bearer`.
The production deployment requires a fine-grained PAT limited to `QiaoKes/fn-music-tv` with only
`Contents: Read-only` (and GitHub's required `Metadata: Read-only`). Never place the PAT in R2,
publisher configuration, browser JavaScript, source control, command arguments, or logs. Anonymous
access remains a local/fail-closed fallback only: Cloudflare Workers can share an egress IP, so
GitHub's unauthenticated per-IP limit is not reliable even for a single administrator.

## 4. Validation & Error Matrix

| Condition | Required result |
|---|---|
| sideload Release URL missing, non-HTTPS, credentialed, or fragmented | fail the Release build |
| store flavor | updater disabled; no install permission or receiver in merged manifest |
| manifest too large, unknown schema, wrong package, bad SHA/size/time | reject as check failure |
| APK host differs from manifest host or redirect occurs | reject; never follow to fallback host |
| latest `versionCode` is current/older | automatic idle; manual “already latest” |
| exact latest code is ignored | automatic idle; manual available with ignored badge |
| file count/hash/package/version/signer mismatch | delete file; do not open installer |
| download cancellation/background | close call and delete partial/final transient file |
| unknown-source permission denied/cancelled | delete verified APK; show retryable error and require a new download |
| GitHub Release draft | never publish |
| `GITHUB_TOKEN` missing/expired/revoked or GitHub rate limit exhausted | show the safe reset/retry error; do not mutate R2 |
| prerelease | hidden by default; explicit visibility and extra confirmation required |
| missing/ambiguous metadata or APK asset | preflight fails without R2 mutation |
| R2 immutable key exists with different content | reject publication |
| new code is not above historical maximum | reject unless exact idempotent live retry |
| ETag conflict on `update.json` | report conflict; do not report success |

## 5. Good / Base / Bad Cases

- Good: automatic code 22 was ignored, code 23 is later published, and the App prompts for 23.
- Good: a manual check joins an in-flight automatic request and still displays an ignored release or
  a retryable error.
- Good: APK upload succeeds, immutable snapshot is written, and only then the conditional public
  pointer changes.
- Good: a response is lost after publishing; retrying the same request/version/hash returns success
  and repairs management history without duplicating or overwriting the APK.
- Base: R2 is temporarily unavailable; automatic checks remain silent and retry after 30 minutes,
  while manual checks explain failure without blocking music playback.
- Base: the GitHub PAT expires or is revoked; Release listing/preflight fails visibly while existing
  `update.json` and APKs remain unaffected.
- Base: the user cancels Android's install page; the candidate APK is removed and a later attempt
  starts from a new download.
- Bad: reading GitHub Releases directly from the App, adding `ghfast` fallback, persisting the 12-hour
  timer, or treating `versionName` as version ordering.
- Bad: placing update ignore codes in account-bound `AppPreferences`, using DownloadManager/background
  service, accepting a checksum-only APK without signer comparison, or bypassing system confirmation.
- Bad: exposing `.publisher/*`, Cloudflare tokens, or R2 credentials through the admin form.
- Bad: storing `GITHUB_TOKEN` in `.publisher/config.json`, the UI, `wrangler.jsonc`, or repository
  secrets intended for GitHub Actions instead of a Cloudflare Worker Secret.

## 6. Tests Required

- Contract tests: valid schema, unknown schema, wrong package, insecure/cross-host URL, uppercase/bad
  digest, invalid time/size/version, and oversized body.
- MockWebServer tests: HTTPS manifest success, redirect rejection, byte-count/hash mismatch, and a
  throttled blocking read whose cancellation closes the call and removes both `.part` and `.apk`.
- Preferences/coordinator tests: exact ignored code, higher-code prompt, cleanup below installed code,
  startup/+12h/+30m transitions, manual joining auto, silent automatic error, player pending prompt,
  and foreground-only download.
- APK tests: archive package/version and signer set match/mismatch helpers, including two empty signer
  sets being rejected. Device/emulator coverage must exercise unknown-source denied/allowed, system
  confirm/cancel, and install status callback.
- Compose tests: about copy/version source, removed helper wording, default update focus, three unique
  callbacks, ignored badge, progress percentage/cancel, and focus neighbors.
- Flavor/build checks: sideload and store compile/assemble/lint/unit tests; merged store manifest must
  contain neither `REQUEST_INSTALL_PACKAGES` nor `UpdateInstallReceiver`.
- CI metadata script: deterministic fixture verifies file name, size, lowercase hashes, signer, version,
  commit, and invalid-input rejection.
- Worker tests with local R2: stable/prerelease/draft filters, metadata mismatch, immutable write,
  conditional pointer conflict, highest-code enforcement, idempotent repair, rollback, CSRF, typecheck,
  and dry-run deploy build.
- GitHub client tests: when `GITHUB_TOKEN` exists, every list/detail/metadata/APK request carries a
  Bearer header; the token never appears in a URL or returned error. Production smoke checks must list
  and preflight a Release after the Secret-bearing Worker version receives traffic.

Hardware installation and production Cloudflare/R2 smoke tests are rollout checks; lack of a connected
device or deployment credentials must be recorded rather than silently claimed as passed.

## 7. Wrong vs Correct

```kotlin
// Wrong: account-scoped ignore and a background downloader outlive the visible App.
appPreferences.bindNamespace(userGuid)
DownloadManager.enqueue(request)

// Correct: device-global exact codes plus a visible-lifecycle coordinator.
UpdatePreferences(application).ignore(manifest.versionCode)
updateCoordinator.onBackground(changingConfigurations = false) // cancels active download
```

```typescript
// Wrong: publish the pointer first or overwrite a versioned APK key.
await bucket.put("update.json", manifest);
await bucket.put("releases/latest.apk", apk);

// Correct: immutable content first, then an ETag-conditional public pointer.
await repository.putImmutableApk(apkKey, response, size, sha256);
await repository.saveVersionManifest(manifest);
await repository.publishManifest(manifest, current?.etag ?? null);
```

```typescript
// Wrong: expose a credential through the persisted publisher configuration.
await repository.saveConfig({ ...config, githubToken });

// Correct: inject a least-privilege Worker Secret only at the GitHub boundary.
const github = new GithubClient(fetch, env.GITHUB_TOKEN);
```

```kotlin
// Wrong: checksum and display name alone authorize installation.
if (sha256 == manifest.sha256) installer.install(apk)

// Correct: verify every Android replacement boundary before system confirmation.
require(candidate.packageName == "com.fnmusic.tv")
require(candidate.longVersionCode == manifest.versionCode && candidate.longVersionCode > installed.longVersionCode)
require(signerDigests(candidate).isNotEmpty())
require(signerDigests(installed).isNotEmpty())
require(signerDigests(candidate) == signerDigests(installed))
installer.install(apk) // PackageInstaller still owns final user confirmation.
```
