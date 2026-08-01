# Android Client Data, Playback, and CI Contracts

## 1. Scope / Trigger

Use this contract for changes crossing the NAS API, session repository, process response cache,
artwork cache, Room store, application playback runtime, Media3 service, current-presentation
pipeline, or build pipeline. These boundaries own credentials, namespace isolation, persisted
schemas, playback authorization, current-track identity, and distributable APKs, so changes require
contract-level tests.

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
SessionRepository.restore()
data class ApiEnvelope<T>(val code: Int, val msg: String = "", val data: T? = null)

data class ResponseCacheKey(
    val namespace: String,
    val kind: String,
    val businessKey: String,
    val page: Int? = null,
)
SerializedResponseCache.getOrFetch(
    key: ResponseCacheKey,
    persist: suspend (String) -> Unit = {},
    fetch: suspend () -> String,
): String
SerializedResponseCache.invalidateNamespace(namespace: String)
SerializedResponseCache.invalidateAll()

MusicRepository.invalidateNamespace(namespace: String, includeEssential: Boolean = false)
MusicRepository.clearAllEvictableCaches()
MusicRepository.cacheUsage(): CacheUsage
enum class CacheBudget(val megabytes: Int) { Small(32), Medium(64), Default(128), Large(256) }

createPlaybackLoadControl(): DefaultLoadControl
createPlaybackHttpDataSourceFactory(): DefaultHttpDataSource.Factory
deleteLegacyAudioCache(cacheDirectory: File): Boolean
validatedShuffleIndices(canonicalIds: List<String>, requestedIds: List<String>): IntArray?
PlaybackCommands.ConfigureAuthCommand
PlaybackCommands.ClearAuthCommand
PlaybackCommands.SetShuffleOrderCommand

