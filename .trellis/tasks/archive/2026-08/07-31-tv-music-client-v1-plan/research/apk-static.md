# Research: NetEase Cloud Music TV APK and remote-first UI reference

- Query: Inspect the corrected NetEase Cloud Music TV APK for package/version/SDK identity, launcher and TV declarations, native UI technology, landscape and D-pad behavior, visual/resource patterns, runtime readiness, copyright-safe reference boundaries, and a reproducible validation runbook.
- Scope: mixed (local APK static analysis, read-only local Android environment checks, and confirmed screenshots of the corrected APK; official Android documentation for comparison)
- Date: 2026-07-31

## Findings

### 1. Scope correction and evidence boundary

This report uses only the following TV APK:

`/Users/saki/Downloads/NeteaseCloudMusic_MusicTV_official_1.1.80.260122145233.apk_official_1.1.80.260122145233_3264.apk`

The `.detail/` NetEase APK, including the 9.5.60 phone build previously encountered there, is the wrong mobile application and was **not used** for any conclusion in this report. The `research/screens/netease-*` captures are also from that wrong mobile application and are **excluded**. Earlier temporary previews from the wrong APK were deleted.

The corrected runtime evidence is limited to `research/screens/tv-*.png`, which the main session confirmed came from package `com.netease.cloudmusic.tv` version `1.1.80`. Static conclusions remain distinguishable from screenshot observations below.

The task PRD has been updated to identify this package as the valid TV sample and
to exclude the earlier `.detail/` mobile APK.

### 2. APK identity and integrity

| Property | Result |
| --- | --- |
| Package | `com.netease.cloudmusic.tv` |
| App label | `网易云音乐` (`NetEaseCloudMusic` in English resources) |
| Version | `1.1.80` (`versionCode=1001080`) |
| SDK | `minSdk=17`, `targetSdk=29`, `compileSdk=30` / Android 11 |
| APK size | 48,995,677 bytes (about 46.7 MiB) |
| SHA-256 | `b0bba5915590d7ff397c564549718c0ec82711bb48eafec2b32f2de8dcd0c87b` |
| Native ABIs | `arm64-v8a`, `armeabi-v7a` |
| DEX files | 4 (`classes.dex` through `classes4.dex`) |
| APK signatures | v1 and v2 verify; v3/v3.1/v4 absent |
| Signer | `CN=LiangJian, OU=Corp.Netease, O=CloudMusic, L=HangZhou, ST=ZheJiang, C=310000` |
| Signer certificate SHA-256 | `54254d2be09daef48dedc2b4a4f497d153e14ed9d70814fc9c360ee9240827f7` |

The package/version/SDK values are encoded in `TV APK :: AndroidManifest.xml:4-14`. `aapt dump badging` independently resolves the same package, version, label, launcher, and ABIs. Signature verification establishes internal APK consistency, but the filename and certificate subject are not an independent chain-of-trust proof that the download came from an official distribution channel.

The manifest is broad: 103 activities, 23 services, 5 receivers, 9 providers, and 37 requested permissions. Forty-six activity declarations use the app namespace; 36 of those are explicitly landscape. The large shared code/resource surface means an unused resource name alone is weak evidence. The UI findings below prioritize layouts reached through activity/view-binding call chains and then use named TV resources only as corroboration.

### 3. TV, launcher, and landscape declarations

- Main/launcher activity: `com.netease.cloudmusic.app.LoadingActivity` (`TV APK :: AndroidManifest.xml:164-180`). Both standard `LAUNCHER` and `LEANBACK_LAUNCHER` resolve to this component.
- TV hardware: touchscreen is explicitly optional, while `android.hardware.type.television` is required (`TV APK :: AndroidManifest.xml:19-25`).
- Core activities are fixed to landscape via `screenOrientation="0"`: launcher, `MainActivity`, playlist, login, settings, daily recommendation, and player activities (`TV APK :: AndroidManifest.xml:164-251,327-330`).
- `MainActivity`, login, recent-play, settings, daily recommendation, and the main player use launch mode `2` (`singleTask`), reducing duplicate task entries during repeated remote activation.
- The application sets `hardwareAccelerated=true`, `largeHeap=true`, `resizeableActivity=true`, and an AndroidX component factory (`TV APK :: AndroidManifest.xml:141-154`).
- A `design_width_in_dp=960` metadata value is present (`TV APK :: AndroidManifest.xml:220-222`), matching the bundled `me.jessyan.autosize` runtime and explaining the logical 960-wide layouts rendered at 1920x1080.

