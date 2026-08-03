# Technical Design

## Boundaries

The feature spans the existing model, data, playback, and authenticated UI boundaries:

- `core/model` carries server-provided favorite state and adds Favorites as a queue source.
- `core/data` owns the Trim Music favorite HTTP contract, server-backed favorite state, and favorite-list paging.
- `core/playback` owns occurrence-index queue deletion, shuffle reconciliation, paging detachment, and durable snapshots.
- `app` owns routes, retained list state, player controls, queue-row actions, focus migration, and user-visible errors.

No server implementation or essential Room schema is added. The Trim Music server remains the source of truth for favorites.

## Favorite Data Contract

Add the documented endpoints to `TrimMusicApi`:

- `POST favorite-track/create` with `{ "trackGUID": guid }`
- `POST favorite-track/delete` with `{ "trackGUID": guid }`
- `GET favorite-track/list?page=<page>&size=50&sort=favoriteAt,desc`

`TrackDto` and `Track` retain `isFavorite`. `MusicRepository` exposes:

- favorite page loading for library UI and playback paging;
- a session-scoped observable favorite-state projection keyed by track GUID;
- a serialized desired-state mutation per track that calls create/delete, publishes success, and restores the last server-confirmed state on failure.

Metadata, normal track pages, roam responses, and favorite pages seed the same projection whenever they contain `isFavorite`. Account transitions clear the in-memory projection through the existing coordinator lifecycle. No optimistic value is persisted locally, so a restart or another device always converges through server responses.

A successful mutation increments a favorite content revision. The player consumes the GUID state directly, while the Favorites route uses the revision to remove an unfavorited row or reload page 1 without retaining a stale server list. A failed mutation retains the server-confirmed value and exposes a retryable UI error.

## Favorite Route And Playback

Add `LibraryRoute.Favorites`, its saveable key, and a track collection backed by `favoriteTracks(page)`. Add `QueueSource.Favorites(sort)` to repository dispatch and snapshot source encoding/decoding so a queue started from Favorites restores and pages exactly like existing playlist/artist/album/library queues.

Home replaces the existing single mixed `LazyRow` with two distinct content rows:

- a fixed first `Row` containing only Random Roam and Favorites;
- a second `LazyRow` beginning with the retained recent playlists and ending with All Playlists.

The existing 193x142dp tile format fits both rows inside the 720p safe area without viewport-scaled text. The first row owns stable `roam` and `favorites` requesters. The second row keeps playlist and `all-playlists` keys plus its retained scroll state. Explicit Down routes each first-row action to the nearest available second-row target, while Up from second-row cards returns to the relevant first-row action. If no playlists have loaded, All Playlists remains the deterministic second-row target. Returning from Favorites or a playlist restores the prior row, card key, and horizontal scroll.

The Favorites route owns retained track pages only while that route remains in the navigation stack.

The player adds an icon-only heart side action with separate outline/filled glyphs and exact semantics (`收藏当前歌曲` / `取消收藏当前歌曲`). Normal focus order becomes:

`favorite -> mode -> previous -> play/pause -> next -> queue`

Roam retains Favorite and hides Mode/Queue, producing:

`favorite -> previous? -> play/pause -> next? -> exit roam`

Every node routes Up to progress, disabled transports remain skipped, and the existing 48dp pointer target invokes the same toggle callback once.

## Queue Deletion Contract

Add `PlaybackController.removeQueueItem(queueIndex)`. It accepts only a valid index in a normal queue and begins a structural transition before mutation.

Deletion is occurrence-based because the UI passes the row's real `queueIndex`. On the first manual deletion, clear `queueSource` and `queueWindow`; the current bounded Media3 queue becomes an explicit detached queue. This is required because deleting only from the client cannot change the backing server playlist/favorites collection, and retaining its paging cursor could reinsert the deleted occurrence later.

Then remove the Media3 item and let Media3 select the next item, fall back from the former tail, or become empty. If shuffle is active, discard pending stale shuffle activation and reapply a complete order over the remaining canonical IDs. Project state, update presentation identity if the current item changed, and persist one structural snapshot after the final queue/mode state is stable.

The queue overlay renders each row as a select action plus an icon-only trailing delete action. Row and delete targets use stable occurrence-safe keys. Right moves row -> delete, Left moves delete -> row, and outer left/right escape is cancelled. Deletion is immediate with no confirmation, matching the provided reference.

After mutation, retain the previously focused occurrence when it survives. If the deleted row owned focus, prefer the new current occurrence, then the row at the deleted index, then the prior row. An empty queue closes the overlay and restores focus to the queue action after it leaves composition.

## Compatibility And Failure Handling

- Existing snapshot version 2 remains readable. The new Favorites source kind is additive; snapshots without it are unchanged.
- Favorite HTTP errors use the existing `AppException` / `AppError` mapping and never leave a successful-looking heart state.
- An unavailable item may remain visible in Favorites using its `accessStatus`, but existing playability filters prevent playback.
- Removing the final queue item yields an empty, stopped normal queue with a valid empty snapshot.
- Cache clearing does not erase server favorites; route caches may be discarded and reloaded.

## Rollback

- Favorite API/model/repository work is isolated from playback and can be reverted with the Favorites route and heart action.
- Queue deletion is isolated behind `removeQueueItem`; removing the row action restores the current read/select-only overlay.
- No destructive database migration or server mutation beyond documented favorite create/delete calls is introduced.
