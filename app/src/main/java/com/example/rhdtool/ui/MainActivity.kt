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
    private lateinit var statusChargingRate: TextView
    private lateinit var statusChargingEvents: TextView
    private lateinit var startChargingSessionButton: Button
    private lateinit var pauseLogButton: Button

    private var wifiDrops = 0
    private var mobileDrops = 0

    private var lastBatteryPct: Int? = null
    private var lastBatteryChangeTimeMs: Long = System.currentTimeMillis()

    // Average % drop timing (discharge only)
    private var totalDropIntervalSec: Long = 0
    private var dropCount: Long = 0

    // Charging session state
    private var chargingSessionActive = false
    private var chargingSessionStartPct: Int? = null
    private var chargingSessionStartTime: Long = 0L
    private var chargingEvents = 0
    private var lastChargingPct: Int? = null
    private var lastChargingTime: Long = 0L
    private var chargerConnected = false

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
                            tallyWifi.text = getString(R.string.wifi_drops, wifiDrops)
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
                            tallyMobile.text = getString(R.string.mobile_drops, mobileDrops)
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
                        tallyWifi.text = getString(R.string.wifi_drops, wifiDrops)
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
                        tallyMobile.text = getString(R.string.mobile_drops, mobileDrops)
                        logEvent("Mobile", "Drop #$mobileDrops")
                    }
                }.also { handler.postDelayed(it, debounceMs) }
            }
        }
    }

    // ---- Mobile signal strength (logs regardless of validation) ----
    private lateinit var telephony: TelephonyManager
    private var lastMobilePoor: Boolean? = null

    // SIM state tracking
    private val simStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra("ss") ?: return
            when (state) {
                "ABSENT", "NOT_READY" -> {
                    mobileDrops++
                    tallyMobile.text = getString(R.string.mobile_drops, mobileDrops)
                    logEvent("Mobile", "SIM card removed or not ready")
                    statusMobile.text = getString(R.string.mobile_sim_absent)
                }
                "READY" -> {
                    logEvent("Mobile", "SIM card ready")
                    statusMobile.text = getString(R.string.mobile_sim_ready)
                }
            }
        }
    }

    // Enhanced signal/service tracking
    private var lastMobileNoService = false
    private val signalListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
            super.onSignalStrengthsChanged(signalStrength)

            val level = signalStrength?.level ?: 0 // 0..4, -1 if unavailable

            if (level == 0 && !lastMobileNoService) {
                mobileDrops++
                tallyMobile.text = getString(R.string.mobile_drops, mobileDrops)
                logEvent("Mobile", "No mobile signal (level 0)")
                statusMobile.text = getString(R.string.mobile_no_coverage)
                lastMobileNoService = true
            } else if (level > 0 && lastMobileNoService) {
                logEvent("Mobile", "Mobile coverage restored (level $level)")
                statusMobile.text = getString(R.string.mobile_coverage_restored)
                lastMobileNoService = false
            }
        }

        override fun onServiceStateChanged(serviceState: android.telephony.ServiceState?) {
            super.onServiceStateChanged(serviceState)
            val state = serviceState?.state
            if ((state == android.telephony.ServiceState.STATE_OUT_OF_SERVICE ||
                 state == android.telephony.ServiceState.STATE_EMERGENCY_ONLY) && !lastMobileNoService) {
                mobileDrops++
                tallyMobile.text = getString(R.string.mobile_drops, mobileDrops)
                logEvent("Mobile", "No mobile service (state $state)")
                statusMobile.text = getString(R.string.mobile_no_service)
                lastMobileNoService = true
            } else if (state == android.telephony.ServiceState.STATE_IN_SERVICE && lastMobileNoService) {
                logEvent("Mobile", "Mobile service restored")
                statusMobile.text = getString(R.string.mobile_service_restored)
                lastMobileNoService = false
            }
        }
    }

    // ---- Battery ----
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) handleBattery(intent)
        }
    }

    private var lastBatteryIntentCurrent: Int? = null

    private val batteryIntentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                // Try to get current_now extra
                val currentNowIntent = intent.getIntExtra("current_now", Int.MIN_VALUE)
                if (currentNowIntent != Int.MIN_VALUE) {
                    lastBatteryIntentCurrent = currentNowIntent
                    android.util.Log.d("ChargingRate", "Intent current_now: $currentNowIntent")
                }
            }
        }
    }

    // Live ticker
    private val sinceTicker = object : Runnable {
        override fun run() {
            val since = System.currentTimeMillis() - lastBatteryChangeTimeMs
            statusSinceChange.text = getString(R.string.since_last_pct_change, formatDuration(since))
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
        statusChargingRate = findViewById(R.id.statusChargingRate)
        statusChargingEvents = findViewById(R.id.statusChargingEvents)
        startChargingSessionButton = findViewById(R.id.btnStartChargingSession)
        pauseLogButton = findViewById(R.id.btnPauseLog)

        pauseLogButton.setOnClickListener {
            isLogPaused = !isLogPaused
            pauseLogButton.text = if (isLogPaused) "Resume Log" else "Pause Log"
        }

        val adapter = EventAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        MainScope().launch {
            viewModel.events.collect {
                adapter.submitList(it)
                if (!isLogPaused && it.isNotEmpty()) {
                    recyclerView.scrollToPosition(it.size - 1)
                }
            }
        }

        declareButton.setOnClickListener { declareBattery() }
        clearButton.setOnClickListener { clearAll() }
        startChargingSessionButton.setOnClickListener { startChargingSession() }

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

        registerReceiver(simStateReceiver, IntentFilter("android.intent.action.SIM_STATE_CHANGED"))
        registerReceiver(batteryIntentReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Throwable) {}
        cm.unregisterNetworkCallback(netCallback)
        wifiDropRunnable?.let { handler.removeCallbacks(it) }
        mobileDropRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(sinceTicker)
        telephony.listen(signalListener, PhoneStateListener.LISTEN_NONE)
        if (chargingSessionActive) {
            try { unregisterReceiver(chargerReceiver) } catch (_: Throwable) {}
        }
        try { unregisterReceiver(simStateReceiver) } catch (_: Throwable) {}
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
        statusDeclaredBattery.text = getString(R.string.battery_declared, pct, declaredAt)
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

        tallyWifi.text = getString(R.string.wifi_drops, 0)
        tallyMobile.text = getString(R.string.mobile_drops, 0)
        statusDeclaredBattery.text = getString(R.string.battery_declared_dash)
        statusSinceChange.text = getString(R.string.since_last_pct_change_dash)
        statusAvgDrop.text = getString(R.string.avg_drop_interval_dash)
        statusWifi.text = getString(R.string.wifi_dash)
        statusMobile.text = getString(R.string.mobile_dash)
        statusBattery.text = getString(R.string.battery_dash)
        statusChargingRate.text = getString(R.string.charging_rate_dash)
        statusChargingEvents.text = getString(R.string.charging_events_zero)

        MainScope().launch { viewModel.clear() }
        initNetworkState()
    }

    // --- Battery handling ---
    private fun handleBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        statusBattery.text = getString(R.string.battery, pct)

        val prev = lastBatteryPct
        if (prev != null && pct != prev) {
            val now = System.currentTimeMillis()
            val intervalSec = TimeUnit.MILLISECONDS.toSeconds(now - lastBatteryChangeTimeMs)

            if (pct < prev) {
                totalDropIntervalSec += intervalSec
                dropCount += 1
                val avg = if (dropCount > 0) totalDropIntervalSec / dropCount else 0
                statusAvgDrop.text = getString(R.string.avg_drop_interval, formatDurationSeconds(avg))
            }
            lastBatteryChangeTimeMs = now

            logEvent("Battery", "Changed to $pct% (Δ${intervalSec}s since last)")
        }

        lastBatteryPct = pct
    }

    // --- UI + logging helpers ---
    private fun setWifiUi(connected: Boolean) {
        statusWifi.text = if (connected) getString(R.string.type_wifi) + " Connected" else getString(R.string.type_wifi) + " Disconnected"
    }

    private fun setMobileUi(connected: Boolean) {
        statusMobile.text = if (connected) getString(R.string.type_mobile) + " Connected" else getString(R.string.type_mobile) + " Disconnected"
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

    // Handler for live charging rate updates
    private val chargingRateHandler = Handler(Looper.getMainLooper())
    private val chargingRateRunnable = object : Runnable {
        override fun run() {
            updateLiveChargingRate()
            chargingRateHandler.postDelayed(this, 1000L)
        }
    }

    private var lastChargeCounter: Int? = null
    private var lastChargeCounterTime: Long = 0L

    private fun updateLiveChargingRate() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val currentNowRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        android.util.Log.d("ChargingRate", "BATTERY_PROPERTY_CURRENT_NOW raw: $currentNowRaw")
        val intentCurrent = lastBatteryIntentCurrent
        val currentMa: String = when {
            intentCurrent != null && intentCurrent != Int.MIN_VALUE -> {
                // Auto-detect unit: if value is < 10,000, treat as mA; else as µA
                val ma = if (Math.abs(intentCurrent) < 10000) -intentCurrent.toDouble() else -intentCurrent / 1000.0
                if (ma < 1.0) {
                    "Intent: ${intentCurrent} (unit auto-detect)"
                } else {
                    String.format(Locale.getDefault(), "%.1f", ma)
                }
            }
            currentNowRaw != Int.MIN_VALUE && currentNowRaw != 0 -> {
                // Auto-detect unit: if value is < 10,000, treat as mA; else as µA
                val ma = if (Math.abs(currentNowRaw) < 10000) -currentNowRaw.toDouble() else -currentNowRaw / 1000.0
                if (ma < 1.0) {
                    "Raw: ${currentNowRaw} (unit auto-detect)"
                } else {
                    String.format(Locale.getDefault(), "%.1f", ma)
                }
            }
            else -> {
                // Fallback: use BATTERY_PROPERTY_CHARGE_COUNTER difference over time
                val chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                val now = System.currentTimeMillis()
                if (lastChargeCounter != null && lastChargeCounterTime != 0L) {
                    val deltaCharge = chargeCounter - lastChargeCounter!! // microamp-hours
                    val deltaTime = now - lastChargeCounterTime // ms
                    val hours = deltaTime / 3600000.0
                    val ma = if (hours > 0) (deltaCharge / hours / 1000.0) else 0.0
                    lastChargeCounter = chargeCounter
                    lastChargeCounterTime = now
                    if (ma >= 1.0) String.format(Locale.getDefault(), "%.1f", ma) else "Counter: ${chargeCounter}µAh"
                } else {
                    lastChargeCounter = chargeCounter
                    lastChargeCounterTime = now
                    "N/A"
                }
            }
        }
        statusChargingRate.text = getString(R.string.charging_rate_ma, currentMa)
    }

    private val chargerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    if (chargingSessionActive) {
                        chargingEvents++
                        chargerConnected = true
                        statusChargingEvents.text = getString(R.string.charging_events, chargingEvents)
                        logEvent("Charging", "Charger connected (event #$chargingEvents)")
                        lastChargingTime = System.currentTimeMillis()
                        lastChargingPct = getCurrentBatteryPct()
                        // Start live charging rate updates
                        chargingRateHandler.post(chargingRateRunnable)
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (chargingSessionActive) {
                        chargingEvents++
                        chargerConnected = false
                        statusChargingEvents.text = getString(R.string.charging_events, chargingEvents)
                        logEvent("Charging", "Charger disconnected (event #$chargingEvents)")
                        updateChargingRate()
                        // Stop live charging rate updates
                        chargingRateHandler.removeCallbacks(chargingRateRunnable)
                        statusChargingRate.text = getString(R.string.charging_rate_dash)
                    }
                }
            }
        }
    }

    private fun getCurrentBatteryPct(): Int? {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) pct else null
    }

    private fun startChargingSession() {
        chargingSessionActive = true
        chargingSessionStartTime = System.currentTimeMillis()
        chargingSessionStartPct = getCurrentBatteryPct()
        chargingEvents = 0
        statusChargingRate.text = getString(R.string.charging_rate_dash)
        statusChargingEvents.text = getString(R.string.charging_events_zero)
        logEvent("Charging", "Charging session started at ${chargingSessionStartPct ?: "?"}%")
        registerReceiver(chargerReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        registerReceiver(chargerReceiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED))
        startChargingSessionButton.isEnabled = false
        // If already plugged in, start live updates immediately
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val isCharging = bm.isCharging()
        if (isCharging) chargingRateHandler.post(chargingRateRunnable)
    }

    // Helper to check charging state
    private fun BatteryManager.isCharging(): Boolean {
        val status = (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun stopChargingSession() {
        chargingSessionActive = false
        try { unregisterReceiver(chargerReceiver) } catch (_: Throwable) {}
        updateChargingRate(final = true)
        logEvent("Charging", "Charging session ended")
        startChargingSessionButton.isEnabled = true
        // Stop live charging rate updates
        chargingRateHandler.removeCallbacks(chargingRateRunnable)
        statusChargingRate.text = getString(R.string.charging_rate_dash)
    }

    private fun updateChargingRate(final: Boolean = false) {
        val startPct = chargingSessionStartPct ?: return
        val startTime = chargingSessionStartTime
        val currentPct = getCurrentBatteryPct() ?: return
        val now = System.currentTimeMillis()
        val pctDelta = currentPct - startPct
        val timeDeltaHours = (now - startTime) / 3600000.0
        val rate = if (timeDeltaHours > 0) pctDelta / timeDeltaHours else 0.0
        statusChargingRate.text = getString(R.string.charging_rate, rate)
        if (final) logEvent("Charging", "Final charging rate: %.2f%%/hr".format(rate))
    }

    private var isLogPaused = false
}
