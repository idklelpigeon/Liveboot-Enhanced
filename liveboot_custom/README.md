# LiveBoot Enhanced module

This directory is the installable KernelSU/Magisk module payload. Build the Android runtime from `../liveboot/` first, then run `../package_module.sh` from the repository root.

The runtime APK is generated at `files/liveboot.apk`; it is intentionally not distributed as a stale prebuilt file in the source repository. The package script refuses to create a module ZIP until a fresh APK has been built and its checksum has been recorded.

Configuration and message-script documentation is in the repository root `MESSAGE_GUIDE.md` and `README.md`.

The module ID is `liveboot_custom`. Do not rename it without updating the fixed module path in `files/liveboot`, `post-fs-data.sh`, and `service.sh`.
