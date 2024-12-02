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
import android.view.WindowManager
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

        screensDatabase = Firebase.database.reference.child("screens")
        playlistsDatabase = Firebase.database.reference.child("playlists")

        loadingSpinner = view.findViewById(R.id.loading_spinner)
        mediaTextView = view.findViewById(R.id.media_text)
        mediaImageView = view.findViewById(R.id.media_image)
        mediaVideoView = view.findViewById(R.id.media_video)

        deviceSerial = getDeviceSerial()

        checkForPlaylistUpdates()
        checkForScreenRemoval()
        fetchAndDisplayPlaylist()

        return view
    }

    private fun checkForScreenRemoval() {
        val screenRef = screensDatabase.child(deviceSerial).child("paired")

        screenRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isPaired = snapshot.getValue(Boolean::class.java) ?: false
                if (!isPaired) {
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
            android.provider.Settings.Secure.getString(requireContext().contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
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
                    showPlaceholder("No playlist assigned.")
                    return
                }
                currentPlaylistId = playlistId
                checkForPlaylistUpdates()
                fetchPlaylistItems(playlistId)
            }

            override fun onCancelled(error: DatabaseError) {
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
                    mediaList = newMediaList
                    currentIndex = 0
                    displayMediaItem()
                } else {
                    showPlaceholder("Playlist is empty.")
                }
                isPlaylistUpdatePending = false
                loadingSpinner.visibility = View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                showPlaceholder("Error fetching playlist items.")
                isPlaylistUpdatePending = false
                loadingSpinner.visibility = View.GONE
            }
        })
    }

    private fun checkForPlaylistUpdates() {
        val screenRef = screensDatabase.child(deviceSerial)

        screenRef.child("currentPlaylistAssigned").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newPlaylistId = snapshot.getValue(String::class.java)
                if (!newPlaylistId.isNullOrEmpty() && newPlaylistId != currentPlaylistId) {
                    Log.i(LOG_TAG, "Detected a playlist ID change. Fetching new playlist.")
                    currentPlaylistId = newPlaylistId
                    fetchPlaylistItems(newPlaylistId)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(LOG_TAG, "Failed to listen for playlist ID updates: ${error.message}")
            }
        })

        if (!currentPlaylistId.isNullOrEmpty()) {
            playlistsDatabase.child(currentPlaylistId!!).child("items").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    isPlaylistUpdatePending = true
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(LOG_TAG, "Failed to listen for playlist updates: ${error.message}")
                }
            })
        }
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

                    if (currentIndex == mediaList.size - 1) {
                        if (isPlaylistUpdatePending) {
                            fetchPlaylistItems(currentPlaylistId!!)
                        } else {
                            currentIndex = 0
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

                    if (currentIndex == mediaList.size - 1) {
                        if (isPlaylistUpdatePending) {
                            fetchPlaylistItems(currentPlaylistId!!)
                        } else {
                            currentIndex = 0
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

    override fun onResume() {
        super.onResume()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