PlaybackSnapshotCodec.Version == 2
PlaybackTransition.awaitCommitted()
data class NowPlayingIdentity(
    val namespace: String,
    val mediaId: String,
    val presentationRevision: Long,
    val title: String,
    val artist: String,
    val audioFormat: String,
    val coverId: String?,
)
```

Room uses `AppDatabase` version 2 and exports JSON schemas. `MIGRATION_1_2` adds non-null
`account_state.schemaRevision INTEGER DEFAULT 1`. The only Media3 custom commands are
`ConfigureAuth`, `ClearAuth`, and `SetShuffleOrder`; there is no media `ClearCache` command.
Command failures use `SessionError` codes, not removed `SessionResult.RESULT_ERROR_*` constants.

## 3. Contracts

### NAS and session

- Accepted server schemes are HTTP and HTTPS only. Reject credentials, queries, fragments, empty
  hosts, and other schemes before any request.
- Canonical API base ends in `/music/api/v1/`; an explicit scheme also updates the HTTPS toggle.
- A host or IP without an explicit port uses port `5666`. Explicit ports, including `80`, are
  preserved. `editableInput` displays only the host when the canonical URL uses port `5666` and
  the standard music API path; custom ports remain visible.
- Password enters the repository as `CharArray` and is zero-filled in `finally`. It is never stored.
  once at the API boundary so UI and repository code continue to handle the user's plain input.
- Password login includes the stable installation `deviceId`. Authenticated API and playback HTTP
  use the returned token as the raw `Authorization` value, without a `Bearer` prefix.
- `remember=true` stores only the user token in Android Keystore. `remember=false` keeps it in
  memory.
- API code `120001`/`99999` maps to `Unauthenticated`; `120002` maps to `AccountDisabled`;
  `100005` maps to `NotFound`.
- API clients keep redirects and OkHttp transport retries disabled. Their network interceptor adds
  `Connection: close` only to HTTP/1.1 requests so a FNOS nginx idle timeout cannot leave a stale
  pooled socket for the next logical request. HTTP/2 and Media3 audio streaming are unaffected.
- `SessionRepository.restore()` contains server discovery, `me()`, user mapping, and signed-in state
  publication in one exception boundary. Cancellation is always rethrown. Invalid or disabled
  remembered credentials clear the token and publish signed-out state; other failures retain the
  token for a later restore attempt and never escape to the application main scope.
- User-token invalidation clears auth and returns to login. HLS or roam-session invalidation must
  not clear the user token.

### Response, Room, and artwork cache

- Every cache key and persisted record is namespaced by `serverGuid:userGuid`. The process response
  key is exactly `(namespace, kind, businessKey, page)`; a business key without a namespace is
  invalid.
- `MusicRepository` retains at most 8 MiB of validated serialized UTF-8 response payloads in an
  access-ordered process-lifetime LRU. Retained entries have no TTL. Eviction, explicit clear,
  or process death permits a later NAS request.
- Read order is process memory, join/start the keyed in-flight request, NAS, then memory plus
  best-effort Room persistence. A `NetworkUnavailable` NAS result may fall back to a valid Room
  page/index/lyric payload and seed the process cache. Stateful roam requests never enter this
  cache.
- Concurrent misses for one `ResponseCacheKey` share one repository-owned upstream request. Each
  caller is a waiter: canceling one waiter does not cancel another, but detaching the final waiter
  cancels the upstream job and its OkHttp call. Failures, invalid payloads, and canceled results are
  never retained.
- Namespace and global invalidation increment generations, remove matching retained entries, and
  cancel matching in-flight work. A result captured before invalidation, or with no remaining
  waiter, must fail acceptance and cannot persist to Room or re-enter memory.
- Essential account state and evictable page/lyric/index payloads remain separate. Evict payloads
  at a 24 MiB target and cap physical DB + WAL + SHM at 32 MiB. Namespace clearing checkpoints WAL
  and runs incremental vacuum; cache-only clearing preserves `account_state`.
- Every Room schema change increments the database version, exports its JSON schema, supplies a
  lossless migration, and preserves queue/settings/account namespaces. Destructive migration is
  forbidden.
- Artwork uses a 24 MiB access-ordered encoded-byte memory LRU plus `cacheDir/artwork`. Its key is
  `(namespace, coverId, variant)`, files live below a hashed namespace directory, and same-key cold
  misses use the same waiter-aware single-flight and generation rules as response payloads.
- Validate downloaded and disk-read image bytes before acceptance. Invalid disk entries are
  deleted; invalid, failed, or canceled downloads leave neither a final file nor a negative cache
  entry. A later request remains eligible to retry.
- Write artwork to a temporary file and replace the target only after validation, using atomic move
  where supported. Touch the final file on both memory and disk hits. Disk pruning is global across
  all namespace directories, ordered by last access, and uses the selected device-wide
  32/64/128/256 MiB budget; the default is 128 MiB.
- Artwork initialization purges unattributable legacy flat files and stale temporary files, then
  enforces the global budget off the main thread. Changing account must not multiply the budget.
- `invalidateNamespace(namespace, includeEssential=false)` clears that namespace's response memory,
  in-flight work, artwork memory/files, and evictable Room rows while preserving its account row.
  Logout/auth invalidation may pass `includeEssential=true`. `clearAllEvictableCaches()` invalidates
  all response generations, clears all artwork namespaces, and clears evictable Room rows while
  preserving credentials and essential account records.
- Cache settings and usage describe only global artwork bytes plus Room/index bytes. They never
  report audio bytes.

### Playback, snapshot, and current presentation

- `PlaybackService` constructs `DefaultMediaSourceFactory` directly from an authenticated
  `DefaultHttpDataSource.Factory`. Persistent audio cache classes (`SimpleCache`,
  `CacheDataSource`, cache-key factories, download stores) are forbidden.
- Forward buffering uses both minimum and maximum `50_000` ms. Back buffering uses `15_000` ms
  with keyframe retention disabled. Cross-protocol redirects remain disabled.
- Service startup idempotently deletes the legacy `cacheDir/media` tree. If that path is a symbolic
  link, unlink only the entry and never traverse its target. No normal playback path recreates it.
- `ConfigureAuth` requires non-blank token and namespace and installs the raw `Authorization`
  header; `ClearAuth` removes request properties. Neither command changes repository caches.
- `SetShuffleOrder` requires a non-negative `SnapshotRevision` and a `MediaIds` list that is the
  same size and exact set as the current Media3 queue, with no blank or duplicate IDs on either
  side. Invalid input returns `SessionError.ERROR_BAD_VALUE` without mutating shuffle order.
  Success applies the requested order and echoes the exact revision and ID list in result extras.
- The controller activates shuffle only when the success acknowledgement still matches the pending
  generation, revision, canonical queue, and order. A stale, malformed, rejected, or failed
  acknowledgement falls back to the declared non-shuffle mode and cannot publish a shuffle
  snapshot.
- Playback persistence uses strict version-2 JSON. It stores generation, monotonically increasing
  revision, up to 250 unique media items, current index/position, exact queue source and page
  segments, queue kind, play mode, complete shuffle order, roam cursor/window, at most one frozen
  normal queue, and play/pause intent. Unknown versions/enums, negative fields, duplicate IDs,
  inconsistent segments, incomplete shuffle order, invalid roam cursor, or deeper frozen nesting
  reject the whole snapshot rather than partially restoring it.
- One FIFO, revision-aware writer owns all snapshot writes. Structural transitions expose
  `PlaybackTransition.awaitCommitted()`; navigation, normal-queue replacement, roam exit, and
  logout/auth invalidation wait at their durability boundaries. A late position checkpoint is
  skipped and cannot overwrite a newer queue, mode, roam transition, or clear. A decoded legacy
  snapshot is normalized and immediately rewritten as version 2.
- Playback controller and `NowPlayingPresenter` are application-scoped and start exactly once from
  `Application.onCreate`; Activity recreation or returning to desktop must not create a second
  runtime or discard the active queue. A signed-in namespace binds preferences, artwork budget,
  playback credentials, and restore. Invalid auth durably clears playback before invalidating the
  departing namespace.
- Current-track fields are captured from one `currentMediaItem.mediaMetadata` instance. A real item
  transition, or a material change to its ID/title/artist/format/cover, increments
  `presentationRevision`. The complete presentation identity is
  `(namespace, mediaId, presentationRevision)`; `mediaId` alone is not sufficient.
- A new presentation identity immediately publishes resource `Loading` states and cancels the old
  presentation/retry jobs. Metadata, artwork, and lyrics may complete independently, but only the
  current identity token and player style may update UI state. Late A -> B -> A completions from an
  older revision are rejected. Missing `coverId` is enriched from matching full-track metadata
  without delaying audio playback.
- OkHttp calls use cancellable async execution: coroutine cancellation invokes `Call.cancel()` and
  `CancellationException` is rethrown through API, retry, and cache boundaries. Cancellation never
  becomes a cached value or a retryable UI failure. Canceling a stale presenter job publishes
  nothing; an independently canceled request whose identity is still current settles to the
  terminal metadata fallback or resource `Absent` state without a retry action.
- Current metadata, artwork, and lyrics retry only retryable idempotent request failures, after
  `250` ms and `750` ms, for at most three total attempts. I/O, HTTP `408`, `429`, and `5xx` are
  retryable. HTTP `401`, `404`, redirects, other `4xx`, invalid/empty content, envelope errors, and
  cancellation are terminal and receive one attempt.
- Current artwork/lyrics `404` or successful empty content maps to `Absent`. Current metadata
  `404`/empty keeps the captured MediaItem title, artist, format, and cover as `Ready` fallback.
  Exhausted transient failures expose `RetryableFailure`; manual retry targets only failed assets
  for the unchanged current presentation identity.

### CI

- Media3 unstable APIs require `androidx.annotation.OptIn(UnstableApi::class)` at the implementation
  boundary so lint accepts usage without making callers opt in.
- CI uses JDK 21 and SDK 36, runs all app/library unit tests and lint variants, builds sideload/store
  debug and unsigned release APKs, compiles both app Android-test APKs, and builds both benchmark
  variants. Application and verification artifacts are retained for 14 days.
- `baselineprofile` resolves the app's `distribution` dimension to `sideload`.

## 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| API HTTP 401 or envelope code 120001/99999 | `AppException(Unauthenticated)`, terminal, one attempt |
| Envelope code 120002 | `AppException(AccountDisabled)`, terminal, token invalidation |
| Remembered-token `me()` returns unauthenticated/disabled | Clear token and publish `SignedOut(error)`; do not crash |
| Remembered-token restore has a transient failure | Retain token and publish `SignedOut(error)` for a later attempt |
| HTTP/1.1 API request after an idle interval | Send `Connection: close`; use one fresh connection and no hidden retry |
| HTTP/envelope 404/100005 | `AppException(NotFound)`, terminal; current artwork/lyrics `Absent`, metadata fallback |
| I/O, HTTP 408/429/5xx | `NetworkUnavailable`, retryable; current resources make at most 3 total attempts |
| Redirect or other non-success HTTP status | `NetworkUnavailable`, terminal, one attempt |
| Envelope code 0 with missing required data | `Empty`, terminal; nullable/current resource becomes `Absent` where allowed |
| Invalid/corrupt JSON or invalid image bytes | `Unknown("invalid_json")` or `Absent`; never cache, never automatically retry |
| Caller cancellation | Cancel its waiter; final waiter cancels upstream `Call`; rethrow `CancellationException` |
| Namespace/global clear during a miss | Increment generation, cancel matching flight, reject every late write |
| Retained response cache hit | Return serialized payload with no NAS call and update process LRU order |
| Response cache exceeds 8 MiB | Evict least-recently-used UTF-8 payloads until within capacity |
| Artwork memory/disk hit | Return bytes with no cover HTTP call and touch the disk entry |
| Artwork disk budget exceeded | Evict globally least-recently-used files across all namespaces |
| Bare host or IP | Add port `5666` and `/music/api/v1/` |
| Explicit port, including `80` | Preserve that port |
| Password login request | Send SHA-256 lowercase hex, never the plain password |
| ConfigureAuth has blank token/namespace | `SessionError.ERROR_BAD_VALUE` |
| SetShuffleOrder has negative revision or non-exact IDs | `SessionError.ERROR_BAD_VALUE`; do not apply shuffle |
| Valid SetShuffleOrder | Success; echo exact `SnapshotRevision` and `MediaIds` acknowledgement |
| Shuffle acknowledgement is stale or no longer matches the queue | Controller ignores it and applies the declared fallback mode |
| Unknown MediaSession command | `SessionError.ERROR_NOT_SUPPORTED` |
| Playback snapshot violates any version-2 invariant | Reject the entire snapshot; do not partially restore |
| Legacy `cacheDir/media` exists at service startup | Delete safely; continue with direct HTTP and do not recreate it |
| DB payload or physical budget exceeded | LRU batch eviction, checkpoint, incremental vacuum |
| CI produces no APK | Artifact upload fails the job |

## 5. Good / Base / Bad Cases

- Good: two concurrent reads of one namespaced album page make one NAS request; canceling one caller
  leaves the other caller and shared result intact.
- Good: clearing namespace A during an artwork download cancels A, prevents its late file write, and
  leaves namespace B plus its files untouched.
- Good: a 128 MiB artwork setting caps the total under `cacheDir/artwork`, not 128 MiB per account.
- Good: `SetShuffleOrder([c, a, b], revision=12)` against canonical `[a, b, c]` succeeds and echoes
  exactly `[c, a, b]` plus revision `12` before the controller activates shuffle.
- Good: a switch A(revision 5) -> B(6) -> A(7) accepts only revision 7 metadata/artwork/lyrics even
  when revision 5 completes last.
- Good: `10.0.0.115` normalizes to `http://10.0.0.115:5666/music/api/v1/` and is shown again as
  `10.0.0.115` on the login screen.
