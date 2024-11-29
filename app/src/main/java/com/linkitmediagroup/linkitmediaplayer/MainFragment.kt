package com.linkitmediagroup.linkitmediaplayer

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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.ktx.database

class MainFragment : Fragment() {

    private lateinit var screensDatabase: DatabaseReference
    private lateinit var playlistsDatabase: DatabaseReference
    private lateinit var deviceSerial: String
    private val handler = Handler(Looper.getMainLooper())
    private var currentIndex = 0
    private var mediaList = mutableListOf<MediaItem>()
    private var rotationInProgress = false
    private lateinit var loadingSpinner: ProgressBar
    private var currentPlaylistId: String? = null
    private lateinit var mediaTextView: TextView
    private lateinit var mediaImageView: ImageView
    private lateinit var mediaVideoView: VideoView
    private var isPlaylistUpdatePending = false
    val LOG_TAG = AppConstants.LOG_TAG

    data class MediaItem(val url: String, val type: String, val duration: Long = 3000L)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_main, container, false)

        // Initialize Firebase Database references
        screensDatabase = Firebase.database.reference.child("screens")
        playlistsDatabase = Firebase.database.reference.child("playlists")

        // Initialize UI elements
        loadingSpinner = view.findViewById(R.id.loading_spinner)
        mediaTextView = view.findViewById(R.id.media_text)
        mediaImageView = view.findViewById(R.id.media_image)
        mediaVideoView = view.findViewById(R.id.media_video)

        // Fetch device serial number
        deviceSerial = getDeviceSerial()

        // Listen for playlist updates
        checkForPlaylistUpdates()
        checkForScreenRemoval()
        // Fetch and display playlist
        fetchAndDisplayPlaylist()

        return view
    }

    private fun checkForScreenRemoval() {
        val screenRef = screensDatabase.child(deviceSerial).child("paired")

        screenRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isPaired = snapshot.getValue(Boolean::class.java) ?: false
                if (!isPaired) {
                    Log.i(LOG_TAG, "Screen is unpaired. Navigating to PairingFragment.")
                    navigateToPairingFragment()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(LOG_TAG, "Failed to check for paired status: ${error.message}")
            }
        })
    }

    private fun navigateToPairingFragment() {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, PairingFragment())
            .commitAllowingStateLoss()
    }

    private fun getDeviceSerial(): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.os.Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                android.os.Build.SERIAL
            }
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "Permission denied for serial: ${e.message}")
            android.provider.Settings.Secure.getString(requireContext().contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error retrieving serial: ${e.message}")
            android.provider.Settings.Secure.getString(requireContext().contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }
    }

    private fun fetchAndDisplayPlaylist() {
        loadingSpinner.visibility = View.VISIBLE

        val screenRef = screensDatabase.child(deviceSerial)
        screenRef.child("currentPlaylistAssigned").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val playlistId = snapshot.getValue(String::class.java)
                if (playlistId.isNullOrEmpty()) {
                    Log.i(LOG_TAG, "No playlist assigned to this screen.")
                    showPlaceholder("No playlist assigned.")
                    return
                }
                Log.i(LOG_TAG, "Fetching playlist with ID: $playlistId")
                currentPlaylistId = playlistId
                checkForPlaylistUpdates() // Start listening for updates
                fetchPlaylistItems(playlistId)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(LOG_TAG, "Failed to fetch playlist ID: ${error.message}")
                showPlaceholder("Error fetching playlist.")
            }
        })
    }

    private fun fetchPlaylistItems(playlistId: String) {
        playlistsDatabase.child(playlistId).child("items").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newMediaList = mutableListOf<MediaItem>()

                snapshot.children.forEach { itemSnapshot ->
                    val url = itemSnapshot.child("url").value as? String
                    val type = itemSnapshot.child("type").value as? String
                    val duration = itemSnapshot.child("duration").value as? Long ?: 3000L
                    if (url != null && type != null) {
                        newMediaList.add(MediaItem(url, type, duration))
                    }
                }

                if (newMediaList.isNotEmpty()) {
                    Log.i(LOG_TAG, "Playlist items updated.")
                    mediaList = newMediaList
                    currentIndex = 0 // Reset to the beginning of the playlist
                    displayMediaItem() // Start displaying updated playlist
                } else {
                    Log.i(LOG_TAG, "Playlist is empty.")
                    showPlaceholder("Playlist is empty.")
                }
                isPlaylistUpdatePending = false // Reset pending update flag
                loadingSpinner.visibility = View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(LOG_TAG, "Failed to fetch playlist items: ${error.message}")
                showPlaceholder("Error fetching playlist items.")
                isPlaylistUpdatePending = false // Reset pending update flag
                loadingSpinner.visibility = View.GONE
            }
        })
    }

    private fun checkForPlaylistUpdates() {
        if (currentPlaylistId.isNullOrEmpty()) return

        playlistsDatabase.child(currentPlaylistId!!).child("items").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.i(LOG_TAG, "Detected an update in playlist items. Marking update as pending.")
                isPlaylistUpdatePending = true
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(LOG_TAG, "Failed to listen for playlist updates: ${error.message}")
            }
        })
    }

    private fun displayMediaItem() {
        if (mediaList.isEmpty()) {
            showPlaceholder("No media available.")
            return
        }
        if (rotationInProgress) return

        rotationInProgress = true
        val mediaItem = mediaList[currentIndex]

        when (mediaItem.type) {
            "image" -> {
                mediaVideoView.visibility = View.GONE
                mediaImageView.visibility = View.VISIBLE

                Glide.with(this)
                    .load(mediaItem.url)
                    .error(R.drawable.error_placeholder)
                    .into(mediaImageView)

                handler.postDelayed({
                    rotationInProgress = false

                    // Check if this is the last item in the playlist
                    if (currentIndex == mediaList.size - 1) {
                        if (isPlaylistUpdatePending) {
                            fetchPlaylistItems(currentPlaylistId!!)
                        } else {
                            currentIndex = 0 // Restart playlist
                            displayMediaItem()
                        }
                    } else {
                        currentIndex++
                        displayMediaItem()
                    }
                }, mediaItem.duration)
            }
            "video" -> {
                mediaImageView.visibility = View.GONE
                mediaVideoView.visibility = View.VISIBLE
                mediaVideoView.setVideoPath(mediaItem.url)
                mediaVideoView.setOnPreparedListener { mediaVideoView.start() }
                mediaVideoView.setOnCompletionListener {
                    rotationInProgress = false

                    // Check if this is the last item in the playlist
                    if (currentIndex == mediaList.size - 1) {
                        if (isPlaylistUpdatePending) {
                            fetchPlaylistItems(currentPlaylistId!!)
                        } else {
                            currentIndex = 0 // Restart playlist
                            displayMediaItem()
                        }
                    } else {
                        currentIndex++
                        displayMediaItem()
                    }
                }
            }
        }
    }


    private fun showPlaceholder(message: String) {
        mediaImageView.visibility = View.GONE
        mediaVideoView.visibility = View.GONE
        mediaTextView.visibility = View.VISIBLE
        mediaTextView.text = message
        loadingSpinner.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}
