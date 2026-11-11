# LiveBoot Enhanced fonts

`LiveBootMono.ttf` is bundled as the default test font. It is DejaVu Sans Mono, distributed under the Bitstream Vera font license; see `DEJAVU_FONT_LICENSE.txt`.

To use another font, copy a static TrueType (`.ttf`) or OpenType (`.otf`) font file directly into this directory and set its exact filename in `../config/settings.conf`, for example:

```ini
font=MyFont.ttf
```

Use the filename only: no path, quotes, or directory separators. Filenames are case-sensitive on Android. Keep the font below 32 MiB. Variable fonts and collections (`.ttc`) are not accepted by this configuration.

Set `font=monospace` or leave it blank to use Android's built-in monospace face. If a custom font cannot be read or parsed, LiveBoot falls back to monospace and records the reason in `/data/local/tmp/liveboot_font_status.log`.
