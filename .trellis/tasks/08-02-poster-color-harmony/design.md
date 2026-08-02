# Poster Color Harmony Design

## Boundaries

Artwork decoding remains owned by `rememberCurrentArtwork` on `Dispatchers.Default`. Color
extraction remains a deterministic UI projection beside the existing ambience helpers. Playback,
network, persistence, and presenter contracts do not change.

## Data Flow

1. AndroidX Palette quantizes the complete bitmap and a right-edge region separately.
2. Each palette is reduced with population-linear weights in perceptual OKLab space. Extreme black
   and white receive a soft penalty; saturation receives no reward.
3. The global result is the anchor. The edge result may contribute up to 35 percent, falling toward
   zero as OKLab distance from the global anchor increases.
4. The single mixed color is tone-mapped in OKLCH: preserve hue, allow zero chroma, cap chroma for a
   large TV surface, and constrain lightness until primary text reaches 4.8:1 contrast.
5. Poster rendering fades the artwork into this one color. The far right may mix a small amount of
   `FnColors.Background`, which changes tone without introducing a second selected hue.

## Contracts

- `artworkPosterSurfaceColor(globalSwatches, edgeSwatches)` is a pure, unit-testable projection.
- Empty edge swatches fall back to the global projection; empty global swatches use the existing
  brand-neutral fallback.
- Edge influence is monotonic: larger perceptual disagreement never increases its weight.
- Standard-player `artworkAmbienceColor` remains unchanged.

## Tradeoffs

Population-linear perceptual averaging can become neutral for multicolor artwork. That is desired
for this large surface: a related neutral is less distracting than promoting a small vivid accent.
The implementation adds local OKLab conversion helpers rather than another color dependency.

## Rollback

Revert the two-palette poster projection and restore the prior single-swatches overload. No stored
data, API contract, or migration is involved.
