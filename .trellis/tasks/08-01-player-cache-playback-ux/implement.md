# 播放器体验与缓存优化 - Implementation Plan

## Preconditions

- Work from the reviewed `prd.md` and `design.md`.
- Read the Android client, Android TV interaction, cross-layer, and code-reuse specs from the task manifests.
- Preserve unrelated user changes and keep reference APK artifacts outside product source.

## Ordered Checklist

### 1. Model and reducer contracts

- [x] Add `PlayMode`, queue kind/item/page-segment models, and pure mode mapping helpers.
- [x] Replace flat sliding-page inference with explicit segment-aware bounded updates.
- [x] Change `CacheBudget` values to artwork-only 32/64/128/256 MiB and simplify `CacheUsage`.
- [x] Add model tests for mode cycle/mapping and both-direction queue eviction round trips, including filtered rows.
- Validation: `./gradlew :core:model:test`.
- Rollback point: no Android/data/UI consumers changed yet.

### 2. Repository response and artwork caching

- [x] Replace blocking OkHttp `Call.execute()` wrappers with one `Call.enqueue` coroutine bridge; cancellation must invoke `Call.cancel()` and rethrow `CancellationException`.
- [x] Unify all JSON/nullable/bytes/unit status mapping and internal retry disposition: 401 -> Unauthenticated, 404 -> NotFound, other non-success/redirect/I/O -> NetworkUnavailable, successful required-data absence -> Empty; only I/O/408/429/5xx are retryable, and current artwork/lyrics NotFound/Empty become Absent.
- [x] Add the 8 MiB namespaced serialized-response LRU and keyed single-flight/generation invalidation.
- [x] Route pages, indexes, lyrics, and track metadata preparation through it; keep roam endpoints uncached.
- [x] Reference-count single-flight waiters: one cancellation cannot poison peers, while the last waiter cancels the unfinished upstream call.
- [x] Add namespace-scoped artwork directories, atomic writes, hit touching, single-flight, failure-entry removal, clear-race protection, and one device-wide cross-namespace LRU budget.
- [x] Add a current-resource retry helper limited to retryable idempotent metadata/lyrics/artwork I/O/408/429/5xx failures at 250/750 ms; never retry cancellation/auth/not-found/redirect/other status/valid absence.
- [x] Add namespace invalidation plus global evictable clear/usage APIs and preserve Room account/credential state.
- [x] Add MockWebServer/repository tests for call cancellation, remaining/last waiter behavior, sequential/concurrent hits, 8 MiB LRU eviction/refetch, namespace isolation, offline seeding, failed retry, and clear races.
- [x] Assert current-resource request counts: retryable transient failure then success uses at most 3 requests; 401, 404, redirect, empty/invalid content, and cancellation each use exactly 1 and never enter automatic retry.
- [x] Add artwork tests for zero-request warm memory/disk hits, one-request concurrent miss, same-key failure then retry, global cross-account eviction, invalid data, atomic cleanup, and late response rejection.
- Validation: `./gradlew :core:data:test`.
- Rollback point: Room schema remains unchanged; cache behavior can be reverted independently.

### 3. Remove persistent audio caching

- [x] Replace `CacheDataSource`/`SimpleCache` with direct authenticated HTTP media sources.
- [x] Configure the pinned Media3 50,000 ms forward-buffer maximum and 15,000 ms back buffer.
- [x] Remove media budget/cache-clear commands and idempotently delete legacy `cacheDir/media`.
- [x] Update cache model/preferences tests and settings-facing usage contracts.
- [x] Add playback/service tests or test seams proving direct HTTP, no media cache graph/files/commands, and the exact load-control settings.
- Validation: `./gradlew :core:playback:test :core:playback:lintDebug`.
- Rollback point: audio playback can be restored without touching metadata or playback snapshots.

### Gate A: cache and audio approval

- [x] Review cancellable HTTP, waiter-counted single-flight, resource retry classification, metadata eviction, device-wide artwork accounting, settings clear behavior, direct authenticated audio streaming, and legacy media cleanup together.
- [x] Do not begin playback-session changes until stages 1-3 and their targeted validation commands pass.

### 4. Versioned playback session and persistence

