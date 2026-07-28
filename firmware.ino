#include <Servo.h>

const int STEER_PIN = 2;
const int ESC_PIN = 3;

const int STEER_CENTER_US = 1500;
const int ESC_NEUTRAL_US = 1500;

const unsigned long COMMAND_TIMEOUT = 500;
const unsigned long ARM_HOLD_MS = 2000;

Servo steerServo;
Servo escServo;

int steerTargetUs = STEER_CENTER_US;
int escTargetUs = ESC_NEUTRAL_US;

bool linkAlive = false;
bool escArmed = false;

unsigned long armHoldStart = 0;
unsigned long lastCommandTime = 0;

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

  linkAlive = lastCommandTime != 0 && millis() - lastCommandTime < COMMAND_TIMEOUT;

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

      steerTargetUs = map(steerByte, 0, 255, 2000, 1000);

      if (escArmed) {
        escTargetUs = map(throttleByte, 0, 255, 1000, 2000);
      } else {
        escTargetUs = ESC_NEUTRAL_US;
      }

      Serial.write('A');
      Serial.write(steerByte);
      Serial.write(throttleByte);
    }
    else if (cmd == 'C') {
      Serial.read();

      lastCommandTime = millis();
      steerTargetUs = STEER_CENTER_US;
      escTargetUs = ESC_NEUTRAL_US;

      Serial.write('A');
      Serial.write((byte)128);
      Serial.write((byte)128);
    }
    else {
      Serial.read();
    }
  }
}