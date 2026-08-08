# Lyric display references

## Scope

Research focused on mature open-source lyric data models and rendering behavior. No source code is
vendored and no new runtime dependency is proposed.

## Apple Music Like Lyrics (AMLL)

- Repository: https://github.com/amll-dev/applemusic-like-lyrics
- Documentation: https://amll.dev/en/guides/component/sequence.html
- Format model: https://amll.dev/en/guides/lyric/ttml
- License: AGPL-3.0-only. Use as behavioral/design reference only.

Relevant findings:

- A lyric line owns timed words, a translated line, and a romanized line as separate fields. The DOM
  renderer creates three separate child elements instead of concatenating strings.
- Translation and transliteration are sidecar roles associated with the original line. Word timing
  belongs to the original word spans.
- The lyric container has explicit dimensions. Normal playback updates progress independently from
  lyric data changes, and seeking is treated as a distinct layout transition.
- Lines use measured content height and animated transforms. Secondary content is subordinate to the
  main lyric rather than sharing its typography.

## BoomingMusic

- Repository: https://github.com/mardous/BoomingMusic
- License: GPL-3.0. This project is also GPL-3.0-only, but implementation will still be adapted to the
  local architecture instead of copied wholesale.

Relevant findings:

- The native Kotlin model stores `content`, `translation`, `transliteration`, and timed syllables on
  one immutable line.
- Its Compose renderer uses one `Column` per lyric item, with separate main and translation nodes.
  Translation is normal weight and approximately `1 / 1.4` of the main font size; a third text role
  reduces secondary size further.
- Word timing is rendered with an annotated string whose completed/current/future words receive
  different alpha. Ordinary line-timed text uses line emphasis instead.
- The component measures real content rather than assuming that every bilingual line occupies one
  text row.

## LDDC

- Repository: https://github.com/chenmozhijin/LDDC
- Existing project port baseline: commit `1ffa0e25426e654376e5d55d854b135ae601f43b`.
- License: GPL-3.0-only.

Relevant findings:

- LDDC preserves word start/end timing and computes the played fraction of the current character.
- Ruby/romanized text uses a smaller font and contributes separately to layout height.
- Its matching/parsing concepts are already the provenance baseline for `core:lyrics`; retaining the
  original word model through presentation is consistent with that boundary.

## Local implications

1. Extend the current `LyricLine(texts)` presentation model rather than introducing a foreign UI
   component. Preserve typed original/translation/romanization roles and timed words.
2. Replace fixed-height single-`Text` bilingual groups with stable lyric-item slots containing a
   measured vertical `Column`.
3. Use a simple Compose `AnnotatedString` word emphasis suitable for the existing playback tick. Do
   not introduce glow-heavy frame animation or a WebView on Android TV.
4. Keep exactly three visible semantic groups (previous/current/next) in the constrained TV panel.
   This provides enough height for two wrapped original rows plus translation without overlap.
