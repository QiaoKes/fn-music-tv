# 播放器体验与缓存优化 - Technical Design

## 1. Summary

本次改动围绕五个明确的状态所有权展开：

1. `MusicRepository` 拥有受容量约束的进程内元数据工作集与图片请求缓存。
2. `PlaybackService` 只负责 Media3 播放、内存缓冲、授权和系统媒体命令边界，不再持久缓存音频。
3. `PlaybackController` 拥有普通队列、漫游、播放模式、分页、恢复快照及所有播放转换；Compose 只渲染状态并发送意图。
4. 应用级 `NowPlayingPresenter` 只拥有由当前播放身份派生的完整元数据、封面和歌词加载状态，不自行决定当前歌曲。
5. `AuthenticatedApp` 拥有页面导航、播放器浮层和 TV 焦点恢复，不复制播放业务或异步资源状态。

```text
Compose intent
    -> PlaybackController reducer/state machine
        -> MusicRepository (metadata/roam/page)
        -> MediaController / PlaybackService / ExoPlayer
        -> LocalStore atomic playback snapshot
    -> PlaybackUiState
        -> NowPlayingPresenter -> current metadata / artwork / lyrics
        -> controls / queue overlay / errors
```

## 2. Cache Architecture

### 2.1 Process-lifetime response cache

Add a repository-local cache component with these contracts:

- Capacity: an 8 MiB working set of validated serialized JSON payloads, LRU-evicted. A key avoids the NAS only while retained; eviction permits a later refetch.
- Key: `namespace|kind|businessKey|page`; no cache key may omit the server/user namespace.
- Read order: memory -> join/start keyed in-flight request -> NAS -> memory + best-effort Room.
- Offline order: on `NetworkUnavailable`, decode the existing Room payload, seed memory, and return it.
- Stateful roam endpoints never enter the cache. Track metadata used by `prepare(track)` uses `track-metadata:<guid>` and does.
- A failed or invalid response is removed from the in-flight table and never enters memory.

Shared fetches run in a repository-owned `SupervisorJob` and maintain a waiter count. A canceled waiter detaches without canceling other waiters; when the last waiter leaves, the incomplete upstream job and its OkHttp call are canceled. Each namespace has a monotonically increasing generation. Clear/logout increments the generation, cancels matching shared jobs, evicts matching entries, and rejects late results whose captured generation no longer matches.

Retained entries intentionally have no online TTL during one process lifetime. The bounded policy optimizes repeated TV navigation without claiming unlimited lifetime retention. Server-side library changes made during the same process are visible after eviction, cache clear, account rebind, or app restart; a future explicit refresh affordance is outside this task.

### 2.2 Artwork cache

Keep the current encoded-byte memory and disk tiers, with these corrections:

- Coalesce misses by namespaced cover/variant key.
- Store files under a hashed namespace directory so current-account clearing does not erase other accounts.
- Purge legacy flat artwork files once because they cannot be attributed back to a namespace.
- Write to a temporary file and atomically rename only after image validation.
- Touch the backing file on both memory and disk hits so disk LRU reflects actual use.
- Generation-check downloads before memory/disk insertion; clearing during a download cannot resurrect it.
- Treat the selected 32/64/128/256 MiB tier as one device-wide physical cap across all hashed namespace directories. Evict globally by last access; changing accounts must not multiply the cap.
- A failed/canceled miss never becomes a negative cache entry. Same-key misses share one request, but a new current-track revision can retry after the failed entry is removed.

Decoded bitmap caching is deferred. Network/disk duplication is the measured architectural gap; adding another large bitmap LRU without device memory measurements would stack memory costs.

### 2.3 Clear behavior

Expose distinct namespace invalidation and global user-initiated clearing:

