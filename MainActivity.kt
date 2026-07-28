package com.example.myapplication

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder().build()
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var wsStatus by mutableStateOf("WS: not connected")
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private var activityDestroyed = false

    private lateinit var prefs: SharedPreferences
    private var serverIp by mutableStateOf("")
    private var showIpDialog by mutableStateOf(false)

    @Volatile
    private var serial: UsbCdcSerial? = null
    private val usbReadExecutor = Executors.newSingleThreadExecutor()
    private val usbWriteExecutor = Executors.newSingleThreadExecutor()
    private var usbStatus by mutableStateOf("USB: disconnected")
    private var lastSteerValue = -1
    private var lastThrottleValue = -1
    private var lastDriveSentAt = 0L
    private var steerDisplay by mutableStateOf(0f)
    private var throttleDisplay by mutableStateOf(0f)

    private val pendingDrive = AtomicReference<Triple<Float, Float, Float>?>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) checkPermissionAndStart()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) connectUsb() else usbStatus = "USB: permission denied"
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectUsb()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device == null || device.deviceName == serial?.deviceName)
                        closeSerial("USB: disconnected")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        serverIp = prefs.getString("server_ip", "") ?: ""

        if (serverIp.isBlank()) {
            showIpDialog = true
        } else {
            checkPermissionAndStart()
        }
        connectUsb()

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = wsStatus,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF888888),
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .clickable { showIpDialog = true }
                        )
                        Text(
                            text = usbStatus,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF888888),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        BarRow(label = "Steer", value = steerDisplay)
                        BarRow(label = "Throt", value = throttleDisplay)
                    }

                    if (showIpDialog) {
                        IpAddressDialog(
                            initial = serverIp,
                            onConfirm = { ip ->
                                serverIp = ip.trim()
                                prefs.edit().putString("server_ip", serverIp).apply()
                                showIpDialog = false
                                checkPermissionAndStart()
                            },
                            onDismiss = { showIpDialog = false }
                        )
                    }
                }
            }
        }
    }

    private fun checkPermissionAndStart() {
        if (serverIp.isBlank()) { showIpDialog = true; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startStreaming()
        else requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startStreaming() {
        reconnectHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, null)
        webSocket = null
        wsStatus = "WS: connecting"
        val request = Request.Builder()
            .url("ws://$serverIp?role=publisher")
            .build()
        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                runOnUiThread { wsStatus = "WS: connected" }
                bindCamera()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try { JSONObject(text) } catch (e: Exception) { return }
                if (json.optString("type") != "gamepad" || !json.optBoolean("connected")) return
                val axes = json.optJSONArray("axes")
                val buttons = json.optJSONArray("buttons")
                val steerAxis = axes?.optDouble(0)?.toFloat() ?: 0f
                val l2 = buttons?.optDouble(6)?.toFloat() ?: 0f
                val r2 = buttons?.optDouble(7)?.toFloat() ?: 0f
                val throttleAxis = (r2 - l2).coerceIn(-1f, 1f)

                runOnUiThread {
                    steerDisplay = steerAxis
                    throttleDisplay = throttleAxis
                }

                pendingDrive.set(Triple(steerAxis, l2, r2))
                usbWriteExecutor.execute {
                    val args = pendingDrive.getAndSet(null) ?: return@execute
                    sendDrive(args.first, args.second, args.third)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (this@MainActivity.webSocket !== webSocket) return
                runOnUiThread { wsStatus = "WS: disconnected, retrying" }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@MainActivity.webSocket !== webSocket) return
                runOnUiThread { wsStatus = "WS: connection lost, retrying" }
                scheduleReconnect()
            }
        })
        webSocket = newSocket
    }

    private fun scheduleReconnect() {
        if (activityDestroyed) return
        val delayMs = (RECONNECT_BASE_DELAY_MS * (1L shl reconnectAttempt.coerceAtMost(6)))
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectAttempt++
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            if (!activityDestroyed) startStreaming()
        }, delayMs)
    }

    private fun connectUsb() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
        if (driver == null) { usbStatus = "USB: no device found"; return }
        val device = driver.device
        if (manager.hasPermission(device)) { openSerial(device); return }
        usbStatus = "USB: requesting permission"
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE
        )
        manager.requestPermission(device, pi)
    }

    private fun openSerial(device: UsbDevice) {
        closeSerial(null)
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val port = UsbCdcSerial.open(manager, device, BAUD_RATE)
        if (port == null) { usbStatus = "USB: open failed"; return }
        serial = port
        lastSteerValue = -1
        lastThrottleValue = -1
        usbStatus = "USB: ${device.productName ?: device.deviceName}"
        startAckReader(port)
    }

    private fun closeSerial(status: String?) {
        val port = serial ?: run { if (status != null) usbStatus = status; return }
        serial = null
        port.close()
        if (status != null) usbStatus = status
    }

    private fun startAckReader(port: UsbCdcSerial) {
        usbReadExecutor.execute {
            val buffer = ByteArray(64)
            while (serial === port) {
                val count = try { port.read(buffer, READ_TIMEOUT_MS) } catch (e: Exception) { return@execute }
                var i = 0
                while (i + 2 < count) {
                    if (buffer[i] == ACK_BYTE) i += 3 else i++
                }
            }
        }
    }

    private fun sendDrive(steerAxis: Float, l2: Float, r2: Float) {
        val port = serial ?: return
        val throttleAxis = (r2 - l2).coerceIn(-1f, 1f)
        val steerByte = (((steerAxis + 1f) / 2f) * 255f).roundToInt().coerceIn(0, 255)
        val throttleByte = (((throttleAxis + 1f) / 2f) * 255f).roundToInt().coerceIn(0, 255)
        val now = SystemClock.uptimeMillis()

        if (steerByte == lastSteerValue && throttleByte == lastThrottleValue
            && now - lastDriveSentAt < DRIVE_KEEPALIVE_MS) return

        val checksum = (steerByte xor throttleByte xor 0xFF).toByte()
        val ok = port.write(byteArrayOf(COMMAND_DRIVE, steerByte.toByte(), throttleByte.toByte(), checksum))

        if (ok) {
            lastSteerValue = steerByte
            lastThrottleValue = throttleByte
            lastDriveSentAt = now
        } else {
            runOnUiThread { closeSerial("USB: write failed") }
        }
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy -> sendFrame(imageProxy) }
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendFrame(imageProxy: ImageProxy) {
        val jpegBytes = imageProxyToJpeg(imageProxy)
        imageProxy.close()
        val ws = webSocket ?: return
        if (jpegBytes != null && ws.queueSize() < 200_000)
            ws.send(jpegBytes.toByteString())
    }

    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
        if (imageProxy.format != ImageFormat.YUV_420_888) return null
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 70, out)
        return out.toByteArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityDestroyed = true
        reconnectHandler.removeCallbacksAndMessages(null)
        cameraProvider?.unbindAll()
        webSocket?.close(1000, null)
        serial?.write(byteArrayOf(COMMAND_CENTER))
        closeSerial(null)
        unregisterReceiver(usbReceiver)
        cameraExecutor.shutdown()
        usbReadExecutor.shutdownNow()
        usbWriteExecutor.shutdownNow()
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.example.myapplication.USB_PERMISSION"
        const val BAUD_RATE = 115200
        const val READ_TIMEOUT_MS = 20
        const val DRIVE_KEEPALIVE_MS = 200L
        const val RECONNECT_BASE_DELAY_MS = 1000L
        const val RECONNECT_MAX_DELAY_MS = 15_000L
        const val COMMAND_DRIVE = 'D'.code.toByte()
        const val COMMAND_CENTER = 'C'.code.toByte()
        const val ACK_BYTE = 'A'.code.toByte()
    }
}

