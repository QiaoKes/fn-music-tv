# Research: NAS service and Web client capability

- Query: Determine the deployed NAS music service stack and run mode; verify the true Web UI and API surface; document authentication and client-side persistence; identify media, image, playlist, history, queue, roam, and recommendation behavior; extract reusable TV client contracts; and surface performance constraints.
- Scope: mixed (repository source and documentation plus read-only probes of `http://10.0.0.115:5666/music/`)
- Date: 2026-07-31

## Findings

### Executive conclusions

1. The deployed product is a NAS-native Go service behind nginx. nginx exposes `/music` on the NAS HTTP listener and proxies to the app over `/var/run/trim_music.socket`; the same service serves the API and the compiled Web SPA. The live app reports music service `0.9.16` and media service `0.8.37`.
2. The client API base is exactly `/music/api/v1`. JSON endpoints use `{code,msg,data}` and many business failures still use HTTP 200, so a TV client must validate both HTTP status and `code === 0`.
3. Password login and NAS OAuth-code login are both real. The returned user token is sent as the raw `Authorization` value, not as a Bearer token. Tokens have a fixed 30-day server TTL, are persisted across NAS restarts, and are replaced when the same user logs in again with the same `deviceId`.
4. The Web client stores the token in a JavaScript-readable `music-token` session cookie, stores its stable 32-hex-character `deviceId` and remembered username in local storage, and stores its playback queue in IndexedDB. There is no server queue API and no cross-device queue synchronization.
5. Original audio is `GET /track/stream?guid=...` with Range support. Unsupported source formats can be transcoded to an HLS URL. Both original and HLS playback can use a track-scoped temporary token in the query string when the native media stack cannot propagate request headers.
6. Models return `coverId`, not an image URL. The client constructs `/music/api/v1/static/cover?coverId=...&size=...` and must authenticate that request. The only generated thumbnail widths are `120`, `200`, `400`, and `800`; the API document's `size=512` example falls back to the original image and should not be copied into TV code.
7. Playlists, favorites, and recent history are server data. Playback history is not inferred from streaming: the client must report a `track_play` event. Standard playback queues and shuffle/repeat state are client-local.
8. The server feature called roam is a random playable-track session, not personalized recommendation. It keeps an in-memory user/device session for 24 hours and returns previous/current/next windows. No semantic recommendation endpoint or recommendation model was found.
9. Large libraries are a known operating condition: the included performance log uses 190,000 tracks, 20,000 artists, and a 3,500-track playlist. TV code should page track lists, prefetch incrementally, use supported cover sizes, and avoid `size=-1` even though several endpoints permit it.

### Files found

| Path | Description |
| --- | --- |
| `README.md` | Project title only; no architecture or run instructions. |
| `docs/API.md` | Current client-facing HTTP API contract, request examples, model definitions, and error codes. |
| `.detail/trim-music-v0.9.16/README.md` | Snapshot title only; no additional documentation. |
| `.detail/trim-music-v0.9.16/core/web/route.go` | Authoritative Gin route registration for the SPA and API. |
| `.detail/trim-music-v0.9.16/core/web/middleware.go` | Cookie/header token resolution and temporary-token middleware. |
| `.detail/trim-music-v0.9.16/core/web/controller/common.go` | JSON envelope behavior and pagination normalization. |
| `.detail/trim-music-v0.9.16/core/model/req/` | Login, playback, playlist, and event request shapes. |
| `.detail/trim-music-v0.9.16/core/model/vo/` | User, playlist, track, lyric, roam, and playback response shapes. |
| `.detail/trim-music-v0.9.16/core/service/user.go` | Password and NAS OAuth login flows. |
| `.detail/trim-music-v0.9.16/core/service/account/user_token.go` | User-token lifecycle, limits, persistence cache, and TTL. |
| `.detail/trim-music-v0.9.16/core/service/user_temp_token.go` | Track-scoped temporary playback tokens. |
| `.detail/trim-music-v0.9.16/core/service/player.go` | Direct stream, transcode sessions, HLS proxy, and MIME mapping. |
| `.detail/trim-music-v0.9.16/core/service/playlist.go` | Per-user playlist ownership and list/detail/mutation semantics. |
| `.detail/trim-music-v0.9.16/core/service/event.go` | Playback-history and lyric-preference event processing. |
| `.detail/trim-music-v0.9.16/core/service/roam.go` | In-memory roam session and random window behavior. |
| `.detail/trim-music-v0.9.16/core/service/lyric.go` | Lyric lookup, first-read download, preferred lyric, and offset behavior. |
| `.detail/trim-music-v0.9.16/core/service/filestore/cover_thumb.go` | Supported cover sizes and thumbnail generation. |
| `.detail/trim-music-v0.9.16/app-center/app/sqlite.sql` | SQLite/WAL schema, including persistent per-device user tokens. |
| `.detail/trim-music-v0.9.16/app-center/mainland/x86/pkg/manifest` | NAS package version, OS minimum, architecture, and native/root install mode. |
| `.detail/trim-music-v0.9.16/app-center/mainland/x86/pkg/cmd/config` | Production binary, data working directory, log, and PID settings. |
| `.detail/trim-music-v0.9.16/app-center/app/ui/config` | NAS launcher entry pointing to `/music`. |
| `.detail/trim-music-v0.9.16/doc/how-to-dev.md` | nginx-to-Unix-socket topology and required browser isolation headers. |
| `.detail/trim-music-v0.9.16/doc/performance-optimization-log.md` | Measured behavior on large libraries and playlists. |
| `.detail/trim-music-v0.9.16/doc/audit/security-music-V0.0.2-kimi.md` | Included audit with dependency versions and authentication topology; its audited target predates 0.9.16. |
| `.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md` | V1 scope, acceptance criteria, exclusions, and open questions. |

