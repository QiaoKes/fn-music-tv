# Smooth compact TV lyrics design

## 1. Boundaries

- `core:playback` keeps its existing 250 ms `PlaybackProgressState` publication cadence.
- `core:model`, `core:lyrics`, and `core:data` keep romanization and word timing unchanged.
- `app/ui/PlayerScreen.kt` owns the two-group composition, romanization suppression, and local
  frame-time interpolation.

## 2. Two-group composition

Replace the previous/current/next projection with current/next projection for both poster and cover
styles. Before the first timed line, the first line occupies the next role until it becomes active.
At the final line, the next role is empty.

Each mode uses a fixed-height lyrics panel split into two non-touching bounds:

- current: upper dominant region with up to two original rows and two translation rows;
- next: lower contextual region with up to two original rows and two translation rows;
- an explicit gap separates the regions so wrapping cannot visually merge them.

The shared lyric item renders only original and translation. Romanization remains in `LyricLine` but
is deliberately ignored by this player component. Current text uses the existing word emphasis;
next text is uniformly subdued and never receives karaoke progress.

Line changes replace slot content immediately and apply one short whole-window opacity recovery.
Outgoing content is never retained, preventing the interrupted-transition stacking defect.

## 3. Frame-smooth word timing

Add a lyric-local interpolated position composable. It reads the latest controller position as an
anchor and, while `isPlaying`, derives:

`anchorPositionMs + (frameObservedUptimeMs - anchorObservedUptimeMs)`

on each display frame, using Android's monotonic uptime clock and clamped to `0..durationMs`. The
controller snapshot remains authoritative:
every new snapshot, seek, duration change, pause, or track identity change creates a fresh anchor.

Use `rememberUpdatedState` so the frame loop sees new anchors without cancel/restart on every 250 ms
snapshot. Key the lyric-local state by the current media/presentation identity so a track change
cannot reuse a stale extrapolated position. When paused, no frame loop runs and the exact controller
position is rendered.

Keep interpolation inside the lyric subtree. Controls and other screens continue observing the
250 ms state, avoiding app-wide 60 Hz recomposition and snapshot churn.

Expose a pure interpolation function for boundary, paused, backward-frame, and duration-clamping
unit tests.

## 4. Compatibility and performance

- No cache schema, database, serialized model, provider, or network behavior changes.
- No new dependency and no new focusable or touch surface.
- Ordinary LRC lines have an empty word list, so the existing whole-line rendering path remains.
- The frame loop is lifecycle-bound to composition and playing state. It stops on pause or disposal.
- Only one active lyric `AnnotatedString` changes per frame; the next group and surrounding player
  geometry remain stable.

## 5. Validation

- Pure tests for current/next projection and interpolated position.
- UI-state tests that romanization is excluded from the player projection contract and long text
  retains fixed, separated bounds.
- Full app tests, Android lint, sideload debug assembly, and `git diff --check`.
- Android TV API 36 visual verification at 1280x720 and 1920x1080 using multilingual word-timed
  lyrics. Capture screenshots plus a short video/contact sheet across line transitions.

## 6. Rollback

The change is localized to the player composition and its pure helpers. Reverting the two-group
projection restores the prior layout; removing the local interpolation restores 250 ms highlighting
without touching playback state production.
