# Android TV Interaction Contract

## 1. Scope / Trigger

Apply this contract to every landscape Compose surface controlled by a TV remote or touch input: login fields,
asynchronously loaded grids/details/song lists/settings, Home/My navigation, the immersive player,
its transient controls, current-song presentation, and the right-side playback queue.

The contract prevents four recurring classes of defects:

- D-pad events being consumed by an editor or an implicit geometric focus search.
- A route opening with no focus, or returning from a child route with focus/scroll reset.
- a track switch publishing metadata, artwork, or lyrics from different revisions.
- a focused transient control or lazy row disappearing without handing focus to a live target.

## 2. Signatures

The shared login field shape remains:

```kotlin
TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    upFocus: FocusRequester? = null,
    downFocus: FocusRequester? = null,
    leftFocus: FocusRequester? = null,
    rightFocus: FocusRequester? = null,
)
```

Login options use a real toggleable focus target, while the adjacent field actions remain
icon-only TV Material buttons:

```kotlin
LoginCheckbox(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
)

LoginActionButton(
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)

HistoryIcon(enabled: Boolean)
VisibilityIcon(hidden: Boolean)
```

Touch keeps the same command callbacks: tapping a login field enters edit mode, tapping a hidden
player reveals its controls, and tapping progress calls the existing relative `onSeek` callback
with `targetPositionMs - currentPositionMs`.

The playback layer publishes one captured identity for the whole current-song presentation:

```kotlin
data class NowPlayingIdentity(
    val namespace: String,
    val mediaId: String,
    val presentationRevision: Long,
    val title: String,
    val artist: String,
    val audioFormat: String,
    val coverId: String?,
)

sealed interface NowPlayingResourceState<out T> {
    data object Loading : NowPlayingResourceState<Nothing>
    data class Ready<T>(val value: T) : NowPlayingResourceState<T>
    data object Absent : NowPlayingResourceState<Nothing>
    data class RetryableFailure(val error: AppError) : NowPlayingResourceState<Nothing>
}

data class NowPlayingPresentation(
    val identity: NowPlayingIdentity,
    val playerStyle: PlayerStyle,
    val metadata: NowPlayingResourceState<Track>,
    val artwork: NowPlayingResourceState<ByteArray>,
    val lyrics: NowPlayingResourceState<CurrentLyrics>,
)
```

`NowPlayingPresenter` is application-scoped, starts once, exposes
`StateFlow<NowPlayingPresentation?>`, and provides:

```kotlin
fun retryCurrentPresentation(): Boolean
fun refreshCurrentPresentation(): Boolean
```

The player owns stable requesters for `progress`, `previous`, `playPause`, `next`, `mode`,
`queue`, `exitRoam`, and `statusRetry`. Its normal side actions are icon-only and expose their
names through semantics:

```kotlin
PlayerControlOverlay(
    roaming: Boolean,
    playMode: PlayMode,
    queueCount: Int,
    modeFocus: FocusRequester,
    queueFocus: FocusRequester,
    // transport/progress/retry requesters and callbacks omitted
)

PlayerStatusRetryButton(
    focusRequester: FocusRequester,
    returnFocusRequester: FocusRequester,
    onInteraction: () -> Unit,
    onRetry: () -> Unit,
)

PlaybackQueueOverlay(
    items: List<PlaybackQueueItem>,
    loadedCount: Int,
    queueError: String?,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onSelect: (Int) -> Unit,
    onInteraction: () -> Unit,
)

fun initialQueueFocusIndex(items: List<PlaybackQueueItem>): Int
fun queueFocusTargetKey(
    requesterKeys: List<String>,
    currentIndex: Int,
    previouslyFocusedKey: String?,
): String?

fun isHomeBackConfirmed(
    previousBackAt: Long,
    currentBackAt: Long,
    windowMs: Long = 2_000L,
): Boolean
```

Artwork ambience, transient player visuals, and non-paged library summaries use deterministic
projections that remain unit-testable without rendering a screen:

```kotlin
data class ArtworkPaletteSwatch(val rgb: Int, val population: Int)
fun artworkAmbienceColor(swatches: List<ArtworkPaletteSwatch>): Color
fun fallbackAmbienceColor(): Color

fun <T> retainPlayerVisualResource(
    previous: NowPlayingResourceState<T>,
    current: NowPlayingResourceState<T>,
): NowPlayingResourceState<T>

data class RetainedListSnapshot<T>(
    val entries: List<T> = emptyList(),
    val error: AppError? = null,
    val initialLoadCompleted: Boolean = false,
)
```

