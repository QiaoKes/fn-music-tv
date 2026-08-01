# Bug Analysis: Previous/Next artwork and lyrics intermittently remain empty

- Scope: current controller projection, Compose player loaders, repository caching, and HTTP cancellation
- Date: 2026-08-01
- Evidence level: implemented regression tests, full Gradle quality gate, and Android TV API 36 connected-device verification

## Bayesian assessment

| Hypothesis | Prior | Evidence update | Posterior role |
| --- | ---: | --- | --- |
| Final one-shot artwork/lyrics request fails and cannot retry | 40% | Both loaders have a terminal same-key failure state | Primary, high confidence |
| Canceled UI work leaves blocking HTTP calls alive | 25% | `Call.execute()` has no coroutine-to-`Call.cancel()` bridge | Primary amplifier, high confidence |
| Media3 publishes mismatched current identity/aggregate metadata | 20% | Pinned controller source masks the new item before aggregate metadata acknowledgement | Confirmed transient contributor |
| Track list omits optional cover metadata | 10% | Queue construction never enriches later items; `coverId` is nullable | Confirmed missing-cover path |
| Server truly has no cover/lyrics | 5% | Valid content absence exists but does not explain intermittent recovery | Expected non-error state |

The combined root-cause confidence is above 90% for the defect class. Exact frequency still requires the planned device/MockWebServer tests.

## 1. Root Cause Category

- **Category B - Cross-layer contract:** current-item identity, metadata, resource requests, cancellation, and UI publication do not share one transition token.
- **Category D - Test coverage gap:** no test exercises MediaController masked transitions, A -> B -> A request ordering, HTTP cancellation, same-key retry, or artwork single-flight.
- **Category E - Implicit assumption:** Compose coroutine cancellation was assumed to stop synchronous OkHttp calls, and one request was assumed sufficient for a local NAS.

### Specific causes

1. `PlaybackController.project()` reads the GUID from `currentMediaItem` but title/art/format from aggregate `player.mediaMetadata`. Media3 can transiently expose B as the current item while aggregate metadata remains A/empty.
2. Artwork converts download/read/write failure to `null`; its `produceState` has no retry input. Lyrics makes one network-first request per media ID and exposes a terminal failure until the ID changes.
3. `TrimMusicApi` uses blocking `Call.execute()` in `withContext(IO)` and never calls `Call.cancel()` when the coroutine is canceled. Rapid changes leave obsolete calls consuming connections.
4. Catch-all `runCatching` paths treat `CancellationException` like a resource failure.
5. Artwork misses are not coalesced and write directly to the same final file. Same-cover transitions reuse only `(coverId, variant)`, so a failed result can remain terminal across a new track transition.
6. Queue items use list metadata without enriching the newly current track; a nullable/missing `coverId` therefore has no recovery path.

## 2. Why Earlier Behavior Fails

No prior targeted fix exists. The current implementation addresses only the surface lifecycle:

1. Keying lyric state by `mediaId` prevents most stale UI writes, but does not cancel transport or retry the final request.
2. Keying artwork by `coverId` avoids unrelated reloads, but omits track transition identity and a retry generation.
3. Reprojecting every 250 ms usually repairs Media3's transient aggregate metadata, but still publishes incoherent intermediate states and cannot manufacture a missing cover ID.
4. Room fallback helps previously cached lyrics during network failure, but the normal path still calls the NAS first and a first-time miss remains terminal.

## 3. Prevention Mechanisms

| Priority | Mechanism | Specific action | Status |
| --- | --- | --- | --- |
| P0 | Architecture | Bind all current-item presentation work to `namespace + mediaId + presentationRevision`; reject every stale presenter result | Implemented and unit-tested |
| P0 | Runtime cancellation | Replace blocking execution with a cancellable OkHttp bridge that invokes `Call.cancel()` | Implemented and unit-tested |
| P0 | Projection | Derive identity/title/artist/art/format from one captured `currentMediaItem.mediaMetadata` | Implemented and unit-tested |
| P0 | Retry | Retry only idempotent I/O/408/429/5xx failures twice (250 ms, 750 ms); never retry cancellation/auth/not-found/redirect/valid absence | Implemented and request-count tested |
| P0 | Cache concurrency | Reference-count single-flight waiters, cancel upstream when the last waiter leaves, and write artwork atomically | Implemented and race-tested |
| P1 | UI state | Expose Loading/Ready/Absent/RetryableFailure; clear stale artwork immediately and offer one shared Retry action after exhaustion | Implemented and focus-tested |
| P1 | Tests | Add masked Media3 transition, reverse completion, same-cover, cancellation, retry, and missing-cover enrichment tests | Implemented; full gate passed |

## 4. Systematic Expansion

- **Similar issues:** browse-page `LaunchedEffect` loaders also use catch-all `runCatching`; cancellable HTTP and cancellation propagation must be fixed at the API boundary, not only in the player.
- **Design improvement:** the playback controller owns a monotonic presentation identity that advances on transitions and material current-item metadata changes; an application-scoped now-playing presenter owns only derived metadata/assets and never infers identity from a route's original `Track`.
- **Process improvement:** every async UI resource needs explicit identity, cancellation, stale-result rejection, retry classification, and Absent versus Failed semantics.
- **Related race:** delayed queue restore can overwrite a newly selected queue after the database read; the task's generation and ordered-snapshot work must test this takeover case.

## 5. Knowledge Capture

- [x] Record the root cause and executable prevention contract in this task.
- [x] Add requirements, design, implementation steps, and acceptance tests before implementation starts.
- [x] After the fix and connected tests pass, update the Android client/cross-layer specs and synchronized templates with the proven transition-resource contract.
- [x] Commit spec changes only with the verified implementation; planning does not yet authorize product/spec commits.

## Completion Evidence

- `NowPlayingPresenterTest` covers A -> B -> A reverse completion, same-cover transition retries, stale revision rejection, and missing-cover enrichment.
- `TrimMusicApiTest` and repository cache tests cover coroutine-to-OkHttp cancellation, waiter-counted single-flight, bounded retry request counts, invalid images, cache-clear races, and namespace isolation.
- The full CI-equivalent Gradle gate passed on 2026-08-02 across model, data, playback, app, lint, release variants, Android-test assemblies, and baseline-profile builds.
- Android TV API 36 at 1920x1080/320 dpi passed 10 Sideload and 10 Store connected tests. Player and queue screenshots were also reviewed at 1920x1080 and 1280x720.
- A real NAS account was unavailable, so the credentialed 20-second stream/back-seek and real-server roam transition remain explicit manual smoke checks rather than claimed evidence.

## Required race tests

1. Project current item B while aggregate metadata is A; every visible B field must come from B.
2. Block A, switch to B, complete A late, then B; only B may publish.
3. Execute A -> B -> A and finish HTTP responses in reverse order; only the newest A revision may publish.
4. Fail the final artwork and lyrics attempts transiently, then succeed without changing media/cover IDs.
5. A/cover-X fails, then B/cover-X becomes current; B must start a new transition attempt.
6. Cancel the only waiter and assert the OkHttp call is canceled; cancel one of two waiters and assert the shared call continues for the remaining waiter.
7. Complete an artwork download after clear/account switch and assert it cannot write memory or disk.
8. Transition to a queue item with no list `coverId`; current-track metadata enrichment must recover its cover when the metadata endpoint supplies one.
9. While the same item is current, publish a missing-cover identity then a cover-enriched identity and finish the older metadata/artwork work late; only the enriched presentation revision may render.
