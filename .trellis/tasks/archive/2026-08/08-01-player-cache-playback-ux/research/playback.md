# Research: Playback architecture, roam completion, queue modes, and persistence

- Query: Map the complete playback architecture; establish why roam does not auto-play the next track; audit queue mutation, completion, next/previous, repeat/shuffle, persistence, tests, and local Feiniu Music reference behavior.
- Date: 2026-08-01

## Findings

### 1. Executive conclusions

1. **Roam completion has no transition owner.** Roam installs one `MediaItem`, while the only `Player.Listener` callback merely projects fields into `PlaybackUiState`. The next-roam API is called only from the visible Compose Next button. With Media3 repeat off, the one-item playlist therefore reaches `STATE_ENDED` and stays there.
2. **Playback mode is split across incompatible owners.** The controller owns the Media3 timeline and a frozen queue JSON; Compose separately owns `roamWindow` and a route Boolean. Neither is authoritative, persisted together, or synchronized when a normal queue replaces roam.
3. **The bounded sliding queue loses page-address truth after eviction.** Append drops items from the head without advancing `firstPage`; prepend drops the tail without reducing `lastPage`. Later reverse paging skips the page that was evicted.
4. **Ended or failed playback cannot be restarted with Play.** `playPause()` calls only `play()` when `isPlaying == false`; Media3 `play()` only sets play-when-ready. An ended item needs a seek to its default position, and an idle/error player needs preparation/recovery.
5. **Repeat-one, repeat-all, sequence, and shuffle are absent.** No model, controller API, Media3 configuration, UI state/control, snapshot field, or test owns these modes. Media3 consequently uses its default repeat-off/non-shuffled behavior.
6. **Roam can be permanently blocked by a CUE track.** The server considers an accessible CUE track roam-playable, but the client rejects all CUE tracks. Manual roam failures are swallowed, so requesting next from the unchanged old roam ID returns the same rejected CUE node again.

### 2. Current architecture and data flow

```text
TrackCollection / BrowseHome
  -> MusicRepository (paged Track data, metadata refresh, roam API)
  -> PlaybackController (MediaController, queue window, Room snapshots)
  -> MediaSession in PlaybackService
```

- `TvMusicApplication` creates one application-scoped `PlaybackController`, `MusicRepository`, and `LocalStore` (`app/src/main/java/com/fnmusic/tv/TvMusicApplication.kt:14-19`). `MainActivity` connects the controller before restoring the session and releases its controller when the activity finishes (`app/src/main/java/com/fnmusic/tv/MainActivity.kt:14-25`).
- `FnMusicApp` collects playback state and configures raw Authorization plus `serverGuid:userGuid` after sign-in (`app/src/main/java/com/fnmusic/tv/ui/FnMusicApp.kt:75-103`).
- `PlaybackController` connects a `MediaController` to `PlaybackService` and exposes a projected `StateFlow` (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:54-95`).
- `PlaybackService` owns `ExoPlayer`, `MediaSession`, authenticated `DefaultHttpDataSource`, and a namespace-prefixed `SimpleCache` (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackService.kt:24-59`). Its callback adds only ConfigureAuth, ClearAuth, and ClearCache commands (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackService.kt:60-100`).
- Normal collection playback builds a maximum-250-item window around the selected track, filters it through `prepareQueue`, and supplies source/page metadata to `playQueue` (`app/src/main/java/com/fnmusic/tv/ui/AuthenticatedApp.kt:643-706`). Queue sources are playlist, artist, album, or all-tracks plus their server sort (`core/model/src/main/kotlin/com/fnmusic/tv/core/model/playback/Playback.kt:12-19`).
- `prepareQueue` emits direct-stream URLs and silently excludes inaccessible and CUE tracks (`core/data/src/main/kotlin/com/fnmusic/tv/core/data/repository/MusicRepository.kt:123-137`). `playQueue` replaces the Media3 timeline, prepares, optionally plays, and force-snapshots it (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:125-157`).
- The ticker/projector checks whether the current index is within 15 items of a loaded edge; page loads retry at 0.5/1/2 seconds and mutate both the reducer window and Media3 playlist (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:248-358`).
- Normal natural completion and item-to-item transition are delegated entirely to Media3. Manual normal Next/Previous call `seekToNextMediaItem`/`seekToPreviousMediaItem` (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:192-200`).

