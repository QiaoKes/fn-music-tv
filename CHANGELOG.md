# Changelog

## 0.1.11 - 2026-08-02

### Added

- Added physical touch support for navigation, library cards, lists, grids, settings, and player controls while preserving TV remote focus and D-pad behavior.
- Added HTTP, HTTPS, and FNID server discovery with automatic direct and relay candidate probing.
- Added optional FNOS access-code verification and forwarding for login, authenticated API, artwork, and audio requests.

### Fixed

- Fixed HTTPS hosts without an explicit port to use port 443, while preserving port 5666 compatibility for bare legacy HTTP hosts.
- Fixed physical taps being dropped by TV Material buttons and prevented list drags from being misinterpreted as card clicks.
- Made the settings screen scrollable on smaller landscape displays and enlarged player touch targets without changing playback or roaming commands.
- Kept the complete login form visible at 1080p and added clear errors for unavailable FNID connections and invalid access codes.
