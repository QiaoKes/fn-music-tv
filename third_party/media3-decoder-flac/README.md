# Media3 libFLAC decoder

This directory contains the official Media3 FLAC decoder extension used by the
Android application. It provides software FLAC decoding on API 23 and newer and
is loaded by Media3 through `DefaultRenderersFactory` extension discovery.

The checked-in AAR keeps normal application builds offline and reproducible. To
rebuild it, install the pinned Android NDK and CMake versions listed below, set
`ANDROID_SDK_ROOT` or `ANDROID_HOME`, and run:

```shell
./third_party/media3-decoder-flac/build.sh
```

The build script verifies that the resulting AAR contains `libflacJNI.so` for
`arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`, checks 16 KB ELF alignment for
the 64-bit libraries, then refreshes its SHA-256 file.

See `PROVENANCE.md` for source revisions and `LICENSE-MEDIA3` and
`LICENSE-LIBFLAC` for the upstream licenses.
