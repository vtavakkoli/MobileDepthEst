package com.ai.mob_dep

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialButton>(R.id.liveModeButton).setOnClickListener {
            startActivity(Intent(this, LiveDepthActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.offlineModeButton).setOnClickListener {
            startActivity(Intent(this, OfflineDepthActivity::class.java))
        }

        findViewById<FloatingActionButton>(R.id.infoButton).setOnClickListener {
            showInfoDialog()
        }
    }

    private fun showInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("About MobDep")
            .setMessage("Developed by Dr. Vahid Tavakkoli 2026\n\nThis application is designed for educational purposes, demonstrating real-time monocular depth estimation and 3D reconstruction using LiteRT (TensorFlow Lite) on Android.")
            .setPositiveButton("OK", null)
            .show()
    }
}
