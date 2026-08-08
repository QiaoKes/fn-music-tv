# Design: Third-party lyrics matching module

## Architecture and Boundaries

新增纯 Kotlin/JVM 模块 `:core:lyrics`：

```text
Playback MediaItem / Track metadata
  -> NowPlayingPresenter captures current revision
  -> core:data maps Track to LyricsMatchRequest
  -> LyricsResolutionRouter
       -> online matching disabled: existing FN lyric path
       -> online matching enabled:
            -> matched-lyrics cache
            -> core:lyrics LyricsMatchCoordinator
                 -> QQ / Kugou / Netease sources (isolated HTTP clients)
                 -> candidate normalization, scoring, variant guard, consensus
                 -> selected source fetch + decrypt + parse
                 -> original / translation / romanization tracks
            -> no usable online result: existing FN lyric path
  -> core:data composes CurrentLyrics
  -> existing PlayerScreen renders original + translation per time group
```

Ownership rules:

- `core:lyrics`: source-neutral contracts, matching policy, source clients, cryptography, parsing, line alignment, timeout/cancellation orchestration.
- `core:model`: application-facing lyric timeline remains the UI contract. Extend it only where preserving source/provenance or word timing is required without importing provider types.
- `core:data`: strategy routing, namespace, process single-flight/persistence adapter, `Track` mapping, first-party fallback, application error mapping, `CurrentLyrics` composition and preference persistence.
- `core:playback`: make album and declared track duration available in the captured current identity; do not perform lyric requests.
- `app`: application lifetime wiring, Presenter revision ownership, UI display and README attribution. No provider parsing in Compose.

`core:lyrics` must not depend on `core:data`, `core:playback`, `app`, Android UI, Room, Media3 or the authenticated `TrimMusicApi`. It may depend on coroutines, serialization and one injected/shared OkHttp transport.

## Public Contracts

Representative contracts; exact names may be refined while preserving ownership:

```kotlin
data class LyricsMatchRequest(
    val localId: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long?,
)

enum class LyricsSourceId { QqMusic, Kugou, Netease }
enum class LyricsTrackKind { Original, Translation, Romanization }

data class LyricsCandidate(
    val source: LyricsSourceId,
    val sourceSongId: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long?,
    val variant: SongVariant,
    val capabilities: Set<LyricsTrackKind>,
)

data class MatchedLyrics(
    val source: LyricsSourceId,
    val candidate: LyricsCandidate,
    val score: Double,
    val original: TimedLyricsTrack,
    val translation: TimedLyricsTrack?,
    val romanization: TimedLyricsTrack?,
)

interface LyricsSource {
    val id: LyricsSourceId
    suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate>
    suspend fun fetch(candidate: LyricsCandidate): SourceLyrics
}

interface MatchedLyricsCache {
    suspend fun get(key: LyricsCacheKey): CachedLyricsResult?
    suspend fun put(key: LyricsCacheKey, value: CachedLyricsResult)
}
```

Exceptions remain internal to `core:lyrics`. The coordinator returns a sealed result distinguishing `Found`, `NotFound`, `NetworkFailure` and `InvalidResponse`; `core:data` performs the single mapping into `CurrentResourceResult`/`AppError`.

`LyricsResolutionRouter` is the application policy boundary. It receives the current `onlineLyricsMatchingEnabled` snapshot and resolves exactly as follows:

1. Disabled: call the existing first-party resolver only.
2. Enabled and valid positive matched cache: return it without provider traffic.
3. Enabled without positive cache: run the coordinator; persist and return `Found`.
4. Any non-cancellation online terminal without usable lyrics: invoke the existing first-party resolver.
5. Cancellation or stale generation: rethrow/ignore; do not issue a fallback request or persist a result.

The router retains provenance internally (`OnlineMatched` or `FnFallback`) for diagnostics and tests, while the UI continues to receive `CurrentLyrics`.

## Metadata Flow

