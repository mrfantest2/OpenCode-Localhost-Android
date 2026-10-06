# OpenCode Localhost Android

A self-contained Android launcher for a real OpenCode v2 server.

## v0.2.0 architecture

**Termux is not required.** The APK embeds an ARM64 Android-native OpenCode runtime and launches it directly from the application's native library directory.

- Local server: `http://127.0.0.1:4096`
- OpenCode runtime: v2.0.22
- Runtime ABI: Android ARM64 / API 28+
- Authentication: HTTP Basic auth
- Username: `opencode`
- Password: generated and stored locally by the APK
- Background operation: Android foreground service
- Optional shared-storage access: Android "All files access" setting

The UI never prints HTTP response bodies. It probes the authenticated `/api/info` endpoint and shows only status, version, PID, connection credentials, and the last lifecycle event.

## Runtime packaging

CI downloads the verified Android-native package:

`Hope2333/opencode-termux -> opencode_2.0.22_aarch64.deb`

The SHA-256 is pinned in the workflow. Only the native OpenCode executable and its required `libopencode-crhandler.so` are embedded.

## Storage

Without special storage access, the OpenCode workspace is the app's external-files directory.

After granting **All files access**, the workspace becomes shared phone storage at `/storage/emulated/0`.

Android still prevents an ordinary unrooted app from accessing other apps' private data and protected system paths.

## Build

Push to `main` or run the GitHub Actions workflow. The produced APK is ARM64-only.

Package: `win.fantest.opencodelocalhost`
