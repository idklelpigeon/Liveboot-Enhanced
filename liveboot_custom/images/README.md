# LiveBoot images

Place PNG, JPG/JPEG, or WebP images directly in this directory (no subfolders).

Use `[image=filename.png]` in a message file to display an image. Layer defaults to `image_layer=` in `config/settings.conf`. Per-image overrides: `[image=filename.png,behind]` and `[image=filename.png,front]`. `[image=none]` or `[image=clear]` removes the current image.

Use a full-display canvas such as 1080x2400 for the Galaxy S21. With matching source and GL viewport dimensions, `image_fit=cover` maps the full canvas without aspect-ratio crop. For different dimensions, `cover` fills and crops, `contain` fits the whole image with empty margins, and `stretch` fills the screen even if it distorts aspect ratio.

For `front` overlays, transparent PNG is recommended; opaque JPG/PNG will cover text wherever the photo is opaque. The post-fs-data hook starts the renderer early and warms referenced image data concurrently; it does not pause boot while reading large image files.
