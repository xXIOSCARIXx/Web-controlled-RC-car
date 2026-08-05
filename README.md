# ShadowScoutX

A hobby RC car project that turns an Android phone into an onboard FPV (first-person view) unit. The phone streams live H.264 video, audio, GPS position, and telemetry to a browser dashboard, and a gamepad connected to the browser drives the car in real time via a USB-connected Arduino.

As long as the phone has mobile data or Wi-Fi, the car has effectively unlimited range. On Wi-Fi the phone connects to the relay server on the local network directly; on mobile data the relay server needs to be reachable from the internet (port-forward port 47291 on your router).

```
[Browser + Gamepad] ◄──WSS (LAN)──► [Node.js Relay Server] ◄──WSS──► [Android App]
                                                                           │
                                                                     USB Serial
                                                                           │
                                                               [Arduino + ESC & Servo]
```

---

## Features

### Live Video Streaming
- Camera frames are captured via CameraX and encoded in real time to **H.264/AVC** using Android's `MediaCodec` hardware encoder (960×720, 5 Mbps VBR, 30 fps, Baseline profile Level 3.1).
- The encoder is configured with `KEY_I_FRAME_INTERVAL = 0` so every frame is eligible to be a keyframe; the SPS/PPS config buffer is prepended to each IDR frame before it is sent, making every keyframe self-contained and enabling a viewer to start decoding from any IDR.
- Encoded NAL units are prefixed with a `0x02` type byte and sent as binary WebSocket frames.
- The browser decodes the H.264 stream using the **WebCodecs `VideoDecoder` API** and renders frames onto a full-window canvas.
- Camera is locked to **fixed focus** (`CONTROL_AF_MODE_OFF`, `LENS_FOCUS_DISTANCE = 0.0`) with both optical and video stabilization disabled to minimise encoder complexity and latency. Exposure compensation is set to +2 EV.
- Supports switching between front and rear cameras mid-stream via a gamepad button or the viewer UI.
- Camera capture and audio recording only start when at least one viewer is connected; they stop automatically when all viewers disconnect, saving power and resources.


### Live Audio Streaming
- Microphone audio is captured using `AudioRecord` (16 kHz, 16-bit mono, `VOICE_COMMUNICATION` source) and streamed as binary frames interleaved with video.
- Binary frames are distinguished by a type byte: `0x01` = audio (raw PCM), `0x02` = H.264 video.
- The viewer page includes an unmute button since browsers require a user gesture before playing audio.

### Torch / Flashlight Control
- The rear camera torch can be toggled remotely from the browser dashboard by pressing the **A button** on the gamepad, or via a button in the viewer UI.
- The active camera (front/rear) can be switched mid-stream with the **B button**.
- Torch state is managed through CameraX `Camera2CameraControl` capture request options.

### Remote Alarm
- Any viewer can trigger the car's onboard alarm by pressing the **X button** on the gamepad, or via a button in the viewer UI.
- When active, the Android phone plays a looping alarm sound (`res/raw/alarm`) using `MediaPlayer`.
- The browser shows a pulsing amber **"ALARM ACTIVE"** overlay with a reminder to press X to silence.
- Alarm state is tracked server-side and broadcast to all connected viewers, so the overlay stays in sync across multiple browser sessions.
- The alarm is automatically stopped and the overlay cleared when the publisher disconnects.

### Horn / Honk
- A horn sound (`res/raw/honk`) can be triggered remotely from the viewer UI.
- `play_honk` / `stop_honk` messages are forwarded by the relay server to the Android app, which plays or stops a looping `MediaPlayer` instance independently of the alarm.

### GPS Tracking
- Uses Google's Fused Location Provider for high-accuracy GPS with a preferred 2-second update interval and a minimum 1-second interval.
- Coordinates and accuracy are streamed as JSON to the relay server and forwarded to all viewers in real time.
- The viewer displays a live satellite map (Leaflet.js + Esri World Imagery tiles) in the bottom-right corner with a position marker and an accuracy radius circle.
- Clicking the mini-map expands it to a full-screen interactive map (press Escape or the close button to return).
- Coordinates are displayed in decimal degrees with ±accuracy in metres.

