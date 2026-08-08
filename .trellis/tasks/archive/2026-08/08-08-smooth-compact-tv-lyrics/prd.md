# Smooth compact TV lyrics

## Goal

Make word-timed lyrics feel continuous and make dense multilingual lyrics readable from a TV viewing
distance. Romanization must not consume player space, and long original/translation pairs must not
look stacked into one undifferentiated block.

## Background

- The poster and cover players currently render original, translation, and romanization inside every
  visible previous/current/next lyric slot in `PlayerScreen.kt`.
- At 1280x720, a long two-line original plus a two-line translation and romanization nearly fills the
  150 dp active slot, while adjacent slots start immediately above and below it. The supplied photo
  shows the resulting dense five-row center group and three-row inactive groups.
- Word highlighting currently consumes `PlaybackProgressState.positionMs`, which is published by
  `PlaybackController` every 250 ms. Interpolating opacity only at that cadence produces visible
  quarter-second steps even though Compose can draw on display frames.
- The typed lyric model and online cache may continue retaining romanization. This task changes player
  presentation only, so matching, cache compatibility, and future non-player consumers remain intact.

## Requirements

### R1. Remove romanization from player presentation

- Poster and cover player styles do not render romanization for active or inactive lyrics.
- Keep romanization in the provider-neutral lyric model, online matching result, and cache payload.
- Romanization remains supplemental and does not affect lyric quality tier selection.

### R2. Reduce multilingual density

- Follow the supplied reference composition: show exactly two stable semantic groups, current and
  next. Do not render the previous lyric.
- Both groups may show original plus translation. The next group is distinctly smaller and quieter
  than the current group.
- Add explicit vertical breathing room between the two groups rather than allowing their fixed bounds
  to touch.
- Keep the active original visually dominant and bound it to two lines with ellipsis as a last resort.
- Keep the active translation smaller and bound it to two lines.
- The next original and translation remain bounded, subdued context and must never overlap the active
  group.
- Poster and cover styles must remain readable at 1280x720 and 1920x1080, including long CJK and Latin
  lyrics.

### R3. Frame-smooth word progress

- Keep the controller's 250 ms global progress ticker unchanged so unrelated screens, snapshots, and
  controls do not begin recomposing at display refresh rate.
- Inside the visible lyric subtree only, extrapolate playback position from the latest controller
  snapshot on each display frame while playback is running.
- Pause, seek, track change, duration change, and lifecycle removal must immediately re-anchor or stop
  local interpolation without continuing a background frame loop.
- Ordinary line-timed lyrics retain whole-line emphasis and must not acquire fake word timing.

### R4. Compatibility

- Preserve online provider selection, FN fallback, word timestamps, translations, visual continuity,
  player controls, and both player styles.
- Do not add a dependency, database migration, cache protocol change, or global progress-frequency
  increase.

## Acceptance Criteria

- [ ] No romanization text is visible in either player style even when the lyric model contains it.
- [ ] Long Japanese/Chinese bilingual lyrics show one dominant active original and a subordinate
      translation with visible separation from the smaller next-line context at 1280x720.
- [ ] Current/next bounds do not overlap or touch incoherently during normal and rapid line changes;
      no previous lyric is visible.
- [ ] A word-timed line advances visually at display-frame cadence between 250 ms controller updates.
- [ ] Pausing freezes the interpolated position; seeking and track changes re-anchor without backward
      drift or a stale highlight.
- [ ] A normal LRC line remains uniformly highlighted at line level.
- [ ] Pure interpolation/layout tests, app unit tests, Android lint, sideload build, and TV emulator
      screenshots/video pass.

## Out of Scope

- Removing romanization from fetched data or serialized caches.
- Changing provider selection priority, adding lyric sources, manual lyric offsets, or lyric editing.
- Increasing the global playback ticker frequency.
