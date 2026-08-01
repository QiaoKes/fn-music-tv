# UI Evidence And Originality Boundaries

## Sources Reviewed

- Authenticated NAS Web client at `http://10.0.0.115:5666/music/`.
- Correct Android TV reference package: `com.netease.cloudmusic.tv`, version `1.1.80`.
- User-provided large-poster player reference image.
- Android TV quality and playback guidance from the official Android developer documentation.

The APK captures were taken from a phone-shaped Android 35 AVD without a Leanback
launcher or hardware D-pad declaration. They are valid for visual hierarchy research
only. A separate API 36 Android TV AVD now validates the target 1920x1080/2 GB
environment and deterministic D-pad input; Play Protect blocked the old-target
reference APK there, so the reference app's focus graph is still not treated as
verified. Application focus and performance must be validated on this TV AVD and
physical TV hardware during implementation. See `research/tv-emulator.md`.

## Evidence Files

| File | What It Shows | What We Keep |
| --- | --- | --- |
| `screens/tv-home.png` | TV home with a persistent now-playing entry, shallow top navigation, large horizontal content cards, and a partially visible next item. | Ten-foot spacing, a stable player re-entry point, horizontal browsing rhythm, and focus-led disclosure. |
| `screens/tv-after-consent.png` | Immersive player with cover-led composition and synchronized lyrics. | Full-screen playback as a destination, cover-derived atmosphere, and lyric prominence. |
| `screens/tv-controls.png` | Playback controls revealed over the immersive player. | Controls hidden at rest, predictable reveal, full-width progress, and remote-friendly transport targets. |
| `screens/tv-left.png`, `screens/tv-back.png` | Alternate player interaction states. | Back first dismisses transient controls, then exits; transport state remains stable. |
| User poster reference | Large clear artwork on the left and song/lyric content on the right. | A second player layout selectable in settings, without stretching or heavy blur. |

## Existing NAS Web Client

The Web client confirms the product data hierarchy but is not a TV layout source:

- Home exposes random roam, favorites, recent listening, recently added music,
  albums, and songs.
- Playlist/album detail uses a cover header, play-all action, and a dense desktop
  table with format, size, and other metadata.
- The expanded player uses clear artwork, song metadata, transport controls, and a
  lyric column on a cover-tinted background.
- A floating bottom player provides re-entry on desktop.

For TV, the useful hierarchy is `entry -> collection -> track -> immersive player`.
Desktop-only table columns, tiny inline icons, hover behavior, and the floating
bottom bar are excluded. The TV equivalent of player re-entry is the upper-left
now-playing pill requested by the user.

## Original Product Direction

The V1 identity is intentionally narrower than the reference product:

- Only two top-level destinations: `首页` and `我的`.
- Home is a calm listening launchpad: now-playing, random roam, and a short list of
  recent or frequently useful playlists.
- My contains a compact account/settings line followed by vertically stacked,
  horizontally browsable Artist, Album, and Music Library shelves. It does not
  repeat Home's playlists.
- Playlist detail favors readable track title, artist, and duration. Audio format,
  file size, social actions, and playlist editing are omitted.
- Two immersive player layouts share one transport model:
  - `封面模式`: compact artwork and a wider lyric field.
  - `大海报模式`: large clear artwork/poster at left; metadata and lyrics at right.
- Poster mode is the first-run default. Layout selection exists only in My >
  Settings; neither immersive player exposes a mode switch or persistent mode label.
- The visual system uses charcoal neutral surfaces, warm white text, restrained
  coral focus accents, and cover-derived secondary color. It does not reproduce
  NetEase red, logos, vinyl artwork, navigation labels, card copy, or content assets.

## TV Interaction Rules

- Every interactive item has a single deterministic D-pad neighbor in each valid
  direction; no action depends on touch, pointer, hover, or swipe.
- Initial focus lands on the highest-value action for the page: first-run server
  address (or Account when a valid server is already saved), Home roam entry,
  My's first Artist lockup, collection play-all, or player play/pause controls.