### Android Phone Telemetry (HUD)
- **Phone battery**: Level streamed as JSON and shown as a colour-coded battery icon (green → amber → red as it depletes).
- **Wi-Fi signal**: RSSI polled every 5 seconds and shown as a 4-bar arc signal widget.
- **Cellular signal**: Reported via `TelephonyCallback` / `PhoneStateListener` and shown as a 4-bar signal widget. Covers Android 9 through 14+. A pulsing red warning banner appears when signal is weak (level ≤ 2).

### Car Battery Monitoring
- The Arduino samples its battery voltage via a resistor-divider on analog pin A0, averages 16 ADC readings, and sends a 3-byte binary packet (`'B'` + 2 voltage bytes) over USB serial every 500 ms.
- The Android app reads these packets from the USB serial port, decodes the millivolt value, and forwards it to the server as `{"type":"car_battery","mv":...}`.
- The viewer renders the voltage as a large readout that turns **amber below 10.5 V** and **red below 9.6 V**.

### USB Serial / Arduino Control
- The Android app connects to an Arduino (or any CDC-ACM USB serial device) using the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library at 115200 baud.
- USB connection and reconnection are fully automatic — the app polls for a device every 2 seconds and requests Android USB permission at first connection.
- Gamepad drive commands received from the server are relayed over USB as a 4-byte packet: `'D'` | steer byte | throttle byte | checksum, where checksum = `steer XOR throttle XOR 0xFF`. The firmware validates this checksum and discards corrupt packets.
- A `'C'` (centre) command is sent on clean shutdown to return servos to neutral.
- The Android side suppresses redundant drive packets — a packet is only sent if the steer or throttle value changed, or more than 200 ms have passed since the last send (`DRIVE_KEEPALIVE_MS = 200`).
- USB status is shown on the Android UI and forwarded to the viewer HUD.

### RC Vehicle Firmware (Arduino)
- Controls a steering servo (pin 10) and an ESC (pin 9) via standard PWM microsecond signals (1000–2000 µs).
- **Expo curves**: Steering uses 80% expo for precise centre control with reduced sensitivity. Throttle expo is configurable.
- **Asymmetric throttle limits**: Forward is capped at 25% of full range (`ESC_FORWARD_LIMIT`) while braking/reverse uses 55% (`ESC_BRAKE_LIMIT`), preventing runaway acceleration while retaining strong braking authority.
- **ESC arming sequence**: On power-up the ESC is held at neutral for 2 seconds (`ARM_HOLD_MS`) before drive commands are accepted, satisfying standard ESC arming requirements.
- **Command watchdog**: If no valid drive packet is received within 500 ms (`COMMAND_TIMEOUT`), steering centres and the ESC returns to neutral. The built-in LED mirrors link state (on = link alive).
- **Battery telemetry**: Voltage is read from a 4.64 kΩ / 1.17 kΩ resistor divider, averaged over 16 samples, calibrated (`BAT_CALIBRATION = 0.994`), and sent upstream at 2 Hz.
- Steering centre trim: `STEER_CENTER_US = 1500 µs` (adjustable per vehicle).

### Viewer Stats Overlay & Connection State
- A top-centre stats bar shows three colour-coded pills: **FPS** (green / amber below 24 / red below 15), **latency** (round-trip ping time to the phone; amber above 200 ms / red above 500 ms), and **viewer count**.
- Latency is measured by a ping sent every 2 seconds from the browser; the relay forwards it to the Android app, which echoes a pong; the browser records the round-trip time on receipt.
- A full-screen **connection-lost overlay** appears (with a 400 ms grace period) whenever the publisher is absent. It shows a distinct "Waiting for publisher…" state if no publisher has ever connected in this session, and a "CONNECTION LOST" state if one has disconnected.
- Clicking the video canvas toggles the browser's native fullscreen mode.

