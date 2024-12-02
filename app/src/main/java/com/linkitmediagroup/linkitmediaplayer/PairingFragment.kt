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
import android.widget.ProgressBar
import com.linkitmediagroup.linkitmediaplayer.AppConstants

class PairingFragment : Fragment() {

    private lateinit var pairingCodeTextView: TextView
    private lateinit var screensDatabase: DatabaseReference
    private lateinit var pairingCode: String
    private lateinit var deviceSerial: String
    private lateinit var pairingListener: ValueEventListener
    private lateinit var loadingSpinner: ProgressBar
    val LOG_TAG = AppConstants.LOG_TAG

    companion object {
        const val SCREENS_PATH = "screens"
        const val FIELD_PAIRED = "paired"
        const val FIELD_PLAYLIST = "currentPlaylistAssigned"
        const val FIELD_PAIRING_CODE = "pairingCode"
        const val FIELD_UUID = "uuid"
        const val FIELD_LAST_HEARTBEAT = "lastHeartbeat"
        const val FIELD_CREATION_DATE = "creationDate"
        const val FIELD_STATUS = "status"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_pairing, container, false)
        pairingCodeTextView = view.findViewById(R.id.pairing_code_text)
        screensDatabase = Firebase.database.reference.child(SCREENS_PATH)
        deviceSerial = getDeviceSerial()

        fetchOrGeneratePairingCode()
        listenForPairingUpdates()

        return view
    }

    private fun getDeviceSerial(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
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
                pairingCode = snapshot.child(FIELD_PAIRING_CODE).value as String
            } else {
                pairingCode = generatePairingCode()
                Log.i(LOG_TAG, "Generated new pairing code: $pairingCode")

                val deviceData = mapOf(
                    FIELD_UUID to deviceSerial,
                    FIELD_PAIRING_CODE to pairingCode,
                    FIELD_LAST_HEARTBEAT to System.currentTimeMillis(),
                    FIELD_CREATION_DATE to getCurrentDate(),
                    FIELD_STATUS to "offline",
                    FIELD_PAIRED to false
                )
                deviceRef.setValue(deviceData).addOnSuccessListener {
                    Log.i(LOG_TAG, "Device data saved successfully")
                    listenForPairingUpdates()
                }.addOnFailureListener { error ->
                    Log.e(LOG_TAG, "Failed to save device data: ${error.message}")
                }
            }
            displayPairingCode(pairingCode)
        }.addOnFailureListener { error ->
            Log.e(LOG_TAG, "Failed to fetch device data: ${error.message}")
            pairingCodeTextView.text = "Error: Unable to retrieve pairing code."
        }
    }

    private fun navigateToMainFragment() {
        val activity = requireActivity() as MainActivity
        activity.runOnUiThread {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MainFragment())
                .commit()
        }
    }

    private fun listenForPairingUpdates() {
        val deviceRef = screensDatabase.child(deviceSerial)

        pairingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val pairingCodeFromFirebase = snapshot.child(FIELD_PAIRING_CODE).value as? String
                    if (!pairingCodeFromFirebase.isNullOrEmpty() && pairingCodeTextView.text.isNullOrEmpty()) {
                        displayPairingCode(pairingCodeFromFirebase)
                    }

                    val paired = snapshot.child(FIELD_PAIRED).value as? Boolean ?: false
                    val playlistId = snapshot.child(FIELD_PLAYLIST).value as? String

                    when {
                        paired && playlistId != null -> {
                            Log.i(LOG_TAG, "Screen is paired and playlist is assigned. Navigating to MainFragment.")
                            pairingCodeTextView.text = "Loading playlist..."
                            navigateToMainFragment()
                        }
                        paired -> {
                            pairingCodeTextView.text = "Waiting for playlist assignment..."
                            Log.i(LOG_TAG, "Screen is paired but no playlist assigned.")
                        }
                    }
                } else {
                    Log.e(LOG_TAG, "Snapshot does not exist for device serial.")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(LOG_TAG, "Failed to listen for pairing updates: ${error.message}")
                pairingCodeTextView.text = "Error: Unable to fetch pairing updates."
            }
        }
        deviceRef.addValueEventListener(pairingListener)
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
        val allowedChars = ('A'..'Z') + ('1'..'9')
        return (1..8).map { allowedChars.random() }.joinToString("")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        screensDatabase.child(deviceSerial).removeEventListener(pairingListener)
    }
}