- Good: two HTTP/1.1 API reads each carry `Connection: close`, use distinct connections, and make
  exactly two server requests while `retryOnConnectionFailure` remains disabled.
- Good: an expired remembered token returns to login, removes that token, and leaves startup alive;
  a `500` during restore also returns to login but retains the token for a later attempt.
- Good: the login request contains the exact lowercase SHA-256 password hash plus the stable
  `deviceId`, and authenticated requests carry the raw token.
- Base: a network failure returns valid Room page/index/lyric data where permitted, persists it in
  the process LRU, and avoids another request while retained.
- Base: a current artwork request receiving `503`, `429`, then valid bytes succeeds on its third and
  final attempt; a `404` makes exactly one request and becomes `Absent`.
- Bad: a response/artwork key without namespace, an unbounded map, a permanent negative cache entry,
  or allowing a cleared flight to write back.
- Bad: `SimpleCache`, `CacheDataSource`, a Media3 `ClearCache` command, or any normal playback write
  below `cacheDir/media`.
- Bad: accepting a shuffle list that merely has the same length, or activating shuffle before an
  exact service acknowledgement.
- Bad: using `mediaId` alone as current-presentation identity, combining a new MediaItem ID with old
  aggregate metadata, or converting cancellation into `NetworkUnavailable`.
  token in Room, SharedPreferences, logs, tests, or workflow files.
