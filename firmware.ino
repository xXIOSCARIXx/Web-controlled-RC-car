#include <Servo.h>

const int STEER_PIN = 2;
const int ESC_PIN = 3;
const int BAT_PIN = A0;

const int STEER_CENTER_US = 1500;
const int ESC_NEUTRAL_US = 1500;

const unsigned long COMMAND_TIMEOUT = 500;
const unsigned long ARM_HOLD_MS = 2000;
const unsigned long BAT_INTERVAL = 500;

const float STEERING_EXPO = 0.80f;
const float THROTTLE_EXPO = 0.0f;
const float ESC_FORWARD_LIMIT = 0.25f;
const float ESC_BRAKE_LIMIT = 0.55f;
const float BAT_CALIBRATION = 0.9517f;

Servo steerServo;
Servo escServo;

int steerTargetUs = STEER_CENTER_US;
int escTargetUs = ESC_NEUTRAL_US;

bool linkAlive = false;
bool escArmed = false;

unsigned long armHoldStart = 0;
unsigned long lastCommandTime = 0;
unsigned long lastBatSend = 0;

float applyExpo(float x, float expo) {
  return (1.0f - expo) * x + expo * x * x * x;
}

int applyExpoToPWM(byte input, bool reverse, float expo, float forwardLimit, float brakeLimit) {
  float x = ((float)input - 127.5f) / 127.5f;

  x = applyExpo(x, expo);

  if (reverse) {
    x = -x;
  }

  if (x > 0) {
    x *= forwardLimit;
  } else {
    x *= brakeLimit;
  }

  int pwm = (int)(1500.0f + x * 500.0f);

  return constrain(pwm, 1000, 2000);
}

uint16_t readBatteryMillivolts() {
  int raw = analogRead(BAT_PIN);
  float vOut = (raw / 1023.0f) * 5.0f;
  float vBat = vOut * (4.64f + 1.17f) / 1.17f * BAT_CALIBRATION;
  return (uint16_t)(vBat * 1000.0f);
}

void setup() {
  Serial.begin(115200);

  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, LOW);

  steerServo.attach(STEER_PIN, 1000, 2000);
  escServo.attach(ESC_PIN, 1000, 2000);

  steerServo.writeMicroseconds(STEER_CENTER_US);
  escServo.writeMicroseconds(ESC_NEUTRAL_US);

  armHoldStart = millis();
}

void loop() {
  readCommands();

  linkAlive = lastCommandTime != 0 &&
              millis() - lastCommandTime < COMMAND_TIMEOUT;

  if (!linkAlive) {
    steerTargetUs = STEER_CENTER_US;
    escTargetUs = ESC_NEUTRAL_US;
    escArmed = false;
    armHoldStart = millis();
  }

  digitalWrite(LED_BUILTIN, linkAlive ? HIGH : LOW);

  updateArming();

  steerServo.writeMicroseconds(steerTargetUs);
  escServo.writeMicroseconds(escTargetUs);

  unsigned long now = millis();
  if (now - lastBatSend >= BAT_INTERVAL) {
    lastBatSend = now;
    uint16_t bat_mv = readBatteryMillivolts();
    Serial.write('B');
    Serial.write((byte)(bat_mv >> 8));
    Serial.write((byte)(bat_mv & 0xFF));
  }
}

void updateArming() {
  if (escArmed) return;

  escTargetUs = ESC_NEUTRAL_US;

  if (millis() - armHoldStart >= ARM_HOLD_MS) {
    escArmed = true;
  }
}

void readCommands() {
  while (Serial.available() > 0) {
    byte cmd = Serial.peek();

    if (cmd == 'D') {
      if (Serial.available() < 4) break;

      Serial.read();

      byte steerByte = Serial.read();
      byte throttleByte = Serial.read();
      byte checksum = Serial.read();

      if ((byte)(steerByte ^ throttleByte ^ 0xFF) != checksum) {
        continue;
      }

      lastCommandTime = millis();

      steerTargetUs = applyExpoToPWM(
        steerByte,
        true,
        STEERING_EXPO,
        1.0f,
        1.0f
      );

      if (escArmed) {
        escTargetUs = applyExpoToPWM(
          throttleByte,
          false,
          THROTTLE_EXPO,
          ESC_FORWARD_LIMIT,
          ESC_BRAKE_LIMIT
        );
      } else {
        escTargetUs = ESC_NEUTRAL_US;
      }
    }
    else if (cmd == 'C') {
      Serial.read();

      lastCommandTime = millis();
      steerTargetUs = STEER_CENTER_US;
      escTargetUs = ESC_NEUTRAL_US;
    }
    else {
      Serial.read();
    }
  }
}