# LiveBoot Enhanced — Message and Configuration Guide

This guide documents the message-script format and `config/settings.conf` used by LiveBoot Enhanced v1.0.

## 1. How message playback works

1. At boot, the module starts the LiveBoot runtime from its early `post-fs-data.sh` hook.
2. The runtime reads `config/settings.conf` and opens the selected file in `messages/`.
3. The script parser reads the message file in order. A directive on its own line changes how the next message action is handled; directives are not rendered as message text.
4. Text, progress bars, and image changes are sent to the display handler. Waits and pauses are processed on the script worker, not by blocking the OpenGL render loop.
5. After the final script action, the configured `finish_hold_ms` delay is applied before the normal LiveBoot shutdown sequence continues.

When a valid custom message file is selected, the custom script controls the displayed text instead of live log lines. If the selected file is missing or invalid, the runtime falls back to the normal LiveBoot output path.

## 2. Message-file rules

- Store message files directly in `liveboot_custom/messages/`.
- Set `message=filename.txt` in `config/settings.conf`; use only the filename, not a directory path.
- Message files may be up to 50 MiB (52,428,800 bytes) and are independently limited to 10,000 lines.
- Blank lines are displayed as blank lines.
- Directives must be on their own line unless specifically described as inline text tags.
- Text uses the current line color, which starts as white.
- Text is left-aligned. Horizontal alignment directives are not supported in v1.0.

## 3. Timing directives

Timing values are milliseconds. They are clamped to the range 0 through 7 days.

| Directive | Meaning |
|---|---|
| `[wait=500]` | Wait 500 ms before the next script action. |
| `[delay=500]` | Alias for `[wait=500]`. |
| `[pause=500]` | Apply a 500 ms pause before the next script action. |

Example:

```text
[color=cyan]
Starting services...
[wait=750]
[color=yellow]
Waiting for data...
[pause=1200]
[color=green]
Services ready.
```

Wait/pause directives are queued for the next visible action. If both are set before the same action, their durations are applied sequentially.

## 4. Whole-line colors

Place `[color=COLOR]` on its own line. The selected color remains active until another valid `[color=...]` directive changes it.

```text
[color=cyan]
Boot sequence
[color=white]
Loading system services...
[color=green]
Complete.
```

Color values use Android color names or hexadecimal values supported by Android `Color.parseColor`, for example:

```text
[color=white]
[color=red]
[color=cyan]
[color=#72E6FF]
[color=#FFB347]
[color=#FF70FFAA]
```

The six-digit hexadecimal form is RGB. The eight-digit form is ARGB.

## 5. Inline word colors

Use `[w=COLOR]` and `[/w]` around the text to color only part of a line. The longer `[word=COLOR]` and `[/word]` spellings are aliases.

```text
Mounting /data ... [w=green]OK[/w]
Starting service ... [w=red]FAILED[/w]
Temperature: [w=#FFB347]42 C[/w]
```

Inline color tags can be nested. The innermost active color is used. A closing tag without a matching opening tag is shown as literal text. An invalid color opening tag is also shown literally so the mistake is visible.

## 6. Persistent word highlighting

A highlight rule colors matching whole words in subsequent text lines. Rules are case-sensitive.

```text
[highlight "OK" green]
[highlight "FAILED" red]
[highlight "WARN" yellow]
Mount /data ... OK
Start daemon ... FAILED
Check cache ... WARN
[highlight clear]
```

Accepted forms include:

```text
[highlight "FAILED" red]
[highlight "FAILED"=red]
[highlight "FAILED" with red]
```

`[highlight clear]`, `[highlight none]`, and `[highlight off]` remove all active rules. A rule can also be removed by assigning it `none`, `off`, or `clear`:

```text
[highlight "FAILED" none]
```

At most 64 active highlight rules are retained. Explicit inline colors take priority over highlight rules.

## 7. Progress bars

Progress directives display a 40-character bar and a numeric percentage. Values are clamped to 0–100.

```text
[progress 0]
[wait=300]
[progress=25]
[wait=300]
[progress 50]
[wait=300]
[progress=75]
[wait=300]
[progress 100]
```

The progress value is supplied by the message author. It is not connected to Android's actual boot percentage or system startup state.

## 8. Clear, fill, and replace

### Clear the displayed message history

```text
[clear]
```

This clears the scripted text display and its retained replacement history.

### Fill the visible text area with blank rows

```text
[fill]
```

This appends blank rows to the visible text buffer. It is useful for pushing older content out of the visible area.

### Replace the most recent displayed line