Important deviations from current Android TV guidance:

- `LEANBACK_LAUNCHER` is present, but the manifest does not declare the `android.software.leanback` feature.
- Neither `<application>` nor the launcher activity declares `android:banner`. `aapt` reports an empty Leanback banner. Current Android documentation expects a TV launcher banner, so a new client should not inherit this omission without testing its exact distribution/launcher requirements.
- Requiring `android.hardware.type.television` is sample-specific. A new TV-only app should choose its feature filters against its intended Google TV, Android TV, and vendor-store distribution rather than copying this declaration mechanically.

### 4. UI technology and application structure

The corrected APK is a native Android View application, not a web-wrapper or cross-platform runtime:

- Kotlin metadata and Kotlin standard library/coroutines are bundled.
- AndroidX Leanback is substantial: 6,042 defined methods. Bundled version markers identify `leanback 1.1.0-beta01`, `leanback-tab 1.1.0-beta01`, and `leanback-paging 1.1.0-alpha08`.
- Other defined packages include AppCompat (4,072 methods; version marker `1.2.0`), RecyclerView (2,111 methods; version marker `1.2.0`), Data Binding (729), View Binding, Lottie (1,320), ConstraintLayout, Lifecycle/ViewModel, and AutoSize (205).
- No package match was found for Jetpack Compose, Flutter, React Native, or Cordova.
- The UI uses generated ViewBinding classes plus custom remote widgets such as `MusicTVTabLayout`, `TVLeanbackViewPager`, `TvFocusFrameLayout`, `TVButton`, `TVFixedButton`, and `TVIconImageView`.
- Native libraries include app-specific audio player/effects/visualizer binaries and FFmpeg for both supported ABIs. This is evidence of a native playback stack, not a reusable implementation for this project.

This is useful architectural evidence, but not a mandate to use the APK's aging library versions or copy its class structure. The V1 architecture should be selected against this project's service contract, maintenance needs, and target TV OS range.

### 5. Home shell and information architecture

Static evidence:

- The main layout is a full-screen `TvFocusFrameLayout` containing a `TVLeanbackViewPager`, with a separate top `MusicTVTabLayout` (`TV APK :: res/layout/af.xml:7-23,31-55`). The focus container explicitly redirects upward focus to the tab row.
- `MainActivity` requests tab focus, observes global focus changes, filters rapid directional input, and forwards key-down/key-up to an embedded player fragment before falling back to the activity (`TV APK :: MainActivity.smali:696-708,3499-3526,3665-3697,4110-4214`).
- The fallback tab model contains atmosphere, recommendation, discovery, family, personal, and search sections, with discovery subcategories (`TV APK :: assets/localMainTabs.json:1-130`). This is evidence of a shallow top-level horizontal navigation model, not a recommendation to reproduce its channel count.
- Fallback recommendation data is organized by presentation type (`GREETING`, `BIG_CARD`, `MUSIC`, `PLAYLIST`) and deep-links each item to login, player, roam, or playlist routes (`TV APK :: assets/local_Recommend.json:1`). This suggests server-driven rows with explicit card types and actions.

Corrected screenshot evidence (`research/screens/tv-home.png`, 1920x1080):