### Runtime stack and deployment topology

- The code is Go with Gin routes, GORM-backed storage, Bleve search, Viper configuration, bcrypt passwords, and a SQLite schema. The included audit records Go 1.25, Gin 1.10, GORM 1.30, Bleve 2.5, and bcrypt (`.detail/trim-music-v0.9.16/doc/audit/security-music-V0.0.2-kimi.md:15`). Exact dependency versions cannot be reproduced from this trimmed snapshot because `go.mod` and `go.sum` are absent.
- The NAS package is `trim.music` version `0.9.16`, marked as a native app installed as root, with `os_min_version="1.2.0300"`, `checkport="false"`, and an x86_64 build (`.detail/trim-music-v0.9.16/app-center/mainland/x86/pkg/manifest:1`). Parallel arm64 and international packaging directories also exist.
- The package launches `${TRIM_APPDEST}/trim-music` in `prod`, with its working directory, PID, and logs under the package variable-data directory (`.detail/trim-music-v0.9.16/app-center/mainland/x86/pkg/cmd/config:10`). It is a background process with an optimistic one-second startup check (`.detail/trim-music-v0.9.16/app-center/mainland/x86/pkg/cmd/service:8`).
- nginx owns the externally visible HTTP port. Requests under `/music` are forwarded to `http://unix:/var/run/trim_music.socket`, with `Cross-Origin-Opener-Policy: same-origin`, `Cross-Origin-Embedder-Policy: require-corp`, and proxy temporary files disabled (`.detail/trim-music-v0.9.16/doc/how-to-dev.md:14`).
- The Go service serves the SPA from `/var/apps/trim.music/target/static`, mounts assets at `/music/static`, and registers the API under `/music/api/v1` (`.detail/trim-music-v0.9.16/core/web/route.go:14`, `:24`). Unknown paths return `index.html`, so HTTP 200 alone does not prove that a UI route or API endpoint exists.
- SQLite uses WAL, a 5,000-page auto-checkpoint, and full synchronization (`.detail/trim-music-v0.9.16/app-center/app/sqlite.sql:1`). Search initialization and the filesystem listener start with the service (`.detail/trim-music-v0.9.16/core/service/init.go:14`).
- A separate Trim media service is reached over another configured Unix socket. Its internal client timeout is 50 seconds (`.detail/trim-music-v0.9.16/core/pkg/client/trimmediasrv/client.go:20`).
- The Web build is a standalone PWA. The live manifest names it `飞牛音乐`, scopes it to `/music/`, and supplies 192/512/maskable icons. The live main JavaScript is 1,650,391 bytes before any transfer encoding and contains React DOM, HLS.js, IndexedDB, SharedArrayBuffer, WebAssembly, and ffmpeg WASM code. This browser playback engine is much heavier than the native TV client should be.

### True Web UI surface

The deployed compiled router declares the following paths under the `/music` base. These were extracted from the live `1bc04b...-CUTd8q3Z.js` asset rather than inferred from SPA HTTP 200 responses.

| Effective URL | Existing Web purpose |
| --- | --- |
| `/music/` | Main/home library view. |
| `/music/login` | Password and NAS OAuth entry. |
| `/music/oauth/result` | OAuth redirect-code receiver. |
| `/music/init` | First-run initialization. |
| `/music/welcome` | Initialization/welcome flow. |
| `/music/playlists` | Playlist collection. |
| `/music/playlists/:playlistId` | Playlist detail and tracks. |
| `/music/recent` | Play history/recent tracks. |
| `/music/favorites` | Favorite tracks. |
| `/music/artists` | Artist browsing. |
| `/music/albums` | Album browsing. |
| `/music/genres` | Genre browsing. |
| `/music/library` | Shared-library administration/browsing. |
| `/music/search` | Search. |
| `/music/settings` | User/server settings. |

