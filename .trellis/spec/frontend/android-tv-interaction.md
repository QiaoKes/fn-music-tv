# Android TV Interaction Contract

## 1. Scope / Trigger

Apply this contract to Compose surfaces controlled by a TV remote, especially text input,
focus graphs, dialogs, the 1920x1080 login surface, and transient player controls. A focused text field has two states:
remote browsing and system-keyboard editing. This prevents `BasicTextField` from consuming
D-pad navigation after it creates an input connection.

## 2. Signatures

The shared login field shape is:

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

Every interactive sibling owns a stable `FocusRequester`; directional neighbors are explicit
through both `focusProperties` and the field's preview-key dispatcher.

The player control row owns stable requesters for `previous`, `playPause`, `next`, and optional
`exitRoam`. When controls become visible, focus is requested on `playPause`.

The persistent player re-entry surface is:

```kotlin
NowPlayingPill(
    playback: PlaybackUiState,
    onClick: () -> Unit,
)
```

## 3. Contracts

- Browse state: the field is focused and `readOnly`; D-pad keys request the declared neighbor.
- Edit state: Enter/D-pad center is consumed on key-down and makes the field writable.
- The TV keyboard is shown after a 200 ms delay. This keeps the center-key release from selecting
  the keyboard's initially focused `q` key.
- Back hides the IME and returns to browse state without leaving the screen.
- `ImeAction.Next` ends editing and requests `downFocus`; Done ends editing in place.
- Losing field focus clears editing state. Password text is cleared before a login request and is
  never persisted.
- HTTP warning and login error share one fixed, single-line status slot. Do not insert a second
  dynamic row that can compress the login button below a 1080p safe area.
- TV text uses fixed `sp` sizes and fixed responsive breakpoints; do not scale fonts continuously
  with viewport width.
- With player controls hidden, the first directional key only reveals controls and is consumed;
  it must not seek or move to another action. Center toggles play/pause and reveals controls.
- With controls visible, use explicit left/right focus routing for previous -> play/pause -> next ->
  exit roam. Disabled previous/next actions must not become focus targets.
- For TV Material `Button`, declare D-pad neighbors with `focusProperties` before
  `focusRequester`. An outer `onPreviewKeyEvent` modifier does not reliably attach to the
  button's internal focus target on a real TV device.
- For a custom control that owns its own focus target, such as the Canvas progress bar, place
  `onPreviewKeyEvent` before `focusable()` and use it only for actions such as 10-second seek;
  declare Up/Down neighbors with `focusProperties` as well.
- The progress control consumes left/right to seek by 10 seconds. Back hides controls before it
  navigates away from the player.
- The Home/My now-playing entry is a compact music pill, not a generic title button. It shows a
  circular compact cover, playing/paused state, title, artist, and a trailing navigation cue.
  Focus changes border, surface, and scale without changing the pill's measured bounds.
- Archived HTML prototype measurements are physical pixels on a 1920x1080 canvas. Translate them
  through the target density before using Compose dimensions. At 320 dpi, the prototype's
  `372px x 74px` now-playing pill is `186dp x 37dp`; copying those pixel numbers as dp doubles its
  physical size and weakens the top-bar hierarchy.
- Roam next/previous actions are single-flight. A successful response replaces the one-item roam
  queue; exiting restores the frozen normal queue paused, or stops playback when no queue existed.

## 4. Validation & Error Matrix

| Condition | Required behavior |
| --- | --- |
| Center pressed in browse state | Consume it, enter edit state, show IME after 200 ms |
| Direction pressed in browse state | Move to the declared neighbor |
| Direction pressed in edit state | Leave it to the text editor/IME |
| Back while IME is visible | Hide IME, keep field focus, restore browse state |
| Login returns `Unauthenticated` after an attempt | Show `账号或密码错误` in the status slot |
| Restored token is unauthorized | Show the session-expired message |
| HTTP selected with no error | Show the unencrypted-LAN warning in the same slot |
| Direction pressed while player controls are hidden | Reveal controls, focus play/pause, consume key |
| Left/right pressed on visible player controls | Move through the explicit transport focus graph |
| Up pressed on a transport `Button` | Move to progress through `focusProperties`, not an outer key listener |
| Left/right pressed while progress is focused | Seek exactly 10 seconds and keep progress focused |
| Back pressed while player controls are visible | Hide controls and stay on the player |
| Exit roam with no frozen normal queue | Stop playback and return home |
| Now-playing title or artist is long | Ellipsize each field independently; keep cover, state, and chevron visible |
| Now-playing artwork is unavailable | Show the restrained circular fallback at the same size |

## 5. Good / Base / Bad Cases

- Good: center, type an address, Back, Down; account receives focus and the address has no prefix.
- Base: an empty password disables Login but leaves every prior control reachable.
- Good: hidden controls + Down + Right + Center invokes next exactly once.
- Good: focused now-playing pill gains a coral border without moving the Home/My navigation.
- Base: roam has no previous node, so previous remains disabled and play/pause is the first focus.
- Base: a paused track changes the state label and dot color but keeps the pill dimensions stable.
- Bad: showing the IME on center-key down without delay inserts `q` from the same key release.
- Bad: adding warning and error as separate conditional rows clips the Login label at 1080p.
- Bad: using the HTML prototype's `372 x 74` pixel size as dp on a 320 dpi target renders a
  `744 x 148` physical-pixel button.
- Bad: relying on geometric focus search for the compact transport row; focus can jump or activate
  play/pause after the user visibly selected next.

## 6. Tests Required

- Compose device test: assert initial server focus and the server -> account -> password ->
  visibility -> password -> remember-login -> HTTPS graph.
- Real TV/AVD system-key test: inject center, text, Back, and Down; assert exact text and focused node.
- Screenshot test at 1920x1080: assert headings, status, and the complete Login button are visible and
  non-overlapping in both base and error states.
- Player device test: hide controls, inject Down/Right/Center, and assert next replaces MediaSession
  metadata; repeat with Left for previous and Right/Right for exit roam.
- Player progress device test: hide controls, inject Down then Up, assert the progress node is
  focused; inject Right and assert position increases by 10 seconds; inject Down and assert
  play/pause is focused again.
- Player state test: exit roam restores the previous queue paused, and an empty frozen queue produces
  `STATE_NONE` with queue size zero.
- Home screenshot test at 1920x1080/320 dpi: assert the now-playing surface is 372x74 physical
  pixels, real artwork/fallback and metadata do not overlap, and focused/unfocused bounds are stable.
- Home device test: focus the now-playing pill, press Center once, and assert the immersive player
  title and progress semantics are present.
- Theme test: assert primary/muted/status colors maintain readable contrast on the root background.

## 7. Wrong vs Correct

```kotlin
// Wrong: the editable field owns D-pad navigation and the key release reaches the IME.
BasicTextField(readOnly = false, modifier = Modifier.onPreviewKeyEvent { false })

// Correct: browse first, consume remote navigation, then open the IME after the key cycle.
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
```

```kotlin
// Wrong: TV Material Button's internal focus target may bypass this outer handler.
Button(
    onClick = onNext,
    modifier = Modifier.onPreviewKeyEvent {
        nextFocus.requestFocus()
        true
    },
) { Text("Next") }

// Correct: route the Button's internal focus target through the focus graph.
Button(
    onClick = onNext,
    modifier = Modifier
        .focusProperties {
            left = playFocus
            right = exitRoamFocus
            up = progressFocus
            down = FocusRequester.Cancel
        }
        .focusRequester(nextFocus),
) { Text("Next") }
```
