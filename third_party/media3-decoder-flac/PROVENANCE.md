# Provenance

The bundled `media3-decoder-flac-1.10.1-libflac-1.5.0.aar` is built without
source modifications from these upstream revisions:

- AndroidX Media3 1.10.1: commit
  `5fb306449733dd71595700c1227ad6087578c559` from
  <https://github.com/androidx/media>
- libFLAC 1.5.0: commit `1507800de4b70e21be71f38caa0d9079d0bc6e45`
  from <https://github.com/xiph/flac>

Pinned native toolchain:

- Android NDK `28.2.13676358`
- CMake `3.22.1`

The SHA-256 for the current checked-in artifact is recorded in
`media3-decoder-flac-1.10.1-libflac-1.5.0.aar.sha256`. The build script
refreshes that file and the application build verifies it before packaging.

Media3 is licensed under Apache License 2.0. The libFLAC library is licensed
under the Xiph.Org BSD-style license. Copies are included in this directory.
