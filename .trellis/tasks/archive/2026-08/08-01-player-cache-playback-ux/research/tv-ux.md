# Research: TV player controls, queue, focus, and Back behavior

- Query: Define remote-control behavior for the full player action bar, current-queue panel, page-entry focus, focus restoration, and Back/app-exit semantics.
- Scope: mixed (current project, two supplied screenshots, NetEase Music TV 1.1.80 static reference, Android TV guidance)
- Date: 2026-08-01

## Findings

### Decision summary

- Build the queue as player-owned overlay state, not a `LibraryRoute`. Opening it must not add a navigation destination or relayout the player.
- Normal-queue action order should be `playMode -> previous -> playPause -> next -> playerStyle -> queue`, plus only those favorite/quality actions that have real backing state. Roam should hide queue/mode actions that would misleadingly operate on the frozen normal queue and retain `exitRoam`.
- Default to **list repeat**. Cycle **list repeat -> shuffle -> single repeat -> list repeat**. The reference also conditionally inserts a proprietary discovery mode; do not reproduce it without an equivalent product capability.
- Queue opening selects, scrolls to, and focuses the current item after rows exist. Back closes the queue and restores focus to the queue action.
- Back priority is `player sub-overlay -> visible controller -> player route -> prior content route -> Home -> system`. At Home, exit directly: no confirmation dialog, double-Back gate, or exit toast.
- Every async grid/detail screen needs an explicit initial target and a stable item-key restoration target. The current implementation relies on geometric focus for most entry paths.

### Files found

- `app/src/main/java/com/fnmusic/tv/ui/AuthenticatedApp.kt` - all authenticated routes, browse/detail screens, player visibility state, and player focus graph.
- `core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt` - projected player state and queue-window ownership; currently exposes neither queue rows nor repeat/shuffle state.
- `core/model/src/main/kotlin/com/fnmusic/tv/core/model/playback/Playback.kt` - bounded/sliding queue model and relative versus absolute queue indices.
- `app/src/main/java/com/fnmusic/tv/MainActivity.kt` - default activity exit behavior and playback-controller disconnect-on-finish.
- `.trellis/spec/frontend/android-tv-interaction.md` - existing project contracts for hidden controls, explicit focus routing, seeking, and Back.
- `.trellis/tasks/archive/2026-08/07-31-tv-music-client-v1-plan/research/apk-static.md` - validated TV APK identity and prior copyright-safe interaction findings.
- `/Users/saki/Downloads/NeteaseCloudMusic_MusicTV_official_1.1.80.260122145233.apk_official_1.1.80.260122145233_3264.apk` - correct reference APK (`com.netease.cloudmusic.tv`, 1.1.80).
- The supplied 1280x720 clipboard screenshots - player controller and current-queue visual evidence. The `.detail/` APK is a phone build and is excluded.

### Current behavior and gaps

The route stack starts at Home, `open` appends, and `root` replaces the entire stack; the only route-level handler runs when more than one route exists (`AuthenticatedApp.kt:140-146`). Home switches to My with `root(My)` and My switches back with `root(Home)` (`AuthenticatedApp.kt:157-184`). Consequently, system Back exits immediately from either Home or My. There is no exit toast or confirmation. Home should remain the one terminal start destination; Back from My should return to Home before system exit.

The player already implements the important base contract correctly:

- controls start visible and own stable requesters (`AuthenticatedApp.kt:1034-1041`);
- a five-second inactivity timer hides them (`AuthenticatedApp.kt:1082-1086`);
- showing controls focuses play/pause, while hiding them focuses the player root (`AuthenticatedApp.kt:1088-1090`);
- Back hides visible controls without leaving the player (`AuthenticatedApp.kt:1094-1097`);
- with controls hidden, Center toggles playback and any direction only reveals/consumes the first key (`AuthenticatedApp.kt:1098-1117`).

The visible controller is still transport-only: progress plus previous/play/next and optional exit-roam (`AuthenticatedApp.kt:1734-1883`). Its explicit transport graph and disabled-action handling are sound (`AuthenticatedApp.kt:1821-1879`, `1888-1921`) and should be extended rather than replaced.

Browse and grid entry is currently implicit. Home renders the random-roam tile before asynchronous playlists (`AuthenticatedApp.kt:388-424`); My asynchronously fills three bands without an entry requester (`AuthenticatedApp.kt:443-510`); paged grids request data after composition but never request the first row (`AuthenticatedApp.kt:577-601`). Track detail remembers the last focused GUID and re-requests it after data changes, but has no initial request when no GUID has yet been saved (`AuthenticatedApp.kt:653-661`, `708-735`).

`PlaybackUiState` exposes only current metadata, relative `currentIndex`, and loaded `itemCount` (`PlaybackController.kt:36-52`). It exposes no queue-row snapshot, absolute window start/known total, repeat mode, or shuffle state. The controller has only play/pause, previous, next, and relative seek commands (`PlaybackController.kt:192-201`). A usable queue and mode button therefore require controller/UI-state additions; UI-only work would create inert controls or incorrect counts.