The persistent player re-entry surface remains:

```kotlin
NowPlayingPill(playback: PlaybackUiState, onClick: () -> Unit)
```

## 3. Contracts

### Text input and focus modifiers

- Browse state is focused and `readOnly`; Enter/D-pad center is consumed on key-down and enters
  edit state. Show the TV keyboard after 200 ms so that the same center-key release cannot select
  its initially focused key.
- A field touch observes down/up in `PointerEventPass.Initial` without consuming either event. On
  release it enters edit state; the `BasicTextField` still receives the same gesture, gains focus,
  and the existing delayed keyboard request opens the device IME. A normal main-pass tap detector
  is insufficient because the text field may consume the gesture first.
- Back hides the IME and returns the field to browse state without leaving the route.
  `ImeAction.Next` ends editing and requests `downFocus`; Done ends editing in place. Losing field
  focus clears edit state.
- Every interactive sibling owns a stable `FocusRequester`. On TV Material controls, apply
  `focusProperties` before `focusRequester`; this ordering also applies to the transient retry
  button. Do not depend on an outer preview-key handler to reach a Material button's internal
  focus target.
- A custom focus target such as the Canvas progress control applies `focusProperties`, then
  `focusRequester`, then `onPreviewKeyEvent`, then `focusable()`.
- Password text is cleared before login and never persisted. HTTP warning and login error share
  one fixed single-line status slot so the Login button remains inside the 1080p safe area.
- Login has one TV-first layout for every landscape device. Do not branch on screen class or keep
  a separate phone tree. The same centered scrollable form uses the existing app palette,
  `widthIn(max = 720.dp)` before `fillMaxWidth(0.86f)`, and compact stable control heights so the
  complete Login button is visible at 1920x1080 while a smaller landscape viewport can scroll it.
- Keep Remember Login and HTTPS in one row as real `Modifier.toggleable` checkbox targets with
  `Role.Checkbox`, a visible square check mark, stable content descriptions `保持登录` and `HTTPS`,
  and explicit D-pad neighbors. Do not simulate them with `ON`/`OFF` text in generic buttons.
- History and password visibility are 52dp icon-only buttons. Use familiar recent-history and eye
  glyphs, preserve content descriptions `历史` and `显示或隐藏密码`, and do not place `历史`,
  `显示密码`, or `隐藏密码` inside the buttons.
- Login form commands use the local foundation-based `LoginActionButton`, including history,
  password visibility, login submission, and history-dialog actions. It owns a real focus target
  plus `Modifier.clickable(Role.Button)` so the same callback works for D-pad and physical pointer
  input inside the vertically scrollable form. Do not substitute TV Material `Button` on this
  surface; it was observed to focus correctly while dropping physical pointer clicks.
- Center field content vertically through `BasicTextField.decorationBox` with a full-height
  `Box(contentAlignment = Alignment.CenterStart)`. Center all command labels explicitly with
  `TextAlign.Center`; padding estimates are not a vertical-centering contract.

### Current-song presentation

- Capture `mediaId`, title, artist, format, cover ID, and artwork URI from the same
  `currentMediaItem.mediaMetadata` snapshot. Increment `presentationRevision` whenever the media
  item changes or any captured presentation field changes materially.
- The publication identity is exactly `(namespace, mediaId, presentationRevision)`. A new identity
  immediately creates independent `Loading` states for metadata, artwork, and lyrics and cancels
  the previous presentation job. The UI renders a presenter value only when its complete
  `NowPlayingIdentity` equals the playback state's expected identity.
- Metadata, artwork, and lyrics may finish independently, but a result publishes only while its
  presentation token and player style are still current. Late A or B completions in an A -> B -> A
  sequence must never replace the newest A revision.
- Presenter publication and UI transition ownership are separate. The presenter still publishes
  only the exact current identity. While the new current resource is `Loading`, the player may keep
  the previous same-namespace `Ready`/`Absent` lyric visual and previous decoded artwork. Replace it
  immediately when the current resource reaches `Ready`, `Absent`, or `RetryableFailure`; never feed
  a retained visual back into the presenter or treat it as current resource state.
- Decoded artwork keeps its complete `PlayerArtworkKey`. A new ready byte array is decoded and
  quantized on `Dispatchers.Default`; the old decoded artwork and ambience remain visible until that
  work completes. A current terminal artwork state clears the old visual immediately.
