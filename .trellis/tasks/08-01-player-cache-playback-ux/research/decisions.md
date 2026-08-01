# Authoritative Product Decisions

This record resolves conflicts between exploratory research, active project specs, and the confirmed product direction for task `08-01-player-cache-playback-ux`. These decisions are binding for implementation and review.

## D1. No persistent audio cache

- Remove the Media3 `SimpleCache` path and all persistent audio spans.
- Retain only Media3's approximately 50-second forward memory buffer and a configured 15-second back buffer.
- Delete legacy `cacheDir/media` content once and never recreate it.
- This task decision supersedes only the media-disk-key and media-cache `ClearCache` clauses in `.trellis/spec/backend/android-client-contracts.md`. Authorization, redirect, namespace, Room, migration, and CI clauses remain binding.

## D2. Product playback modes and Home Back

- Normal playback exposes four modes: List repeat, Shuffle, Single repeat, and Sequence.
- Home requires two Back presses within two seconds before moving the task to the desktop; playback continues in the background.
- These choices supersede the three-mode cycle and direct-Home-exit recommendations in `research/tv-ux.md`. Its queue-overlay, focus, layout, and reference-behavior findings remain applicable.

## D3. Active queue boundary

- The user-visible and Media3-active queue is the loaded playable window, capped at 250 items.
- Queue counts and visible row numbers describe this active playable window. Raw source totals and absolute source positions remain internal paging metadata.
- Repeat and shuffle operate over the active window. They do not promise a complete cycle across unloaded or evicted rows from a larger remote source.

## D4. Durable transport ownership

- `PlaybackController` is application-scoped and remains the sole playback-session state owner.
- `TvMusicApplication.onCreate()` eagerly calls `AppContainer.startPlaybackRuntime()`, which registers the owner, connects the controller, loads the snapshot, and publishes Normal/Roam ownership when restore completes. This startup path does not depend on an Activity; `MainActivity` only renders it and never disconnects it on finish or backgrounding.
- `PlaybackService` publishes a `RoamRoutingPlayer : ForwardingPlayer` around ExoPlayer. It overrides `seekToNextMediaItem`, `seekToNext`, `seekToPreviousMediaItem`, and `seekToPrevious`.
- A process-local `PlaybackTransportBridge` synchronously reports Normal, Roam, or Restoring ownership. In Normal, both Next variants normalize to next-item navigation and both Previous variants apply the product rule: seek the current item to zero above 3,000 ms, otherwise move to the previous item. Roam posts one serialized controller intent on the main thread and suppresses the delegate call. Restoring suppresses navigation until ownership is known. If application runtime registration is unexpectedly absent, service startup fails closed for navigation rather than mutating an unknown session.
- Notification, hardware-key, and external `MediaController` commands therefore traverse the same wrapper even when no Activity exists.

## D5. Ordered persistence

- One application-scoped snapshot-writer actor is the only caller allowed to write playback envelopes.
- Every immutable envelope has a monotonic revision. Structural snapshots are FIFO, non-conflated, and acknowledged; position checkpoints may replace only an older pending checkpoint and may never cross a structural barrier.
- The writer skips any revision older than the newest committed revision, so a delayed checkpoint cannot overwrite a newer mode, queue, roam-exit, normal-replacement, or logout state.

## D6. Device-wide artwork budget

- Artwork files remain namespaced, but the selected 32/64/128/256 MiB setting is one device-wide physical limit across every namespace.
- Eviction uses one global access-ordered LRU across namespace directories. The active account's selected tier configures that global limit.
- Settings reports global artwork bytes plus shared Room bytes. Global cache clear invalidates all evictable metadata/artwork/Room payloads while preserving account rows and credentials; logout separately invalidates the departing namespace.

## D7. Bounded roam recovery

- Initial entry and every Next/Previous advance may inspect at most eight server windows.
- Blank, unchanged, or previously seen roam IDs terminate the attempt immediately.
- Failed initial entry leaves normal playback untouched. A later failure retains the current roam item and exposes Retry and Exit; it never clears authentication.

## D8. Current-track resource identity

- Every logical MediaItem transition, and every material change to the captured current item's identity/title/artist/format/cover fields, owns a monotonic presentation revision distinct from persistence revision.
- Current ID/title/artist/format/cover fields come from one captured `currentMediaItem.mediaMetadata`; aggregate Media3 metadata cannot be combined with another item's identity.
- Artwork, lyrics, and current-track metadata use the complete namespace/media/presentation-revision identity. Canceled or late work cannot publish for a newer revision. Shared single-flight work may still populate a generation-valid namespace cache only while another legitimate waiter remains.
- Coroutine cancellation must invoke OkHttp `Call.cancel()`. Only idempotent transient current-resource failures receive two bounded retries; valid absence, auth/not-found, and cancellation do not retry.
- The final exhausted state offers an explicit retry for the unchanged current revision, and a new track transition retries even when its cover ID equals the prior track's cover ID.
- All API bridges consistently map HTTP 401 to `Unauthenticated` and HTTP 404 to `NotFound`; current artwork/lyrics map `NotFound` or successful empty content to `Absent`. This narrowly supersedes the generic “all non-success status -> NetworkUnavailable” row in `.trellis/spec/backend/android-client-contracts.md` for HTTP 404 only.
