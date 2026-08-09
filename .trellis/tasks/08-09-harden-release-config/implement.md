# Implementation plan

## 1. Pre-development gate

- [x] Load `trellis-before-dev` and all relevant backend/build-pipeline specifications.
- [x] Record exact current remote heads, tags, release assets, Actions artifacts, caches, and PR refs
      without printing protected values.
- [x] Reconfirm clean starting worktree and zero unique commits on every local non-main branch.

## 2. Externalize and obfuscate configuration

- [x] Replace non-reserved hostname fixtures in server normalization and connection tests with
      RFC-reserved examples.
- [x] Add the internal signer/configuration boundary and replace tracked plaintext constants.
- [x] Add a non-cacheable Gradle generator for encrypted, split byte payloads under `build/`.
- [x] Make release packaging fail safely when either environment variable is missing.
- [x] Keep debug/test compilation independent of production configuration.
- [x] Update the GitHub workflow to use the branch-restricted `release` environment, environment
      variables, `--no-build-cache`, masking, and plaintext absence checks.
- [x] Add focused unit/build tests using synthetic credentials only.

## 3. Configure GitHub and validate application changes

- [x] Transfer current values directly from the local tracked source into GitHub Environment
      Secrets through stdin without showing them in process arguments, output, or chat.
- [x] Configure the `release` environment deployment policy for `main` only.
- [x] Run unit tests, lint, release assembly, APK binary scan, and an FNID smoke test.
- [x] Confirm no raw production value exists in the worktree, generated source logs, workflow YAML,
      Gradle cache, or unpacked APK.
- [x] Commit application/build changes with a neutral maintenance message.

## 4. Rewrite and consolidate repository references

- [x] Freeze writes and create the encrypted offline recovery bundle and reference inventory.
- [ ] Install/verify `git-filter-repo >= 2.47` and rewrite a fresh mirror clone using a protected
      temporary replacement file.
- [ ] Remove every non-main head from the rewritten mirror and verify affected tags are clean.
- [ ] Force-update `main` and rewritten tags using expected old object IDs; delete all remote
      non-main heads.
- [ ] Delete all local non-main branches after remote verification.
- [ ] Remove affected Release APK/checksum assets, Actions artifacts, and all relevant Gradle caches.
- [ ] Privately request GitHub removal of remaining read-only PR/cached references if necessary.

## 5. Final verification

- [ ] Fresh-clone the rewritten repository and scan every retained Git object against the approved
      private replacement set.
- [ ] Confirm the remote exposes only `main` and clean retained tags.
- [ ] Confirm release notes remain, removed package assets are unavailable, and no relevant Actions
      artifact or cache remains.
- [ ] Confirm new CI packaging succeeds and its logs contain no protected value or derived plaintext.
- [ ] Run the Trellis quality gate, update applicable specs, record the maintenance mapping privately,
      and archive the task.

## Validation commands

Commands that consume protected values must obtain them from masked environment variables and print
only aggregate counts or pass/fail results.

- `./gradlew test lint --no-daemon`
- `./gradlew :app:assembleSideloadRelease --no-daemon --no-build-cache`
- APK unzip plus binary exact-value absence check
- Full retained-object scan using `git rev-list --objects --all` and `git cat-file`
- `git ls-remote --heads origin`
- `gh release view`, Actions artifact API, and cache inventory checks

## Risk and rollback gates

- Do not publish the rewrite until the application change and new release build have passed.
- Do not delete local branches until their unique-commit counts remain zero immediately before
  deletion.
- Do not delete the encrypted recovery bundle until the fresh-clone verification and GitHub-side
  cleanup are complete.
- Stop before force-updating refs if any unexpected remote head, tag, fork, PR, or collaborator
  change appears after the preflight inventory.
