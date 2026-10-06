# OpenCode Localhost Android

Native Android launcher/controller for running a real OpenCode server locally on the phone.

## What it does

- Starts OpenCode on `127.0.0.1:4096`
- Stops the local server
- Checks `/global/health` every 5 seconds
- Installs/repairs the Android Termux OpenCode runtime
- Copies the localhost URL for OpenCode Mobile
- Uses Termux's official `RUN_COMMAND` integration instead of a fake API proxy

## One-time Termux requirement

Use a current Termux build from F-Droid/GitHub. In Termux:

```sh
mkdir -p ~/.termux
printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties
termux-reload-settings
```

Then grant **OpenCode Localhost** the **Run commands in Termux environment** additional permission in Android app settings.

## Runtime

The app prefers `opencode`, then `opencode2`. If neither exists it installs Node.js if needed and installs `opencode-termux` through npm.

Server URL:

```
http://127.0.0.1:4096
```

## Security

The default listener is loopback-only. It is not exposed to Wi-Fi, Tailscale, or the public internet.

## Build

```sh
gradle :app:assembleDebug
```

Package: `win.fantest.opencodelocalhost`