- Generate artwork swatches with AndroidX Palette and choose the ambience seed by a deterministic
  score that combines population, saturation, and useful lightness. A large neutral field must not
  automatically defeat a smaller representative color. Map the result to a bounded dark surface;
  missing artwork uses one fixed brand-neutral fallback, never a title/artist hash color.
- Poster mode generates one panel color from separate complete-artwork and right-edge palettes. The
  complete artwork is the anchor; the edge may contribute at most 35 percent and contributes less
  as its OKLab distance from the global anchor increases. Use population-linear perceptual
  averaging with no saturation reward or minimum saturation, cap OKLab chroma at 0.09, constrain
  lightness and preserve at least 4.8:1 primary-text contrast. Fade the artwork into that single
  panel hue; the far edge may only mix in a small amount of the app background.
- `Ready` renders the resource; `Absent` is a valid terminal fallback; only
  `RetryableFailure` exposes retry. A retry reloads only failed resources and remains bound to the
  same identity. Missing `coverId` may be enriched from full metadata, which creates a new revision
  before the newly identified artwork publishes.
- `NowPlayingPresenter` lives in the application container, not an Activity or player Composable,
  so navigation and Activity recreation do not restart or detach current resource ownership.
- A retry action may remove its own button. On click, request `returnFocusRequester` before invoking
  `onRetry`; after removal, progress is focused and Down returns to play/pause.

### Player controls, modes, and queue

- Hidden controls consume the first directional key, reveal controls, and focus play/pause without
  seeking or selecting an action. Center toggles play/pause and reveals controls.
- A tap on the player while controls are hidden reveals them without toggling playback. Visible
  Material buttons retain their normal click handling. Tapping progress seeks to the proportional
  timeline position through the same bounded relative seek callback used by D-pad input.
- Visible normal controls have an explicit left/right graph:
  mode -> previous -> play/pause -> next -> queue. Disabled previous/next controls are skipped and
  are not focus targets. Up routes to progress; progress Left/Right seeks exactly 10 seconds.
- Normal playback supports `ListRepeat`, `Shuffle`, `SingleRepeat`, and `Sequence`. The mode button
  cycles in that order and immediately updates its icon and content description.
- Mode and queue buttons use familiar music glyphs only; do not render labels such as `列表循环`
  or `队列 5` inside them. Their semantics remain `播放模式：<模式名>` and
  `播放队列，共 <count> 首`, so tests and accessibility do not infer meaning from pixels.
- Roam hides the normal mode and queue controls entirely. Its graph reaches `退出漫游` after the
  available transport controls; normal queue/repeat rules do not affect roam.
- Roam next/previous and natural completion share one single-flight transition. A successful
  response replaces the one-item roam queue; exit restores the frozen normal queue paused, or
  stops playback and returns Home when no frozen queue existed.
- The queue is a right-side overlay on the player, not a route. It shows `loadedCount`, continuous
  row numbers, title, artist, and the current marker. Opening scrolls to and focuses the current
  row; selecting a row calls `onSelect(item.queueIndex)` and keeps focus in the overlay.
- Fixed-height text controls do not rely on TV Material's default content padding for Chinese font
  centering. The roam exit label and queue retry label use a full-size centered `Box`. Queue rows use
  zero button content padding plus a full-size vertically centered `Row`; the title/artist column has
  explicit line heights and `Arrangement.Center`. Keep outer bounds and focus modifiers unchanged.
- Queue row keys are occurrence-safe (`"$mediaId:$occurrence"`). Each `LazyColumn` row creates
  `remember(rowKey) { FocusRequester() }` inside the keyed row and requests focus from a
  row-owned `LaunchedEffect`; never retain detached row requesters in an overlay-level map.
- On a queue mutation, preserve the previously focused row when its key remains. If it disappears,
  scroll to and focus the new current row; if there is no current row, focus the first row. An empty
  queue has no row focus target.
- Back handling is layered: close queue -> hide controls -> leave player. Closing the queue restores
  focus to its queue icon. Returning focus is requested only after the disappearing overlay has
  left composition.

### Async routes, return behavior, and layout

- The user-facing product name is `回声台`. `@string/app_name`, loading/login/top-bar text,
  launcher label, baseline-profile selectors, README title, launcher icon, and TV banner must move
  together. The icon uses a charcoal background, a coral primary waveform, and a warm-white echo
  waveform. Keep the same flat double-wave mark in both `ic_logo.xml` and `tv_banner.xml`; do not
  add the retired teal node or play triangle. Internal package and command namespaces stay
  `com.fnmusic.tv` so a visual rebrand remains an in-place signed Android upgrade.
