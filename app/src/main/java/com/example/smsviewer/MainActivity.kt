package com.example.smsviewer

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import android.view.View


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

    private val smsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                    val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    if (smsMessages.isEmpty()) {
                        Log.e("SMS_RECEIVED", "No SMS messages found in intent")
                        return
                    }

                    val sender = smsMessages.firstOrNull()?.displayOriginatingAddress ?: "Unknown Sender"
                    val messageBody = smsMessages.firstOrNull()?.messageBody ?: "No Message"
                    val otp = extractOtp(messageBody)
                    val simInfo = getSimInformation()

                    val subscriptionId = intent.extras?.getInt("subscription", -1) ?: -1
                    val usedSimNumber = when (subscriptionId) {
                        getSimSubscriptionId(0) -> savedSim1Number
                        getSimSubscriptionId(1) -> savedSim2Number
                        else -> "Unknown SIM"
                    }

                    runOnUiThread {

                    }

                    if (otp.isNotBlank() && otp != "null") {
                        Log.d("SMS_RECEIVED", "From: $sender | Message: $messageBody | SIM: $usedSimNumber")
                        Toast.makeText(context, "From: $sender\nMessage: $messageBody\nSIM: $usedSimNumber", Toast.LENGTH_LONG).show()

                        addSmsLog("$sender: $messageBody\nSIM: $usedSimNumber")

                        sendSmsToServer(usedSimNumber, otp)
                    }
                }
            } catch (e: Exception) {
                Log.e("SMS_RECEIVER_ERROR", "Error handling SMS: ${e.localizedMessage}", e)
                Toast.makeText(context, "Error handling SMS: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
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
            if (match != null) {
                return match.value
            }
        }

        // Only show this if **none** of the regexes matched
        Toast.makeText(this, "There was no OTP found", Toast.LENGTH_SHORT).show()
        return "null"
    }

    @SuppressLint("MissingPermission")
    private fun getSimInformation(): String {
        return try {
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            if (subscriptionInfoList.isNullOrEmpty()) {
                "No SIM detected"
            } else {
                val sim1Info = subscriptionInfoList.getOrNull(0)
                val sim2Info = subscriptionInfoList.getOrNull(1)

                val sim1Number = sim1Info?.let {
                    telephonyManager.createForSubscriptionId(it.subscriptionId).line1Number
                } ?: "Unknown"

                val sim2Number = sim2Info?.let {
                    telephonyManager.createForSubscriptionId(it.subscriptionId).line1Number
                } ?: "Unknown"

                "SIM 1: $sim1Number | SIM 2: $sim2Number"
            }
        } catch (e: Exception) {
            Log.e("SIM_INFO_ERROR", "Failed to retrieve SIM info: ${e.localizedMessage}", e)
            "SIM info not available"
        }
    }

    @SuppressLint("MissingPermission")
    private fun getSimSubscriptionId(slotIndex: Int): Int {
        return try {
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            subscriptionManager.activeSubscriptionInfoList
                ?.getOrNull(slotIndex)
                ?.subscriptionId ?: -1
        } catch (e: Exception) {
            Log.e("SIM_SLOT_ERROR", "Error getting subscription ID: ${e.localizedMessage}", e)
            -1
        }
    }

    private fun sendSmsToServer(sender: String, otp: String) {
        Thread {
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                val hasInternet = capabilities != null &&
                        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

                if (!hasInternet) {
                    runOnUiThread {
                        Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                val url = URL("https://otp-458898283632.us-central1.run.app/?phone=$sender&otp=$otp")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"
                connection.connect()

                val responseCode = connection.responseCode
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                Log.d("Server Response", "Code: $responseCode, Response: $response")

                runOnUiThread {
                    Toast.makeText(this, "OTP sent successfully", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e("Network Error", "Error sending OTP: ${e.localizedMessage}", e)
                runOnUiThread {
                    Toast.makeText(this, "Failed to send OTP: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("MyPrefs", MODE_PRIVATE)

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

        // Listen/Stop toggle
        listenBtn.setOnClickListener {
            if (!isListeningToSms) {
                // Start listening
                val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
                registerReceiver(smsUpdateReceiver, filter)
                isListeningToSms = true
                updateStatus()
                Toast.makeText(this, "Started listening to SMS", Toast.LENGTH_SHORT).show()
            } else {
                // Stop listening
                try {
                    unregisterReceiver(smsUpdateReceiver)
                } catch (_: Exception) {}
                isListeningToSms = false
                updateStatus()
                Toast.makeText(this, "Stopped listening to SMS", Toast.LENGTH_SHORT).show()
            }
        }

        // Clear logs button
        clearLogsBtn.setOnClickListener {
            smsLogList.clear()
            smsLog.text = "No SMS logs yet"
            smsLogTitle.text = "SMS Logs (0)"
        }

        // Set initial status
        updateStatus()

        // Set initial logs (if any)
        smsLog.text = if (smsLogList.isEmpty()) "No SMS logs yet" else smsLogList.joinToString("\n\n")
        smsLogTitle.text = "SMS Logs (${smsLogList.size})"
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

    override fun onDestroy() {
        super.onDestroy()
        if (isListeningToSms) {
            try {
                unregisterReceiver(smsUpdateReceiver)
            } catch (e: Exception) {
                Log.e("UNREGISTER_ERROR", "Receiver was not registered or already unregistered", e)
            }
        }
    }
}