There is no dedicated player URL. The live bundle models the immersive player as an open/closed now-playing view over global player state (`isNowPlayingOpen`) and keeps it alive while navigating. Its queue supports play-now, append, insert-next, remove, multi-remove, reorder, clear, shuffle, repeat-off/all/one, and roam state. Playlist create/edit/delete, save queue to a playlist, favorites, recent history, search, library administration, and downloads exist in the broader Web product, although several are explicitly outside the TV V1 PRD (`.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md:38`).

### API conventions and true route prefix

- All client API paths use `/music/api/v1` (`docs/API.md:1`; `.detail/trim-music-v0.9.16/core/web/route.go:32`).
- Normal JSON responses are `ApiResult<T> = {code:number,msg:string,data:T|null}`. Business errors generally retain HTTP 200 and set a nonzero `code`; invalid middleware authentication uses HTTP 401 (`.detail/trim-music-v0.9.16/core/web/controller/common.go:18`, `.detail/trim-music-v0.9.16/core/web/middleware.go:53`). The live success response used an empty `msg`, so clients must not match the message string.
- File, cover, direct-stream, and HLS responses are not JSON envelopes.
- Default paging is page 1, size 50. Normal endpoints constrain page and size to 1..2000; some track, favorite, and history lists accept `size=-1` for all records (`.detail/trim-music-v0.9.16/core/web/controller/common.go:54`, `.detail/trim-music-v0.9.16/core/web/controller/music.go:127`).
- Unix timestamps are seconds, while track/audio duration and lyric offsets are milliseconds (`docs/API.md:122`). Transcode `start`, `end`, and heartbeat timestamps are seconds.
- Names and parameter casing are contract-sensitive: examples include `guid`, `playlistGUID`, `trackGUID`, `trackGUIDs`, `resourceGUID`, and `relativeRoamId`.

### Authentication and client persistence

#### Server-supported login

| Flow | Request | Result and notes |
| --- | --- | --- |
| Password | `POST /user/password-login` with `{username,password,deviceId}` | Returns `{userToken,user}`. Username is trimmed; login is rate/protection checked; a user must have a local password (`.detail/trim-music-v0.9.16/core/service/user.go:123`). |
| NAS OAuth | `POST /user/auth-login` with `{code,deviceId}` | Exchanges a NAS sign-in code over the local OAuth service; existing OAuth users are loaded and a missing member can be created (`.detail/trim-music-v0.9.16/core/service/user.go:28`). |
| Current session | `GET /user/me` | Validates the token and returns `User`. |
| Logout | `POST /user/logout` | Deletes only the current token (`.detail/trim-music-v0.9.16/core/service/user.go:171`). |

The Web OAuth flow first reads public `/sys/config`, then opens the NAS `/signin` URL with `client_id`, a `/music/oauth/result` redirect, and app name. A native TV implementation therefore needs an external-browser/custom-deep-link design for OAuth; the server does not expose a device-code or QR polling API. Password login is the only flow that can be implemented as a self-contained remote-control form without additional NAS browser/deep-link integration.

#### Token transport and lifecycle

- Send `Authorization: <userToken>` exactly. Do not prepend `Bearer ` (`docs/API.md:45`; `.detail/trim-music-v0.9.16/core/web/middleware.go:16`).
- `music-token=<userToken>` is the alternate cookie. If both are present, the cookie wins because middleware reads it first.
- A login deletes the existing token for the same `(user,deviceId)`, then creates a new GUID token. A token has a fixed 30-day TTL; activity updates user last-access metadata but does not extend token expiry. At most 20 device tokens are retained per user (`.detail/trim-music-v0.9.16/core/service/account/user_token.go:17`, `:137`).
- User tokens are stored in SQLite specifically to survive a NAS reboot and are loaded into memory at startup (`.detail/trim-music-v0.9.16/app-center/app/sqlite.sql:55`; `.detail/trim-music-v0.9.16/core/service/account/init.go:19`).
- Reusing a `deviceId` across two physical TV installs causes the newest login to invalidate the other install. Use one stable random device ID per installation; the Web client generates a 32-character hexadecimal ID.
- The live service is plain `http://`, so passwords and bearer tokens are not encrypted in transit on the LAN. Secure native storage protects at rest but does not address this network exposure.

#### Existing Web storage behavior

