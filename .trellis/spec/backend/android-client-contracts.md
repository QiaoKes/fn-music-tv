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
    accessCode: CharArray = charArrayOf(),
)
ConnectionResolver.resolve(input: String, useHttps: Boolean): ConnectionTarget
ConnectionResolver.verifyAccessCode(
    target: ConnectionTarget,
    accessCode: String,
): ConnectionAccess
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
MusicRepository.favoriteTracks(page: Int): Page<Track>
MusicRepository.toggleFavorite(trackGuid: String, fallbackFavorite: Boolean): Result<Boolean>
MusicRepository.favoriteState: StateFlow<FavoriteLibraryState>
enum class CacheBudget(val megabytes: Int) { Small(32), Medium(64), Default(128), Large(256) }

data class FavoriteTrackRequest(val trackGUID: String)
TrimMusicApi.favoriteTracks(page: Int, size: Int = 50): PageListDto<TrackDto>
TrimMusicApi.createFavorite(trackGuid: String)
TrimMusicApi.deleteFavorite(trackGuid: String)

ArtworkBitmapCache.peek(coverId: String, variant: CoverVariant): Bitmap?
ArtworkBitmapCache.get(coverId: String, variant: CoverVariant): Bitmap?
ArtworkBitmapCache.prefetch(coverId: String, variant: CoverVariant): Job?
ArtworkBitmapCache.clear()

createPlaybackLoadControl(): DefaultLoadControl
createPlaybackHttpDataSourceFactory(): DefaultHttpDataSource.Factory
deleteLegacyAudioCache(cacheDirectory: File): Boolean
validatedShuffleIndices(canonicalIds: List<String>, requestedIds: List<String>): IntArray?
PlaybackCommands.ConfigureAuthCommand
PlaybackCommands.ClearAuthCommand
PlaybackCommands.SetShuffleOrderCommand