### Screenshot and reference behavior

The player screenshot shows a full-width timeline above a three-zone action row: secondary actions at the left, stable previous/play/next in the center, and style/queue actions at the right. The queue screenshot shows a translucent right-side panel over the still-visible player, headed `当前播放 (30)`, with numbered title/artist rows and a separately marked current row. These are behavioral/layout references, not geometry or asset specifications.

Static TV APK evidence agrees with the screenshots:

- its bottom controller is a 250dp player overlay containing a separate progress include and action include (`res/layout/d_.xml:32-120`);
- the action layout centers play/pause between previous/next and places favorite/mode/quality on the left and player-style/queue on the right (`res/layout/km.xml:3-98`);
- activating queue opens `NewTvPlayerListDialog`, rather than navigating to another page (`PlayerLogicBase.x`, decompiled lines 814-836);
- the queue grid disables back-to-top wrapping, selects `PlayService.getCurrentPlayIndex()`, then requests focus (`NewTvPlayerListDialog`, decompiled lines 312-336 and 439-454).

The reference mode handler cycles the stored mode, updates the action text, and posts the selected mode as a transient message (`ControllerHelper`, decompiled lines 130-160). Its normal mapping is list repeat, shuffle, and single repeat (`ControllerHelper`, lines 902-914); the cycle helper confirms list -> shuffle -> single -> list, with an optional proprietary discovery-mode branch (`IotNeteaseUtils`, lines 164-190). Static analysis does not prove a clean-install default because the APK reads persisted global state. **List repeat is the recommended product default**, not a claimed APK default.

### Recommended player focus contract

Use one stable `FocusRequester` per visible action. Derive left/right neighbors from the ordered list of currently enabled nodes, so disabled/hidden actions are skipped and never focused. Do not wrap at the row ends.

| Input/state | Required result |
| --- | --- |
| Enter player or reveal controller | Focus `playPause`. |
| Left/Right on action row | Move to nearest enabled action in visual order. |
| Up on any action | Focus progress. |
| Down on progress | Focus `playPause`. |
| Left/Right on progress | Seek -/+10 seconds and retain progress focus. |
| First direction while controls hidden | Reveal controls, focus `playPause`, consume; do not also seek/move. |
| Center while controls hidden | Toggle play/pause, reveal controls, focus `playPause`. |
| Controller inactivity | Hide after five seconds only when no player overlay is open. |

Recommended normal order: `playMode, previous, playPause, next, playerStyle, queue`. Add `favorite` before mode and `quality` before transport only when their loading, success, failure, and selected states are implemented. Never ship focusable placeholders. On roam, expose only actions that operate on the active roam session, followed by `exitRoam`.

The mode button is a single cyclic action, not a popup. Use `LIST_REPEAT` as default and map it to Media3 repeat-all with shuffle off; `SHUFFLE` to repeat-all with shuffle on; and `SINGLE_REPEAT` to repeat-one with shuffle off. Persist per account if playback preferences are account-scoped. After Center, retain focus and show the new label. A short non-focus-stealing toast such as `已切换为随机播放` is appropriate when the resting button is icon-only; it must not be used as the sole state indication.

### Recommended queue overlay contract

- Render a right-side overlay over the full-bleed player with a restrained left scrim. Keep the underlying player alive and spatially unchanged. Use original dimensions, colors, icons, and row composition.
- Heading is `当前播放 (N)`, where `N = knownTotal ?: loadedItemCount`. Row numbers use `windowStart + localIndex + 1`; the current `itemCount/currentIndex` alone are insufficient for a sliding queue.
- Distinguish **current playback** from **remote focus**. The current row keeps a play/equalizer marker when focus moves elsewhere; focused rows get the normal product focus surface/scale.
- On open, suspend controller auto-hide, hide the action bar behind the panel, wait until rows are composed, scroll to the current local index, then request focus on that row. Center seeks to that queue item and keeps the panel open so users can compare or make another selection.
- Up/Down traverses rows without wrapping. Left/Right must not accidentally leave the panel. If removal is implemented, make the row the primary target and expose a trailing remove target through explicit Right/Left routing; otherwise omit the reference screenshot's `X` rather than render an inert affordance.
- Back closes the panel only, restores focus to `queue`, reveals the action bar, and restarts its inactivity timer. It must not pop the player route or toggle playback.
- Empty queue: do not open an empty panel; show a brief `当前播放列表为空` toast and retain queue-button focus. Load/page failures belong in a stable inline panel state with a focusable retry action, not a disappearing toast.

### Entry and return focus

