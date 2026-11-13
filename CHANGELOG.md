# Changelog

## Unreleased — 2026-11-13

- Increase the maximum custom message-script file size from 1 MiB to 50 MiB (52,428,800 bytes); retain the independent 10,000-line guard.
- Stage the configured TTF/OTF font into `/data/local/tmp/liveboot-font/` synchronously before launching `app_process`.
- Verify staged font copies and permissions, pass staged/fallback paths through environment variables, and log loader exceptions with their messages.
- Initialize Android’s preinstalled Typeface map reflectively before constructing `Runner`, because direct `app_process` startup can bypass normal application-binding initialization.
- Fall back to the bundled `LiveBootMono.ttf` before Android monospace if a configured custom font cannot be loaded.
- Add `line_background=on|off`; when enabled, draw an independent black background behind each displayed/wrapped text row, limited to its measured text width.
- Add host-side smoke checks for font staging and content-sized line-background rendering.
- Log the Typeface map initialization result alongside each font load/fallback result.
- Device validation remains pending, including hidden-API access and `/data/local/tmp` SELinux access.

## v1.0 — 2026-11-11

- Establishes the first public LiveBoot Enhanced release.
- Removes horizontal text alignment support; message text uses the standard left inset.
- Adds bare `[replace]` to replace the most recent displayed message with the next text/progress line.
- Retains parameterized `[replace "old" with "new"]` for literal substitution in retained message history.
- Updates app and module branding to LiveBoot Enhanced v1.0 and credits idklelpigeon.
- Sets the default sample message to a generic boot sequence rather than a device-specific demonstration.
- Adds release documentation, parser smoke tests, and a GitHub Actions build workflow.
- Makes the build script select SDK/JDK/AAPT2 from the local environment and makes the package script verify the newly built runtime APK.

## Earlier project work

Earlier custom module development introduced configurable message playback, image layers, font selection, word colors, highlight rules, progress bars, script timing, and the custom text renderer. Those features are documented in `MESSAGE_GUIDE.md`.


## Font loading correction

- Bundled a static DejaVu Sans Mono TTF as `LiveBootMono.ttf` and selected it in the default module configuration.
- Load with `Typeface.Builder` first on Android 8.0+, falling back to `Typeface.createFromFile` for compatibility.
- Removed Typeface singleton identity checks that could reject an otherwise valid loaded font.
- Validate the font file's location, readability, size, and sfnt signature before loading.
- Write a clear load/fallback result to `/data/local/tmp/liveboot_font_status.log`.
- Normalize packaged font file permissions and test that the font and license are present.
