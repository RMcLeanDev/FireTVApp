package com.linkitmediagroup.linkitmediaplayer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.firebase.database.*
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.ktx.database
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.linkitmediagroup.linkitmediaplayer.AppConstants

class PairingFragment : Fragment() {

    private lateinit var pairingCodeTextView: TextView
    private lateinit var screensDatabase: DatabaseReference
    private lateinit var pairingCode: String
    private lateinit var deviceSerial: String
    val LOG_TAG = AppConstants.LOG_TAG
    
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_pairing, container, false)

        pairingCodeTextView = view.findViewById(R.id.pairing_code_text)
        screensDatabase = Firebase.database.reference.child("screens")

        // Initialize the device serial
        deviceSerial = getDeviceSerial()

        // Fetch or generate pairing code
        fetchOrGeneratePairingCode()

        return view
    }

    private fun getDeviceSerial(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial() // Requires permission
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL // Deprecated in Android O+
            }
        } catch (e: SecurityException) {
            Log.e("PairingFragment", "Permission denied for serial: ${e.message}")
            // Fallback to a secure identifier
            Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            Log.e("PairingFragment", "Error retrieving serial: ${e.message}")
            // Fallback to a secure identifier
            Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)
        }
    }

    private fun fetchOrGeneratePairingCode() {
        val deviceRef = screensDatabase.child(deviceSerial)

        deviceRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                pairingCode = snapshot.child("pairingCode").value as String
                Log.i(LOG_TAG, "Pairing code retrieved: $pairingCode")
            } else {
                pairingCode = generatePairingCode()
                Log.i(LOG_TAG, "Generated new pairing code: $pairingCode")

                // Save the new pairing code along with the updated structure
                val deviceData = mapOf(
                    "uuid" to deviceSerial,
                    "pairingCode" to pairingCode,
                    "lastHeartbeat" to System.currentTimeMillis(),
                    "creationDate" to getCurrentDate(),
                    "status" to "offline" // Default to offline until first heartbeat
                )
                deviceRef.setValue(deviceData).addOnSuccessListener {
                    Log.i(LOG_TAG, "Device data saved successfully")
                }.addOnFailureListener { error ->
                    Log.e(LOG_TAG, "Failed to save device data: ${error.message}")
                }
            }
            displayPairingCode(pairingCode)
        }.addOnFailureListener { error ->
            Log.e(LOG_TAG, "Failed to fetch device data: ${error.message}")
            pairingCode = generatePairingCode()

            // Save the new pairing code along with the updated structure
            val deviceData = mapOf(
                "uuid" to deviceSerial,
                "pairingCode" to pairingCode,
                "lastHeartbeat" to System.currentTimeMillis(),
                "creationDate" to getCurrentDate(),
                "status" to "offline"
            )
            deviceRef.setValue(deviceData).addOnSuccessListener {
                Log.i(LOG_TAG, "Device data saved successfully after failure")
            }.addOnFailureListener { saveError ->
                Log.e(LOG_TAG, "Failed to save device data after error: ${saveError.message}")
            }
            displayPairingCode(pairingCode)
        }
    }

    private fun getCurrentDate(): String {
        val sdf = java.text.SimpleDateFormat("MM-dd-yyyy", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    private fun displayPairingCode(code: String) {
        pairingCodeTextView.text = "Pairing Code: $code"
        Log.i(LOG_TAG, "Displaying pairing code: $code")
    }

    private fun generatePairingCode(): String {
        val allowedChars = ('A'..'Z') + ('0'..'9')
        return (1..8).map { allowedChars.random() }.joinToString("")
    }
}