- The live Web bundle writes `music-token` as `Path=/; SameSite=Strict` without `Max-Age`, `Expires`, `Secure`, or `HttpOnly`. It is a JavaScript-readable browser-session cookie, not local storage.
- Local storage contains the device ID, remembered account name, remember-me flag, language/theme, volume, and play mode. Remember-me does not persist the password or user token.
- The pending initialization session is placed in session storage.
- Queue persistence uses IndexedDB database `trim-music`, schema version 2, with object stores `playback-queue-snapshots` and `playback-track-metadata-snapshots`.
- Queue keys are namespaced by server GUID, user GUID, and a storage schema version. Snapshots store track IDs, original queue IDs, current track ID, playback hints, shuffle/repeat, and optional roam state. Restored queues are intentionally stopped (`isPlaying:false`) and track metadata is re-resolved.

For TV, preserve the useful namespace and ID-first snapshot design, but store the user token in platform secure storage rather than reproducing the Web cookie. Queue state should remain local and be namespaced by `(serverGUID,userGUID)`.

### V1 API mapping

| V1 flow | Method and path relative to `/music/api/v1` | Request | Response/use |
| --- | --- | --- | --- |
| Bootstrap server | `GET /sys/config` | Public | `serverGUID`, `serverName`, service/media versions, NAS OAuth config. Namespace local data with `serverGUID`. |
| Check initialization | `GET /initialization/state` | Public | `{initialized}`. The live server is initialized; TV V1 need not own initialization unless explicitly added. |
| Password login | `POST /user/password-login` | `{username,password,deviceId}` | `LoginResult`. Persist the raw token securely. |
| NAS OAuth completion | `POST /user/auth-login` | `{code,deviceId}` | Same `LoginResult`; obtaining the code is an external-browser flow. |
| Validate restored auth | `GET /user/me` | Raw token header | `User`; on HTTP 401/code 99999 clear auth and show login. |
| List playlists | `GET /playlist/list` | No paging parameters | `PageList<Playlist>`. The service returns all playlists and the list item does not include `trackCount`. |
| Enrich playlist counts | `GET /playlist/batch-detail?guids=<comma-separated>` | Playlist GUIDs | `{list: PlaylistWithTrackCount[]}`. Batch conservatively to avoid URL-length issues. |
| Playlist detail | `GET /playlist/detail?guid=<playlistGuid>` | Playlist GUID | `PlaylistWithTrackCount`. |
| Playlist tracks | `GET /track/playlist-detail/list?playlistGUID=<guid>&page=1&size=50&sort=trackAddedAt,desc` | Paging and sort | `SortedPageList<TrackWithFavoriteAndAccess>`. Page and prefetch rather than loading all. |
| Track audio metadata | `GET /track/audio-info?guid=<trackGuid>` | Track GUID | Audio specification used to select direct stream versus transcode. |
| Direct playback | `GET /track/stream?guid=<trackGuid>` | User header or `tempToken` | Original audio bytes with Range/ETag support. |
| Temporary playback auth | `POST /user/temp-token` | `{usage:"track-stream",scopes:["track:stream","track:hls"],resourceGUID,ttlSeconds}` | `{token,expiredAt}`; default 300 seconds, maximum 1800 seconds. |
| Transcode fallback | `POST /track/transcode` | `{guid,output:{start,end?,codec,bitrate?,channel?}}` | `{status,errno,errmsg,hlsTime,url}`. Check `data.status`, not only envelope code. |
| HLS lifecycle | `GET /track/hls/{guid}/{filename}`, `POST /track/transcode/heartbeat`, `POST /track/transcode/quit` | HLS requests plus heartbeat/quit bodies | Server-backed HLS session; stop/replace it explicitly. |
| Lyrics | `GET /lyric/list?trackGUID=<trackGuid>` | Track GUID | `{list:[Lyric],preferred}`. Select `preferred`, apply its millisecond `offset`, parse when `isLRC`, otherwise show static text. |
| Record play history | `POST /event/report` | `events:[{eventType:"track_play",occurredAt,payload:{trackGUID}}]` | Required for recent history; streaming alone does not update it. Maximum 200 events. |
| Start roam | `GET /track/roam-start?deviceId=<deviceId>` | Stable device ID | `{current,next}`. Starting resets that device's roam session. |
| Step roam | `GET /track/roam-next` or `/track/roam-previous` | `deviceId`, `relativeRoamId` | `{previous,current,next}`; retain each returned `roamId`. |

`route.go` confirms these endpoints and their auth boundaries (`.detail/trim-music-v0.9.16/core/web/route.go:32`, `:80`, `:103`, `:113`, `:141`).

### Reusable TV data contracts