- On the first entry to a grid, detail, song list, or settings route, wait until the relevant async
  content has reached a terminal initial-load state, then focus the first actionable item in reading
  order. Do not request focus against a placeholder or an item not yet composed.
- Persist route-local `focusedKey`, scroll state, loaded entries/pages, and continuation metadata.
  Returning from a child route restores the retained key and scroll position; it must not refetch
  page 1 or force focus back to the first item. If the retained key no longer exists, use that
  surface's deterministic first-action fallback.
- Home playlists and All Playlists share one session-owned `RetainedListSnapshot`. My and the full
  Artists/Albums grids share the same retained paged snapshots; shared libraries use another
  retained list. A successful initial load is not repeated on route re-entry. An empty failed list
  may retry on the next entry. The store is keyed by the signed-in user and must be discarded when
  the account changes.
- Remote artwork keeps fixed bounds and renders a deterministic placeholder until its exact
  `CoverVariant` bitmap is ready. Initialize Compose state from the application decoded cache so a
  page return does not flash the placeholder. Artist/album lockup focus may prefetch the exact Grid
  entry for the destination detail page, but the Compact list image must never be displayed as a
  temporary Grid image; this avoids a visible low-resolution-to-high-resolution sharpening step.
- Home/My player re-entry is a compact music pill with fixed measured bounds, cover/fallback,
  playing state, ellipsized title/artist, and a trailing cue. Focus may change border, surface, and
  scale without reflow. Its compact status row disables Android font padding, while the bold title
  enables it so fallback-script glyphs receive their full line box instead of being squeezed by
  invisible status-row font metrics.
- At a root route, Back from My replaces the root with Home. At Home, the first Back shows
  `再按一次退出`; a second Back within 2,000 ms saves a paused playback snapshot, stops playback and
  its service, removes the task, and exits the process. It must not clear the saved queue, account
  data, preferences, or caches. After the window expires, the next Back is a new first press.
- Use fixed `sp` sizes and explicit responsive breakpoints, never viewport-scaled text. The compact
  now-playing pill is `186dp` wide and `42dp` high at the default font scale. For larger configured
  font scales, increase only its height by `28dp` per additional `1.0` scale so both font-padded rows
  remain visible; do not use unconstrained intrinsic height with a fill-sized child.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Center in text-field browse state | Consume key-down, enter edit state, show IME after 200 ms |
