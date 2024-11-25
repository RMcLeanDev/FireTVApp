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
import com.google.firebase.database.DatabaseReference
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.ktx.database

class MainFragment : Fragment() {

    private lateinit var mediaDatabase: DatabaseReference
    private val handler = Handler(Looper.getMainLooper())
    private var currentIndex = 0
    private var mediaList = mutableListOf<MediaItem>()
    private var rotationInProgress = false
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var mediaTextView: TextView
    private lateinit var mediaImageView: ImageView
    private lateinit var mediaVideoView: VideoView

    data class MediaItem(val url: String, val type: String, val duration: Long = 3000L)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_main, container, false)

        // Initialize Firebase Database reference
        mediaDatabase = Firebase.database.reference.child("media")

        // Initialize UI elements
        loadingSpinner = view.findViewById(R.id.loading_spinner)
        mediaTextView = view.findViewById(R.id.media_text)
        mediaImageView = view.findViewById(R.id.media_image)
        mediaVideoView = view.findViewById(R.id.media_video)

        // Fetch and display playlist
        fetchAndDisplayPlaylist()

        return view
    }

    private fun fetchAndDisplayPlaylist() {
        loadingSpinner.visibility = View.VISIBLE
        mediaDatabase.child("playlistId").get().addOnSuccessListener { snapshot ->
            val newMediaList = mutableListOf<MediaItem>()
            snapshot.children.forEach {
                val url = it.child("url").value as? String
                val type = it.child("type").value as? String
                val duration = it.child("duration").value as? Long ?: 3000L
                if (url != null && type != null) {
                    newMediaList.add(MediaItem(url, type, duration))
                }
            }
            if (newMediaList.isNotEmpty()) {
                mediaList = newMediaList
                currentIndex = 0
                displayMediaItem()
            } else {
                showPlaceholder()
            }
            loadingSpinner.visibility = View.GONE
        }.addOnFailureListener {
            Log.e("Playlist", "Failed to fetch playlist: ${it.message}")
            showPlaceholder()
            loadingSpinner.visibility = View.GONE
        }
    }

    private fun displayMediaItem() {
        if (mediaList.isEmpty()) {
            showPlaceholder()
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

    private fun showPlaceholder() {
        mediaImageView.visibility = View.GONE
        mediaVideoView.visibility = View.GONE
        mediaTextView.visibility = View.VISIBLE
        mediaTextView.text = "No media available."
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}
