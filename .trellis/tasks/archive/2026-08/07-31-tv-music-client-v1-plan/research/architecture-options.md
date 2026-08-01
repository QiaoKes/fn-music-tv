# Research: Android TV Client Architecture Options

- Query: Compare native Kotlin + Compose for TV + Media3, Flutter, and reuse of the existing SPA in WebView for the TV music V1; recommend a stack, SDK levels, dependency boundaries, and risk controls.
- Scope: mixed (repository/API evidence plus Android, Flutter, and Chromium-owned first-party documentation)
- Date: 2026-07-31

## Findings

### Recommendation

Build V1 as a native Android TV application using **Kotlin, Compose for TV, and Media3 ExoPlayer hosted in a `MediaSessionService`**.

Use these SDK levels for a new project:

| Setting | Recommendation | Reason |
| --- | --- | --- |
| `minSdk` | **23** | It meets the requested API 23+ range and the current Media3 line raised its minimum to API 23. Compose for TV itself works from API 21, so Media3 is the binding floor. |
| `compileSdk` | **36** | Compile against Android 16 APIs with the current stable Android toolchain. |
| `targetSdk` | **36** | A greenfield app should adopt current behavior now. The Google Play TV exception only requires API 34 from 2026-08-31, but selecting 34 would create avoidable migration work and is not a compatibility benefit for API 23 devices. |

Current stable pins evidenced by the official release pages are Compose BOM `2026.06.00`, `androidx.tv:tv-material:1.1.0`, and Media3 `1.10.1`. Pin versions in a version catalog and update them deliberately; do not use dynamic versions.

This is not merely a preference for native UI. It is the only option of the three that simultaneously satisfies:

- API 23 support.
- First-class D-pad focus and TV-focused components.
- A system-visible media session and reliable background audio without inventing a cross-runtime bridge.
- Direct Range playback plus HLS fallback through one supported media engine.
- A credible memory/startup plan for 2 GB and lower-end televisions.

### Decision baseline from this project

- V1 is explicitly a native, remote-operated TV client with system keyboard login, playlist playback, an immersive player, roam, lyrics, and stable focus behavior (`.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md:5`, `:21-35`).
- Every core action must work with direction, select, and back; rapid input must not lose focus or duplicate navigation (`prd.md:56-66`). This makes focus behavior a primary architecture constraint rather than a UI polish item.
- Direct streams use `http.ServeContent`, expose Range/ETag headers, and must handle `200/206/304/416` (`research/api-contract.md:86-96`; `.detail/trim-music-v0.9.16/core/web/controller/file_response.go:11-50`).
- CUE tracks are not correctly clipped by the direct endpoint and must use transcode/HLS (`research/api-contract.md:96-120`; `.detail/trim-music-v0.9.16/core/service/player.go:94-106,141-165`).
- HLS sessions and temporary tokens are volatile; playback needs distinct recovery for decoder failure, stale HLS, and authentication expiry (`research/api-contract.md:98-120,225`).
- Lyrics are raw text, may be slow on first fetch, and require a client-side LRC timeline parser independent from audio startup (`research/api-contract.md:122-133`).
- A 3,500-track playlist previously took seven seconds when fully materialized. The current client still needs bounded pages and incremental queue fill (`research/nas-service.md:231-245`; `.detail/trim-music-v0.9.16/doc/performance-optimization-log.md:4`).
- The observed Web main JavaScript is about 1.65 MB and includes React, HLS.js, and ffmpeg/WASM paths; Web source, package manifests, and source maps are absent from this repository snapshot (`research/nas-service.md:243,258,277-278`). Reuse is therefore deployment reuse, not maintainable source reuse.

### Option comparison