```text
invalidateNamespace(namespace)
  -> clear process metadata entries/in-flight work
  -> clear that namespace's Room pages/lyrics/indexes, preserve its account row
  -> clear namespace artwork memory/disk files

clearAllEvictableCaches()
  -> invalidate every process-cache generation
  -> clear all Room pages/lyrics/indexes, preserve account rows and credentials
  -> clear artwork memory and every namespace directory
```

Logout captures the old namespace before credentials are removed and calls `invalidateNamespace`. The active account's selected tier configures the global artwork cap. Settings shows global artwork bytes plus the shared Room database physical size and its clear action uses `clearAllEvictableCaches`.

### 2.4 Cancellable HTTP and resource retry

Replace blocking `Call.execute()` wrappers with one cancellable OkHttp coroutine bridge based on `Call.enqueue`. Cancellation invokes `Call.cancel()` and rethrows `CancellationException`; it is never translated to `NetworkUnavailable`, cached as failure, or rendered as a resource error.

All four API bridge shapes (`execute`, `executeNullable`, `executeBytes`, and `executeUnit`) share one status classifier: HTTP 401 -> `Unauthenticated`, HTTP 404 -> `NotFound`, redirects/I/O/other non-success -> `NetworkUnavailable`, and successful missing required data -> `Empty`. An internal failure disposition distinguishes retryable I/O/408/429/5xx from non-retryable redirects and other statuses without changing the public `AppError`. The D8 decision deliberately narrows the active backend spec's generic non-success row for 404. Current artwork/lyrics translate `NotFound`, `Empty`, and validated empty content to `Absent`.

Automatic retry is not a global OkHttp policy. Only idempotent current-track metadata, lyrics, and artwork reads retry a retryable `NetworkUnavailable`, with two retries after 250 ms and 750 ms. Authentication, account-disabled, not-found, redirects, other non-retryable HTTP statuses, valid empty content, invalid artwork, and cancellation do not retry. POST/session/roam mutations retain their existing no-automatic-retry semantics. Tests assert exactly three requests for a recoverable transient sequence and exactly one for 401, 404, redirect, empty, invalid, and canceled cases.

## 3. Audio Buffering

Remove `SimpleCache`, `CacheDataSource`, media cache keys, the media cache clear command, and audio budget accounting from `PlaybackService`. No audio byte, span, or completed track may be persisted for reuse.

`ExoPlayer` uses `DefaultHttpDataSource.Factory` directly with the existing authorization and redirect restrictions. Configure `DefaultLoadControl` with the pinned Media3 1.10.1 forward-buffer maximum of 50,000 ms and a 15,000 ms in-memory back buffer. These are the only reusable audio buffers; neither retains played songs on disk.

At service startup, delete the legacy `cacheDir/media` directory and record no new files there. The operation is idempotent. Existing enum storage names remain stable, but their values become artwork-only budgets:

| Enum | Artwork disk budget |
| --- | ---: |
| `Small` | 32 MiB |
| `Medium` | 64 MiB |
| `Default` | 128 MiB |
| `Large` | 256 MiB |

No Room migration is needed for the preference because persisted enum names do not change.

## 4. Playback State Ownership

### 4.1 Public state

Extend the playback model with:

```kotlin
enum class PlayMode { ListRepeat, Shuffle, SingleRepeat, Sequence }
enum class QueueKind { Normal, Roam }

data class PlaybackQueueItem(
    val mediaId: String,
    val title: String,
    val artist: String,
    val queueIndex: Int,
    val isCurrent: Boolean,
)

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

`PlaybackUiState` additionally exposes queue kind/items/loaded playable count, play mode, Media3 playback state, `NowPlayingIdentity`, roam availability/busy/error, and navigation capability derived from Media3 rather than raw `index +/- 1` arithmetic. Raw source total/window positions stay internal to paging.

MediaItem construction and snapshot restore carry a dedicated `COVER_ID_KEY` alongside `AUDIO_FORMAT_KEY` in metadata extras. The projector captures `currentMediaItem` once and derives media ID, title, artist, format, and typed cover ID from that same item's `mediaMetadata`; it never reparses a URL for identity. It also never combines current-item identity with aggregate `player.mediaMetadata`, which MediaController may update in a later acknowledgement. Identity projection reacts to item transition, media-metadata change, and timeline change; the 250 ms ticker updates progress only.

Compose removes its local `roamWindow`, `roaming` route flag, and `roamBusy`. It observes controller state and invokes controller intents only.

### 4.2 Internal session state

`PlaybackController` serializes all mutations through its main-thread scope and owns one internal session:

```text
PlaybackSession
  generation
  snapshotRevision
  activeQueue: NormalQueue | RoamQueue
  frozenNormalQueue: NormalQueue?
  mode
  currentMediaId / position / playbackState
  shuffleOrderIds
  pendingTransition / retryableError