- A persistent now-playing pill occupies the upper-left corner with artwork, title, and artist. It remains visually separate from the centered primary tab row, giving playback context a stable global return point.
- The selected top tab is high-contrast and underlined. The body begins with a time-aware greeting, then an art-first horizontal row of large cards, followed by a denser song row.
- Cards combine large square/portrait artwork with a short caption band; the next card remains partially visible at the right edge, clearly signaling horizontal continuation.
- The overall background is low-contrast and content-toned while text and selected navigation stay bright. This preserves a calm ten-foot viewing hierarchy.
- The screenshot shows many product channels because it is the reference app. The project PRD deliberately limits V1 to `首页` and `我的` (`prd.md:21-26,53-55`); only the spacing rhythm, global now-playing access, and row navigation principle are transferable.

### 6. Playlist detail pattern

The playlist activity resolves a dedicated landscape detail layout (`TV APK :: res/layout/ad.xml:1-63`) with:

- a compact header above a fragment-hosted track list;
- a 120dp rounded cover at left, title/metadata in the middle, and a fixed action cluster at right (`TV APK :: res/layout/n6.xml:22-43,69-177`);
- remote-focusable actions including play all and optional mode-specific actions (`TV APK :: res/layout/n6.xml:178-254`);
- explicit `nextFocusUp` links for actions and separate focusable previous/next page controls below the list (`TV APK :: res/layout/ad.xml:27-58`).

The reusable principle is a stable header/list boundary, one dominant action, deterministic focus return, and paging controls that do not force long D-pad traversal. Exact cover size, button geometry, icons, labels, and secondary modes are proprietary reference expression and should not be copied.

### 7. Immersive player and lyrics

Static evidence:

- `PlayerActivityV2` hosts `PlayerFragment` in a full-screen container. The generated binding resolves `TV APK :: res/layout/d_.xml`.
- The player layout layers a full-screen content fragment, artwork/loading layer, hidden bottom controller panel, karaoke/lyrics layer, and auxiliary overlay (`TV APK :: res/layout/d_.xml:6-44,122-144`).
- The bottom controller panel is 250dp high with a progress row, title/artist slots, central action cluster, and left/right utility actions (`TV APK :: res/layout/d_.xml:32-121`; `TV APK :: res/layout/n0.xml:1-48`; `TV APK :: res/layout/km.xml:3-98`).
- Controller actions include previous, play/pause, next, favorite, quality, lyrics mode, play style, and queue. Some labels start at zero text size and are rendered through custom button state logic, making the icon-first resting state compact.
- Resource strings explicitly cover no-lyrics, instrumental, lyric-load, repeat, single-repeat, and shuffle states (`TV APK :: res/values/strings.xml:2912,3411-3412,3860-3862,3919-3922,4318-4320`).
- `PlayerFragment` gives specialized child/player helpers the first opportunity to consume key events, then handles player controls and controller visibility (`TV APK :: PlayerFragment.smali:2102-2213`). On key-up it recognizes both D-pad center (`23`) and Enter (`66`) before settling the controller state (`TV APK :: PlayerFragment.smali:2215-2252`).

Corrected screenshot evidence (`tv-after-consent.png`, `tv-controls.png`, `tv-left.png`, and `tv-back.png`, all 1920x1080):

- The resting player is genuinely full-bleed. A large vinyl/album object anchors the left half; track title/artist sits at the top-right; synchronized lyrics form a vertical reading column below.
- The active lyric line is much larger, bold, and near-white. Adjacent lines remain present but progressively dim, allowing context without competing for attention.
- The background is a restrained two-sided ambient color field derived from the current artwork, while the center stays dark enough for lyric contrast. It is not a decorative standalone gradient detached from content.
- `tv-left.png` shows the transient control state: a full-width progress line and time endpoints appear above a bottom action row. Transport controls remain centered; secondary actions sit at the far edges. The lyric column stays visible rather than being replaced by a modal controller surface.
- The `tv-left.png` -> `tv-back.png` pair is consistent with Back first dismissing the controls overlay while leaving playback visible. Because this researcher did not record the input sequence, treat that as a supported inference from the confirmed filenames/screenshots, not a fully instrumented behavior claim.

