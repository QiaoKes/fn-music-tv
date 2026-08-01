# Research: Android TV Emulator Setup And Validation

- Date: 2026-07-31
- Host: Apple Silicon (`arm64`), macOS 26.3.2
- Scope: install an isolated Android TV AVD, validate TV and D-pad behavior, try the
  corrected reference APK without logging in, and leave all emulators stopped.

## Outcome

The host now has a reusable, stopped Android TV AVD named `FnMusicTV_API36`.
It uses the stable ARM64 Android TV API 36 image and Google's 1080p television
device profile. The existing phone AVD, `Pixel_Android_35`, was shut down cleanly
before setup and was neither edited nor deleted.

| Property | Validated result |
| --- | --- |
| System-image package | `system-images;android-36;android-tv;arm64-v8a` |
| Image revision | 4 |
| AVD | `FnMusicTV_API36` |
| Device profile | `tv_1080p` / Television (1080p) |
| Android | Android 16, API 36 |
| Guest model / ABI | `sdk_google_atv64_arm64` / `arm64-v8a` |
| CPU / memory | 4 virtual cores; 2048 MiB configured; guest `MemTotal=2021944 kB` |
| Display | 1920x1080, 320 dpi, 60 Hz, rotation 0 in landscape geometry |
| TV capabilities | `android.hardware.type.television`, `android.software.leanback`, and `android.software.leanback_only` present |
| D-pad | `hw.dPad=yes`; virtual input source reports `KEYBOARD | DPAD`; focus round-trip passed |
| Touch caveat | AVD is configured `no-touch` and has no touch-class input device, but this API 36 image still advertises touchscreen features; see below |
| Final runtime state | No attached ADB device and no emulator/QEMU process |

The first measured cold boot completed in about 21.8 seconds on this host. This is
only an environment-readiness number, not an application startup benchmark.

## Image Selection

The stable SDK channel exposed ARM64 Android TV and Google TV images for API 33,
34, and 36. It did not expose an API 35 TV image:

```text
system-images;android-33;android-tv;arm64-v8a
system-images;android-33;google-tv;arm64-v8a
system-images;android-34;android-tv;arm64-v8a
system-images;android-34;google-tv;arm64-v8a
system-images;android-36;android-tv;arm64-v8a
system-images;android-36;google-tv;arm64-v8a
```

API 34 and API 36 are equally close to 35. API 36 was selected because the client
design already recommends `compileSdk=36` and `targetSdk=36`. The generic Android
TV image was selected over Google TV because it is a neutral Leanback test target
and avoids making Google TV content behavior part of the app contract.

Installed host tools at validation time:

```text
Android SDK Command-line Tools 20.0
Android Emulator 36.5.10.0
Android Platform Tools 37.0.0
```

`sdkmanager` and `avdmanager` printed two host-tool warnings: their launcher script
evaluated an empty integer, and this command-line tools build reported that it
understands SDK XML through version 3 while Android Studio supplied version 4
metadata. Both commands exited successfully, and the installed package was
independently verified from `sdkmanager --list_installed` and its
`source.properties`. The warnings should still be rechecked after the next Android
Studio or command-line tools update.

## Setup Runbook

The commands below reproduce the installed target. Do not add `--force` to the AVD
creation command: that could overwrite an existing AVD with the same name.

```sh
# Inspect first.
sdkmanager --list --channel=0 \
  | rg 'system-images;android-(3[4-6]|33);(android-tv|google-tv);arm64-v8a'
emulator -list-avds
adb devices -l

# If a phone AVD is running, resolve its name before stopping it.
adb -s emulator-5554 emu avd name
adb -s emulator-5554 emu kill

# Install and create the independent TV target.
sdkmanager 'system-images;android-36;android-tv;arm64-v8a'
avdmanager create avd \
  --name FnMusicTV_API36 \
  --package 'system-images;android-36;android-tv;arm64-v8a' \
  --device tv_1080p </dev/null
```

The generated `tv_1080p` profile unexpectedly set `hw.screen=multi-touch` and
`hw.initialOrientation=portrait`, even though its LCD dimensions were 1920x1080.
Only the newly created AVD was corrected in
`~/.android/avd/FnMusicTV_API36.avd/config.ini`:

```ini
hw.dPad=yes
hw.device.name=tv_1080p
hw.initialOrientation=landscape
hw.lcd.height=1080
hw.lcd.width=1920
hw.ramSize=2048
hw.screen=no-touch
```

Start it with a cold boot for repeatable validation:

```sh
emulator @FnMusicTV_API36 \
  -no-snapshot \
  -no-boot-anim \
  -no-audio \
  -gpu swiftshader_indirect
```

The emulator logged a non-blocking `VulkanVirtualQueue` support warning and noted
that guest ANGLE is still unstable above API 35. It selected SwiftShader/ANGLE and
rendered correctly. Application performance measurements must therefore be run
again with the normal production-like GPU mode and on physical TV hardware; this
software-rendered boot is suitable for correctness and focus testing only.

## Validation Runbook And Evidence

Always bind commands to the resolved serial before installing or injecting keys:

```sh
TV_SERIAL=emulator-5554

adb -s "$TV_SERIAL" emu avd name
adb -s "$TV_SERIAL" shell getprop ro.boot.qemu.avd_name
adb -s "$TV_SERIAL" shell getprop ro.build.version.sdk
adb -s "$TV_SERIAL" shell getprop ro.build.version.release
adb -s "$TV_SERIAL" shell getprop ro.product.model
adb -s "$TV_SERIAL" shell getprop ro.product.cpu.abilist

adb -s "$TV_SERIAL" shell pm list features \
  | sort \
  | rg 'television|leanback|touchscreen|faketouch|screen.landscape'
adb -s "$TV_SERIAL" shell dumpsys input
adb -s "$TV_SERIAL" shell wm size
adb -s "$TV_SERIAL" shell wm density
adb -s "$TV_SERIAL" shell dumpsys display
adb -s "$TV_SERIAL" shell cat /proc/meminfo | rg '^MemTotal:'
```

The AVD identity resolved twice to `FnMusicTV_API36`. `wm size` returned
`1920x1080`; display services reported a 1920x1080, 60 Hz surface at rotation 0,
and the UI hierarchy occupied `[0,0][1920,1080]`. The TV, Leanback, Leanback-only,
and landscape features were all present.

### D-pad proof

The first-run launcher dialog exposed two focusable actions. ADB key injection was
validated against the structured UI hierarchy rather than treating a zero exit
code as proof:

```sh
adb -s "$TV_SERIAL" shell uiautomator dump /sdcard/window.xml >/dev/null
adb -s "$TV_SERIAL" exec-out cat /sdcard/window.xml \
  | xmllint --xpath 'string(//node[@focused="true"]/@text)' -

adb -s "$TV_SERIAL" shell input keyevent KEYCODE_DPAD_RIGHT
adb -s "$TV_SERIAL" shell uiautomator dump /sdcard/window.xml >/dev/null
adb -s "$TV_SERIAL" exec-out cat /sdcard/window.xml \
  | xmllint --xpath 'string(//node[@focused="true"]/@text)' -

adb -s "$TV_SERIAL" shell input keyevent KEYCODE_DPAD_LEFT
```

Observed focus sequence:

```text
before=Go to Shop
after_right=Dismiss
after_left=Go to Shop
```

This proves that the AVD accepts injected remote-direction events and that its TV
launcher moves focus deterministically in both directions. It does not replace a
full application focus-graph test.

### Touchscreen limitation

After changing `hw.screen` to `no-touch` and cold-booting, `dumpsys input` listed
only `gpio-keys` and the virtual `KEYBOARD | DPAD` device. There was no input device
with the `TOUCHSCREEN` class, and all tested navigation worked with D-pad keys.

However, `pm list features` still returned all of the following:

```text
android.hardware.faketouch
android.hardware.touchscreen
android.hardware.touchscreen.multitouch
android.hardware.touchscreen.multitouch.distinct
android.hardware.touchscreen.multitouch.jazzhand
```

`dumpsys display` also labelled the built-in display as `touch INTERNAL`. This is
an effective emulator/image metadata limitation, not evidence that the app needs touch. It means
this AVD can validate touch-free operation, but it cannot prove Play filtering on a
device where touchscreen features are genuinely absent. The future client must
explicitly declare `android.hardware.touchscreen` with `required=false`; M0 should
verify that declaration from the built APK and repeat install/launch on a physical
non-touch TV or another image that does not advertise the feature.

## Reference APK Attempt

The corrected reference input was verified before installation:

```text
Path: /Users/saki/Downloads/NeteaseCloudMusic_MusicTV_official_1.1.80.260122145233.apk_official_1.1.80.260122145233_3264.apk
SHA-256: b0bba5915590d7ff397c564549718c0ec82711bb48eafec2b32f2de8dcd0c87b
Package: com.netease.cloudmusic.tv
Version: 1.1.80 (1001080)
minSdk / targetSdk: 17 / 29
ABI: arm64-v8a and armeabi-v7a
Leanback launcher: com.netease.cloudmusic.app.LoadingActivity
```

Both streaming and non-streaming ADB install paths reached an interactive Google
Play Protect block:

```sh
adb -s "$TV_SERIAL" install -r "$REFERENCE_APK"
adb -s "$TV_SERIAL" install --no-streaming -r "$REFERENCE_APK"
```

Play Protect labelled it `Unsafe app blocked` because it was built for an older
Android version and lacks current privacy protections. The block was not bypassed.
`Got it` was selected with the D-pad, the waiting ADB install process was stopped,
and checks confirmed that `com.netease.cloudmusic.tv` was not installed. The pushed
copy under `/data/local/tmp` was removed; the original file in Downloads remains
unchanged. The app was therefore not launched, no account was used, and no NAS or
service data was accessed or changed.

Evidence screenshots, both confirmed as 1920x1080:

- [TV emulator home](screens/tv-emulator-home.png)
- [Play Protect block](screens/tv-emulator-play-protect-block.png)

The Play Protect result is useful compatibility evidence: the reference APK's old
SDK target should not be treated as a viable dependency or runtime baseline for the
new client. Its static interaction patterns and earlier confirmed screenshots
remain the appropriate reference boundary.

## Shutdown And Reuse

The validation target was stopped gracefully and left installed for future M0 work:

```sh
adb -s "$TV_SERIAL" emu kill
adb devices -l
ps -axo pid=,command= | rg 'qemu-system|emulator.*-avd|emulator @'
```

Final checks showed no attached emulator and no emulator/QEMU process. Both AVDs
remain available:

```text
FnMusicTV_API36
Pixel_Android_35
```

To reuse the TV target, start `FnMusicTV_API36` explicitly and verify its AVD name
before any install, clear-data, or key-injection command. Do not use the phone AVD
as TV acceptance evidence.