Decode the API envelope once at the networking boundary, then map the following server objects into immutable TV domain models. This follows the project's cross-layer contract guidance rather than letting screens cast raw payloads independently (`.trellis/spec/guides/cross-layer-thinking-guide.md:19`, `:74`).

| Domain model | Required server fields | TV-specific handling |
| --- | --- | --- |
| `ServerIdentity` | `serverGUID`, `serverName`, `serverVersion`, `mediasrvVersion` | Key token/queue/image namespaces by GUID, not display name. |
| `User` | `guid`, `name`, `role`, nullable `lastAccessedAt`, timestamps | User timestamps are Unix seconds. |
| `PlaylistSummary` | `guid`, `name`, nullable `coverId`, timestamps, optional separately fetched `trackCount` | `/playlist/list` omits count; merge batch-detail data by GUID. |
| `Track` | `guid`, `title`, nullable `coverId`, nullable year/disc/track/ISRC, `duration`, `isCue`, album, artists, genres, `audioSpec`, optional `isFavorite`, optional `accessStatus` | `duration` is milliseconds. Keep nullable/empty cases. Do not expose or trust `audioSpec.path`; it is a server filesystem path. |
| `Artist` | `guid`, `name`, nullable `coverId` | Join multiple artist names for the compact TV row while retaining the list. |
| `Album` | `guid`, `name`, nullable `coverId`, release metadata | The server falls back from a missing track cover to its album cover; preserve the returned value as-is and use one image resolver (`.detail/trim-music-v0.9.16/core/service/vo_builder.go:746`). |
| `AudioSpec` | bit depth, sample rate, channel, bitrate, codec, container, duration, format, size | Use codec/container to choose original versus HLS. Treat new codec strings as unknown, not fatal. |
| `LyricList` | `list`, nullable `preferred` | Empty list means no lyric. A missing preferred GUID must gracefully fall back to the first suitable item. |
| `Lyric` | `guid`, `source`, `content`, `isLRC`, `offset`, timestamps | Offset is milliseconds and may be nonzero. `isLRC=false` is valid static text, not an error. |
| `RoamTrack` | `roamId`, `track` | Roam returns base `Track`, without favorite/access-status extensions. Keep roam identity separate from track GUID. |
| `RoamWindow` | nullable previous/next and required current | Null neighbor is a boundary/empty state; a stale session is recoverable by starting a new roam session. |
| `QueueSnapshot` | Client-owned track GUIDs, original order, current GUID, repeat/shuffle, source context | This is not a server DTO. Namespace locally and resolve metadata after restore. |

Canonical source shapes are in `.detail/trim-music-v0.9.16/core/model/vo/vo.go:13`, `:39`, `:58`, `:91`, `:120`, `:141`; page wrappers are in `.detail/trim-music-v0.9.16/core/model/vo/music.go:3`; lyric and roam windows are in `.detail/trim-music-v0.9.16/core/model/vo/lyric.go:3` and `.detail/trim-music-v0.9.16/core/model/vo/roam.go:3`.

`accessStatus` values are 0 normal, 1 library path unavailable, 2 library permission denied, 3 audio file missing, and 4 audio file permission denied (`.detail/trim-music-v0.9.16/core/constant/constant.go:11`). TV rows should remain visible but disabled/unavailable for nonzero or future unknown values, and continuous playback should skip them without blocking the queue.

### Media, images, and lyrics

#### Direct stream and HLS fallback

- Direct stream resolves the authorized server file and serves it via `http.ServeContent`, yielding standard `200`, `206`, `304`, or `416` behavior with one-day private caching and an ETag derived from the file hash (`.detail/trim-music-v0.9.16/core/web/controller/file_response.go:28`). This is the preferred path when the TV decoder supports the reported codec/container.
- Source MIME mappings include MP3, FLAC, WAV, Ogg, Opus, AAC, M4A/M4B, AIFF, APE, WMA, DSF, DFF, DTS, and TTA; this does not imply every target TV can decode each format (`.detail/trim-music-v0.9.16/core/service/player.go:350`).
- Transcode sessions are keyed by `userID:deviceID:trackGUID` and stored in process memory. Starting another transcode for the same key stops the previous session (`.detail/trim-music-v0.9.16/core/service/player.go:20`, `:119`). A NAS service restart loses all active transcode sessions.
- Successful transcode returns `/music/api/v1/track/hls/{guid}/preset.m3u8`; allowed proxy files include `.m3u8`, `.ts`, `.m4s`, and `.mp4` (`.detail/trim-music-v0.9.16/core/service/player.go:232`, `:299`).
- Track-scoped temporary tokens are in-memory `tmp_...` values, bound to user, device, resource GUID, and scope (`.detail/trim-music-v0.9.16/core/service/user_temp_token.go:16`). HLS playlists are rewritten so child URLs inherit `tempToken` (`.detail/trim-music-v0.9.16/core/pkg/client/trimmediasrv/resp_rewrite.go:16`). They remain reusable until expiry but are lost on service restart.
- Native playback can instead attach the raw user token to every initial, Range, manifest, and segment request. Use temporary query tokens only where the media stack cannot reliably propagate headers; query tokens can appear in URL logs/history.

