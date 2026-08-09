# Design: release configuration and repository maintenance

## Architecture

### Configuration flow

1. GitHub's `release` environment owns `FN_CONNECT_AUTHX_PREFIX` and
   `FN_CONNECT_API_KEY` as environment secrets. No algorithm source, cipher name, decoder program,
   or algorithm-selection flag is stored in the environment.
2. The release workflow exposes them only to the Gradle build step as environment variables.
3. A non-cacheable Gradle generation task reads the variables at execution time and emits Kotlin
   under `core/data/build/generated/`.
4. The reviewed project code contains a fixed AES-256-GCM generator/decoder implementation. Each
   release build creates a fresh random content key and nonce, encrypts both values, and writes only
   ciphertext plus split key-reconstruction byte arrays into generated source. No plaintext string
   is entered into the DEX string table.
5. A narrow internal signer reads reconstructed bytes, updates the existing digest incrementally,
   and clears temporary arrays in `finally`.

The decoder and its reconstruction material ship in the APK. This design intentionally provides
static-analysis resistance only; it does not represent an unextractable client secret.

Keeping the algorithm in source follows the normal cryptographic boundary: confidentiality depends
on protected values, not on hiding executable algorithm text. Loading executable algorithm text
from an environment variable would still require an interpreter or equivalent implementation in
the APK, would not prevent extraction, and would make builds harder to audit and reproduce.

### Variant behavior

- Release packaging requires both environment variables and disables the Gradle build cache.
- Debug and test variants generate an unconfigured provider when the variables are absent.
- Tests inject synthetic byte values directly into the signer and never depend on generated
  production configuration.
- FNID resolution reports the existing unavailable error when production configuration is absent;
  direct URL/IP resolution remains independent.

### Code boundaries

- `ConnectionResolver` remains responsible for input classification, lookup, and probing.
- A new internal signing abstraction owns the exact request-header calculation.
- Generated configuration is package-internal and exposes temporary bytes rather than public
  immutable strings.
- The production provider is the only consumer of generated build output.

## Build and CI controls

- The workflow job declares `environment: release` and branch-restricted deployment policy.
- Secrets are assigned in `env`, never interpolated into a Gradle command.
- Release packaging uses `--no-build-cache`; the workflow does not persist project build outputs.
- A post-build check decompresses the APK and searches binary contents for exact secret values.
  It reports only pass/fail and masks both inputs before inspection.
- A source check searches tracked files for exact values and prohibited plaintext constant forms
  without emitting matching lines.

## Repository rewrite

### Preflight

- Freeze writes and capture exact old remote head/tag object IDs.
- Produce an encrypted, offline-only recovery bundle and checksum; never upload it.
- Reconfirm that every local non-main branch has zero commits outside `main`.
- Create a fresh mirror clone for rewriting; do not rewrite the working development clone in place.

### Rewrite and publication

- Use `git-filter-repo >= 2.47` with `--sensitive-data-removal --replace-text` and a temporary
  permission-restricted replacement file outside the repository. Replacement targets remain in a
  private maintenance inventory and are never written to logs.
- Retain only `refs/heads/main`; rewrite retained release tags and remove non-main heads.
- Verify the rewritten mirror before any push, then update `main` and tags with explicit expected
  old object IDs rather than an unconstrained blind mirror push.
- Delete remote non-main heads, attached superseded APK/checksum assets, relevant Actions artifacts,
  and Gradle caches.
- Preserve version labels and release notes with neutral descriptions.

### Remote-only references

GitHub pull-request refs are read-only. After all writable refs are clean, request private GitHub
Support cleanup using the minimum truthful information required for dereferencing and garbage
collection. No public issue or release note will describe the private maintenance inventory.

## Compatibility

- Network protocol and endpoint behavior remain unchanged.
- Existing installed APKs remain operational because the upstream values do not change.
- Rewritten commits receive new object IDs. Collaborators must re-clone or carefully rebase; merging
  superseded history is forbidden.
- Existing signed commit/tag signatures affected by rewriting may need recreation.

## Rollback

- Before publication, discard the mirror clone to abort with no remote impact.
- After publication, the encrypted offline bundle can restore repository references, but it must
  never be pushed without repeating the maintenance because it contains the superseded object graph.
- Application changes can be reverted independently before history publication; GitHub Environment
  Secrets remain available for a corrected build.

## Trade-offs

- No third-party obfuscation plugin is added, reducing supply-chain and maintenance risk.
- Randomized generated code makes release builds non-reproducible at the byte level, which is
  acceptable for this mitigation but must be documented.
- Disabling release build caching increases CI time in exchange for avoiding secret-bearing cache
  entries.