### 3. Root cause: roam does not auto-play next

Static reproduction:

2. `enterRoam()` freezes the current queue and then calls `playQueue(listOf(track))`, producing a Media3 playlist of exactly one item (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:159-166`).
3. The controller listener implements only `onEvents -> project`; it does not inspect `EVENT_PLAYBACK_STATE_CHANGED`, `STATE_ENDED`, transition reason, or queue kind (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:75-77`). `PlaybackUiState` does not expose playback state, play-when-ready, queue kind, roam ID, or mode (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:36-52`).
4. The only calls to `nextRoam`/`previousRoam` are inside the visible player UI button callbacks (`app/src/main/java/com/fnmusic/tv/ui/AuthenticatedApp.kt:1158-1209`). No service callback or controller completion handler calls them.
5. Official Media3 behavior is: repeat-off transitions to `STATE_ENDED` after the last playlist item. Therefore the one-item roam timeline ends by design; it cannot consume the detached Compose `RoamWindow.next`.

This is not an API prefetch issue. It is an ownership issue: playback completion occurs in the service/player layer, while roam advancement exists only in a foreground UI callback.

### 4. Other concrete playback defects

#### P0: queue page metadata becomes false after bounded eviction

- Append combines items, drops `removeFromStart`, and advances `lastPage`, but leaves `firstPage` and `reachedStart` unchanged (`core/model/src/main/kotlin/com/fnmusic/tv/core/model/playback/Playback.kt:79-103`). Example: pages 1-5 plus page 6 drops page 1, yet the state still says `firstPage=1` and `reachedStart=true`; moving left can never reload page 1.
- Prepend drops `removeFromEnd` and updates `firstPage`, but leaves `lastPage` and `reachedEnd` unchanged (`core/model/src/main/kotlin/com/fnmusic/tv/core/model/playback/Playback.kt:106-130`). Example: pages 2-6 plus page 1 drops page 6, yet the next request uses `lastPage + 1 == 7`, skipping page 6.
- These fields directly choose the next network page and suppress loads at claimed boundaries (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:278-303`). Existing append/prepend tests assert size, removal count, and selection only; they do not assert corrected page bounds or round-trip traversal (`core/model/src/test/kotlin/com/fnmusic/tv/core/model/playback/QueueReducerTest.kt:49-73,105-131`).
- Filtering CUE/unavailable tracks makes page ownership harder still: reducer GUID counts are playable-item counts, while `firstPage`, `lastPage`, `knownTotal`, and initial `windowStart` describe unfiltered server rows (`app/src/main/java/com/fnmusic/tv/ui/AuthenticatedApp.kt:687-704`; `core/data/src/main/kotlin/com/fnmusic/tv/core/data/repository/MusicRepository.kt:123-137`).

#### P0: roam/normal mode can diverge and destroy the frozen queue

