package com.linkitmediagroup.linkitmediaplayer

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import androidx.fragment.app.FragmentActivity
import com.google.firebase.ktx.Firebase
import com.google.firebase.ktx.initialize
import android.content.pm.PackageManager
import android.util.Log

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(AppConstants.LOG_TAG, "MainActivity onCreate")
        super.onCreate(savedInstanceState)
        // Initialize Firebase
        Firebase.initialize(this)
        Log.e(AppConstants.LOG_TAG, "Firebase initialized")
        // Keep the screen on while the app is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        val loadingSpinner = findViewById<ProgressBar>(R.id.initial_loading_spinner)
        loadingSpinner.visibility = View.VISIBLE // Show the spinner

        if (savedInstanceState == null) {
            // Determine the initial state
            val sharedPreferences = getSharedPreferences("device_prefs", MODE_PRIVATE)
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Permissions are granted by default below API 23
            }
            val isPaired = sharedPreferences.getString("pairing_code", null) != null

            val initialFragment = when {
                !hasPermission -> PermissionFragment()
                !isPaired -> PairingFragment()
                else -> MainFragment()
            }

            // Load the determined fragment
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, initialFragment)
                .runOnCommit {
                    loadingSpinner.visibility = View.GONE // Hide the spinner once the fragment is loaded
                }
                .commitNow()
        }
    }
}
