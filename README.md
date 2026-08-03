# ShadowScoutX

A hobby RC car project that turns an Android phone into an onboard FPV (first-person view) unit. The phone streams live video, audio, GPS position, and telemetry to a browser dashboard, and a gamepad connected to the browser drives the car in real time via a USB-connected Arduino.

As long as the phone has mobile data and the relay server is reachable from the internet, the car has effectively unlimited range — it can drive as far as cellular coverage reaches.

```
[Browser + Gamepad] ──WS (LAN)──► [Node.js Relay Server] ──WSS──► [Android App]
                                                                          │
                                                                    USB Serial
                                                                          │
                                                                [Arduino + Servos]
```

---

## Features

### Live Video Streaming
- Camera frames are captured via CameraX, converted from YUV to NV21, JPEG-compressed at quality 75, and streamed as binary WebSocket frames to all connected viewers.
- Supports switching between front and rear cameras mid-stream via a gamepad button or the viewer UI.
- Streaming automatically pauses when the phone receives a call and resumes afterwards.
- Frame rate is throttle-limited to prevent saturating the connection.

### Live Audio Streaming
- Microphone audio is captured using `AudioRecord` (16 kHz, 16-bit mono) and streamed as binary frames interleaved with video, distinguished by a frame-type header byte (`0x01` = audio, anything else = video).
- The viewer page includes an unmute button since browsers require a user gesture before playing audio.

### Torch / Flashlight Control
- The rear camera torch can be toggled remotely from the browser dashboard by pressing the **A button** on the gamepad, or via a button in the viewer UI.
- Torch state is managed through CameraX `Camera2CameraControl` capture request options.

### GPS Tracking
- Uses Google's Fused Location Provider for high-accuracy GPS with a 1-second update interval.
- Coordinates and accuracy are streamed as JSON to the relay server and forwarded to all viewers in real time.
- The viewer displays a live satellite map (Leaflet.js + Esri World Imagery tiles) in the bottom-right corner with a position marker and an accuracy radius circle.
- Clicking the mini-map expands it to a full-screen interactive map. The mini-map auto-follows the vehicle; dragging it unlocks auto-follow.
- Coordinates are displayed in decimal degrees with ±accuracy in metres.

### Android Phone Telemetry (HUD)
- **Phone battery**: Level streamed as JSON and shown as a colour-coded battery icon (green → amber → red as it depletes).
- **Wi-Fi signal**: RSSI polled every 5 seconds and shown as a 4-bar signal widget.
- **Cellular signal**: Reported via `PhoneStateListener` and shown as a second 4-bar signal widget. Covers Android 9 through 14+.

### Car Battery Monitoring
- The Arduino samples its battery voltage via a resistor-divider on analog pin A0, averages 16 ADC readings, and sends a 3-byte binary packet (`'B'` + 2 voltage bytes) over USB serial every 500 ms.
- The Android app reads these packets from the USB serial port, decodes the millivolt value, and forwards it to the server as `{"type":"car_battery","mv":...}`.
- The viewer renders the voltage as a large readout that turns **amber below 6.8 V** and **red below 6.2 V**.

### USB Serial / Arduino Control
- The Android app connects to an Arduino (or any CDC-ACM USB serial device) using the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library at 115200 baud.
- USB connection and reconnection are fully automatic — the app polls for a device every 2 seconds and requests Android USB permission at first connection.
- Gamepad drive commands received from the server are relayed over USB as a 4-byte packet: `'D'` | steer byte | throttle byte | checksum, where checksum = `steer XOR throttle XOR 0xFF`. The firmware validates this checksum and discards corrupt packets.
- A `'C'` (centre) command is sent on clean shutdown to return servos to neutral.
- USB status is shown on the Android UI and forwarded to the viewer HUD.

### RC Vehicle Firmware (Arduino)
- Controls a steering servo (pin 10) and an ESC (pin 9) via standard PWM microsecond signals (1000–2000 µs).
- **Expo curves**: Steering uses 80% expo for precise centre control with reduced sensitivity. Throttle expo is configurable.
- **Asymmetric throttle limits**: Forward is capped at 25% of full range (`ESC_FORWARD_LIMIT`) while braking/reverse uses 55% (`ESC_BRAKE_LIMIT`), preventing runaway acceleration while retaining strong braking authority.
- **ESC arming sequence**: On power-up the ESC is held at neutral for 2 seconds (`ARM_HOLD_MS`) before drive commands are accepted, satisfying standard ESC arming requirements.
- **Command watchdog**: If no valid drive packet is received within 500 ms (`COMMAND_TIMEOUT`), steering centres and the ESC returns to neutral. The built-in LED mirrors link state (on = link alive).
- **Battery telemetry**: Voltage is read from a 4.64 kΩ / 1.17 kΩ resistor divider, averaged over 16 samples, calibrated (`BAT_CALIBRATION = 0.994`), and sent upstream at 2 Hz.
- Steering centre trim: `STEER_CENTER_US = 1460 µs` (adjustable per vehicle).