### Browser Gamepad Control
- Uses the Web Gamepad API, polled at 50 Hz (every 20 ms).
- **Left stick axis 0** → steering. **R2** → forward throttle. **L2** → brake/reverse. Throttle = `R2 − L2`, clamped to ±1.
- **Deadzone**: 15% on the steering axis to ignore stick drift.
- **Steering trim**: D-Pad left/right adjusts a persistent trim offset in ±0.01 steps (max ±1.0) that is added to the raw stick value before sending. The trim offset is shown as a red overlay on the steer bar.
- **A button** → toggle torch. **B button** → toggle camera (front/rear). **X button** → toggle alarm.
- A keepalive packet is sent at least every 100 ms even when inputs haven't changed, so the Arduino watchdog stays satisfied.
- The HUD displays live bar graphs for steer and throttle values plus the trim offset, and shows the detected gamepad name.
- Gamepad state is serialised to JSON and forwarded through the relay to the Android app, which extracts axes and trigger values and sends drive bytes over USB.

### Relay Server (Node.js)
- Two separate HTTPS/WSS servers on different ports: one for the Android publisher (`47291`) and one for browser viewers (`3000`). Both use the same self-signed TLS certificate.
- The publisher connection uses a self-signed certificate auto-generated with OpenSSL on first run; the `.cer` file must be installed as a raw resource in the Android app (`res/raw/cert.cer`). The Android app pins this certificate using a custom `TrustManager`.
- The server caches the last message of each telemetry type (`battery`, `wifi`, `cell`, `car_battery`, `usb_status`, `gps`) and replays them immediately to any viewer that connects mid-session, so the HUD is fully populated on join.
- Only one publisher is permitted at a time; a new connection closes the previous one.
- The publisher connection is kept alive with a WebSocket-level ping/pong heartbeat every **5 seconds** (`PUBLISHER_PING_INTERVAL_MS`). If the publisher fails to respond to a ping before the next interval fires, the server calls `ws.terminate()` to forcibly close the dead connection and clean up state.
- H.264 binary frames are forwarded only when the viewer's `bufferedAmount` is below 256 KB, and non-keyframe frames are dropped for lagging viewers, providing natural backpressure to prevent buffer bloat.
- Viewer-initiated messages (`toggle_camera`, `toggle_torch`, `gamepad`) are forwarded upstream to the publisher only when the publisher's `bufferedAmount` is zero.
- `play_honk` and `stop_honk` messages from any viewer are forwarded directly to the publisher.
- The publisher is notified of viewer count changes and individual viewer connect/disconnect events.

### Android App UI
- Minimal always-on dark UI built with Jetpack Compose.
- Displays WSS connection status, camera streaming state, USB connection state, and live steer/throttle bar graphs (mirroring what the viewer sees).
- Server IP/hostname is entered via a dialog on first launch and persisted in `SharedPreferences`. Tapping the status line re-opens the dialog to change server.
- Screen is kept on via `FLAG_KEEP_SCREEN_ON`.
- A **QUIT** button cleanly stops the foreground service, closes the WebSocket, centres servos, and exits.
- Runs as a `LifecycleService` foreground service so it survives the activity being backgrounded, with a persistent notification.
- UI updates are throttle-limited to ~30 fps to avoid unnecessary recompositions; critical state changes (WSS connect/disconnect, streaming toggle) bypass the throttle and update immediately.

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
    ├── UsbCdcSerial.kt
    └── app/src/main/res/raw/
        ├── alarm.wav   # Alarm sound played by the remote alarm feature
        └── honk.wav    # Horn sound played by the remote honk feature