- Compose stores `roamWindow` with plain `remember`, while the route also captures a `roam` Boolean (`app/src/main/java/com/fnmusic/tv/ui/AuthenticatedApp.kt:118-142,216-230`). The controller separately infers a frozen state only from nullable `frozenQueueJson` (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:68-70,159-190`).
- `playQueue()` does not clear a frozen queue or publish a mode transition (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:125-157`). Reproduction: start roam, Back to Home, open a playlist, play a normal track. The controller now plays a normal queue, but Home still has `activeRoam`; later Now Playing can reopen with roam controls and the old roam ID.
- After process recreation, Room restores `frozenQueueJson` and the one-item `queueJson`, but Compose recreates `roamWindow=null`. The current roam item is shown as an ordinary paused queue with no Exit Roam route, leaving the frozen normal queue inaccessible (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:97-119,422-430`; `app/src/main/java/com/fnmusic/tv/ui/AuthenticatedApp.kt:140-165`).

#### P0: exiting roam is not durably committed

- Exit replaces Media3 with the frozen normal timeline and clears `frozenQueueJson`, but does not force-save the restored normal `queueJson` (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:168-190`).
- The ordinary snapshot is only written by a 5-second ticker (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:348-381`). If the process dies after the frozen column is cleared but before the next ticker write, Room retains the roam one-item `queueJson` and permanently loses the restored normal queue on next launch.
- Enter and exit also update `queueJson` and `frozenQueueJson` in independent coroutines/SQL statements rather than one transactional playback snapshot (`core/data/src/main/kotlin/com/fnmusic/tv/core/data/local/AppDatabase.kt:16-24,62-66`; `core/data/src/main/kotlin/com/fnmusic/tv/core/data/local/LocalStore.kt:17-25`).

#### P1: Play cannot recover from ended/error states

- `playPause()` distinguishes only `isPlaying` and otherwise calls `play()` (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:192-194`). The projected state hides `player.playbackState` (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:248-267`).
- Media3 defines `play()` as setting play-when-ready for when state becomes `READY`; it does not seek an ended item or prepare an idle/error player. Thus after the final sequence item or roam item ends, the UI changes to paused but Center/Play does not restart it. The same applies after `playerError`, which puts the player in `STATE_IDLE`.

#### P1: CUE can wedge roam; decoder fallback is dead code

- `ResolvePlaybackSource` models CUE -> HLS and direct-decoder-failure -> HLS (`core/data/src/main/kotlin/com/fnmusic/tv/core/data/playback/ResolvePlaybackSource.kt:7-12`), but repository-wide usage is only its unit test. `MusicRepository.prepare` instead throws for CUE, and `prepareQueue` filters it out (`core/data/src/main/kotlin/com/fnmusic/tv/core/data/repository/MusicRepository.kt:111-135`). No player-error path requests an HLS source.
- On roam start, this aborts entry even if other direct-playable tracks exist (`app/src/main/java/com/fnmusic/tv/ui/AuthenticatedApp.kt:409-416`). On manual Next/Previous, both the roam request failure and `prepare` failure are swallowed (`app/src/main/java/com/fnmusic/tv/ui/AuthenticatedApp.kt:1163-1176,1191-1204`). Because `onRoamChanged` is not called, the client keeps the old relative ID and repeatedly receives the same rejected next node.

#### P1: background/system transport controls bypass roam semantics

- The MediaSession callback accepts default player commands and does not intercept next/previous for roam (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackService.kt:59-101`). Media3 delegates TV/media-button commands directly to the session player.
- Therefore notification, Assistant, hardware media key, or another controller sends Next to the one-item Media3 timeline and does nothing, while only the Compose callback knows how to call `roam-next`. Roam auto-advance also stops working whenever the Activity/controller is disconnected, despite playback living in a background service.

#### P2: duplicate queue models obscure the real contract

- `QueueCursor`/`QueueReducer` is used only by its tests; production uses `SlidingQueueState`/`SlidingQueueReducer` (`core/model/src/main/kotlin/com/fnmusic/tv/core/model/playback/Playback.kt:21-33,139-175`; production imports at `core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:18-20`). Tests of the unused reducer can pass while production page-eviction behavior is wrong.

### 5. Repeat, sequence, and shuffle support

Repository-wide search found no call to `repeatMode`, `setRepeatMode`, `shuffleModeEnabled`, or `setShuffleModeEnabled`, and no playback-mode model or UI control. The current effective behavior is Media3 `REPEAT_MODE_OFF` with shuffle disabled:

- normal queues advance in source order and stop after the final loaded/source item;
- a single item never repeats;
- final-item Next is disabled through raw index arithmetic (`app/src/main/java/com/fnmusic/tv/ui/AuthenticatedApp.kt:1080-1081`);
- repeat/shuffle are absent from the queue JSON encoder/decoder (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:384-474`).

Media3 already supports repeat-off, repeat-one, repeat-all, shuffle, manual skip ignoring repeat-one, stable shuffled insertion/removal, and transition callbacks. However, `currentMediaItemIndex` always refers to canonical order, so future controls must use `hasNextMediaItem`/`getNextMediaItemIndex`, not `currentIndex + 1`, when shuffle or repeat-all is active.

Recommended user-visible mode set, corroborated by the official Feiniu mobile APK, is:

| Product mode | Media3 behavior | Natural completion | Manual Next/Previous |
| --- | --- | --- | --- |
| Sequence | repeat off, shuffle off | stop ended at source end | canonical neighbor |
| List loop | repeat all, shuffle off | wrap source end to start | canonical neighbor with wrap |
| Single loop | repeat one, shuffle off | repeat current | move neighbor, then repeat it |
| Shuffle | explicit stable shuffle order; decide repeat policy explicitly | play every effective item once, then either stop or reshuffle per product decision | effective-order neighbor |

Roam is a queue kind, not a fifth repeat mode. Its natural completion always requests/activates the next server node; normal mode is frozen and restored unchanged on exit.

### 6. Local Feiniu Music reference evidence

- Two local APK paths are byte-identical: `/Users/saki/Downloads/飞牛音乐.apk.1` and `/Users/saki/Downloads/飞牛音乐_1.0.0_100049_20260728_183413_Android.apk`; SHA-256 `e3d7dbc605d6bc74f13f310b0408f330cbd100b0eaf6a14aaeb062c69cc05cae`.
- Manifest inspection identifies `com.trim.music`, version `1.0.0`/`100049`, Flutter AOT, and `com.ryanheise.audioservice.AudioService`, meaning playback is explicitly background-service based.
- Read-only `libapp.so` string inspection finds product enums/keys `MusicPlayMode`, `sequence`, `listLoop`, `singleLoop`, `shuffle`, `music_user_play_mode`, `setRepeatMode`, `setShuffleMode`, `skipToNext`, `skipToPrevious`, and `autoCompleted`.
- It also finds persistence keys `music_play_session`, `music_play_session_roam_window_v2`, `music_play_session_current_queue_item_id`, `currentQueueItemId`, and `shuffledQueueItemIds`, plus `music_play_queue_reshuffle` and `MusicRoamDirection`.
- These strings are corroborating design evidence, not recoverable Dart control flow: the release APK is Flutter AOT. They strongly indicate that the first-party app treats completion reason, play mode, shuffled order, current queue item, and roam window as persistent playback-session state rather than Compose/page-local state.

### 7. Recommended executable contracts

#### Single state owner

Define one serialized `PlaybackSessionState` owned beside the service/controller, not in Compose:

```kotlin
data class PlaybackSessionState(
    val generation: Long,
    val queue: QueueState,
    val currentMediaId: String?,
    val positionMs: Long,
    val playMode: PlayMode,
    val playIntent: PlayIntent,
    val roam: RoamState?,
    val frozenNormal: QueueState?,
)
```

- Queue kind is explicit: `Normal(source/window)` or `Roam(window/currentRoamId)`.
- Compose renders this state and sends intents; it never owns a parallel roam Boolean/window.
- `startNormalQueue`, `enterRoam`, `advance`, `complete`, `exitRoam`, mode change, page apply, and restore are serialized transitions guarded by `generation`.
- A stale API/page result whose generation/current roam ID no longer matches is discarded.

#### Completion and transport

- Listen to `onPlaybackStateChanged(STATE_ENDED)` and/or the relevant `onEvents` flag in the playback owner. Deduplicate by `(generation, mediaId, completionOrdinal)` so repeated events cannot issue duplicate network calls.
- Normal completion delegates to Media3 for adjacent items/modes. Roam completion uses the same `advance(Next, cause=AutoCompleted)` path as UI, system Next, and notification Next.
- Roam advance is single-flight. Keep the current item playing/ended until next track preparation succeeds; then atomically update `RoamWindow`, Media3 item, snapshot, and UI. Surface retryable error without moving the relative ID on failure.
- Play behavior is state-aware: `READY -> play`, `ENDED -> seekToDefaultPosition + play`, `IDLE with media/no fatal source error -> prepare + play`; source errors enter an explicit retry/skip/fallback transition.
- Specify Previous: recommended music behavior is restart current when position exceeds a threshold, otherwise move to previous; if product wants unconditional prior track, retain `seekToPreviousMediaItem` and test it explicitly.

#### Queue paging and shuffle

- Store exact loaded page segments (raw row identity plus playable projection), rather than inferring source pages from a filtered flat GUID count.
- When head/tail segments are evicted, update both page bounds and reached flags atomically. Test append -> evict -> prepend and prepend -> evict -> append round trips.
- Persist canonical order separately from effective shuffle order/seed. Changing mode must not change canonical source/page cursors. On restore, the same track and remaining shuffle traversal must survive.
- Use Media3 navigation queries for enabled state; raw index arithmetic is invalid under shuffle/repeat-all.

#### Persistence

- Persist one versioned snapshot in one Room transaction/column. It must contain normal/roam kind, roam window/current ID, frozen normal queue, mode, canonical/effective order, current item, position, play intent, and generation.
- Enter roam, replace roam track, exit roam, normal-queue replacement, mode change, and item transition force an immediate atomic snapshot. The 5-second position checkpoint remains supplemental.
- Restore remains paused unless the product explicitly opts into background playback resumption, but restored roam must still expose Exit and retain its server cursor. Invalid/expired roam cursor should restart roam or exit with a visible recoverable result; it must not clear user auth.

### 8. Tests required

1. Controller/service unit test: one-item roam reaches `STATE_ENDED`; exactly one `roam-next` request occurs and successful preparation replaces/plays the new item.
2. Race test: auto-completion plus UI/system Next at the same time still advances once; exiting roam or starting a normal queue while a response is in flight discards the stale result.
3. Roam error matrix: next API network failure, expired/invalid roam ID, empty response, CUE current/next, metadata failure, and media-source failure all retain a coherent cursor and expose retry/exit.
4. Mode matrix: sequence end, list wrap, repeat-one natural completion, manual skip under repeat-one, shuffle every item once, append/remove while shuffled, and enabled-state queries.
5. Ended/error Play test: Play restarts ended media from default position and prepares/retries recoverable idle media.
6. Sliding-window round trip: pages 2-6 prepend page 1 and evict page 6, then moving right reloads page 6 (not page 7); mirror for append/head eviction. Include unavailable and CUE rows.
7. Persistence crash-point tests after each roam transition: persisted state is always either the complete old state or complete new state, never roam queue with cleared frozen normal queue.
8. Process-recreation test: roam window, current ID, frozen normal queue, position, and mode restore paused; Exit restores the exact normal queue/index/position.
9. MediaSession/device test: TV/media Next and Previous have the same result as on-screen controls in normal and roam modes.
10. Existing tests need relocation/focus: production `SlidingQueueReducer` needs the full mutation matrix; unused `QueueReducer` coverage must not substitute for it.

No Gradle tests were run during this research-only pass because the Trellis researcher role forbids writes outside the task's `research/` directory. Static reproduction is based on complete call-site and state-transition tracing.

### 9. Files found

- `core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt` - UI projection, MediaController commands, normal/roam queue replacement, pagination, and JSON snapshots.
- `core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackService.kt` - ExoPlayer/MediaSession/HTTP cache owner and external command boundary.
- `core/model/src/main/kotlin/com/fnmusic/tv/core/model/playback/Playback.kt` - queue sources and both legacy/current reducers.
- `core/data/src/main/kotlin/com/fnmusic/tv/core/data/repository/MusicRepository.kt` - queue page source, direct playback preparation, CUE filtering, lyrics, and roam calls.
- `app/src/main/java/com/fnmusic/tv/ui/AuthenticatedApp.kt` - Compose-owned roam window/routes, queue construction, player controls, and sole roam advance callbacks.
- `core/data/src/main/kotlin/com/fnmusic/tv/core/data/local/AppDatabase.kt` / `LocalStore.kt` - separately persisted normal/frozen queue JSON.
- `core/model/src/test/kotlin/com/fnmusic/tv/core/model/playback/QueueReducerTest.kt` - current reducer coverage and page-bound omission.
- `/Users/saki/Downloads/飞牛音乐.apk.1` - first-party mobile reference APK; Flutter AOT string/manifest evidence only.

### 10. Code patterns

- Player state is projected by polling every 250 ms plus broad `onEvents`, rather than reduced from typed playback events (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:75-77,248-269,348-358`). This loses completion cause and makes durable transitions implicit.
- Media3 timeline mutation and separate reducer mutation happen in the controller, while roam network mutation happens in Compose. The cross-layer split is the direct cause of background/auto-completion divergence.
- Snapshots serialize Media3 items back into ad hoc JSON and silently return `null` on decode error (`core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:384-474`). There is no schema version or diagnostic, despite `account_state.schemaRevision` existing only at the table row level (`core/data/src/main/kotlin/com/fnmusic/tv/core/data/local/AppDatabase.kt:16-24`).

