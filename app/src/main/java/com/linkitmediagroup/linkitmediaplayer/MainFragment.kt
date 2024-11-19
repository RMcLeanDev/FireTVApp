package com.linkitmediagroup.linkitmediaplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private lateinit var database: DatabaseReference
    private lateinit var firebaseListener: ValueEventListener
    private val handler = Handler(Looper.getMainLooper())
    private val networkStatusHandler = Handler(Looper.getMainLooper())
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

        // Initialize Firebase Realtime Database reference
        database = Firebase.database.reference.child("media")

        // Reference to the network status text view
        networkStatusTextView = view.findViewById(R.id.network_status_text)

        // Reference to the pairing code text view
        pairingCodeTextView = view.findViewById(R.id.pairing_code_text)

        // Initialize loading spinner
        loadingSpinner = view.findViewById(R.id.loading_spinner)
        loadingSpinner.visibility = View.VISIBLE

        // Generate and display pairing code
        pairingCode = generatePairingCode()
        savePairingCodeToFirebase(pairingCode)
        displayPairingCode(pairingCode)

        // Listen for playlist assignment
        listenForPlaylistAssignment(pairingCode)

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
        handler.removeCallbacksAndMessages(null) // Stop all pending callbacks
        networkStatusHandler.removeCallbacks(networkStatusRunnable) // Stop periodic checks
        database.removeEventListener(firebaseListener) // Remove Firebase listener
    }

    private fun generatePairingCode(): String {
        val allowedChars = ('A'..'Z') + ('0'..'9')
        return (1..8).map { allowedChars.random() }.joinToString("")
    }

    private fun savePairingCodeToFirebase(pairingCode: String) {
        val databaseRef = Firebase.database.reference.child("pairings").child(pairingCode)
        val deviceData = mapOf(
            "deviceName" to "Living Room TV", // Replace with a dynamic name if needed
            "playlistId" to null
        )

        databaseRef.setValue(deviceData).addOnSuccessListener {
            Log.d("Pairing", "Pairing code saved successfully.")
        }.addOnFailureListener { error ->
            Log.e("Pairing", "Failed to save pairing code: ${error.message}")
        }
    }

    private fun displayPairingCode(pairingCode: String) {
        pairingCodeTextView.text = "Pairing Code: $pairingCode"
    }

    private fun listenForPlaylistAssignment(pairingCode: String) {
        val databaseRef = Firebase.database.reference.child("pairings").child(pairingCode).child("playlistId")
        databaseRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val playlistId = snapshot.value as? String
                if (playlistId != null) {
                    Log.d("Pairing", "Playlist assigned: $playlistId")
                    loadPlaylist(playlistId)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Pairing", "Failed to listen for playlist: ${error.message}")
            }
        })
    }

    private fun loadPlaylist(playlistId: String) {
        // Replace "media" with the Firebase node for playlists
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
                displayMediaItem(requireView())
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
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
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
            database.get().addOnSuccessListener { dataSnapshot ->
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
                    // Cache new items to Room
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
            // Offline: Load from Room
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

        database.addValueEventListener(firebaseListener)
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
                            // Update media list after a full rotation
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
                        // Update media list after a full rotation
                        mediaList = pendingMediaList.toMutableList()
                    }
                    displayMediaItem(view)
                }
            }
        }
    }
}