- Focus is shown through scale, high-contrast outline, and elevation; size changes
  are absorbed by fixed item bounds so rows do not reflow.
- Center toggles play/pause in the player. Left/right seek when transport controls
  are visible. Media keys work from every page.
- Back dismisses a transient overlay first, then returns to the previous page while
  preserving collection scroll and focused item. Back never stops playback.
- Long labels use one or two lines with ellipsis. No marquee runs while unfocused;
  an optional slow marquee may start only after a focused delay.
- Safe content bounds assume at least 48 px inset at 1080p, with all primary text
  and controls inside the TV overscan-safe region.

## My Page Redesign

The rejected My draft used a desktop-dashboard composition: artist list, album
grid, and statistics overview were placed in three columns separated by vertical
rules, while a large account card occupied the upper-right. This caused long and
ambiguous D-pad jumps, mixed item densities, a weak reading order, and large empty
areas with no action value.

The recommended direction is a TV media catalog:

- Account identity and connection state are compact utilities, not the page's
  dominant card. Settings remains a clear icon+text target at the upper-right.
- A vertical lazy catalog owns the page. Each section is one horizontal focus
  group: Artists, Albums, then Music Library.
- Up/Down changes shelves; Left/Right browses one media type. Rows retain a stable
  focus line and reveal the next item at the right edge; the next shelf title peeks
  at the bottom to communicate vertical continuation.
- Under the PRD's recommended decision D1, artist and album lockups lead to
  read-only collection detail and song playback, and Music Library begins with
  All Tracks. D1 remains approval-bound; if rejected, those previews are not
  focusable. Shared-library status cards are always display-only and therefore
  never become false focus targets.
- No vertical divider grid, dashboard number wall, pointer-style cursor, or three
  simultaneous navigation axes remain.

This follows Android TV's official catalog guidance that a media browser consists
of sections containing media lists, and its current recommendation to use TV-aware
lazy rows/columns for focus-driven scrolling. Apple's cross-platform TV guidance
independently reinforces directional focus, safe areas, consistent grids, and
partially visible continuation content.

- <https://developer.android.com/training/tv/playback/compose/browse>
- <https://developer.android.com/training/tv/playback/compose/lists>
- <https://developer.apple.com/design/human-interface-guidelines/focus-and-selection/>
- <https://developer.apple.com/design/human-interface-guidelines/layout>

## Poster Mode Rules

- Artwork uses Compose `ContentScale.Inside` semantics inside a stable left-hand region; it is
  never stretched to fill the screen.
- Landscape poster art may use more of the available region. Square album art
  remains square and receives a quiet neutral surround.
- The server's 800 px thumbnail is a center-cropped square. Poster mode therefore
  requests the original only for this screen, downsamples it to the display target,
  and falls back to 800 px when size/dimension safety limits or decoding fail.
- Background color is derived once from the cached 800 px cover and persisted with
  the cover cache key. Runtime blur, animated shader backgrounds, and continuous
  palette extraction are prohibited on low-tier TVs.
- The active lyric is the highest contrast line. Previous and next lines remain
  visible at lower contrast to preserve reading context. Translation, when present,
  sits directly below its source line at a smaller size.
- Missing art falls back to an original geometric placeholder based on track and
  artist initials; it must not enlarge a low-resolution thumbnail.
- No mode switch or mode label appears in either player. Poster is the first-run
  default, and the only selector lives in My > Settings.

## Implementation Validation Needed

- Validate D-pad focus graphs at 1920x1080 and 1280x720, plus a real overscan TV.
- Validate font fallback for Chinese, Latin, Japanese, and Korean metadata.
- Validate controls with a standard remote, media keys, and long-press seek.
- Validate the poster layout with square, portrait, landscape, low-resolution, and
  missing cover art.
- After the user explicitly approves the original prototype, freeze it as the
  screenshot-comparison baseline; never compare against or copy the reference APK.
