# 图片加载无空白优化

## Goal

Eliminate transient empty artwork surfaces when entering artist, album, and similar music pages, while keeping image changes visually stable on TV displays.

## Requirements

- Reuse decoded artwork across composables and page re-entry instead of decoding the same exact cover variant repeatedly.
- Cache entries must be keyed by cover ID and exact `CoverVariant`; a Compact bitmap must never be displayed as a temporary Grid bitmap.
- Artist and album lockups must prefetch the exact Grid artwork when focused so the following detail page can render immediately when possible.
- While an exact bitmap is unavailable, every artwork surface must show a stable, meaningful placeholder rather than an empty solid rectangle.
- Cache memory must be bounded, concurrent requests for the same key must be deduplicated, and account/cache clearing must also clear decoded bitmaps.
- Existing raw artwork byte caching, image dimensions, navigation, and playback behavior must remain unchanged.

## Acceptance Criteria

- [x] Returning to a page with artwork already decoded shows the exact image on the first composition without an empty frame.
- [x] Opening a focused artist or album detail uses a prefetched Grid bitmap when it has completed.
- [x] Compact artwork is never substituted for Grid artwork, so no low-resolution-to-high-resolution sharpening transition occurs.
- [x] Initial network/cache misses show a stable placeholder until the exact bitmap is ready.
- [x] Concurrent consumers of the same cover ID and variant cause only one decode/load operation.
- [x] Decoded artwork cache is bounded and is cleared during account changes and user-triggered cache clearing.
- [x] Unit tests cover exact-variant separation, in-flight deduplication, prefetch, and clearing behavior.

## Notes

- Avoid crossfading between different image resolutions. Placeholder-to-exact-image replacement is acceptable.
- Prefer a single reusable cache/prefetch abstraction over page-specific state.
