# Implementation Plan

- [x] Read the active frontend/backend contracts and inspect all artwork rendering and clearing call sites.
- [x] Add a bounded, exact-variant decoded bitmap cache with in-flight deduplication and prefetch support.
- [x] Integrate the cache with `AppContainer`, account transitions, and cache-clearing actions.
- [x] Update `rememberRemoteArtworkBitmap` to synchronously reuse cached bitmaps.
- [x] Add Grid prefetch to artist and album focus handling without changing displayed Compact images.
- [x] Replace empty artwork fallbacks with stable placeholders at remaining call sites.
- [x] Add cache behavior tests and run targeted unit tests, lint, and debug compile checks.
- [x] Review the final diff for unrelated changes and update Trellis contracts with the durable convention.

## Validation

- `./gradlew :app:testSideloadDebugUnitTest`
- `./gradlew :app:lintSideloadDebug :app:assembleSideloadDebug`

## Rollback Points

- Cache integration is isolated behind `AppContainer`; UI can fall back to repository-backed loading by reverting the `rememberRemoteArtworkBitmap` integration.
- Focus prefetch is additive and can be removed independently without affecting normal image loading.
