package com.roverlink

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

class UsbCdcSerial private constructor(
    private val port: UsbSerialPort,
    val deviceName: String
) {
    fun write(bytes: ByteArray): Boolean = try { port.write(bytes, WRITE_TIMEOUT_MS); true } catch (_: Exception) { false }
    fun read(buffer: ByteArray, timeoutMs: Int): Int = try { port.read(buffer, timeoutMs) } catch (_: Exception) { 0 }
    fun close() { try { port.close() } catch (_: Exception) { } }

    companion object {
        private const val WRITE_TIMEOUT_MS = 50

        fun open(manager: UsbManager, device: UsbDevice, baudRate: Int): UsbCdcSerial? {
            val driver: UsbSerialDriver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: return null
            val connection = manager.openDevice(driver.device) ?: return null
            val port = driver.ports.firstOrNull() ?: run { connection.close(); return null }
            return try {
                port.open(connection)
                port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                UsbCdcSerial(port, driver.device.deviceName)
            } catch (_: Exception) {
                try { port.close() } catch (_: Exception) { }
                null
            }
        }
    }
}
