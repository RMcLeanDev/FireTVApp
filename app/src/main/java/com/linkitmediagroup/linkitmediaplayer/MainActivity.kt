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
        super.onCreate(savedInstanceState)
        Firebase.initialize(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        val loadingSpinner = findViewById<ProgressBar>(R.id.initial_loading_spinner)
        loadingSpinner.visibility = View.VISIBLE

        if (savedInstanceState == null) {
            val sharedPreferences = getSharedPreferences("device_prefs", MODE_PRIVATE)
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            val isPaired = sharedPreferences.getString("pairing_code", null) != null

            val initialFragment = when {
                !hasPermission -> PermissionFragment()
                !isPaired -> PairingFragment()
                isPaired -> MainFragment()
                else -> PairingFragment()
            }

            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, initialFragment)
                .runOnCommit {
                    loadingSpinner.visibility = View.GONE
                }
                .commitNow()
        }
    }
}
