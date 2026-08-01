# Research: planning convergence review

- Query: Review the final PRD, design, implementation plan, and context manifests against the Trellis convergence gate, the three research reports, current code, and pinned Media3 1.10.1 APIs.
- Scope: mixed (planning artifacts, project specs/code, prior external research, pinned dependency bytecode)
- Date: 2026-08-01

## Findings

### Verdict

**Not ready to start implementation.** All required artifacts exist and both JSONL manifests contain real entries, but the plan still has five blocking contract gaps. The cache/audio direction and most TV overlay/focus decisions are otherwise evidence-backed.

### Blocking findings

1. **The metadata guarantee is impossible under the proposed bounded LRU.** PRD R1 says every repeated process-lifetime read directly hits memory (`prd.md:34`), while the design permits 8 MiB LRU eviction (`design.md:28`). AC1 only covers two immediate reads (`prd.md:82`). Either qualify R1 as "while retained/no intervening eviction," define a working-set guarantee, or remove eviction; then add an eviction acceptance case.

2. **The durable playback owner and external-command path are not executable yet.** The controller is declared the sole state owner (`design.md:9,107-120`), but system Next/Previous is intercepted in `PlaybackService` and sent through an undefined process-local router (`design.md:180`; `implement.md:66`). In Media3 1.10.1, `MediaSession.Callback.onPlayerCommandRequest` returns a synchronous result and `onMediaButtonEvent` covers media-button intents; neither specifies the proposed asynchronous roam handoff. Define the exact bridge/player wrapper, dependency direction, acknowledgement/error behavior, and behavior when the Activity/controller disconnects or only the service is recreated. Add service-only notification/media-controller tests, not just an Activity-background test.

3. **Four-mode behavior does not compose with the 250-item sliding window.** `REPEAT_MODE_ALL` wraps the currently loaded Media3 timeline, not the full server source after earlier pages were evicted; shuffle can likewise repeat the loaded window without a defined trigger for unseen pages (`design.md:126-135,146-160`). This conflicts with full-queue loop/shuffle and edge correctness (`prd.md:66-69,90-91`). Decide whether "queue" means loaded window or complete source. For complete-source semantics, specify lazy page discovery, end-to-start reload, shuffle exhaustion, insertion/eviction, and current-item preservation. The custom shuffle command also needs a validated ID/index payload and success acknowledgement because `MediaController` cannot set ExoPlayer's `ShuffleOrder` directly.

4. **A single JSON column prevents torn records, but not stale-write reordering.** Structural snapshots and five-second checkpoints can be launched concurrently; an older write may complete after a newer transition. Main-thread mutation serialization (`design.md:107`) does not serialize suspend DAO writes. Require a single persistence actor/mutex or a monotonic snapshot revision with conditional write, and test delayed old checkpoint versus mode, roam-exit, normal-replacement, and logout writes (`design.md:182-200`; current launch pattern at `PlaybackController.kt:375-381`).

5. **Both manifests inject instructions that the final plan intentionally contradicts.** `research/tv-ux.md:13-15,113-124` requires a three-mode cycle and direct Home exit, while the PRD/design require four modes and double Back (`prd.md:66,77`; `design.md:133,227-236`). The manifests include that report without a supersession note (`implement.jsonl:9`; `check.jsonl:10`). They also inject the active backend contract that requires namespace-prefixed Media3 disk keys/ClearCache, while the design removes that cache (`android-client-contracts.md:63-66`; `design.md:67`). Curate each manifest reason or add an authoritative decision record stating exactly which research/spec clauses are superseded and which remain binding.

### Major findings

6. **Artwork budget ownership is ambiguous.** Namespace directories and current-account usage (`design.md:43-48,63`) can turn the advertised 32/64/128/256 MiB tier into a per-account limit and allow total app disk use to grow with account count. Define a device-wide physical cap and cross-namespace eviction policy, or explicitly expose per-account plus total usage and cleanup.

