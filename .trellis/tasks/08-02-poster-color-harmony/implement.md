# Implementation Plan

1. Extend artwork extraction to build complete and right-edge Palette swatches.
2. Replace single-swatch winner selection with the pure global-anchor/edge-correction OKLab
   projection and restrained tone mapping.
3. Keep poster rendering on one selected hue and reduce far-edge brightness only through the app
   background mix.
4. Replace prior poster-color unit tests with global harmony, accent rejection, neutral-cover,
   warm-cover, chroma-cap, and contrast cases.
5. Update the Android TV interaction contract with the single-color poster rule.
6. Run focused unit tests, full sideload unit tests, lint, release assembly, and package metadata and
   signature checks.

## Risk Points

- Palette edge-region bounds must remain valid for very small decoded bitmaps.
- OKLab transfer functions must handle finite clamped sRGB values.
- A contrast correction must not raise chroma or create a hue discontinuity.
