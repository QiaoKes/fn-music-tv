# Reference APK and AppUpdate Research

## Inputs

- APK: `/Users/saki/Downloads/fntv_1.3.2_dangbei.apk`
- Static analysis only; the APK was not installed or executed.
- Official source: `https://github.com/azhon/AppUpdate`, inspected at the repository's current
  `main` branch and the documented `4.3.6` release API.

## APK identity

| Field | Value |
|---|---|
| Package | `com.trim.tv` |
| Version | `1.3.2` |
| Version code | `13203` |
| minSdk / targetSdk | `21 / 34` |
| File size | about 48 MB |
| APK SHA-256 | `89f0074fdc0714535ec17d2b52e9ed502e9b295a06b4f1605c0af8405d624d68` |
| Signer SHA-256 | `76b6df09ee2ff4ec83891d7872e2adead055fc6662480e23b0cff4d1101ae173` |
| Signature schemes | v1 and v2 |

## Manifest evidence

The reference APK explicitly requests `android.permission.REQUEST_INSTALL_PACKAGES`. It contains:

- App-owned `androidx.core.content.FileProvider` with authority `com.trim.tv.fileprovider`.
- AppUpdate `com.azhon.appupdate.config.AppUpdateFileProvider` with authority
  `com.trim.tv.fileProvider`.
- AppUpdate `com.azhon.appupdate.service.DownloadService`.
- AppUpdate `com.azhon.appupdate.view.UpdateDialogActivity`.

Therefore the project owner's statement that no whitelist is required means no Hisense/Vidda
manufacturer whitelist is required. It does not mean the APK omits Android's install-packages
permission.

## Decompiled update flow

`com.trim.tv.utils.upgrade.AppUpgrade` calls the app's update API with the package name, current
version code, Android platform, device brand/model and language. The result is shown with the app's
own TV dialogs.

The obfuscated wrapper `C1535k70.m(UpdateAppInfo)` builds AppUpdate's `DownloadManager` using the
server APK URL and a file named `FN_TV_<versionCode>.apk`, then starts AppUpdate's download service.
On completion, `C2079qm` computes and stores an MD5 for reusing the downloaded APK later. The final
installation is AppUpdate's FileProvider-backed `ACTION_VIEW` intent.

The observed integration does not provide the same authorization boundary as this project: the
cached MD5 path is weaker than the current exact byte count + SHA-256 + package + version + signer
verification.

## Official AppUpdate behavior

`ApkUtil.installApk(context, authority, apk)` starts an `ACTION_VIEW` intent with MIME type
`application/vnd.android.package-archive`, `FLAG_ACTIVITY_NEW_TASK`, and
`FLAG_GRANT_READ_URI_PERMISSION`. Android N+ obtains the URI from the library FileProvider.

The full artifact manifest also contributes `REQUEST_INSTALL_PACKAGES`, `POST_NOTIFICATIONS`, a
download service, a FileProvider and an update dialog activity. The official no-op artifact has an
empty manifest and the same `ApkUtil.installApk(...)` signature, which is suitable for the store
flavor.

The library's default `app_update_file.xml` grants `/` under external storage and external cache. It
does not cover the existing internal `cacheDir/updates` destination and is broader than this app
needs. The app must override the provider metadata with a local paths resource containing only a
`cache-path` for `updates/`.

The default `HttpDownloadManager` must not be used here because it installs a trust-all TLS socket
factory globally and follows redirects without enforcing the fixed-host policy. The library service
also owns completion notifications before this project's package/version/signer verification could
authorize installation.

## Decision

Use AppUpdate only as the final, already-verified APK handoff. Keep the existing updater for update
checks, foreground download, cancellation, SHA-256 and Android replacement validation. Strip the
library's unused service and dialog activity from the sideload merged manifest, and use the no-op
artifact for store builds. Override the provider's paths metadata so the only shared files are
verified APKs in internal `cacheDir/updates/`.
