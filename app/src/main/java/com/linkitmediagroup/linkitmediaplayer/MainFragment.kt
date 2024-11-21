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
    private lateinit var firebaseListener: ValueEventListener
    private lateinit var assignmentListener: ValueEventListener
    private val handler = Handler(Looper.getMainLooper())
    private val networkStatusHandler = Handler(Looper.getMainLooper())
    private lateinit var deviceSerial: String
    private val networkStatusRunnable: Runnable = object : Runnable {
        override fun run() {
            checkNetworkStatus()
            networkStatusHandler.postDelayed(this, 2000) // Check every 2 seconds
        }
    }

    private var currentIndex = 0
    private var mediaList = mutableListOf<MediaItem>()
    private var pendingMediaList = mutableListOf<MediaItem>()
    private var rotationInProgress = false

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
        deviceSerial = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)

        // Reference to the network status and pairing code text views
        networkStatusTextView = view.findViewById(R.id.network_status_text)
        pairingCodeTextView = view.findViewById(R.id.pairing_code_text)

        // Initialize loading spinner
        loadingSpinner = view.findViewById(R.id.loading_spinner)
        loadingSpinner.visibility = View.VISIBLE

        // Fetch or generate pairing code
        fetchOrGeneratePairingCode()

        // Load initial media content
        loadInitialMediaContent(view)

        return view
    }

    override fun onResume() {
        super.onResume()
        networkStatusHandler.post(networkStatusRunnable) // Start periodic network checks
    }

    override fun onPause() {
        super.onPause()
        networkStatusHandler.removeCallbacks(networkStatusRunnable) // Stop periodic checks
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        networkStatusHandler.removeCallbacks(networkStatusRunnable)
        if (::firebaseListener.isInitialized) {
            mediaDatabase.removeEventListener(firebaseListener)
        }
        if (::assignmentListener.isInitialized) {
            Firebase.database.reference.child("pairings").child(pairingCode).child("playlistId")
                .removeEventListener(assignmentListener)
        }
    }

    private fun fetchOrGeneratePairingCode() {
        val sharedPreferences = requireContext().getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        val savedPairingCode = sharedPreferences.getString("pairingCode", null)

        if (savedPairingCode != null) {
            // Use locally saved pairing code
            pairingCode = savedPairingCode
            Log.d("Pairing", "Loaded pairing code from SharedPreferences: $pairingCode")
            displayPairingCode(pairingCode)
        } else {
            // Check Firebase for an existing pairing code
            val deviceRef = devicesDatabase.child(deviceSerial)
            deviceRef.child("pairingCode").get().addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    // Pairing code exists in Firebase
                    pairingCode = snapshot.value as String
                    Log.d("Pairing", "Loaded pairing code from Firebase: $pairingCode")
                    savePairingCodeLocally(pairingCode)
                    displayPairingCode(pairingCode)
                } else {
                    // Generate a new pairing code
                    pairingCode = generatePairingCode()
                    Log.d("Pairing", "Generated new pairing code: $pairingCode")
                    savePairingCodeToFirebase(pairingCode)
                    savePairingCodeLocally(pairingCode)
                    displayPairingCode(pairingCode)
                }
            }.addOnFailureListener {
                Log.e("Pairing", "Failed to fetch pairing code from Firebase: ${it.message}")
                // Fallback: Generate a new pairing code
                pairingCode = generatePairingCode()
                savePairingCodeToFirebase(pairingCode)
                savePairingCodeLocally(pairingCode)
                displayPairingCode(pairingCode)
            }
        }
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


    private fun displayPairingCode(pairingCode: String) {
        pairingCodeTextView.text = "Pairing Code: $pairingCode"
        loadingSpinner.visibility = View.GONE
    }

    private fun loadPlaylist(playlistId: String) {
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
                if (isAdded) displayMediaItem(requireView())
            }
        }.addOnFailureListener { error ->
            Log.e("Pairing", "Failed to load playlist: ${error.message}")
        }
    }

    private fun checkNetworkStatus() {
        val isOnline = requireContext().isNetworkAvailable()
        val status = if (isOnline) "Online" else "Offline"
        if (networkStatusTextView.text != status) {
            networkStatusTextView.text = status
            Log.d("MainFragment", "Network status updated to: $status")
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

    private fun loadInitialMediaContent(view: View) {
        if (requireContext().isNetworkAvailable()) {
            // Online: Fetch from Firebase and cache to Room
            mediaDatabase.get().addOnSuccessListener { dataSnapshot ->
                mediaList.clear()
                val newMediaList = mutableListOf<MediaItem>()

                dataSnapshot.children.forEach { snapshot ->
                    val url = snapshot.child("url").value as? String
                    val type = snapshot.child("type").value as? String
                    val duration = snapshot.child("duration").value as? Long ?: 3000L

                    if (url != null && type != null) {
                        val mediaItem = MediaItem(url, type, duration)
                        newMediaList.add(mediaItem)
                    }
                }

                if (newMediaList.isNotEmpty()) {
                    mediaList.addAll(newMediaList)
                    CoroutineScope(Dispatchers.IO).launch {
                        AppDatabase.getDatabase(requireContext()).mediaItemDao().clearAndInsert(newMediaList.map {
                            MediaItemEntity(it.url, it.type, it.duration)
                        })
                    }
                }

                if (mediaList.isNotEmpty()) displayMediaItem(view)
                setupMediaListener(view)
                loadingSpinner.visibility = View.GONE

            }.addOnFailureListener {
                val mediaTextView = view.findViewById<TextView>(R.id.media_text)
                mediaTextView?.text = getString(R.string.failed_to_load_media)
                loadingSpinner.visibility = View.GONE
            }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                val cachedItems = AppDatabase.getDatabase(requireContext()).mediaItemDao().getAllMediaItems()
                mediaList = cachedItems.map { MediaItem(it.url, it.type, it.duration) }.toMutableList()
                if (mediaList.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        displayMediaItem(view)
                        loadingSpinner.visibility = View.GONE
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        val mediaTextView = view.findViewById<TextView>(R.id.media_text)
                        mediaTextView?.text = getString(R.string.failed_to_load_media)
                        loadingSpinner.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun setupMediaListener(view: View) {
        firebaseListener = object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                pendingMediaList.clear()

                dataSnapshot.children.forEach { snapshot ->
                    val url = snapshot.child("url").value as? String
                    val type = snapshot.child("type").value as? String
                    val duration = snapshot.child("duration").value as? Long ?: 3000L

                    if (url != null && type != null) {
                        pendingMediaList.add(MediaItem(url, type, duration))
                    }
                }

                if (!rotationInProgress) {
                    mediaList = pendingMediaList.toMutableList()
                    currentIndex = 0
                    displayMediaItem(view)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                val mediaTextView = view.findViewById<TextView>(R.id.media_text)
                mediaTextView?.text = getString(R.string.failed_to_load_media)
            }
        }

        mediaDatabase.addValueEventListener(firebaseListener)
    }

    private fun displayMediaItem(view: View) {
        if (mediaList.isEmpty()) return  // Exit if there are no items to display

        rotationInProgress = true
        val mediaItem = mediaList[currentIndex]
        val mediaTextView = view.findViewById<TextView>(R.id.media_text)
        val mediaImageView = view.findViewById<ImageView>(R.id.media_image)
        val mediaVideoView = view.findViewById<VideoView>(R.id.media_video)

        // Load animations
        val fadeIn = android.view.animation.AnimationUtils.loadAnimation(context, R.anim.fade_in)
        val fadeOut = android.view.animation.AnimationUtils.loadAnimation(context, R.anim.fade_out)

        when (mediaItem.type) {
            "image" -> {
                mediaVideoView.visibility = View.GONE
                mediaImageView.visibility = View.VISIBLE
                mediaTextView.visibility = View.GONE

                mediaImageView.startAnimation(fadeIn)

                Glide.with(this)
                    .load(mediaItem.url)
                    .error(R.drawable.error_placeholder)
                    .into(mediaImageView)

                handler.postDelayed({
                    mediaImageView.startAnimation(fadeOut)
                    handler.postDelayed({
                        currentIndex = (currentIndex + 1) % mediaList.size
                        if (currentIndex == 0) {
                            mediaList = pendingMediaList.toMutableList()
                        }
                        displayMediaItem(view)
                    }, fadeOut.duration)
                }, mediaItem.duration)
            }
            "video" -> {
                mediaImageView.visibility = View.GONE
                mediaVideoView.visibility = View.VISIBLE
                mediaTextView.visibility = View.GONE

                mediaVideoView.setVideoPath(mediaItem.url)
                mediaVideoView.setOnPreparedListener {
                    mediaVideoView.start()
                    mediaVideoView.startAnimation(fadeIn)
                }

                mediaVideoView.setOnCompletionListener {
                    mediaVideoView.startAnimation(fadeOut)
                    currentIndex = (currentIndex + 1) % mediaList.size
                    if (currentIndex == 0) {
                        mediaList = pendingMediaList.toMutableList()
                    }
                    displayMediaItem(view)
                }
            }
        }
    }
}
