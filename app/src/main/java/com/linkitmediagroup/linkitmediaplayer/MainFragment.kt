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
import com.google.firebase.database.*
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.ktx.database

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
        val view = inflater.inflate(R.layout.fragment_main, container, false)

        // Initialize Firebase Realtime Database reference to "media" node
        database = Firebase.database.reference.child("media")

        // Set up listener for changes in the media node on Firebase
        setupMediaListener(view)

        return view
    }

    private fun setupMediaListener(view: View) {
        database.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                val newMediaList = mutableListOf<MediaItem>()

                // Populate newMediaList with updated items from Firebase
                dataSnapshot.children.forEach { snapshot ->
                    val url = snapshot.child("url").value as? String
                    val type = snapshot.child("type").value as? String
                    val duration = snapshot.child("duration").value as? Long ?: 3000L

                    if (url != null && type != null) {
                        newMediaList.add(MediaItem(url, type, duration))
                    }
                }

                // Update mediaList only if there are new items
                if (newMediaList != mediaList) {
                    mediaList.clear()
                    mediaList.addAll(newMediaList)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                val mediaTextView = view.findViewById<TextView>(R.id.media_text)
                mediaTextView?.text = getString(R.string.failed_to_load_media)
            }
        })
    }

    private fun displayMediaItem(view: View, mediaItem: MediaItem) {
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
                    .error(R.drawable.error_placeholder) // Placeholder for failed loads
                    .into(mediaImageView)

                handler.postDelayed({
                    mediaImageView.startAnimation(fadeOut)
                    handler.postDelayed({
                        currentIndex = (currentIndex + 1) % mediaList.size
                        displayMediaItem(view, mediaList[currentIndex])
                    }, fadeOut.duration)
                }, mediaItem.duration)
            }
            "video" -> {
                mediaImageView.visibility = View.GONE
                mediaVideoView.visibility = View.VISIBLE
                mediaTextView.visibility = View.GONE

                mediaVideoView.setVideoPath(mediaItem.url)
                mediaVideoView.setOnErrorListener { _, _, _ ->
                    mediaTextView.text = getString(R.string.failed_to_load_media)
                    mediaVideoView.visibility = View.GONE
                    true
                }

                mediaVideoView.setOnCompletionListener {
                    currentIndex = (currentIndex + 1) % mediaList.size
                    displayMediaItem(view, mediaList[currentIndex])
                }

                mediaVideoView.start()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)  // Stop any pending callbacks when the view is destroyed
    }
}
