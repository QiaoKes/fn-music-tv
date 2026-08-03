# Design

## Boundaries

- Add an app-scoped decoded bitmap cache owned by `AppContainer`.
- Keep raw byte retrieval and its disk/memory cache in `MusicRepository` unchanged.
- Keep rendering and focus hooks in the Compose UI layer.

## Cache Contract

`ArtworkBitmapCache` stores decoded `Bitmap` values under `(coverId, CoverVariant)` keys. It exposes synchronous `peek`, suspending `get`, fire-and-forget `prefetch`, and `clear` operations.

- `get` returns an exact cached bitmap or joins/starts one app-scoped load.
- In-flight work is shared so cancellation of one composable does not cancel a load needed by another consumer.
- A small semaphore bounds concurrent decode/load work.
- An Android `LruCache` bounds decoded memory by bitmap allocation bytes.
- A generation token prevents a load that was active during `clear` from repopulating stale data.

## UI Data Flow

1. `RemoteArtwork` synchronously peeks the exact cache key for its initial Compose state.
2. On a miss, it awaits `get`; all consumers join the same work.
3. The surface keeps its fixed dimensions and renders a deterministic placeholder until the exact bitmap is available.
4. Artist and album lockups keep displaying Compact artwork, but focus triggers a Grid prefetch under a separate key.
5. Detail pages request Grid only and therefore either render the exact Grid bitmap or their stable placeholder.

## Lifecycle And Clearing

`AppContainer` clears decoded bitmaps when the authenticated namespace changes, on sign-out, and alongside explicit artwork/all-cache clearing. Raw byte cache APIs remain the source of truth for persistent cache deletion.

## Tradeoffs

- Exact-variant isolation costs more memory/network than temporarily reusing Compact images, but avoids visible sharpening and honors the TV presentation requirement.
- Focus-only prefetch limits image quota use and memory pressure while covering the normal remote-control navigation path.
- A 40 MiB decoded cache is large enough for several Grid images while remaining bounded on TV devices.