| Criterion | Kotlin + Compose TV + Media3 | Flutter | Existing SPA in WebView |
| --- | --- | --- | --- |
| D-pad and focus | **Strong.** TV Material supplies focus-aware components; Compose provides focus groups and explicit focus control. Still requires screen-level focus tests. | **Medium-low.** Flutter has a generic keyboard focus tree and traversal policies, but the reviewed first-party docs provide no Android TV component system equivalent to Compose for TV. TV spatial traversal and focus restoration remain custom application work. | **Low for this SPA.** DOM focus can be engineered, but the current SPA was not evidenced as TV/D-pad UI. Spatial navigation, key repeat, back, system IME, and focus restoration would require a TV-specific Web rewrite. |
| Background audio / system controls | **Strong.** `MediaSessionService` is the platform-prescribed owner for player/session background playback; system, media-key, and external controllers use the same state. | **Low-medium.** The first-party `video_player` is a video widget backed by ExoPlayer, not a complete TV music `MediaSessionService`. Meeting this requirement still needs native Android service/plugin code and Dart/native state synchronization. | **Low.** HTML audio does not replace the required Android media service/session contract. A native playback bridge would leave two navigation/state runtimes and little meaningful SPA reuse. |
| FLAC, Range, HLS | **Strong.** Media3 supports progressive HTTP and HLS; request headers can be injected for every media request. FLAC uses device decoders by default, with NAS HLS as the compatibility fallback. | **Medium.** Android playback ultimately delegates to a native plugin/ExoPlayer, so capability is possible, but source negotiation, per-request auth, service lifecycle, and error recovery cross a plugin boundary. | **Medium-low.** Browser audio/HLS code exists, but codec and WebView-version behavior varies. Native media integration is still needed for reliable background/system behavior. |
| API 23+ | **Strong.** Current Media3 minimum is API 23. | **Fail.** Flutter `3.44.7` officially supports Android API 24-37 and marks API 23 and earlier unsupported; the first-party `video_player` also says Android SDK 24+. | **Nominal but risky.** WebView exists on API 23, but the engine/update level and supported Web features vary by device; the missing Web source prevents proving the bundle's API 23 browser target. |
| 2 GB TV memory, startup, package | **Best baseline.** No Flutter engine or Web runtime payload is shipped by the app. Dependencies can be kept to TV UI and audio-only Media3 modules; R8 and Baseline Profiles apply directly. | **Higher baseline.** Flutter documents that release artifacts are self-contained with framework/runtime and AOT code. It can perform well, but adds a second runtime without cross-platform value in V1. | **Highest uncertainty.** WebView process/runtime plus JS parsing, SPA state, image cache, and optional WASM compete with media buffers. It also depends on loading server assets before the app becomes interactive. |
| Testing and maintenance | **Strong.** Pure Kotlin rules, repository fakes, Compose UI tests, Media3 test utilities, media-session validation, Macrobenchmark, and physical TV tests fit one toolchain. | **Medium-low.** Dart tests cover UI/domain, but service/session/plugin behavior needs a second native test layer and integration contract. | **Low.** Requires Web, WebView/container, native bridge, browser-version, and media-session coverage; the source required to maintain the current SPA is not present here. |
| Future Android TV expansion | **Strong.** TV home integration, media resumption, Assistant/media keys, Watch Next, and Cast can extend the same Android media/session boundary. | **Medium.** Cross-platform UI would help only after another client is committed; Android TV platform features remain native plugin work. | **Low-medium.** Server-side UI can update quickly, but deeper TV platform features increase bridge surface and hybrid ownership. |

### Recommended runtime design

1. **Single TV activity and Compose for TV UI.** Use `androidx.tv.material3` components for interactive surfaces and regular Compose Foundation lazy lists/grids. Do not mix mobile Material 3 components into the TV theme. Use stable item keys, focus groups, saved focus anchors, and explicit directional overrides only where tested automatic focus search is wrong.
   For the C3 sideload artifact, keep `android.software.leanback`, `android.hardware.type.television`, and touchscreen optional until the projector's declared features are measured; use launcher aliases only as required by its OEM app list. A future Play TV artifact gets a separate store manifest overlay instead of tightening the sideload package.
