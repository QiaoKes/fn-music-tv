# LDDC portability research

## Scope and upstream state

- Repository: https://github.com/chenmozhijin/LDDC
- License: GPL-3.0-only/GPL-3.0.
- Default `main` commit inspected: `84631e8cd011fcc3f71ca0ae017e2c9758958ffc` (2025-06-24).
- Active `flutter` branch reference inspected and pinned for this task: `1ffa0e25426e654376e5d55d854b135ae601f43b` (2026-08-07).
- Latest released desktop build during research: v0.9.2; the active Flutter rewrite is development code and its packages specify `publish_to: none`.

The Python `main` implementation uses PySide6 `QEventLoop`, `QTimer`, thread-pool helpers, httpx, diskcache and mutagen. Embedding it in this native Android app would add a Python/Qt runtime and bind matching to desktop lifecycle code.

The Flutter branch is architecturally cleaner:

- `packages/lddc_lyrics_core`: pure Dart models, algorithms, parsers, converters and cryptography.
- `packages/lddc_lyrics_runtime`: provider HTTP implementations, matching orchestration, cache, cancellation and persistence.
- `packages/lddc_lyrics_flutter`: UI/application integration.

This separation is the reference for the new Kotlin module, but the Dart packages cannot be directly consumed by the current Kotlin Android project without embedding Flutter. Native Kotlin re-expression keeps APK size, startup, cancellation and maintenance aligned with the existing codebase.

## Matching behavior found in upstream

Python `auto_fetch.py` and the Flutter `AutoFetchUseCase` perform metadata matching, not audio fingerprinting or acoustic alignment:

1. Search `artist - title`; fall back to title if a source has no usable result.
2. Reject known duration differences greater than 4 seconds.
3. Score title, artist and optional album. Typical weights are title 50%, artist 35%-50%, album up to 15%.
4. Reject or down-rank weak title matches.
5. Fetch a bounded number of candidates per source.
6. Keep results within 15 points of the best score, then prioritize verbatim lyrics, translation and romanization richness.

The Flutter rewrite adds explicit normal-vs-instrumental conflict handling, bounded cancellation and per-source candidate attempts. Its default request still uses `minScore = 55`, a 30-second overall timeout, and a 15-point quality window. Those defaults favor coverage and rich lyrics, not this project's stricter false-match requirement. This task raises the initial eligibility threshold and reduces the quality tie window.

No upstream corpus-level Top-1 accuracy, false-positive rate or representative cold-network latency benchmark was found. The algorithm benchmark is a smoke guard against catastrophic regression, not evidence of user-visible latency or match accuracy.

## Source and protocol findings

- QQ Music: HTTPS business/QIMEI endpoints, anonymous device/session construction, encrypted QRC with original/translation/romanization support.
- Netease: HTTPS EAPI, anonymous client/device profile, YRC/LRC original and translated tracks.
- Kugou: HTTPS device registration and lyric acquisition, but the inspected compatibility search still builds `http://<domain>/api/v3/search/...` URIs with a 3-second timeout.
- LRCLIB: HTTPS JSON `/api/search` and `/api/get`, synchronized LRC or plain lyrics, typically without proprietary word/translation tracks. The protocol was researched but is excluded from this project after a 2,217 ms live search sample made it unsuitable for the latency target.

The upstream live compatibility workflow ran QQ, Netease, Kugou and LRCLIB protocol checks successfully for the pinned Flutter commit on 2026-08-07. This establishes point-in-time reachability only; private provider protocols remain volatile. This project's active/default set is limited to QQ Music, Kugou and Netease.

## Current project fit

- `Track` supplies title, artist, album and duration.
- Playback `MediaItem` already stores album title; adding declared duration to extras avoids blocking every lyric search on another NAS metadata call.
- Presenter already rejects stale namespace/media/revision results and supports current-resource retries.
- `LyricLine.texts` already represents grouped original/translation UI lines.
- Existing parser discards per-word YRC timing, so source precision can be retained in the new module but UI remains line-level for this task.
- Existing response/Room cache contracts are namespaced, waiter-aware and generation-safe. Because FN lyrics remain the fallback and online matching can be toggled independently, third-party matches need a separate cache table; otherwise one strategy overwrites the other and causes avoidable provider searches after toggling.
- Existing settings are namespace-bound through `AppPreferencesState` and `account_state`. Adding a default-on online-matching field requires a Room v2 → v3 migration while preserving queue, player style, cache budget and existing FN lyric rows.

## License and attribution

Both projects use GPL-3.0, so source-level integration is license-compatible if obligations are preserved. Derived files should keep SPDX/upstream attribution, and README should thank and link LDDC. This does not resolve the separate copyright or service-term status of downloaded lyric content or private provider APIs.

## References

- https://github.com/chenmozhijin/LDDC/blob/flutter/packages/lddc_lyrics_runtime/lib/src/auto_fetch/auto_fetch_usecase.dart
- https://github.com/chenmozhijin/LDDC/blob/flutter/packages/lddc_lyrics_core/lib/src/algorithm/title_scorer.dart
- https://github.com/chenmozhijin/LDDC/blob/flutter/packages/lddc_lyrics_core/lib/src/algorithm/artist_parser.dart
- https://github.com/chenmozhijin/LDDC/blob/flutter/packages/lddc_lyrics_runtime/lib/src/runtime/default_runtime_factories.dart
- https://github.com/chenmozhijin/LDDC/blob/flutter/packages/lddc_lyrics_runtime/lib/src/api/lyrics_sources/kg_request_executor.dart
- https://github.com/chenmozhijin/LDDC/actions/runs/31161513186
