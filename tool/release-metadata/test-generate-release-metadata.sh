#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT

version_name=$(sed -n 's/^VERSION_NAME=//p' "$script_dir/../../version.properties")
apk="$temp_dir/fn-music-tv-$version_name-universal.apk"
metadata="$temp_dir/release-metadata.json"
printf 'deterministic-test-apk' > "$apk"

BUILD_TIMESTAMP=2026-08-11T12:00:00Z \
  "$script_dir/generate-release-metadata.sh" \
  "$apk" \
  bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  0123456789abcdef0123456789abcdef01234567 \
  "$metadata"

expected_sha=$(openssl dgst -sha256 "$apk" | awk '{print tolower($NF)}')
version_code=$(sed -n 's/^VERSION_CODE=//p' "$script_dir/../../version.properties")
jq -e \
  --arg expectedSha "$expected_sha" \
  --arg expectedName "$(basename "$apk")" \
  --argjson expectedSize "$(wc -c < "$apk" | tr -d '[:space:]')" \
  --argjson expectedVersionCode "$version_code" \
  --arg expectedVersionName "$version_name" \
  '.packageName == "com.fnmusic.tv" and
   .versionName == $expectedVersionName and
   .versionCode == $expectedVersionCode and
   .apk.fileName == $expectedName and
   .apk.size == $expectedSize and
   .apk.sha256 == $expectedSha and
   .apk.signingCertificateSha256 == ("b" * 64) and
   .builtAt == "2026-08-11T12:00:00Z" and
   .commitSha == "0123456789abcdef0123456789abcdef01234567"' \
  "$metadata" >/dev/null

if "$script_dir/generate-release-metadata.sh" \
  "$apk" \
  invalid-signer \
  0123456789abcdef0123456789abcdef01234567 \
  "$temp_dir/invalid-signer.json" >/dev/null 2>&1; then
  echo "invalid signer digest was accepted" >&2
  exit 1
fi

wrong_apk="$temp_dir/not-the-release-name.apk"
cp "$apk" "$wrong_apk"
if "$script_dir/generate-release-metadata.sh" \
  "$wrong_apk" \
  bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  0123456789abcdef0123456789abcdef01234567 \
  "$temp_dir/invalid-name.json" >/dev/null 2>&1; then
  echo "invalid APK file name was accepted" >&2
  exit 1
fi

if BUILD_TIMESTAMP=not-a-timestamp "$script_dir/generate-release-metadata.sh" \
  "$apk" \
  bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  0123456789abcdef0123456789abcdef01234567 \
  "$temp_dir/invalid-time.json" >/dev/null 2>&1; then
  echo "invalid build timestamp was accepted" >&2
  exit 1
fi
