# Research: metadata, artwork, audio caching, and request architecture

- Query: Map current API requests and metadata/image/audio caches; establish capacity, TTL, eviction, screen refetches, whole-song behavior, risks, and the smallest testable design for PRD R1-R2.
- Scope: mixed (repository code, configuration/tests/specs, local API contract, official Media3/Android documentation)
- Date: 2026-08-01

## Findings

### Executive answer

- This is a native Kotlin/Compose Android project. `rg --files -g '*.dart' -g 'pubspec.yaml'` found no Dart or Flutter files.
- Metadata is persisted, but it is **not an online read cache**. Every `cachedPage`, `cachedIndex`, and lyric call reads/touches Room, then calls the NAS; Room is returned only for `NetworkUnavailable` (`MusicRepository.kt:139-151`, `:232-280`). There is no metadata TTL. This is why route re-entry still refetches.
- Artwork is already cache-first: 24 MiB of encoded bytes in memory, then `cacheDir/artwork`, then the NAS (`MusicRepository.kt:49-53`, `:166-186`). It has no TTL; disk eviction is last-access-time-based against the selected artwork budget (`:202-215`). Concurrent misses and decoded `Bitmap`s are not shared (`AuthenticatedApp.kt:1007-1015`).
- Audio uses Media3 `SimpleCache` with an LRU limit, not merely a transient streaming buffer (`PlaybackService.kt:43-55`). It persists the byte ranges Media3 reads. A partially played/skipped track usually leaves partial spans; a track played/buffered end-to-end can become fully cached. There is no download worker, `CacheWriter`, or `DownloadService`, so whole files are not deliberately fetched in advance.
- Recommended R2 policy: remove persistent audio `SimpleCache`. Keep the existing Media3 forward memory buffer and configure a small 15-second back buffer. Offline downloads are explicitly out of scope (`prd.md:82-85`); a LAN Range-capable NAS makes refetch-on-seek the simpler, predictable policy. This is the only option that makes AC4 (no long-lived complete-song accumulation in the cache directory) unambiguous.

### Files found

| File | Role |
| --- | --- |
| `core/data/.../repository/MusicRepository.kt` | All metadata wrappers, artwork memory/disk cache, cache usage, playback URL preparation. |
| `core/data/.../local/AppDatabase.kt` | Room cache entities, access timestamps, size queries, eviction queries, 24/32 MiB limits. |
| `core/data/.../local/LocalStore.kt` | Room reads/touches, writes, namespace clearing, budget enforcement. |
| `core/data/.../api/ApiContract.kt`, `Dto.kt` | JSON decoding and cached DTO shapes. |
| `core/data/.../repository/SessionRepository.kt` | Per-server/user API and cache namespace lifecycle. |
| `core/data/.../preferences/AppPreferences.kt` | Per-account cache setting persisted to Room and mirrored to SharedPreferences. |
| `core/model/.../preferences/Preferences.kt` | Cache tiers and 3:1 media/artwork split. |
| `core/playback/.../PlaybackService.kt` | Media3 HTTP factory, `SimpleCache`, namespace keys, LRU, cache clear command. |
| `core/playback/.../PlaybackController.kt`, `PlaybackCommands.kt` | Player media items, queue paging requests, cache/auth commands. |
| `app/.../TvMusicApplication.kt`, `MainActivity.kt`, `ui/FnMusicApp.kt` | Singleton ownership, startup/session binding, playback service connection. |
| `app/.../ui/AuthenticatedApp.kt` | Route-local loading, artwork decoding, lyrics, settings and cache usage UI. |
| `gradle/libs.versions.toml` | Media3 1.10.1, OkHttp 5.3.2, Room 2.8.4 (`:6`, `:14`, `:20`). |
| `core/data/build.gradle.kts`, `core/playback/build.gradle.kts`, `app/build.gradle.kts` | Data/playback/UI dependency boundaries; no image-loading library is present. |
| `app/src/main/AndroidManifest.xml` | Playback service and Internet/foreground-service declarations (`:8-11`, `:39-48`). |
| `docs/API.md` | NAS stream Range/ETag contract and long-cache cover semantics (`:429-440`, `:496-521`). |
| `LocalStoreTest.kt`, `AppDatabaseMigrationTest.kt` | Existing namespace/size tests and schema migration coverage. |
| `CacheBudgetTest.kt`, `AppPreferencesTest.kt` | Existing 3:1 split and account preference synchronization tests. |