class UsbCdcSerial private constructor(
    private val port: UsbSerialPort,
    val deviceName: String
) {
    fun write(bytes: ByteArray): Boolean = try { port.write(bytes, WRITE_TIMEOUT_MS); true } catch (e: Exception) { false }
    fun read(buffer: ByteArray, timeoutMs: Int): Int = try { port.read(buffer, timeoutMs) } catch (e: Exception) { 0 }
    fun close() { try { port.close() } catch (e: Exception) { } }

    companion object {
        private const val WRITE_TIMEOUT_MS = 10

        fun open(manager: UsbManager, device: UsbDevice, baudRate: Int): UsbCdcSerial? {
            val driver: UsbSerialDriver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: return null
            val connection = manager.openDevice(driver.device) ?: return null
            val port = driver.ports.firstOrNull() ?: run { connection.close(); return null }
            return try {
                port.open(connection)
                port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                UsbCdcSerial(port, driver.device.deviceName)
            } catch (e: Exception) {
                try { port.close() } catch (_: Exception) { }
                null
            }
        }
    }
}

@Composable
fun IpAddressDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server address", fontFamily = FontFamily.Monospace) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("host:port", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            )
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onConfirm(text) }) {
                Text("Connect")
            }
        },
        dismissButton = {
            if (initial.isNotBlank()) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun BarRow(label: String, value: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 3.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFF888888),
            modifier = Modifier.width(40.dp)
        )
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(6.dp)
                .background(Color(0xFF333333))
        ) {
            if (value != 0f) {
                val fillWidth = (abs(value) * 70).dp
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(fillWidth)
                        .align(Alignment.CenterStart)
                        .offset(x = if (value >= 0f) 70.dp else 70.dp - fillWidth)
                        .background(Color(0xFF66CCFF))
                )
            }
        }
        Text(
            text = " % .2f".format(value),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFF888888)
        )
    }
}