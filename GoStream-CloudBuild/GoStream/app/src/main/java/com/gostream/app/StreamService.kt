package com.gostream.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Network
import android.os.Build
import android.os.IBinder
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class StreamService : Service() {
    companion object {
        const val ACTION_START = "com.gostream.app.START"
        const val ACTION_STOP = "com.gostream.app.STOP"
        const val ACTION_STATUS = "com.gostream.app.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_NETWORK = "network"
        const val EXTRA_TARGET = "target"
        private const val CHANNEL_ID = "gostream_stream"
        private const val NOTIFICATION_ID = 41
    }

    private val running = AtomicBoolean(false)
    @Volatile private var cameraSocket: DatagramSocket? = null
    @Volatile private var localForwardSocket: DatagramSocket? = null
    @Volatile private var ffmpegSessionId: Long = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopStreaming()
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val network: Network? = intent.getParcelableExtra(EXTRA_NETWORK)
                val target = intent.getStringExtra(EXTRA_TARGET)
                if (network == null || target.isNullOrBlank()) {
                    publishStatus("Missing camera network or RTMP destination.")
                    stopSelf()
                } else startStreaming(network, target)
            }
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(network: Network, target: String) {
        if (!running.compareAndSet(false, true)) return
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Connecting to GoPro…"))
        publishStatus("Sending HERO4 stream-start command…")

        thread(name = "GoStream-camera") {
            try {
                // Route only camera-side sockets through the GoPro Wi-Fi network.
                // FFmpeg's RTMP output uses Android's normal internet route (usually mobile data).
                val command = URL("http://10.5.5.9/gp/gpControl/execute?p1=gpStream&a1=proto_v2&c1=restart")
                val connection = network.openConnection(command) as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"
                connection.inputStream.use { while (it.read() != -1) { } }
                connection.disconnect()

                val camera = DatagramSocket(null)
                camera.reuseAddress = true
                camera.bind(InetSocketAddress("0.0.0.0", 8554))
                network.bindSocket(camera)
                camera.soTimeout = 1000
                cameraSocket = camera

                val forward = DatagramSocket()
                localForwardSocket = forward
                val loopback = InetAddress.getByName("127.0.0.1")

                // Remux the GoPro's H.264 transport stream to RTMP/FLV without re-encoding.
                val safeTarget = target.replace("\\", "\\\\").replace("'", "\\'")
                val commandLine = "-hide_banner -loglevel warning -fflags nobuffer -flags low_delay " +
                    "-probesize 32768 -analyzeduration 0 -i udp://127.0.0.1:8555?fifo_size=500000&overrun_nonfatal=1 " +
                    "-map 0:v:0 -c:v copy -f flv '$safeTarget'"
                val session = FFmpegKit.executeAsync(commandLine) { session ->
                    val rc = session.returnCode
                    if (ReturnCode.isSuccess(rc)) publishStatus("Stream ended.")
                    else if (!ReturnCode.isCancel(rc)) publishStatus("FFmpeg stopped: ${session.failStackTrace ?: rc}")
                    stopStreaming()
                }
                ffmpegSessionId = session.sessionId

                publishStatus("Waiting for HERO4 video…")
                val keepAlive = "_GPHD_:0:0:2:0.000000\n".toByteArray(Charsets.UTF_8)
                val cameraAddress = InetAddress.getByName("10.5.5.9")
                val keepPacket = DatagramPacket(keepAlive, keepAlive.size, cameraAddress, 8554)
                var lastKeepAlive = 0L
                val buffer = ByteArray(65507)

                while (running.get()) {
                    val now = System.currentTimeMillis()
                    if (now - lastKeepAlive >= 2500) {
                        camera.send(keepPacket)
                        lastKeepAlive = now
                    }
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        camera.receive(packet)
                        val outgoing = DatagramPacket(packet.data, packet.length, loopback, 8555)
                        forward.send(outgoing)
                        publishStatus("Receiving GoPro video • relaying to RTMP")
                    } catch (_: java.net.SocketTimeoutException) {
                        // Keep-alive loop continues while waiting for the next video packet.
                    }
                }
            } catch (e: Exception) {
                publishStatus("Connection failed: ${e.message ?: e.javaClass.simpleName}")
                stopStreaming()
            }
        }
    }

    private fun stopStreaming() {
        if (!running.getAndSet(false)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        runCatching { cameraSocket?.close() }
        runCatching { localForwardSocket?.close() }
        cameraSocket = null
        localForwardSocket = null
        if (ffmpegSessionId >= 0) runCatching { FFmpegKit.cancel(ffmpegSessionId) }
        ffmpegSessionId = -1
        publishStatus("Stream stopped.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.notify(NOTIFICATION_ID, notification(message))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "GoStream", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("GoStream is running")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("GoStream")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
