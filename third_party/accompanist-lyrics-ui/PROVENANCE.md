# Vendored Accompanist Lyrics UI

- Upstream: https://github.com/6xingyv/accompanist-lyrics-ui
- Pinned revision: `b0499d8cd1b6f4904d5c45ab7d0a8576edb4e72a`
- License: Apache License 2.0, copied in [`LICENSE`](./LICENSE).
- Copied scope: `src/src/commonMain/kotlin/**`, with the Android implementation of the string
  helpers from `src/src/androidMain/kotlin/**`. Sample application, JVM implementation, resources,
  and upstream multiplatform build files are intentionally excluded.
- Local adaptations: converted the upstream Kotlin Multiplatform common source set into an Android
  library source set, merged the common `expect` string declarations with the pinned Android
  `actual` implementation in `utils/String.kt`, replaced the remote Gaze dependency with the
  sibling `:third_party:gaze-capsule` project, and set `minSdk = 23`. Public package names and
  lyrics composable APIs are unchanged.

Refresh this directory only together with this revision and an updated copy-scope/adaptation note.
