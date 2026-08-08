# Lyrics quality selection and TV layout design

## 1. Boundaries

- `core:lyrics` owns provider completion, candidate eligibility, fetched-content quality, and final
  online selection.
- `core:data` owns online cache compatibility and maps `MatchedLyrics` into the app-facing lyric
  presentation model.
- `core:model` owns the provider-neutral timed lyric presentation shape used by the player.
- `app` owns TV typography, slot geometry, word emphasis, and active-line transitions.
- `MusicRepository` remains the only online-to-FN fallback boundary.

## 2. Online selection

### Search phase

Run every distinct provider concurrently. Each provider performs the current artist-title query and,
when needed, title-only fallback. Remove the whole-match timeout and early aggregation channel. Every
provider produces a terminal `SearchOutcome` under the existing per-source timeout.

Score all candidates together so cross-provider consensus remains available. Preserve the current hard
eligibility gates for duration and version conflicts. Group eligible candidates by provider in metadata
score order.

### Fetch phase

Run providers concurrently. Within one provider, try its ranked candidates sequentially until one
usable original lyric is returned, capped at three candidates. This prevents a lower-confidence cover
from displacing a valid top candidate solely because it has richer formatting, while still recovering
from an empty or malformed leading payload.

After every provider reaches a terminal fetch outcome, compare the usable provider results globally.

### Content quality

Introduce a deterministic `LyricsContentQuality` projection:

1. `WordTimed`: the original contains multiple valid timed word/syllable spans on usable lines.
2. `Translated`: no word timing, but an aligned non-duplicate translation is non-empty.
3. `Basic`: usable ordinary online original lyric.

Comparator order:

1. content tier;
2. word-timed coverage;
3. aligned translation coverage;
4. timed-line coverage;
5. usable original line count;
6. candidate metadata score;
7. source consensus count;
8. configured provider order and remote ID for deterministic stability.

Metadata eligibility remains a prerequisite, so content richness cannot rescue a candidate rejected as
the wrong recording. A word-timed eligible result beats an eligible translated line-timed result, per
the requested product order.

If no provider yields usable lyrics, preserve result classification (`NotFound`, `NetworkFailure`, or
`InvalidResponse`) after all outcomes are known. `MusicRepository` then requests FN lyrics. Disabling
online matching still bypasses the online phase.

## 3. Cache compatibility

- Increase `MATCHED_LYRICS_SCHEMA_VERSION` and `MATCH_PROTOCOL_VERSION`.
- Old positive and negative envelopes fail fingerprint/schema validation and are re-fetched.
- Successful selection continues to cache the complete `MatchedLyrics`; true all-provider not-found
  keeps the five-minute TTL; failures are not persisted.

## 4. Presentation model

Replace the untyped `LyricLine(startMs, texts)` payload with a provider-neutral structure that retains:

- line start/end;
- original text;
- optional translation;
- optional romanization;
- original timed words (`startMs`, `endMs`, `text`).

Keep a derived `texts` accessor only where compatibility helps existing document generation/tests.
First-party LRC groups use first same-timestamp text as original and subsequent distinct texts as
secondary rows; online mapping supplies explicit roles and preserves original word timing.

Extend alignment so translation and romanization are attached without replacing the original word
list. Do not manufacture word timing for ordinary LRC lines.

## 5. TV composition

Create one shared lyric-item composable used by poster and cover styles:

- original row: dominant weight/size;
- translation row: normal weight, roughly 70% of original size;
- romanization row: tertiary size and lower alpha;
- active word timing: completed words full alpha, current word interpolated, future words muted;
- inactive original lines: uniform reduced alpha with no karaoke progress.

Use three stable vertical roles: previous, current, next. Each mode provides explicit panel and role
height constraints; each role clips only after its own original/secondary layout. The current role
budgets two wrapped original rows, two translation rows, and one romanization row. This removes the
existing four-slot squeeze and prevents text from entering neighboring slots.

Animate active-index changes at the bounded lyric-window level. Playback position only recomposes word
styles; it does not rebuild the lyric window or change geometry.

## 6. Compatibility and rollback

- Track/presentation cancellation remains structured through the existing presenter job.
- No new UI dependency, WebView, focus target, database migration, or provider is introduced.
- Rollback is localized: coordinator/content comparator, presentation mapping/model, and player lyric
  composables can each be reverted independently. The cache version bump prevents mixed envelopes.

## 7. Validation

- Fixture tests for cross-provider richness, provider completion, failure isolation, and deterministic
  ties, including the `RTRT` scenario.
- Model/data tests for typed bilingual mapping, word preservation, first-party grouping, and old-cache
  invalidation.
- Pure UI projection tests for previous/current/next windows and role typography/line limits.
- Existing full unit suite, Android lint, sideload debug assembly, and `git diff --check`.
