package com.linkitmediagroup.linkitmediaplayer

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class PermissionFragment : Fragment() {

    private val REQUEST_READ_PHONE_STATE = 1001

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_permissions, container, false)
        val titleTextView = view.findViewById<TextView>(R.id.permission_title)
        val infoTextView = view.findViewById<TextView>(R.id.info_text)
        val grantPermissionButton = view.findViewById<Button>(R.id.grant_permission_button)

        infoTextView.text = getString(R.string.permission_info)
        grantPermissionButton.text = getString(R.string.grant_permission_button_text)

        grantPermissionButton.setOnClickListener {
            requestPermission()
        }

        return view
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (requireContext().checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), REQUEST_READ_PHONE_STATE)
            } else {
                navigateToPairingFragment()
            }
        } else {
            navigateToPairingFragment()
        }
    }

    private fun navigateToPairingFragment() {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, PairingFragment())
            .commitNow()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_READ_PHONE_STATE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("PermissionFragment", "Permission granted. Navigating to PairingFragment.")
                navigateToPairingFragment()
            } else {
                Log.d("PermissionFragment", "Permission denied.")
            }
        }
    }
}