7. **Incompatible-roam skipping is not testable.** The design says "fixed bound" without a number or cursor-cycle rule (`design.md:166-174`), and the checklist does not explicitly cover an incompatible initial `roam-start` item (`implement.md:62-67`). Specify maximum hops, repeated/unchanged cursor detection, Next and Previous behavior, initial entry, and the exact retry/exit state.

8. **Queue count semantics conflict with filtering.** Page segments preserve raw totals/indices while CUE and unavailable rows are omitted (`design.md:148-154`), yet the overlay labels `knownTotal` as the queue count (`design.md:208`). That can exceed the actual Media3 queue and produce numbering gaps, contrary to synchronization requirements (`prd.md:64,69`). Name it source count, compute playable count, or explicitly accept unknown total until all pages are inspected.

9. **Device tests are compiled, not run.** Steps 7-8 and the final gate use `assemble*AndroidTest` (`implement.md:77,85,112-113`), which proves compilation only, while AC9-AC11 require interaction tests to pass. Add the exact connected/managed-device commands and target API/profile, or classify each item as a recorded manual TV/AVD check with required evidence.

10. **Several outcomes lack measurable thresholds.** "Quick" artwork display and "about 50 seconds works normally" (`prd.md:84-85`) should map to request/file/config assertions plus named device seek scenarios. The pinned Media3 API does support `DefaultLoadControl.Builder.setBackBuffer(15000, false)`, and its 1.10.1 streaming max-buffer default is 50,000 ms, so the implementation shape itself is compatible.

11. **The execution unit is too broad for its failure domains.** Nine stages span cache policy, audio transport, persistence/state machine, paging/modes, roam, queue UI, and every route's focus/Back behavior, with two single-owner hotspot files (`implement.md:9-124`). Use a parent integration task with independently verifiable cache/audio, playback-engine, and TV-UI children, or add explicit approval gates between those groups.

### Files found

- `prd.md`, `design.md`, `implement.md` - complete but not yet converged for the blockers above.
- `implement.jsonl`, `check.jsonl` - non-seed manifests whose unqualified context entries conflict with final decisions.
- `research/cache.md`, `research/playback.md`, `research/tv-ux.md` - evidence base and recommendations.
- `PlaybackController.kt`, `PlaybackService.kt`, `Playback.kt`, `MusicRepository.kt`, `LocalStore.kt` - current ownership, cache, paging, and persistence patterns.
- `gradle/libs.versions.toml` - pins Media3 1.10.1.

### Code and API patterns

- Current controller snapshots use fire-and-forget coroutine writes, so ordering must be designed explicitly (`PlaybackController.kt:375-381`).
- Media3 1.10.1 exposes `ExoPlayer.setShuffleOrder`, but the generic `Player`/`MediaController` API does not; a service command or service-owned player abstraction is required.
- Direct HTTP media sources and `DefaultLoadControl.setBackBuffer` fit the pinned API; removing `SimpleCache` does not require a Room schema migration.

### External references

- Official Media3 Player/session, playlist, background playback, caching, and `DefaultLoadControl` references are already recorded in `research/cache.md:149-155` and `research/playback.md:200-206`.
- Android TV Back guidance and Compose focus references are recorded in `research/tv-ux.md:127-132`.

### Related specs

- `.trellis/spec/backend/android-client-contracts.md` - namespace, Room, authorization, cache, and CI contracts; its audio-cache clauses need explicit task-level supersession before dispatch.
- `.trellis/spec/frontend/android-tv-interaction.md` - focus and roam contracts.
- `.trellis/spec/guides/cross-layer-thinking-guide.md` - requires exact formats and ownership at the service/controller/persistence boundaries.

## Caveats / Not Found

- No product code or planning artifact was edited, and no Gradle task was run in this research-only review.
- Double Back and the four-mode cycle appear to be explicit final product overrides, not unresolved decisions; the blocker is contradictory injected context, not the choices themselves.
- Exact service restart guarantees depend on the final owner/bridge design, which the current artifacts do not define.