### Browser Gamepad Control
- Uses the Web Gamepad API, polled at 50 Hz (every 20 ms).
- **Left stick axis 0** → steering. **R2** → forward throttle. **L2** → brake/reverse. Throttle = `R2 − L2`, clamped to ±1.
- **Deadzone**: 15% on the steering axis to ignore stick drift.
- **Steering trim**: D-Pad left/right adjusts a persistent trim offset in ±0.01 steps (max ±1.0) that is added to the raw stick value before sending.
- **A button** → toggle torch. **B button** → toggle camera (front/rear).
- A keepalive packet is sent at least every 100 ms even when inputs haven't changed, so the Arduino watchdog stays satisfied.
- The HUD displays live bar graphs for steer and throttle values plus the trim offset, and shows the detected gamepad name.
- Gamepad state is serialised to JSON and forwarded through the relay to the Android app, which extracts axes and trigger values and sends drive bytes over USB.

### Relay Server (Node.js)
- Two separate HTTPS/WSS servers on different ports: one for the Android publisher (`47291`) and one for browser viewers (`3000`).
- The publisher connection uses **mutual TLS** — a self-signed certificate is auto-generated with OpenSSL on first run and must be installed as a raw resource in the Android app (`res/raw/cert.cer`). The Android app pins this certificate using a custom `TrustManager`.
- The viewer server uses plain HTTP/WS (suitable for LAN; add a reverse proxy with a real cert for internet access).
- The server caches the last message of each telemetry type (`battery`, `wifi`, `cell`, `car_battery`, `usb_status`, `gps`) and replays them immediately to any viewer that connects mid-session, so the HUD is fully populated on join.
- Only one publisher is permitted at a time; a new connection closes the previous one.
- Binary video/audio frames are forwarded only when the viewer's `bufferedAmount` is zero, providing natural backpressure to prevent buffer bloat.
- Viewer-initiated messages (`toggle_camera`, `toggle_torch`, `gamepad`) are forwarded upstream to the publisher only, never broadcast.
- The publisher is notified of viewer count and connect/disconnect events.

### Android App UI
- Minimal always-on dark UI built with Jetpack Compose.
- Displays WSS connection status, camera streaming state, USB connection state, and live steer/throttle bar graphs (mirroring what the viewer sees).
- Server IP/hostname is entered via a dialog on first launch and persisted in `SharedPreferences`. Tapping the status line re-opens the dialog to change server.
- Screen is kept on via `FLAG_KEEP_SCREEN_ON`.
- A **QUIT** button cleanly stops the foreground service, closes the WebSocket, centres servos, and exits.
- Runs as a `LifecycleService` foreground service so it survives the activity being backgrounded, with a persistent notification.
- UI updates are throttle-limited to ~30 fps to avoid unnecessary recompositions.

---

## Repository Structure

```
├── app.js              # Node.js relay server
├── UI.html             # Browser viewer dashboard (single-file, no build step)
├── firmware/
│   └── firmware.ino    # Arduino sketch
└── android/            # Android Studio project (build from source)
    ├── MainActivity.kt
    ├── MainService.kt
    └── UsbCdcSerial.kt
```

---

## Requirements

### Relay Server
- Node.js 18+
- `npm install ws` (only dependency)
- OpenSSL available on `$PATH` (for certificate generation on first run)

### Browser Viewer
- Any modern browser with WebSocket and Web Gamepad API support (Chrome/Edge recommended for gamepad support)
- A gamepad or controller connected via USB or Bluetooth

### Android App
- Android 9 (API 26) or newer
- Built from source — **no pre-built APK is distributed** (see below)

### Arduino / RC Hardware
- Arduino Uno or Nano (or any AVR board with `Servo.h` support)
- Standard RC steering servo on pin 10
- ESC on pin 9 (standard 50 Hz PWM, 1000–2000 µs range)
- Resistor divider on A0: 4.64 kΩ (high side) + 1.17 kΩ (low side) for battery voltage sensing
- USB cable from Arduino to Android phone (OTG adapter may be required)

---

## Setup

### 1. Relay Server

```bash
npm install ws
node app.js
```

On first run, a self-signed TLS certificate is generated in `ssl/`. Copy `ssl/cert.cer` into the Android project at `app/src/main/res/raw/cert.cer` before building the app.

The server prints the ports it is listening on. By default:

| Port  | Purpose                      |
|-------|------------------------------|
| 47291 | Android publisher (WSS/TLS)  |
| 3000  | Browser viewer (WS/HTTP)     |

### 2. Arduino Firmware

Open `firmware/firmware.ino` in the Arduino IDE (1.8+ or 2.x), select your board and port, and upload. The `Servo.h` library is included with the Arduino IDE — no additional libraries are needed.

Adjust the constants at the top of the sketch to match your hardware:

| Constant           | Default | Description                            |
|--------------------|---------|----------------------------------------|
| `STEER_CENTER_US`  | 1460    | Steering servo centre in microseconds  |
| `ESC_FORWARD_LIMIT`| 0.25    | Max forward throttle fraction          |
| `ESC_BRAKE_LIMIT`  | 0.55    | Max brake/reverse throttle fraction    |
| `STEERING_EXPO`    | 0.80    | Steering expo (0 = linear, 1 = cubic)  |
| `BAT_CALIBRATION`  | 0.994   | Voltage calibration multiplier         |

### 3. Android App (build from source)

The Android app is not available as a pre-built APK. You must build it yourself using Android Studio. This is intentional — the self-signed server certificate is embedded at build time, and you need to supply your own.

**Steps:**

1. Install [Android Studio](https://developer.android.com/studio) (Hedgehog or newer recommended).
2. Clone this repository and open the `android/` folder as an Android Studio project.
3. Generate the relay server certificate first (run `node app.js` once), then copy `ssl/cert.cer` to `android/app/src/main/res/raw/cert.cer`.
4. Connect your Android phone via USB, enable Developer Options and USB Debugging.
5. Build and install: **Run ▶ → Run 'app'**, or from the terminal:
   ```bash
   ./gradlew installDebug
   ```
6. On first launch, enter your relay server's IP address or hostname when prompted.

**Required permissions** (declared in `AndroidManifest.xml`, requested at runtime):
- `CAMERA`
- `RECORD_AUDIO`
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
- `READ_PHONE_STATE`
- `POST_NOTIFICATIONS` (Android 13+)
- `FOREGROUND_SERVICE`
- `WAKE_LOCK`

**Dependencies** (add to `build.gradle`):
- `androidx.camera:camera-camera2`
- `androidx.camera:camera-lifecycle`
- `com.google.android.gms:play-services-location`
- `com.github.mik3y:usb-serial-for-android`
- `com.squareup.okhttp3:okhttp`

### 4. Viewer

Open `http://<server-ip>:3000` in a browser. Connect your gamepad before opening the page, or connect it at any time — it is detected automatically. The HUD and map populate as soon as the Android app connects and starts sending telemetry.

---

## Protocol Reference

### USB Serial (Android → Arduino)

| Byte 0 | Byte 1     | Byte 2        | Byte 3                         |
|--------|------------|---------------|--------------------------------|
| `'D'`  | Steer byte | Throttle byte | `steer XOR throttle XOR 0xFF`  |

- Steer and throttle are mapped from ±1.0 float to 0–255 unsigned byte (127 = centre/neutral).
- `'C'` (single byte) centres both outputs immediately.

### USB Serial (Arduino → Android)

| Byte 0 | Byte 1      | Byte 2     |
|--------|-------------|------------|
| `'B'`  | Voltage MSB | Voltage LSB |

- Value is battery millivolts as a big-endian `uint16_t`.

### WebSocket JSON Messages

| `type`               | Direction              | Fields                                      |
|----------------------|------------------------|---------------------------------------------|
| `battery`            | Android → Viewers      | `level` (0–100)                             |
| `wifi`               | Android → Viewers      | `level` (0–4)                               |
| `cell`               | Android → Viewers      | `level` (0–4)                               |
| `car_battery`        | Android → Viewers      | `mv` (integer millivolts)                   |
| `usb_status`         | Android → Viewers      | `status` (string)                           |
| `gps`                | Android → Viewers      | `lat`, `lng`, `acc` (float)                 |
| `gamepad`            | Viewer → Android       | `connected`, `axes`, `buttons`, `pressed`   |
| `toggle_torch`       | Viewer → Android       | *(no extra fields)*                         |
| `toggle_camera`      | Viewer → Android       | *(no extra fields)*                         |
| `publisher_status`   | Server → Viewers       | `connected` (bool)                          |
| `publisher_disconnected` | Server → Viewers  | *(no extra fields)*                         |
| `viewer_count`       | Server → Android       | `count` (integer)                           |

### WebSocket Binary Frames (Android → Viewers)

| Byte 0  | Remaining bytes  |
|---------|------------------|
| `0x01`  | Raw PCM audio chunk (16 kHz, 16-bit, mono) |
| anything else | JPEG video frame |

---

## Networking Notes

- The publisher WebSocket (port 47291) uses a self-signed TLS certificate, pinned in the Android app, so only your phone can connect as the driver. This port needs to be reachable from the internet (port-forward it on your router) — the phone connects out over mobile data, giving the car effectively unlimited range as long as there's cell coverage.
- The viewer port (3000) stays on the LAN; the browser connects locally to the relay server while the car itself can be anywhere.
- There's no login on the viewer by default — anyone on your LAN who knows the port can watch and grab the controls. For a private session, a simple nginx `auth_basic` block is enough.

---

## License

MIT
