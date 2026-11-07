package com.gostream.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var ssid: EditText
    private lateinit var wifiPassword: EditText
    private lateinit var rtmpUrl: EditText
    private lateinit var streamKey: EditText
    private lateinit var cm: ConnectivityManager
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var cameraNetwork: Network? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra(StreamService.EXTRA_STATUS) ?: "Working…"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        buildUi()
        if (Build.VERSION.SDK_INT >= 33) {
            val missing = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.POST_NOTIFICATIONS)
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 44)
        }
        val filter = IntentFilter(StreamService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter)
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 26, 22, 22)
            setBackgroundColor(0xFF101820.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun heading(s: String, size: Float = 18f) {
            content.addView(TextView(this).apply {
                text = s; textSize = size; setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 14, 0, 8)
            })
        }
        fun field(hintText: String, input: Int = InputType.TYPE_CLASS_TEXT): EditText {
            return EditText(this).apply {
                hint = hintText; inputType = input
                setSingleLine(true)
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFFB8C3CC.toInt())
                setPadding(14, 10, 14, 10)
                setBackgroundColor(0xFF263746.toInt())
                content.addView(this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 })
            }
        }
        heading("GoStream", 30f)
        heading("GoPro → custom RTMP livestream", 15f)
        heading("1. GoPro Wi-Fi")
        ssid = field("GoPro Wi-Fi name (SSID)").apply { setText("GoPro") }
        wifiPassword = field("GoPro Wi-Fi password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        heading("2. Streaming destination")
        rtmpUrl = field("RTMP server URL (e.g. rtmp://server/app)")
        streamKey = field("Stream key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        heading("3. Stream")
        status = TextView(this).apply {
            text = "Status: Not connected"
            textSize = 14f
            setTextColor(0xFFB9D7EA.toInt())
            setPadding(0, 8, 0, 14)
        }
        content.addView(status)
        val connect = Button(this).apply {
            text = "CONNECT TO GOPRO WI-FI"
            setOnClickListener { connectToCameraWifi() }
        }
        content.addView(connect)
        val start = Button(this).apply {
            text = "START RTMP STREAM"
            setOnClickListener { startStream() }
        }
        content.addView(start)
        val stop = Button(this).apply {
            text = "STOP STREAM"
            setOnClickListener { stopStream() }
        }
        content.addView(stop)
        heading("Notes", 17f)
        content.addView(TextView(this).apply {
            text = "For HERO4 Silver: turn the camera on, enable its Wi-Fi/app connection, then enter the exact Wi-Fi name and password shown by the camera. The phone needs internet access for RTMP; Android may use mobile data while the GoPro Wi-Fi has no internet."
            textSize = 13f
            setTextColor(0xFFD5DEE5.toInt())
        })
        scroll.addView(content)
        outer.addView(scroll)
        setContentView(outer)
    }

    private fun connectToCameraWifi() {
        if (Build.VERSION.SDK_INT < 29) {
            status.text = "Android 10 or newer is required for this Wi-Fi connection flow."
            return
        }
        val name = ssid.text.toString().trim()
        val pass = wifiPassword.text.toString()
        if (name.isBlank()) {
            Toast.makeText(this, "Enter the GoPro Wi-Fi name first.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            wifiCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            val specBuilder = WifiNetworkSpecifier.Builder().setSsid(name)
            if (pass.isNotEmpty()) specBuilder.setWpa2Passphrase(pass)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specBuilder.build())
                .build()
            status.text = "Waiting for Android to connect to $name…"
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cameraNetwork = network
                    runOnUiThread { status.text = "GoPro Wi-Fi connected. Enter RTMP details and start." }
                }
                override fun onUnavailable() {
                    runOnUiThread { status.text = "Could not connect. Check the Wi-Fi name/password and camera Wi-Fi." }
                }
                override fun onLost(network: Network) {
                    if (cameraNetwork == network) cameraNetwork = null
                    runOnUiThread { status.text = "GoPro Wi-Fi disconnected." }
                }
            }
            wifiCallback = callback
            cm.requestNetwork(request, callback)
        } catch (e: Exception) {
            status.text = "Wi-Fi connection error: ${e.message}"
        }
    }

    private fun startStream() {
        val network = cameraNetwork
        if (network == null) {
            Toast.makeText(this, "Connect to the GoPro Wi-Fi first.", Toast.LENGTH_LONG).show()
            return
        }
        val base = rtmpUrl.text.toString().trim().trimEnd('/')
        val key = streamKey.text.toString().trim()
        if (!base.startsWith("rtmp://") && !base.startsWith("rtmps://")) {
            Toast.makeText(this, "Enter a valid RTMP or RTMPS server URL.", Toast.LENGTH_LONG).show()
            return
        }
        if (key.isBlank()) {
            Toast.makeText(this, "Enter your stream key.", Toast.LENGTH_LONG).show()
            return
        }
        val target = "$base/$key"
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_NETWORK, network)
            putExtra(StreamService.EXTRA_TARGET, target)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "Starting camera feed and RTMP…"
    }

    private fun stopStream() {
        val intent = Intent(this, StreamService::class.java).apply { action = StreamService.ACTION_STOP }
        startService(intent)
        status.text = "Stopping stream…"
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(statusReceiver) }
        wifiCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        super.onDestroy()
    }
}
