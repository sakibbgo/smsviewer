package com.example.smsviewer

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val ACTION_SMS_LOG = "com.example.smsviewer.ACTION_SMS_LOG"
private const val PREFS_NAME = "MyPrefs"
private const val PREF_LOGS = "smsLogs"

class MainActivity : AppCompatActivity() {

    private lateinit var sim1EditText: EditText
    private lateinit var sim2EditText: EditText
    private lateinit var sim1SaveBtn: Button
    private lateinit var sim2SaveBtn: Button
    private lateinit var listenBtn: Button
    private lateinit var clearLogsBtn: Button
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var smsLog: TextView
    private lateinit var smsLogTitle: TextView

    private lateinit var permSms: TextView
    private lateinit var smsAllowBtn: Button
    private lateinit var permSim1: TextView
    private lateinit var permSim2: TextView
    private lateinit var permNet: TextView
    private lateinit var permBattery: TextView
    private lateinit var screenBtn: Button

    private var savedSim1Number: String = ""
    private var savedSim2Number: String = ""
    private var isListeningToSms = false
    private var isInternetAvailable = false

    private val smsLogList = mutableListOf<String>()
    private lateinit var sharedPreferences: SharedPreferences
    private val requestCode = 101
    private val accessRequestCode = 102

    private lateinit var networkMonitor: NetworkMonitor