#### Cover URLs

- `coverId` is an opaque typed identifier such as `track_<guid>`, `album_<guid>`, `artist_<guid>`, or `playlist_<guid>` (`.detail/trim-music-v0.9.16/core/service/filestore/cover.go:24`, `:147`). Do not parse it in the TV app.
- Construct `GET {origin}/music/api/v1/static/cover?coverId={urlEncodedCoverId}&size={width}` and attach the normal user token. A null `coverId` must render a local placeholder.
- Generated square JPEG thumbnails exist only for widths 120, 200, 400, and 800 at quality 85 (`.detail/trim-music-v0.9.16/core/service/filestore/cover_thumb.go:18`, `:80`). An unsupported size silently falls back to the original image (`.detail/trim-music-v0.9.16/core/service/static.go:18`). Recommended TV mapping: 200 for compact rows, 400 for playlist grids, 800 for the immersive player.
- Cover responses are public-cacheable for 30 days and immutable with ETag (`.detail/trim-music-v0.9.16/core/web/controller/static.go:20`). Because the endpoint is authenticated and shared-library visibility can differ by user, TV client cache keys must include server identity, user identity, cover ID and variant; account switching also clears in-memory artwork to prevent cross-account display.

#### Lyrics

- `/lyric/list` returns the full content of every stored lyric plus `isLRC`, per-user offset, and a preferred GUID (`.detail/trim-music-v0.9.16/core/model/vo/lyric.go:3`).
- If a track has no stored lyrics, this GET invokes an on-demand server download before returning (`.detail/trim-music-v0.9.16/core/service/lyric.go:18`). Treat it as potentially slow and fallible even though it is a read endpoint; fetch asynchronously without blocking audio start.
- Preference selection favors a stored user preference, then synchronized LRC, then source priority sidecar > embedded > scraped/user-linked, then newest (`.detail/trim-music-v0.9.16/core/service/lyric.go:127`).
- Server LRC detection recognizes timestamps like `[m:ss]`, `[mm:ss.xx]`, or `[mmm:ss.xxx]` and declares LRC when at least one timed line exists (`.detail/trim-music-v0.9.16/core/pkg/lrc/lrc.go:9`). The TV parser must tolerate metadata lines, multiple timestamps, BOM, blank lines, and malformed lines.
- `lyric_preference_change` and `lyric_offset_change` events can persist a user's selection/offset across clients, but editing these values is not required by TV V1.

### Playlist, history, queue, and roam capabilities

#### Playlists

- Playlists are user-owned server records. The route surface supports create/edit/delete/list/detail/batch-detail/add/remove/purge, but V1 only needs read/list/play (`.detail/trim-music-v0.9.16/core/web/route.go:113`; PRD out-of-scope at `.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md:40`).
- `/playlist/list` is unpaginated and returns all user playlists without counts (`.detail/trim-music-v0.9.16/core/service/playlist.go:157`). Use batch-detail for counts only when the UI needs them.
- Playlist track order defaults to `trackAddedAt,desc`; the track-list response includes `total`, actual `sort`, favorite state, and access status (`docs/API.md:364`).
- A server playlist is not a playback queue. "Play all" must create a client queue from the ordered track list. Start from the first page and prefetch subsequent pages so playback can begin before a large playlist is fully materialized.

#### Favorites and history

- Favorites have server create/delete/list endpoints and include unavailable entries with `accessStatus`; favorite management is out of TV V1 but the optional `isFavorite` model field can be retained.
- `/play-history/list` returns recent tracks ordered by the server record's updated time, but the response is only `TrackWithFavorite`: it exposes neither play count nor played-at time (`.detail/trim-music-v0.9.16/core/service/user_preference.go:137`).
- Streaming a track does not update history. `POST /event/report` with `track_play` validates access and upserts `(user,track)`, incrementing play count and updating server time (`.detail/trim-music-v0.9.16/core/service/event.go:84`; `.detail/trim-music-v0.9.16/core/storage/manager/play_history.go:27`).
- The live Web bundle config marks a play complete at 85% progress. Matching that threshold on TV will keep recent-history semantics consistent; do not report merely because a stream URL was opened.

#### Queue