### 11. External references and versions

- Project dependency: AndroidX Media3 `1.10.1` (`gradle/libs.versions.toml:1-7`).
- [Media3 playlists](https://developer.android.com/media/media3/exoplayer/playlists) - repeat-off ends at the last item; repeat-one/all and shuffle behavior; shuffled indices remain canonical; `onMediaItemTransition` reports automatic/repeat transitions.
- [Media3 Player API](https://developer.android.com/reference/androidx/media3/common/Player) - `STATE_IDLE`/`STATE_ENDED`, play-when-ready semantics, transition events, repeat constants, and navigation queries.
- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback) - the service is the durable player/session owner; playback resumption should restore playlist plus repeat/shuffle settings.
- [Control playback with MediaSession](https://developer.android.com/media/media3/session/control-playback) - external media buttons are delegated to Player methods unless the session/player behavior is customized.

### 12. Related specs

- `.trellis/spec/frontend/android-tv-interaction.md:29-31,61-67` already requires stable player focus and single-flight roam next/previous, but it does not define natural completion or a playback-state owner.
- `.trellis/spec/frontend/android-tv-interaction.md:99-112` requires exit-roam restore and player device/state tests; those tests do not exist in the current tree.
- `.trellis/spec/backend/android-client-contracts.md:63-69` owns playback Authorization/cache boundaries but currently has no queue, completion, mode, or snapshot contract.
- `.trellis/spec/guides/cross-layer-thinking-guide.md:19-51` applies directly: Media3 events, controller state, Compose state, Room snapshot, and server roam cursor currently each own only part of one transition.

## Caveats / Not Found

- No first-party Feiniu Dart source or debug symbols were found locally. The mobile APK evidence cannot establish exact implementation control flow or whether Shuffle stops or reshuffles after a full cycle; that remains a product decision.
- The two local Feiniu APK filenames contain identical bytes, not two versions.
- No playback controller/service test source exists under `core/playback`; app tests cover player lyric/color helpers, not transport or lifecycle behavior.
- No repeat/shuffle contract was found in current Trellis specs or task PRD; the four-mode recommendation is based on Media3 capability plus first-party mobile APK enum/string evidence.
- Server roam is in-memory and can invalidate a persisted roam ID after 24 hours or any server restart. Client restore must treat this as a roam-session recovery case, not user-token invalidation.
