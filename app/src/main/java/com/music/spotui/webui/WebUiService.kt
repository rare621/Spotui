package com.music.spotui.webui

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.music.spotui.MainActivity
import com.music.spotui.R
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address

internal data class WebUiState(val running: Boolean = false, val link: String = "", val error: String = "")
internal object WebUiController {
    val state = MutableStateFlow(WebUiState())
    fun wifiAddress(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstNotNullOfOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) null
            else cm.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }?.address?.hostAddress
        }
    }
    fun start(context: Context) {
        state.value = WebUiState(error = "Starting…")
        runCatching { ContextCompat.startForegroundService(context, Intent(context, WebUiService::class.java)) }
            .onFailure { state.value = WebUiState(error = "Couldn't start. Keep USpot open and try again.") }
    }
    fun stop(context: Context) { context.stopService(Intent(context, WebUiService::class.java)) }
}

/** Opt-in LAN session. No boot receiver and no sticky restart after force-stop. */
class WebUiService : Service() {
    private var server: WebUiServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var address: String? = null
    private val watch = object : Runnable {
        override fun run() {
            if (server == null) return
            if (WebUiController.wifiAddress(this@WebUiService) != address) {
                WebUiController.state.value = WebUiState(error = "Wi-Fi changed or disconnected. Reconnect and start desktop mode again.")
                stopSelf(); return
            }
            handler.postDelayed(this, 3000)
        }
    }
    private val renew = object : Runnable {
        override fun run() { if(server!=null) { wakeLock?.acquire(30*60_000L); handler.postDelayed(this,15*60_000L) } }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (server != null) return START_NOT_STICKY
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("web_ui", "desktop mode connection", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 72, Intent(this,WebUiService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(this, 73, Intent(this,MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this,"web_ui").setSmallIcon(R.drawable.ic_playing)
                .setContentTitle("USpot desktop mode is running").setContentText("Connected browsers can play from this phone. Tap Stop to disconnect.")
                .setContentIntent(open).setOngoing(true).addAction(0,"Stop",stop).build()
            ServiceCompat.startForeground(this, 7201, notification, if(Build.VERSION.SDK_INT>=29)ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            val ip = WebUiController.wifiAddress(this) ?: error("Connect your phone to Wi-Fi first.")
            address = ip
            val secret = WebUiProtocol.secret()
            val port = if (com.music.spotui.BuildConfig.IS_PREVIEW) 8766 else 8765
            val started = WebUiServer(applicationContext, ip, port, secret)
            started.start(70_000, false)
            server = started
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"USpot:WebUI").apply {
                setReferenceCounted(false); acquire(30*60_000L)
            }
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"USpot:WebUI").apply {
                setReferenceCounted(false); acquire()
            }
            WebUiController.state.value = WebUiState(true,"http://$ip:$port/")
            handler.post(watch); handler.postDelayed(renew,15*60_000L)
        } catch(e:Exception) {
            WebUiController.state.value = WebUiState(error = "Couldn't start desktop mode. Check Wi-Fi, then try again; the local port may be in use.")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        server?.stop(); server=null
        wifiLock?.let { if(it.isHeld)it.release() }; wifiLock=null
        wakeLock?.let { if(it.isHeld)it.release() }; wakeLock=null
        val error = WebUiController.state.value.error
        WebUiController.state.value = WebUiState(error=error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