Transferable player principles for V1 are: a quiet resting state, one dominant lyric line, nearby lyric context, artwork-derived ambience, controls revealed on demand, a stable central transport cluster, and Back dismissing transient UI before leaving playback. Do not copy the vinyl/tonearm object, exact lyric typography, artwork treatment, gradient/color values, icons, or geometry.

### 8. Login and first-run states

The reference APK's login activity presents a full-screen `LoginDialog`. It begins with a centered loading state and then renders QR content from runtime data (`TV APK :: LoginDialog.smali:1504-1541,1622-1670`; `TV APK :: res/layout/mt.xml:2-103`). The decoded layout uses a dark full-screen base, a left promotional/info image, and a right white QR card with scan instructions. The QR bitmap is supplied by observed ViewModel state rather than stored as a reusable login image (`TV APK :: LoginDialog.smali:564-616`).

The corrected `tv-launch.png` shows a separate first-run privacy/terms gate: a centered dark panel over a black background, high-contrast title, readable body text, and two large remote-sized actions along the bottom.

Neither pattern changes V1 login scope. The PRD explicitly requires username/password entry through the system TV keyboard and excludes QR, phone scan, and third-party authorization (`prd.md:21-23,53-54`). Safe lessons are limited to explicit loading/success/error states, generous action targets, and a clear exit path.

### 9. Focus and remote interaction model

The APK contains stronger D-pad evidence than a generic Leanback dependency:

- Main content is wrapped in a custom focus frame with explicit directional search attributes (`TV APK :: res/layout/af.xml:7-19`).
- Main activity records old/new global focus, re-requests the tab strip when needed, and suppresses excessively rapid directional repeats (`TV APK :: MainActivity.smali:3499-3526,3665-3697,4110-4148`; `TV APK :: com.netease.cloudmusic.tv.activity.e.smali:209-318`). The filter distinguishes horizontal (`21/22`) and vertical (`19/20`) D-pad keys.
- `TVButton` focus state optionally animates from `1.0x` to `1.1x` over 150ms and changes text color; losing focus reverses it (`TV APK :: TVButton$c.smali:94-113,198-217`). Press state briefly lowers alpha and restores it after 200ms.
- Focusable controls are explicitly declared in playlist actions, paging controls, player buttons, and retry states. A network-error state provides a focused retry action (`TV APK :: res/layout/nl.xml:2-38`), while empty content has its own static state (`TV APK :: res/layout/my.xml:2-21`).
- The bundled player tutorial maps up to help, down to more actions, left/right repeat or hold to previous/next/seek behavior, center to play/pause and controller display, long center to favorite, and menu to an alternate player (`TV APK :: res/values/strings.xml:5500-5506`). These mappings demonstrate deliberate remote semantics, but V1 should adopt only mappings that remain predictable and discoverable for its smaller scope.

Still screenshots cannot prove a complete focus graph. In `tv-home.png`, the current tab and now-playing pill are visually prominent, but selection and keyboard focus cannot be separated confidently from a single frame. Static code proves focus scaling exists; a proper TV-device traversal test must prove it is consistently visible and free of dead ends.

### 10. Visual language and state design

Repeated static patterns include:

- dark neutral backgrounds (`#151515` in common TV surfaces) with white text at several alpha levels;
- translucent white checked/pressed surfaces instead of heavily bordered containers (`TV APK :: res/values/colors.xml:657-686`);
- rounded tab and action shapes, selected setting strokes, horizontal/vertical tab selectors, and a dedicated seekbar (`TV APK :: res/drawable/t_bg_tab_horizontal.xml:2-5`, `t_bg_btn_setting_selected.xml:2-8`, `t_play_seekbar_vertical.xml:2-25`);
- named TV resources for mini-player surfaces, playlist play, card placeholders, error/empty/network states, drawer, and settings;
- 1.1x focus scale plus contrast change rather than scale alone.

