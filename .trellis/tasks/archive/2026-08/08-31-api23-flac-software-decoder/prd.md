# Add API 23 FLAC software decoding

## Goal

Make direct FLAC playback reliable on Android 6.0/API 23 and newer TVs even when the OEM
`MediaCodec` implementation is missing or broken, while preserving the existing Media3 session,
queue, authentication, buffering, and presentation behavior.

## Background

- Version 1.1.0 lowered the application minimum SDK from API 29 to API 23, allowing older TVs to
  install the app.
- The current `PlaybackService` constructs the default Media3 renderer stack, so FLAC decoding
  depends on the device platform decoder. Media3 only requires a platform FLAC `MediaCodec` from
  API 27.
- The app uses Media3 1.10.1 and exposes progressive NAS streams through
  `/track/stream?guid=...`; those URLs have no filename extension.
- The repository already vendors pinned Android dependencies under `third_party/` when published
  artifacts do not satisfy API 23 compatibility.

## Requirements

- R1: Bundle the official Media3 1.10.1 FLAC decoder extension and libFLAC 1.5.0 for API 23+
  instead of using a Media3 1.9 prebuilt or changing the project's Media3 version.
- R2: Keep the decoder provenance and rebuild procedure reproducible from pinned upstream
  revisions. Do not depend on a moving branch or an unverified third-party binary.
- R3: Include `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` so the existing universal sideload
  package keeps its documented architecture support.
- R4: Always prefer the bundled libFLAC renderer for FLAC input so an OEM decoder that advertises
  support but produces silence cannot win renderer selection. Other audio formats must retain
  their existing Media3 renderer behavior.
- R5: Preserve Media3's existing response-header and content-sniffing behavior for the multi-format
  `/track/stream` endpoint. Do not force a MIME type from display metadata.
- R6: Preserve the direct authenticated HTTP source, raw `Authorization`, access-code headers,
  relay cookie, redirect policy, 50-second forward buffer, 15-second back buffer, MediaSession,
  queue, snapshot, and playback error contracts.
- R7: Add focused automated coverage for renderer configuration and unchanged MIME-neutral media
  items, plus a packaged-native-library check. Exercise real FLAC decoding on API 23
  instrumentation when an emulator or device is available.

## Acceptance Criteria

- [x] A Release or Debug APK targeting minSdk 23 contains `libflacJNI.so` for all four supported
      ABIs.
- [x] `PlaybackService` always selects the bundled FLAC extension ahead of an OEM FLAC decoder.
- [x] FLAC from the extensionless NAS stream endpoint is detected from the actual response and
      decoded by libFLAC without forcing every stream to one MIME type.
- [x] MP3, AAC, WAV, Ogg, and unknown formats retain their existing media-item and detection
      behavior.
- [ ] A representative 16-bit/44.1 kHz FLAC and 24-bit/high-sample-rate FLAC decode with audible
      output on an API 23 runtime, or the absence of a suitable runtime is explicitly recorded as
      the remaining device-validation item.
      Both fixtures decode and play to completion without error on the API 23 emulator; audible
      output on a physical Xiaomi TV remains the release-device validation item.
- [x] Existing playback configuration tests and the full project quality gate pass.
- [x] Release shrinking preserves and loads the FLAC JNI renderer.

## Out Of Scope

- Rewriting the app in Flutter or replacing Media3 with mpv/VLC.
- Adding broad FFmpeg support for ALAC, DTS, TrueHD, or unrelated codecs.
- Server-side transcoding or HLS fallback changes.
- Audio caching or changes to the NAS API.
