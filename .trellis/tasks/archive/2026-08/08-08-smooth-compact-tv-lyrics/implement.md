# Implementation plan

## Phase A: Presentation projection and density

- [x] Add a current/next lyric-index projection with timeline-boundary tests.
- [x] Replace poster and cover three-slot geometry with two separated fixed regions.
- [x] Remove romanization rendering from the shared player lyric item while retaining model data.
- [x] Tune current/next typography and line limits against the supplied 1280x720 reference.

## Phase B: Smooth local timing

- [x] Add a pure playback-position extrapolation helper with clamp/pause/backward-time tests.
- [x] Add a lyric-local frame loop that consumes the latest progress anchor only while playing.
- [x] Re-anchor on snapshot, seek, duration, and track identity changes without changing the global
      250 ms ticker.
- [x] Keep ordinary line-timed lyrics on uniform whole-line emphasis.

## Phase C: Verification

- [x] Run focused app UI-state tests.
- [x] Run the complete unit suite, Android lint, sideload debug assembly, and `git diff --check`.
- [x] Install the debug APK on Android TV API 36 and verify 1280x720 plus 1920x1080 screenshots.
- [x] Record a short word-timed segment and inspect sequential frames for continuous highlighting,
      stable current/next bounds, and absence of romanization/old-content stacking.

## Risk and rollback points

- Do not increase `PlaybackController.PROGRESS_TICK_MS` frequency.
- Do not delete romanization from data models or caches.
- Do not use an outgoing/incoming crossfade that keeps multiple lyric contents composed.
- Keep the per-frame state read below the player shell so the full screen and controls do not recompose
  at display refresh rate.