### Request architecture

1. `TvMusicApplication` owns one `AppContainer`, hence one repository instance per app process (`TvMusicApplication.kt:10-19`). `MainActivity` connects the MediaController and restores the session on launch (`MainActivity.kt:14-20`).
2. A signed-in transition binds account preferences and sends raw auth plus `serverGuid:userGuid` to playback (`FnMusicApp.kt:98-103`; `SessionRepository.kt:97-112`).
4. Metadata/list calls flow UI -> `MusicRepository` -> Room read/touch -> NAS -> Room upsert. Only a network-classified failure returns the prior Room payload (`MusicRepository.kt:232-280`). Invalid JSON, auth failure, account-disabled, and not-found do not silently return stale data.
5. Audio is a separate network stack: Media3 `DefaultHttpDataSource`, not the OkHttp client (`PlaybackService.kt:35-52`). Adding an OkHttp cache would therefore not change audio playback behavior.

### Current cache policy matrix

| Data | Location/key | Online read policy / TTL | Capacity and eviction |
| --- | --- | --- | --- |
| Pages | Room `cache_page`; namespace + source + page (`AppDatabase.kt:27-36`) | Network-first; stale only on network failure; no TTL (`MusicRepository.kt:232-260`) | Shared 24 MiB payload target / 32 MiB DB+WAL+SHM cap (`AppDatabase.kt:137-140`); access timestamp touched on reads (`LocalStore.kt:32-37`); evicts 32 oldest page rows per pass (`LocalStore.kt:74-88`, `:102-104`). |
| Index/detail | Room `cache_index`; namespace + logical key (`AppDatabase.kt:46-52`) | Same; no TTL (`MusicRepository.kt:263-280`) | Same shared limits; independently evicts 32 oldest index rows per pass (`AppDatabase.kt:113-114`). |
| Lyrics | Room `cache_lyric`; namespace + track GUID (`AppDatabase.kt:38-44`) | Same; no TTL (`MusicRepository.kt:139-156`) | Same shared limits; independently evicts 32 oldest lyric rows per pass (`AppDatabase.kt:110-111`). |
| Track metadata used by roam | None | Always NAS (`MusicRepository.kt:111-120`) | Not cached. |
| Artwork bytes | 24 MiB `LruCache`; disk key hashes server + user + cover + variant (`MusicRepository.kt:49-53`, `:166-170`) | Memory -> disk -> NAS; no TTL (`:169-186`) | Memory fixed at 24 MiB. Disk uses selected artwork share and deletes oldest `lastModified` files (`:202-215`). Invalid/oversized images are rejected; max download 20 MiB, max edge 8192, max 16 MP (`:219-225`, `:290-293`). |
| Decoded artwork | Current Composable only (`AuthenticatedApp.kt:1007-1015`) | Decode again after route/composable recreation | No shared decoded-bitmap LRU. |
| Audio | `cacheDir/media`; key = namespace + URI (`PlaybackService.kt:43-52`) | Cache spans first, upstream for holes; no TTL | Media3 span-level LRU at `CacheBudget.mediaBytes`; clear removes current namespace keys (`:104-108`). Android may independently remove `cacheDir` files under storage pressure. |

Current selectable disk budgets come from a 75% audio / 25% artwork split (`Preferences.kt:5-10`):

| Tier | Total shown | Audio LRU | Artwork LRU |
| --- | ---: | ---: | ---: |
| Small | 128 MiB | 96 MiB | 32 MiB |
| Medium | 256 MiB | 192 MiB | 64 MiB |
| Default | 512 MiB | 384 MiB | 128 MiB |
| Large | 1024 MiB | 768 MiB | 256 MiB |