- Bad: `fallbackToDestructiveMigration`, an unexported schema version, or a cache row without a
  namespace.
- Bad: running a root `test` selector that accidentally schedules connected benchmark tests; CI
  names every non-device task explicitly.

## 6. Tests Required

- `ServerUrlNormalizerTest`: default port `5666`, explicit/custom ports, editable host display,
  schemes, paths, embedded credentials, query/fragment, and invalid hosts.
  invalid JSON, redirects, retryable status classification, HTTP/1.1 close with distinct connection
  indices, no transport retry, exact request count, maximum three current-resource attempts, `404`
  terminal behavior, and cancellation calling `Call.cancel()`.
- `SessionRepositoryTest`: remembered-token `401` and `120002` are contained and clear credentials;
  transient restore failure is contained but retains credentials; cancellation is rethrown without
  publishing an error state.
- `SerializedResponseCacheTest`: 8 MiB-equivalent byte LRU ordering, namespace isolation, same-key
  single-flight, one-of-many waiter cancellation, final-waiter upstream cancellation, failed fetch
  retryability, namespace/global generations, and late-persist rejection.
- `ArtworkCacheTest`/`ArtworkValidationTest`: namespaced memory/disk hits, same-key single-flight,
  waiter cancellation, invalid-byte rejection, atomic temporary replacement, hit touching, global
  cross-namespace budget, legacy/temp cleanup, and clear-versus-late-write races.
