# Implementation Plan

- [x] Read the active frontend/backend contracts and the API favorite section before editing.
- [x] Extend Track DTO/domain mapping with `isFavorite` and add favorite create/delete/list API calls with contract tests.
- [x] Add server-backed favorite paging, session-scoped favorite state/revision, serialized toggle/rollback behavior, and repository tests.
- [x] Add `QueueSource.Favorites` across repository paging and playback snapshot source round trips.
- [x] Add the Favorites route and split Home into a fixed Random Roam/Favorites first row plus a playlist-only second `LazyRow`, preserving row/card focus and playlist scroll state.
- [x] Reuse `TrackCollection` for Favorites, including paging, empty/error states, playback startup, return-state restoration, and mutation refresh.
- [x] Add the player heart action in normal and roam focus graphs with filled/outline state, single-flight mutation, semantics, and retryable failure feedback.
- [x] Implement occurrence-index queue deletion, paging-source detachment, shuffle reconciliation, empty-queue handling, and durable snapshot persistence.
- [x] Add row-local queue delete actions and deterministic focus migration after deletion.
- [x] Update Trellis frontend/backend contracts with the durable favorite and queue-mutation conventions.
- [x] Run targeted model/data/playback/UI tests, full sideload unit tests, lint, and debug assembly.
- [x] Capture and inspect 1920x1080 and 1280x720 player/home/favorites screenshots for focus, clipping, and overlap.
- [x] Refine the shared heart silhouette and redesign only the Random Roam, Favorites, and All Playlists Home artwork; rebuild and inspect both TV viewports.
- [x] Restyle queue rows and delete focus after the NetEase reference, and keep the selected player heart red across focus and press states.
- [x] Remove the legacy record fallback across library surfaces; reuse Favorites artwork in its detail and keep artist initials consistent between cards and detail.

## Validation

- `./gradlew :core:model:test :core:data:test :core:playback:test`
- `./gradlew :app:testSideloadDebugUnitTest`
- `./gradlew :app:lintSideloadDebug :app:assembleSideloadDebug`
- Android TV device checks for player normal/roam focus graphs, queue deletion focus, Home ordering, Favorites empty/list/error states, and physical pointer single-click behavior.

## Risky Files And Rollback Points

- `core/playback/.../PlaybackController.kt`: validate delete-current, delete-tail, delete-last, shuffle-pending, restore, and snapshot commit ordering before UI wiring.
- `core/playback/.../PlaybackSnapshot.kt`: keep version-2 decoding compatible while adding only the Favorites source discriminator.
- `app/.../PlayerScreen.kt`: preserve fixed control/queue bounds and explicit D-pad graph at both supported TV viewports.
- `app/.../AuthenticatedApp.kt`: preserve Home retained playlist state and route cleanup while adding Favorites.
- Stop and roll back the relevant layer if favorite mutation cannot reliably restore server-confirmed state or queue deletion can be undone by later source paging.
