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
    private lateinit var mediaTextView: TextView
    private lateinit var mediaImageView: ImageView
    private lateinit var mediaVideoView: VideoView
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

        // Fetch and display playlist
        fetchAndDisplayPlaylist()

        return view
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

        // Fetch the playlist ID from the screens node
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

                // Iterate over the numeric keys in the items array
                snapshot.children.forEach { itemSnapshot ->
                    val url = itemSnapshot.child("url").value as? String
                    val type = itemSnapshot.child("type").value as? String
                    val duration = itemSnapshot.child("duration").value as? Long ?: 3000L
                    if (url != null && type != null) {
                        newMediaList.add(MediaItem(url, type, duration))
                    }
                }

                if (newMediaList.isNotEmpty()) {
                    mediaList = newMediaList
                    currentIndex = 0
                    displayMediaItem()
                } else {
                    Log.i(LOG_TAG, "Playlist is empty.")
                    showPlaceholder("Playlist is empty.")
                }
                loadingSpinner.visibility = View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(LOG_TAG, "Failed to fetch playlist items: ${error.message}")
                showPlaceholder("Error fetching playlist items.")
                loadingSpinner.visibility = View.GONE
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
                    currentIndex = (currentIndex + 1) % mediaList.size
                    displayMediaItem()
                }, mediaItem.duration)
            }
            "video" -> {
                mediaImageView.visibility = View.GONE
                mediaVideoView.visibility = View.VISIBLE
                mediaVideoView.setVideoPath(mediaItem.url)
                mediaVideoView.setOnPreparedListener { mediaVideoView.start() }
                mediaVideoView.setOnCompletionListener {
                    rotationInProgress = false
                    currentIndex = (currentIndex + 1) % mediaList.size
                    displayMediaItem()
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
