@file:androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
package com.example.myapplication

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var serverIp by mutableStateOf("")
    private var showIpDialog by mutableStateOf(value = false)

    private var mainService: MainService? = null
    private var isBound by mutableStateOf(false)

    private var wsStatus by mutableStateOf("WSS: Connecting...")
    private var usbStatus by mutableStateOf("USB: Disconnected")
    private var steerDisplay by mutableFloatStateOf(0f)
    private var throttleDisplay by mutableFloatStateOf(0f)
    private var isStreaming by mutableStateOf(false)
    private var lastUiUpdateAt = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MainService.LocalBinder
            val s = binder.getService()
            mainService = s
            isBound = true

            wsStatus = s.wsStatus
            usbStatus = s.usbStatus
            steerDisplay = s.steerDisplay
            throttleDisplay = s.throttleDisplay
            isStreaming = s.isStreaming

            s.setStatusListener {
                val now = SystemClock.uptimeMillis()
                val criticalStateChanged = (wsStatus != s.wsStatus) || (isStreaming != s.isStreaming)
                val throttleExpired = (now - lastUiUpdateAt > 33L)
                
                if (criticalStateChanged || throttleExpired) {
                    runOnUiThread {
                        wsStatus = s.wsStatus
                        usbStatus = s.usbStatus
                        steerDisplay = s.steerDisplay
                        throttleDisplay = s.throttleDisplay
                        isStreaming = s.isStreaming
                        lastUiUpdateAt = now
                    }
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            mainService = null
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startAndBindService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        serverIp = prefs.getString("server_ip", "") ?: ""

        if (serverIp.isBlank()) {
            showIpDialog = true
        } else {
            checkPermissions()
        }

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = wsStatus,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (wsStatus.contains("Connected")) Color(0xFF66CCFF) else Color(0xFF888888),
                                modifier = Modifier
                                    .padding(bottom = 6.dp)
                                    .clickable { showIpDialog = true }
                            )
                            Text(
                                text = if (isStreaming) "CAM: Streaming" else "CAM: Standby",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (isStreaming) Color(0xFF66CCFF) else Color(0xFF888888),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            Text(
                                text = usbStatus,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (usbStatus.startsWith("USB: ") &&
                                    !usbStatus.contains("Disconnected") &&
                                    !usbStatus.contains("No device found") &&
                                    !usbStatus.contains("Open failed") &&
                                    !usbStatus.contains("Permission denied") &&
                                    !usbStatus.contains("Requesting permission")
                                ) Color(0xFF66CCFF) else Color(0xFF888888),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            BarRow(label = "Steer", value = steerDisplay)
                            BarRow(label = "Throt", value = throttleDisplay)
                        }

                        Button(
                            onClick = { quitApp() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF442222)),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                        ) {
                            Text("QUIT", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }

                        if (showIpDialog) {
                            IpAddressDialog(
                                initial = serverIp,
                                onConfirm = { ip ->
                                    serverIp = ip.trim()
                                    prefs.edit { putString("server_ip", serverIp) }
                                    showIpDialog = false
                                    checkPermissions()
                                },
                                onDismiss = { showIpDialog = false }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startAndBindService()
        } else {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, MainService::class.java).apply {
            putExtra("server_ip", serverIp)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, BIND_AUTO_CREATE)
    }

    private fun quitApp() {
        if (isBound) {
            mainService?.setStatusListener(null)
            unbindService(connection)
            isBound = false
        }
        val intent = Intent(this, MainService::class.java)
        stopService(intent)
        finishAffinity()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            mainService?.setStatusListener(null)
            unbindService(connection)
            isBound = false
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
                label = { Text("host / IP", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
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
