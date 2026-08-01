# Android Client Data, Playback, and CI Contracts

## 1. Scope / Trigger

Use this contract for changes crossing the NAS API, session repository, Room cache, Media3
service, or build pipeline. These boundaries own credentials, persisted schema, playback
authorization, and distributable APKs, so changes require contract-level tests.

## 2. Signatures

```kotlin
ServerUrlNormalizer.normalize(input: String, useHttps: Boolean): ServerUrlResult
ServerUrlNormalizer.editableInput(
    input: String,
    currentUseHttps: Boolean,
): EditableServerInput
SessionRepository.login(
    serverInput: String,
    useHttps: Boolean,
    username: String,
    password: CharArray,
    remember: Boolean,
)
TrimMusicApi.login(username: String, password: String, deviceId: String): LoginResultDto
data class ApiEnvelope<T>(val code: Int, val msg: String = "", val data: T? = null)
```

Room uses `AppDatabase` version 2 and exports JSON schemas. `MIGRATION_1_2` adds non-null
`account_state.schemaRevision INTEGER DEFAULT 1`. Media3 custom commands are ConfigureAuth,
ClearAuth, and ClearCache; command failures use `SessionError` codes, not removed
`SessionResult.RESULT_ERROR_*` constants.

## 3. Contracts

### NAS and session

- Accepted server schemes are HTTP and HTTPS only. Reject credentials, queries, fragments, empty
  hosts, and other schemes before any request.
- Canonical API base ends in `/music/api/v1/`; an explicit scheme also updates the HTTPS toggle.
- A host or IP without an explicit port uses port `5666`. Explicit ports, including `80`, are
  preserved. `editableInput` displays only the host when the canonical URL uses port `5666` and
  the standard music API path; custom ports remain visible.
- Password enters the repository as `CharArray` and is zero-filled in `finally`. It is never stored.
- `TrimMusicApi.login` sends the password as lowercase, 64-character SHA-256 hex. Hashing happens
  once at the API boundary so UI and repository code continue to handle the user's plain input.
- Password login includes the stable installation `deviceId`. Authenticated calls use the returned
  token as the raw `Authorization` value, without a `Bearer` prefix.
- `remember=true` stores only the user token in Android Keystore. `remember=false` keeps it in memory.
- API code `120001`/`99999` maps to `Unauthenticated`; `120002` maps to `AccountDisabled`.
- User-token invalidation clears auth and returns to login. HLS or roam-session invalidation must not
  clear the user token.

### Room and cache

- Every persisted record is namespaced by `serverGuid:userGuid`.
- Essential account state and evictable page/lyric/index payloads remain separate.
- Evict payloads at 24 MiB target and cap physical DB + WAL + SHM at 32 MiB.
- All schema changes increment the version, export the new schema, add a migration, and preserve
  queue/settings/account namespaces. Destructive migration is forbidden.
- Namespace clearing checkpoints WAL and runs incremental vacuum; cache-only clearing preserves
  `account_state`.

### Playback and CI

- Playback HTTP redirects and cross-protocol redirects stay disabled; Authorization is configured
  through the MediaSession command and cleared on logout.
- Media cache keys start with the account namespace. ClearCache removes only matching keys.
- Media3 unstable APIs require `androidx.annotation.OptIn(UnstableApi::class)` at the implementation
  boundary so lint accepts the usage without making callers opt in.
- CI uses JDK 21 and SDK 36, runs all app/library unit tests and lint variants, builds sideload/store
  debug and unsigned release APKs, compiles both app Android-test APKs, and builds both benchmark
  variants. Application and verification artifacts are retained for 14 days.
- `baselineprofile` resolves the app's `distribution` dimension to `sideload`.

## 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| API HTTP 401 or envelope code 120001/99999 | `AppException(Unauthenticated)` |
| Envelope code 120002 | `AppException(AccountDisabled)` and token invalidation |
| HTTP redirect, I/O failure, or non-success status | `NetworkUnavailable` |
| Envelope code 0 with missing required data | `Empty` |
| Invalid/corrupt JSON | `Unknown("invalid_json")` |
| Bare host or IP | Add port `5666` and `/music/api/v1/` |
| Explicit port, including `80` | Preserve that port |
| Password login request | Send SHA-256 lowercase hex, never the plain password |
| ConfigureAuth has blank token/namespace | `SessionError.ERROR_BAD_VALUE` |
| Unknown MediaSession command | `SessionError.ERROR_NOT_SUPPORTED` |
| DB payload or physical budget exceeded | LRU batch eviction, checkpoint, incremental vacuum |
| CI produces no APK | Artifact upload fails the job |

## 5. Good / Base / Bad Cases

- Good: `10.0.0.115` normalizes to `http://10.0.0.115:5666/music/api/v1/` and is shown again as
  `10.0.0.115` on the login screen.
- Good: the login request body contains the exact SHA-256 lowercase hex plus the stable `deviceId`.
- Base: network failure returns cached page/index data where that repository method permits fallback.
- Bad: pre-hashing in the UI and hashing again in `TrimMusicApi`.
- Bad: storing a password or bearer token in Room, SharedPreferences, logs, tests, or workflow files.
- Bad: `fallbackToDestructiveMigration`, an unexported schema version, or cache rows without namespace.
- Bad: running a root `test` selector that accidentally schedules connected benchmark tests; CI names
  every non-device task explicitly.

## 6. Tests Required

- `ServerUrlNormalizerTest`: default port `5666`, explicit/custom ports, editable host display, schemes,
  paths, embedded credentials, query/fragment, and invalid hosts.
- `ApiDecoderTest`/`TrimMusicApiTest`: exact password hash, `deviceId`, raw auth header, code mapping,
  invalid JSON, redirects, and no retry.
- `AppDatabaseMigrationTest`: open a version-1 schema with version 2 and assert data plus
  `schemaRevision=1` survive.
- `LocalStoreTest`: account isolation, LRU eviction, physical-budget reclaim, and essential-state clear.
- Playback unit/lint checks: current `SessionError` constants and explicit unstable-API opt-in.
- CI-equivalent local gate: all named workflow Gradle tasks must succeed and output four app APKs,
  two app Android-test APKs, and benchmark APKs.

## 7. Wrong vs Correct

```kotlin
// Wrong: old constant family; current Media3 lint rejects it.
SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)

// Correct.
SessionResult(SessionError.ERROR_BAD_VALUE)
```

```kotlin
// Wrong: erases user state when a schema changes.
Room.databaseBuilder(context, AppDatabase::class.java, NAME)
    .fallbackToDestructiveMigration()

// Correct: versioned, exported, lossless migration.
Room.databaseBuilder(context, AppDatabase::class.java, NAME)
    .addMigrations(AppDatabase.MIGRATION_1_2)
```

```kotlin
// Wrong: sends a plain password or hashes it in more than one layer.
api.login(username, plainPassword, deviceId)

// Correct: TrimMusicApi owns the single SHA-256 conversion at the network boundary.
api.login(username, plainPassword, deviceId) // request body receives sha256Hex(plainPassword)
```
