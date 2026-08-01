# Redesign Home Now-Playing Banner

## Goal

Make the persistent player entry at the upper-left of Home and My feel like a
compact music surface instead of a generic text button, while preserving fast and
predictable TV remote access to the immersive player.

## Background

- The current Compose implementation is a `360dp x 62dp` button containing only
  the track title (`AuthenticatedApp.kt`, `LibraryTopBar`).
- The approved HTML prototype defines the upper-left entry as a compact pill with
  a circular cover, playback state, title, artist, and trailing chevron. Its
  1920x1080 baseline is `372px x 74px`.
- The archived NetEase TV Home reference uses the same information hierarchy:
  circular artwork plus title and artist in a quiet translucent pill.
- The target TV AVD is 1920x1080 at 320 dpi, so HTML pixel measurements must be
  translated to physical Compose dimensions rather than copied as dp values.

## Requirements

- R1. Replace the generic player button in `LibraryTopBar` with a dedicated
  now-playing pill shown whenever `playback.hasMedia` is true.
- R2. Show real current-track artwork as a circular 54px-equivalent thumbnail.
  Derive the cover ID from `PlaybackUiState.artworkUrl`; use the existing compact
  artwork loader and a restrained fallback when artwork is unavailable.
- R3. Show playback state (`正在播放` or `已暂停`), track title, artist, and a
  trailing chevron in the hierarchy established by the HTML prototype.
- R4. Match the prototype's physical scale, dark neutral surface, subtle border,
  and restrained coral status accent. Do not reproduce NetEase branding, assets,
  or its full navigation system.
- R5. The focused state must be clearly visible through a modest scale increase,
  brighter border/surface, and unchanged internal layout; it must not reflow the
  top bar.
- R6. Center/Enter continues to open the existing immersive player route. Existing
  Home/My navigation and focus behavior remain intact.
- R7. Long title and artist values remain single-line and ellipsize independently.

## Acceptance Criteria

- [x] At 1920x1080/320 dpi, the banner reads as a compact music pill rather than a
  720px-wide generic action button.
- [x] The pill visibly contains circular artwork, state, title, artist, and a
  trailing navigation cue without clipping or overlap.
- [x] Playing and paused states are distinguishable without changing dimensions.
- [x] Focus and unfocused screenshots show a clear, stable TV focus treatment.
- [x] Clicking the pill opens the current immersive player exactly once.
- [x] Home playlist tiles, My shelves, navigation labels, and player layouts are
  visually and behaviorally unchanged.
- [x] Sideload unit tests, APK assembly, lint, and `git diff --check` pass.

## Out Of Scope

- Redesigning Home content rails, Home/My navigation, the immersive player, or
  playback queue behavior.
- Copying NetEase logos, artwork, color tokens, or destination labels.
- Adding transport controls to the top-bar pill.

## Technical Notes

- Reuse `RemoteArtwork`, `CoverVariant.Compact`, and the existing `artworkUrl`
  cover-ID parsing pattern from `ImmersivePlayer`.
- Keep this a lightweight, single-file UI task; no data-model or API changes are
  required.