| Touch release on a login field | Enter edit state, retain text-field focus, show the device IME |
| Direction in browse/edit state | Browse requests the declared neighbor; edit leaves it to editor/IME |
| Back while IME is visible | Hide IME, retain field focus, restore browse state |
| Login returns `Unauthenticated` after an attempt | Show `账号或密码错误` in the fixed status slot |
| A restored token is unauthorized | Show the session-expired message in the fixed status slot |
| HTTP is selected without another login error | Show the unencrypted-LAN warning in the same slot |
| Login is rendered on any landscape device | Use the same centered form tree; no device-specific alternate UI |
| Remember Login or HTTPS is activated by touch/Center | Toggle its `ToggleableState` and keep a valid focus target |
| History/password side action is rendered | Show the 52dp familiar icon only and retain its exact content description |
| Physical pointer taps an enabled Login action | Invoke its command exactly once; Login clears the submitted password and starts submission |
| Physical pointer taps a disabled Login action | Do not invoke its command or create a focus target |
| 1920x1080 login first frame | Show the complete Login button with no clipped bottom edge or overlapping control |
| Launcher/app surface after rebrand | Display `回声台`; icon and TV banner share the coral/warm-white double-wave mark |
| Existing signed installation receives the rebrand | Preserve `com.fnmusic.tv` and signer; increment managed version code |
| New presentation identity/revision | Publish three `Loading` states and cancel the prior token |
| Late resource result has an old namespace/media/revision/style | Ignore it; current UI state is unchanged |
| Current artwork is Loading during a track switch | Keep the prior decoded artwork/background until current decode completes |
| Poster right-edge palette differs sharply from the complete-artwork palette | Reduce edge influence toward zero and keep the panel anchored to the complete artwork |
| Poster edge palette is empty | Generate the single panel color from the complete-artwork palette |
| Poster complete-artwork palette is empty | Use the fixed restrained brand-neutral panel fallback |
| Current lyrics is Loading during a track switch | Keep the prior same-namespace renderable lyric state; do not flash loading/absent copy |
| Metadata/artwork/lyrics is validly absent | Publish fallback/`Absent`; do not show retry |
| One or more current resources exhaust retryable failure | Show one retry action bound to the current revision |
| Focused retry action succeeds and disappears | Hand focus to progress before removal; Down reaches play/pause |
| Direction while controls are hidden | Reveal controls, focus play/pause, consume the key |
| Tap while player controls are hidden | Reveal controls; do not toggle playback or seek |
| Tap the visible progress track | Seek to the tapped proportional position and reset the hide timer |
| Normal mode button is activated | Cycle ListRepeat -> Shuffle -> SingleRepeat -> Sequence -> ListRepeat |
| Mode or queue action is rendered | Show a familiar icon only; retain the exact content description |
| Player enters roam | Remove normal mode and queue nodes from composition and the focus graph |
| Exit roam with no frozen normal queue | Stop playback and return Home |
| Queue opens with a current row | Scroll to and focus that row; keep it visible |
| Focused queue row survives a mutation | Preserve focus by occurrence-safe row key |
| Focused queue row is deleted | Focus the new current row, otherwise the first row |
| Queue row is selected | Play `queueIndex`, update current marker, keep overlay focus valid |
| Roam label or queue text is measured | Its content group is vertically centered inside the fixed button/row bounds |
| Back with queue / controls visible | Close queue first; otherwise hide controls; do not leave player early |
| Async route first load completes | Focus its first actionable content item exactly once |
| Return to a retained route | Restore prior focus, scroll, pages, and continuation metadata |
| Return to Home/My/All with successful retained summary data | Render retained entries on the first frame; do not request page 1 again |
| Exact artwork bitmap is already decoded | Render it on the first composition without an empty/placeholder frame |
| Detail Grid artwork is still loading | Keep the fixed deterministic placeholder; never substitute the Compact list bitmap |
| Artist/album lockup receives focus | Prefetch its exact Grid artwork without changing the displayed Compact artwork |
| Back at My root | Replace My with Home; do not background the task |
| First Back at Home | Show confirmation only; playback and task remain active |
| Second Back within 2,000 ms | Save paused state, stop playback/service, remove the task, and exit without clearing account data |
| Back after more than 2,000 ms | Show confirmation and begin a new window |
| Long title/artist or compact viewport | Ellipsize independently; icons, controls, and queue rows do not overlap |
| Current artwork is absent | Keep the same artwork bounds and render the restrained fallback |

## 5. Good / Base / Bad Cases

- Good: enter an async album page, wait for data, and focus its first album; open a child and Back,
  then restore the exact album and horizontal scroll rather than returning to index zero.
- Good: focus an album card, keep its Compact image unchanged, then open detail and immediately use
  the independently prefetched Grid bitmap when available.
- Good: tap Account, type with a phone IME, tap the center of a three-minute progress track from
  12 seconds, and request a relative seek of about 78 seconds without changing TV focus contracts.
- Good: render the same centered login form on TV and a smaller landscape device; the TV shows the
  complete form initially, while the smaller viewport scrolls the same tree to the Login button.
- Good: install `回声台` over the previous signed package and preserve app data because the package
  name and signer are unchanged while the version code increases.
- Good: switch A(rev 1) -> B(rev 2) -> A(rev 3), complete requests in reverse order, and display
  only A rev 3 metadata, artwork, and lyrics.
- Good: switch A -> B, keep A's decoded cover and lyrics only while B is Loading, then replace each
  independently as B reaches its current terminal state without showing a placeholder frame.
- Good: a violet subject occupies less area than a pale neutral backdrop; Palette quantization plus
  scoring selects the representative violet hue and maps it to a dark readable background.
- Good: a mostly gray poster with a small red edge accent stays neutral or subtly warm; the artwork
  overlap fades into the same restrained panel hue used by the right half.
- Good: focus a retryable player error, press Center, transfer focus to progress, remove the retry
  button, then press Down to reach play/pause.
- Good: traverse icon-only mode -> previous -> play/pause -> next -> queue using D-pad; every icon
  has a stable content description and activating mode resets the control-hide timer.
- Good: focus queue row C, append/reindex rows while C remains, and retain C; then delete C and move
  focus to the new current row after it is composed.
- Base: no current queue row focuses the first row; an empty queue owns no row requester.
- Base: roam has no normal mode/queue nodes and skips a disabled previous action.
- Base: My Back returns Home; Home Back once only shows the confirmation while music continues.
- Good: confirm Home exit, durably save the current queue and position as paused, then stop the
  player/service and remove the app task without clearing login, preferences, or caches.
