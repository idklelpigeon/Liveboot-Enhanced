# Changelog

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