The visually inspected TV-specific empty/error/no-network bitmaps are light monochrome illustrations intended for dark backgrounds. They and the NetEase launcher/brand assets must not be copied into the product.

### 11. Copyright-safe use of the reference

Use the sample as behavioral research, not as an asset or source-code donor:

Safe pattern-level references:

- remote-first focus graph and explicit focus return;
- selected-tab hierarchy and horizontally continuing content rows;
- stable now-playing return point;
- playlist header + list + one dominant action;
- full-screen player with on-demand controls and synchronized lyric emphasis;
- distinct loading, empty, network error, and retry states.

Do not reuse or closely reproduce:

- NetEase names, marks, red launcher icon, certification marks, or deep-link names;
- bundled icons, illustrations, fonts, record/tonearm artwork, screenshots, album art, recommendation JSON, URLs, or copy;
- exact card composition, dimensions, spacing, color values, gradients, lyric typography, motion timings, or focus treatment as a combined trade-dress-like whole;
- decompiled Kotlin/Java/smali, custom widget implementations, native libraries, or extracted resource XML.

Create original branding, iconography, copy, assets, spatial proportions, and motion. Retain only independently implemented interaction principles justified by TV ergonomics and the project PRD. This is an engineering boundary, not legal advice.

### 12. Local ADB/emulator readiness and actions not taken

Host tools found:

- Android SDK: `/Users/saki/Library/Android/sdk`
- `adb 1.0.41`, Platform Tools `37.0.0`, Darwin arm64
- Android Emulator `36.5.10.0`
- One AVD: `Pixel_Android_35`
- Only installed system image: `system-images;android-35;google_apis;arm64-v8a`

During this research, another session/user started `Pixel_Android_35`, installed the corrected APK, accepted the first-run flow, and navigated it. Read-only checks found:

- device `emulator-5554`, API 35, model `sdk_gphone64_arm64`;
- installed package version `1.1.80` / `1001080`, matching the corrected APK;
- both launcher categories resolve to `com.netease.cloudmusic.app.LoadingActivity`;
- the app was focused in `MainActivity` at 1920x1080 landscape when inspected.

This researcher did **not** start, stop, rotate, install, uninstall, launch, clear data, grant permissions, or inject remote keys. The read-only checks and confirmed screenshots were used as supplied.

The existing AVD is not a TV device:

- AVD config has `hw.dPad=no`, `hw.initialOrientation=portrait`, Google APIs (not Android TV/Google TV), and no Play Store.
- Package-manager features include touchscreen/faketouch but not `android.hardware.type.television` or `android.software.leanback`.
- No Android TV/Google TV system image is installed locally.

It is adequate for a fast ARM64/API-compatibility and landscape-rendering smoke test. It is **not** authoritative for TV launcher/banner appearance, real remote focus dispatch, ten-foot density, overscan/safe areas, controller reconnects, TV system keyboard behavior, accessibility, or store filtering. The APK's `minSdk=17` and ARM64 library make it CPU/API compatible, while its old `targetSdk=29` and broad legacy permission set can expose compatibility or privacy behavior on API 35.

### 13. Reproducible runbook for a proper TV target

Prefer a clean ARM64 Android TV or Google TV AVD, or a disposable physical TV device with debugging enabled. Do not use a real account in the third-party reference app.

1. Confirm and select the target explicitly:

   ```sh
   adb devices -l
   TV_SERIAL=emulator-5554
   adb -s "$TV_SERIAL" shell getprop ro.build.version.sdk
   adb -s "$TV_SERIAL" shell pm list features | rg 'television|leanback|touchscreen'
   ```

2. Check before modifying the device:

   ```sh
   adb -s "$TV_SERIAL" shell pm path com.netease.cloudmusic.tv
   adb -s "$TV_SERIAL" shell dumpsys package com.netease.cloudmusic.tv | rg 'versionCode=|versionName='
   ```

