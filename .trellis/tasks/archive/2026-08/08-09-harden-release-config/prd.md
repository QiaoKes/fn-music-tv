# Harden release configuration and repository maintenance

## Goal

Move release-only connection signing configuration out of tracked source, reduce its visibility in
distributed Android packages through build-time obfuscation, and complete a separate repository
reference consolidation using neutral public maintenance language.

## Background

- The Android client currently stores two connection-signing parameters as string constants in
  `core/data/src/main/kotlin/com/fnmusic/tv/core/data/server/ConnectionResolver.kt:195` and `:196`.
- The values are controlled by the upstream service and cannot be rotated by this project.
- `.github/workflows/android.yml` already consumes GitHub Secrets for release signing but does not
  inject the connection-signing parameters.
- The public remote has `main` plus three non-main branches. The local clone has six non-main
  branches, and all six contain zero commits that are not already reachable from `main`.
- Seven remote release tags from `v0.1.11` through `v1.0.4`, attached release APKs, one pull-request
  reference, and existing build artifacts or caches are included in the maintenance inventory.
- Client-side obfuscation is a deterrent against straightforward static inspection, not a security
  boundary against a determined runtime attacker.

## Requirements

### R1. External release configuration

- Store the two current service-controlled values as GitHub Environment Secrets scoped to a
  `release` environment and the `main` deployment branch.
- Pass the values to the release build only through step environment variables, never command-line
  arguments, tracked files, workflow output, or logs.
- A release build must fail with a value-free diagnostic when either required variable is absent.
- Debug and unit-test compilation must work without production values and use invalid placeholders
  or injected fakes.

### R2. Build-time obfuscation

- Remove the plaintext constants from tracked Kotlin source.
- Keep the standard encryption/generation and runtime decoding algorithms in reviewed project
  code. Environment variables contain configuration values only, not executable algorithm text or
  algorithm-selection switches.
- Generate a non-cacheable, build-directory-only Kotlin source containing encrypted or reversibly
  obfuscated byte arrays and the runtime reconstruction logic needed by the signer.
- Do not place the raw values in Android resources, manifest metadata, `BuildConfig` string fields,
  Gradle properties, cache keys, task names, filenames, or generated-source logs.
- Keep R8 shrinking and obfuscation enabled for release builds.
- Avoid saving secret-bearing release compilation output to the Gradle build cache.
- Clear transient plaintext byte buffers after signing where the current JVM/Android APIs permit.

### R3. Code boundary and behavior

- Introduce a narrow connection-signing configuration/signer boundary rather than exposing raw
  values throughout the data module.
- Preserve the existing FNID lookup URL, request shape, nonce range, timestamp format, digest
  algorithm, header format, candidate ordering, and error behavior.
- Preserve direct URL/IP login behavior.
- Add focused tests for configured signing behavior, missing configuration, and unchanged request
  signing semantics without embedding production values in fixtures.

### R4. Repository reference consolidation

- Use RFC-reserved `example.com` hostnames for test fixtures.
- Use neutral public descriptions such as `release configuration maintenance` or
  `repository reference consolidation`; do not publish the specific cleanup target in commits,
  tags, release notes, workflow names, or public issue text.
- Normalize retained history with a current `git-filter-repo` release using an approved private
  replacement set.
- Keep only remote branch `main`; delete the three remote non-main branches and all six local
  non-main branches after preflight verification that none owns unique commits.
- Rewrite affected tags while preserving release version names and notes where practical.
- Remove superseded APK/checksum assets and Actions artifacts or caches included in the maintenance
  inventory.
- If GitHub Support is required to remove an internal pull-request reference or cached object, use
  the minimum truthful description in a private support channel and do not publish the details.
- Coordinate old clones so they cannot merge or push the pre-rewrite history back into `main`.

### R5. Prevention and verification

- Enable available GitHub secret scanning and push protection for the public repository.
- Add a repository/CI check that rejects future plaintext assignments for the connection-signing
  parameters without printing any matched value.
- Verify the final Git object graph, remote heads, tags, release assets, Actions artifacts, caches,
  workflow logs, and a newly built APK without printing the protected values.

## Acceptance Criteria

- [x] `git grep` and a full retained-history scan find no approved private replacement target.
- [x] Current tests use only reserved or synthetic hostname fixtures.
- [x] The GitHub `release` environment contains both required secrets and permits deployment only
      from `main`.
- [x] A release build succeeds with the environment secrets and fails safely without them.
- [x] Debug/unit-test builds succeed without access to GitHub Secrets.
- [x] Decompressed APK contents and ordinary DEX/JADX string inspection do not contain either
      plaintext production value.
- [x] Existing connection-signing tests pass with non-production fixtures, and an FNID smoke test
      succeeds against the existing upstream service.
- [x] `git ls-remote --heads origin` returns only `refs/heads/main`.
- [x] No retained branch or tag reaches a Git object containing an approved private replacement
      target.
- [x] Superseded release APK/checksum assets, Actions artifacts, and build caches are
      removed; release notes and clean rewritten tags remain available where practical.
- [x] Public commit messages, tag annotations, workflow names, and release notes use neutral
      maintenance language and do not identify the cleanup target.
- [x] The worktree is clean and the rewritten remote state is independently re-cloned and verified.

## Out of Scope

- Changing or rotating the upstream-managed service parameters.
- Claiming cryptographic secrecy against runtime instrumentation or a determined APK reverse
  engineer.
- Adding a new proxy/backend service or changing the upstream protocol.
- Deleting release notes solely because their attached APK asset is removed.
- Recovering or deleting third-party copies that are outside this repository owner's control.
