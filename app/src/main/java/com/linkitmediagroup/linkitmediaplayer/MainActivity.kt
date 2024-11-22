package com.linkitmediagroup.linkitmediaplayer

import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.initialize

/**
 * Loads [PermissionInfoFragment] initially and transitions to [MainFragment].
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Firebase
        Firebase.initialize(this)

        // Keep the screen on while the app is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            // Load PermissionInfoFragment as the initial screen
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, PermissionInfoFragment())
                .commitNow()
        }
    }
}
