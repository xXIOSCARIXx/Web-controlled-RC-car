@file:Suppress("DEPRECATION")
package shadowscoutx

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.hardware.usb.UsbManager
import android.location.Location
import android.net.wifi.WifiManager
import android.os.*
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.hoho.android.usbserial.driver.UsbSerialProber
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.min
import kotlin.math.roundToInt

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class MainService : LifecycleService() {

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    var wsStatus = "WSS: Connecting..."
        private set
    var usbStatus = "USB: Disconnected"
        private set
    var steerDisplay = 0f
        private set
    var throttleDisplay = 0f
        private set
    var isStreaming = false
        private set

    @Volatile
    private var wasWsConnected = false
    private val appStartTime = SystemClock.uptimeMillis()
    private val statusDelayHandler = Handler(Looper.getMainLooper())
    private var onStatusChanged: (() -> Unit)? = null

    @Volatile
    private var webSocket: WebSocket? = null
    private val client by lazy { buildTrustedClient() }
    private var serverIp = ""
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val usbReconnectHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CameraFrameThread").apply { priority = Thread.MAX_PRIORITY }
    }
    private var useFrontCamera = false
    private var torchEnabled = false
    private var lastFrameSentAt = 0L
    private var isInCall = false
    @Volatile
    private var isShuttingDown = false

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile
    private var isAudioRunning = false

    private var wifiLevel = 0
    private var cellLevel = 0
    private val signalHandler = Handler(Looper.getMainLooper())
    private val signalRunnable = object : Runnable {
        override fun run() {
            updateSignalInfo()
            signalHandler.postDelayed(this, 5000)
        }
    }

    @Suppress("DEPRECATION")
    private fun updateSignalInfo() {
        val wm = getSystemService(WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        if (info.networkId != -1) {
            wifiLevel = WifiManager.calculateSignalLevel(info.rssi, 5)
            webSocket?.send("""{"type":"wifi","level":$wifiLevel}""")
        } else {
            wifiLevel = 0
        }
    }

    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java", ReplaceWith("onSignalStrengthsChanged(signalStrength)"))
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            super.onSignalStrengthsChanged(signalStrength)
            val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                signalStrength.level
            } else {
                try {
                    val getLevel = SignalStrength::class.java.getMethod("getLevel")
                    getLevel.invoke(signalStrength) as Int
                } catch (_: Exception) {
                    0
                }
            }
            cellLevel = level
            webSocket?.send("""{"type":"cell","level":$cellLevel}""")
        }

        @Deprecated("Deprecated in Java", ReplaceWith("onCallStateChanged(state, phoneNumber)"))
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            isInCall = (state != TelephonyManager.CALL_STATE_IDLE)
            if (isInCall) {
                stopStreamingInternal() 
            }
        }
    }

    private var yuvBuffer: ByteArray? = null
    private val jpegOutputStream = ByteArrayOutputStream()

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            val ws = webSocket ?: return
            val payload = """{"type":"gps","lat":${loc.latitude},"lng":${loc.longitude},"acc":${loc.accuracy}}"""
            ws.send(payload)
        }
    }

    @Volatile
    private var serial: UsbCdcSerial? = null
    private val usbLock = Any()
    private val usbReadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "UsbReadThread").apply { priority = Thread.MAX_PRIORITY }
    }
    private val usbWriteExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "UsbWriteThread").apply { priority = Thread.MAX_PRIORITY }
    }
    private var lastSteerValue = -1
    private var lastThrottleValue = -1
    private var lastDriveSentAt = 0L
    private val pendingDrive = AtomicReference<Triple<Float, Float, Float>?>()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if ((level != -1) && (scale != -1)) {
                val pct = (level * 100f / scale).roundToInt()
                webSocket?.send("""{"type":"battery","level":$pct}""")
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) connectUsb() else updateUsbStatus("USB: Permission denied")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectUsb()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if ((device == null) || (device.deviceName == (serial?.deviceName ?: "")))
                        closeSerial("USB: Disconnected")
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): MainService = this@MainService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val initialStatus = "$wsStatus | CAM: Standby"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(initialStatus),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification(initialStatus))
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Scout::WakeLock").apply {
            acquire(10 * 60 * 1000L)
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("DEPRECATION")
        tm.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_CALL_STATE)
        signalHandler.post(signalRunnable)

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newIp = intent?.getStringExtra("server_ip") ?: ""
        if (newIp.isNotEmpty() && (newIp != serverIp)) {
            serverIp = newIp
            startStreaming()
        }
        connectUsb()
        startLocationUpdates()
        return super.onStartCommand(intent, flags, startId)
    }

    fun setStatusListener(listener: (() -> Unit)?) {
        onStatusChanged = listener
    }

    private fun updateWsStatus(status: String) {
        if (wsStatus == status) return
        wsStatus = status
        onStatusChanged?.invoke()
        updateNotification()
    }

    private fun updateUsbStatus(status: String) {
        if (usbStatus == status) return
        usbStatus = status
        onStatusChanged?.invoke()
        updateNotification()
        webSocket?.send("""{"type":"usb_status","status":"$usbStatus"}""")
    }

    private fun updateNotification() {
        val camStatus = if (isStreaming) "CAM: Streaming" else "CAM: Standby"
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification("$wsStatus | $camStatus"))
    }

    private fun buildTrustedClient(): OkHttpClient {
        val certInputStream = resources.openRawResource(R.raw.cert)
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(certInputStream)
        certInputStream.close()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("server", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, tmf.trustManagers, null)
        }
        val tm = tmf.trustManagers[0] as X509TrustManager
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun startStreaming() {
        reconnectHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, null)
        webSocket = null
        
        if (wsStatus == "WSS: Connected") {
            updateWsStatus("WSS: Connecting...")
        }
        
        val host = serverIp.substringBefore(':')
        val url = "wss://$host:47291?role=publisher"
        val request = Request.Builder().url(url).build()
        val newSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    wasWsConnected = true
                    statusDelayHandler.removeCallbacksAndMessages(null)
                    Handler(Looper.getMainLooper()).post {
                        updateWsStatus("WSS: Connected")
                        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                        val batteryStatus = registerReceiver(null, filter)
                        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                        if ((level != -1) && (scale != -1)) {
                            val pct = (level * 100f / scale).roundToInt()
                            webSocket.send("""{"type":"battery","level":$pct}""")
                        }
                        updateSignalInfo()
                        webSocket.send("""{"type":"usb_status","status":"$usbStatus"}""")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = try { JSONObject(text) } catch (_: Exception) { return }
                    when (json.optString("type")) {
                        "viewer_count" -> {
                            val hasViewers = json.optInt("count") > 0
                            Handler(Looper.getMainLooper()).post {
                                if (hasViewers && !isStreaming) {
                                    isStreaming = true
                                    bindCamera()
                                    startAudio()
                                } else if (!hasViewers && isStreaming) {
                                    isStreaming = false
                                    stopAudio()
                                    cameraProvider?.unbindAll()
                                    activeCamera = null
                                }
                                onStatusChanged?.invoke()
                                updateNotification()
                            }
                            return
                        }
                        "toggle_camera" -> {
                            useFrontCamera = !useFrontCamera
                            Handler(Looper.getMainLooper()).post { bindCamera() }
                            return
                        }
                        "toggle_torch" -> {
                            torchEnabled = !torchEnabled
                            activeCamera?.cameraControl?.enableTorch(torchEnabled)
                            return
                        }
                    }
                    if ((json.optString("type") != "gamepad") || !json.optBoolean("connected")) return
                    val axes = json.optJSONArray("axes")
                    val buttons = json.optJSONArray("buttons")
                    val steerAxis = axes?.optDouble(0)?.toFloat() ?: 0f
                    val l2 = buttons?.optDouble(6)?.toFloat() ?: 0f
                    val r2 = buttons?.optDouble(7)?.toFloat() ?: 0f

                    steerDisplay = steerAxis
                    throttleDisplay = (r2 - l2).coerceIn(-1f, 1f)
                    onStatusChanged?.invoke()

                    pendingDrive.set(Triple(steerAxis, l2, r2))
                    usbWriteExecutor.execute {
                        val args = pendingDrive.getAndSet(null) ?: return@execute
                        sendDrive(args.first, args.second, args.third)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (isShuttingDown || this@MainService.webSocket !== webSocket) return
                    Handler(Looper.getMainLooper()).post {
                        stopStreamingInternal()
                        updateWsStatus("WSS: Connection lost, retrying...")
                        scheduleReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (isShuttingDown || this@MainService.webSocket !== webSocket) return
                    Handler(Looper.getMainLooper()).post {
                        stopStreamingInternal()
                        val now = SystemClock.uptimeMillis()
                        val elapsed = now - appStartTime
                        if (elapsed < 3000L && !wasWsConnected) {
                            statusDelayHandler.removeCallbacksAndMessages(null)
                            statusDelayHandler.postDelayed({
                                if (this@MainService.webSocket !== webSocket) return@postDelayed
                                updateWsStatus("WSS: Unable to connect, retrying...")
                                scheduleReconnect()
                            }, 3000L - elapsed)
                        } else {
                            val msg = if (wasWsConnected) "WSS: Connection lost, retrying..." else "WSS: Unable to connect, retrying..."
                            updateWsStatus(msg)
                            scheduleReconnect()
                        }
                    }
                }
            },
        )
        webSocket = newSocket
    }

    private fun stopStreamingInternal() {
        if (isStreaming) {
            isStreaming = false
            stopAudio()
            cameraProvider?.unbindAll()
            activeCamera = null
            onStatusChanged?.invoke()
            updateNotification()
        }
    }

    private fun scheduleReconnect() {
        if (isShuttingDown) return
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({ startStreaming() }, 3000L)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                if (!isStreaming || lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
                cameraProvider = try { providerFuture.get() } catch (_: Exception) { return@addListener }
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { imageProxy -> sendFrame(imageProxy) }
                val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider?.unbindAll()
                    val camera = cameraProvider?.bindToLifecycle(this, selector, analysis)
                    activeCamera = camera
                    camera?.let {
                        val focusDistance = if (useFrontCamera) 0.0f else 0.0f
                        Camera2CameraControl.from(it.cameraControl).captureRequestOptions = CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                            .setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                            .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                            .build()
                    }
                } catch (e: Exception) { e.printStackTrace() }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun sendFrame(imageProxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        val ws = webSocket
        
        if (ws == null || isInCall || ((now - lastFrameSentAt) < 33L) || (ws.queueSize() > 150_000)) {
            imageProxy.close()
            return
        }

        val jpegBytes = imageProxyToJpegOptimized(imageProxy)
        imageProxy.close()

        jpegBytes?.let {
            val prefixed = ByteArray(it.size + 1)
            prefixed[0] = 0x00 // Type: Video
            System.arraycopy(it, 0, prefixed, 1, it.size)
            if (ws.send(prefixed.toByteString())) {
                lastFrameSentAt = now
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (isAudioRunning) return
        val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (bufferSize <= 0) return

        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return

        isAudioRunning = true
        audioThread = Thread({
            val audioBuffer = ByteArray(4096)
            val sendBuffer = ByteArray(audioBuffer.size + 1)
            sendBuffer[0] = 0x01 // Type: Audio
            
            try {
                audioRecord?.startRecording()
                while (isAudioRunning && !isShuttingDown) {
                    val read = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (read > 0) {
                        val ws = webSocket
                        if (ws != null && ws.queueSize() < 50_000) {
                            System.arraycopy(audioBuffer, 0, sendBuffer, 1, read)
                            ws.send(sendBuffer.toByteString(0, read + 1))
                        }
                    }
                }
            } catch (_: Exception) {}
        }, "MicStreamThread").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun stopAudio() {
        isAudioRunning = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        audioThread = null
    }

    private var vRowArr = ByteArray(0)
    private var uRowArr = ByteArray(0)

    private fun imageProxyToJpegOptimized(imageProxy: ImageProxy): ByteArray? {
        if (imageProxy.format != ImageFormat.YUV_420_888) return null
        val width = imageProxy.width
        val height = imageProxy.height
        val size = (width * height * 3) / 2
        if (yuvBuffer == null || yuvBuffer?.size != size) { yuvBuffer = ByteArray(size) }
        val nv21 = yuvBuffer!!
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        if (yRowStride == width) { yBuffer.get(nv21, 0, width * height) } 
        else { for (row in 0 until height) { yBuffer.position(row * yRowStride); yBuffer.get(nv21, row * width, width) } }
        val uvOffset = width * height
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uvWidth = width / 2
        val uvHeight = height / 2
        if (vPixelStride == 2 && uPixelStride == 2 && vRowStride == uRowStride) {
            for (row in 0 until uvHeight) {
                vBuffer.position(row * vRowStride)
                vBuffer.get(nv21, uvOffset + row * width, min(width, vBuffer.remaining()))
            }
        } else {
            if (vRowArr.size < vRowStride) vRowArr = ByteArray(vRowStride)
            if (uRowArr.size < uRowStride) uRowArr = ByteArray(uRowStride)
            for (row in 0 until uvHeight) {
                vBuffer.position(row * vRowStride)
                vBuffer.get(vRowArr, 0, min(vRowStride, vBuffer.remaining()))
                uBuffer.position(row * uRowStride)
                uBuffer.get(uRowArr, 0, min(uRowStride, uBuffer.remaining()))
                for (col in 0 until uvWidth) {
                    nv21[uvOffset + row * width + col * 2] = vRowArr[col * vPixelStride]
                    nv21[uvOffset + row * width + col * 2 + 1] = uRowArr[col * uPixelStride]
                }
            }
        }
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        jpegOutputStream.reset()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 75, jpegOutputStream)
        return jpegOutputStream.toByteArray()
    }

    private fun connectUsb() {
        if (isShuttingDown) return
        usbReconnectHandler.removeCallbacksAndMessages(null)
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
        if (driver == null) { updateUsbStatus("USB: No device found"); scheduleUsbReconnect(); return }
        val device = driver.device
        if (serial?.deviceName == device.deviceName) return
        if (manager.hasPermission(device)) { openSerial(device); return }
        updateUsbStatus("USB: Requesting permission")
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
        manager.requestPermission(device, pi)
    }

    private fun openSerial(device: UsbDevice) {
        synchronized(usbLock) {
            closeSerial(null)
            val manager = getSystemService(USB_SERVICE) as UsbManager
            val port = UsbCdcSerial.open(manager, device, BAUD_RATE)
            if (port == null) { updateUsbStatus("USB: Open failed"); return }
            serial = port
        }
        lastSteerValue = -1
        lastThrottleValue = -1
        updateUsbStatus("USB: Connected")
        serial?.let { startAckReader(it) }
    }

    private fun closeSerial(status: String?) {
        synchronized(usbLock) {
            val port = serial
            serial = null
            port?.close()
            status?.let { newStatus ->
                updateUsbStatus(newStatus)
                if (newStatus.contains("failed") || newStatus.contains("Disconnected") || newStatus.contains("found")) { scheduleUsbReconnect() } 
                else { usbReconnectHandler.removeCallbacksAndMessages(null) }
            }
        }
    }

    private fun scheduleUsbReconnect() {
        if (isShuttingDown) return
        usbReconnectHandler.removeCallbacksAndMessages(null)
        usbReconnectHandler.postDelayed({ if (serial == null) connectUsb() }, 2000L)
    }

    private fun startAckReader(port: UsbCdcSerial) {
        usbReadExecutor.execute {
            val buffer = ByteArray(1024)
            var bytesRead = 0
            while (serial === port) {
                val tempBuffer = ByteArray(256)
                val count = try { port.read(tempBuffer, READ_TIMEOUT_MS) } catch (_: Exception) { return@execute }
                if (count > 0) {
                    if (bytesRead + count > buffer.size) { bytesRead = 0 }
                    System.arraycopy(tempBuffer, 0, buffer, bytesRead, count)
                    bytesRead += count
                    var i = 0
                    while (i <= bytesRead - 3) {
                        if (buffer[i] == 'B'.code.toByte()) {
                            val batHigh = buffer[i + 1].toInt() and 0xFF
                            val batLow = buffer[i + 2].toInt() and 0xFF
                            val batMv = (batHigh shl 8) or batLow
                            webSocket?.send("""{"type":"car_battery","mv":$batMv}""")
                            i += 3
                        } else { i++ }
                    }
                    if (i > 0) { System.arraycopy(buffer, i, buffer, 0, bytesRead - i); bytesRead -= i }
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
        if (((steerByte == lastSteerValue) && (throttleByte == lastThrottleValue)) && ((now - lastDriveSentAt) < DRIVE_KEEPALIVE_MS)) return
        val checksum = (steerByte xor throttleByte xor 0xFF).toByte()
        val ok = port.write(byteArrayOf(COMMAND_DRIVE, steerByte.toByte(), throttleByte.toByte(), checksum))
        if (ok) {
            lastSteerValue = steerByte
            lastThrottleValue = throttleByte
            lastDriveSentAt = now
        } else {
            Handler(Looper.getMainLooper()).post { closeSerial("USB: Write failed") }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Scout Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scout Active")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        isShuttingDown = true
        reconnectHandler.removeCallbacksAndMessages(null)
        usbReconnectHandler.removeCallbacksAndMessages(null)
        statusDelayHandler.removeCallbacksAndMessages(null)
        stopForeground(true)
        super.onDestroy()
        wakeLock?.release()
        signalHandler.removeCallbacks(signalRunnable)
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("DEPRECATION")
        tm.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        unregisterReceiver(batteryReceiver)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        cameraProvider?.unbindAll()
        webSocket?.close(1000, null)
        webSocket = null
        serial?.write(byteArrayOf(COMMAND_CENTER))
        closeSerial(null)
        unregisterReceiver(usbReceiver)
        cameraExecutor.shutdownNow()
        usbReadExecutor.shutdownNow()
        usbWriteExecutor.shutdownNow()
    }

    companion object {
        const val CHANNEL_ID = "scout_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_USB_PERMISSION = "shadowscoutx.USB_PERMISSION"
        const val BAUD_RATE = 115200
        const val READ_TIMEOUT_MS = 20
        const val DRIVE_KEEPALIVE_MS = 200L
        const val COMMAND_DRIVE = 'D'.code.toByte()
        const val COMMAND_CENTER = 'C'.code.toByte()
    }
}