`PlaybackController.mediaItem()` already writes album title. Add declared track duration to the MediaMetadata extras and extend `CapturedNowPlayingFields`/`NowPlayingIdentity` with album and nullable duration. Snapshot compatibility is preserved because serialized `Track` already contains album and duration.

The Presenter constructs an initial `Track` from the identity and starts third-party lyrics immediately, in parallel with the existing full metadata request. If the initial identity lacks a meaningful album/duration and the full metadata result materially improves the fingerprint before lyrics reaches a terminal state, cancel/restart lyrics once under the same presentation revision. This avoids always serializing lyrics behind a NAS request while still using complete metadata when available.

All publishes continue to compare `namespace + mediaId + presentationRevision`. Lyrics cancellation is rethrown through repository boundaries and never rendered as a failure for a newer identity.

## Matching Policy

### Normalization

- Unicode NFKC/full-width normalization, trim and lowercase where case is irrelevant.
- Preserve original display text separately from normalized score text.
- Parse artist collections and common separators; retain aliases/character-CV groupings from the LDDC rules where tests prove value.
- Extract version evidence (`instrumental`, `off vocal`, `live`, `remix`, `acoustic`, `radio/TV size`, `cover`) without blindly deleting it from the comparison.

### Candidate eligibility and score

1. Reject empty remote title, explicit normal-vs-instrumental conflict and known duration delta greater than 4 seconds.
2. Compute title, artist and optional album similarities using a Kotlin equivalent of LDDC's SequenceMatcher-based behavior.
3. Combine as title 50%, artist 35%-50%, album up to 15%; duration and cross-source consensus refine confidence rather than allowing an unrelated title to recover.
4. Require total score `>= 80`, title score `>= 50`, and no hard conflict.
5. Cluster normalized candidates across sources by compatible title/artist and duration; consensus increases selection confidence but never bypasses hard gates.
6. Sort primarily by metadata score. Only candidates within 5 points use lyric richness (`original timed`, translation, romanization) and configured source order as tie-breakers.

All weights, thresholds, duration tolerance, tie window and source order live in one immutable `LyricsMatchPolicy` with unit-test coverage.

## Concurrency and Request Budget

- Use `supervisorScope` so one source failure does not cancel siblings.
- Each source search gets an injected timeout budget; the coordinator has a shorter production total deadline than LDDC's 30 seconds.
- Search sources concurrently and consume completed results progressively. An exact/high-confidence candidate may start fetch immediately, while a short aggregation window allows a stronger/consensus result to replace it before publication.
- At most one selected fetch and two ordered fallback fetches run per match. No unbounded retry loops.
- Retry only idempotent timeout/I/O/408/429/5xx failures with a small bounded policy. Authentication-like protocol rejection, parsing errors, empty results and cancellation are not retried blindly.
- One application-lifetime source registry owns the clients and can close them during application shutdown. Provider clients never inherit NAS interceptors or headers.

Production defaults should target a 3-second coordinator deadline, with shorter per-source attempts and a 250-400 ms strong-candidate aggregation window. Deterministic tests use injected clocks/budgets rather than wall-clock sleeps.

## Source Adapters

- **QQ Music:** HTTPS search plus public line-level LRC original/translation responses; textual QRC timing syntax remains parser-compatible, but proprietary QRC binary decryption is outside this integration.
- **Netease:** anonymous HTTPS search plus YRC/LRC original/translation responses, without user login or account cookies.
- **Kugou:** HTTPS search, lyric candidate acquisition and base64 LRC download; textual KRC timing syntax remains parser-compatible, but proprietary KRC binary decryption is outside this integration.

LRCLIB is not registered or implemented. A live search sample took 2,217 ms, so it was removed from the online path to keep cold-match latency bounded around the three faster active sources.

Each adapter converts external JSON/encrypted payloads at its boundary into typed `LyricsCandidate`/`SourceLyrics`. Raw maps cannot cross into matching or UI code. Live source smoke tests are opt-in because protocol availability is external state.

## Lyrics Parsing and Display Composition

