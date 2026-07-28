# ShadowScoutX

A low-latency FPV rover platform that streams live video from an Android
phone while receiving remote gamepad commands through a WebSocket
server. The Android device acts as both the camera and the control
bridge, forwarding controller input to a microcontroller over USB
serial, which then drives the steering servo and ESC.

------------------------------------------------------------------------

## Features

-   Live FPV video streamed from an Android phone
-   Browser-based gamepad control
-   Low-latency WebSocket communication
-   USB Serial communication between Android and microcontroller
-   Standard RC servo steering
-   Brushless ESC control
-   Built-in failsafe system
-   ESC arming protection
-   Command acknowledgement and checksum validation

------------------------------------------------------------------------

## System Overview

``` text
Gamepad
    │
Browser UI (HTML)
    │
WebSocket
    │
Android App
 ├── CameraX
 ├── WebSocket Client
 └── USB Serial
    │
Microcontroller
 ├── Steering Servo
 └── ESC
    │
RC Vehicle
```

## Components

### Android App

-   Streams camera video
-   Receives browser gamepad input
-   Sends commands to the microcontroller over USB Serial
-   Displays steering and throttle telemetry

### Browser UI

-   Live FPV video
-   Gamepad support
-   Fullscreen mode
-   Steering/throttle HUD

### Firmware

-   115200 baud serial
-   Servo + ESC control
-   500 ms communication timeout
-   2 second ESC arming delay
-   Checksum validation
-   Automatic failsafe

## Serial Protocol

Drive Packet

``` text
'D' | Steering | Throttle | Checksum
```

Checksum:

``` text
steering XOR throttle XOR 0xFF
```

Center Command:

``` text
'C'
```

## Safety

If communication is lost: - Steering returns to center - ESC returns to
neutral - ESC disarms - Vehicle must re-arm after reconnecting

## Hardware

-   Arduino-compatible microcontroller
-   Steering servo
-   Brushless ESC
-   Android phone with USB OTG
-   RC chassis
-   WebSocket server

## Future Improvements

-   Telemetry
-   H.264 streaming
-   Authentication
-   Autonomous driving
-   On-screen overlays

## License

MIT License

## Disclaimer

This project controls a real vehicle. Test with the wheels off the
ground first and always verify failsafe behavior before driving.