2. **One playback owner.** `PlaybackService : MediaSessionService` owns the only `ExoPlayer`, `MediaSession`, active queue, current media item, and playback error recovery. The activity connects with `MediaController`; composables and ViewModels never hold another player.
3. **One data boundary.** Decode the API envelope and DTOs once, normalize them into immutable domain models, and expose repositories. This follows the project rule that payload contracts and validation have a single owner (`.trellis/spec/guides/cross-layer-thinking-guide.md:19-50,74-101`).
4. **Separate ordinary queue, roam, and transport state.** Queue/roam reducers are pure Kotlin. Media3 is the transport source of truth; UI state is a projection of `MediaController`, never a parallel mutable playback model. This preserves the ordinary queue when entering roam (`research/api-contract.md:135-144,190-199`).
5. **Resolve a playback source before preparing.** `isCue=true` always selects HLS. Other tracks try direct playback when the reported codec/container is supported, then fall back to a newly created HLS session on decoder/source failure. Recreate HLS on stale-session errors; do not treat those as login expiry.
6. **Attach authorization at the media data-source boundary.** Media3's `DataSource.Factory`/`ResolvingDataSource` can add the raw `Authorization` token to every direct, manifest, and segment request. Use track-scoped query tokens only if an endpoint cannot receive headers; before every query-token request, strip any stale token embedded by a manifest and inject the track's current ticket so renewal stays in the same transcode generation. Never log tokenized URLs.
7. **Load lyrics independently.** Audio preparation must not wait for `/lyric/list`. Parse LRC into a pure domain timeline and derive the active line from controller position plus server offset.

### Module and dependency boundaries

Keep the greenfield V1 to four production Gradle modules. Feature packages can be split later if build or ownership evidence warrants it.

```text
:core:model        pure Kotlin models, errors, queue/roam reducers, LRC parser
     ^
     |
:core:data         API DTO decoding, repositories, auth, preferences, playback-source resolution
     ^
     |
:core:playback     Media3 player/session/service, media DataSource, controller-facing commands
     ^
     |
:app               TV manifest, composition root, navigation, ViewModels, Compose TV screens

:baselineprofile   non-production benchmark/profile generation; depends on :app only
```

Boundary rules:

- `:core:model` has no Android, network, persistence, Compose, or Media3 dependency.
- `:core:data` depends on `:core:model`; API DTOs never escape it. It owns token storage, server identity namespacing, bounded paging, cover URL sizing, and local queue/settings persistence.
- `:core:playback` depends on `:core:model` and narrow repository/source interfaces from `:core:data`. It exposes Media3 `MediaController`/commands upward but does not know screens or navigation.
- `:app` depends downward and owns dependency construction. ViewModels receive repositories/controller facades, not HTTP clients, DTOs, `ExoPlayer`, or service instances.
- Do not introduce a mandatory domain-use-case class for every repository call. Android's official guidance treats the domain layer as optional; add use cases only for reused/complex operations such as `ResolvePlaybackSource`, `StartRoam`, or queue restoration.

Initial production dependencies should stay narrow:

- Compose BOM `2026.06.00`, `activity-compose`, lifecycle/ViewModel Compose, Compose Foundation, and `androidx.tv:tv-material:1.1.0`.
- Media3 `1.10.1`: `media3-exoplayer`, `media3-exoplayer-hls`, and `media3-session`; add `media3-datasource-okhttp` only if the API and media paths intentionally share OkHttp.
- Navigation and DataStore for non-secret local state. Keep the user token behind a secure-storage interface; do not put it in ordinary preferences as plain text.
- An image-loading implementation behind one app-level configuration, constrained to server-supported widths 200/400/800 and a measured memory cache.
- Exclude Media3 DASH, RTSP, IMA, Cast, Transformer, video UI, FFmpeg decoder, embedded Cronet, and WorkManager from V1 unless a requirement activates them. Media3 documents roughly 8 MB package impact for embedded Cronet versus under 1 MB for its OkHttp integration; LAN audio does not justify Cronet.
- Do not use `media3-ui-compose-material3` as the TV control surface. Build the small audio control UI with TV Material while using `MediaController` as its state/command source.

### Media, memory, startup, and package implications

**FLAC and other source codecs.** Android documents platform FLAC decode from Android 4.1, but Media3 explicitly says actual sample-format support depends on the device decoder. Do not bundle FFmpeg for V1. Probe/attempt native playback, use NAS HLS for unsupported sources, and test high-bit-depth/sample-rate FLAC on target televisions. This is smaller and less memory intensive than carrying a general software decoder.