`core:lyrics` parses line-level LRC, YRC and textual QRC/KRC timing syntax into a source-neutral representation that can retain line and word intervals. It does not decrypt proprietary QRC/KRC binary payloads. Original and translation remain separate tracks. A port of LDDC's closest-line matcher aligns translation by timing/content with stable handling for repeated and empty lines.

`core:data` maps the result into the current application model:

- original text first;
- aligned translation second when nonblank and different from original;
- romanization excluded from the default `texts` list;
- multiple entries at one start time remain ordered and deduplicated;
- no timed original falls back to static original plus translation text.

The current UI already joins `LyricLine.texts` with newlines and reserves up to four current visual lines, so no new UI control is required. Word intervals may be retained in an optional domain field for future work, but this task keeps line-level activation.

## Cache Contract

Use the existing namespace rule and single-flight infrastructure through a `core:data` adapter. Third-party matches use a distinct process key and a new Room table `cache_matched_lyric`, while the existing `cache_lyric` table and response key remain the first-party cache. This separation lets the setting switch strategies without destroying either result.

The third-party serialized envelope contains:

- schema/protocol version;
- normalized metadata fingerprint;
- selected source/candidate provenance and score;
- parsed original/translation/romanization tracks;
- fetched timestamp and `Found`/`NotFound` status.

Read acceptance requires matching namespace, track GUID, fingerprint and schema version. Positive lyrics can use a long TTL; negative results use a short TTL. An offline request may return a valid positive Room value. Invalid, failed or canceled responses are never persisted. Disabling online matching bypasses this table without deleting it; re-enabling may immediately reuse a still-valid positive row.

Room v3 adds `cache_matched_lyric(namespace, trackGuid, payload, accessedAt)` and `account_state.onlineLyricsMatchingEnabled INTEGER NOT NULL DEFAULT 1`. `MIGRATION_2_3` must preserve all existing rows, export the schema, include matched lyrics in payload accounting/LRU eviction, and remove both lyric tables from namespace/all-cache cleanup. Migration tests cover v2 data preservation and the default-on value.

## Preference and Live Switching

Add `onlineLyricsMatchingEnabled: Boolean = true` to `AppPreferencesState` and persist it with the existing namespace-bound account settings. `AppPreferences.bindNamespace()` treats a missing/legacy value as `true`; no process-global setting may override an account's stored choice.

The settings page adds one D-pad focusable toggle labeled “在线歌词匹配”, with checked semantics and stable neighbors. A preference change increments or regenerates the current lyric request while leaving metadata/artwork untouched:

- on → off: cancel any provider work and resolve the current song through FN;
- off → on: resolve the current song from matched cache first, then providers, then FN fallback;
- do not clear either cache on a normal toggle;
- namespace/media/presentation revision remains the final publish guard.

## Errors, Logging, and Privacy

- Logs contain source ID, stage, coarse outcome, elapsed time and candidate count; they do not contain full lyric content, NAS credentials, anonymous device IDs or encrypted protocol material.
- `NotFound` means every completed source lacks an eligible/fetchable candidate, not merely that one source returned empty.
- Partial network failure plus a valid match returns the match. Every other non-cancellation online terminal delegates to FN; only the combined online + FN outcome becomes `Absent` or `RetryableFailure`.
- Third-party source health never signs the user out or mutates the NAS session.

## Compatibility and Rollback

- Keep the existing `CurrentLyrics` and Presenter state surface, minimizing UI blast radius.
- Preserve `TrimMusicApi.lyrics`, its parser, retry policy and `cache_lyric` as the supported runtime fallback and as the only path when online matching is disabled.
- A rollback can force the preference/router to the first-party branch and stop provider wiring without changing playback snapshots; Room v3 remains backward-compatible data even when the online module is disabled.
- The source protocol reference is pinned in research so later maintainers can diff upstream changes deliberately.

## Attribution

README thanks and relevant source headers identify `chenmozhijin/LDDC`, the pinned reference commit, and GPL-3.0. The implementation may independently re-express algorithms in Kotlin, but attribution is still retained because protocol and algorithm behavior derive from LDDC research.