- No queue CRUD, queue-sync, playback-session, or resume-position endpoint exists in the backend route table. Cross-device queue/position continuation is therefore unsupported.
- The Web queue is global client state with current/original order, index/current track, repeat, shuffle, selections, and distinct roam fields. Its IndexedDB snapshot stores IDs and resolves metadata after restore, avoiding a stale duplicate copy of the whole API model.
- TV V1 should use the same conceptual separation: `QueueState` for ordinary playlist playback, `RoamState` for server roam, and `PlayerState` for transport/progress. Preserve the ordinary queue while roaming so exiting roam can restore the prior context, as required by the PRD (`.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md:52`).

#### Random roam versus recommendation

- `roam-start` resets an in-memory session identified by user and the supplied device ID, then returns current and look-ahead next tracks. Previous/next calls are relative to a returned `roamId` (`.detail/trim-music-v0.9.16/core/service/roam.go:20`, `:36`).
- Candidate selection queries accessible, nondeleted tracks with SQLite `ORDER BY RANDOM() LIMIT 20000`, shuffles that result again in Go, and consumes it (`.detail/trim-music-v0.9.16/core/storage/dao/track.go:437`; `.detail/trim-music-v0.9.16/core/service/roam.go:343`).
- Used-track exclusion when replenishing the candidate pool is commented out, so repetition is possible after the current pool is exhausted (`.detail/trim-music-v0.9.16/core/service/roam.go:513`).
- Sessions expire after 24 hours and live only in process memory. A server restart, TTL expiry, or stale `relativeRoamId` requires a new `roam-start` and must be treated as recoverable.
- No recommendation endpoint, taste/profile vector, similar-track relation, collaborative filtering, or history-weighted ranking was found. Product copy should call this random roam or radio-like discovery, not personalized recommendations.

### Performance and reliability constraints

| Constraint/evidence | TV implication |
| --- | --- |
| The performance log measures 190k tracks/20k artists: optimized artist list about 0.5-1.3 s and genre list about 0.9 s (`.detail/trim-music-v0.9.16/doc/performance-optimization-log.md:1`). | Assume nontrivial LAN/server latency; keep stale content visible during refresh, cancel obsolete requests, and avoid blocking focus/navigation on full responses. |
| A 3,500-track playlist previously took 7 s for "play all" because each track triggered filesystem checks; the service was changed to DB status and defers the actual check to playback (`.detail/trim-music-v0.9.16/doc/performance-optimization-log.md:4`). | The per-track I/O issue is fixed, but full payload/decode/memory cost remains. Use size 50-100 pages and incremental queue fill; never use `size=-1` for TV playback. |
| `/playlist/list` is unpaginated and the configured maximum is 99,999 playlists (`.detail/trim-music-v0.9.16/core/service/playlist.go:157`; `.detail/trim-music-v0.9.16/core/constant/constant.go:4`). | Render lazily and enrich counts/covers only for visible or near-visible items. Batch batch-detail conservatively. |
| Standard page size defaults to 50 and caps at 2,000; several endpoints allow all records. | Keep page size explicit and bounded. Treat `total` as potentially large and use stable list keys by GUID. |
| Only 120/200/400/800 cover thumbnails are real; unsupported sizes return the original image. | Use exact supported widths. Limit decoded bitmap dimensions and memory-cache size, especially on 1080p/4K screens. |
| First lyric lookup can trigger a server-side download and returns complete text for all candidates. | Start audio independently; show loading/no-lyric/error separately; cache parsed lyrics by `(serverGUID,trackGUID,lyricGUID,updatedAt)`. |
| Direct streams support Range and one-day revalidation. | Let the native player seek using Range; do not eagerly download whole source files. |
| Transcode creation can take up to the media client's 50-second timeout; sessions are in memory and create is not idempotent. | Show a distinct non-blocking preparing state and set the client timeout above the measured server bound. Do not blindly retry a POST after its body may have been sent; treat timeout/response loss as ambiguous, wait the server bound, then perform controlled cleanup before manual retry. |
| The live Web main JS alone is 1.65 MB and embeds browser audio/HLS/ffmpeg/WASM machinery. | Do not reuse the Web SPA in a TV WebView for V1. A native media stack avoids large JS parse/WASM memory and gives better remote/media-key integration. |
| Roam uses SQLite `ORDER BY RANDOM()` over up to 20,000 candidates and holds a 24-hour in-memory session. | Do not start roam repeatedly during rapid key presses. Serialize/deduplicate start/next requests and prefetch only one next window. |
| User token is persistent, but temp playback tokens, HLS sessions, and roam sessions are volatile. | Model 401/stale-HLS/stale-roam as different recovery paths: re-login only for the user token; recreate temporary/transcode/roam state otherwise. |