**Range and HLS.** Let Media3 issue Range requests and seeks against the direct endpoint; never eagerly download source files. Add the HLS artifact explicitly. The server supports `.m3u8`, TS, and fMP4 proxy paths, all of which are within Media3's documented HLS container support (`research/api-contract.md:86-120`).

**Memory.** The user-confirmed launch gate is the Vidda C3 Pro (officially marketed with 4 GB RAM); treat Android's stricter low-RAM TV guidance as a compatibility stress guard rather than the primary-device description. During the image-grid-to-player critical journey, keep total process memory below 280 MB and Anonymous+Swap+Graphics below 200 MB on the low-memory target. Always check `ActivityManager.isLowRamDevice()` rather than RAM/resolution heuristics. In low-RAM mode, reduce image prefetch/cache, avoid large background bitmaps, retain only adjacent queue metadata, and release screen image references on navigation. Profile on hardware because some TVs under-report graphics memory.

**Startup.** Draw a focusable local shell before token validation or NAS calls; no blocking network or bitmap decode in `Application.onCreate`. Measure cold TTID/TTFD with Macrobenchmark, call `reportFullyDrawn` only after the first usable screen, and generate a Baseline/Startup Profile for launch, first focus movement, home/library navigation, and opening the player.

**Package.** Enable R8 full optimization and resource shrinking for release. Record AAB download size, installed size, DEX/native library breakdown, and cold-start metrics at every milestone. A hard package number is premature in an empty repo, but bundling a browser UI, Flutter engine, FFmpeg, or Cronet without a measured need is outside the V1 budget.

### Test and maintenance gate

- Pure unit tests: API envelope/error mapping, nullable DTO normalization, LRC edge cases, queue/roam reducers, CUE/HLS decision rules, token/session recovery state machines.
- Repository tests with a local HTTP fixture: `200/206/304/401/416/502`, raw JSON error on an apparent stream URL, Range seek, HLS manifest/segment authorization, temporary-token expiry, and NAS restart.
- Media3 service tests: exactly one player, controller reconnect after activity/process recreation where applicable, audio focus/noisy route, media buttons, foreground-service start/stop, queue transitions, and metadata updates. Use Media3 test utilities and the platform Media Controller Test / Media Session Validator.
- Compose TV instrumented tests: every visible control reachable using only D-pad/select/back; deterministic focus restore after detail/player return; system IME login; held/rapid keys cannot double-open or lose focus; empty/loading/error states remain navigable.
- Performance tests: the Vidda C3 Pro is the launch and decoder gate after M0 records its real API, ABI, launcher, app surface, and codec set. The API 36 Android TV AVD provides deterministic D-pad/layout regression, while a real 2 GB low-tier Android TV target is compatibility stress coverage for memory, input latency, cold start, and long playback. Neither emulator nor low-tier-device success replaces C3 Pro sign-off.
- Audio matrix: representative MP3/AAC/FLAC (including high-resolution FLAC), at least one unsupported source that must transcode, CUE, direct seek, HLS seek, a long track crossing temp-token lifetime, and gap/transition behavior.

### Why the alternatives are not selected

#### Flutter

Flutter is rejected for V1 primarily because it fails the explicit API 23 compatibility requirement: the current first-party support matrix says API 24-37 and marks API 23 and earlier unsupported. Its first-party Android video plugin has the same SDK 24+ floor.

Even if API 23 were dropped, it would not remove the hard Android work. A TV music app still needs a native foreground `MediaSessionService`, Media3 source/auth customization, media keys, and Android service lifecycle behavior. Flutter would add a generic focus system and a Dart/native bridge around the most failure-sensitive subsystem. There is no confirmed non-Android client in scope to repay that cost. Reconsider Flutter only if cross-platform delivery becomes a committed requirement, API 24+ is accepted, and the team owns a tested native Media3/session plugin.

#### Existing SPA in WebView

