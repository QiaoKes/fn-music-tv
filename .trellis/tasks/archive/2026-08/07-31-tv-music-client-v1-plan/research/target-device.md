# Target Device: Vidda C3 Pro

- Date checked: 2026-08-01
- User-confirmed launch device: Vidda C3 Pro projector
- Official domestic model shown by Hisense Mall: `VL7R-PRO`

## Confirmed Hardware

The current [official Hisense Mall product page](https://mall.hisense.com/items/6293)
identifies the device as Vidda C3 Pro / `VL7R-PRO`. Its official detail artwork
states:

- MediaTek `MT9681`, 12 nm;
- 4 GB memory and 128 GB storage;
- 4K display pipeline, including 4K 60 Hz signal input/output;
- gigabit Ethernet, Wi-Fi 7 and Bluetooth 5.4;
- two USB 3.0 and two HDMI 2.1 ports.

These are manufacturer claims and make this a substantially stronger launch target
than the provisional 2 GB low-tier TV baseline. They do not prove Android app
compatibility or application-level frame performance.

The user has confirmed that their domestic C3 Pro runs Android. The native Android
client architecture is therefore retained without a VIDAA/Web or external-box branch.

## Still Unconfirmed

The domestic official page does not expose the following implementation-critical
facts in machine-readable specifications:

- Android version and API level;
- whether the launcher declares TV/Leanback features;
- ABI list and 32/64-bit app support;
- exact unknown-source APK/ADB installation path;
- actual application surface size/density (`wm size`, `wm density`) and whether apps
  render at native 3840x2160 or a 1920x1080 logical surface;
- decoder support for the representative NAS codec/container set.

Do not infer exact Android properties from the global Hisense C3 line, which can ship
VIDAA U9 and is a different market/software configuration. Before M0 exits, collect the launch unit's
About screen and, if ADB can be enabled, record:

```sh
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.build.version.release
adb shell pm list features
adb shell wm size
adb shell wm density
adb shell dumpsys media.codec
adb shell dumpsys meminfo <app-package>
```

Until those features are measured, the C3 sideload manifest must treat
`android.software.leanback` and `android.hardware.type.television` as optional, as
well as touchscreen. This changes only installation/launcher filtering, not the
remote-first Compose TV UI. A future Google Play TV artifact can use a separate
manifest overlay with store-required TV features.

## Design Consequences

- Keep the 1920x1080 review canvas until the logical app surface is measured; add a
  native-4K screenshot/focus pass if the projector exposes 3840x2160 to apps.
- On a non-low-RAM native-4K surface, poster artwork may decode up to about 1920 px
  long edge while retaining one foreground bitmap; 1080p/low-RAM targets retain the
  roughly 1200 px decode target.
- Use the projector itself as the primary correctness/performance and remote-input
  gate. The installed API 36 TV AVD remains a deterministic regression target, not a
  performance proxy for MT9681.
- Keep `minSdk=23` provisional until the actual Android API is observed. The product
  remains a native Android APK; do not silently switch it to a Web/VIDAA app.
