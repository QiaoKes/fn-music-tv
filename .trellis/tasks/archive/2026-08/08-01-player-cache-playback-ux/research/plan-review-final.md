# Research: final planning convergence review

- Query: Re-review the revised PRD, design, implementation plan, context manifests, and authoritative decisions against every finding in `research/plan-review.md`.
- Scope: mixed (planning artifacts, current code, project specs, pinned Media3 1.10.1 APIs)
- Date: 2026-08-01

## Findings

### Verdict

**Ready for the final planning summary and fresh user approval.** All five prior blockers and six major findings are resolved. The artifacts are internally consistent, acceptance criteria are observable, both manifests contain curated real context, and no user-owned product decision remains open. This verdict does not itself authorize `task.py start`; implementation still requires the user's subsequent approval of the latest summary.

### Confirmed audio decision

The confirmed product choice is represented consistently and without a fallback loophole:

- `research/decisions.md:5-10` is authoritative: remove `SimpleCache` and all persistent audio spans, delete legacy `cacheDir/media`, and never recreate it.
- `prd.md:42-47,87` requires zero persistent audio files, Media3's 50,000 ms forward-buffer maximum, and a 15,000 ms in-memory back buffer.
- `design.md:71-86,256-260,268-269` specifies direct authenticated `DefaultHttpDataSource`, exact load-control settings, no media cache command/accounting, unit inspection, and target-device verification. Persistent audio caching is explicitly not a memory-pressure fallback.
- `implement.md:31-44,109,153-160` makes this an early approval gate and a final completion invariant.
- Decision D1 and both manifest reasons narrowly supersede only the obsolete media-disk-key/ClearCache clauses; authorization, redirects, Room, migration, namespace, and CI contracts remain binding.

The shape fits pinned Media3 1.10.1: `DefaultLoadControl.Builder` supports the required 15,000 ms back buffer and 50,000 ms streaming maximum, while a direct HTTP media-source factory adds no persistent disk cache.

### Prior finding resolution matrix

| Prior finding | Status | Revised contract |
| --- | --- | --- |
| 1. Unlimited process-lifetime cache claim versus 8 MiB LRU | Resolved | R1/AC1 and `design.md:28-37` now define a retained working set and observable eviction/refetch. |
| 2. Undefined durable transport owner/bridge | Resolved | D4 and `design.md:189-193` define an application-scoped owner, cold `startPlaybackRuntime()`, `ForwardingPlayer`, Normal/Roam/Restoring routing, fail-closed behavior, and service-only tests. |
| 3. Full-source modes incompatible with a 250-item window | Resolved | D3, R4, AC7/AC8, and Out of Scope explicitly constrain repeat/shuffle/counts to the loaded playable active window. |
| 4. Atomic row but reorderable snapshot writes | Resolved | D5 and `design.md:212-214` require monotonic revisions, one writer actor, FIFO acknowledged structural barriers, safe checkpoint conflation, and delayed-write tests. |
| 5. Manifest/spec/research contradictions | Resolved | D1/D2 are authoritative and both manifests name the exact superseded cache, mode, and Home-Back recommendations. |
| 6. Per-account artwork growth ambiguity | Resolved | D6, R1/AC3, and `design.md:49,55-69` define one global physical limit, global LRU eviction, global usage, and global clear. |
| 7. Untestable incompatible-roam skip | Resolved | D7, R3/AC6a, and `design.md:173-183` define initial/Next/Previous behavior, eight windows, seen/blank/unchanged ID termination, and exact failure states. |
| 8. Raw source total exposed as playable queue count | Resolved | Public state uses `queueIndex`; raw positions remain in page segments, while UI count/numbering is contiguous over playable active items. |
| 9. Android tests compiled but not run | Resolved | AC11 and Gates C/9 require both API 36 connected flavor tests; no runnable TV target means incomplete acceptance. |
| 10. Non-measurable cache/buffer outcomes | Resolved | AC3/AC4 and `design.md:254-260` define request counts, exact configuration assertions, a 20-second/10-second device seek, and disk-file checks. |
| 11. Oversized execution unit | Resolved | Gates A-C separate cache/audio, playback engine, and TV UI approval before integration, with single-owner hotspot rules. |

### Media3 and current-code fit

- Media3 1.10.1 `ForwardingPlayer` exposes all four overridden methods: `seekToNextMediaItem`, `seekToNext`, `seekToPreviousMediaItem`, and `seekToPrevious`.
- The revised bridge normalizes both Previous command families to the product's 3-second rule, fixing the current controller's unconditional `seekToPreviousMediaItem()` behavior (`PlaybackController.kt:196-197`).
- Media3's generic controller cannot install an explicit `ShuffleOrder`; the planned validated service command correctly maps acknowledged media IDs to ExoPlayer indices.
- Keeping the playback JSON envelope in existing `queueJson` and atomically clearing legacy `frozenQueueJson` does not change the Room table schema, so no Room version bump is required.

### Remaining conditions, not planning gaps

- Run the connected Android TV API 36 tests and device audio-cache/seek scenario; compilation-only results cannot satisfy AC11.
- Keep Gate A's zero-disk-audio invariant through all later stages and rollback diagnostics.
- After behavior is proven, update the Android client and TV interaction specs so the temporary D1/D2 supersession record is no longer needed.
- Present the revised final planning summary and obtain fresh explicit implementation approval.

### Files found

- `prd.md`, `design.md`, `implement.md` - converged requirements, architecture, execution gates, and validation commands.
- `implement.jsonl`, `check.jsonl` - curated non-seed manifests with explicit supersession notes and `research/decisions.md`.
- `research/decisions.md` - authoritative product and architecture conflict resolutions D1-D7.
- `research/plan-review.md` - source list of the eleven findings verified above.

### Related specs and external references

- `.trellis/spec/backend/android-client-contracts.md`, `.trellis/spec/frontend/android-tv-interaction.md`, and `.trellis/spec/guides/cross-layer-thinking-guide.md` remain binding except for the narrowly recorded D1/D2 overrides.
- Official Media3 caching, Player/session, playlist, background playback, and `DefaultLoadControl` references remain recorded in `research/cache.md:149-155` and `research/playback.md:200-206`.

## Caveats / Not Found

- No product code or existing planning artifact was edited, and no Gradle or device test was run in this research-only review.
- Implementation correctness still depends on the mandatory gates; this review establishes plan convergence, not test results.