WebView is rejected as the shipping client, not as a throwaway prototype. The current SPA lacks checked-in source and is not evidenced as a TV UI. Reusing it would still require a TV-specific focus/navigation rewrite and a native Media3/session bridge, producing three state boundaries: DOM/JavaScript, Android container, and playback service.

Android also documents that WebView does not share browser app data, so the existing browser login is not reusable. JavaScript must be enabled and any native bridge expands the security surface. The observed JS/HLS/ffmpeg/WASM path adds startup and memory uncertainty on a platform where image grids and media buffers already compete for RAM. WebView implementation/version variability on older TVs further weakens API 23 guarantees.

The live NAS uses cleartext HTTP. With target API 36, both native and WebView approaches need an explicit Network Security Configuration exception, because cleartext is disabled by default from API 28 targets and WebView honors that policy. This should be a temporary LAN compatibility measure; TLS is the real fix. WebView does not improve that security boundary.

#### Traditional Views / Leanback

Do not start a greenfield app on Leanback: Android's current documentation marks the artifact and classes deprecated and directs TV apps to Compose for TV. A narrow `AndroidView`/RecyclerView interop island remains an acceptable contingency if physical-device measurement uncovers a specific Compose/OEM defect, but it should not define navigation, theming, or playback architecture.

### Major risks and controls

| Risk | Control / exit criterion |
| --- | --- |
| Compose focus differs across nested rows, overlays, or OEM key repeat | Prototype the home, playlist, player overlay, and login focus paths first. Use stable keys/focus groups, explicit edges only after tests, and run held-key tests on two physical TV families before visual polish. |
| Device decoder accepts metadata but fails a FLAC/container at runtime | Treat capability checks as hints, catch Media3 source/decoder errors, retry once through HLS, and persist no permanent device blacklist until the codec matrix is measured. |
| CUE/direct regression | A pure source-selection test must assert every `isCue` item resolves to HLS; integration test verifies only the CUE segment plays. |
| HLS/temp token/session expires or NAS restarts | Model HLS recreation independently from account authentication. Bound retries, refresh the URL/session, keep UI/controller responsive, and skip/report only after recovery fails. |
| Foreground service behavior under target 36 | Declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and `foregroundServiceType="mediaPlayback"`; start playback only from a user action and stop the service promptly after playback ends. |
| 2 GB TV memory pressure from covers and player artwork | Use exact thumbnail widths, bounded lazy lists and image cache, no blurred duplicate full-screen bitmap, `isLowRamDevice` degradation, and the 280/200 MB profiling gates above. |
| Current HTTP origin exposes password/token and needs cleartext opt-in | Prefer HTTPS at the NAS/reverse proxy. If V1 must support HTTP, show an explicit trust boundary, avoid URL tokens where headers work, never log credentials, and scope the network exception as narrowly as deployable addressing permits. |
| Dependency churn at the API 23 floor | Pin stable Compose TV/Media3 versions, run API 23 compile/instrumented smoke tests on every update, and review release notes before upgrading. Do not adopt a library whose manifest raises the floor. |

### External first-party references and current versions