The Room cache can occupy an additional 32 MiB and is intentionally excluded from `CacheUsage.totalBytes` (`Preferences.kt:17-22`; settings copy at `AuthenticatedApp.kt:2115-2123`). The 24 MiB artwork memory cache is also outside the displayed disk usage.

### Which screens refetch

Route content is removed/recreated when `stack.last()` changes (`AuthenticatedApp.kt:140-156`). `SaveableStateProvider` preserves `rememberSaveable` values such as focus, but list data uses plain `remember`, so re-entering a route reruns these effects:

| Route/action | Requests caused on each entry |
| --- | --- |
| Home | `playlists()` (`AuthenticatedApp.kt:388-396`). |
| My | Page 1 artists + page 1 albums + shared libraries, in parallel (`:443-451`). |
| All playlists | `playlists()` again, immediately duplicating Home's logical key (`:547-553`). |
| All artists / albums | Page 1 through `PagedGrid`; later pages on explicit load (`:556-600`). These duplicate My's page-1 preview keys. |
| Playlist, album, all-track detail | Page 1 through `TrackCollection`, then next page on focus within 15 rows or explicit load (`:644-708`, `:723-743`). |
| Artist detail | Page 1 artist albums plus page 1 artist tracks (`:616-639`). |
| Player | Lyrics on every nonblank media ID / player reconstruction (`:1028-1059`); artwork separately through `produceState`. |
| Roam start/previous/next | Stateful roam call plus uncached `track/metadata` preparation (`:409-415`, `:1168-1169`, `:1196-1197`). |

`playlist(guid)`, `artist(guid)`, and `album(guid)` have cached repository wrappers (`MusicRepository.kt:59-61`, `:71-73`, `:87-89`) but no current app call sites.

### Audio: whole songs or streaming buffers?

Media3 defines its cache as partial resource spans. `CacheDataSource` writes upstream bytes as they are read; `CacheWriter` is the separate API that deliberately fills requested data. This app wires only `CacheDataSource` into playback (`PlaybackService.kt:48-55`) and has no writer/download code.

Therefore the precise answer is:

- It is a **persistent on-the-fly byte-range cache**, not just ExoPlayer's RAM buffer.
- It is **not an intentional whole-song cache**, but normal end-to-end playback can leave all ranges of a song cached. Skips/seeks leave one or more partial spans.
- The NAS supports `Range`, `Content-Range`, `Content-Length`, `ETag`, and `Last-Modified` (`docs/API.md:429-440`), so uncached seeks are viable. The current span cache does not revalidate already cached bytes; replacing audio under the same GUID/URL can leave stale spans or mix old cached spans with newly fetched holes.
- Because it lives under `cacheDir`, even a complete cached song is not a guaranteed offline asset; Android may delete it under storage pressure.

### Risks in the current implementation

1. **Guaranteed repeat NAS traffic and UI loading gaps.** Every route entry recreates plain `remember` state and the repository is network-first.
2. **Duplicate concurrent work.** There is no keyed single-flight for metadata or artwork. Two simultaneous misses can issue duplicate NAS calls and direct-write the same artwork file (`MusicRepository.kt:171-181`).
3. **No process memory cache for metadata.** Room is read and JSON is decoded even for immediate repeats; roam track metadata is not persisted at all.
4. **Audio persists much more than the UX needs.** Default audio capacity is 384 MiB and can contain many fully traversed songs, contrary to R2/AC4.
5. **Audio content freshness.** Stable namespace+URI keys have no ETag/version component. A server-side track replacement under the same GUID can reuse stale cached ranges.
6. **Artwork memory/disk LRU mismatch.** A memory hit returns before touching the backing file (`MusicRepository.kt:169-170`, `:185`), so frequently used in-memory art can still look old to disk pruning. Disk writes are not atomic.
7. **Artwork memory pressure/duplicate decode.** The 24 MiB cache stores compressed bytes, while decoded bitmaps are independently allocated per active Composable. Poster images can be expensive on TV hardware.
8. **Budget semantics are not live.** Playback reads the media budget only in `PlaybackService.onCreate` (`PlaybackService.kt:38-47`); settings explicitly state it takes effect next service start (`AuthenticatedApp.kt:2120-2123`).
9. **Room eviction is approximate rather than global LRU.** Each over-budget iteration evicts up to 32 rows from each table, even if one table contains newer data (`LocalStore.kt:74-88`). SQLite `length(TEXT)` counts characters, so non-ASCII JSON is not a precise byte measurement (`AppDatabase.kt:98-105`).
10. **Clear/usage mismatches are possible.** Audio clearing targets only the current namespace and swallows removal failures (`PlaybackService.kt:104-108`), while usage scans the whole media directory (`MusicRepository.kt:198-200`). Artwork clearing removes every account's files (`:189-192`).