3. Only on a disposable target where installation is authorized and needed, install without blanket runtime grants:

   ```sh
   adb -s "$TV_SERIAL" install -r '/Users/saki/Downloads/NeteaseCloudMusic_MusicTV_official_1.1.80.260122145233.apk_official_1.1.80.260122145233_3264.apk'
   ```

   Do not add `-g`. If a signature conflict occurs, use a clean AVD rather than uninstalling an existing user-owned package/data set.

4. Resolve and launch the TV entry point:

   ```sh
   adb -s "$TV_SERIAL" shell cmd package resolve-activity --brief \
     -a android.intent.action.MAIN \
     -c android.intent.category.LEANBACK_LAUNCHER \
     com.netease.cloudmusic.tv
   adb -s "$TV_SERIAL" shell am start -W \
     -n com.netease.cloudmusic.tv/com.netease.cloudmusic.app.LoadingActivity
   ```

5. Exercise only the standard remote surface first:

   | Key | Numeric code | Purpose |
   | --- | ---: | --- |
   | Up | 19 | move focus / player help depending on state |
   | Down | 20 | move focus / reveal player actions depending on state |
   | Left | 21 | move focus / seek or previous behavior in player |
   | Right | 22 | move focus / seek or next behavior in player |
   | D-pad center | 23 | select or play/pause |
   | Enter | 66 | alternate select key |
   | Back | 4 | dismiss transient UI, then navigate backward |
   | Media play/pause | 85 | platform media event smoke test |

   Example:

   ```sh
   adb -s "$TV_SERIAL" shell input keyevent 20
   adb -s "$TV_SERIAL" shell input keyevent 23
   adb -s "$TV_SERIAL" shell input keyevent 4
   ```

6. Validate behavior rather than only appearance:

- initial focus is visible and deterministic on every screen;
- every visible action is reachable with four directions and select;
- rows and long song lists scroll while keeping focus stable;
- rapid repeat does not lose focus, double-open a destination, or skip unpredictably;
- Back closes the player overlay before leaving the player, and repeated Back eventually reaches the TV home screen;
- now-playing returns to the same playback context;
- no-lyrics, instrumental, empty, loading, offline, retry, and expired-session states remain remote-operable;
- controller disconnect/reconnect and app background/foreground preserve focus and playback state;
- launcher icon/banner and app visibility are correct on the actual TV launcher.

For observation, use non-mutating commands such as `adb -s "$TV_SERIAL" shell dumpsys window displays`, `adb -s "$TV_SERIAL" shell pidof com.netease.cloudmusic.tv`, and a filtered `adb logcat`. Avoid clearing logs/data or granting all permissions merely to simplify inspection.

### 14. Product implications for this V1

- Keep the PRD's two top-level destinations, not the reference's many channels. Use the reference only to set horizontal rhythm and focus hierarchy.
- Preserve the PRD's stable upper-left now-playing entry; the corrected home screenshot validates that this is a mature TV pattern.
- Make the playlist screen action-led: art/title context, one prominent play-all action, then a focus-stable song list.
- Make the player restful by default and reveal controls transiently. Lyrics should remain the primary reading target, with clear instrumental/no-lyrics/load-failure variants.
- Define key ownership by layer: global shell, focused list/card, player surface, and transient overlay. Each layer should return handled/unhandled explicitly so Back and D-pad events do not leak unpredictably.
- Test on a true TV image before accepting any focus or typography decision. Pixel landscape screenshots are valuable composition evidence but not a TV interaction quality gate.

### 15. Files found

