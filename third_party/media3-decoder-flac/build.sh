#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
media3_version="1.10.1"
media3_commit="5fb306449733dd71595700c1227ad6087578c559"
libflac_version="1.5.0"
libflac_commit="1507800de4b70e21be71f38caa0d9079d0bc6e45"
ndk_version="28.2.13676358"
cmake_version="3.22.1"
output_name="media3-decoder-flac-${media3_version}-libflac-${libflac_version}.aar"

for command in git java unzip shasum; do
    command -v "$command" >/dev/null || {
        echo "Missing required command: $command" >&2
        exit 1
    }
done

android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$android_sdk" ]]; then
    echo "Set ANDROID_SDK_ROOT or ANDROID_HOME before building." >&2
    exit 1
fi
if [[ ! -d "$android_sdk/ndk/$ndk_version" ]]; then
    echo "Android NDK $ndk_version is required under $android_sdk/ndk." >&2
    exit 1
fi
if [[ ! -d "$android_sdk/cmake/$cmake_version" ]]; then
    echo "CMake $cmake_version is required under $android_sdk/cmake." >&2
    exit 1
fi
readelf_bin=$(find "$android_sdk/ndk/$ndk_version/toolchains/llvm/prebuilt" -type f -path '*/bin/llvm-readelf' | head -n 1)
if [[ ! -x "$readelf_bin" ]]; then
    echo "llvm-readelf is missing from Android NDK $ndk_version." >&2
    exit 1
fi

build_root=$(mktemp -d "${TMPDIR:-/tmp}/fn-music-media3-flac.XXXXXX")
cleanup() {
    rm -rf -- "$build_root"
}
trap cleanup EXIT

media3_dir="$build_root/media3"
libflac_dir="$media3_dir/libraries/decoder_flac/src/main/jni/libflac"

git init -q "$media3_dir"
git -C "$media3_dir" remote add origin https://github.com/androidx/media.git
git -C "$media3_dir" fetch --depth 1 origin "$media3_commit"
git -C "$media3_dir" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$media3_dir" rev-parse HEAD)" == "$media3_commit" ]]

git init -q "$libflac_dir"
git -C "$libflac_dir" remote add origin https://github.com/xiph/flac.git
git -C "$libflac_dir" fetch --depth 1 origin "$libflac_commit"
git -C "$libflac_dir" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$libflac_dir" rev-parse HEAD)" == "$libflac_commit" ]]

(
    cd "$media3_dir"
    ./gradlew :lib-decoder-flac:assembleRelease \
        --no-daemon \
        -I "$script_dir/gradle-mirror.init.gradle"
)

built_aar="$media3_dir/libraries/decoder_flac/buildout/outputs/aar/lib-decoder-flac-release.aar"
output_aar="$script_dir/$output_name"
install -m 0644 "$built_aar" "$output_aar"

expected_abis="arm64-v8a armeabi-v7a x86 x86_64 "
actual_abis=$(unzip -Z1 "$output_aar" | awk -F/ '/^jni\/[A-Za-z0-9_-]+\/libflacJNI\.so$/ { print $2 }' | sort | tr '\n' ' ')
if [[ "$actual_abis" != "$expected_abis" ]]; then
    echo "Unexpected FLAC decoder ABIs: $actual_abis" >&2
    exit 1
fi

alignment_dir="$build_root/alignment"
mkdir -p "$alignment_dir"
unzip -q "$output_aar" 'jni/*/libflacJNI.so' -d "$alignment_dir"
for abi in arm64-v8a x86_64; do
    if ! "$readelf_bin" -lW "$alignment_dir/jni/$abi/libflacJNI.so" | awk '
        BEGIN { found = 0 }
        $1 == "LOAD" {
            found = 1
            if ($NF != "0x4000") exit 1
        }
        END { if (!found) exit 1 }
    '; then
        echo "$abi/libflacJNI.so is not 16 KB ELF aligned." >&2
        exit 1
    fi
done

(
    cd "$script_dir"
    shasum -a 256 "$output_name" > "$output_name.sha256"
)

echo "Built $output_aar"