- Base: an empty password disables Login while all preceding controls remain reachable.
- Base: unavailable server history disables the history icon but keeps its stable 52dp bounds.
- Base: a paused track changes its state treatment without changing the now-playing pill bounds.
- Bad: keying artwork only by cover ID; two songs sharing a cover can reuse a failed or stale attempt.
- Bad: clearing decoded artwork or renderable lyrics merely because the new exact identity first
  projects `Loading`; this creates a visible placeholder flash even though a stable visual exists.
- Bad: generating missing-artwork ambience from title/artist hashes; the background is unrelated to
  the image and can jump to a misleading hue between tracks.
- Bad: selecting one highly saturated poster swatch, forcing a minimum saturation, and painting it
  across the right half; a small red or pink accent becomes a glaring unrelated panel.
- Bad: keeping every lazy row requester in an overlay-level map; a deleted/off-screen row leaves a
  requester detached from the focus tree.
- Bad: drawing `列表循环` and `队列 5` as side-button text; it wastes TV control width and makes
  familiar actions harder to scan.
- Bad: requesting first focus before data is composed, or always requesting index zero after Back.
- Bad: drawing an empty solid rectangle while artwork loads, or stretching a cached Compact bitmap
  into detail before swapping to Grid.
- Bad: showing the IME on center-key down without delay and letting the matching key-up enter the
  keyboard's initially focused character.
- Bad: relying on a main-pass `detectTapGestures` outside `BasicTextField`; its own pointer input can
  consume the gesture first and leave the field read-only.
- Bad: rendering login warning and error as separate dynamic rows and clipping Login at 1080p.
- Bad: adding separate TV/phone login Composables or conditionally removing controls for a phone.
- Bad: rendering `ON 保持登录`, `OFF HTTPS`, `历史`, or `显示密码` as large text pills.
- Bad: testing Login with semantics `performClick()` only; it bypasses the physical pointer path
  that previously failed on TV Material buttons inside the scrollable form.
- Bad: renaming the launcher label but leaving old branding in loading, login, top bar, banner,
  baseline-profile selectors, or README; changing `applicationId` during this visual rebrand.
- Bad: copying physical-pixel prototype measurements directly as dp at 320 dpi and doubling the
  intended on-screen surface.

## 6. Tests Required

- Presenter unit test: drive A -> B -> A with distinct revisions and reverse completion order;
  assert only the latest identity publishes metadata, artwork, and lyrics. Cover enrichment must
  create a fresh revision, same-cover songs must make independent attempts, and retry must reload
  only `RetryableFailure` resources.
- Presenter/UI projection test: assert a presentation is accepted only when its complete identity
  equals `PlaybackUiState.nowPlayingIdentity`; old namespace/media/revision values project Loading.
- Login Compose/device test: assert initial server focus and the server -> account -> password ->
  visibility -> remember-login -> HTTPS graph. Inject Center, text, Back, and Down and assert exact
  text plus focused node.
- Login touch test: inject a real pointer click on Account, then assert it is focused, exposes text
  editing, and accepts injected text without a D-pad center event.
- Login command touch test: enter a valid server, account, and password, inject a real pointer click
  on the enabled Login button, and assert the suspend login callback runs exactly once. Do not use
  semantics `performClick()` as a substitute for this regression path.
- Login command D-pad test: from the same valid state, focus Login through the declared graph,
  press Center, and assert the same callback runs exactly once.
- Login option test: activate Remember Login through semantics/touch and assert its
  `ToggleableState` changes from On to Off. D-pad traversal must still reach Remember Login and
  HTTPS in the declared order.
- Login screenshot test at 1920x1080: assert headings, the shared base/error status slot, and the
  complete Login button are visible and non-overlapping. Assert history/password actions are
  icon-only, field text is vertically centered, and no legacy left-side branding/equalizer remains.
- Route state tests: assert first actionable focus after async initial load; return from a child with
  the same focused key/scroll; retained page 2 does not request page 1; a removed key uses the
  deterministic fallback.
- Retained summary tests: a successful list snapshot prevents another initial request, while an
  empty failed snapshot is retryable on the next route entry. Home/My/full grids consume the same
  user-scoped store keys.
- Artwork continuity tests: exact decoded hits are available synchronously, Compact and Grid stay
  isolated, focused artist/album items request Grid prefetch, and a miss retains stable bounds and
  placeholder content.
