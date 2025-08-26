package com.example.rhdtool.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rhdtool.R
import com.example.rhdtool.data.AppDatabase
import com.example.rhdtool.data.Event
import com.example.rhdtool.data.LogRepo
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private lateinit var statusWifi: TextView
    private lateinit var statusMobile: TextView
    private lateinit var statusBattery: TextView
    private lateinit var statusDeclaredBattery: TextView
    private lateinit var statusSinceChange: TextView
    private lateinit var statusAvgDrop: TextView
    private lateinit var tallyWifi: TextView
    private lateinit var tallyMobile: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var declareButton: Button
    private lateinit var clearButton: Button

    private var wifiDrops = 0
    private var mobileDrops = 0

    private var lastBatteryPct: Int? = null
    private var lastBatteryChangeTimeMs: Long = System.currentTimeMillis()

    // Average % drop timing (discharge only)
    private var totalDropIntervalSec: Long = 0
    private var dropCount: Long = 0

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(LogRepo(AppDatabase.getDatabase(this).eventDao()))
    }

    // ---- Networking (validated + debounced) ----
    private lateinit var cm: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private val debounceMs = 1500L
    private var wifiValidated = false
    private var mobileValidated = false
    private var wifiDropRunnable: Runnable? = null
    private var mobileDropRunnable: Runnable? = null

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                if (validated && !wifiValidated) {
                    wifiValidated = true
                    wifiDropRunnable?.let { handler.removeCallbacks(it) }
                    setWifiUi(true)
                    logEvent("WiFi", "Connected")
                } else if (!validated && wifiValidated) {
                    wifiValidated = false
                    wifiDropRunnable?.let { handler.removeCallbacks(it) }
                    wifiDropRunnable = Runnable {
                        if (!wifiValidated) {
                            setWifiUi(false)
                            wifiDrops++
                            tallyWifi.text = "WiFi drops: $wifiDrops"
                            logEvent("WiFi", "Drop #$wifiDrops")
                        }
                    }.also { handler.postDelayed(it, debounceMs) }
                }
            }

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                if (validated && !mobileValidated) {
                    mobileValidated = true
                    mobileDropRunnable?.let { handler.removeCallbacks(it) }
                    setMobileUi(true)
                } else if (!validated && mobileValidated) {
                    mobileValidated = false
                    mobileDropRunnable?.let { handler.removeCallbacks(it) }
                    mobileDropRunnable = Runnable {
                        if (!mobileValidated) {
                            setMobileUi(false)
                            mobileDrops++
                            tallyMobile.text = "Mobile drops: $mobileDrops"
                            logEvent("Mobile", "Drop #$mobileDrops")
                        }
                    }.also { handler.postDelayed(it, debounceMs) }
                }
            }
        }

        override fun onLost(network: Network) {
            val caps = cm.getNetworkCapabilities(network) ?: return
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && wifiValidated) {
                wifiValidated = false
                wifiDropRunnable?.let { handler.removeCallbacks(it) }
                wifiDropRunnable = Runnable {
                    if (!wifiValidated) {
                        setWifiUi(false)
                        wifiDrops++
                        tallyWifi.text = "WiFi drops: $wifiDrops"
                        logEvent("WiFi", "Drop #$wifiDrops")
                    }
                }.also { handler.postDelayed(it, debounceMs) }
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && mobileValidated) {
                mobileValidated = false
                mobileDropRunnable?.let { handler.removeCallbacks(it) }
                mobileDropRunnable = Runnable {
                    if (!mobileValidated) {
                        setMobileUi(false)
                        mobileDrops++
                        tallyMobile.text = "Mobile drops: $mobileDrops"
                        logEvent("Mobile", "Drop #$mobileDrops")
                    }
                }.also { handler.postDelayed(it, debounceMs) }
            }
        }
    }

    // ---- Mobile signal strength (logs regardless of validation) ----
    private lateinit var telephony: TelephonyManager
    private var lastMobilePoor: Boolean? = null
    private val signalListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
            super.onSignalStrengthsChanged(signalStrength)

            val level = signalStrength?.level ?: -1 // 0..4, -1 if unavailable

            if (level == -1) {
                statusMobile.text = if (mobileValidated) "Mobile (no level)" else "Mobile (radio off / no SIM)"
                if (lastMobilePoor == null) {
                    logEvent("Mobile", "No cellular signal info (emulator/no SIM?)")
                    lastMobilePoor = false // baseline to avoid repeats
                }
                return
            }

            val isPoor = (level <= 1) // 0 none/unknown, 1 poor
            statusMobile.text = if (isPoor) "Mobile Poor (lvl $level)" else "Mobile Good (lvl $level)"

            val prev = lastMobilePoor
            if (prev == null) {
                lastMobilePoor = isPoor // baseline only
            } else if (prev != isPoor) {
                lastMobilePoor = isPoor
                if (isPoor) logEvent("Mobile", "Data dropped to poor (level $level)")
                else logEvent("Mobile", "Data recovered from poor (level $level)")
            }
        }
    }

    // ---- Battery ----
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) handleBattery(intent)
        }
    }

    // Live ticker
    private val sinceTicker = object : Runnable {
        override fun run() {
            val since = System.currentTimeMillis() - lastBatteryChangeTimeMs
            statusSinceChange.text = "Since last % change: ${formatDuration(since)}"
            handler.postDelayed(this, 1000L)
        }
    }

    // ---- Permissions (modern API) ----
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        if (allGranted) {
            initNetworkState()
            telephony.listen(signalListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        } else {
            logEvent("Permissions", "Some permissions were denied — mobile monitoring may not work")
        }
    }

    private fun ensurePermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ActivityCompat.checkSelfPermission(this, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            telephony.listen(signalListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        }
    }

    // ---- Lifecycle ----
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusWifi = findViewById(R.id.statusWifi)
        statusMobile = findViewById(R.id.statusMobile)
        statusBattery = findViewById(R.id.statusBattery)
        statusDeclaredBattery = findViewById(R.id.statusDeclaredBattery)
        statusSinceChange = findViewById(R.id.statusSinceChange)
        statusAvgDrop = findViewById(R.id.statusAvgDrop)
        tallyWifi = findViewById(R.id.tallyWifi)
        tallyMobile = findViewById(R.id.tallyMobile)
        recyclerView = findViewById(R.id.eventLog)
        declareButton = findViewById(R.id.btnDeclareBattery)
        clearButton = findViewById(R.id.btnClear)

        val adapter = EventAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        MainScope().launch { viewModel.events.collect { adapter.submitList(it) } }

        declareButton.setOnClickListener { declareBattery() }
        clearButton.setOnClickListener { clearAll() }

        // Battery: register + immediately read sticky value so UI shows on launch
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { sticky ->
            handleBattery(sticky)
        }

        // Connectivity callback
        cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(req, netCallback)
        initNetworkState()

        // Telephony signal strength
        telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        ensurePermissions()

        // Timers
        handler.post(sinceTicker)

        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Throwable) {}
        cm.unregisterNetworkCallback(netCallback)
        wifiDropRunnable?.let { handler.removeCallbacks(it) }
        mobileDropRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(sinceTicker)
        telephony.listen(signalListener, PhoneStateListener.LISTEN_NONE)
    }

    // --- Initialization helpers ---
    private fun initNetworkState() {
        val active = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(active)

        val wifiUp = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        wifiValidated = wifiUp
        setWifiUi(wifiUp)

        val mobileUp = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        mobileValidated = mobileUp
        statusMobile.text = if (mobileUp) "Mobile Connected" else "Mobile Disconnected"
    }

    // --- Button helpers ---
    private fun declareBattery() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val declaredAt = nowStamp()
        statusDeclaredBattery.text = "Battery declared: $pct%  •  $declaredAt"
        logEvent("DeclaredBattery", "Declared $pct% at $declaredAt")
    }

    private fun clearAll() {
        wifiDrops = 0
        mobileDrops = 0
        totalDropIntervalSec = 0
        dropCount = 0
        lastBatteryPct = null
        lastBatteryChangeTimeMs = System.currentTimeMillis()
        lastMobilePoor = null

        tallyWifi.text = "WiFi drops: 0"
        tallyMobile.text = "Mobile drops: 0"
        statusDeclaredBattery.text = "Battery declared: —"
        statusSinceChange.text = "Since last % change: —"
        statusAvgDrop.text = "Avg % drop interval: —"
        statusWifi.text = "WiFi: —"
        statusMobile.text = "Mobile: —"
        statusBattery.text = "Battery: —"

        MainScope().launch { viewModel.clear() }
        initNetworkState()
    }

    // --- Battery handling ---
    private fun handleBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        statusBattery.text = "Battery: $pct%"

        val prev = lastBatteryPct
        if (prev != null && pct != prev) {
            val now = System.currentTimeMillis()
            val intervalSec = TimeUnit.MILLISECONDS.toSeconds(now - lastBatteryChangeTimeMs)

            if (pct < prev) {
                totalDropIntervalSec += intervalSec
                dropCount += 1
                val avg = if (dropCount > 0) totalDropIntervalSec / dropCount else 0
                statusAvgDrop.text = "Avg % drop interval: ${formatDurationSeconds(avg)}"
            }
            lastBatteryChangeTimeMs = now

            logEvent("Battery", "Changed to $pct% (Δ${intervalSec}s since last)")
        }

        lastBatteryPct = pct
    }

    // --- UI + logging helpers ---
    private fun setWifiUi(connected: Boolean) {
        statusWifi.text = if (connected) "WiFi Connected" else "WiFi Disconnected"
    }

    private fun setMobileUi(connected: Boolean) {
        statusMobile.text = if (connected) "Mobile Connected" else "Mobile Disconnected"
    }

    private fun logEvent(type: String, status: String) {
        MainScope().launch {
            viewModel.insert(Event(timestamp = System.currentTimeMillis(), type = type, status = status))
        }
    }

    private fun nowStamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(System.currentTimeMillis())
    }

    private fun formatDuration(ms: Long): String =
        formatDurationSeconds(TimeUnit.MILLISECONDS.toSeconds(ms))

    private fun formatDurationSeconds(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> String.format(Locale.getDefault(), "%dh %dm %ds", h, m, s)
            m > 0 -> String.format(Locale.getDefault(), "%dm %ds", m, s)
            else -> String.format(Locale.getDefault(), "%ds", s)
        }
    }
}