### Read-only live probe record

Probes were run against `http://10.0.0.115:5666` on 2026-07-31. No authenticated data endpoint was called, no login was attempted, and no service data was changed.

- `GET /music/`: HTTP 200 from nginx, 8,990-byte HTML, `Cache-Control: no-cache, no-store, must-revalidate`, COOP `same-origin`, COEP `require-corp`, last modified 2026-07-30.
- `GET /music/api/v1/sys/config`: HTTP 200 / `code:0`; reported server name `nas`, server version `0.9.16`, and media service version `0.8.37`. Instance OAuth client ID and server GUID were intentionally redacted from this research artifact.
- `GET /music/api/v1/initialization/state`: `code:0`, `initialized:true`.
- Unauthenticated `GET /music/api/v1/user/me`: HTTP 401 with `code:99999`, `msg:"INVALID TOKEN"`.
- `/music/`, declared SPA routes, and `/music/__dev/ping` returned byte-identical SPA HTML. Because `route.go` mounts dev endpoints only conditionally and `/__dev/ping` did not return a ping payload, dev APIs appear disabled in this live deployment.
- The live PWA manifest uses `start_url` and `scope` `/music/`, `display:"standalone"`, background/theme `#0f0f0f`, and 192/512/maskable icons.
- The compiled router declared only the UI paths listed above. In particular, arbitrary paths also return SPA HTML via server fallback, and `/music/music` is not a valid declared screen.
- Live asset inspection confirmed cookie auth, local device/account preferences, IndexedDB queue snapshots, queue/shuffle/repeat/roam support, React DOM, HLS.js, and ffmpeg/WASM code. The Web source and source maps were not present in the repository snapshot.

### External references and versions

- No Internet reference was needed or used. The primary contract is repository source plus `docs/API.md`, checked against the deployed private NAS service.
- Live reported versions: Trim Music `0.9.16`; Trim media service `0.8.37`.
- Package manifest version: `0.9.16`; minimum NAS OS: `1.2.0300`.
- Included audit dependency record: Go 1.25, Gin 1.10.0, GORM 1.30.0, Bleve 2.5, bcrypt cost 12. Treat exact dependency versions as secondary evidence because the audit target is labeled `trim-music-dev-0.0.2` and manifests are missing from the trimmed source.

### Related specs

- `.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md:5`: V1 goal is native TV login, playlist playback, immersive player, roam, and synchronized lyrics.
- `.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md:38`: search, favorite management, playlist mutation, download/offline, cross-device resume, casting, and complex queue editing are out of scope.
- `.trellis/spec/guides/cross-layer-thinking-guide.md:19`: map source-to-display data flow and define boundary contracts before implementation.
- `.trellis/spec/guides/cross-layer-thinking-guide.md:74`: decode payloads centrally rather than redefining raw contracts in each consumer.
- `.trellis/spec/backend/index.md` and `.trellis/spec/frontend/index.md` are currently unfilled templates; they provide no project-specific implementation convention beyond requiring English documentation.

## Caveats / Not Found

- Root and trimmed-snapshot READMEs contain only titles. They do not describe installation, build, API, or frontend behavior.
- The snapshot omits `main.go`, `go.mod`, `go.sum`, runtime YAML, frontend source, frontend package manifests, and source maps. Backend behavior is well evidenced by retained source, but exact dependency versions and Web library versions cannot be independently rebuilt or verified.
- The PRD says `docs/API.md`; the actual case-sensitive path is `docs/API.md`.
- The API document's cover example requests 512 pixels (`docs/API.md:513`), while code only generates 120/200/400/800. Unsupported sizes return the original image, making this a real documentation/performance mismatch.
- The existing authenticated browser session was not accessed. Live probes deliberately stayed unauthenticated, so no real user, playlist, track, cover, lyric, or audio payload was copied into research. Field contracts were reconciled from API documentation, source models/services, and the compiled Web client.
- `/lyric/list` was not live-probed because a first lookup may download and persist missing lyrics; doing so would violate the read-only probe boundary.
- No source audio was streamed and no transcode session was created, so actual codec support, seek behavior, HLS startup time, and heartbeat cadence still require implementation-stage tests against representative files and target TVs.
- No server queue, resume-position sync, semantic recommendations, device-code login, QR login, or cross-device playback state was found.
- Roam, HLS transcode sessions, and temporary tokens are process-memory state. The API does not advertise a server generation/restart ID, so clients discover staleness only through failed requests and must recreate the relevant state.
- The live server is a private LAN address and its availability/configuration can change. Do not treat instance identifiers or present initialization state as product constants.
