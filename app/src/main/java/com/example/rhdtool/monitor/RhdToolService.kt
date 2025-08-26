package com.example.rhdtool.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.example.rhdtool.R

/**
 * Public state exposed to UI
 */
object MonitorState {
    // true = connected (validated), false = disconnected after debounce
    val wifiStatus = MutableLiveData<Boolean>()
    val mobileStatus = MutableLiveData<Boolean>()
    val btStatus = MutableLiveData<Boolean>()

    // Optional display labels
    val wifiSsid = MutableLiveData<String?>()
    val mobileCarrier = MutableLiveData<String?>()
}

class RhdToolService : Service() {

    // ---- System services ----
    private val cm by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val btAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }

    // ---- Foreground notification ----
    private val channelId = "rhdtool_monitor"
    private val notifId = 1001

    // ---- Connectivity state + debounce ----
    private var wifiValidated = false
    private var mobileValidated = false

    private val handler = Handler(Looper.getMainLooper())
    private val debounceMs = 1500L
    private var wifiDropRunnable: Runnable? = null
    private var mobileDropRunnable: Runnable? = null

    // ---- Bluetooth updates ----
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED == intent?.action) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                MonitorState.btStatus.postValue(state == BluetoothAdapter.STATE_ON)
            }
        }
    }

    // ---- Network callback: ONLY treat as connected when VALIDATED ----
    private val netCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                if (validated && !wifiValidated) {
                    wifiValidated = true
                    wifiDropRunnable?.let { handler.removeCallbacks(it) }
                    MonitorState.wifiSsid.postValue(getWifiSsidFrom(caps))
                    MonitorState.wifiStatus.postValue(true)
                } else if (!validated && wifiValidated) {
                    wifiValidated = false
                    wifiDropRunnable?.let { handler.removeCallbacks(it) }
                    wifiDropRunnable = Runnable {
                        if (!wifiValidated) {
                            MonitorState.wifiSsid.postValue(null)
                            MonitorState.wifiStatus.postValue(false)
                        }
                    }.also { handler.postDelayed(it, debounceMs) }
                }
            }

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                if (validated && !mobileValidated) {
                    mobileValidated = true
                    mobileDropRunnable?.let { handler.removeCallbacks(it) }
                    MonitorState.mobileCarrier.postValue(getCarrierName())
                    MonitorState.mobileStatus.postValue(true)
                } else if (!validated && mobileValidated) {
                    mobileValidated = false
                    mobileDropRunnable?.let { handler.removeCallbacks(it) }
                    mobileDropRunnable = Runnable {
                        if (!mobileValidated) {
                            MonitorState.mobileCarrier.postValue(null)
                            MonitorState.mobileStatus.postValue(false)
                        }
                    }.also { handler.postDelayed(it, debounceMs) }
                }
            }
        }

        override fun onLost(network: Network) {
            // Some devices jump straight here; only emit disconnect if we were validated.
            val caps = cm.getNetworkCapabilities(network) ?: return

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && wifiValidated) {
                wifiValidated = false
                wifiDropRunnable?.let { handler.removeCallbacks(it) }
                wifiDropRunnable = Runnable {
                    if (!wifiValidated) {
                        MonitorState.wifiSsid.postValue(null)
                        MonitorState.wifiStatus.postValue(false)
                    }
                }.also { handler.postDelayed(it, debounceMs) }
            }

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && mobileValidated) {
                mobileValidated = false
                mobileDropRunnable?.let { handler.removeCallbacks(it) }
                mobileDropRunnable = Runnable {
                    if (!mobileValidated) {
                        MonitorState.mobileCarrier.postValue(null)
                        MonitorState.mobileStatus.postValue(false)
                    }
                }.also { handler.postDelayed(it, debounceMs) }
            }
        }
    }

    // ---- Service lifecycle ----
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(notifId, buildNotification())

        // Register for internet-capable networks
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(req, netCallback)

        // Initial BT state + receiver
        MonitorState.btStatus.postValue(btAdapter?.isEnabled == true)
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onDestroy() {
        super.onDestroy()
        cm.unregisterNetworkCallback(netCallback)
        wifiDropRunnable?.let { handler.removeCallbacks(it) }
        mobileDropRunnable?.let { handler.removeCallbacks(it) }
        try { unregisterReceiver(btReceiver) } catch (_: Throwable) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Helpers ----

    /**
     * Backwards-compatible SSID:
     * - API 29+: use NetworkCapabilities.transportInfo as WifiInfo
     * - API 26–28: fall back to WifiManager.connectionInfo (may require location permission to avoid "<unknown ssid>")
     */
    private fun getWifiSsidFrom(nc: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = nc.transportInfo
            if (info is WifiInfo) {
                val ssid = info.ssid
                return ssid?.trim('"')
            }
            return null
        }
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wm.connectionInfo?.ssid
            if (ssid == null || ssid == WifiManager.UNKNOWN_SSID) null else ssid.trim('"')
        } catch (_: Throwable) {
            null
        }
    }

    private fun getCarrierName(): String? = try {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        tm.networkOperatorName?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(channelId) == null) {
                val ch = NotificationChannel(
                    channelId,
                    "RHD Monitor",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Connectivity monitoring"
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)        // use launcher icon (exists by default)
            .setContentTitle("RHD Tool monitoring")
            .setContentText("Watching Wi-Fi, Mobile, and Bluetooth")
            .setOngoing(true)
            .build()
    }
}
