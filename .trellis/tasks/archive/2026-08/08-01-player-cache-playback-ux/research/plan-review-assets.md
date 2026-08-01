# Research: Current-track asset plan convergence

- Query: Re-review the updated PRD, design, implementation plan, decisions, asset-bug research, and manifests for the intermittent Previous/Next artwork and lyrics failure.
- Scope: internal
- Date: 2026-08-01

## Verdict

Ready for implementation. The artifacts converge on one executable contract for coherent current-item projection, current-presentation ownership, cancellation, retry, and stale-result rejection. No blocking product or architecture question remains.

## Files Found

- `.trellis/tasks/08-01-player-cache-playback-ux/prd.md` - R6 and AC12/AC12a define observable asset-transition behavior and request bounds.
- `.trellis/tasks/08-01-player-cache-playback-ux/design.md` - Defines the HTTP bridge, retry disposition, presentation identity, presenter ownership, and verification contract.
- `.trellis/tasks/08-01-player-cache-playback-ux/implement.md` - Orders repository/runtime/presenter work and its quality gates.
- `.trellis/tasks/08-01-player-cache-playback-ux/research/decisions.md` - D8 is the authoritative current-track resource decision.
- `.trellis/tasks/08-01-player-cache-playback-ux/research/track-change-assets.md` - Records the root causes and required race tests.
- `.trellis/tasks/08-01-player-cache-playback-ux/implement.jsonl` and `check.jsonl` - Inject the relevant specs, D8, and bug research into both execution phases.

## Findings

1. **Coherent projection:** R6 and design require one captured `currentMediaItem.mediaMetadata` for ID, title, artist, format, and typed cover ID. This directly replaces the current mixed projection at `core/playback/src/main/kotlin/com/fnmusic/tv/core/playback/PlaybackController.kt:249` and `:258`.
2. **Revision separation:** `presentationRevision` advances on every logical MediaItem transition and material captured-field change; it is explicitly distinct from `snapshotRevision` (`design.md:141`, `:154`). No stale `transitionRevision` term remains.
3. **Presenter ownership:** The application-scoped `NowPlayingPresenter` is started through idempotent `AppContainer.startPlaybackRuntime()` during application startup. Stage 5 creates this runtime before presenter construction; stage 7 consumes the already-started runtime, so the previous stage dependency is resolved (`implement.md:65`, `:92`).
4. **Cancellation and single-flight:** The plan replaces blocking `Call.execute()` (currently visible at `core/data/src/main/kotlin/com/fnmusic/tv/core/data/api/TrimMusicApi.kt:122`) with a cancellable callback bridge. Waiter counting preserves work for remaining consumers and cancels the upstream OkHttp call when the last waiter leaves. Generation checks prevent clear/logout races from repopulating cache.
5. **Bounded retry classification:** All four bridge shapes share 401/404/public error mapping, while an internal disposition limits retries to I/O/408/429/5xx. Current metadata/artwork/lyrics get exactly two retries at 250/750 ms; 401, 404, redirect, empty/invalid, and cancellation make one request. D8's 404 exception is explicit in both manifests.
6. **Asset state semantics:** Metadata 404/empty retains the captured MediaItem fields as `Ready`; artwork and lyrics use `Absent`. Cancellation is rethrown and never rendered as failure. Exhausted retryable failures expose retry only for the still-current revision (`prd.md:91`, `design.md:168`).
7. **Transition races:** The identity guard is `namespace + mediaId + presentationRevision`. The acceptance and presenter tests cover aggregate-A/current-B projection, A -> B -> A reverse completion, same-cover new-track retry, same-key recovery, and late old completion (`prd.md:109`, `implement.md:73`).
8. **Missing cover enrichment:** Full metadata loads concurrently with lyrics and may enrich a missing list `coverId` without blocking audio. A material missing-cover -> enriched-cover update advances the presentation revision, with an explicit late-old-result regression test (`design.md:166`, `prd.md:109`).
9. **Stages and gates:** Stages are numbered 1-10. Gate A follows repository/audio stages 1-3, Gate B follows playback stages 4-7, Gate C follows UI stages 8-9, and stage 10 is the integrated gate. Validation commands are attached to every stage.
10. **No persistent audio conflict:** D1, R1/AC4, design, stages 3/10, and final checks consistently remove `SimpleCache`/`CacheDataSource`, delete legacy `cacheDir/media`, use direct authenticated HTTP, and retain only 50 s forward plus 15 s in-memory back buffering. The new single-flight/cache work is limited to metadata, lyrics, and artwork.
11. **Manifest convergence:** Both manifests include the active backend/TV contracts, D8 decisions, and `track-change-assets.md`; both explicitly document the D1 persistent-audio and D8 HTTP 404 spec overrides.

## Code Patterns

- The current projector combines aggregate metadata with current-item media ID (`PlaybackController.kt:249-259`), matching the diagnosed incoherence.
- Current lyrics use a media-ID-keyed one-shot `LaunchedEffect` and catch-all `runCatching` (`AuthenticatedApp.kt:1047-1057`); artwork identity is reparsed from a URL (`AuthenticatedApp.kt:1062-1074`). The planned presenter removes both patterns.
- Current API helpers execute blocking calls and differ in status mapping (`TrimMusicApi.kt:122-150`); stage 2 centralizes both transport cancellation and classification.

## External References

- Media3 1.10.1 is the pinned project version used by the design for load-control and controller-event semantics.
- The existing bug research records pinned Media3 source inspection; no additional external source was needed for this convergence review.

## Related Specs

- `.trellis/spec/backend/android-client-contracts.md` - API, auth, cache, Room, Media3, and compatibility contracts; D1 and D8 narrowly supersede identified rows.
- `.trellis/spec/frontend/android-tv-interaction.md` - Player controls, D-pad focus, overlays, and Back behavior.
- `.trellis/spec/guides/cross-layer-thinking-guide.md` - Required API-to-repository-to-controller-to-presenter-to-Compose data-flow review.

## Caveats / Not Found

- This is a static plan-convergence review, not a runtime reproduction or implementation verification.
- Readiness depends on the specified MockWebServer, pure projector/presenter, Gradle, and API 36 TV device tests actually passing; the bug research notes that exact field frequency has not yet been reproduced at runtime.
- No product code, specs, manifests, or planning artifacts were edited by this researcher.