PlaybackSnapshotCodec.Version == 2
PlaybackTransition.awaitCommitted()
PlaybackController.state: StateFlow<PlaybackUiState>
PlaybackController.progress: StateFlow<PlaybackProgressState>
PlaybackController.removeQueueItem(queueIndex: Int): PlaybackTransition?
interface PlaybackSessionStore
interface PlaybackContentSource
data class PlaybackFailure(val code: Int, val displayName: String)
version.properties: VERSION_CODE=<monotonic Int>, VERSION_NAME=<display SemVer>
app/src/main/baseline-prof.txt
release signing alias: fn-music-tv
release signing default files: ~/.config/fn-music-tv/release.jks, release.password
data class NowPlayingIdentity(
    val namespace: String,
    val mediaId: String,
    val presentationRevision: Long,
    val title: String,
    val artist: String,
    val audioFormat: String,
    val coverId: String?,
)
data class PlaybackCredentials(
    val apiBase: String,
    val rawAuthorization: String,
    val cacheNamespace: String,
    val accessCodeHeader: String? = null,
    val relayMode: Boolean = false,
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
- Arbitrary user-supplied HTTP NAS origins require cleartext traffic in the application network
  security config. The login UI must keep the visible unencrypted-connection warning whenever HTTP
  is selected; never silently downgrade an HTTPS input.
- Canonical API base ends in `/music/api/v1/`; an explicit scheme also updates the HTTPS toggle.
- A bare HTTP host or IP without an explicit port uses legacy port `5666`; explicit `http://` uses
  standard port `80`, and HTTPS without a port uses `443`. Explicit ports are always preserved.
  `editableInput` displays only the host when the canonical URL uses its implicit port and
  the standard music API path; custom ports remain visible.
- A six-or-more-character alphanumeric/underscore/hyphen identifier with no URL punctuation is an
  FNID. Resolve it through the signed FN Connect lookup, then concurrently probe internal IPv4,
  public IPv6, public IPv4, and HTTPS relay candidates in that priority order. IP candidates try
  HTTP before HTTPS; relay requests carry `Cookie: mode=relay`.
- Probe `/access_code_verify` at the origin before the music API. Encode a supplied security code
  once as base64 UTF-8 and attach `x-access-code` plus `x-access-source: app` to login,
  authenticated API, artwork, and Media3 audio requests. Security codes are zero-filled at the UI
  boundary and, only with remember-login, encrypted by Android Keystore storage.
- Password enters the repository as `CharArray`, is hashed once at the API boundary, and is
  zero-filled in `finally`. It is never stored.
- Password login includes the stable installation `deviceId`. Authenticated API and playback HTTP
  use the returned token as the raw `Authorization` value, without a `Bearer` prefix.
- `remember=true` stores the user token and any supplied security code with Android Keystore-backed
  encryption. `remember=false` keeps both in memory only.
- `SessionRepository` construction performs no token or access-code decryption. `restore()` loads
  remembered credentials once on `Dispatchers.IO` while `SessionState.Loading` remains published;
  login and invalidation then own the in-memory values. Application/container construction must
  never call Android Keystore reads on the main thread.
- API code `120001`/`99999` maps to `Unauthenticated`; `120002` maps to `AccountDisabled`;
  `100005` maps to `NotFound`.
- API clients keep automatic redirects and OkHttp transport retries disabled. Relay requests may
  manually follow at most five redirects while preserving relay, access-code, and auth headers.
  Their network interceptor adds
  `Connection: close` only to HTTP/1.1 requests so a FNOS nginx idle timeout cannot leave a stale
  pooled socket for the next logical request. HTTP/2 and Media3 audio streaming are unaffected.
- `SessionRepository.restore()` contains server discovery, `me()`, user mapping, and signed-in state
  publication in one exception boundary. Cancellation is always rethrown. Invalid or disabled
  remembered credentials clear the token and publish signed-out state; other failures retain the
  token for a later restore attempt and never escape to the application main scope.
- User-token invalidation clears auth and returns to login. HLS or roam-session invalidation must
  not clear the user token.

### Server-backed favorites

- Favorites use `POST favorite-track/create`, `POST favorite-track/delete`, and
  `GET favorite-track/list?page=<page>&size=50&sort=favoriteAt,desc`. Both POST bodies are exactly
  `{ "trackGUID": "<guid>" }`; a success envelope may contain `data: null` and must be decoded as
  a Unit response rather than requiring a JSON payload.
- `TrackDto.isFavorite` maps into `Track.isFavorite`. Metadata, ordinary track pages, roam results,
  and favorite pages seed one account-scoped `FavoriteLibraryState` keyed by track GUID. A namespace
  change or sign-out clears it before the next account can observe any value.
- The NAS is the source of truth. Favorite list pages bypass the persistent response cache and use
  server paging, so another device's changes are visible on reload. No favorite is persisted in
  Room or preferences as authoritative state.
- Toggle publishes one optimistic desired value under a serialized mutation, calls create/delete,
  increments `revision` only after success, and removes the pending marker. Failure or cancellation
  restores the last server-confirmed value; cancellation is rethrown. Every mutation captures its
  originating namespace, and a completion arriving after account change is ignored.
- `QueueSource.Favorites(sort = "favoriteAt,desc")` participates in queue paging and version-2
  snapshot round trips exactly like the other normal queue sources.

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
- `LocalStore` performs one exact payload/physical audit when its process-local estimate is absent.
  Subsequent writes add the encoded payload size conservatively and trigger another exact audit only
  when the 24 MiB payload estimate, 32 MiB physical estimate, 4 MiB unverified growth, or 32-write
  interval is reached. `wal_checkpoint(TRUNCATE)` plus incremental vacuum runs only after actual
  eviction or explicit clear, never after an ordinary under-budget save.
- Every Room schema change increments the database version, exports its JSON schema, supplies a
  lossless migration, and preserves queue/settings/account namespaces. Destructive migration is
  forbidden.
- Artwork uses a 24 MiB access-ordered encoded-byte memory LRU plus `cacheDir/artwork`. Its key is
  `(namespace, coverId, variant)`, files live below a hashed namespace directory, and same-key cold
  misses use the same waiter-aware single-flight and generation rules as response payloads.
- `AppContainer` additionally owns one 40 MiB access-ordered decoded-`Bitmap` LRU keyed by the exact
  `(coverId, CoverVariant)`. Compact, Grid, Player, and Poster are independent entries. Same-key
  consumers join one application-scoped decode, at most three loads run concurrently, and a
  composable cancellation does not cancel work still useful to another consumer.
- Decoded cache clearing increments a generation, cancels known in-flight work, and rejects every
  result captured before the clear. Clear it whenever the authenticated namespace changes or signs
  out and alongside explicit artwork/all-cache clearing; this is what makes its non-namespaced key
  account-safe. Decoded bytes are process-only and are excluded from disk-cache usage reporting.
- Validate downloaded and disk-read image bytes before acceptance. Invalid disk entries are
  deleted; invalid, failed, or canceled downloads leave neither a final file nor a negative cache
  entry. A later request remains eligible to retry.
- Write artwork to a temporary file and replace the target only after validation, using atomic move
  where supported. Touch the final file on both memory and disk hits. Disk pruning is global across
  all namespace directories, ordered by last access, and uses the selected device-wide
  32/64/128/256 MiB budget; the default is 128 MiB.
- Artwork initialization purges unattributable legacy flat files and stale temporary files, then
  enforces the global budget off the main thread. Changing account must not multiply the budget.
- Artwork initialization or a missing ledger performs one full-tree calibration. Thereafter every
  successful replace/delete adjusts a `diskMutex`-owned byte ledger; an under-budget write must not
  walk or sort the tree. Full LRU scan/sort remains required when the ledger crosses the selected
  budget, when the user applies a lower budget, or when error recovery invalidates the ledger.
- A network or validated Room fallback DTO decoded during one repository call is reused for
  persistence metadata and the returned domain projection. Process-cache hits from another call
  still decode their serialized payload once; serialized cache and Room formats do not change.
- `invalidateNamespace(namespace, includeEssential=false)` clears that namespace's response memory,
  in-flight work, artwork memory/files, and evictable Room rows while preserving its account row.
  Logout/auth invalidation may pass `includeEssential=true`. `clearAllEvictableCaches()` invalidates
  all response generations, clears all artwork namespaces, and clears evictable Room rows while
  preserving credentials and essential account records.
- Cache settings and usage describe only global artwork bytes plus Room/index bytes. They never
  report audio bytes.

### Playback, snapshot, and current presentation

- `core:playback` owns the narrow `PlaybackSessionStore` and `PlaybackContentSource` capability
  contracts and must not depend on `core:data`. The app composition root adapts `LocalStore` and
  `MusicRepository`; adapters are stateless parameter mappings and cannot add cache semantics.
- Media3 failures are projected as `PlaybackFailure`. Session verification is selected by the
  numeric Media3 error code (`ERROR_CODE_IO_BAD_HTTP_STATUS`), never by parsing `errorCodeName`.
- `AuthenticatedAppCoordinator` is the sole owner of signed-in session orchestration: namespace
  binding, invalid-auth playback/cache cleanup, account switch, cache clear, and explicit exit.
  Account switch preserves playback clear -> artwork clear -> local namespace clear -> logout;
  the existing best-effort playback-clear boundary remains non-blocking for later cleanup.
- `PlaybackService` constructs `DefaultMediaSourceFactory` directly from an authenticated
  `DefaultHttpDataSource.Factory`. Persistent audio cache classes (`SimpleCache`,
  `CacheDataSource`, cache-key factories, download stores) are forbidden.
- Forward buffering uses both minimum and maximum `50_000` ms. Back buffering uses `15_000` ms
  with keyframe retention disabled. Cross-protocol redirects remain disabled.
- Service startup idempotently deletes the legacy `cacheDir/media` tree. If that path is a symbolic
  link, unlink only the entry and never traverse its target. No normal playback path recreates it.
- `ConfigureAuth` requires non-blank token and namespace and installs the raw `Authorization`
  header plus optional access-code headers and relay token/mode cookies; `ClearAuth` removes all
  request properties. Neither command changes repository caches.
- `SetShuffleOrder` requires a non-negative `SnapshotRevision` and a `MediaIds` list that is the
  same size and exact set as the current Media3 queue, with no blank or duplicate IDs on either
  side. Invalid input returns `SessionError.ERROR_BAD_VALUE` without mutating shuffle order.
  Success applies the requested order and echoes the exact revision and ID list in result extras.
- The controller activates shuffle only when the success acknowledgement still matches the pending
  generation, revision, canonical queue, and order. A stale, malformed, rejected, or failed
  acknowledgement falls back to the declared non-shuffle mode and cannot publish a shuffle
  snapshot.
- `removeQueueItem(queueIndex)` accepts only a valid occurrence index in a normal queue. Before the
  Media3 mutation it starts a structural transition and clears `queueSource` plus `queueWindow`, so
  later server paging cannot reinsert the locally removed occurrence. Removing current selects the
  following item, removing the current tail falls back to the previous item, and removing the final
  item stops playback and resets to `ListRepeat`.
- After manual deletion, rebuild the bounded queue projection and persist a structural snapshot. If
  shuffle was active, discard stale pending activation, retain the remaining acknowledged order,
  complete a new order for remaining canonical IDs, and require a fresh exact acknowledgement.
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
  snapshot is normalized and immediately rewritten as version 2. Capture Media3 state on its
  owning thread, but encode the immutable snapshot on `Dispatchers.Default` inside that same FIFO
  writer; encoding jobs outside the writer may reorder structural revisions and are forbidden.
- The 250 ms ticker updates only `PlaybackController.progress`. `PlaybackController.state` owns
  stable playback metadata, presentation identity, queue/mode, errors, and transport availability,
  and must not emit for a position-only tick. Queue projection is reused for progress, playback
  state, and error-only events; rebuild it only for timeline, media-item-transition, or media-
  metadata events. Player lyrics and progress controls observe the progress flow in their own
  composition scopes so Home/My/root and the queue overlay are not invalidated at 4 Hz.
- Playback controller and `NowPlayingPresenter` are application-scoped and start exactly once from
  `Application.onCreate`; Activity recreation or returning to desktop must not create a second
  runtime or discard the active queue. A signed-in namespace binds preferences, artwork budget,
  playback credentials, and restore. Invalid auth durably clears playback before invalidating the
  departing namespace.
- Explicit app exit is distinct from auth/session clearing: cancel in-flight queue/roam work, pause
  playback, durably persist the current queue and position with pause intent, stop and release the
  player/controller, then stop the playback service. Login, preferences, and repository caches are
  retained for the next launch.
- Current-track fields are captured from one `currentMediaItem.mediaMetadata` instance. A real item
  transition, or a material change to its ID/title/artist/format/cover, increments
  `presentationRevision`. The complete presentation identity is
  `(namespace, mediaId, presentationRevision)`; `mediaId` alone is not sufficient.
- `MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED` does not unconditionally force another
  presentation revision: queue installation and metadata enrichment already project their material
  field change, and the following Media3 callback must not cancel and restart the same artwork/lyric
  requests. Seek/auto/repeat transitions still force a revision; playlist changes rely on the
  captured presentation-key comparison and only publish when those fields actually differ.
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

- The application applies the Baseline Profile consumer plugin and connects the
  `:baselineprofile` generator through its `baselineProfile` dependency. Release builds keep
  automatic device generation disabled, consume the checked-in app profile, and merge rules for
  the application's own startup, session, authenticated UI, and playback classes. The generator's
  login/home/collection/player journeys remain the source for device-based refreshes.
- `version.properties` is the single source of application versioning. The first formal package is
  `VERSION_NAME=0.1.0` with monotonic `VERSION_CODE=2`, which is higher than the previous development
  package. Every later distributable must increase `VERSION_CODE`; display version changes alone do
  not make an Android upgrade.
- Local Release APK packaging fails closed unless the fixed `fn-music-tv` signing identity is
  available from the default protected directory, Gradle properties, or
  `FN_MUSIC_RELEASE_PASSWORD`. Keystores and passwords never enter Git. CI may opt into unsigned
  Release compilation only with explicit `-PallowUnsignedRelease=true`; those APKs are verification
  artifacts and must not be distributed as formal updates.
- Media3 unstable APIs require `androidx.annotation.OptIn(UnstableApi::class)` at the implementation
  boundary so lint accepts usage without making callers opt in.
- CI has one packaging responsibility: on `main`, restore the fixed signing identity from protected
  repository secrets, build the signed sideload Release APK, and verify package, version, signer,
  minimum/target SDK, and all four supported ABIs. Upload exactly one universal APK plus its SHA-256
  checksum as a 14-day workflow artifact. Unit tests, lint, store/test APKs, and benchmark variants
  remain explicit local gates rather than CI packaging work.
- If `v<VERSION_NAME>` has not been published, the same packaging job creates the immutable tag and
  GitHub Release after verification. The GitHub Release title is the exact `v<VERSION_NAME>` tag;
  do not prefix it with the product name. An already-published version is rebuilt as a workflow
  artifact without overwriting its release. Lint ignores `OldTargetApi` because the project target
  is pinned while that check varies with the newest platform installed in the environment.
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
| Exact decoded bitmap hit | Return it synchronously on the first composition; do not show a placeholder frame |
| Only another artwork variant is decoded | Treat as a miss; keep the stable placeholder and load the exact variant |
| Decoded cache clear races an older load | Cancel the flight and reject its result by generation; memory stays empty |
| Artwork disk budget exceeded | Evict globally least-recently-used files across all namespaces |
| Artwork write remains within the tracked budget | Adjust the byte ledger; do not walk or sort the cache tree |
| Artwork process starts or ledger is invalidated | Calibrate from disk once, purge legacy/temp files, then enforce the budget |
| Ordinary Room cache write stays below all audit thresholds | Update conservative estimates; do not run SUM/checkpoint/vacuum |
| Room estimate crosses a target or audit interval | Run exact payload/physical queries; evict if needed and reclaim only after removal |
| SessionRepository is constructed | Publish Loading with zero secure-store reads |
| Session restore needs remembered credentials | Read token and access code once on `Dispatchers.IO` |
| Bare host or IP | Add port `5666` and `/music/api/v1/` |
| HTTPS input without a port | Use port `443`; never append `5666` |
| Explicit `http://` without a port | Use port `80`; a bare HTTP host retains legacy `5666` |
| Explicit port, including `80` | Preserve that port |
| FNID lookup succeeds and one or more candidates respond | Select the first reachable candidate in declared priority order |
| FNID lookup/probes have no reachable candidate | `AppException(FnIdUnavailable)` |
| `/access_code_verify` rejects a blank code | `AppException(AccessCodeRequired)` |
| `/access_code_verify` rejects a supplied code | `AppException(InvalidAccessCode)` |
| Password login request | Send SHA-256 lowercase hex, never the plain password |
| Favorite list request | GET page/size with `favoriteAt,desc`; do not serve an authoritative local cache |
| Favorite create/delete succeeds with `data: null` | Accept Unit success and increment favorite revision once |
| Favorite create/delete fails | Restore the prior server-confirmed state and clear pending |
| Authenticated namespace changes | Clear all in-memory favorite statuses, pending entries, revisions, and errors |
| ConfigureAuth has blank token/namespace | `SessionError.ERROR_BAD_VALUE` |
| SetShuffleOrder has negative revision or non-exact IDs | `SessionError.ERROR_BAD_VALUE`; do not apply shuffle |
| Valid SetShuffleOrder | Success; echo exact `SnapshotRevision` and `MediaIds` acknowledgement |
| Shuffle acknowledgement is stale or no longer matches the queue | Controller ignores it and applies the declared fallback mode |
| Invalid or roam queue deletion index | Return `null`; do not mutate Media3 or persist a transition |
| Current queue item is deleted | Select the next occurrence, or previous when deleting the tail |
| Final queue item is deleted | Stop, clear the queue, reset ListRepeat, and persist the empty snapshot |
| Unknown MediaSession command | `SessionError.ERROR_NOT_SUPPORTED` |
| Playback snapshot violates any version-2 invariant | Reject the entire snapshot; do not partially restore |
| Position-only ticker update | Publish `PlaybackProgressState`; do not emit `PlaybackUiState` or rebuild queue items |
| Timeline, media-item, or media-metadata event | Rebuild the bounded queue projection and publish stable playback state |
| Captured snapshot is ready to persist | Enqueue it first, then encode on the writer's background dispatcher in FIFO order |
| Media3 failure is `ERROR_CODE_IO_BAD_HTTP_STATUS` | Publish typed failure and ask the coordinator to verify the session |
| Other Media3 failure | Publish typed failure for display; do not verify the session |
| Account switch is activated | Clear playback, artwork, local namespace, then logout through the coordinator |
| Legacy `cacheDir/media` exists at service startup | Delete safely; continue with direct HTTP and do not recreate it |
| DB payload or physical budget exceeded | LRU batch eviction, checkpoint, incremental vacuum |
| CI produces no APK | Artifact upload fails the job |
| Release merged art profile contains no `Lcom/fnmusic/tv` rule | Profile wiring is incomplete; fail performance acceptance |
| Local Release packaging has no fixed key/password | Fail before packaging; never silently emit a formal unsigned APK |
| CI explicitly sets `allowUnsignedRelease=true` | Permit unsigned Release compile/package verification only |
| Later formal version does not increase `VERSION_CODE` | Android may reject the update; release is invalid |

## 5. Good / Base / Bad Cases

- Good: two concurrent reads of one namespaced album page make one NAS request; canceling one caller
  leaves the other caller and shared result intact.
- Good: the initial `0.1.0` package uses version code `2`; the `回声台` rebrand ships as `0.1.1`
  with version code `3`, reuses the fixed release certificate, and installs over the same
  `com.fnmusic.tv` package.
- Good: clearing namespace A during an artwork download cancels A, prevents its late file write, and
  leaves namespace B plus its files untouched.
- Good: two Grid consumers for one cover join one decode; a Compact hit neither satisfies nor
  visually substitutes for the Grid request.
- Good: a 128 MiB artwork setting caps the total under `cacheDir/artwork`, not 128 MiB per account.
- Good: after startup calibration, 100 small artwork writes below 128 MiB update the ledger without
  100 full directory sorts; crossing the limit performs one global LRU prune.
- Good: small Room payload saves reuse the exact baseline estimate; an actual over-budget audit
  evicts rows, performs one reclaim pass, and preserves account state.
- Good: `SetShuffleOrder([c, a, b], revision=12)` against canonical `[a, b, c]` succeeds and echoes
  exactly `[c, a, b]` plus revision `12` before the controller activates shuffle.
- Good: device A favorites a track, device B reloads `favorite-track/list` and sees it; neither
  device depends on a local-only favorites table.
- Good: manually deleting queue index 4 detaches the server paging source before mutation, then
  persists the explicit remaining queue so restore cannot append the deleted occurrence again.
- Good: four position ticks update only the progress flow; Home/My do not recompose and the existing
  queue list instance is retained until a structural player event occurs.
- Good: revisions 12 and 13 enter the snapshot writer in order, are encoded on its background
  dispatcher, and remain separated by the same structural durability barrier.
- Good: playback compiles against model and Media3 capabilities only; the app adapts data
  repositories and a typed bad-HTTP-status failure triggers coordinator-owned verification.
- Good: a switch A(revision 5) -> B(6) -> A(7) accepts only revision 7 metadata/artwork/lyrics even
  when revision 5 completes last.
- Good: `10.0.0.115` normalizes to `http://10.0.0.115:5666/music/api/v1/` and is shown again as
  `10.0.0.115` on the login screen.
- Good: `https://nas.example.com` and a bare host with HTTPS selected normalize to
  `https://nas.example.com/music/api/v1/`, using port `443`, while an explicit custom port remains.
- Good: FNID relay login, cover loading, and audio playback all carry the same access-code headers
  and `mode=relay`; authenticated requests additionally carry the user token cookie.
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
- Bad: sorting every artwork file after each successful write, running three SUM queries plus vacuum
  after every Room upsert, or decrypting Keystore values in `AppContainer` construction.
- Bad: keying decoded artwork by cover ID alone, retaining decoded entries across an account change,
  or launching independent decode jobs from every composable.
- Bad: `SimpleCache`, `CacheDataSource`, a Media3 `ClearCache` command, or any normal playback write
  below `cacheDir/media`.
- Bad: `core:playback` importing a concrete data repository, UI parsing `errorCodeName`, or a page
  independently coordinating playback, cache, namespace, and logout operations.
- Bad: accepting a shuffle list that merely has the same length, or activating shuffle before an
  exact service acknowledgement.
- Bad: treating `data: null` on favorite create/delete as an empty-response error, persisting a
  local favorite as authority, or retaining favorite state across namespaces.
- Bad: deleting only by media ID, retaining the source paging cursor after manual deletion, or
  writing a snapshot before shuffle reconciliation finishes.
- Bad: copying position into the aggregate `PlaybackUiState` every 250 ms, rebuilding 250 queue rows
  for `EVENT_IS_PLAYING_CHANGED`, or launching independent snapshot encoding jobs before FIFO entry.
- Bad: using `mediaId` alone as current-presentation identity, combining a new MediaItem ID with old
  aggregate metadata, or converting cancellation into `NetworkUnavailable`.
- Bad: checking the security code only during login, then omitting it from cover or audio requests;
  this produces a signed-in UI whose media cannot load.
  token in Room, SharedPreferences, logs, tests, or workflow files.
- Bad: `fallbackToDestructiveMigration`, an unexported schema version, or a cache row without a
  namespace.
- Bad: running a root `test` selector that accidentally schedules connected benchmark tests; CI
  names every non-device task explicitly.
- Bad: committing the release keystore/password, rebuilding with a different certificate, or
  changing only `VERSION_NAME` and expecting Android to accept an upgrade.

## 6. Tests Required

- `ServerUrlNormalizerTest`: legacy bare HTTP port `5666`, standard HTTP/HTTPS ports `80`/`443`,
  explicit/custom ports, editable host display, schemes, paths, embedded credentials,
  query/fragment, and invalid hosts.
- `ConnectionResolverTest`: direct HTTPS normalization, FNID signed lookup and candidate priority,
  access-code base64/header construction, relay cookies, and required/invalid access-code errors.
  invalid JSON, redirects, retryable status classification, HTTP/1.1 close with distinct connection
  indices, no transport retry, exact request count, maximum three current-resource attempts, `404`
  terminal behavior, and cancellation calling `Call.cancel()`.
- `SessionRepositoryTest`: remembered-token `401` and `120002` are contained and clear credentials;
  transient restore failure is contained but retains credentials; cancellation is rethrown without
  publishing an error state; construction performs zero secure reads and restore reads off main.
- `SerializedResponseCacheTest`: 8 MiB-equivalent byte LRU ordering, namespace isolation, same-key
  single-flight, one-of-many waiter cancellation, final-waiter upstream cancellation, failed fetch
  retryability, namespace/global generations, and late-persist rejection.
- `ArtworkCacheTest`/`ArtworkValidationTest`: namespaced memory/disk hits, same-key single-flight,
  waiter cancellation, invalid-byte rejection, atomic temporary replacement, hit touching, global
  cross-namespace budget, startup ledger calibration, no rescan for under-budget writes,
  legacy/temp cleanup, and clear-versus-late-write races.
- `ArtworkBitmapCacheTest`: exact-variant isolation, same-key decode single-flight, exact Grid
  prefetch, and decoded clear-versus-late-write rejection.
- `AppDatabaseMigrationTest`/`LocalStoreTest`: version-1 to version-2 preservation,
  `schemaRevision=1`, account isolation, LRU eviction, physical-budget reclaim, version-2 playback
  payload storage, estimated-audit reuse without reclaim, and essential-state-preserving clear.
- `CacheBudgetTest`: exactly 32/64/128/256 MiB artwork budgets, default 128 MiB, and no audio budget.
- `PlaybackServiceConfigurationTest`: direct `DefaultHttpDataSource`, access-code and relay request
  headers, 50,000 ms min/max forward
  buffer, 15,000 ms back buffer, no retained back-buffer keyframes, safe/idempotent legacy media
  deletion, exact shuffle permutation validation, and explicit unstable-API opt-in/lint.
- `PlaybackSnapshotCodecTest`/`PlaybackSnapshotWriterTest`: strict version-2 round trip and rejection
  matrix, exact queue page segments, 250-item/unique-ID bounds, roam/frozen constraints, legacy
  rewrite, FIFO structural acknowledgements, background encoding dispatcher, stale-checkpoint
  skipping, clear ordering, and cancellation propagation.
- `PlaybackProjectionTest`/`PlaybackTransitionGuardsTest`: one-MediaItem field capture, monotonic
  presentation revision, progress-only queue reuse, structural event classification, exact shuffle
  acknowledgement guards, typed failure classification, and committed structural transitions.
- `TrimMusicApiTest`/`FavoriteLibraryStateTest`: exact favorite paths, sort and request body;
  `data: null` Unit success; account isolation; optimistic state; success revision; failure rollback;
  and stale server observations blocked while a mutation is pending.
- `PlaybackSnapshotCodecTest`/`PlaybackTransitionGuardsTest`: Favorites source round trip; invalid
  deletion rejection; delete-current selection; current-tail fallback; final-item empty state; and
  shuffle mutation invalidating the old acknowledgement.
- `AppContainerRuntimeTest`/`NowPlayingPresenterTest`: one application runtime, durable invalid-auth
  clear, account-switch cleanup order, `(namespace, mediaId, presentationRevision)` identity,
  A -> B -> A stale rejection,
  missing-cover enrichment, selective manual retry, terminal `404`/empty fallback, and cancellation
  without a retryable UI error.
- CI-equivalent local gate: all named workflow Gradle tasks must succeed and output four app APKs,
  two app Android-test APKs, and benchmark APKs.
- Formal APK verification: `aapt dump badging` asserts package `com.fnmusic.tv`, the managed version
  name/code, and `apksigner verify --print-certs` asserts one fixed signer. Install and reinstall the
  same signed artifact with replace enabled before distribution.
- Baseline profile check: both Sideload and Store merged art profiles contain app-owned
  `Lcom/fnmusic/tv` rules; on a connected target, run profile collection and the startup/frame
  macrobenchmark before replacing the checked-in profile.

## 7. Wrong vs Correct

```kotlin
// Wrong: every scheme without an explicit port is forced onto the legacy HTTP music port.
if (!hasExplicitPort) originBuilder.port(5666)

// Correct: preserve bare-HTTP compatibility while honoring standard URL scheme ports.
if (!hasExplicitPort) {
    originBuilder.port(
        when {
            scheme == "https" -> 443
            hasExplicitScheme -> 80
            else -> 5666
        },
    )
}
```

```kotlin
// Wrong: the access code is attached only to the password-login request.
api.login(headers = accessCodeHeaders)

// Correct: one ConnectionAccess supplies login, API, artwork, and playback request headers.
val headers = connectionAccess.headers(authToken)
```

```kotlin
// Wrong: hard-coded version values and a Release build that silently uses no stable identity.
defaultConfig { versionCode = 1; versionName = "0.1.0" }

// Correct: read one tracked version file and require local formal packaging to use the fixed key.
defaultConfig {
    versionCode = versionProperties.getProperty("VERSION_CODE").toInt()
    versionName = versionProperties.getProperty("VERSION_NAME")
}
buildTypes.release {
    signingConfig = signingConfigs.getByName("release")
}
```

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
// Wrong: every hot-path write rescans/sorts disk and reclaims Room pages.
writeArtwork(bytes); pruneDisk(budget)
dao.upsertPage(page); enforceBudgetWithSumsAndVacuum()

// Correct: update conservative ledgers and run exact expensive work only at a boundary.
trackedDiskBytes = trackedDiskBytes?.plus(newBytes - replacedBytes)
if (trackedDiskBytes > budget) trackedDiskBytes = pruneDisk(budget)
recordEvictableWrite(page.payload) // audits only when an estimate/interval requires it
```

```kotlin
// Wrong: Application construction decrypts secure values on the main thread.
private var token = tokenStore.read()

// Correct: Loading remains visible while restore performs one IO-bound credential load.
val (token, accessCode) = withContext(Dispatchers.IO) {
    tokenStore.read() to tokenStore.readAccessCode()
}
```

```kotlin
// Wrong: every composition starts empty and Compact is reused as a blurry Grid preview.
produceState<Bitmap?>(null, coverId) { value = decode(repository.artwork(coverId, Compact)) }

// Correct: synchronously reuse only the exact variant and join one bounded app-scoped decode.
val initial = artworkBitmapCache.peek(coverId, CoverVariant.Grid)
produceState(initial, coverId) {
    value = artworkBitmapCache.get(coverId, CoverVariant.Grid)
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
// Wrong: a position tick invalidates every playback consumer and rebuilds the queue.
_state.value = projectEverything(player)

// Correct: high-frequency progress is isolated; stable projection classifies structural events.
_progress.value = PlaybackProgressState(player.currentPosition, player.duration)
if (shouldRebuildPlaybackQueue(timelineChanged, itemTransition, metadataChanged)) {
    projectedQueueItems = projectQueue(player)
}
```

```kotlin
// Wrong: encoding starts before FIFO admission and later revisions may overtake it.
scope.async(Dispatchers.Default) { PlaybackSnapshotCodec.encode(snapshot) }

// Correct: enqueue the captured immutable snapshot; the FIFO writer encodes it off main in order.
writer.writeStructural(PlaybackSnapshotWriteRequest(namespace, revision, null, snapshot))
```

```kotlin
// Wrong: playback imports concrete storage and UI guesses infrastructure meaning from a name.
PlaybackController(context, localStore, musicRepository)
if (playback.error?.contains("BAD_HTTP_STATUS") == true) verifySession()

// Correct: app adapters satisfy playback-owned ports and errors carry typed behavior.
PlaybackController(context, sessionStore, contentSource)
if (playback.error?.requiresSessionVerification == true) actions.verifyCurrentSession()
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

```kotlin
// Wrong: local state is authoritative and the paging cursor can restore a deleted queue item.
preferences.setFavorite(trackGuid, true)
player.removeMediaItem(queueIndex) // queueSource still attached

// Correct: mutate the NAS account and detach server paging before an occurrence deletion.
musicRepository.toggleFavorite(trackGuid, fallbackFavorite)
queueSource = null
queueWindow = null
player.removeMediaItem(queueIndex)
```