- `AppDatabaseMigrationTest`/`LocalStoreTest`: version-1 to version-2 preservation,
  `schemaRevision=1`, account isolation, LRU eviction, physical-budget reclaim, version-2 playback
  payload storage, and essential-state-preserving clear.
- `CacheBudgetTest`: exactly 32/64/128/256 MiB artwork budgets, default 128 MiB, and no audio budget.
- `PlaybackServiceConfigurationTest`: direct `DefaultHttpDataSource`, 50,000 ms min/max forward
  buffer, 15,000 ms back buffer, no retained back-buffer keyframes, safe/idempotent legacy media
  deletion, exact shuffle permutation validation, and explicit unstable-API opt-in/lint.
- `PlaybackSnapshotCodecTest`/`PlaybackSnapshotWriterTest`: strict version-2 round trip and rejection
  matrix, exact queue page segments, 250-item/unique-ID bounds, roam/frozen constraints, legacy
  rewrite, FIFO structural acknowledgements, stale-checkpoint skipping, clear ordering, and
  cancellation propagation.
- `PlaybackProjectionTest`/`PlaybackTransitionGuardsTest`: one-MediaItem field capture, monotonic
  presentation revision, exact shuffle acknowledgement guards, and committed structural
  transitions.
- `AppContainerRuntimeTest`/`NowPlayingPresenterTest`: one application runtime, durable invalid-auth
  clear, `(namespace, mediaId, presentationRevision)` identity, A -> B -> A stale rejection,
  missing-cover enrichment, selective manual retry, terminal `404`/empty fallback, and cancellation
  without a retryable UI error.
