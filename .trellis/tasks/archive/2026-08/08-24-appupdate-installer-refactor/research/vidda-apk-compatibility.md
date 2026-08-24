# Vidda APK Compatibility Root-Cause Analysis

## Bug Analysis: Vidda ignores v2-only update APKs

### 1. Root Cause Category

- **Category**: E - Implicit Assumption, with a D - Test Coverage Gap.
- **Specific cause**: the build relied on Android Gradle Plugin's automatic signing-scheme choice.
  When the app minSdk changed from 23 to 29, AGP stopped emitting the legacy v1 JAR signature and
  produced v2-only APKs. Modern Android accepts them, but the Hisense Vidda file-manager/installer
  integration can ignore the click without showing an error.

### 2. Evidence and Bayesian Update

Initial hypotheses:

| Hypothesis | Prior |
|---|---:|
| App installation Intent/provider path | 35% |
| APK signing/packaging compatibility | 35% |
| targetSdk/manifest permission policy | 20% |
| Installed-package state or firmware-specific cache | 10% |

Discriminating evidence:

| APK | minSdk / targetSdk | v1 | v2 | Observed Vidda behavior |
|---|---|---|---|---|
| Fn Music TV 1.0.4 (code 20) | 23 / 36 | yes | yes | historical upgrades worked |
| Fn Music TV 1.0.5 (code 21) | 29 / 36 | no | yes | start of reported no-response upgrades |
| Fn Music TV 1.0.6 (code 22) | 29 / 36 | no | yes | cannot cover 1.0.5 |
| Fn Music TV 1.0.7 (code 23) | 29 / 36 | no | yes | same incompatible shape |
| FNTV 1.3.2 (code 13203) | 21 / 34 | yes | yes | internal/external upgrade works |
| FNTV 1.3.4 (code 13404) | 21 / 34 | yes | yes | compatible upgrade candidate |

The Fn Music TV signer is identical across 1.0.4-1.0.7, and targetSdk is 36 throughout, so signer
identity and targetSdk do not explain the regression boundary. External file-manager clicks also
bypass the app's AppUpdate code, which sharply lowers the installer-Intent hypothesis. Posterior
confidence that v2-only packaging is the primary Vidda compatibility cause is above 90%.

### 3. Why Earlier Fixes Failed

1. Removing or retaining `REQUEST_INSTALL_PACKAGES` changed an app-initiated-install permission,
   but external APK clicks failed too; the fix stayed in the wrong layer.
2. Replacing `PackageInstaller.Session` with AppUpdate corrected the in-app handoff but could not
   change whether Vidda's file manager parsed the candidate APK.
3. Unit, lint, emulator and modern-device checks accepted v2-only packages and did not model the OEM
   file-manager compatibility boundary.

### 4. Prevention Mechanisms

| Priority | Mechanism | Specific action | Status |
|---|---|---|---|
| P0 | Build configuration | Explicitly enable v1 and v2 in the release signing config | Done |
| P0 | CI artifact test | Verify v1+v2 with `apksigner --min-sdk-version 21 --verbose` | Done |
| P1 | Hardware rollout | Cover-install every release candidate on Vidda before publication | Pending device result |
| P1 | Documentation | Record dual-signature compatibility in the update distribution spec | Done |

### 5. Systematic Expansion

- **Similar issues**: any future minSdk, AGP, signing, bundletool or APK repackaging change can alter
  the final signing schemes without a Kotlin/compiler failure.
- **Design improvement**: treat the final distributable APK, not Gradle configuration, as the tested
  contract. CI checks the actual staged universal APK.
- **Process improvement**: external file-manager installation is a separate rollout test from the
  app's updater state machine and must be tested independently.
