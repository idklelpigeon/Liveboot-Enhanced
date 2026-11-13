# LiveBoot Enhanced fonts

`LiveBootMono.ttf` is bundled as the default custom font and the runtime fallback. It is DejaVu Sans Mono, distributed under the Bitstream Vera font license; see `DEJAVU_FONT_LICENSE.txt`.

To use another font, copy a static TrueType (`.ttf`) or OpenType (`.otf`) font file directly into this directory and set its exact filename in `../config/settings.conf`, for example:

```ini
font=MyFont.ttf
```

The launcher reads `font=` and stages the selected font into `/data/local/tmp/liveboot-font/` before starting `app_process`. It also stages `LiveBootMono.ttf` as the fallback. Before constructing the renderer, Java reflectively invokes Android’s hidden `Typeface.loadPreinstalledSystemFontMap()` method. A direct `app_process` launch can bypass the normal application-binding step that initializes the default typeface map; without that map, custom Typeface construction can fail even when the font file is valid. After the initialization attempt, the renderer validates the staged file, loads it with `Typeface.Builder` (then `Typeface.createFromFile` as a compatibility fallback), and records the map-initialization result and font-loader errors in `/data/local/tmp/liveboot_font_status.log`. If the platform method is unavailable or blocked, the failure is logged and the existing font fallback sequence continues.

Use the filename only: no path, quotes, or directory separators. Filenames are case-sensitive on Android. Keep the font below 32 MiB. Variable fonts and collections (`.ttc`) are not accepted by this configuration.

Set `font=monospace` or leave it blank to use Android's built-in monospace face intentionally. If staging or loading a configured custom font fails, LiveBoot attempts the bundled `LiveBootMono.ttf` before falling back to Android monospace. Staging diagnostics are written to `/data/local/tmp/liveboot-font/staging.log` when that directory is accessible. The early-boot Typeface initialization workaround requires on-device validation on each target Android/OEM build.
