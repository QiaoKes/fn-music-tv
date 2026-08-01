# 真实 NAS 播放回归修复

## Goal

Restore reliable authenticated reads and startup recovery against the real FNOS NAS so playlist
tracks can load and playback can be smoke-tested without stale-connection failures or expired-token
crashes.

## Requirements

- Prevent reuse of server-closed pooled HTTP/1.1 connections without adding an application-level
  retry loop or enabling redirects.
- Keep the existing current-presentation retry budget and request-count contracts unchanged.
- Contain every connection and identity lookup failure inside `SessionRepository.restore()`.
- Map an invalid or disabled remembered token to signed-out state and clear the invalid credential;
  map other restore failures to the existing recoverable signed-out error state.
- Remove temporary live-diagnostic logging after the regression is covered.
- Verify the fixes with focused unit tests and a real NAS Android TV smoke test using the existing
  three-track playlist.

## Acceptance Criteria

- [x] A stale pooled connection no longer makes a successful playlist endpoint appear unavailable.
- [x] Transport recovery does not enable redirects or add retries for terminal HTTP/API failures.
- [x] An expired or disabled remembered token never escapes `restore()` or crashes application
  startup, and the invalid token is removed.
- [x] Existing API, repository, playback, presenter, and TV interaction tests remain green.
- [x] The real NAS playlist loads all three tracks on the API 36 TV target.
- [x] Real playback remains active for at least 20 seconds, seeks backward, and rapid previous/next
  transitions keep current artwork and lyrics tied to the current track.
- [x] Queue and play-mode controls remain icon-only and usable, and no persistent audio cache is
  created.

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
- Real-device validation used the three-track `test` playlist on the API 36 Android TV emulator:
  natural next-track playback, exact ten-second backward seek, rapid next/previous transitions,
  queue and mode controls, roam auto-advance, artwork, lyrics, and cache contents all passed.