### Minimal design recommended for R1-R2

#### R1: process-lifetime metadata and artwork single-flight

Keep Room schema/version 2 and the existing network-first-on-first-process-read semantics. Add one byte-bounded, serialized-response LRU inside the process-singleton `MusicRepository`:

- Key: `namespace | response-kind | business-key | page`, never a bare business key.
- Value: validated serialized DTO payload, so the generic cache is type-safe at the serialization boundary and the same payload can be written to/read from Room.
- Read order: memory hit -> join/start keyed in-flight -> NAS -> memory + best-effort Room. On `NetworkUnavailable`, decode Room and seed memory. Never cache a failed/invalid response.
- Use a repository-owned coroutine scope for shared work so one leaving Composable does not cancel every waiter. Track a namespace generation; logout/clear increments it, cancels/removes matching in-flight entries, and prevents a late non-cancellable OkHttp response from repopulating the cleared namespace.
- Bound the memory cache (suggested starting point: 8 MiB serialized payloads or a measured entry cap); do not use an unbounded map.
- Route `prepare(track)` through an index key such as `track-metadata:<guid>` so roam preparation follows the same rule. Do not cache stateful `roamStart/Next/Previous` calls.
- Clear memory synchronously when the existing playback/session teardown still has the old namespace (`PlaybackController.clearSession` captures it at `PlaybackController.kt:218-234`). Different namespaces already prevent cross-account hits; explicit clear satisfies the retention requirement.

For artwork, keep the existing cache and budget. Add keyed single-flight around disk miss/download, validate before completing waiters, and write temp-file-then-rename. A small decoded-bitmap LRU keyed by namespace/cover/variant is a follow-up only if measured decode time remains visible; it should replace, not blindly stack on top of, part of the fixed 24 MiB raw-byte budget.

This design directly satisfies AC1-AC3 without a Room migration or UI-wide ViewModel rewrite. Screens may still recreate local state, but repeat entries return from process memory instead of the NAS.

#### R2: remove persistent audio caching

- Feed `DefaultMediaSourceFactory` directly from `DefaultHttpDataSource.Factory`; remove `SimpleCache`, its namespace key factory, and cache-clear command/usage accounting.
- Keep Media3's default forward buffering (Media3 1.10.1 documents a 50-second default maximum) and set `DefaultLoadControl.Builder().setBackBuffer(15_000, false)` for short backward seeks without retaining songs on disk. Validate the 15-second choice on the target TV/NAS rather than increasing it speculatively.
- On upgrade, remove legacy `cacheDir/media` spans once and ensure the settings usage refresh observes zero audio-disk bytes.
- Reinterpret the existing persisted `Small/Medium/Default/Large` enum names as artwork-only budgets of 32/64/128/256 MiB. Names remain stable in Room/SharedPreferences, so no preference migration is required; settings must label this as image disk cache and state that playback buffering is memory-only.
- Do not introduce `CacheWriter`, `DownloadManager`, or a tiny disk LRU. Even a small LRU can retain complete short/compressed songs and makes AC4 and usage wording ambiguous. A real offline-download feature would require separate durable storage and is out of scope.

