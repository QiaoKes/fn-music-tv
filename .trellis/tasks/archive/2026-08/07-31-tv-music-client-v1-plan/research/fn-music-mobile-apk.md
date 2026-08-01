# Research: FN Music mobile APK authentication and discovery

- Source: `/Users/saki/Downloads/飞牛音乐.apk.1`
- Date: 2026-08-01
- Method: read-only manifest, native-library, and Flutter AOT string inspection
- SHA-256: `e3d7dbc605d6bc74f13f310b0408f330cbd100b0eaf6a14aaeb062c69cc05cae`

## Identity

- Package: `com.trim.music`
- Version: `1.0.0` (`versionCode=100049`)
- Runtime: Flutter AOT with Android platform plugins

## Password login

- The app references `user/password-login`, `deviceId`, `SHA-256`/`sha256`, and port `5666`.
- Its persisted server model separates the user's input address from normalized host, port, HTTPS,
  login type, NAS machine ID, and FN ID.
- This corroborates the server-source contract used by the TV client: a bare host defaults to `5666`,
  and password login sends lowercase SHA-256 hex with a stable device identifier.

## FN ID login

- The AOT strings reference `user/auth-login`, `/oauthapi/authorize`, `nasOAuth`, and `FN ID`.
- FN ID is an OAuth-style authorization path, not a variant of password login. It should be added as
  a separate session provider with explicit callback/cancellation states and token handling.
- The current TV V1 deliberately excludes third-party/FN ID authorization. This evidence is retained
  for a later task and must not be represented by a non-functional login entry.

## LAN discovery

- The APK includes Flutter `nsd_android` integration backed by Android `NsdManager`/DNS-SD.
- Strings include `_services._dns-sd._udp` and discovery start/stop operations.
- Discovery should be modeled as an optional source of server candidates. Manual IP entry remains the
  baseline and discovered hosts must still pass the same URL normalization and `/sys/config` checks.
- The current TV V1 deliberately excludes LAN discovery. A later implementation needs real NAS service
  type/TXT-record confirmation, lifecycle-safe stop behavior, deduplication, and a manual-entry fallback.

## Scope conclusion

The mobile APK was used only to confirm protocol shape and future capability boundaries. No code,
assets, UI composition, or credentials were copied. Password hashing and default-port behavior are in
the MVP; FN ID and LAN discovery remain separately testable follow-up work.