- Artwork ambience tests: a colorful minority swatch beats a large neutral backdrop, black margins
  do not defeat a valid color, every mapped surface stays dark, and missing artwork returns the one
  fixed brand neutral.
- Poster panel color tests: a divergent right-edge accent stays closer to the complete-artwork
  result than a related edge correction, neutral artwork stays neutral, a warm cover remains warm,
  OKLab chroma is at most 0.09, and primary-text contrast is at least 4.8:1.
- Player visual transition tests: `Loading` retains only prior `Ready`/`Absent` visuals; current
  `Ready`, `Absent`, and `RetryableFailure` replace immediately. A namespace change retains nothing.
- Player device test: assert the normal icon-only graph reaches all transports, mode, and queue;
  no visible mode/queue labels exist; content descriptions identify all four modes and the queue;
  four activations complete the mode cycle.
- Retry device test: navigate play/pause -> progress -> retry, activate it, assert the retry node is
  removed and progress is focused, then assert Down restores play/pause.
- Queue device test: opening focuses current, selection uses the item's real `queueIndex`, a retained
  row keeps focus across mutation, and deleting the focused row moves focus to the new current row.
- Queue/roam bounds test: compare unmerged text bounds with the owning semantics bounds and assert
  the roam label center and queue title/artist group center match their fixed-height containers.
- Back device test: assert queue -> controls -> player ordering, My -> Home, one Home Back only shows
  the prompt, and the confirmed Back saves paused playback, stops the MediaSession service, and
  removes the task.
- Roam device test: assert mode and queue semantics are absent, disabled transports are skipped, and
  the remaining graph reaches exit roam.
- Player progress device test: reveal controls, move Up from play/pause, seek Right, and assert the
  progress value increases by exactly 10 seconds while focus stays on progress; Down returns to
  play/pause.
- Player touch test: click the center of the progress node at 12/180 seconds and assert the existing
  relative seek callback receives approximately +78 seconds.
- Player state test: exit roam restores the previous queue paused, while no frozen queue produces
  `STATE_NONE` with an empty queue.
- Capture and inspect player-controls and player-queue screenshots on an Android TV API 36 target at
  both 1920x1080 and 1280x720 (320 dpi). Assert nonblank output, no clipped/overlapping text, stable
  icon/control bounds, readable focus state, current-row visibility, and a right-side queue that does
  not obscure required player controls incoherently.
- Screenshot test at 1920x1080 also covers login base/error and the Home now-playing pill; assert the
  complete Login button is visible, the pill remains `372` physical pixels wide with at least `74`
  physical pixels of height at 320 dpi, and no lower title glyph is clipped at the configured font
  scale. Inspect rendered title pixels, not only semantic bounds.
- Brand resource check: search user-facing sources for the retired product name, assert the merged
  manifest label resolves to `回声台`, visually inspect the double-wave mark at launcher size, and
  verify the newly versioned signed APK installs with replace over the prior package.
- Home device test: focus the now-playing pill, press Center once, and assert the player title and
  progress semantics are present. Theme tests keep primary, muted, and status colors readable on
  the root background.

## 7. Wrong vs Correct

```kotlin
// Wrong: one vivid accent independently decides the full poster panel.
val panel = palette.swatches.maxBy(::saturation).rgb

// Correct: the complete artwork anchors one restrained surface; the edge is bounded by agreement.
val panel = artworkPosterSurfaceColor(globalSwatches, edgeSwatches)

// Wrong: an editable field owns D-pad navigation and the key release reaches the IME.
BasicTextField(readOnly = false, modifier = Modifier.onPreviewKeyEvent { false })

// Correct: browse first, consume center key-down, then open the IME after the key cycle.
if (event.key == Key.DirectionCenter && event.type == KeyEventType.KeyDown) {
    editing = true
    true
}
LaunchedEffect(editing) {
    if (editing) {
        delay(200)
        keyboard?.show()
    }
}

// Touch observes the field gesture before its internal text input consumes the main pass.
Modifier.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) editing = true
    }
}
```

```kotlin
// Wrong: ordering and an outer key listener do not reliably route a Material Button on TV.
Button(
    modifier = Modifier
        .focusRequester(nextFocus)
        .onPreviewKeyEvent { playFocus.requestFocus(); true },
    onClick = onNext,
) { Text("Next") }

// Correct: declare neighbors before attaching the requester's Material focus target.
Button(
    modifier = Modifier
        .focusProperties { left = playFocus; right = queueFocus; up = progressFocus }
        .focusRequester(nextFocus),
    onClick = onNext,
) { PlayerTransportIcon(TransportGlyph.Next) }
```

