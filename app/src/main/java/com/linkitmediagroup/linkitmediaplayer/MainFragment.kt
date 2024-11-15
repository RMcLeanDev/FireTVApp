package com.linkitmediagroup.linkitmediaplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class MainFragment : Fragment() {

    private lateinit var database: DatabaseReference
    private val handler = Handler(Looper.getMainLooper())
    private var currentIndex = 0
    private var mediaList = mutableListOf<MediaItem>()
    private var pendingMediaList = mutableListOf<MediaItem>()
    private var rotationInProgress = false
    private lateinit var networkStatusTextView: TextView
    private lateinit var loadingSpinner: ProgressBar

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

        // Initialize loading spinner
        loadingSpinner = view.findViewById(R.id.loading_spinner)
        loadingSpinner.visibility = View.VISIBLE

        // Load initial media content
        loadInitialMediaContent(view)

        return view
    }

    override fun onResume() {
        super.onResume()
        checkNetworkStatus()  // Check network status whenever the fragment is resumed
    }

    // Function to check and display network status
    private fun checkNetworkStatus() {
        if (requireContext().isNetworkAvailable()) {
            networkStatusTextView.text = "Online"
        } else {
            networkStatusTextView.text = "Offline"
        }
    }

    // Utility function to check network availability
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
        database.addValueEventListener(object : ValueEventListener {
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

                // Apply the pending list only when a full rotation completes
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
        })
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
                mediaVideoView.setOnErrorListener { _, _, _ ->
                    mediaTextView.text = getString(R.string.failed_to_load_media)
                    mediaVideoView.visibility = View.GONE
                    true
                }

                mediaVideoView.setOnCompletionListener {
                    currentIndex = (currentIndex + 1) % mediaList.size
                    if (currentIndex == 0) {
                        // Update media list after a full rotation
                        mediaList = pendingMediaList.toMutableList()
                    }
                    displayMediaItem(view)
                }

                mediaVideoView.start()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}
