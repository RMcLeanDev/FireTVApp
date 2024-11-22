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
import android.content.pm.PackageManager
import java.util.UUID
import java.io.File

class MainFragment : Fragment() {

    private lateinit var mediaDatabase: DatabaseReference
    private lateinit var devicesDatabase: DatabaseReference
    private lateinit var pairingCodeListener: ValueEventListener
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var deviceSerial: String
    private var currentIndex = 0
    private var currentPlaylistId: String? = null
    private var mediaList = mutableListOf<MediaItem>()
    private var rotationInProgress: Boolean = false
    private var pendingMediaList = mutableListOf<MediaItem>()
    private val networkStatusHandler = Handler(Looper.getMainLooper())
    private val REQUEST_READ_PHONE_STATE = 1001
    private val networkStatusRunnable: Runnable = object : Runnable {
        override fun run() {
            checkNetworkStatus()
            networkStatusHandler.postDelayed(this, 2000) // Check every 2 seconds
        }
    }
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
        requestSerialPermission()

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

    private fun requestSerialPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (requireContext().checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), REQUEST_READ_PHONE_STATE)
            } else {
                initializeDeviceSerial() // Permission already granted
            }
        } else {
            initializeDeviceSerial() // Permissions not required for versions below Android 6.0
        }
    }

    private fun initializeDeviceSerial() {
        deviceSerial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial() // Requires permission
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL // Deprecated
            }
        } catch (e: SecurityException) {
            Log.e("DeviceSerial", "Permission denied for serial: ${e.message}")
            getFallbackSerial() // Fallback to a secure identifier
        } catch (e: Exception) {
            Log.e("DeviceSerial", "Error retrieving serial: ${e.message}")
            getFallbackSerial() // Fallback to a secure identifier
        }
        Log.d("DeviceSerial", "Device serial: $deviceSerial")
    }

    private fun getFallbackSerial(): String {
        val fallback = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)
        Log.d("FallbackSerial", "Fallback serial used: $fallback")
        return fallback
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_READ_PHONE_STATE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeDeviceSerial() // Permission granted
            } else {
                deviceSerial = getFallbackSerial() // Use fallback directly
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun getOrGeneratePersistentIdentifier(): String {
        val sharedPreferences = requireContext().getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        val savedIdentifier = sharedPreferences.getString("device_persistent_id", null)

        return if (savedIdentifier != null) {
            Log.d("DeviceIdentifier", "Using saved persistent identifier: $savedIdentifier")
            savedIdentifier
        } else {
            // Generate a new UUID and save it
            val newIdentifier = java.util.UUID.randomUUID().toString()
            sharedPreferences.edit().putString("device_persistent_id", newIdentifier).apply()
            Log.d("DeviceIdentifier", "Generated new persistent identifier: $newIdentifier")
            newIdentifier
        }
    }

    private fun getOrGenerateDeviceIdentifier(): String {
        val sharedPreferences = requireContext().getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        return sharedPreferences.getString("device_uuid", null)
            ?: UUID.randomUUID().toString().also { uuid ->
                sharedPreferences.edit().putString("device_uuid", uuid).apply()
                Log.d("DeviceIdentifier", "Generated new UUID: $uuid")
            }
    }

    private fun checkNetworkStatus() {
        val isOnline = requireContext().isNetworkAvailable()
        val statusText = if (isOnline) "Online" else "Offline"

        if (networkStatusTextView.text != statusText) {
            networkStatusTextView.text = statusText
            Log.d("NetworkStatus", "Network status updated to: $statusText")
        }
    }

    private fun Context.isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo != null && networkInfo.isConnected
        }
    }

    override fun onResume() {
        super.onResume()
        networkStatusHandler.post(networkStatusRunnable) // Start periodic network checks
    }

    override fun onPause() {
        super.onPause()
        networkStatusHandler.removeCallbacks(networkStatusRunnable) // Stop periodic network checks
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        if (::pairingCodeListener.isInitialized) {
            Firebase.database.reference.child("pairings").child(pairingCode).removeEventListener(pairingCodeListener)
        }
    }

    private fun fetchOrGeneratePairingCode(view: View) {
        val sharedPreferences = requireContext().getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
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

        // Hide spinner here to avoid infinite loading
        loadingSpinner.visibility = View.GONE
    }


    private fun listenForPairingCodeChanges(pairingCode: String, view: View) {
        val pairingRef = Firebase.database.reference.child("pairings").child(pairingCode)
        pairingCodeListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val pairingCodeTextView = view.findViewById<TextView>(R.id.pairing_code_text)

                if (snapshot.exists()) {
                    val playlistId = snapshot.child("playlistId").value as? String
                    if (playlistId != null) {
                        Log.d("Pairing", "Playlist ID updated: $playlistId")
                        pairingCodeTextView.visibility = View.GONE // Hide pairing code
                        pauseAndSwitchPlaylist(playlistId, view)
                    } else {
                        Log.d("Pairing", "No playlist assigned yet.")
                        showPlaceholder(view)
                    }
                } else {
                    Log.d("Pairing", "Pairing code removed. Displaying pairing code and clearing cache.")
                    displayPairingCode(pairingCode) // Display pairing code for re-pairing
                    clearCachedMediaInBackground() // Clear media in the background
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Firebase", "Failed to listen for pairing code changes: ${error.message}")
            }
        }
        pairingRef.addValueEventListener(pairingCodeListener)
    }

    private fun clearCachedMediaInBackground() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("Cache", "Clearing cached media in the background.")
                AppDatabase.getDatabase(requireContext()).mediaItemDao().clearMediaItems()
                Log.d("Cache", "Successfully cleared cached media.")
            } catch (e: Exception) {
                Log.e("Cache", "Error clearing cached media: ${e.message}")
            }
        }
    }

    private fun displayPairingCode(pairingCode: String) {
        pairingCodeTextView.text = "Pairing Code: $pairingCode"
        pairingCodeTextView.visibility = View.VISIBLE

        // Hide the spinner explicitly
        loadingSpinner.visibility = View.GONE

        // Hide any media UI elements
        val mediaTextView = view?.findViewById<TextView>(R.id.media_text)
        val mediaImageView = view?.findViewById<ImageView>(R.id.media_image)
        val mediaVideoView = view?.findViewById<VideoView>(R.id.media_video)

        mediaTextView?.visibility = View.GONE
        mediaImageView?.visibility = View.GONE
        mediaVideoView?.visibility = View.GONE

        Log.d("Pairing", "Displaying pairing code for re-pairing.")
    }


    private fun pauseAndSwitchPlaylist(playlistId: String, view: View) {
        handler.removeCallbacksAndMessages(null)
        rotationInProgress = false
        loadPlaylist(playlistId, view)
    }

    private fun loadPlaylist(playlistId: String, view: View) {
        currentPlaylistId = playlistId // Set currentPlaylistId
        val loadingSpinner = view.findViewById<ProgressBar>(R.id.loading_spinner)

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
                displayMediaItem(view)
            } else {
                Log.d("Playlist", "No items found in playlist: $playlistId")
                showPlaceholder(view)
            }

            // Start listening for playlist updates
            if (!playlistId.isNullOrEmpty()) {
                setupMediaListener(view)
            } else {
                Log.e("PlaylistError", "Invalid playlistId: $playlistId")
            }

            // Hide the spinner after processing
            loadingSpinner.visibility = View.GONE
        }.addOnFailureListener { error ->
            Log.e("Playlist", "Failed to load playlist: ${error.message}")
            showPlaceholder(view)
            loadingSpinner.visibility = View.GONE
        }
    }

    private fun displayMediaItem(view: View) {
        val mediaTextView = view.findViewById<TextView>(R.id.media_text)
        val mediaImageView = view.findViewById<ImageView>(R.id.media_image)
        val mediaVideoView = view.findViewById<VideoView>(R.id.media_video)
        mediaTextView.visibility = View.GONE
        if (mediaList.isEmpty()) {
            showPlaceholder(view)
            return
        }

        if (rotationInProgress) return

        rotationInProgress = true
        val mediaItem = mediaList[currentIndex]

        // Update media list at the start of a new cycle
        if (currentIndex == 0 && pendingMediaList.isNotEmpty()) {
            mediaList = pendingMediaList.toMutableList()
            Log.d("MediaUpdate", "Media list updated from pending list.")
        }

        // Load animations
        val fadeIn = android.view.animation.AnimationUtils.loadAnimation(context, R.anim.fade_in)
        val fadeOut = android.view.animation.AnimationUtils.loadAnimation(context, R.anim.fade_out)

        when (mediaItem.type) {
            "image" -> {
                mediaVideoView.visibility = View.GONE
                mediaImageView.visibility = View.VISIBLE
                mediaImageView.startAnimation(fadeIn)

                Glide.with(this)
                    .load(mediaItem.url)
                    .error(R.drawable.error_placeholder)
                    .into(mediaImageView)

                handler.postDelayed({
                    mediaImageView.startAnimation(fadeOut)
                    handler.postDelayed({
                        rotationInProgress = false
                        currentIndex = (currentIndex + 1) % mediaList.size
                        displayMediaItem(view)
                    }, fadeOut.duration)
                }, mediaItem.duration)
            }

            "video" -> {
                mediaImageView.visibility = View.GONE
                mediaVideoView.visibility = View.VISIBLE
                mediaVideoView.setVideoPath(mediaItem.url)
                mediaVideoView.setOnPreparedListener {
                    mediaVideoView.start()
                    mediaVideoView.startAnimation(fadeIn)
                }

                mediaVideoView.setOnCompletionListener {
                    mediaVideoView.startAnimation(fadeOut)
                    handler.postDelayed({
                        rotationInProgress = false
                        currentIndex = (currentIndex + 1) % mediaList.size
                        displayMediaItem(view)
                    }, fadeOut.duration)
                }
            }
        }
    }

    private fun setupMediaListener(view: View) {
        currentPlaylistId?.let { playlistId ->
            val playlistRef = Firebase.database.reference.child("playlists").child(playlistId)
            playlistRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val updatedMediaList = mutableListOf<MediaItem>()
                    snapshot.child("items").children.forEach { item ->
                        val url = item.child("url").value as? String
                        val type = item.child("type").value as? String
                        val duration = item.child("duration").value as? Long ?: 3000L
                        if (url != null && type != null) {
                            updatedMediaList.add(MediaItem(url, type, duration))
                        }
                    }

                    if (updatedMediaList.isNotEmpty()) {
                        pendingMediaList = updatedMediaList // Defer updates
                        Log.d("FirebaseListener", "Pending media list updated: $pendingMediaList")
                    }

                    // Sync with Room database cache
                    CoroutineScope(Dispatchers.IO).launch {
                        AppDatabase.getDatabase(requireContext()).mediaItemDao().clearAndInsert(
                            updatedMediaList.map { MediaItemEntity(it.url, it.type, it.duration) }
                        )
                        Log.d("CacheSync", "Room database cache synced.")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("FirebaseListener", "Failed to fetch playlist updates: ${error.message}")
                }
            })
        } ?: Log.e("SetupMediaListener", "currentPlaylistId is null, skipping listener setup.")
    }

    private fun showPlaceholder(view: View) {
        val mediaTextView = view.findViewById<TextView>(R.id.media_text)
        val mediaImageView = view.findViewById<ImageView>(R.id.media_image)
        val mediaVideoView = view.findViewById<VideoView>(R.id.media_video)

        mediaImageView.visibility = View.GONE
        mediaVideoView.visibility = View.GONE
        mediaTextView.visibility = View.VISIBLE
        mediaTextView.text = "No playlist assigned or available."
    }

    private fun savePairingCodeLocally(pairingCode: String) {
        val sharedPreferences = requireContext().getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
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
}