```kotlin
// Wrong: keep a requester after its LazyColumn row may have left composition.
val rowRequesters = remember { mutableMapOf<String, FocusRequester>() }
val requester = rowRequesters.getOrPut(rowKey) { FocusRequester() }

// Correct: the keyed row owns the requester and requests focus only while composed.
itemsIndexed(items, key = { index, _ -> requesterKeys[index] }) { index, item ->
    val rowKey = requesterKeys[index]
    val requester = remember(rowKey) { FocusRequester() }
    LaunchedEffect(targetKey, rowKey) {
        if (targetKey == rowKey) requester.requestFocus()
    }
    QueueRow(
        item = item,
        modifier = Modifier
            .focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
            .focusRequester(requester),
    )
}
```

```kotlin
// Wrong: retry first; recomposition can remove the only focused node.
onClick = { onRetry() }

// Correct: hand focus to a live target before retry removes its button.
onClick = {
    onInteraction()
    returnFocusRequester.requestFocus()
    onRetry()
}
```

```kotlin
// Wrong: visible text is the only identification for compact mode/queue actions.
PlayerSideActionButton(label = "列表循环", description = "")

// Correct: use the familiar glyph and retain an exact accessibility/test contract.
PlayerSideActionButton(
    glyph = playModeGlyph(PlayMode.ListRepeat),
    description = "播放模式：列表循环",
)
```

```kotlin
// Wrong: device branches create two login surfaces and text pills fake checkbox/icon controls.
if (isPhone) PhoneLogin() else TvLogin()
Button(onClick = onRemember) { Text("ON 保持登录") }
Button(onClick = onHistory) { Text("历史") }

// Correct: one flexible TV-first form and semantic icon/checkbox controls serve landscape devices.
Column(
    Modifier
        .widthIn(max = 720.dp)
        .fillMaxWidth(0.86f)
        .verticalScroll(rememberScrollState()),
) { /* shared login fields */ }

Row(
    Modifier.toggleable(
        value = rememberLogin,
        role = Role.Checkbox,
        onValueChange = { rememberLogin = it },
    ),
) { CheckboxMark(rememberLogin); Text("保持登录") }

Button(
    modifier = Modifier.size(52.dp).semantics { contentDescription = "历史" },
    onClick = onHistory,
) { HistoryIcon() }
```

```xml
<!-- Wrong: launcher text changes while in-app branding and upgrade identity drift. -->
<string name="app_name">回声台</string>
<!-- applicationId = "com.example.echostage" -->

<!-- Correct: update all user-facing brand resources but preserve the installed identity. -->
<string name="app_name">回声台</string>
<!-- applicationId remains com.fnmusic.tv; versionCode increases for the formal release. -->
```

```kotlin
// Wrong: exact-identity Loading is treated as a command to erase every stable visual.
val artworkBitmap = readyArtwork?.let(::decodeArtwork)
val lyrics = (presentation.lyrics as? Ready)?.value

// Correct: identity validation remains strict, while the UI retains renderable visuals only
// during the new current resource's Loading window.
val displayedLyrics = retainPlayerVisualResource(previousTerminal, presentation.lyrics)
val decoded by produceState<DecodedPlayerArtwork?>(null, request?.key, request?.bytes) {
    val current = request ?: return@produceState // keep the existing value while Loading
    value = withContext(Dispatchers.Default) { decodeAndExtractAmbience(current) }
}
```

```kotlin
// Wrong: use a list thumbnail as the first detail frame, then visibly sharpen it.
RemoteArtwork(coverId, CoverVariant.Grid, fallback = cachedCompact)

// Correct: prefetch Grid on focus and keep a stable placeholder until that exact image is ready.
onFocusChanged { if (it.isFocused) artworkBitmapCache.prefetch(coverId, CoverVariant.Grid) }
RemoteArtwork(coverId, CoverVariant.Grid, placeholder = stableArtworkPlaceholder)
```

```kotlin
// Wrong: padding guesses make a Chinese label look off-center inside a fixed TV button.
Button(contentPadding = PaddingValues(vertical = 8.dp)) { Text("退出漫游") }

// Correct: preserve the fixed outer target and center an explicit full-size content box.
Button(contentPadding = PaddingValues(0.dp)) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("退出漫游", lineHeight = 14.sp)
    }
}
```
