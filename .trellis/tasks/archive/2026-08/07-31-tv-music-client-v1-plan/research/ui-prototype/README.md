# Echo TV UI Prototype

Open `index.html` directly in a browser. No build step, server, package install, or network connection is required. This is a native 1920x1080 review prototype, not an approved implementation baseline. The canvas scales down for convenient preview only; a 1280x720 browser window is not a native 720p layout test. Real 720p and native 4K breakpoints belong to the Compose implementation and screenshot suite.

Direct review states:

- `#home`
- `#my`
- `#login`
- `#playlists`
- `#playlist`
- `#collection`
- `#settings`
- `#player` (cover mode)
- `#player-poster` (large poster mode)

Use the arrow keys to move focus, `Enter` to activate the focused control, and `Escape` to go back. Player controls contain only progress, Previous, Play/Pause, and Next, plus Exit Roam while roaming. They appear briefly after interaction, then leave the focus order when hidden. With controls hidden, any arrow reveals them with focus on Play; `Enter` toggles play/pause and also reveals them with focus on Play. When progress is focused, Left and Right seek by 10 seconds. Back first hides visible controls, and a second Back leaves the player. The selector under `#settings` persists the default player choice with `localStorage`; the first-run default is poster mode and future playback uses the saved choice. The two player hashes are direct design-review states and are not linked by an in-player mode switch.

Starting Random Roam from Home marks the player context and adds Exit Roam to the transient controls. Normal playlist playback does not show this command. Exit Roam restores the prior ordinary queue and position in a paused state, hides the command, and focuses Play.

The unified cache controls under `#settings` persist a 128, 256, 512, or 1024 MB limit with `localStorage`; the default is 512 MB. The user-selected quota covers audio and image cache only, split 75/25. The local metadata index has an independent 32 MB cap and is not included in the displayed usage. Simulated usage starts at 286 MB. Lowering the quota preserves the truthful current-usage value and shows that the saved limit will apply at the next track transition or process start, when simulated usage is reduced to the new limit. Clear Cache opens a trapped Cancel/Confirm dialog. Confirm removes unlocked cache; active playback keeps an 18 MB locked span with a release-after-track notice, while clearing with playback paused reaches zero. Back closes the dialog and restores focus to Clear Cache.

Settings remains a full-screen task page. Back returns to My with focus restored to the Settings button. Back from My returns to Home and restores the last focused Home content item. Back from Home shows the review-only `data-exit-state="requested"` surface with "playback continues" copy; another Back marks it `confirmed` without routing back into Home.

`#my` uses three remote-friendly horizontal shelves for artists, albums, and music-library collections. Artist and album items plus All Songs open the shared `#collection` detail surface as a review-only D1 detail example; this prototype intentionally does not include the D1 All Artists/All Albums grids or pagination. The entire drill-down remains conditional on PRD decision D1 and must be designed and accepted in Compose after approval, not treated as complete or approved behavior here. Shared-library cards show read-only availability and last-update status without invented song counts. No local-music or lossless categories, false actions, or duplicate playlist grid appear on My.

Login shows the NAS address without a scheme (`10.0.0.115:5666/music/`) and keeps the scheme in a separate HTTPS option. The focus path is NAS, Account, Password, Keep Login, HTTPS, Login; Right from NAS reaches recent-server history and Right from Password reaches the visibility eye, with Left returning to the paired field. First use focuses NAS; a valid remembered server focuses Account. The history button opens a trapped local list with two simulated recent servers and Cancel; selecting an entry updates the scheme-less field and HTTPS option, while Back or Cancel restores focus to the history button. This is local history, not LAN discovery. Keep Login defaults on and represents encrypted-token persistence only; this prototype never stores a password. HTTPS defaults off for the current address, so the restrained, non-blocking warning "Local-network HTTP connection is not encrypted" remains visible until HTTPS is enabled. Pasted HTTP/HTTPS schemes are stripped into the separate option; credential-bearing URLs and non-HTTP(S) schemes are rejected. Forgot-password and LAN-discovery actions are intentionally absent because the server contract does not support them. Back from Login opens a review-only exit state with no playback-continuation claim and never routes to My.

Readable body and Settings text is sized for a 1920x1080 ten-foot UI (generally 24px or larger); compact labels, units, and metadata stay at 18px or larger. Synced lyrics use 48px current source, 34px current translation, and 30px neighboring source/translation text. Each source and translation reserves up to two lines in a stable lyric group so long text cannot move or overlap the player controls.