- CI-equivalent local gate: all named workflow Gradle tasks must succeed and output four app APKs,
  two app Android-test APKs, and benchmark APKs.

## 7. Wrong vs Correct

```kotlin
// Wrong: a stale HTTP/1.1 socket may be reused, or a hidden transport retry can exceed request limits.
OkHttpClient.Builder().retryOnConnectionFailure(true).build()

// Correct: keep logical retry ownership explicit and retire only HTTP/1.1 API connections.
OkHttpClient.Builder()
    .retryOnConnectionFailure(false)
    .addNetworkInterceptor { chain ->
        val request = if (chain.connection()?.protocol() == Protocol.HTTP_1_1) {
            chain.request().newBuilder().header("Connection", "close").build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }
    .build()
```

```kotlin
// Wrong: me() throws outside the runCatching boundary and crashes startup.
runCatching { connect(savedServer, useHttps = false) }.onSuccess { it.api.me() }

// Correct: contain the whole restore transaction while preserving cancellation.
try {
    val connected = connect(savedServer, useHttps = false)
    val user = connected.api.me().toDomain()
    api = connected.api
    _state.value = SessionState.SignedIn(connected.server, user)
} catch (cause: CancellationException) {
    throw cause
} catch (cause: Exception) {
    api = null
    val error = (cause as? AppException)?.error ?: AppError.Unknown()
    if (error == AppError.Unauthenticated || error == AppError.AccountDisabled) clearToken()
    _state.value = signedOut(error)
}
```

```kotlin
// Wrong: persistent audio disk cache and a command whose ownership no longer exists.
DefaultMediaSourceFactory(CacheDataSource.Factory().setCache(SimpleCache(cacheDir, evictor)))
PlaybackCommands.ClearCacheCommand

// Correct: authenticated direct HTTP plus bounded Media3 memory buffering.
DefaultMediaSourceFactory(createPlaybackHttpDataSourceFactory())
    // ExoPlayer also receives createPlaybackLoadControl(): 50 s forward, 15 s back.
```

```kotlin
// Wrong: cross-account key and independent duplicate requests.
cache.getOrPut("album:$guid") { api.album(guid) }

// Correct: namespace participates in the waiter-aware, generation-checked single-flight key.
responses.getOrFetch(ResponseCacheKey(namespace, "index", "album:$guid")) {
    api.album(guid).let(ApiDecoder.json::encodeToString)
}
```

```kotlin
// Wrong: a blocking call outlives coroutine cancellation and may populate a cleared cache.
client.newCall(request).execute().use(::decode)

// Correct: cancellation reaches OkHttp and remains CancellationException.
suspendCancellableCoroutine { continuation ->
    val call = client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(callback)
}
```

```kotlin
// Wrong: length-only validation and optimistic shuffle activation.
if (requestedIds.size == player.mediaItemCount) player.shuffleModeEnabled = true

// Correct: validate an exact non-blank permutation, then activate only after the echoed ack matches.
val indices = validatedShuffleIndices(canonicalIds, requestedIds)
    ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
player.setShuffleOrder(DefaultShuffleOrder(indices, revision))
```

```kotlin
// Wrong: old Media3 constant family and incomplete presentation identity.
SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
PresentationKey(mediaId)

// Correct.
SessionResult(SessionError.ERROR_BAD_VALUE)
PresentationKey(namespace, mediaId, presentationRevision)
```

```kotlin
// Wrong: erases user state when a schema changes.
Room.databaseBuilder(context, AppDatabase::class.java, NAME)
    .fallbackToDestructiveMigration()

// Correct: versioned, exported, lossless migration.
Room.databaseBuilder(context, AppDatabase::class.java, NAME)
    .addMigrations(AppDatabase.MIGRATION_1_2)
```
