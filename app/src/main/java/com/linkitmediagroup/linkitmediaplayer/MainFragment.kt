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
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import android.graphics.Color
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.ktx.Firebase
import android.os.PowerManager
import com.google.firebase.database.ktx.database
import android.content.Context

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
    private lateinit var mediaPlayerView: PlayerView
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var errorLayout: View
    private lateinit var rootView: View
    private var hasError = false
    private lateinit var wakeLock: PowerManager.WakeLock
    private var isPlaylistUpdatePending = false
    private val eventListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    Log.i(LOG_TAG, "Video is ready to play.")
                    rotationInProgress = true // Ensure we're tracking that a media item is playing
                }
                Player.STATE_ENDED -> {
                    Log.i(LOG_TAG, "Video playback completed naturally.")
                    rotationInProgress = false
                    moveToNextMedia() // Immediately transition to the next media item
                }
                Player.STATE_BUFFERING -> Log.i(LOG_TAG, "Video is buffering.")
                Player.STATE_IDLE -> Log.i(LOG_TAG, "Player is idle.")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(LOG_TAG, "Media3 error: ${error.message}")
            val errorMessage = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Network connection failed."
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Video file not found."
                PlaybackException.ERROR_CODE_DECODING_FAILED -> "Decoding error occurred."
                else -> "Error: ${error.message}"
            }
            val errorTextView = view?.findViewById<TextView>(R.id.error_text)
            errorTextView?.text = errorMessage
            errorLayout.visibility = View.VISIBLE
            errorLayout.bringToFront()
            hasError = true
        }
    }
    val LOG_TAG = AppConstants.LOG_TAG

    data class MediaItem(val url: String, val type: String, val duration: Long = 3000L)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_main, container, false)
        Log.i(LOG_TAG, "main fragment")
        screensDatabase = Firebase.database.reference.child("screens")
        playlistsDatabase = Firebase.database.reference.child("playlists")

        loadingSpinner = view.findViewById(R.id.loading_spinner)
        mediaTextView = view.findViewById(R.id.media_text)
        mediaImageView = view.findViewById(R.id.media_image)
        mediaPlayerView = view.findViewById(R.id.media_player_view)
        errorLayout = view.findViewById(R.id.error_layout)
        exoPlayer = ExoPlayer.Builder(requireContext()).build()
        mediaPlayerView.player = exoPlayer

        deviceSerial = getDeviceSerial()

        listenForCommands()
        checkForPlaylistUpdates()
        checkForScreenRemoval()
        fetchAndDisplayPlaylist()

        return view
    }

    private fun listenForCommands() {
        val commandRef = screensDatabase.child(deviceSerial).child("commands")

        commandRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val command = snapshot.child("action").getValue(String::class.java)
                command?.let {
                    when (it) {
                        "sleep" -> enterSleepMode()
                        "wake" -> exitSleepMode()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(LOG_TAG, "Failed to listen for commands: ${error.message}")
            }
        })
    }

    private fun enterSleepMode() {
        Log.d(LOG_TAG, "Entering sleep mode...")

        cleanupExoPlayer()
        mediaImageView.visibility = View.GONE
        mediaPlayerView.visibility = View.GONE
        mediaTextView.visibility = View.GONE

        val powerManager = requireActivity().getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::SleepModeWakeLock")
        wakeLock.acquire()

        rootView.setBackgroundColor(Color.BLACK)
    }

    private fun exitSleepMode() {
        Log.d(LOG_TAG, "Exiting sleep mode...")

        if (wakeLock.isHeld) {
            wakeLock.release()
        }

        rootView.setBackgroundColor(Color.BLACK)
        fetchAndDisplayPlaylist()
    }

    private fun checkForScreenRemoval() {
        val screenRef = screensDatabase.child(deviceSerial).child("paired")
        Log.i(LOG_TAG, "check for screen removal")
        screenRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isPaired = snapshot.getValue(Boolean::class.java) ?: false
                if (!isPaired) {
                    Log.i(LOG_TAG, "screen removal is not paired")
                    navigateToPairingFragment()
                }
                Log.i(LOG_TAG, "screen removal is paired")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(LOG_TAG, "Failed to check for paired status: ${error.message}")
            }
        })
        Log.i(LOG_TAG, "check for screen removal end")
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
        Log.i(LOG_TAG, "fetch and display playlist")
        loadingSpinner.visibility = View.VISIBLE
        val screenRef = screensDatabase.child(deviceSerial)
        screenRef.child("currentPlaylistAssigned").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.i(LOG_TAG, "fetch and display playlist on data change")
                if (!snapshot.exists()) {
                    Log.e(LOG_TAG, "Snapshot does not exist for currentPlaylistAssigned.")
                    showPlaceholder("No playlist assigned.")
                    return
                }

                val playlistId = snapshot.getValue(String::class.java)
                Log.i(LOG_TAG, "Retrieved playlistId: $playlistId")

                if (playlistId.isNullOrEmpty()) {
                    Log.i(LOG_TAG, "fetch and display playlist isnullorempty")
                    showPlaceholder("No playlist assigned.")
                    return
                }
                Log.i(LOG_TAG, "fetch and display playlist else")
                currentPlaylistId = playlistId
                checkForPlaylistUpdates()
                fetchPlaylistItems(playlistId)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.i(LOG_TAG, "fetch and display playlist on cancelled")
                showPlaceholder("Error fetching playlist.")
            }
        })
        Log.i(LOG_TAG, "fetch and display playlist end")
    }

    private fun fetchPlaylistItems(playlistId: String) {
        Log.i(LOG_TAG, "fetch playlist items")
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
                    Log.i(LOG_TAG, "newMediaList not empty")
                    mediaList = newMediaList
                    currentIndex = 0
                    displayMediaItem()
                } else {
                    Log.w(LOG_TAG, "Playlist is empty.")
                    showPlaceholder("Playlist is empty.")
                }

                isPlaylistUpdatePending = false
                loadingSpinner.visibility = View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(LOG_TAG, "Failed to fetch playlist items: ${error.message}")
                showPlaceholder("Error fetching playlist items.")
                isPlaylistUpdatePending = false
                loadingSpinner.visibility = View.GONE
            }
        })
    }

    private fun checkForPlaylistUpdates() {
        Log.i(LOG_TAG, "check for playlist updates")
        val screenRef = screensDatabase.child(deviceSerial)

        screenRef.child("currentPlaylistAssigned").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.i(LOG_TAG, "check for playlist updates ondatachange")
                val newPlaylistId = snapshot.getValue(String::class.java)
                if (!newPlaylistId.isNullOrEmpty() && newPlaylistId != currentPlaylistId) {
                    Log.i(LOG_TAG, "Detected a playlist ID change. Fetching new playlist.")
                    currentPlaylistId = newPlaylistId
                    fetchPlaylistItems(newPlaylistId)
                }
                Log.i(LOG_TAG, "check for playlist updates ondatachange end")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(LOG_TAG, "Failed to listen for playlist ID updates: ${error.message}")
            }
        })

        if (!currentPlaylistId.isNullOrEmpty()) {
            Log.i(LOG_TAG, "check for playlist updates !currentPlaylistID")
            playlistsDatabase.child(currentPlaylistId!!).child("items").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    Log.i(LOG_TAG, "Playlist updates detected. Setting isPlaylistUpdatePending to true.")
                    isPlaylistUpdatePending = true
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(LOG_TAG, "Failed to listen for playlist updates: ${error.message}")
                }
            })
        }
        Log.i(LOG_TAG, "check for playlist updates end")
    }

    private fun displayMediaItem() {
        if (mediaList.isEmpty()) {
            Log.i(LOG_TAG, "Media list is empty. Showing placeholder.")
            showPlaceholder("No media available.")
            return
        }
        Log.i(LOG_TAG, "display media item start")
        if (rotationInProgress) return
        rotationInProgress = true

        val mediaItem = mediaList[currentIndex]

        when (mediaItem.type) {
            "image" -> {
                cleanupExoPlayer() // Ensure the ExoPlayer is reset when switching to an image
                mediaPlayerView.visibility = View.GONE
                mediaImageView.visibility = View.VISIBLE

                Glide.with(this)
                    .load(mediaItem.url)
                    .error(R.drawable.error_placeholder)
                    .into(mediaImageView)

                handler.postDelayed({
                    rotationInProgress = false
                    moveToNextMedia()
                }, mediaItem.duration)
            }
            "video" -> {
                mediaImageView.visibility = View.GONE
                mediaPlayerView.visibility = View.VISIBLE

                cleanupExoPlayer() // Reset ExoPlayer before setting up a new media item
                val videoMediaItem = androidx.media3.common.MediaItem.Builder()
                    .setUri(Uri.parse(mediaItem.url))
                    .build()

                exoPlayer.setMediaItem(videoMediaItem)
                exoPlayer.prepare()
                exoPlayer.play()

                exoPlayer.removeListener(eventListener)
                exoPlayer.addListener(eventListener) // Attach the listener

                // Handle timeout for Firebase-defined duration
                handler.postDelayed({
                    if (rotationInProgress) {
                        if (hasError) {
                            Log.i(LOG_TAG, "Timeout reached and error detected. Handling video error.")
                            handleVideoError()
                        } else {
                            Log.i(LOG_TAG, "Timeout reached. Moving to next media item.")
                            moveToNextMedia()
                        }
                    }
                }, mediaItem.duration)
            }
        }
    }

    private fun handleVideoError() {
        Log.e(LOG_TAG, "Handling video error for URL: ${mediaList[currentIndex].url}")
        hasError = false // Reset error flag for the next item
        val errorTextView = view?.findViewById<TextView>(R.id.error_text)
        cleanupExoPlayer()

        handler.post {
            Log.i(LOG_TAG, "Hiding error message text view.")
            errorLayout.visibility = View.GONE
            errorTextView?.text = ""
            moveToNextMedia() // Transition to the next media item after cleanup
        }
    }

    private fun moveToNextMedia() {
        cleanupExoPlayer() // Reset player state before transitioning

        rotationInProgress = false
        currentIndex = (currentIndex + 1) % mediaList.size
        if (currentIndex == 0 && isPlaylistUpdatePending){
            fetchPlaylistItems(currentPlaylistId!!)
            isPlaylistUpdatePending = false
        } else {
            displayMediaItem()
        }
    }

    private fun cleanupExoPlayer() {
        exoPlayer.stop()
        exoPlayer.seekTo(0)
        exoPlayer.clearMediaItems()
        exoPlayer.removeListener(eventListener)
        exoPlayer.setPlayWhenReady(false)
    }

    private fun showPlaceholder(message: String) {
        mediaImageView.visibility = View.GONE
        mediaPlayerView.visibility = View.GONE
        mediaTextView.visibility = View.VISIBLE
        mediaTextView.text = message
        loadingSpinner.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        exoPlayer.release()
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