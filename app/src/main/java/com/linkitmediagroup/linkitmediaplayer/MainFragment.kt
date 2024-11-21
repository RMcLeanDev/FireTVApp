package com.linkitmediagroup.linkitmediaplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.firebase.database.*
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.ktx.database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainFragment : Fragment() {

    private lateinit var mediaDatabase: DatabaseReference
    private lateinit var devicesDatabase: DatabaseReference
    private lateinit var pairingCodeListener: ValueEventListener
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var deviceSerial: String
    private var currentIndex = 0
    private var mediaList = mutableListOf<MediaItem>()

    private lateinit var networkStatusTextView: TextView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var pairingCodeTextView: TextView
    private lateinit var pairingCode: String

    data class MediaItem(val url: String, val type: String, val duration: Long = 3000L)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_main, container, false)

        // Initialize Firebase Realtime Database references
        mediaDatabase = Firebase.database.reference.child("media")
        devicesDatabase = Firebase.database.reference.child("devices")

        // Initialize device serial
        deviceSerial =
            Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)

        // Reference to the network status and pairing code text views
        networkStatusTextView = view.findViewById(R.id.network_status_text)
        pairingCodeTextView = view.findViewById(R.id.pairing_code_text)

        // Initialize loading spinner
        loadingSpinner = view.findViewById(R.id.loading_spinner)
        loadingSpinner.visibility = View.VISIBLE

        // Fetch or generate pairing code
        fetchOrGeneratePairingCode(view)

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        if (::pairingCodeListener.isInitialized) {
            Firebase.database.reference.child("pairings").child(pairingCode)
                .removeEventListener(pairingCodeListener)
        }
    }

    private fun fetchOrGeneratePairingCode(view: View) {
        val sharedPreferences =
            requireContext().getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        val savedPairingCode = sharedPreferences.getString("pairingCode", null)

        if (savedPairingCode != null) {
            pairingCode = savedPairingCode
            Log.d("Pairing", "Loaded pairing code from SharedPreferences: $pairingCode")
            displayPairingCode(pairingCode)
            listenForPairingCodeChanges(pairingCode, view)
        } else {
            val deviceRef = devicesDatabase.child(deviceSerial)
            deviceRef.child("pairingCode").get().addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    pairingCode = snapshot.value as String
                    Log.d("Pairing", "Loaded pairing code from Firebase: $pairingCode")
                    savePairingCodeLocally(pairingCode)
                    displayPairingCode(pairingCode)
                    listenForPairingCodeChanges(pairingCode, view)
                } else {
                    pairingCode = generatePairingCode()
                    savePairingCodeToFirebase(pairingCode)
                    savePairingCodeLocally(pairingCode)
                    displayPairingCode(pairingCode)
                    listenForPairingCodeChanges(pairingCode, view)
                }
            }.addOnFailureListener {
                pairingCode = generatePairingCode()
                savePairingCodeToFirebase(pairingCode)
                savePairingCodeLocally(pairingCode)
                displayPairingCode(pairingCode)
                listenForPairingCodeChanges(pairingCode, view)
            }
        }
    }

    private fun listenForPairingCodeChanges(pairingCode: String, view: View) {
        val pairingRef = Firebase.database.reference.child("pairings").child(pairingCode)
        pairingCodeListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val playlistId = snapshot.child("playlistId").value as? String
                    if (playlistId != null) {
                        Log.d("Pairing", "Playlist ID updated: $playlistId")
                        loadPlaylist(playlistId, view)
                    } else {
                        Log.d("Pairing", "No playlist assigned yet.")
                    }
                } else {
                    Log.d("Pairing", "Pairing code not found in Firebase.")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Firebase", "Failed to listen for pairing code changes: ${error.message}")
            }
        }
        pairingRef.addValueEventListener(pairingCodeListener)
    }

    private fun savePairingCodeLocally(pairingCode: String) {
        val sharedPreferences =
            requireContext().getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().putString("pairingCode", pairingCode).apply()
        Log.d("Pairing", "Pairing code saved locally: $pairingCode")
    }

    private fun generatePairingCode(): String {
        val allowedChars = ('A'..'Z') + ('0'..'9')
        return (1..8).map { allowedChars.random() }.joinToString("")
    }

    private fun savePairingCodeToFirebase(pairingCode: String) {
        val deviceData = hashMapOf(
            "UUID" to deviceSerial,
            "pairingCode" to pairingCode,
            "deviceName" to (Build.MODEL ?: "Unknown Device"),
            "lastHeartBeat" to System.currentTimeMillis()
        )

        devicesDatabase.child(deviceSerial).setValue(deviceData)
            .addOnSuccessListener {
                Log.d("Pairing", "Pairing code saved to Firebase: $pairingCode")
            }
            .addOnFailureListener { error ->
                Log.e("Pairing", "Failed to save pairing code to Firebase: ${error.message}")
            }
    }

    private fun displayPairingCode(pairingCode: String) {
        pairingCodeTextView.text = "Pairing Code: $pairingCode"
        loadingSpinner.visibility = View.GONE
    }

    private fun loadPlaylist(playlistId: String, view: View) {
        val playlistRef = Firebase.database.reference.child("playlists").child(playlistId)
        playlistRef.get().addOnSuccessListener { dataSnapshot ->
            val newMediaList = mutableListOf<MediaItem>()
            dataSnapshot.child("items").children.forEach { snapshot ->
                val url = snapshot.child("url").value as? String
                val type = snapshot.child("type").value as? String
                val duration = snapshot.child("duration").value as? Long ?: 3000L
                if (url != null && type != null) {
                    newMediaList.add(MediaItem(url, type, duration))
                }
            }

            if (newMediaList.isNotEmpty()) {
                mediaList = newMediaList
                currentIndex = 0
                if (isAdded) displayMediaItem(view)
            }
        }.addOnFailureListener { error ->
            Log.e("Playlist", "Failed to load playlist: ${error.message}")
        }
    }

    private fun displayMediaItem(view: View) {
        if (mediaList.isEmpty()) return  // Exit if there are no items to display

        val mediaItem = mediaList[currentIndex]
        val mediaImageView = view.findViewById<ImageView>(R.id.media_image)
        val mediaVideoView = view.findViewById<VideoView>(R.id.media_video)

        when (mediaItem.type) {
            "image" -> {
                mediaVideoView.visibility = View.GONE
                mediaImageView.visibility = View.VISIBLE

                Glide.with(this)
                    .load(mediaItem.url)
                    .error(R.drawable.error_placeholder)
                    .into(mediaImageView)

                // Ensure the delay matches the media item duration
                handler.postDelayed({
                    currentIndex = (currentIndex + 1) % mediaList.size
                    displayMediaItem(view)
                }, mediaItem.duration)
            }

            "video" -> {
                mediaImageView.visibility = View.GONE
                mediaVideoView.visibility = View.VISIBLE
                mediaVideoView.setVideoPath(mediaItem.url)
                mediaVideoView.setOnPreparedListener {
                    mediaVideoView.start()
                }
                mediaVideoView.setOnCompletionListener {
                    currentIndex = (currentIndex + 1) % mediaList.size
                    displayMediaItem(view)
                }
            }
        }
    }
}