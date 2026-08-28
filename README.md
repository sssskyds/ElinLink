# Elin-Link

A generic **BLE serial terminal** Android app for automotive use cases. It scans for Bluetooth Low Energy devices, connects to a selected device, subscribes to a Nordic UART Service (NUS) style notify characteristic to receive streaming serial data, and writes commands over the write characteristic.

## Features

- Scan for nearby BLE devices (name, MAC address, RSSI), sorted by signal strength.
- Connect to a selected device and discover its GATT services/characteristics.
- Subscribe to the NUS notify (TX) characteristic and display incoming data in a scrollable log.
- Text field + Send button to write commands to the NUS write (RX) characteristic.
- Clear connection status: Idle / Scanning / Connecting / Connected / Disconnected, with graceful error handling.
- Runtime permissions for Android 12+ (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) and `ACCESS_FINE_LOCATION` on API 23-30.
- Responsive UI for phones/tablets in all orientations; state (connection, device list, log) is preserved across rotation via a `ViewModel`.

## Nordic UART Service UUIDs

| Role | UUID |
| --- | --- |
| Service | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |
| RX (write, phone -> device) | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| TX (notify, device -> phone) | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |

If your device uses a different serial service, update the UUIDs in `BleViewModel.kt`.

## Project structure

```
ElinLink/
├── settings.gradle
├── build.gradle                 (project-level)
├── gradle.properties
└── app/
    ├── build.gradle             (module-level)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/elin/elinlink/
        │   ├── MainActivity.kt
        │   ├── BleViewModel.kt
        │   ├── ScannedDevice.kt
        │   └── DeviceAdapter.kt
        └── res/
            ├── layout/activity_main.xml
            ├── layout/item_device.xml
            └── values/{strings,colors,themes}.xml
```

## Build the APK

1. Open the project in **Android Studio** (AGP 8.5.2 / Kotlin 1.9.24 / JDK 17).
2. Let Gradle sync and download dependencies.
3. Add the launcher icon: right-click `res` -> **New -> Image Asset -> Launcher Icons**, and select your `elmos.png` as the foreground image (generates `ic_launcher`).
4. Connect a **physical Android phone** (BLE does not work on most emulators) with USB debugging enabled.
5. Press **Run**, or build an APK via **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Usage

Tap **Scan**, grant Bluetooth permissions, tap a device to connect. Once **Connected**, type a command and tap **Send**. Incoming data appears as `RX:` lines in the log.

## Notes

- Commands are sent with a trailing `\n`; incoming bytes are decoded as UTF-8. Adjust in `BleViewModel.kt` if your device uses different framing.
- The launcher icon (`elmos.png`) is not included in this repo; add it via the Image Asset wizard as described above.