| Destination | First-entry focus | Return behavior |
| --- | --- | --- |
| Home | Random-roam tile (first enabled content action) | Restore the last focused tile by stable key and row position. |
| My | First enabled item in the first populated media band | Restore band/item key and both vertical/horizontal list positions. |
| Playlist/album/all-tracks detail | `播放全部` when enabled; otherwise first playable track | Restore focused track GUID; fall back to the nearest enabled row. |
| Artist detail | First playable primary action; do not let async album arrival steal established focus | Restore the previously focused album/track key. |
| Paged artist/album/playlist grid | First enabled tile after the first non-empty result is composed | Restore item key and grid state; if missing after refresh, use the nearest enabled tile. |
| Player queue | Current queue row after composition | Back restores the queue action; leaving player restores the originating content key. |

Request initial focus exactly once per destination/data identity. Do not re-request merely because pagination changes collection size; that would pull focus away from the user. `focusProperties` must precede `focusRequester`, matching the existing project spec and controller implementation.

### Back and toast contract

Back must be a one-way unwind, never a toggle:

1. Close queue/style/other transient player overlay and restore its launcher focus.
2. If the controller is visible, hide it and remain on the player.
3. If the controller is hidden, pop Player and restore the source route's saved focus.
4. Pop detail/grid/settings routes normally.
5. From My, return to Home; Home is the fixed start/root destination.
6. From Home, allow the activity/system Back path to exit immediately.

Do **not** add `再按一次返回键退出`, an exit confirmation dialog, or an exit toast. Android TV guidance explicitly says not to gate exit and requires repeated Back to reach the system without loops. `MainActivity` currently has no custom Back interception and disconnects the UI controller only when finishing (`MainActivity.kt:10-26`); exiting the UI should not implicitly issue stop/clear-session.

Use toasts only for brief non-actionable acknowledgements such as a mode change or an empty queue. Use persistent inline state for actionable failures. Toasts never receive focus and never replace visible selected/current state.

### Verification required

- Device/AVD D-pad test: reveal controls, traverse the complete enabled row both directions, verify disabled actions are skipped, Up reaches progress, and progress Down returns to play/pause.
- Mode state test: default list repeat; three Center presses produce shuffle, single repeat, list repeat; verify Media3 repeat/shuffle flags and persistence.
- Queue device test: open at a nonzero sliding-window offset; assert header uses known total, absolute numbering is correct, current row is selected/focused, and Center seeks without dismissing.
- Queue Back test: open queue, move focus, press Back, assert player remains, action bar is visible, and queue action regains focus; next Back hides controls; next Back leaves player.
- Async focus tests: first load focuses the declared target; pagination does not steal focus; returning from detail/player restores the prior stable item key; missing/disabled restored keys fall back predictably.
- Root Back test: Back from My reaches Home; Back from Home finishes once with no toast/dialog and does not stop or clear the playback session.
- Screenshot tests at 1920x1080 and 1280x720: controller zones, progress, queue heading/rows, focus ring, ellipsis, and safe-area bounds do not overlap.

### External references

- [Android TV navigation](https://developer.android.com/training/tv/get-started/navigation) - predictable D-pad traversal, fixed start destination, direct Back behavior, and no exit gating.
- [Manage TV controllers](https://developer.android.com/training/tv/get-started/controllers) - Back is linear navigation and consecutive Back presses must eventually reach TV Home.
- [Compose focus behavior](https://developer.android.com/develop/ui/compose/touch-input/focus/change-focus-behavior) - explicit focus requests, directional overrides, and modifier precedence.
- [TV playback controls](https://developer.android.com/training/tv/playback/controls) - Center play/pause, seek, and control-reveal conventions.

### Related specs

- `.trellis/spec/frontend/android-tv-interaction.md:26-30` requires stable player requesters and play/pause entry focus.
- `.trellis/spec/frontend/android-tv-interaction.md:55-66` defines hidden-control reveal, explicit routing, modifier order, seeking, and Back-hides-controls behavior.
- `.trellis/spec/frontend/android-tv-interaction.md:88-94` defines the corresponding edge cases.
- `.trellis/spec/frontend/android-tv-interaction.md:119-129` defines player device-test expectations.

## Caveats / Not Found

- The task PRD is still a placeholder, so favorite, quality, removal, and account-scoping requirements are not established. The contract above excludes unsupported focusable actions rather than assuming them.
- Screenshots cannot distinguish a current-row marker from focus by themselves. The APK's select-current-then-request-focus sequence supplies the initial-focus evidence; the product should still render current and focused states independently.
- Static APK evidence cannot prove clean-install repeat-mode default or every DialogFragment dismissal callback. The recommended default and explicit focus restoration are product decisions.
- The queue UI cannot be implemented correctly from today's `PlaybackUiState`: full row metadata, `windowStart`, `knownTotal`, a seek-to-index command, and repeat/shuffle projection are missing.
- Reference APK code/resources and screenshots are research evidence only. Do not copy its assets, exact geometry, strings beyond generic platform terms, or decompiled implementation.
