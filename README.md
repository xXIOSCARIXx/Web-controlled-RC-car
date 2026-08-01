# Shadow Scout

A low-latency remote-control system for an RC car with live video streaming, GPS tracking, and real-time telemetry — built from an Android phone, an Arduino, and a Node.js relay server.

---

## How it works

```
[RC Car + Arduino] ──USB Serial──▶ [Android Phone (publisher)]
                                         │  MJPEG + telemetry (WSS)
                                         ▼
                               [Node.js Relay Server]
                                         │  video + telemetry (WS)
                                         ▼
                               [Browser Viewer (UI.html)]
                                         │  gamepad commands
                                         └──────────────────▶ [Relay] ──▶ [Android] ──▶ [Arduino]
```

The Android phone sits on the car. It streams compressed JPEG frames over a secure WebSocket to the relay server, which fans them out to any connected browser viewer. The viewer sends gamepad input back along the same path; the phone translates it into drive commands and sends them to the Arduino over USB serial at 115 200 baud.

---

## Components

| Part | File(s) | Role |
|---|---|---|
| Android app | `MainActivity.kt`, `MainService.kt`, `UsbCdcSerial.kt` | Publisher — camera, USB serial, telemetry |
| Relay server | `app.js` | Bridges publisher ↔ viewer over WSS/WS |
| Viewer UI | `UI.html` | Browser dashboard — video feed, gamepad, map |
| Car firmware | `car_firmware.ino` | Arduino — servo + ESC control, battery reporting |

---

## Repository layout

```
.
├── android/                  # Android Studio project
│   └── app/src/main/
│       ├── java/com/example/myapplication/
│       │   ├── MainActivity.kt
│       │   ├── MainService.kt
│       │   └── UsbCdcSerial.kt
│       └── res/raw/
│           └── cert.cer      # generated — see Server setup
├── server/
│   ├── app.js
│   └── UI.html
├── firmware/
│   └── car_firmware.ino
└── README.md
```

---

## Server setup

Requires Node.js 18+ and `npm install ws`.

```bash
cd server
npm install ws
node app.js
```

On first run the server generates a self-signed TLS certificate under `ssl/`. Copy `ssl/cert.cer` into your Android project at `app/src/main/res/raw/cert.cer` before building the app — the app pins this certificate for the WSS connection.

Two ports are used:

| Port | Protocol | Purpose |
|---|---|---|
| 47291 | WSS | Android publisher connection |
| 3000 | HTTP + WS | Browser viewer (restricted to `VIEWER_IP`) |

Edit the constants at the top of `app.js` to change ports or the allowed viewer IP.

---

## Android app

### Requirements

- Android 8.0+ (API 26)
- USB OTG cable connecting the phone to the Arduino
- Permissions requested at runtime: `CAMERA`, `ACCESS_FINE_LOCATION`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS` (Android 13+)

### Build

1. Open the `android/` folder in Android Studio.
2. Ensure `cert.cer` is in `app/src/main/res/raw/`.
3. Add the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library to your `build.gradle` dependencies.
4. Build and install on the phone.

### First launch

A dialog prompts for the server host/IP. Enter the machine running `app.js` (e.g. `192.168.1.100`). The value is saved and used for all future connections.

### Status indicators (on-screen)

| Label | Meaning |
|---|---|
| `WSS: Connected` | WebSocket tunnel to relay server is up |
| `CAM: Streaming` | Camera frames are being sent |
| `USB: Connected` | Arduino detected and serial port open |
| `Steer` / `Throt` bars | Live drive values being sent to the car |

Tap the WSS status line to change the server address.

---

## Arduino firmware

### Wiring

| Arduino pin | Connection |
|---|---|
| D2 | Steering servo signal |
| D3 | ESC signal |
| A0 | Battery voltage divider (4.64 kΩ / 1.17 kΩ) |

### Serial protocol (115 200 baud)

**Host → Arduino**

| Byte 0 | Byte 1 | Byte 2 | Byte 3 | Description |
|---|---|---|---|---|
| `'D'` (0x44) | steer (0–255) | throttle (0–255) | `steer XOR throttle XOR 0xFF` | Drive command |
| `'C'` (0x43) | — | — | — | Centre / stop |

**Arduino → Host**

| Byte 0 | Byte 1 | Byte 2 | Description |
|---|---|---|---|
| `'B'` (0x42) | voltage high byte | voltage low byte | Battery millivolts, sent every 500 ms |

### Safety behaviour

- If no valid drive command is received for **500 ms** the steering centres and the ESC goes to neutral.
- The ESC requires a **2-second neutral hold** after power-on before it will accept throttle commands (standard ESC arming sequence).
- Expo curves are applied to both steering (0.80) and throttle (0.0 by default) — adjust the constants at the top of the sketch.
- Forward throttle is capped at **25%** and brake/reverse at **55%** of full range by default.

---

## Viewer UI

Open `http://<server>:3000` in a browser on the machine whose IP matches `VIEWER_IP` in `app.js`.

Features:
- Live MJPEG video feed from the phone camera
- Gamepad support (tested with standard USB/Bluetooth controllers)
- Toggle front/rear camera and torch
- GPS map overlay (uses browser Geolocation for the viewer's own position, car GPS shown separately)
- Telemetry HUD: phone battery, car battery voltage, Wi-Fi signal, cell signal, USB link status

---

## Telemetry messages (WebSocket JSON)

| `type` | Fields | Direction | Description |
|---|---|---|---|
| `battery` | `level` (%) | phone → server → viewer | Phone battery |
| `wifi` | `level` (0–4) | phone → server → viewer | Wi-Fi RSSI level |
| `cell` | `level` (0–4) | phone → server → viewer | Cellular signal level |
| `car_battery` | `mv` | phone → server → viewer | Car battery in millivolts |
| `usb_status` | `status` (string) | phone → server → viewer | USB connection state |
| `gps` | `lat`, `lng`, `acc` | phone → server → viewer | Car GPS position |
| `gamepad` | `steer`, `l2`, `r2` | viewer → server → phone | Drive input axes |
| `toggle_camera` | — | viewer → server → phone | Flip front/rear camera |
| `toggle_torch` | — | viewer → server → phone | Toggle flashlight |
| `viewer_count` | `count` | server → phone | Number of active viewers |

---

## Dependencies

### Android
- [CameraX](https://developer.android.com/training/camerax) (camera capture)
- [OkHttp](https://square.github.io/okhttp/) (WebSocket client)
- [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) (USB CDC serial)
- [Google Play Services Location](https://developers.google.com/android/guides/setup) (fused GPS)

### Server
- [ws](https://github.com/websockets/ws)

### Firmware
- Arduino `Servo` library (built-in)

---

## License

MIT