| Path | Description |
| --- | --- |
| `/Users/saki/Downloads/NeteaseCloudMusic_MusicTV_official_1.1.80.260122145233.apk_official_1.1.80.260122145233_3264.apk` | Corrected TV APK used for every static package/code/resource conclusion. |
| `.trellis/tasks/07-31-tv-music-client-v1-plan/research/screens/tv-launch.png` | Corrected APK first-run terms/privacy gate at 1920x1080. |
| `.trellis/tasks/07-31-tv-music-client-v1-plan/research/screens/tv-after-consent.png` | Corrected APK immersive player resting state. |
| `.trellis/tasks/07-31-tv-music-client-v1-plan/research/screens/tv-controls.png` | Corrected APK lyric/player state captured during the confirmed run. |
| `.trellis/tasks/07-31-tv-music-client-v1-plan/research/screens/tv-left.png` | Corrected APK player with progress and control overlay visible. |
| `.trellis/tasks/07-31-tv-music-client-v1-plan/research/screens/tv-back.png` | Corrected APK player after the captured Back step, with overlay hidden. |
| `.trellis/tasks/07-31-tv-music-client-v1-plan/research/screens/tv-home.png` | Corrected APK home shell and content rows at 1920x1080. |
| `.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md` | V1 product scope, remote requirements, non-copying rule, and acceptance criteria. |
| `/Users/saki/.android/avd/Pixel_Android_35.avd/config.ini` | Existing phone AVD configuration proving `hw.dPad=no` and a non-TV Google APIs image. |
| `/Users/saki/Library/Android/sdk/platform-tools/adb` | Installed ADB used only for environment and package read checks. |
| `/Users/saki/Library/Android/sdk/emulator/emulator` | Installed emulator executable; no emulator was started by this researcher. |
| `TV APK :: AndroidManifest.xml` | Manifest decoded in-memory with `apkanalyzer`; identity, TV feature, launcher, and landscape evidence. |
| `TV APK :: res/layout/{af,ad,n6,d_,n0,km,mt,nl,my}.xml` | Call-chain-resolved main, playlist, player, login, error, and empty-state layouts. |
| `TV APK :: assets/{localMainTabs,local_Recommend}.json` | Bundled fallback IA/presentation models; content itself is not reusable. |
| `TV APK :: MainActivity.smali`, `PlayerFragment.smali`, `LoginDialog.smali`, `TVButton$c.smali` | `apkanalyzer` virtual decompilation used to establish focus, key routing, QR state, and focus animation behavior. |

The `research/screens/netease-*` files and all `.detail/` NetEase APK material are intentionally absent from the evidence list because they belong to the excluded phone build.

### 16. Code patterns

| Pattern | Evidence | Engineering takeaway |
| --- | --- | --- |
| Focus-aware shell | `TV APK :: res/layout/af.xml:7-23`; `MainActivity.smali:3665-3697` | Treat focus ownership and return targets as part of screen architecture. |
| Rapid-key guard by axis | `TV APK :: com.netease.cloudmusic.tv.activity.e.smali:209-318` | Protect against remote repeat without globally swallowing unrelated keys. |
| Layered key dispatch | `MainActivity.smali:4151-4214`; `PlayerFragment.smali:2102-2252` | Give the active overlay/player first refusal, then fall back predictably. |
| Multi-channel focus feedback | `TVButton$c.smali:94-113,198-217` | Combine contrast and modest scale; do not depend on color or scale alone. |
| Data-driven home rows | `TV APK :: assets/local_Recommend.json:1` | Normalize presentation types and action targets before rendering. |
| Stable playlist header/list split | `TV APK :: res/layout/ad.xml:5-26`; `n6.xml:22-254` | Keep context/actions fixed while the long list owns scrolling. |
| Resting vs active player chrome | `TV APK :: res/layout/d_.xml:32-121`; corrected `tv-left.png` and `tv-back.png` | Hide controls when idle; Back dismisses transient chrome before navigation. |
| Explicit recoverable states | `TV APK :: res/layout/nl.xml:2-38`; `my.xml:2-21` | Loading, empty, offline, and retry must each have a deterministic focus target. |

### 17. External references and tool/library versions