- Android recommends Compose for TV, says it works from API 21, and warns against mixing mobile and TV Material: [Use Jetpack Compose on Android TV](https://developer.android.com/training/tv/playback/compose).
- Current TV artifacts include stable `tv-material:1.1.0` (2026-05-06): [Compose for TV release notes](https://developer.android.com/jetpack/androidx/releases/tv).
- Media3 `1.10.1` is current on the reviewed release page and the current line raises `minSdk` to 23: [Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3).
- Android requires the player/session to live in `MediaSessionService` or `MediaLibraryService` for background playback: [Background playback with a MediaSessionService](https://developer.android.com/media/media3/session/background-playback).
- Media3 supports injecting authentication headers for every HTTP interaction through a custom/Resolving data source: [Customize ExoPlayer](https://developer.android.com/media/media3/exoplayer/customization).
- Decoder support is device-dependent, while HLS support covers MPEG-TS, fMP4/CMAF, ADTS AAC, and MP3: [Media3 supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats) and [Media3 HLS](https://developer.android.com/media/media3/exoplayer/hls).
- TV navigation is D-pad-first and requires reachability, predictable scrolling, and clear focus: [TV navigation](https://developer.android.com/training/tv/get-started/navigation).
- The official low-RAM TV limits and image/network/startup recommendations are documented in [Optimize memory usage on TV](https://developer.android.com/training/tv/playback/memory).
- From 2026-08-31 Google Play requires API 36 generally but permits Android TV submissions at API 34; target 36 remains the greenfield recommendation: [Google Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk).
- R8 reduces code, resource, runtime memory, and startup footprint; Baseline Profiles precompile critical paths: [Enable app optimization with R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization) and [Baseline Profiles overview](https://developer.android.com/topic/performance/baselineprofiles/overview).
- Flutter `3.44.7` supports Android API 24-37 and not API 23: [Flutter supported deployment platforms](https://docs.flutter.dev/reference/supported-platforms). Its focus documentation describes a generic keyboard focus tree: [Flutter focus system](https://docs.flutter.dev/ui/interactivity/focus). Flutter also documents its self-contained artifact size concern: [Measuring Flutter app size](https://docs.flutter.dev/perf/app-size).
- The first-party Flutter `video_player` is an SDK 24+ video-widget plugin backed by ExoPlayer, not a documented TV media-session architecture: [video_player package](https://pub.dev/packages/video_player).
- WebView is an embedded page surface with JavaScript/bridge security responsibilities and does not share browser app data: [Build web apps in WebView](https://developer.android.com/develop/ui/views/layout/webapps/webview).
- Android marks Leanback deprecated in favor of Compose for TV: [Leanback release notes](https://developer.android.com/jetpack/androidx/releases/leanback).
- Cleartext is disabled by default for apps targeting API 28+, requiring an explicit exception for the current HTTP NAS: [Network Security Configuration](https://developer.android.com/privacy-and-security/security-config).

### Related specs

- `.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md:5-43`: native TV product scope, D-pad requirements, and architecture/performance planning deliverables.
- `.trellis/tasks/07-31-tv-music-client-v1-plan/research/api-contract.md:86-225`: direct/HLS/CUE rules, lyrics, roam/queue separation, error recovery, paging, and client data flow.
- `.trellis/tasks/07-31-tv-music-client-v1-plan/research/nas-service.md:231-258`: measured backend scale, Web bundle evidence, and TV implications.
- `.trellis/spec/guides/cross-layer-thinking-guide.md:19-50,68-101`: map contracts and keep decoding/validation at one boundary.
- `.trellis/spec/guides/code-reuse-thinking-guide.md:61-97`: centralize repeated contract logic but do not create abstractions before they remove real complexity.
- `.trellis/spec/frontend/index.md:9-22`: frontend-specific project conventions are still unfilled; this architecture decision should later seed native-client specs rather than being inferred from placeholder Web conventions.

## Caveats / Not Found

- The repository is effectively empty of client product code. No Kotlin, Flutter, Gradle, Dart, or checked-in SPA source exists, so package size, startup, memory, focus latency, and decoder behavior cannot yet be benchmarked. The recommendations above define what implementation must measure; they are not fabricated measurements.
- The deployed Web SPA was observed through compiled assets, but its source, browser target, dependency lockfile, build configuration, and source maps were not found. API 23 WebView compatibility cannot be established.
- No representative audio was streamed and no transcode session was created during prior read-only research. Direct FLAC, Range seek, HLS startup, heartbeat cadence, and token renewal must be validated on the actual NAS and target TVs before calling the playback matrix complete (`research/nas-service.md:281-285`).
- The backend does not publish transcode codec/profile capabilities. The recommended HLS fallback depends on agreeing and testing at least one server output profile; do not hard-code AAC or another codec as guaranteed before that test (`research/api-contract.md:105-120,203-225`).
- Flutter's official matrix lists Android rather than a separately tested Android TV tier. The absence of a first-party TV component/session architecture in the reviewed documentation is a support-gap observation, not a claim that a custom Flutter TV app is impossible.
- Target SDK policy changes over time. Recheck the Play requirement and Android 16 behavior-change checklist at implementation/release time, while retaining `minSdk=23` unless product scope changes.
