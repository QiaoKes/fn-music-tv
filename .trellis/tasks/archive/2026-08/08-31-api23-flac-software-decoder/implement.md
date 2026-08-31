# Implementation Plan: API 23 FLAC software decoding

- [x] 1. Fetch Media3 tag 1.10.1 and libFLAC 1.5.0 into an isolated build directory; build the
      official `decoder_flac` AAR for the four supported ABIs.
- [x] 2. Verify the AAR namespace, minSdk, classes, consumer rules, and native libraries; record
      hashes and measured size.
- [x] 3. Add the pinned AAR, licenses, provenance, and pinned rebuild/verification tooling
      under `third_party/` without committing transient native build output.
- [x] 4. Package the local decoder dependency from the application module while keeping every
      Media3 module on 1.10.1 and the renderer configuration in `core:playback`.
- [x] 5. Centralize renderer-factory construction and configure
      `EXTENSION_RENDERER_MODE_PREFER`; preserve the current data source, load control, and audio
      attributes.
- [x] 6. Preserve the current MIME-neutral `MediaItem` construction so the multi-format stream
      endpoint remains content-detected.
- [x] 7. Add renderer configuration tests and packaged-native-library verification, including
      multi-format regression cases.
- [x] 8. Run focused `core:playback` tests, full unit tests, lint, Debug APK builds, and Release/R8
      packaging checks. Inspect final APK ABI contents and report native-library contribution.
- [x] 9. Run an API 23 FLAC playback smoke check when a suitable emulator/device is available;
      otherwise document the exact remaining device test.
- [x] 10. Run the Trellis quality check, update the Android playback contract with the new decoder
      boundary, and prepare the final change summary without committing unless requested.

## Rollback Point

Before step 4, rollback is removal of the newly added third-party artifact/tooling. After step 4,
also restore the default renderer factory and null MIME behavior. No persisted state rollback is
required.
