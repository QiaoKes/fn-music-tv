# Technical Design

## 1. Scope

This refactor changes only the boundary between a verified APK and the system package installer.
The release manifest, update checks, foreground-only downloader, cryptographic/file checks and TV UI
remain owned by the existing implementation.

```text
update.json
  -> existing HTTPS client
  -> existing foreground OkHttp downloader
  -> exact size + SHA-256
  -> package + versionCode + signer set
  -> AppUpdate ApkUtil.installApk
  -> Android system install confirmation
```

AppUpdate's `DownloadManager`, `HttpDownloadManager`, `DownloadService`, notification flow and
`UpdateDialogActivity` are explicitly outside the design.

## 2. Dependency and flavor boundary

Add version-catalog entries for `appupdate` and `appupdate-no-op`, both pinned to `4.3.6`:

```kotlin
sideloadImplementation(libs.appupdate)
storeImplementation(libs.appupdate.noop)
```

Both artifacts expose `ApkUtil.installApk(Context, String, File)`, so `UpdateInstaller` can stay in
the common source set. The store flavor remains compile-compatible while the no-op implementation and
empty manifest prevent install capability from entering the store APK. `SELF_UPDATE_ENABLED=false`
continues to make the store coordinator Disabled, so the no-op method is not a reachable user path.

## 3. Installer adapter

`UpdateInstaller` becomes a small synchronous adapter:

```kotlin
internal class UpdateInstaller(private val context: Context) {
    fun install(apk: File) {
        ApkUtil.installApk(context, "${context.packageName}.fileProvider", apk)
    }
}
```

Using `context.packageName` is required because debug builds append `.debug`; it always matches the
AppUpdate provider authority generated from `${applicationId}.fileProvider`.

The previous `PackageInstaller.Session` copy, mutable PendingIntent, session commit and
`UpdateInstallReceiver` are removed. Installation remains non-silent because `ACTION_VIEW` resolves
to the Android-owned package installer UI.

## 4. Manifest merge

The sideload source manifest continues to own `REQUEST_INSTALL_PACKAGES`. It removes the obsolete
app receiver and uses manifest merger removal rules for these unused components contributed by
AppUpdate:

- `com.azhon.appupdate.service.DownloadService`
- `com.azhon.appupdate.view.UpdateDialogActivity`

The library FileProvider remains non-exported with URI grants enabled, but its default paths XML is
not accepted: it exposes `/` under external storage/external cache and cannot address the existing
internal `cacheDir/updates` directory. Redeclare the same provider/authority at app priority and
replace its `android.support.FILE_PROVIDER_PATHS` metadata resource with a local file containing only:

```xml
<paths>
    <cache-path name="verified_updates" path="updates/" />
</paths>
```

This keeps the authority `${applicationId}.fileProvider` expected by `UpdateInstaller`, avoids moving
or recopying a verified APK, and grants the system installer access only to the updater's internal
cache subtree.

The store flavor gets `appupdate-no-op`, whose manifest is empty. Merged-manifest tests must prove it
contains neither install permission nor AppUpdate/legacy installer components.

## 5. Coordinator state and lifecycle

After verification:

1. If unknown-source permission is missing, remain in `AwaitingInstallPermission` and launch only the
   current package's settings URI after explicit user action.
2. If permission exists, enter `PreparingInstaller`, set an explicit installer handoff marker, call
   `UpdateInstaller.install(apk)`, then enter `AwaitingSystemConfirmation`.
3. If `installApk` throws because the provider or system activity is unavailable, clear the marker,
   delete the verified APK and show a retryable error.
4. Returning from the permission settings page checks `canRequestPackageInstalls()`; denial deletes
   the APK and requires a new download.
5. Returning from the installer while the same process/version is still active means the external
   confirmation did not replace this app. Delete the APK and show an “installation cancelled or not
   completed” retryable error. A successful replacement normally terminates/replaces the process;
   startup cleanup handles any residual cache.

Replace the current Boolean `systemHandoff` with an enum/sealed marker distinguishing permission and
installer handoffs. This prevents `onResumeFromSystem()` from applying permission logic to an
installer return. `handleInstallStatus(Intent)` and all `PackageInstaller.EXTRA_STATUS` handling are
deleted.

The existing Activity effect remains responsible for opening the unknown-source settings page. The
AppUpdate installer starts its own `NEW_TASK` system intent as defined by the framework. Ordinary
backgrounding still cancels downloads; only a declared handoff may temporarily retain a verified APK.

## 6. UI behavior

No new screen is introduced. Existing `PreparingInstaller` and `AwaitingSystemConfirmation` states
remain to avoid visual churn. Error copy changes from PackageInstaller-specific status messages to
the outcomes observable with an external ACTION_VIEW flow:

- cannot open system installer;
- install permission not granted;
- installation cancelled or not completed.

All errors retain the manifest so the existing “retry” action starts a fresh secure download.

## 7. Security invariants

The following checks remain mandatory before calling AppUpdate:

- response and downloaded byte count equal the manifest size;
- SHA-256 equals the public manifest;
- archive package is exactly `com.fnmusic.tv`;
- candidate version code equals the manifest and exceeds the installed version;
- installed and candidate signer digest sets are both non-empty and equal.

The AppUpdate download stack is never constructed. This avoids its trust-all TLS socket factory,
unrestricted redirect behavior and notification-based install path. The Android system still performs
its own final signature/replacement check.

## 8. Verification strategy

- JVM/Robolectric: installer calls the framework with the expected dynamic authority; coordinator
  state/cleanup for permission grant, denial, install launch failure and installer return.
- Instrumentation: sideload declares permission and resolves the non-exported AppUpdate provider;
  store declares neither. Confirm the system settings intent resolves.
- Build outputs: inspect merged manifests for both flavors and reject unused service/activity,
  legacy receiver or store installation capability.
- Regression: run existing UpdateClient, UpdateDownloader, ApkVerifier, preferences, contracts and
  Compose update dialog tests.
- Device: open the Android install confirmation page on an emulator/device. Final coverage is an
  upgrade from an older signed build on the user's Vidda C3 Pro.

## 9. Risks and mitigations

| Risk | Mitigation |
|---|---|
| AppUpdate's default provider paths are incompatible and overbroad | Override provider metadata with an app-owned `cache-path` limited to `updates/`; assert the merged resource. |
| Store gains sideload capability through manifest merge | Use the no-op artifact and assert merged manifest contents for store. |
| AppUpdate's insecure downloader is accidentally adopted later | Document the installer-only boundary in the Trellis spec and tests; remove contributed service/activity. |
| ACTION_VIEW has no reliable success callback | Treat app resume without replacement as cancel/incomplete; rely on new-process cleanup after successful replacement. |
| Vidda firmware still blocks the system intent | Produce a signed debug/test APK and capture the resolved installer behavior on the actual projector before release. |

## 10. Vidda APK signing compatibility

The external-click failure proves the APK artifact itself is a separate compatibility boundary from
AppUpdate. Fn Music TV 1.0.4 and FNTV 1.3.2/1.3.4 are v1+v2 signed; Fn Music TV 1.0.5 onward became
v2-only when minSdk rose to 29, matching the first reported failure. Keep minSdk/targetSdk unchanged
and explicitly enable v1+v2 on the official release signing config.

CI verifies the staged universal APK with `apksigner --min-sdk-version 21 --verbose`. The explicit
compatibility minSdk is necessary because default verification uses the APK's minSdk 29 and may
report v1 as unused even when the JAR signature is present.