- [x] Add the single internal playback session, v2 codec, legacy queue/frozen decoder, monotonic revision, and atomic LocalStore save operation.
- [x] Project queue rows, loaded playable count, mode, Media3 state, roam state, capabilities, and retryable errors; keep raw source positions internal.
- [x] Add one FIFO snapshot-writer actor: acknowledge non-conflated structural writes, conflate position checkpoints only within structural barriers, and skip stale revisions.
- [x] Force acknowledged snapshots on every structural transition and keep timed position checkpoints.
- [x] Restore legacy and v2 states paused; preserve frozen normal queue and restored roam Exit.
- [x] Guard restore with session generation plus an apply-time empty-session recheck so a delayed Room read cannot replace a newly selected queue.
- [x] Add codec/legacy/corruption/crash-point tests plus delayed-old-checkpoint tests for mode, roam exit, normal replacement, and logout, and a delayed-restore/new-queue takeover test.
- Validation: `./gradlew :core:data:test :core:playback:test`.
- Rollback point: old columns remain; auth/account data is never migrated destructively.

### 5. Current-item artwork and lyrics consistency

- [x] Add idempotent `AppContainer.startPlaybackRuntime()` for application-scope controller connect/restore/configure, invoke it from `TvMusicApplication.onCreate()`, and remove Activity-driven disconnect before constructing the presenter.
- [x] Add typed `COVER_ID_KEY` metadata to MediaItem construction and v2/legacy restore; stop parsing cover identity from artwork URLs.
- [x] Project ID/title/artist/format/cover from one captured `currentMediaItem.mediaMetadata`; handle transition, metadata, and timeline events while the ticker updates progress only.
- [x] Add a monotonic `presentationRevision`, independent of snapshot revision, that advances on logical transitions or material captured current-item field changes; expose `NowPlayingIdentity`.
- [x] Add application-scoped `NowPlayingPresenter` started by `startPlaybackRuntime()`: metadata uses Loading/Ready/RetryableFailure, while artwork/lyrics also support Absent; metadata NotFound/Empty retains initial MediaItem fields as Ready.
- [x] Load full metadata and lyrics concurrently for the captured identity, enrich missing list cover IDs, decode only the current artwork, and reject every namespace/media/revision mismatch.
- [x] Reset stale artwork immediately on transition, preserve cancellation, apply the bounded resource retry policy, and expose `retryCurrentPresentation()` for exhausted current failures.
- [x] Remove Compose-owned lyric loading state, route-track authority, and same-cover terminal `produceState` behavior.
- [x] Add pure projector and presenter tests for B identity with A aggregate metadata, A -> B -> A reverse completion, same-cover failure/new transition, cancellation, same-key retry recovery, valid absence, and same-item missing-cover -> enriched-cover with late old completion.
- Validation: `./gradlew :core:data:test :core:playback:test :app:test`.

### 6. Normal modes, transport, and queue paging

- [x] Implement the four mode mappings over the active loaded playable window (maximum 250), default List repeat, explicit stable shuffle order, and mode persistence.
- [x] Send the complete unique active media-ID order plus snapshot revision to the service; activate Shuffle only after a successful validated session-command acknowledgement.
- [x] Implement ended/idle Play recovery and the 3-second Previous rule.
- [x] Apply segment-aware page additions/evictions to Media3 without losing current media.
- [x] Use Media3 navigation capabilities for enabled state.
- [x] Add active-window mode matrix, shuffle acknowledgement/rejection/restore/mutation, ended recovery, previous threshold, and paging integration tests.
- Validation: `./gradlew :core:model:test :core:playback:test`.

### 7. Roam state machine and external transport

- [x] Move roam window/busy/error from Compose into the controller session.
- [x] Unify auto completion, UI Next/Previous, retry, and system transport through one single-flight advance path.
- [x] Add generation/cursor checks, cancellation, an eight-window/seen-ID bound for both directions and initial entry, and coherent Retry/Exit state; initial failure leaves normal playback untouched.
- [x] Make enter/replace/exit/normal replacement atomic and immediately persisted.
- [x] Register Normal/Roam/Restoring ownership with the already-started application runtime and publish `RoamRoutingPlayer : ForwardingPlayer` with the synchronous transport bridge.
- [x] Add completion/manual race, exit/normal replacement race, initial/Next/Previous eight-hop and cursor-cycle failures, expired cursor, and process restore tests.
- [x] Add cold service-only notification/external-controller tests with no Activity for restore completion, both command method families, normalized normal 3-second Previous, roam routing, restoring suppression, and missing-owner fail-closed behavior.
- Validation: `./gradlew :core:playback:test :app:test`.

### Gate B: playback engine approval

- [x] Review the single state owner, ordered persistence, coherent current-item presentation, active-window mode semantics, service command acknowledgement, roam recovery, and Activity-free transport together.
- [x] Do not begin player UI work until stages 4-7 and their targeted validation commands pass.

### 8. Player action bar and queue overlay

