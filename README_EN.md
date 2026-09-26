<p align="right">
  <sub><a href="README.md">中文文档</a> | English</sub>
</p>

# ADB Bridge

ADB Bridge is an ADB forwarding tool that runs on the Android device itself. After pairing with the device's own wireless debugging, it exposes a WebSocket endpoint on the local network and forwards requests from an ADB client in the browser to the device's adbd, with no adb installed on the computer.

<p align="center">
  <a href="https://count.getloli.com" target="_blank">
    <img alt="Moe Counter!" src="https://count.getloli.com/@journey-ad.adb-ws-bridge?padding=7&offset=0&align=top&scale=1&pixelated=1&darkmode=auto">
  </a>
</p>

## How it works

The app first pairs with the device's own wireless debugging: mDNS discovers the port of the `_adb-tls-pairing._tcp` service, and entering the 6-digit pairing code from the system into the notification completes the handshake. The generated RSA key stays on the device and remains valid afterwards.

Once forwarding starts, the app locates the wireless debugging connect port through mDNS and runs a WebSocket server on the configured port. A browser connects to `ws://device-address:port/adb`, the app opens a tunnel to the local adbd for that connection, and bytes pass through in both directions from then on.

Only one connection is accepted at a time. A new client needs confirmation on the device on its first connection, while already authorized clients connect directly; when a connection password is enabled, the browser has to send the matching password as well.

## Features

- Discovers the pairing and connect ports of wireless debugging through mDNS, pairing code entered in the notification
- RSA key generated and stored on the device, pairing is a one-time step
- Ktor based WebSocket server with a configurable port, 5556 by default
- New clients require confirmation on the device, authorized clients connect directly and can be revoked in settings
- Optional connection password verified when the browser connects
- Live connection details: client, duration, and upstream and downstream rates
- Logs grouped into sessions per connection, with search, detail view and deletion, keeping the latest 20 connection sessions
- Logs can stay in memory only, without being written to storage
- Chinese and English interface, light and dark theme
- Built with Jetpack Compose and Material 3

## Requirements

- Android 11 (API 30) or above
- Wireless debugging available in system settings and already paired with this app
- Client and device on the same local network

## Build

```bash
./gradlew assembleRelease
```

The debug build carries a `.debug` applicationId suffix and can be installed alongside the release build:

```bash
./gradlew assembleDebug
```

Unit tests and static analysis:

```bash
./gradlew testDebugUnitTest lintDebug
```

The GitHub Actions workflow in this repository runs checks and the debug build on pushes and pull requests, and builds a signed release with a GitHub Release when a `v*` tag is pushed.

## Credits

- [Shizuku](https://github.com/RikkaApps/Shizuku): ADB protocol and pairing implementation
- [Ktor](https://github.com/ktorio/ktor): WebSocket server

## License

[MIT LICENSE](LICENSE)