```

Every asynchronous page or roam result captures `generation` plus the expected source/cursor. Applying a result requires both to match. Entering a new normal queue, exiting roam, clearing auth, or disconnecting increments the generation and cancels active jobs.

### 4.3 Current-item presentation pipeline

`PlaybackController` increments `presentationRevision` for each logical MediaItem transition and whenever the captured current item's media ID, title, artist, format, or cover ID changes materially. Snapshot revision and presentation revision are separate counters. A transition remains authoritative even when the same media/cover ID appears again; index/count-only timeline changes do not restart presentation work.

An application-scoped `NowPlayingPresenter`, started by `AppContainer.startPlaybackRuntime()`, observes identity with latest-only semantics and exposes:

```text
NowPlayingPresentation
  identity
  metadata: Loading | Ready | RetryableFailure
  artwork: Loading | Ready(bitmap) | Absent | RetryableFailure
  lyrics: Loading | Ready(timeline/static) | Absent | RetryableFailure
```

For a new identity it immediately clears prior-track derived content, uses the MediaItem's typed fields as an initial value, and concurrently loads full track metadata plus lyrics. If the queue/list item lacks a cover ID, full metadata can supply it without delaying audio. Artwork then loads for the resolved cover and current player variant. All presenter applies compare the complete `namespace + mediaId + presentationRevision`; A -> B -> A therefore cannot accept the first A's late result. A same-item MediaItem update from missing cover to an enriched cover advances the revision, so older metadata/artwork work also cannot win. A detached old presenter may not publish, while shared single-flight cache insertion is still valid when namespace generation matches and another legitimate waiter remains.

Metadata `NotFound`/`Empty` retains the initial MediaItem fields and settles as `Ready`; it never blanks known title/artist. Only artwork and lyrics use `Absent` for their `NotFound`/empty result.

The presenter owns retry state rather than Compose. It uses the resource-specific retry policy from section 2.4 and exposes one `retryCurrentPresentation()` intent after automatic retries are exhausted; the intent retries only failed assets for the still-current identity. A new transition with the same cover ID still creates a new Loading attempt. Valid no-cover/no-lyrics responses become `Absent`, not failures.

Compose removes its local timeline/static/loading/failed variables and does not parse `coverId` back out of a URL or use the route's initially opened `Track` as current-track authority. Rendering a new identity cannot retain the old bitmap while Loading.

### 4.4 Play modes

Mode mapping is explicit:

| Product mode | Media3 repeat | Shuffle | Natural end |
| --- | --- | --- | --- |
| List repeat | `REPEAT_MODE_ALL` | off | wrap to first |
| Shuffle | `REPEAT_MODE_ALL` | explicit stable order | wrap after effective queue |
| Single repeat | `REPEAT_MODE_ONE` | off | repeat current |
| Sequence | `REPEAT_MODE_OFF` | off | stop at end |

Default is List repeat. The UI cycle is List repeat -> Shuffle -> Single repeat -> Sequence -> List repeat.

All four modes operate on the currently loaded playable Media3 window, capped at 250 items. They do not wrap or randomize unloaded/evicted rows from a larger server source.

Shuffle keeps canonical source order separate from effective order. Store the effective media-ID order in the snapshot and configure the service through a custom session command containing the snapshot revision and the complete ordered list of active media IDs. The service rejects missing, extra, or duplicate IDs with `SessionError.ERROR_BAD_VALUE`; otherwise it maps IDs to current ExoPlayer indices, installs an explicit `ShuffleOrder`, and returns success. The controller exposes Shuffle as active only after that acknowledgement. Page additions retain existing remaining order and insert newly loaded IDs using the session seed; removals filter missing IDs. Switching back to a non-shuffle mode restores canonical order without changing the current track or source cursor.

### 4.5 Normal transport behavior

- Play while Ready toggles normally.
- Play while Ended seeks the current item to its default position and starts it.
- Play while recoverable Idle prepares and starts; fatal source failure remains a retryable error.
- Previous at position >3,000 ms seeks to zero. Otherwise it moves to the Media3 previous item.
- Manual Next/Previous under Single repeat still changes items; natural completion repeats.
- Enabled state uses `hasNextMediaItem`/`hasPreviousMediaItem` and the product mode, not raw indices.

## 5. Sliding Queue Contract

Replace inferred flat-page ownership with explicit loaded page segments. Each segment stores:

- server page number and raw row count;
- playable queue entries with source absolute indices;
- sort and known total captured from the response.

The active Media3 list is the flattened playable projection. When the 250-item cap is exceeded, evict complete distant segments and atomically update first/last page and reached-start/end flags. The panel reports `activeItems.size` and numbers its visible rows contiguously from 1 through that count. Source absolute indices remain internal segment metadata for reloads, so CUE/unavailable filtering cannot create user-visible count mismatches or numbering gaps.

Required round trips are:

- append page 6, evict page 1, move left, reload page 1;
- prepend page 1, evict page 6, move right, reload page 6;
- the same cases with CUE and unavailable rows.

## 6. Roam State Machine

`enterRoam(window, prepared)` first resolves a playable initial item without mutating playback, then atomically freezes the current normal session, installs the roam item, and persists the combined state. If initial resolution fails, normal playback remains untouched. Roam uses a single MediaItem but owns the server `previous/current/next` window.

One `advanceRoam(direction, cause)` path handles UI buttons, natural completion, retry, and intercepted system transport:

1. Reject if another advance is active.
2. Capture session generation and current roam ID.
3. Request next/previous window.
4. Prepare its current track.
5. If the track is client-incompatible, advance the server cursor again. One attempt may inspect at most eight windows and maintains a seen-ID set; a blank, unchanged, or repeated ID fails immediately.
6. If generation/cursor still match, replace the MediaItem, update the window, force-snapshot, and play.
7. On failure, retain the coherent old cursor and expose Retry/Exit without clearing user auth.

`Player.Listener` handles `STATE_ENDED`: normal queues defer to Media3 mode behavior; roam calls `advanceRoam(Next, AutoCompleted)` exactly once per generation/media ID. A deduplication token prevents repeated ticker/listener callbacks from issuing another request.

`exitRoam` cancels pending work and restores the frozen normal queue/index/position paused. With no frozen queue it stops and clears playback. Starting any normal queue while roaming explicitly discards roam state after installing the new normal queue.

`PlaybackService` gives `MediaSession` a `RoamRoutingPlayer : ForwardingPlayer` around ExoPlayer. It overrides `seekToNextMediaItem`, `seekToNext`, `seekToPreviousMediaItem`, and `seekToPrevious`, so notification, hardware-key, and external `MediaController` transport all share one boundary.

Each override synchronously asks a process-local `PlaybackTransportBridge` for `Normal`, `Roam`, or `Restoring` ownership. In `Normal`, both Next variants normalize to next-item navigation; both Previous variants normalize to the product rule by seeking the current item to zero above 3,000 ms and moving to the previous item otherwise. `Roam` posts exactly one `advanceRoam` intent to the application controller's main-thread reducer and suppresses the delegate call. `Restoring` suppresses navigation until the snapshot owner is known.

`TvMusicApplication.onCreate()` eagerly calls `AppContainer.startPlaybackRuntime()`. That idempotent application-scope operation registers the owner, connects `PlaybackController`, loads the v2/legacy snapshot, configures Media3, and changes bridge ownership from `Restoring` to `Normal` or `Roam`; it never waits for `MainActivity`. The Activity only renders application state and no longer disconnects the controller. An unexpectedly absent registration fails closed for navigation rather than mutating an unknown session. Service-only cold-start tests verify restore eventually leaves `Restoring`, then cover both method families for normal 3-second Previous, roam routing, and external commands with no Activity.

## 7. Versioned Atomic Snapshot

Use `account_state.queueJson` as one versioned playback envelope instead of independently updating `queueJson` and `frozenQueueJson`:

```text
version = 2
generation
snapshot revision
active queue kind and items
normal source/page segments
roam window and current roam ID
frozen normal queue
mode and explicit shuffle order
current media ID/index/position
play intent
```

Add one DAO update statement that writes the new envelope and clears legacy `frozenQueueJson` atomically. No database version bump is required. The codec accepts the old queue/frozen pair, converts it to the v2 envelope, and immediately rewrites it. Unknown/corrupt versions fail closed to an empty paused session without clearing account credentials.

Restore captures session generation before reading Room and rechecks both generation and an empty/unclaimed active session immediately before applying. A queue selected while that read is suspended wins; the delayed snapshot may not replace or pause it.

One application-scoped snapshot-writer actor is the only code allowed to call that DAO update. Producers create immutable envelopes with monotonically increasing revisions on the serialized reducer thread. Structural writes enter a FIFO channel, are never conflated, and return an acknowledgement; a transition that promises durable completion awaits that acknowledgement. Position checkpoints may replace only an older pending checkpoint before the next structural barrier. The actor records the latest committed revision and skips stale requests, preventing an older delayed checkpoint from overwriting a newer mode change, roam exit, normal replacement, or logout.

Force an acknowledged structural snapshot after queue replacement, page mutation, mode change, item transition, roam transition, and exit. Keep the existing five-second position checkpoint only as supplemental conflated progress persistence. Restores are paused, including restored roam; the Exit action remains available even if the server cursor later expires.

## 8. Player Queue UI

The queue is a right-side overlay on the immersive player, not a navigation route. It retains the full-bleed player underneath with a restrained left scrim.

- Action row: mode on the left, previous/play/next centered, queue on the right; roam hides normal mode/queue and retains Exit Roam.
- Opening queue suspends controller auto-hide, scrolls to the current row, then focuses it after composition.
- Heading uses the loaded playable active count (maximum 250); rows use contiguous active-queue numbers, title, artist, current marker, and independent remote-focus state. Raw remote totals/indices are not presented as playable queue counts.
- Center selects and plays the row without dismissing the panel.
- Back closes the queue, restores queue-button focus, and restarts the five-second controller timer.
- Empty queues do not open; page failure uses a stable inline Retry state.
- Queue deletion/reordering is intentionally omitted; no inert remove affordance is rendered.
- Current-track artwork/lyrics render the presenter's Loading/Absent/Failure states. When either retryable resource exhausts automatic retries, one focusable Retry command retries only the current transition; it disappears after the identity changes or recovery succeeds.

All action buttons own stable requesters. Directional neighbors are calculated from currently visible/enabled actions and declared with `focusProperties` before `focusRequester`.

## 9. Page Focus and Back

Each async destination stores a stable focused item key with its saveable list/grid state:

- first entry requests focus once after the first enabled item is composed;
- pagination never steals focus;
- return attaches the requester to the saved key;
- a missing/disabled saved key falls back to the nearest enabled item, then the first enabled item.

Track details focus enabled Play All first, otherwise the first playable track. Paged grids focus their first enabled tile. Settings focuses the first player-style action. Home/My retain stable focus keys across navigation.

Back priority is:

1. queue/player sub-overlay;
2. visible player controls;
3. player route;
4. detail/grid/settings route;
5. My -> Home;
6. Home first Back -> toast, second within 2 seconds -> `moveTaskToBack(true)`.

The double-Back behavior intentionally follows the user's product requirement even though generic Android TV guidance prefers direct exit. Returning to desktop never stops or clears playback.

## 10. Verification Contract

- Repository tests count upstream calls: a retained sequential/concurrent metadata key produces one NAS request; an evicted key may refetch; warm artwork memory and disk hits produce zero HTTP requests.
- MockWebServer tests prove coroutine cancellation invokes `Call.cancel()`, one canceled single-flight waiter does not cancel another, the last waiter does cancel upstream, and failed/canceled entries remain retryable.
- Playback construction tests assert a 50,000 ms Media3 forward-buffer maximum, a 15,000 ms back buffer, direct HTTP data sources, and absence of any `SimpleCache`, cache data source, media cache command, or recreated `cacheDir/media` file.
- Projector tests feed current item B with aggregate metadata A and require a coherent B identity. Presentation tests cover A -> B -> A reverse completion, same-cover failure/new transition, final transient failure then same-key recovery, cancellation not rendered as failure, and a same-item missing-cover -> enriched-cover revision with late old completion.
- Snapshot tests delay an old position write behind newer mode, roam-exit, normal-replacement, and logout requests and assert the newest structural revision wins.
- Restore tests suspend the Room read, start a new queue, then release the old read and prove it cannot replace or pause the new queue.
- Service-only tests cold-start the application/service with no Activity, verify restore leaves `Restoring`, then issue both Next/Previous method families through the published Player and verify normalized normal behavior, roam routing, restoring suppression, and single-flight behavior.
- Android TV API 36 AVD tests run at 1920x1080/320 dpi via `:app:connectedSideloadDebugAndroidTest` and `:app:connectedStoreDebugAndroidTest`; absence of a runnable target means TV interaction acceptance is not complete. The device scenario plays beyond 20 seconds, seeks back 10 seconds without interruption, and verifies no audio cache files appear.
- Record player/queue screenshots at 1920x1080 and 1280x720 and inspect focus visibility, overlap, and clipping.

## 11. Compatibility, Rollback, and Risks

- Existing persisted cache enum names remain readable.
- Existing Room schema stays at version 2; the playback JSON envelope carries its own version.
- Legacy audio/artwork cache removal is one-way but affects only evictable `cacheDir` data.
- Legacy queue snapshots migrate in place; corrupt snapshots degrade to empty paused playback.
- Main risk is state-machine breadth. Keep transition logic centralized and test it independently from Compose.
- The confirmed 15-second back buffer and player overlay framing require target-TV validation. Material memory pressure blocks acceptance and requires a new product decision; persistent audio caching is not a fallback.
- A pre-release rollback can restore prior code for diagnosis, but released behavior must continue to use direct HTTP without persistent audio spans. Migrated queue envelopes must remain safely ignored by old decoders, so a controller-code rollback may lose the current queue but never account/auth data.

## 12. Affected Boundaries

- `core/model`: play modes, queue segments/reducer contracts, cache budget semantics.
- `core/data`: cancellable HTTP bridge, process cache, metadata/lyrics/artwork single-flight/storage, atomic playback snapshot DAO operation.
- `core/playback`: Media3 load control, coherent current-item identity, session commands, state machine, roam, queue projection, codec.
- `app`: app-scoped startup/presenter, current-track resource states, player controls/queue overlay, page focus, double-Back, settings copy.
- `.trellis/spec`: update Android client and TV interaction contracts after implementation proves the behavior.