- [x] Extend existing stable transport focus graph with mode and queue actions; hide them in roam.
- [x] Build the right-side queue overlay with loaded playable count, contiguous active numbering, current marker, current-row initial focus, scroll, selection, retry, and Back focus restoration.
- [x] Suspend control auto-hide while the queue is open and preserve layered Back order.
- [x] Render presenter Loading/Absent/Failure states without stale artwork and add one focusable current-presentation Retry command only after bounded retries are exhausted.
- [x] Use original project styling/icons; do not copy reference assets or exact branded geometry.
- [x] Add Compose/device semantics tests for action traversal, mode cycle, queue selection, and Back sequence.
- Validation: `./gradlew :app:test :app:assembleSideloadDebugAndroidTest :app:assembleStoreDebugAndroidTest`.

### 9. Page-entry focus and double Back

- [x] Add one-shot initial focus plus stable key/list state restoration to Home, My, grids, details, tracks, and Settings.
- [x] Ensure async pagination never steals established focus and disabled targets fall back predictably.
- [x] Change My Back to Home; implement two-second Home Back toast and `moveTaskToBack(true)` without stopping playback.
- [x] Add focus-helper tests and device tests for initial/return focus and double-Back timeout.
- Validation: `./gradlew :app:test :app:assembleSideloadDebugAndroidTest`.

### Gate C: TV UI approval

- [x] Run stages 8-9 on an Android TV API 36 AVD at 1920x1080/320 dpi with `./gradlew :app:connectedSideloadDebugAndroidTest :app:connectedStoreDebugAndroidTest`.
- [x] Capture 1920x1080 and 1280x720 player/queue evidence and review focus, clipping, layered Back, double Back, and background playback. A missing runnable TV target leaves this gate incomplete.

### 10. Integrated quality gate

- [x] Run targeted tests after each ownership area, then the full CI-equivalent gate.
- [x] Run the connected Android TV tests, not only their assemble tasks.
- [ ] Run a target TV/AVD smoke pass against a real NAS account: automated and local-device checks cover API/cache counts, A -> B -> A presentation recovery, legacy audio-cache deletion, all four modes, queue focus/count, paging edges, layered Back, and background behavior. A credentialed 20-second media stream plus 10-second back seek and a real-server roam auto-next remain external manual checks.
- [x] Inspect 1920x1080 and 1280x720 screenshots for player/queue overlap and text clipping.
- [x] Run a final Trellis check agent and fix all verified findings.

```bash
./gradlew --no-daemon --stacktrace \
  :core:model:test \
  :core:data:test \
  :core:playback:test \
  :app:test \
  :core:data:lintDebug \
  :core:data:lintRelease \
  :core:playback:lintDebug \
  :core:playback:lintRelease \
  :app:lintSideloadDebug \
  :app:lintSideloadRelease \
  :app:lintStoreDebug \
  :app:lintStoreRelease \
  :app:assembleSideloadDebug \
  :app:assembleSideloadRelease \
  :app:assembleStoreDebug \
  :app:assembleStoreRelease \
  :app:assembleSideloadDebugAndroidTest \
  :app:assembleStoreDebugAndroidTest \
  :baselineprofile:assembleBenchmarkRelease \
  :baselineprofile:assembleNonMinifiedRelease
```

With the Android TV API 36 AVD running at 1920x1080/320 dpi, also run:

```bash
./gradlew --no-daemon --stacktrace \
  :app:connectedSideloadDebugAndroidTest \
  :app:connectedStoreDebugAndroidTest
```

## Risky Files and Ownership

- `core/playback/.../PlaybackController.kt`: central state-machine changes; one implementation owner at a time.
- `app/.../ui/AuthenticatedApp.kt`: queue, controls, routes, and focus currently share one large file; one UI owner at a time.
- `core/data/.../MusicRepository.kt`: cache and artwork concurrency; do not mix unrelated API refactors.
- `core/data/.../TrimMusicApi.kt`: cancellation bridge affects every API call; preserve status/auth/redirect classification and never add mutation retries.
- `core/data/.../AppDatabase.kt` / `LocalStore.kt`: preserve version 2 and all essential account fields.
- `PlaybackService.kt`: audio cache removal and transport interception must be verified together.
- `app/.../NowPlayingPresenter.kt`: derived resource identity only; it must never become a second owner of queue/current-item selection.

## Final Review Gate

- Every PRD acceptance criterion has a test or a recorded target-device check.
- No seed-only context manifests remain.
- No persistent audio cache directory or file is created during playback.
- No Compose-owned duplicate roam or play-mode state remains.
- No Compose-owned current lyric/artwork request survives; no presentation result can publish under a different presentation revision.
- No user/auth state is cleared by a playback/cache failure.
- No reference APK code, resources, colors, or exact layouts enter product source.