```

---

## Requirements

### Relay Server
- Node.js 18+
- `npm install ws` (only dependency)
- OpenSSL available on `$PATH` (for certificate generation on first run)

### Browser Viewer
- Any modern browser with WebSocket, WebCodecs (`VideoDecoder`), and Web Gamepad API support (Chrome/Edge recommended)
- A gamepad or controller connected via USB or Bluetooth

### Android App
- Android 9 / API 28 or newer
- Built from source — **no pre-built APK is distributed** (see below)

### Arduino / RC Hardware
- Arduino Uno or Nano (or any AVR board with `Servo.h` support)
- Standard RC steering servo on **pin 10**
- ESC on **pin 9** (standard 50 Hz PWM, 1000–2000 µs range)
- Resistor divider on **A0**: 4.64 kΩ (high side) + 1.17 kΩ (low side) for battery voltage sensing
- USB cable from Arduino to Android phone (OTG adapter may be required)

---

## Hardware Wiring (Arduino)

This section explains how to connect your servo and ESC to the Arduino if you haven't done it before.

### Power supply (important — read this first)

The Arduino is powered by **two sources simultaneously**, and both must be connected for a stable link:

- **USB from the Android phone** — the phone connects to the Arduino's USB port (via an OTG adapter if needed). This powers the Arduino and carries the serial data link.
- **ESC BEC via VIN** — the servo and ESC power wires (red V+ and black GND) connect to the Arduino's **VIN** and **GND** pins. The ESC's BEC powers the servo and the Arduino through this rail.

### What you need to know about servo connectors

Both a standard RC servo and an ESC's control input use the same 3-wire connector:

| Wire colour (typical) | Signal | Description                                  |
|-----------------------|--------|----------------------------------------------|
| Brown or Black        | GND    | Ground — must be shared with the Arduino     |
| Red                   | V+     | Power — connect to Arduino **VIN** (not 5 V) |
| Orange or White       | Signal | PWM control signal from the Arduino          |

### Steering servo (pin 10)

1. Connect the **signal (orange/white) wire to Arduino digital pin 10**.
2. Connect the **ground (brown/black) wire to any Arduino GND pin**.
3. Connect the **red (V+) wire to Arduino VIN**. The servo is powered from the ESC's BEC through this rail.

### ESC (pin 9)

The ESC has two connectors: thick wires going to the motor and battery, and a thin 3-pin servo-style control lead. Connect both:

1. Connect the **signal (orange/white) wire to Arduino digital pin 9**.
2. Connect the **ground (brown/black) wire to any Arduino GND pin**.
3. Connect the **red (V+) wire to Arduino VIN**. This allows the ESC's BEC to power the Arduino alongside the USB supply.

### Battery voltage divider (pin A0)

To monitor the car's main pack voltage, wire a simple resistor divider between the battery positive terminal and ground:

```
Battery+ ──┤ 4.64 kΩ ├──┬──┤ 1.17 kΩ ├── GND
                         │
                      Arduino A0
```

- The junction between the two resistors connects to **Arduino analog pin A0**.
- The bottom resistor goes to **Arduino GND**. Ground is shared with the battery negative terminal via the ESC's ground wire, which is already connected to Arduino GND as described above — no separate wire from the battery negative to the Arduino is needed.
- This scales a nominal 3S LiPo pack (≈12.6 V max) down to under 5 V, safe for the Arduino's ADC.
- If you need a different voltage range, keep the ratio `(R_high + R_low) / R_low` correct and adjust `BAT_CALIBRATION` in the sketch if readings are slightly off.

### Summary

```
Arduino pin 10  ──── Servo signal wire
Arduino pin 9   ──── ESC control signal wire
Arduino VIN     ──── Servo V+  +  ESC control V+  (ESC BEC powers servo and Arduino)
Arduino GND     ──── Servo GND  +  ESC control GND (battery negative shared via ESC)  +  divider bottom
Arduino A0      ──── Junction of 4.64 kΩ / 1.17 kΩ divider
Arduino USB     ──── Android phone (via OTG adapter if needed)
```

Both the USB connection to the phone and the ESC BEC via VIN must be active simultaneously for a stable serial link.

---

## Setup

### 1. Relay Server

```bash
npm install ws
node app.js
```

On first run, a self-signed TLS certificate is generated in `ssl/`. Copy `ssl/cert.cer` into the Android project at `app/src/main/res/raw/cert.cer` before building the app.

The server prints the ports it is listening on. By default:

| Port  | Purpose                        |
|-------|--------------------------------|
| 47291 | Android publisher (HTTPS/WSS)  |
| 3000  | Browser viewer (HTTPS/WSS)     |

### 2. Arduino Firmware

Open `firmware/firmware.ino` in the Arduino IDE (1.8+ or 2.x), select your board and port, and upload. The `Servo.h` library is included with the Arduino IDE — no additional libraries are needed.

Adjust the constants at the top of the sketch to match your hardware:

| Constant            | Default | Description                            |
|---------------------|---------|----------------------------------------|
| `STEER_CENTER_US`   | 1500    | Steering servo centre in microseconds  |
| `ESC_FORWARD_LIMIT` | 0.25    | Max forward throttle fraction          |
| `ESC_BRAKE_LIMIT`   | 0.55    | Max brake/reverse throttle fraction    |
| `STEERING_EXPO`     | 0.80    | Steering expo (0 = linear, 1 = cubic)  |
| `THROTTLE_EXPO`     | 0.00    | Throttle expo (0 = linear, 1 = cubic); set to 0 to disable  |
| `BAT_CALIBRATION`   | 0.994   | Voltage calibration multiplier         |

`STEER_CENTER_US` is the most likely constant you'll need to adjust. If your car pulls left or right at neutral stick, change this value (increasing it steers left, decreasing it steers right) until the wheels point straight ahead.

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

Open `https://<server-ip>:3000` in a browser (accept the self-signed certificate warning). Connect your gamepad before opening the page, or connect it at any time — it is detected automatically. The HUD and map populate as soon as the Android app connects and starts sending telemetry.

