<img width="5332" height="2840" alt="1000075276" src="https://github.com/user-attachments/assets/d3e35e51-b8d5-4fb5-b679-a51dcd3753ec" />

# LiveBoot Enhanced

[![Version](https://img.shields.io/badge/version-1.0_stable-blue)](https://github.com/idklelpigeon/Liveboot-Enhanced/releases)
[![GitHub Release](https://img.shields.io/github/v/release/idklelpigeon/Liveboot-Enhanced?style=flat-square&color=brightgreen)](https://github.com/idklelpigeon/Liveboot-Enhanced/releases)
[![Platform](https://img.shields.io/badge/platform-KernelSU%20Next%20%7C%20KernelSU%20%7C%20Magisk-green)](#compatibility)
[![Tested](https://img.shields.io/badge/tested-Galaxy%20S21%205G%20(G991N)-brightgreen)](#compatibility)
[![Telegram](https://img.shields.io/badge/Telegram-channel-blue?logo=telegram)](https://t.me/livebootplus)
[![Telegram Chat](https://img.shields.io/badge/Telegram-chat-blue?logo=telegram)](https://t.me/livebootplusdiscussion)
[![License](https://img.shields.io/badge/license-GPLv3-orange)](liveboot/)

A KernelSU/Magisk boot-display module built on the open-source LiveBoot Android application. It keeps everything LiveBoot already does — full logcat and dmesg streaming during boot — and layers on a scriptable message engine with per-line and per-word styling, animated progress bars, image backgrounds, and layout control.

In short: **if it can be said on the boot screen, this module can say it — in color and over a background image.**

> [!IMPORTANT]
> Pre-packaged installable ZIPs are available on the [GitHub Releases](https://github.com/idklelpigeon/Liveboot-Enhanced/releases) page. If you prefer to build from source, `build_module.sh` compiles the current Java source and refreshes the module APK. No prebuilt APK is committed directly to the repository.

> [!TIP]
> **v1.0 is the last device-verified release.** The staged-font and content-sized line-background changes in the current source have host-side checks, but still require boot testing on a device before they can be described as verified.

> [!NOTE]
> **Community:** join the [Telegram channel](https://t.me/livebootplus) for releases and the [discussion chat](https://t.me/livebootplusdiscussion) for support, bug reports, and script sharing.

---

## Contents

- [Preview](#preview)
- [Why LiveBoot Enhanced](#why-liveboot-enhanced)
- [Features](#features)
- [Known Issues](#known-issues)
- [Compatibility](#compatibility)
- [Installation](#installation)
- [Building from Source](#building-from-source)
- [Configuration](#configuration)
- [Project Structure](#project-structure)
- [Testing](#testing)
- [Community](#community)
- [License](#license)
- [Disclaimer](#disclaimer)

---

## Preview

<img width="1080" height="2400" alt="1000095862" src="https://github.com/user-attachments/assets/f1f30324-968e-4113-ad2d-5eaadd7ff571" />
<img width="1080" height="2400" alt="1000095861" src="https://github.com/user-attachments/assets/3101542a-440c-43ef-8a61-a945edbb4935" />

---

## Why LiveBoot Enhanced

Stock LiveBoot streams kernel and logcat output — useful, but visually flat and completely fixed. LiveBoot Enhanced keeps that stream intact and gives you a scripting layer on top:

| Capability | Stock LiveBoot | LiveBoot Enhanced |
|---|---|---|
| Live logcat / dmesg output | ✅ | ✅ preserved |
| Custom authored messages | ❌ | ✅ millisecond-timed scripts |
| Per-line color | ❌ | ✅ |
| Inline word colors & highlights | ❌ | ✅ |
| Animated progress bars | ❌ | ✅ custom colors |
| Image backgrounds (PNG/JPEG/WebP) | ❌ | ✅ behind or in front |
| Custom TTF/OTF fonts | ❌ | ✅ staged deployment and bundled fallback (device test pending) |
| Content-sized line backgrounds | ❌ | ✅ optional black background per displayed text row |
| Adjustable text size & wrapping | ❌ | ✅ |
| Message replacement (two modes) | ❌ | ✅ |
| Clear / fill / overwrite regions | ❌ | ✅ |

---

## Features

### Boot behavior

- **Early boot launch** through KernelSU Next / KernelSU / Magisk service scripting, with a **guarded late fallback** so the overlay still appears if the early stage is unavailable on a particular firmware.
- **Original LiveBoot runtime preserved** — logcat and dmesg paths still function, so you can keep diagnostic boot output alongside custom messages instead of choosing between them.
- **Module-native layout** — `module.prop`, `service.sh`, `post-fs-data.sh`, and a `config/` directory that survives module updates.

### Message scripting

- **Millisecond-precision timing** — each line can be shown, held, or skipped with explicit waits, so the boot sequence reads as intentional rather than dumped.
- **Waits and pauses** — separate primitives for `wait` (delay between actions) and `pause` (block until an event), so scripts can pace themselves around kernel milestones.
- **Two forms of message replacement** — replace-in-place for status lines that update (e.g. "Mounting /system…" → "Mounted"), and full-region replacement for swapping entire blocks.
- **Script control directives** — clear, fill, and position regions of the display independently, so you can build a stable header/footer and only animate the middle.
- **Default + examples included** — `messages/default.txt` ships as a working demo; additional bundled scripts show word coloring, highlighting, and replacement in use.

### Text styling

- **Persistent line colors** — assign a color per line that stays applied for the whole line, independent of later styling.
- **Inline word colors** — color individual words or spans without affecting the rest of the line, useful for emphasizing `[ OK ]`, `[FAIL]`, or subsystem names.
- **Persistent word-highlighting rules** — declare a pattern once (e.g. a keyword) and every matching occurrence is highlighted automatically across the whole script, without editing each line.
- **Left-aligned rendering** — consistent, predictable layout across screen sizes; center/right alignment is intentionally out of scope for this release.
- **Content-sized line backgrounds** — optional opaque black rectangles are drawn only behind each displayed text row, sized to its measured text width. Wrapped portions receive independent rectangles instead of a full-width bar.

### Progress and status

- **Progress bars** — draw an in-place progress indicator with configurable colors, useful for long-running steps like filesystem checks or module loading.
- **Clearing and filling** — blank a line, blank a region, or fill a region with a solid color to build section dividers and visual grouping.
- **In-place updates** — status text and progress can share the same line and update without scroll, so the boot screen stays tidy.

### Images

- **Multiple formats** — PNG, JPEG, and WebP backgrounds are all supported.
- **Two layering modes**:
  - `behind` — image sits under all text (classic branded boot screen).
  - `front` — image sits over the text layer (watermarks, overlays, frame effects).
- **Three fit modes**:
  - `cover` — fill the screen, crop overflow (best for photos and wallpapers).
  - `contain` — fit entirely within the screen (best for logos that must not be cropped).
  - `stretch` — stretch to exact screen dimensions (best for pre-sized overlays).
- **Adjustable opacity** — tune image transparency so text stays legible over busy backgrounds.

### Fonts and layout

- **Adjustable text size** — pick a size that reads well on the device's screen without clipping.
- **Word wrapping** — long lines wrap cleanly instead of being truncated, with a configurable limit.
- **Configurable line count** — control how many lines are visible at once so layouts stay stable.
- **Custom TTF/OTF fonts** — the launcher stages the selected font before starting Java; before constructing the renderer, Java attempts to initialize Android’s preinstalled Typeface map (normally populated during application binding) and then loads the staged font. Loader and initialization errors are recorded, with the bundled `LiveBootMono.ttf` tried before Android monospace. The early-boot initialization path still needs device verification.

### Configuration surface

Everything above is driven from `config/settings.conf` and files in `messages/`. There is no app to launch, no GUI to navigate — change a value, reboot, see the result.

---

## Known Issues

| Issue / validation item | Status | Notes |
|---|---|---|
| Custom TTF/OTF fonts | 🟡 Requires device validation | The source stages fonts under `/data/local/tmp/liveboot-font/` and initializes the preinstalled Typeface map reflectively before custom font construction. Loader and initialization failures are logged; bundled `LiveBootMono.ttf` is tried before Android monospace. The reflection workaround and SELinux access must still be verified on the target firmware. |
| Content-sized line backgrounds | 🟡 Requires device validation | Host-side source checks cover the per-row, measured-width drawing path. Visual behavior with image layers still needs on-device testing. |

The existing v1.0 behavior for message scripting, line/word colors, highlighting rules, progress bars, image layers, text size, wrapping, and line count was tested on the reference device. The two changes above are new source changes and are not included in that device-verification claim.

---

## Compatibility

### Verified

| Device | Model | Root solution | Status |
|---|---|---|---|
| Samsung Galaxy S21 5G | SM-G991N | KernelSU Next | ✅ v1.0 tested, working |

### Expected (untested)

- Other devices running KernelSU Next, KernelSU, or Magisk with a supported boot-display path.
- Android firmware variants that allow early-boot surface rendering.

> [!WARNING]
> Early-boot overlays are device- and firmware-sensitive. Behavior on untested devices is not guaranteed. Always keep a recovery path (fastboot, download mode, or a known-good boot image) before flashing.

---

## Installation

### Option A: Download from GitHub Releases (Recommended)

1. Go to the [GitHub Releases](https://github.com/idklelpigeon/Liveboot-Enhanced/releases) page.
2. Download the latest `LiveBoot_Enhanced_v1.0.zip` file.
3. Copy the ZIP to your device.
4. In KernelSU Next, KernelSU, or Magisk, open **Modules** → **Install from storage** and select the ZIP.
5. Wait for installation to finish, then reboot.

### Option B: Build from Source

See the [Building from Source](#building-from-source) section to compile the APK and package the ZIP yourself.

### Configuration

To change messages or appearance after installation, edit `/data/adb/modules/liveboot_custom/config/settings.conf` and reboot.

> [!WARNING]
> Do not install multiple boot-overlay modules that launch the same LiveBoot runtime. The module ID and fixed paths are `liveboot_custom`; renaming the ID requires updating the paths in the module launcher and service scripts.

---

## Building from Source

### Termux (Android ARM64)

```sh
pkg update -y
pkg install -y openjdk-17 zip unzip coreutils

export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
export PATH="$JAVA_HOME/bin:$PREFIX/bin:$PATH"
export ANDROID_SDK_ROOT="$HOME/android-sdk"   # adjust if needed

termux-setup-storage
mkdir -p "$HOME/LiveBoot-Enhanced"
unzip -oq "$HOME/storage/downloads/LiveBoot_Enhanced_v1.0-Source.zip" -d "$HOME/LiveBoot-Enhanced"
cd "$HOME/LiveBoot-Enhanced/LiveBoot_Enhanced_v1.0"

chmod 0755 build_module.sh package_module.sh liveboot/gradlew
./build_module.sh
```

`build_module.sh` verifies JDK 17 and SDK 34, writes `liveboot/local.properties`, resolves `aapt2` from `PATH` when present, runs `:liveBootAni2:assembleDebug`, and copies the resulting APK to `liveboot_custom/files/liveboot.apk`.

> [!NOTE]
> The first build requires network access to download Gradle and Maven dependencies. Keep the device awake and avoid terminating Termux while Gradle is running.

### Packaging the ZIP

Run the full release sequence with one command:

```sh
./release.sh
```

This runs the parser regression tests, invokes `build_module.sh`, and then invokes `package_module.sh` to create the final ZIP. The resulting archive places `module.prop` at its root and includes the freshly compiled runtime. Launch scripts and daemon helper binaries are packaged with executable permissions.

---

## Configuration

See [`MESSAGE_GUIDE.md`](MESSAGE_GUIDE.md) for directives, examples, settings, image/font placement, limits, and troubleshooting.

- Default script: `liveboot_custom/messages/default.txt`.
- Bundled examples demonstrate word colors and message replacement.
- To select a different script, set `message=` in `config/settings.conf` to a filename inside `messages/`.
- `font=` selects a staged TTF/OTF file; `line_background=on` enables content-sized black backgrounds behind displayed text rows. See [MESSAGE_GUIDE.md](MESSAGE_GUIDE.md).

---

## Project Structure

```text
.
├── liveboot/                 Android Gradle project and application source
├── liveboot_custom/          KernelSU/Magisk module payload
├── build_module.sh           Build APK and update module runtime
├── package_module.sh         Validate and package module ZIP
├── release.sh                Run tests, build, and package
├── MESSAGE_GUIDE.md          Message syntax and settings reference
├── CHANGELOG.md              Release history
└── .github/workflows/        Automated build workflow
```

### Script reference

| Script | Purpose |
|---|---|
| `build_module.sh` | Compile current Java source and refresh `liveboot_custom/files/liveboot.apk` |
| `package_module.sh` | Validate required paths and APK checksum, then produce the installable ZIP |
| `release.sh` | Run tests → build → package in one step |
| `tests/run-parser-smoke.sh` | Host-side parser and text-replacement smoke test |
| `tests/run-font-smoke.sh` | Host-side staged-font and per-row-background source checks |

---

## Testing

Run the host-side checks:

```sh
./tests/run-parser-smoke.sh
./tests/run-font-smoke.sh
```

These verify parser behavior, text replacement, font staging/source wiring, and the content-sized background drawing path. They do **not** replace a full Android build or on-device KernelSU/Magisk boot testing.

The v1.0 release was tested end-to-end on **Galaxy S21 5G (G991N)** running **KernelSU Next**. That verification does not cover the new staged-font loader or content-sized line-background renderer in this source revision; see [Known Issues](#known-issues).

---

## Community

| Channel | Link | Purpose |
|---|---|---|
| Telegram channel | https://t.me/livebootplus | Release announcements, changelogs, feature updates |
| Telegram chat | https://t.me/livebootplusdiscussion | Support, bug reports, script sharing, general discussion |

Bug reports with device model, root solution, firmware build, font staging log, font status log, and logcat capture are useful for validating the new paths.

---

## License

Based on [LiveBoot](https://github.com/Chainfire/liveboot) by Jorrit "Chainfire" Jongma. Original source attribution and [GPLv3](liveboot/COPYING) license files are retained in `liveboot/`. See [`CREDITS.md`](CREDITS.md) for project attribution and dependency links.

---

## Disclaimer

v1.0 has been validated on a single reference device (Galaxy S21 5G / G991N on KernelSU Next). A successful build and ZIP validation confirm that the source compiled and the archive is structurally complete; they do not guarantee early boot rendering, device-specific graphics initialization, image decoding, or compatibility with every Android firmware/root combination.

The custom-font staging, early-boot Typeface map initialization, and content-sized line-background changes in the current source have not yet been verified on a device. In particular, the hidden Typeface initialization method and SELinux access to `/data/local/tmp/liveboot-font/` depend on the target firmware and root implementation.

Test the module on your own target device and keep a recovery path available before distributing it further.