### Testable verification plan

1. Add repository cache tests with a fake clock/upstream or MockWebServer: sequential same-key calls make one request; concurrent same-key calls make one request; different page/kind/namespace calls do not collide; failure is removed from in-flight and retry succeeds; offline Room fallback seeds memory.
2. Test clear races: start a blocked request, clear/switch namespace, release the response, and assert it cannot repopulate memory; a new namespace request must reach its own upstream.
3. Test `track-metadata:<guid>` and lyrics under the same process policy; stateful roam calls must remain uncached.
4. Add artwork tests: memory/disk hit makes no request; concurrent miss makes one request; invalid bytes are neither memory- nor disk-cached; clear during a blocked download prevents late insertion; disk write recovery handles a partial temp file.
5. Replace `CacheBudgetTest` assertions at `CacheBudgetTest.kt:7-13` with artwork-only tier assertions. Extend `AppPreferencesTest.kt:35-49` to prove serialized enum names remain compatible.
6. Playback test: assert the service/media-source graph contains no `SimpleCache`/`CacheDataSource`, no media files remain after one-time cleanup, the back buffer is 15 seconds, and cache usage/settings report artwork only. A device or integration test should play through a representative long track and assert `cacheDir/media` does not grow.
7. Existing `LocalStoreTest` covers namespace separation and capacity (`LocalStoreTest.kt:32-61`) but not exact LRU order; no `MusicRepositoryTest` and no `core/playback/src/test` directory currently exist.

### External references

- Project pins [Media3 1.10.1](https://developer.android.com/jetpack/androidx/releases/media3), matching `gradle/libs.versions.toml:6`.
- Media3's [network stack and caching guide](https://developer.android.com/media/media3/exoplayer/network-stacks) describes `SimpleCache` + `CacheDataSource` as on-the-fly caching of bytes loaded during playback.
- Media3's [Cache API](https://developer.android.com/reference/androidx/media3/datasource/cache/Cache) explicitly defines resources as partial cached byte spans; [CacheWriter](https://developer.android.com/reference/androidx/media3/datasource/cache/CacheWriter) is the separate whole-request fill utility.
- [DefaultLoadControl](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl) documents a 50-second default maximum forward buffer and zero-second default back buffer; its builder supports an explicit back-buffer duration.
- Android's [`Context.getCacheDir`](https://developer.android.com/reference/android/content/Context#getCacheDir()) states the system may delete cache files when space is needed.

### Related specs

- `.trellis/tasks/08-01-player-cache-playback-ux/prd.md:27-41` defines process-lifetime direct hits/single-flight, namespace clearing, image behavior, and the audio-policy requirement; AC1-AC4 are at `:68-74`.
- `.trellis/spec/backend/android-client-contracts.md:53-69` requires per-account namespaces, 24/32 MiB Room limits, namespace-prefixed Media3 keys, and cache-clear behavior. Removing Media3 disk cache requires updating the latter playback contract after implementation.
- `.trellis/spec/backend/android-client-contracts.md:104-115` requires contract-level cache, migration, playback, and CI validation.
- `.trellis/spec/frontend/state-management.md` and `.trellis/spec/frontend/android-tv-interaction.md` apply to any later screen-state/focus changes; the R1 repository design intentionally avoids expanding into that UI refactor.

## Caveats / Not Found

- No Dart/Flutter source or configuration exists; all relevant implementation is Kotlin/Gradle/Android XML.
- No current test directly exercises `MusicRepository` cache behavior, artwork concurrency, `SimpleCache`, partial audio spans, cache clearing, or playback-service budget changes.
- Exact forward/back-buffer memory use and seek latency depend on codec/bitrate and the target TV. The 15-second back buffer is a testable starting policy, not a measured device result.
- Server-side replacement behavior for a track GUID was not found. The stale/mixed audio-span risk follows from the stable URI key plus the documented ETag/Range contract; removing persistent audio spans eliminates it.
