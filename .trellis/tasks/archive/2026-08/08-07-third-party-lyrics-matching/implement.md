# Implementation Plan: Third-party lyrics matching module

## 1. Module and contracts

- [ ] Register `:core:lyrics` in `settings.gradle.kts`; add Kotlin/JVM, serialization, coroutines, OkHttp and test dependencies through the version catalog.
- [ ] Add source-neutral request, candidate, timed-track, result, policy, source, cache and clock/request-budget contracts.
- [ ] Add architecture tests or dependency review proving the module does not import Android UI, Room, Media3, `core:data` or authenticated NAS API types.

## 2. Matching algorithm

- [ ] Port and test Unicode/symbol normalization and SequenceMatcher-equivalent text similarity.
- [ ] Port and test artist parsing/alias comparison, title tag handling and explicit version classification.
- [ ] Implement centralized eligibility, score, consensus clustering, 80-point minimum and 5-point lyric-quality tie window.
- [ ] Add parameterized regression cases for same title, aliases, multi-artist, album, duration, live/remix/cover/instrumental and multilingual metadata.

## 3. Lyrics models, parsers and alignment

- [ ] Implement source-neutral line/word timing models with original, translation and romanization tracks.
- [x] Implement LRC/YRC and textual QRC/KRC timing parsing needed by the line-level source paths; proprietary QRC/KRC binary decryption remains explicitly outside this integration.
- [ ] Port the stable closest-line matcher for original/translation alignment and cover duplicate, empty, repeated and slightly shifted lines.
- [ ] Add conversion tests proving default composition is original first, translation second, no blank/duplicate/romanization line.

## 4. Provider adapters

- [ ] Implement shared isolated HTTP transport, cancellable call bridge, request budgets and privacy-safe diagnostics.
- [x] Implement QQ Music HTTPS search and bilingual line-level LRC fetch adapter with sanitized fixtures.
- [x] Implement Netease HTTPS search and YRC/LRC bilingual fetch adapter with sanitized fixtures.
- [x] Implement Kugou HTTPS search, candidate selection and base64 LRC fetch adapter with sanitized fixtures.
- [x] Keep the active registry limited to QQ Music, Kugou and Netease; remove LRCLIB and its fixture because live search latency was too high.
- [ ] Add opt-in live smoke entry points that are excluded from the default offline test suite.

## 5. Coordinator, cache and data mapping

- [ ] Implement concurrent progressive search using `supervisorScope`, per-source timeouts, strong-candidate aggregation window, total deadline and at most two fallback fetches.
- [ ] Implement source failure isolation, cancellation propagation and deterministic `Found/NotFound/NetworkFailure/InvalidResponse` reduction.
- [ ] Add Room v3 migration with an independent `cache_matched_lyric` table and `account_state.onlineLyricsMatchingEnabled DEFAULT 1`; update schema export, storage accounting, LRU eviction and cache cleanup.
- [ ] Add a `core:data` matched-cache adapter using namespaced single-flight, a versioned third-party envelope, metadata fingerprint and bounded negative cache; keep the existing `cache_lyric` path for FN results.
- [ ] Map `Track` to `LyricsMatchRequest`, module result to `LyricDocument/LyricTimeline`, and module failures to `CurrentResourceResult` without exposing provider models.
- [ ] Implement a resolution router: when enabled use matched cache → providers → FN fallback; when disabled call FN directly; cancellation/stale revision must not start fallback or persist results.
- [ ] Add tests for provider success without FN traffic, every online non-success fallback class, both cache channels coexisting, positive cache reuse, short negative cache and v2 → v3 migration preservation.

## 6. Playback and Presenter integration

- [ ] Add album and nullable declared duration to current playback identity via MediaMetadata/extras while preserving snapshot compatibility.
- [ ] Update Presenter lyric request contracts to use captured metadata immediately and allow one metadata-enriched restart without serializing every request behind the NAS.
- [ ] Preserve namespace/media/revision guards, retained loading visuals, automatic/manual retry semantics and cancellation behavior.
- [ ] Observe online-matching preference changes and restart only the current lyric resource under a new request/revision guard.
- [ ] Add A -> B -> A late-response tests, metadata-enrichment tests, current-resource terminal-state tests and on/off live-switch cancellation tests.

## 7. Online matching setting

- [ ] Add `onlineLyricsMatchingEnabled: Boolean = true` to `AppPreferencesState`, namespace binding and persistence APIs.
- [ ] Extend `account_state` settings reads/writes without regressing player style or cache budget; old and new accounts default to enabled.
- [ ] Add the “在线歌词匹配” TV toggle to Settings using real checked/toggleable semantics and stable D-pad focus neighbors.
- [ ] Add preference and UI tests for default-on, per-account isolation, persistence, switching, accessibility semantics and retained matched cache.

## 8. Default bilingual display

- [ ] Verify both player styles render grouped original/translation in the existing fixed lyric area with original first and translation second.
- [ ] Add UI/state tests for translated, original-only, static, empty, loading and failure lyrics; romanization remains hidden.
- [ ] Confirm long bilingual current groups retain natural wrapping without changing focus order or control layout.

## 9. Attribution and cleanup

- [ ] Add `chenmozhijin/LDDC` to README “特别感谢” with an accurate description and repository link.
- [ ] Add SPDX/upstream attribution to derived algorithm/protocol files and review GPL-3.0 notices.
- [ ] Verify first-party lyric references exist only in the resolution router/fallback implementation; search for duplicate constants, leaked raw payload maps and credential-bearing third-party clients.

## 10. Validation

- [ ] Run `./gradlew :core:lyrics:test`.
- [ ] Run `./gradlew :core:model:test :core:data:test :core:playback:test :app:testSideloadDebugUnitTest`.
- [ ] Run `./gradlew :app:lintSideloadDebug :app:assembleSideloadDebug`.
- [ ] Run focused provider fixture tests with network disabled and verify no secrets/full lyrics appear in logs or snapshots.
- [ ] Run Room v2 → v3 migration tests and verify both FN and matched-lyrics cache cleanup/eviction accounting.
- [ ] Run an opt-in live smoke for available sources and record source-by-source result/latency without making it a default CI gate.
- [ ] Measure a positive cache hit and representative cold match; compare with `<50 ms` cache P95 and `<1.2 s`/`<3 s` cold P50/P95 targets, documenting environment and sample size.
- [ ] Install the debug APK on an available TV/emulator and manually verify original+translation, original-only, no match, source outage, rapid next/previous and retry flows.

## Risk and rollback checkpoints

- After step 2: scoring behavior must be frozen by tests before provider work; rollback is module-only.
- After step 4: provider clients must remain isolated from NAS auth; any credential/header reuse blocks integration.
- After step 5: Room v3 must preserve all v2 account/playback/FN lyric data, keep FN and matched caches independent, and prove namespace invalidation for both.
- After step 6: audio playback must remain independent of lyrics. If matching delays playback or breaks revision guards, restore the old presenter signature before continuing.
- Before activation: confirm the user accepts the final planning summary; do not run `task.py start` in the planning turn.