    private val uiLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val log = intent?.getStringExtra("log") ?: return
            addSmsLog(log)
            persistLogs()
        }
    }

    private val airplaneModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshInternetUiOnly()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        sim1EditText = findViewById(R.id.sim1Number)
        sim2EditText = findViewById(R.id.sim2Number)
        sim1SaveBtn = findViewById(R.id.sim1SaveBtn)
        sim2SaveBtn = findViewById(R.id.sim2SaveBtn)
        listenBtn = findViewById(R.id.listenBtn)
        clearLogsBtn = findViewById(R.id.clearLogsBtn)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        smsLog = findViewById(R.id.smsLog)
        smsLogTitle = findViewById(R.id.smsLogTitle)

        permSms = findViewById(R.id.permSms)
        smsAllowBtn = findViewById(R.id.smsAllowBtn)
        permSim1 = findViewById(R.id.permSim1)
        permSim2 = findViewById(R.id.permSim2)
        permNet = findViewById(R.id.permNet)
        permBattery = findViewById(R.id.permBattery)
        screenBtn = findViewById(R.id.screenBtn)

        smsAllowBtn.text = "FIX"

        sim1EditText.setText(sharedPreferences.getString("sim1Number", ""))
        sim2EditText.setText(sharedPreferences.getString("sim2Number", ""))

        savedSim1Number = sim1EditText.text.toString()
        savedSim2Number = sim2EditText.text.toString()

        if (!hasPermissions()) {
            requestPermissions()
        }

        networkMonitor = NetworkMonitor(this) { available ->
            isInternetAvailable = available
            runOnUiThread {
                updateStatus()
                updateNetIndicator()
            }
        }
        isInternetAvailable = networkMonitor.isInternetCurrentlyAvailable()
        networkMonitor.start()

        sim1SaveBtn.setOnClickListener {
            val input = sim1EditText.text.toString().trim()
            if (input.length != 11) {
                Toast.makeText(this, "Enter a valid 11-digit SIM 1 number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            savedSim1Number = input
            sharedPreferences.edit().putString("sim1Number", input).apply()
            updateSimIndicators()
        }

        sim2SaveBtn.setOnClickListener {
            val input = sim2EditText.text.toString().trim()
            if (input.length != 11) {
                Toast.makeText(this, "Enter a valid 11-digit SIM 2 number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            savedSim2Number = input
            sharedPreferences.edit().putString("sim2Number", input).apply()
            updateSimIndicators()
        }

        isListeningToSms = isSmsReceiverEnabled(this)
        updateStatus()
        updateNetIndicator()
        updateBatteryStatus()
        updateAccessIndicator()
        updateSimIndicators()

        listenBtn.setOnClickListener {
            isListeningToSms = !isListeningToSms
            setSmsListening(this, isListeningToSms)
            updateStatus()
        }

        clearLogsBtn.setOnClickListener {
            smsLogList.clear()
            smsLog.text = "No SMS logs yet"
            smsLogTitle.text = "SMS Logs (0)"
            sharedPreferences.edit().remove(PREF_LOGS).apply()
        }

        permBattery.setOnClickListener {
            handleBatteryOptimizationClick()
        }

        smsAllowBtn.setOnClickListener {
            handleAccessFixClick()
        }

        screenBtn.setOnClickListener {
            openScreenSettings()
        }

        loadStoredLogsIntoList()
        reloadLogsFromStorage()
    }

    override fun onResume() {
        super.onResume()
        refreshInternetUiOnly()
        updateBatteryStatus()
        updateAccessIndicator()
        updateSimIndicators()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.stop()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            uiLogReceiver,
            IntentFilter(ACTION_SMS_LOG),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            airplaneModeReceiver,
            IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        reloadLogsFromStorage()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(uiLogReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(airplaneModeReceiver) } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            this.requestCode, accessRequestCode -> {
                updateAccessIndicator()
                updateSimIndicators()
                updateStatus()
            }
        }
    }

    private fun refreshInternetUiOnly() {
        isInternetAvailable = networkMonitor.isInternetCurrentlyAvailable()
        updateStatus()
        updateNetIndicator()
    }

    private fun updateStatus() {
        if (isListeningToSms) {
            if (isInternetAvailable) {
                statusDot.setBackgroundResource(R.drawable.dot_green)
                statusText.text = "Online"
                statusText.setTextColor(Color.parseColor("#41B619"))
            } else {
                statusDot.setBackgroundResource(R.drawable.dot_red)
                statusText.text = "No Internet"
                statusText.setTextColor(Color.parseColor("#AA0000"))
            }
            listenBtn.text = "Deactivate"
            listenBtn.setBackgroundColor(Color.parseColor("#E53935"))
        } else {
            statusDot.setBackgroundResource(R.drawable.dot_red)
            statusText.text = "Offline"
            statusText.setTextColor(Color.parseColor("#AA0000"))
            listenBtn.text = "Activate"
            listenBtn.setBackgroundColor(Color.parseColor("#41B619"))
        }
    }

    private fun updateAccessIndicator() {
        val accessAllowed = hasAccessPermissions()

        if (accessAllowed) {
            permSms.text = "ACCESS ✔"
            permSms.setTextColor(Color.parseColor("#2E7D32"))
            smsAllowBtn.alpha = 0.6f
        } else {
            permSms.text = "ACCESS ❌"
            permSms.setTextColor(Color.parseColor("#AA0000"))
            smsAllowBtn.alpha = 1.0f
        }
    }

    private fun updateSimIndicators() {
        val sim1Ready = savedSim1Number.trim().length == 11
        val sim2Ready = savedSim2Number.trim().length == 11

        if (sim1Ready) {
            permSim1.text = "S1 ✔"
            permSim1.setTextColor(Color.parseColor("#1565C0"))
        } else {
            permSim1.text = "S1 ❌"
            permSim1.setTextColor(Color.parseColor("#AA0000"))
        }

        if (sim2Ready) {
            permSim2.text = "S2 ✔"
            permSim2.setTextColor(Color.parseColor("#1565C0"))
        } else {
            permSim2.text = "S2 ❌"
            permSim2.setTextColor(Color.parseColor("#AA0000"))
        }
    }

    private fun updateNetIndicator() {
        if (isInternetAvailable) {
            permNet.text = "NET ✔"
            permNet.setTextColor(Color.parseColor("#EF6C00"))
        } else {
            permNet.text = "NET ❌"
            permNet.setTextColor(Color.parseColor("#AA0000"))
        }
    }

    private fun updateBatteryStatus() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnored = pm.isIgnoringBatteryOptimizations(packageName)

        if (isIgnored) {
            permBattery.text = "BAT ✔"
            permBattery.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            permBattery.text = "BAT ⚠"
            permBattery.setTextColor(Color.parseColor("#C2185B"))
        }
    }

    private fun handleBatteryOptimizationClick() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager

        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Battery optimization already disabled", Toast.LENGTH_SHORT).show()
            updateBatteryStatus()
            return
        }

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Cannot open battery settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleAccessFixClick() {
        if (hasAccessPermissions()) {
            Toast.makeText(this, "SMS and Phone permissions already allowed", Toast.LENGTH_SHORT).show()
            updateAccessIndicator()
            return
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_PHONE_STATE
            ),
            accessRequestCode
        )
    }

    private fun openScreenSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Cannot open screen settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addSmsLog(logMsg: String) {
        smsLogList.add(0, logMsg)
        smsLog.text = smsLogList.joinToString("\n\n")
        smsLogTitle.text = "SMS Logs (${smsLogList.size})"
    }

    private fun persistLogs() {
        sharedPreferences.edit().putString(PREF_LOGS, smsLogList.joinToString("\n\n")).apply()
    }

    private fun loadStoredLogsIntoList() {
        val saved = sharedPreferences.getString(PREF_LOGS, "") ?: ""
        if (saved.isNotBlank()) {
            smsLogList.clear()
            smsLogList.addAll(saved.split("\n\n"))
        }
    }

    private fun reloadLogsFromStorage() {
        val saved = sharedPreferences.getString(PREF_LOGS, "") ?: ""
        smsLogList.clear()
        if (saved.isNotBlank()) {
            smsLogList.addAll(saved.split("\n\n"))
        }
        smsLog.text = if (smsLogList.isEmpty()) "No SMS logs yet" else smsLogList.joinToString("\n\n")
        smsLogTitle.text = "SMS Logs (${smsLogList.size})"
    }

    private fun hasAccessPermissions(): Boolean {
        return listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE
        ).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasPermissions(): Boolean {
        return listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.INTERNET
        ).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.INTERNET
            ),
            requestCode
        )
    }

    private fun setSmsListening(context: Context, enabled: Boolean) {
        val pm = context.packageManager
        val cn = ComponentName(context, SmsReceiver::class.java)
        pm.setComponentEnabledSetting(
            cn,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun isSmsReceiverEnabled(context: Context): Boolean {
        val pm = context.packageManager
        val cn = ComponentName(context, SmsReceiver::class.java)
        return pm.getComponentEnabledSetting(cn) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
}

/** Internet monitor */
class NetworkMonitor(
    context: Context,
    private val onInternetChanged: (Boolean) -> Unit
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            notifyCurrentState()
        }

        override fun onLost(network: Network) {
            notifyCurrentState()
            mainHandler.postDelayed({ notifyCurrentState() }, 300)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            notifyCurrentState()
        }
    }

    fun start() {
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun isInternetCurrentlyAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun notifyCurrentState() {
        onInternetChanged(isInternetCurrentlyAvailable())
    }
}

/** Manifest-declared receiver that survives idle/Doze and wakes the app for SMS. */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) return

        val pending = goAsync()

        Thread {
            try {
                val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (smsMessages.isEmpty()) return@Thread

                val sender = smsMessages.firstOrNull()?.displayOriginatingAddress ?: "Unknown Sender"
                val messageBody = smsMessages.joinToString("") { it.messageBody ?: "" }

                val subscriptionId = intent.extras?.getInt(
                    "subscription",
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID
                ) ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID

                val usedSimNumber = resolveSavedSimNumber(context, subscriptionId)

                if (messageBody.isNotBlank()) {
                    val simLabel = when (getSlotIndex(context, subscriptionId)) {
                        0 -> "SIM1"
                        1 -> "SIM2"
                        else -> "Unknown SIM"
                    }

                    val logLine = "$sender: $messageBody\n$simLabel: $usedSimNumber"

                    appendLog(context, logLine)

                    context.sendBroadcast(
                        Intent(ACTION_SMS_LOG)
                            .setPackage(context.packageName)
                            .putExtra("log", logLine)
                    )

                    sendSmsToServer(context, usedSimNumber, messageBody)
                }

            } catch (e: Exception) {
                Log.e("SMS_RECEIVER_ERROR", e.message ?: "error", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun resolveSavedSimNumber(context: Context, subscriptionId: Int): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sim1 = sp.getString("sim1Number", "") ?: ""
        val sim2 = sp.getString("sim2Number", "") ?: ""

        return when (getSlotIndex(context, subscriptionId)) {
            0 -> if (sim1.isNotBlank()) sim1 else "Unknown SIM"
            1 -> if (sim2.isNotBlank()) sim2 else "Unknown SIM"
            else -> "Unknown SIM"
        }
    }

    @SuppressLint("MissingPermission")
    private fun getSlotIndex(context: Context, subId: Int): Int {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return -1
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            sm.activeSubscriptionInfoList?.firstOrNull { it.subscriptionId == subId }?.simSlotIndex ?: -1
        } catch (e: Exception) {
            Log.e("SIM_SLOT_ERROR", e.message ?: "error", e)
            -1
        }
    }

    private fun sendSmsToServer(context: Context, sender: String, otp: String) {
        var connection: HttpURLConnection? = null

        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val network = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(network)

            val hasInternetTransport = caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

            if (!hasInternetTransport) {
                Log.w("OTP_SEND", "No active network transport reported, attempting send anyway")
            }

            val encoded = URLEncoder.encode(otp, "UTF-8")
            val url = URL("https://otp-458898283632.us-central1.run.app/?phone=$sender&otp=$encoded")

            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                useCaches = false
                doInput = true
                instanceFollowRedirects = true
            }

            connection.connect()

            val responseCode = connection.responseCode
            val inputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseBody = inputStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            Log.d("OTP_SEND", "Response code=$responseCode body=$responseBody")
        } catch (e: Exception) {
            Log.e("OTP_SEND", "Send failed: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun appendLog(context: Context, line: String) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = sp.getString(PREF_LOGS, "") ?: ""
        val updated = if (existing.isBlank()) line else "$line\n\n$existing"
        sp.edit().putString(PREF_LOGS, updated).apply()
    }
}