---

## Protocol Reference

### USB Serial (Android → Arduino)

| Byte 0 | Byte 1     | Byte 2        | Byte 3                         |
|--------|------------|---------------|--------------------------------|
| `'D'`  | Steer byte | Throttle byte | `steer XOR throttle XOR 0xFF`  |

- Steer and throttle are mapped from ±1.0 float to 0–255 unsigned byte (127 = centre/neutral).
- `'C'` (single byte) centres both outputs immediately.

### USB Serial (Arduino → Android)

| Byte 0 | Byte 1      | Byte 2      |
|--------|-------------|-------------|
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
| `toggle_alarm`       | Viewer → Server        | *(no extra fields)*; server toggles alarm state and sends `play_alarm`/`stop_alarm` to Android |
| `play_alarm`         | Server → Android       | *(no extra fields)*                         |
| `stop_alarm`         | Server → Android       | *(no extra fields)*                         |
| `alarm_state`        | Server → Viewers       | `active` (bool)                             |
| `play_honk`          | Viewer → Server → Android | *(no extra fields)*                      |
| `stop_honk`          | Viewer → Server → Android | *(no extra fields)*                      |
| `ping`               | Viewer → Server → Android | `ts` (timestamp ms), `_vid` (viewer ID) |
| `pong`               | Android → Server → Viewer | `ts` (echoed), `_vid` (echoed)          |
| `publisher_status`   | Server → Viewers       | `connected` (bool)                          |
| `publisher_disconnected` | Server → Viewers  | *(no extra fields)*                         |
| `viewer_count`       | Server → Android & Viewers | `count` (integer)                      |
| `viewer_connected`   | Server → Android       | *(no extra fields)*                         |

### WebSocket Binary Frames (Android → Viewers)

| Byte 0  | Remaining bytes  |
|---------|------------------|
| `0x01`  | Raw PCM audio chunk (16 kHz, 16-bit, mono) |
| `0x02`  | H.264 NAL unit(s); IDR frames are preceded by the SPS/PPS config |

---

## Networking Notes

- Both WebSocket servers (publisher port 47291 and viewer port 3000) use the same self-signed TLS certificate, so both use `wss://` / `https://`. Accept the browser certificate warning on first visit to the viewer.
- The publisher WebSocket (port 47291) is pinned in the Android app, so only your phone can connect as the driver. This port needs to be reachable from the internet (port-forward it on your router) — the phone connects out over mobile data, giving the car effectively unlimited range as long as there's cell coverage.
- The viewer port (3000) is intended for LAN use; the browser connects locally to the relay server while the car itself can be anywhere.
- There's no login on the viewer by default — anyone on your LAN who knows the port can watch and grab the controls. For a private session, a simple nginx `auth_basic` block is enough.

---

## License

MIT
