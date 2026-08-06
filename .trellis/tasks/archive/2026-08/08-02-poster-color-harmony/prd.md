# 海报播放器颜色融合优化

## Goal

Make the poster player's right-side surface join the displayed artwork naturally while remaining
harmonious with the artwork as a whole and restrained on a large TV or projector.

## Requirements

- Produce one poster panel color, not independently selected seam and body colors.
- Derive the panel primarily from the complete artwork palette and use the artwork's right edge only
  as a bounded correction for the visual join.
- Reduce edge influence as its perceptual distance from the global artwork color increases, so a
  small saturated accent cannot recolor the whole panel.
- Do not impose a minimum saturation. Neutral artwork may produce a neutral panel.
- Cap panel chroma and brightness and preserve at least 4.8:1 contrast with the existing primary
  player text color.
- Keep the existing retained-artwork lifecycle and background decode ownership unchanged.
- Preserve a single-hue right surface; any far-edge variation may only darken the same panel color.

## Acceptance Criteria

- [x] A pale mixed cool cover with a small hot-pink accent produces a muted cool panel, not pink.
- [x] A mostly gray/dark cover with a small red accent remains neutral or subtly warm, not vivid red.
- [x] A globally warm cover still produces a recognizably warm but non-glare panel.
- [x] Panel chroma has no minimum and never exceeds the documented large-surface cap.
- [x] Primary text contrast against every generated panel is at least 4.8:1.
- [x] The poster transition uses the same panel hue from the artwork overlap through the right edge.
- [x] Existing ambience behavior for the standard player remains unchanged.
- [x] Unit tests, Android lint, and the signed sideload release build pass.

## Out Of Scope

- Changing lyric typography, player controls, artwork loading, or the standard disc-player layout.
- Reproducing a proprietary third-party color algorithm exactly.