- Android Developers, **Create and run a TV app**: Leanback launcher, `android.software.leanback`, touchscreen-optional declaration, and launcher banner guidance. <https://developer.android.com/training/tv/get-started/create>
- Android Developers, **TV navigation**: D-pad reachability, predictable focus, scrolling-list behavior, and Back expectations. <https://developer.android.com/training/tv/get-started/navigation>
- Android Developers, **Manage TV controllers**: minimum remote surface, key variants, media behavior, and controller reconnect handling. <https://developer.android.com/training/tv/get-started/controllers>
- Android Developers, **TV apps checklist**: launcher, banner, overscan, remote-only operation, and media behavior checks. <https://developer.android.com/training/tv/publishing/checklist>
- Android Developers, **Android Debug Bridge**: device selection, APK installation, shell, and activity-manager commands. <https://developer.android.com/tools/adb>
- Local analysis tools: Android SDK `apkanalyzer`; Build Tools 35.0.0 `aapt`/`apksigner`; Platform Tools 37.0.0; Emulator 36.5.10.0.
- Bundled reference versions: AndroidX Leanback 1.1.0-beta01, Leanback Paging 1.1.0-alpha08, AppCompat 1.2.0, RecyclerView 1.2.0. These describe the sample only and should not be selected as new-project dependency versions without a separate current-architecture decision.

Official pages were checked on 2026-07-31.

### 18. Related specs

- `.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md:5-7` defines a planning-only native TV client outcome.
- `.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md:13-15` permits mature-TV pattern research but forbids 1:1 copying; line 13 needs the APK-path correction recorded in this report.
- `.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md:21-33` fixes password login, two top-level tabs, stable now-playing access, playlists, playback, roam, lyrics, and remote-only core operation.
- `.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md:43-60` excludes broad media/social features and requires recoverable lyric/error states and a dead-end-free rapid-key focus path.
- `.trellis/spec/frontend/index.md:15-22`, `component-guidelines.md:7-19`, and `quality-guidelines.md:7-19` remain unfilled templates; no project-specific frontend component or test convention can yet be inferred from them.
- `.trellis/spec/guides/cross-layer-thinking-guide.md:19-51` requires explicit source-to-display contracts and boundary error ownership, which is especially relevant for playback, lyric timing, focus state, and transient overlay routing.

## Caveats / Not Found

- APK authenticity was not independently verified against an official download page or published signing certificate. Hash and signer details provide reproducibility, not provenance.
- Static resource extraction reported one malformed/nonstandard nested asset archive. The outer APK, manifest, signatures, DEX, and referenced layouts remained readable; no conclusion depends on that nested asset.
- Obfuscation hides many resource/class names. Layouts cited above were resolved from binding/activity call chains; named but unreferenced `t_*` resources are only corroborative.
- Bundled fallback JSON can be stale or replaced by server responses. It proves supported presentation models and routes, not the exact production home feed.
- The reference screenshots were initially produced on a phone-class Pixel AVD, so those frames alone do not prove TV focus behavior. A dedicated API 36 Android TV AVD was subsequently installed and its TV/Leanback/D-pad environment validated; the legacy target-29 reference APK was blocked by Play Protect and that protection was not bypassed. See `research/tv-emulator.md`.
- Still images do not establish focus order, animation smoothness, long-press thresholds, rapid-repeat behavior, audio correctness, lyric synchronization accuracy, memory use, or Back-stack termination. These require a proper Android TV/Google TV target and event-by-event testing.
- The local `FnMusicTV_API36` AVD is suitable for deterministic layout and D-pad regression, but cannot be the final decoder, memory, launcher, or performance gate; that belongs to the Vidda C3 Pro launch device.
- The manifest has no TV banner and no `android.software.leanback` declaration despite the Leanback launcher category/library. Verify the new client's current store/launcher requirements from its own target platforms rather than copying this manifest.
- The APK requests legacy storage, phone, contacts, and location permissions and targets API 29. Those choices are irrelevant to the narrower V1 and should not be inherited.
- The QR login implementation and privacy wording are out of V1 scope. Only their large-screen state treatment was studied.
- No product code, project spec, APK, screenshot, emulator configuration, or device state was modified by this research report.
