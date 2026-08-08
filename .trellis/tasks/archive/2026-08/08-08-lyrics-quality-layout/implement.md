# Implementation plan

## Phase A: Content quality and provider completion

- [x] Add fetched-content quality projection and deterministic comparator in `core:lyrics`.
- [x] Replace total-budget/aggregation orchestration with all-provider terminal search outcomes.
- [x] Fetch one usable result per provider concurrently, retaining bounded per-call and per-provider
      candidate attempts.
- [x] Select the richest eligible result and preserve terminal error classification.
- [x] Add coordinator tests for word-timed > translated > basic, `RTRT`, slow providers, mixed failures,
      and cancellation.

## Phase B: Typed presentation and cache

- [x] Extend `core:model` lyric lines with end time, typed secondary content, and timed words.
- [x] Preserve original words while aligning translation and romanization in `core:data`.
- [x] Adapt first-party LRC/YRC parsing and existing consumers.
- [x] Bump online-match cache schema/protocol and test stale positive/negative invalidation.

## Phase C: Android TV lyric layout

- [x] Add a shared original/translation/romanization lyric-item composable.
- [x] Add progressive word emphasis driven by current playback position.
- [x] Replace poster and cover four-line concatenated layouts with stable previous/current/next slots.
- [x] Add pure projection/geometry tests and update existing player UI tests.

## Phase D: Verification

- [x] Run focused `core:lyrics`, `core:model`, `core:data`, and `app` tests.
- [x] Run the complete relevant unit suite.
- [x] Run `:app:lintSideloadDebug` and `:app:assembleSideloadDebug`.
- [x] Run `git diff --check` and review the complete diff against PRD acceptance criteria.

## Risk and rollback points

- Do not remove individual transport/source timeouts; only remove the global/early cutoff.
- Do not let content tier bypass the existing metadata eligibility gates.
- Do not serialize Compose types into `core:model` or cache envelopes.
- Cache schema and fingerprint protocol must change together.
- Preserve current static lyric and online-disabled fallback paths.
