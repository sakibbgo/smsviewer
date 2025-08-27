package com.example.smsviewer

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import android.widget.*
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

// Internal broadcast action so the Receiver can push log lines to the UI when the Activity is visible.
private const val ACTION_SMS_LOG = "com.example.smsviewer.ACTION_SMS_LOG"

// NEW: single source of truth key for persisted logs
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

    private var savedSim1Number: String = ""
    private var savedSim2Number: String = ""
    private var isListeningToSms = false
    private val smsLogList = mutableListOf<String>()
    private lateinit var sharedPreferences: SharedPreferences
    private val requestCode = 101

    // Receiver to update UI log when the background SmsReceiver broadcasts a new log line
    private val uiLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val log = intent?.getStringExtra("log") ?: return
            addSmsLog(log)             // CHANGED: still updates UI
            persistLogs()              // NEW: keep storage in sync when Activity is visible
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Find all views by new IDs
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

        // Restore saved SIM numbers
        sim1EditText.setText(sharedPreferences.getString("sim1Number", ""))
        sim2EditText.setText(sharedPreferences.getString("sim2Number", ""))

        savedSim1Number = sim1EditText.text.toString()
        savedSim2Number = sim2EditText.text.toString()

        // Request permissions on launch
        if (!hasPermissions()) {
            requestPermissions()
        }

        // SIM 1 Save Button
        sim1SaveBtn.setOnClickListener {
            val sim1Input = sim1EditText.text.toString().trim()
            if (sim1Input.length != 11) {
                Toast.makeText(this, "Enter a valid 11-digit SIM 1 number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            savedSim1Number = sim1Input
            sharedPreferences.edit().putString("sim1Number", savedSim1Number).apply()
            Toast.makeText(this, "SIM 1 number saved", Toast.LENGTH_SHORT).show()
        }

        // SIM 2 Save Button
        sim2SaveBtn.setOnClickListener {
            val sim2Input = sim2EditText.text.toString().trim()
            if (sim2Input.length != 11) {
                Toast.makeText(this, "Enter a valid 11-digit SIM 2 number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            savedSim2Number = sim2Input
            sharedPreferences.edit().putString("sim2Number", savedSim2Number).apply()
            Toast.makeText(this, "SIM 2 number saved", Toast.LENGTH_SHORT).show()
        }

        // Initialize listening state from the actual component enabled state
        isListeningToSms = isSmsReceiverEnabled(this)
        updateStatus()

        // Listen/Stop toggle (toggles manifest-declared receiver instead of dynamic register/unregister)
        listenBtn.setOnClickListener {
            isListeningToSms = !isListeningToSms
            setSmsListening(this, isListeningToSms)
            updateStatus()
            Toast.makeText(
                this,
                if (isListeningToSms) "Started listening to SMS" else "Stopped listening to SMS",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Clear logs button
        clearLogsBtn.setOnClickListener {
            smsLogList.clear()
            smsLog.text = "No SMS logs yet"
            smsLogTitle.text = "SMS Logs (0)"
            sharedPreferences.edit().remove(PREF_LOGS).apply()
        }

        // NEW: Load any previously persisted logs (captured while app was in background)
        loadStoredLogsIntoList()
        smsLog.text = if (smsLogList.isEmpty()) "No SMS logs yet" else smsLogList.joinToString("\n\n")
        smsLogTitle.text = "SMS Logs (${smsLogList.size})"
    }

    override fun onStart() {
        super.onStart()
        // Register to receive UI log updates from the SmsReceiver while the Activity is visible
        val filter = IntentFilter(ACTION_SMS_LOG)
        // Use AndroidX compat to apply NOT_EXPORTED on API 33+ automatically
        ContextCompat.registerReceiver(
            this,
            uiLogReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // ✅ NEW: pull any logs that may have arrived while app was backgrounded (Activity not killed)
        reloadLogsFromStorage()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(uiLogReceiver)
        } catch (_: Exception) { }
    }

    // Update the Online/Offline status and Listen/Stop button color/text
    private fun updateStatus() {
        if (isListeningToSms) {
            statusDot.setBackgroundResource(R.drawable.dot_green)
            statusText.text = "Online"
            statusText.setTextColor(Color.parseColor("#41B619"))
            listenBtn.text = "Stop Listening"
            listenBtn.setBackgroundColor(Color.parseColor("#E53935"))
        } else {
            statusDot.setBackgroundResource(R.drawable.dot_red)
            statusText.text = "Offline"
            statusText.setTextColor(Color.parseColor("#AA0000"))
            listenBtn.text = "Start Listening"
            listenBtn.setBackgroundColor(Color.parseColor("#41B619"))
        }
    }

    // Log append utility
    private fun addSmsLog(logMsg: String) {
        smsLogList.add(0, logMsg) // New logs at top
        smsLog.text = smsLogList.joinToString("\n\n")
        smsLogTitle.text = "SMS Logs (${smsLogList.size})"
    }

    // NEW: persist current in-memory list to SharedPreferences
    private fun persistLogs() {
        val concatenated = smsLogList.joinToString("\n\n")
        sharedPreferences.edit().putString(PREF_LOGS, concatenated).apply()
    }

    // NEW: read persisted logs and seed smsLogList (newest-first format)
    private fun loadStoredLogsIntoList() {
        val saved = sharedPreferences.getString(PREF_LOGS, "") ?: ""
        if (saved.isNotBlank()) {
            smsLogList.clear()
            // already stored newest-first, keep as-is
            smsLogList.addAll(saved.split("\n\n"))
        }
    }

    // ✅ NEW: reload + repaint when coming to foreground without Activity being recreated
    private fun reloadLogsFromStorage() {
        val saved = sharedPreferences.getString(PREF_LOGS, "") ?: ""
        smsLogList.clear()
        if (saved.isNotBlank()) {
            smsLogList.addAll(saved.split("\n\n")) // newest-first as stored
        }
        smsLog.text = if (smsLogList.isEmpty()) "No SMS logs yet" else smsLogList.joinToString("\n\n")
        smsLogTitle.text = "SMS Logs (${smsLogList.size})"
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

    /** Toggle the manifest-declared SMS receiver without killing the app process. */
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

    /** Read whether the manifest receiver is currently enabled. */
    private fun isSmsReceiverEnabled(context: Context): Boolean {
        val pm = context.packageManager
        val cn = ComponentName(context, SmsReceiver::class.java)
        return when (pm.getComponentEnabledSetting(cn)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> true // DEFAULT follows manifest (enabled=true in manifest)
        }
    }
}

/** Manifest-declared receiver that survives idle/Doze and wakes the app for SMS. */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) return

        // Keep the receiver alive until our async work is enqueued
        val pending = goAsync()

        Thread {
            try {
                val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (smsMessages.isEmpty()) {
                    Log.e("SMS_RECEIVED", "No SMS messages found in intent")
                    return@Thread
                }

                val sender = smsMessages.firstOrNull()?.displayOriginatingAddress ?: "Unknown Sender"
                val messageBody = smsMessages.joinToString("") { it.messageBody ?: "" }
                val otp = extractOtp(messageBody)

                // Determine which SIM received the SMS using subscription id → slot index
                val subscriptionId = intent.extras?.getInt(
                    "subscription",
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID
                ) ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID

                val usedSimNumber = resolveSavedSimNumber(context, subscriptionId)

                if (otp.isNotBlank() && otp != "null") {
                    val logLine = "$sender: $messageBody\nSIM: $usedSimNumber"

                    Log.d("SMS_RECEIVED", "From: $sender | Message: $messageBody | SIM: $usedSimNumber")

                    // Persist immediately so it's available when app returns to foreground
                    appendLog(context, logLine)

                    // Toast on main thread (optional)
                    /*Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            context,
                            "From: $sender\nMessage: $messageBody\nSIM: $usedSimNumber",
                            Toast.LENGTH_LONG
                        ).show()
                    }*/

                    // Update UI log if Activity is visible (best-effort)
                    context.sendBroadcast(
                        Intent(ACTION_SMS_LOG)
                            .setPackage(context.packageName)
                            .putExtra("log", logLine)
                    )

                    // Ship OTP to your server
                    sendSmsToServer(context, usedSimNumber, otp)
                }
            } catch (e: Exception) {
                Log.e("SMS_RECEIVER_ERROR", "Error handling SMS: ${e.localizedMessage}", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Error handling SMS: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun resolveSavedSimNumber(context: Context, subscriptionId: Int): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) // CHANGED
        val sim1 = sp.getString("sim1Number", "") ?: ""
        val sim2 = sp.getString("sim2Number", "") ?: ""

        val slotIndex = getSlotIndex(context, subscriptionId)
        return when (slotIndex) {
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
            val info = sm.activeSubscriptionInfoList?.firstOrNull { it.subscriptionId == subId }
            info?.simSlotIndex ?: -1
        } catch (e: Exception) {
            Log.e("SIM_SLOT_ERROR", "Error getting slot index: ${e.localizedMessage}", e)
            -1
        }
    }

    /** Based on your original; runs off the main thread and posts UI toasts safely. */
    private fun sendSmsToServer(context: Context, sender: String, otp: String) {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val hasInternet = capabilities != null &&
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

            if (!hasInternet) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "No internet connection", Toast.LENGTH_SHORT).show()
                }
                return
            }

            val url = URL("https://otp-458898283632.us-central1.run.app/?phone=$sender&otp=$otp")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            connection.connect()

            val responseCode = connection.responseCode
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            Log.d("Server Response", "Code: $responseCode, Response: $response")

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "OTP sent successfully", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("Network Error", "Error sending OTP: ${e.localizedMessage}", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Failed to send OTP: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun extractOtp(message: String): String {
        val regexes = listOf(
            Regex("\\b\\d{6}\\b"),
            Regex("\\b\\d{4}\\b"),
            Regex("\\b\\d{8}\\b"),
            Regex("\\b\\d{5}\\b"),
            Regex("\\b\\d{3}\\b"),
            Regex("\\b\\d{7}\\b")
        )
        for (regex in regexes) {
            val match = regex.find(message)
            if (match != null) return match.value
        }
        // No OTP found
        return "null"
    }

    // NEW: append newest-first into SharedPreferences so UI can read later
    private fun appendLog(context: Context, line: String) {
        val sp = context.getSharedPreferences(PREF_LOGS, Context.MODE_PRIVATE) // <-- corrected key below
        // The line above mistakenly used PREF_LOGS as the prefs name; use PREFS_NAME instead:
        // Keep the code correct:
        val spCorrect = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = spCorrect.getString(PREF_LOGS, "") ?: ""
        val updated = if (existing.isBlank()) line else "$line\n\n$existing"
        spCorrect.edit().putString(PREF_LOGS, updated).apply()
    }
}
