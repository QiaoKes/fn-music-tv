# Bug Analysis: Home feature covers appear after D-pad input

## 1. Root Cause Category

- **Category**: E - Implicit Assumption, with D - Test Coverage Gap.
- **Specific cause**: The Home feature deck requested only `CoverVariant.Grid`, while the screens users
  visited first populated `CoverVariant.Compact`. Cache keys correctly isolate variants, but the UI
  assumed Grid had already been warmed. During the Grid miss it rendered title-initial placeholders;
  the first D-pad event happened after the asynchronous request completed and made the timing look
  input-dependent.

### Bayesian check

| Hypothesis | Prior | Evidence | Posterior |
|------------|-------|----------|-----------|
| Focus/input blocks image publication | 35% | One key appeared to reveal images, but no artwork path reads key state | 5% |
| Grid cache miss plus normal async completion | 45% | Exact cache keys isolate Compact/Grid and Home alone requests Grid | 90% |
| Server returned intermittent placeholders | 20% | Cover IDs and later images were stable without another data request | 5% |

Confidence is above 90% because the cache key and call-site evidence predict the observed first-frame
state, and the regression tests reproduce the variant sequence directly.

## 2. Why Surface Fixes Would Fail

1. Forcing recomposition on D-pad or focus change would preserve the false input dependency and still
   show title cards on cold entry.
2. Sharing one bitmap key across Compact and Grid would hide the miss but violate image-quality
   boundaries and could permanently serve a list thumbnail to a detail header.
3. Replacing the placeholder alone would remove the ugly text but would not improve time-to-image.

## 3. Prevention Mechanisms

| Priority | Mechanism | Specific action | Status |
|----------|-----------|-----------------|--------|
| P0 | Architecture | Add explicit `getProgressively` with an opt-in fallback variant | DONE |
| P0 | UI contract | Home decorative decks use real Compact imagery, then exact Grid | DONE |
| P0 | Test coverage | Assert intermediate-before-exact and fallback-on-exact-failure | DONE |
| P1 | Device check | Capture Home after launch without sending remote input | DONE |
| P1 | Documentation | Scope progressive fallback to decorative Home decks, not details | DONE |

## 4. Systematic Expansion

- **Similar issues**: Any decorative summary that requests a larger variant than the list that warmed
  the cache can show an avoidable placeholder.
- **Design improvement**: Keep exact cache keys, but make progressive fallback an explicit call-site
  decision rather than an automatic global downgrade.
- **Process improvement**: Cold-entry visual checks must not send input before the first screenshot;
  otherwise input timing can conceal asynchronous first-frame defects.

## 5. Knowledge Capture

- [x] Updated `.trellis/spec/frontend/android-tv-interaction.md`.
- [x] Updated task PRD and design.
- [x] Added JVM regression coverage.
- [x] Checked for `src/templates/markdown/spec/`; this project has no template mirror to sync.