Put `[replace]` on its own line, then put the new text on the following line. The next text or progress line replaces the most recent retained scripted line instead of appending another line.

```text
Connecting to system service...
[wait=1000]
[replace]
System service connected.
```

A blank line, wait, pause, color directive, or image directive may appear between `[replace]` and the new text. The replacement applies to the next text/progress action. If there is no previous scripted line, the new line is displayed normally.

### Replace literal text in retained message history

The parameterized form searches retained scripted lines and replaces every matching occurrence:

```text
[replace "PENDING" with "CONNECTED"]
```

This form is case-sensitive. The replacement text inherits the color of the first character of each matched string. It changes retained scripted text, not kernel logs or text that has already been discarded from the retained history. Double quotes are delimiters and should not appear inside either string.

## 9. Images and image layers

Copy image files directly into `liveboot_custom/images/`. Supported formats are PNG, JPG/JPEG, and WebP.

```text
[image=boot_background.png]
```

An image remains active until another image is selected or it is cleared.

```text
[image=none]
```

`[image=clear]` is an alias for `[image=none]`.

The layer can be selected globally in `settings.conf` using `image_layer=behind` or `image_layer=front`. A message can override the selected layer for an individual image:

```text
[image=logo.png,behind]
[image=overlay.png,front]
```

- `behind`: image is drawn below scripted text.
- `front`: image is drawn above scripted text; opaque image pixels can cover text.

Image decode is performed away from the render loop. Files larger than 32 MiB are rejected. Decoded images are downsampled as needed and limited to 4096 pixels per side and approximately 8 megapixels. A missing, unsupported, or invalid image is skipped; the current image remains displayed.

### Image fitting

Set `image_fit` in `config/settings.conf`:

| Value | Behavior |
|---|---|
| `cover` | Fill the display while preserving aspect ratio; edges may be cropped. |
| `contain` | Show the complete image while preserving aspect ratio; unused space may remain. |
| `stretch` | Fill the display without preserving aspect ratio. |

For a full-screen portrait image, prepare artwork close to the device's display aspect ratio. Transparent PNG files are useful for `front` overlays.

## 10. Fonts and text sizing

A static DejaVu Sans Mono font, `LiveBootMono.ttf`, is included in `liveboot_custom/fonts/` and selected by default. To use another static `.ttf` or `.otf` file, copy it directly into `liveboot_custom/fonts/` and set its exact filename in `config/settings.conf`:

```ini
font=MyFont.ttf
```

Before starting `app_process`, the module launcher copies the selected font into `/data/local/tmp/liveboot-font/`, sets readable file permissions, and verifies that the staged file is non-empty and readable. It also stages `LiveBootMono.ttf` as the fallback. Java reads the staged path from `LIVEBOOT_FONT_PATH`, loads it with `Typeface.Builder` first, and tries `Typeface.createFromFile` if the builder fails. If the configured font cannot be staged or loaded, Java tries the staged `LiveBootMono.ttf`, then the original bundled copy, and finally Android monospace. The detailed Java result, including exception class and message, is written to `/data/local/tmp/liveboot_font_status.log`; staging results are written to `/data/local/tmp/liveboot-font/staging.log` when accessible.

A blank value, `default`, or `monospace` intentionally selects Android’s built-in monospace typeface. Font files must be readable, no larger than 32 MiB, and have a valid TrueType/OpenType signature. Use simple exact filenames without paths; `.ttc` collections and variable font files are not supported by this setting. The font setting applies to the entire message renderer; the script format does not switch fonts line by line.

The staged directory uses ordinary filesystem permissions, but access from the root Java process can still be restricted by a device's SELinux policy. The loader records access failures and attempts the module's bundled fallback path if the staged file cannot be opened. Actual SELinux behavior must be confirmed on the target device.

## 11. Per-line text backgrounds

Set `line_background=on` in `config/settings.conf` to draw an opaque black rectangle behind every displayed text row. Set `line_background=off` to disable it. The default is `off` so existing layouts do not change unexpectedly.

The rectangle width is calculated from the measured text width, with a small horizontal inset. It does not fill the remaining screen width. A long logical line that wraps produces a separate fitted rectangle for each displayed row; wrapped pieces remain part of the same original message line. The setting applies to scripted messages and live logcat/dmesg rows. It is independent of the full-screen background, image opacity, and image layer settings.

Text size settings:

- `text_size=0`: calculate text size from the line count and display height.
- `text_size=1` through `256`: request a fixed pixel size; the renderer reduces it when necessary to fit the font's vertical metrics into a line.
- `lines=1` through `500`: number of display rows used when automatic sizing is active. A higher value makes the text smaller/denser.
- `wordwrap=true`: wrap long lines at spaces when possible.
- `wordwrap=false`: stop at the available width instead of wrapping.

