# Design: API 23 FLAC software decoding

## Architecture

Keep Media3 as the single playback engine. Add its official `decoder_flac` extension as a pinned
local artifact under `third_party/`, built from Media3 tag `1.10.1` (commit
`5fb306449733dd71595700c1227ad6087578c559`) and libFLAC tag `1.5.0`.

The checked-in integration includes provenance, licenses, and a pinned, reproducible rebuild script. The
script downloads or checks out only pinned revisions, builds the decoder AAR with the project's
supported ABIs, and verifies the output before replacement. Normal application builds consume the
fixed local artifact and do not require network access, CMake, or an NDK download.

This follows the repository's existing local-dependency boundary while avoiding a floating source
checkout in every CI build. The AAR is an upstream build product, not a new playback abstraction.

## Playback Data Flow

1. `MusicRepository` continues returning the authenticated direct stream URL without forcing a
   MIME type from display metadata.
2. Media3 keeps detecting the actual progressive stream format from the HTTP response and content
   bytes, so the shared endpoint remains valid for FLAC, MP3, AAC, WAV, Ogg, and other formats.
3. `PlaybackService` creates a `DefaultRenderersFactory` with
   `EXTENSION_RENDERER_MODE_PREFER` and passes it to `ExoPlayer.Builder`.
4. Media3 discovers `LibflacAudioRenderer` from the local decoder AAR and selects it for FLAC.
   Other formats continue through the platform MediaCodec renderer because the extension supports
   only FLAC.
5. Decoded PCM flows through the existing Media3 audio sink and unchanged audio attributes.

## Compatibility And Packaging

- Application and decoder minimum SDK remain API 23.
- Preserve `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` in the universal APK.
- Keep Media3 modules on exactly 1.10.1; do not use Jellyfin's current 1.9.0 FFmpeg AAR because the
  extension API is unstable across Media3 versions.
- libFLAC's decoder library uses its Xiph BSD-style license. Retain upstream notices and provenance
  beside the artifact; the application remains GPL-3.0.
- Release shrinking must retain the renderer/JNI entry points. Verify the Release APK, not only
  Debug.

## Testing

- Extend playback configuration coverage so renderer construction is centralized and asserts the
  intended extension preference without starting a service.
- Retain regression coverage proving media items do not force a display-metadata-derived MIME type
  on the multi-format endpoint.
- Add a verification script or Gradle assertion that inspects the packaged APK/AAR for
  `libflacJNI.so` in every supported ABI.
- Add a small generated FLAC fixture for instrumentation decoding if the Media3 extension exposes
  a stable-enough test boundary; otherwise run a service/player smoke check and record the device
  limitation explicitly.
- Run unit tests, lint, both sideload/store Debug assembly, Release/R8 assembly where signing
  configuration permits, and an API 23 runtime smoke check when available.

## Risks And Rollback

- Native artifacts increase APK size. Keep the extension FLAC-only and report the measured APK
  contribution. The four uncompressed native libraries contribute 1,369,112 bytes to the universal
  Release APK; the complete unsigned Store Release APK is 4,489,026 bytes.
- An incomplete ABI set can cause runtime load failure. Package inspection is a release gate.
- Reflection-based extension discovery can be broken by shrinking. Release assembly and load
  verification are required.
- Rollback removes the local decoder dependency and restores the default renderer factory; no
  database, API, or persisted playback migration is involved.

## References

- Media3 FLAC decoder: https://github.com/androidx/media/tree/1.10.1/libraries/decoder_flac
- Media3 supported formats: https://developer.android.com/media/media3/exoplayer/supported-formats
- libFLAC 1.5.0: https://github.com/xiph/flac/releases/tag/1.5.0
