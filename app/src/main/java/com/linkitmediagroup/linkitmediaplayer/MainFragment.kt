package com.linkitmediagroup.linkitmediaplayer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class MainFragment : Fragment() {

    private lateinit var database: DatabaseReference
    private val handler = Handler(Looper.getMainLooper())
    private var currentIndex = 0
    private val mediaList = mutableListOf<MediaItem>()

    data class MediaItem(val url: String, val type: String, val duration: Long = 3000L)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_main, container, false)

        // Initialize Firebase Realtime Database
        database = Firebase.database.reference

        // Load data from Firebase
        loadMediaContent(view)

        return view
    }

    private fun loadMediaContent(view: View) {
        database.child("media").get().addOnSuccessListener { dataSnapshot ->
            mediaList.clear() // Clear the list before adding new items

            dataSnapshot.children.forEach { snapshot ->
                val url = snapshot.child("url").value as? String
                val type = snapshot.child("type").value as? String
                val duration = snapshot.child("duration").value as? Long ?: 3000L  // Default to 3 seconds if no duration provided

                if (url != null && type != null) {
                    mediaList.add(MediaItem(url, type, duration))
                }
            }

            // Start displaying media from the first item
            if (mediaList.isNotEmpty()) {
                displayMediaItem(view, mediaList[currentIndex])
            }

        }.addOnFailureListener {
            val mediaTextView = view.findViewById<TextView>(R.id.media_text)
            mediaTextView?.text = getString(R.string.failed_to_load_media)
        }
    }

    private fun displayMediaItem(view: View, mediaItem: MediaItem) {
        val mediaTextView = view.findViewById<TextView>(R.id.media_text)
        val mediaImageView = view.findViewById<ImageView>(R.id.media_image)
        val mediaVideoView = view.findViewById<VideoView>(R.id.media_video)

        when (mediaItem.type) {
            "image" -> {
                mediaVideoView.visibility = View.GONE
                mediaImageView.visibility = View.VISIBLE
                mediaTextView.visibility = View.GONE

                // Load the image with Glide
                Glide.with(this).load(mediaItem.url).into(mediaImageView)

                // Set a delay for the duration before showing the next media item
                handler.postDelayed({
                    currentIndex = (currentIndex + 1) % mediaList.size  // Loop back to the first item
                    displayMediaItem(view, mediaList[currentIndex])
                }, mediaItem.duration)
            }
            "video" -> {
                mediaImageView.visibility = View.GONE
                mediaVideoView.visibility = View.VISIBLE
                mediaTextView.visibility = View.GONE

                mediaVideoView.setVideoPath(mediaItem.url)
                mediaVideoView.start()

                // Move to the next item after the video finishes playing
                mediaVideoView.setOnCompletionListener {
                    currentIndex = (currentIndex + 1) % mediaList.size
                    displayMediaItem(view, mediaList[currentIndex])
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)  // Stop any pending callbacks when the view is destroyed
    }
}