## 12. `settings.conf` reference

The file is `liveboot_custom/config/settings.conf`. Values are read at startup; reboot after editing.

| Key | Accepted value | Default / behavior |
|---|---|---|
| `message` | File name in `messages/` | `default.txt` |
| `transparent` | `true` / `false` | `false`; transparent background when enabled |
| `opacity` | `0`–`100` | `75`; background alpha when `transparent=true` |
| `finish_hold_ms` | `0`–`60000` | `1500`; final screen hold time after the script completes |
| `dark` | `true` / `false` | `true`; black background when true, dark gray when false |
| `image_fit` | `cover`, `contain`, `stretch` | `cover` |
| `image_opacity` | `0`–`100` | `100`; image opacity, in addition to PNG alpha |
| `image_layer` | `behind`, `front` | `behind` |
| `font` | Blank, `monospace`, or `.ttf`/`.otf` filename | `LiveBootMono.ttf`; custom font staged before launch |
| `line_background` | `on` / `off` (also `true` / `false`) | `off`; content-width black background on each displayed row |
| `text_size` | `0`–`256` | `0`; automatic sizing |
| `lines` | `1`–`500` | `100` |
| `wordwrap` | `true` / `false` | `true` |
| `save` | `true` / `false` | `true`; inherited LiveBoot log saving option |
| `logcatcolors` | `true` / `false` | `true`; inherited logcat color option |

Boolean values also accept `yes`/`no` and `1`/`0`. Invalid numeric values are ignored. Out-of-range numeric settings are clamped to the listed range.

Example configuration:

```ini
message=default.txt
transparent=false
opacity=75
finish_hold_ms=1500
dark=true
image_fit=cover
image_opacity=100
image_layer=behind
font=LiveBootMono.ttf
line_background=off
text_size=0
lines=100
wordwrap=true
save=true
logcatcolors=true
```

## 13. Complete sample

```text
[clear]
[color=cyan]
LIVEBOOT ENHANCED v1.0
[wait=500]
[color=white]
Starting boot services...
[highlight "OK" green]
[highlight "FAILED" red]
Mounting partitions ... OK
Checking runtime ... OK
Starting services ... FAILED
[highlight clear]
[wait=700]
[color=yellow]
Retrying service...
[wait=900]
[replace]
[color=green]
Service started successfully.
[wait=500]
[progress=100]
[wait=500]
[color=white]
Boot display sequence complete.
```

## 14. Troubleshooting

**A directive appears as text.** Check spelling and syntax. Directives such as `[wait=500]`, `[clear]`, `[fill]`, `[replace]`, `[progress=50]`, `[color=cyan]`, and `[image=photo.png]` must occupy their own lines. Inline word-color tags are the exception. Center/right alignment tags are not supported in v1.0 and must be removed from message files.

**`[replace]` does not change anything.** It replaces the most recent retained message line with the next visible text/progress line. It cannot replace a line after `[clear]` or when no prior line exists. For literal substitution within displayed history, use `[replace "old" with "new"]`.

**The custom message does not load.** Check `message=` for a filename only, verify the file exists in `messages/`, and ensure it is no larger than 50 MiB (52,428,800 bytes) and contains no more than 10,000 lines.

**An image does not appear.** Check its exact filename and supported extension, confirm it is in `images/`, and verify it is non-empty and below 32 MiB. Image names cannot contain directory paths.

**A custom font does not appear.** Read `/data/local/tmp/liveboot-font/staging.log` and `/data/local/tmp/liveboot_font_status.log`. Confirm that the exact `.ttf`/`.otf` filename in `settings.conf` exists directly in `liveboot_custom/fonts/`, is readable, and is included in the installed module ZIP. The runtime tries staged `LiveBootMono.ttf` and then the module's bundled copy before using Android monospace. A permission-denied error under `/data/local/tmp/liveboot-font/` may indicate a device-specific SELinux restriction; that behavior must be checked on the target firmware.

**The line background fills too much space.** `line_background` is drawn to the measured width of each wrapped display row. Confirm that the installed APK was rebuilt from this source revision. Setting `line_background=off` disables the rectangles.

**The display does not start.** Review `/data/local/tmp/liveboot_debug.log`, confirm the module is enabled, and check that all module launch scripts and daemon helper binaries retain executable mode. Device-specific early graphics initialization still requires on-device validation.
