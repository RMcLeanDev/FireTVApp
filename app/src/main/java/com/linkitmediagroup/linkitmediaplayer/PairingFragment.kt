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
    private lateinit var devicesDatabase: DatabaseReference
    private lateinit var pairingCode: String
    private lateinit var deviceSerial: String

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_pairing, container, false)

        pairingCodeTextView = view.findViewById(R.id.pairing_code_text)
        devicesDatabase = Firebase.database.reference.child("devices")

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
        val deviceRef = devicesDatabase.child(deviceSerial)

        deviceRef.child("pairingCode").get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                pairingCode = snapshot.value as String
                Log.d(AppConstants.LOG_TAG, "Pairing code fetched: $pairingCode")
            } else {
                pairingCode = generatePairingCode()
                Log.d(AppConstants.LOG_TAG, "Generated new pairing code: $pairingCode")
                deviceRef.child("pairingCode").setValue(pairingCode)
            }
            displayPairingCode(pairingCode)
        }.addOnFailureListener { e ->
            Log.e(AppConstants.LOG_TAG, "Failed to fetch pairing code: ${e.message}")
            pairingCode = generatePairingCode()
            deviceRef.child("pairingCode").setValue(pairingCode)
            displayPairingCode(pairingCode)
        }
    }

    private fun displayPairingCode(code: String) {
        Log.d(AppConstants.LOG_TAG, "Displaying pairing code: $code")
        pairingCodeTextView.text = "Pairing Code: $code"
    }

    private fun generatePairingCode(): String {
        val allowedChars = ('A'..'Z') + ('0'..'9')
        return (1..8).map { allowedChars.random() }.joinToString("")
    }
}
