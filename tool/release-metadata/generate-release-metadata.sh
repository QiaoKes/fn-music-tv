#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: $0 <apk> <signing-certificate-sha256> <commit-sha> <output>" >&2
  exit 2
fi

apk=$1
signer_sha=$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')
commit_sha=$(printf '%s' "$3" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
output=$4
repo_root=$(cd "$(dirname "$0")/../.." && pwd)

test -f "$apk"
test -f "$repo_root/version.properties"
command -v jq >/dev/null
command -v openssl >/dev/null

set -a
# shellcheck disable=SC1091
source "$repo_root/version.properties"
set +a

[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]]
[[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
[[ "$signer_sha" =~ ^[0-9a-f]{64}$ ]]
[[ "$commit_sha" =~ ^[0-9a-f]{40}$ ]]

file_name=$(basename "$apk")
expected_name="fn-music-tv-$VERSION_NAME-universal.apk"
[[ "$file_name" == "$expected_name" ]]
size=$(wc -c < "$apk" | tr -d '[:space:]')
sha256=$(openssl dgst -sha256 "$apk" | awk '{print tolower($NF)}')
built_at=${BUILD_TIMESTAMP:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}
[[ "$built_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]

mkdir -p "$(dirname "$output")"
jq -n \
  --arg packageName "com.fnmusic.tv" \
  --arg versionName "$VERSION_NAME" \
  --argjson versionCode "$VERSION_CODE" \
  --arg fileName "$file_name" \
  --argjson size "$size" \
  --arg sha256 "$sha256" \
  --arg signingCertificateSha256 "$signer_sha" \
  --arg builtAt "$built_at" \
  --arg commitSha "$commit_sha" \
  '{
    schemaVersion: 1,
    packageName: $packageName,
    versionName: $versionName,
    versionCode: $versionCode,
    apk: {
      fileName: $fileName,
      size: $size,
      sha256: $sha256,
      signingCertificateSha256: $signingCertificateSha256
    },
    builtAt: $builtAt,
    commitSha: $commitSha
  }' > "$output"

jq -e '
  .schemaVersion == 1 and
  .packageName == "com.fnmusic.tv" and
  (.versionName | test("^[0-9]+\\.[0-9]+\\.[0-9]+$")) and
  (.versionCode | type == "number" and . > 0) and
  (.apk.fileName | type == "string" and endswith("-universal.apk")) and
  (.apk.size | type == "number" and . > 0) and
  (.apk.sha256 | test("^[0-9a-f]{64}$")) and
  (.apk.signingCertificateSha256 | test("^[0-9a-f]{64}$")) and
  (.builtAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
  (.commitSha | test("^[0-9a-f]{40}$"))
' "$output" >/dev/null
