# Lyrics quality selection and TV layout

## Goal

Select the richest trustworthy online lyric result instead of the first successful provider, use
the NAS lyric only when every online provider produces no usable lyric, and present timed or bilingual
lyrics as a stable, readable TV composition rather than concatenated oversized text.

## Background

- The current matcher ranks song candidates before fetching content, then returns after the first
  successful lyric download. A 5-point candidate tie is resolved by provider order `QQ -> Kugou ->
  Netease`, so a plain QQ lyric can hide a word-timed bilingual Netease lyric.
- The current matcher has a 3-second total budget and begins a 350 ms aggregation window after the
  first acceptable candidate. Slow but valid providers can therefore be omitted before lyric quality
  is known.
- `MusicRepository` falls back to the first-party FN/NAS lyric when online matching returns no lyric
  or throws.
- The poster layout currently joins all texts in one `Text` node. Original and translation therefore
  share one active font size and line height, producing the oversized mixed-language wrapping shown
  in the reported screenshot.

## Requirements

### R1. Content-aware online selection

- Search all configured online providers and fetch every metadata-qualified candidate needed to
  compare the best usable lyric from each provider.
- Rank fetched lyric content in this product order:
  1. word-timed original lyric;
  2. original lyric with a non-empty aligned translation;
  3. ordinary timed or plain online lyric;
  4. first-party FN/NAS lyric.
- Within the same content tier, retain candidate confidence, timing completeness, usable line count,
  source consensus, and configured source order as deterministic tie-breakers. A richer lyric must
  never lose solely because its provider has lower static priority.
- Romanization is supplemental content and must not replace a translation or increase a result above
  the explicitly requested word-timed/bilingual tiers by itself.

### R2. Complete online attempt before NAS fallback

- Remove the 3-second whole-match timeout and the 350 ms early aggregation cutoff.
- Wait for every configured online provider to reach a terminal outcome before declaring that online
  matching has no usable result.
- Keep bounded per-request connection/read/write/source timeouts so one unavailable third party cannot
  leave the player loading forever.
- Network or malformed-payload failures from one provider must not discard usable lyrics returned by
  another provider.
- Query FN/NAS lyrics only after online matching returns no usable lyric. The setting that disables
  online matching continues to request FN/NAS lyrics directly.

### R3. Stable cache semantics

- Persist the selected lyric quality and enough source content to reproduce the chosen presentation.
- Bump the matched-lyrics cache protocol/schema so previously cached lower-quality provider results
  are invalidated and reconsidered automatically.
- Cache a true all-provider `NotFound` for the existing short negative TTL. Do not negative-cache
  transport or invalid-payload failures.

### R4. TV lyric composition

- Render original, translation, and optional romanization as separate semantic rows inside one lyric
  item. Never join bilingual text into a single large string.
- Preserve original word start/end timestamps through the data layer. Word-timed lyrics progressively
  highlight completed/current words from the playback position; line-timed lyrics retain line-level
  emphasis without synthesizing fake word timing.
- The active original line is visually dominant; translation is smaller and quieter; romanization,
  when present, is tertiary. Previous and next lyric items remain readable without competing with the
  active line.
- Use stable vertical slots or measured item heights so a two- or three-line active lyric does not
  overlap neighboring lyrics or cause the entire panel to jump on every timestamp.
- Long CJK and Latin lyrics wrap within the right panel; no text may overlap the song header, format
  badge, artwork boundary, or neighboring lyric item at 1080p and smaller supported landscape sizes.
- Poster and cover player styles use the same lyric hierarchy, adjusted to their available width.
- Timeline changes animate smoothly without turning the lyric surface into a manually scrollable or
  focusable control.

### R5. Compatibility and observability

- Preserve cancellation when the user changes track, account, or player presentation revision.
- Preserve static lyric, loading, retry, visual-continuity, and online-matching preference behavior.
- Keep provider and quality selection deterministic and unit-testable without live network access.

## Acceptance Criteria

- [ ] For `RTRT / Mili / Miracle Milk / ~215 s`, fixtures equivalent to the live provider responses
      select Netease word-timed bilingual lyrics over plain QQ lyrics.
- [ ] A word-timed lyric without translation beats a line-timed bilingual lyric, matching the requested
      priority order.
- [ ] A bilingual online lyric beats a plain online lyric; any usable online lyric beats FN/NAS fallback.
- [ ] One slow/failing provider does not prevent another provider's usable result, and NAS fallback is
      evaluated only after all online providers have terminal outcomes.
- [ ] There is no whole-match 3-second timeout or 350 ms early aggregation window; bounded individual
      network operations remain.
- [ ] Existing cached matches from the prior selection protocol are invalidated.
- [ ] Poster and cover lyric tests verify separate original/translation styling, stable slot geometry,
      bounded wrapping, and correct active-window projection.
- [ ] The supplied mixed Japanese/Chinese example renders as one lyric item with a dominant original
      row and smaller translation row, without the current oversized three-line block.
- [ ] Core lyric/data/app unit tests, Android lint, and a sideload debug build pass.

## Out of Scope

- Manual provider selection, lyric editing, desktop-style lyric offset controls, and karaoke seeking.
- Adding new lyric providers or replacing the existing QQ/Kugou/Netease API integrations.
- Downloading or parsing new lyric formats beyond the existing YRC/QRC/LRC/plain support.
