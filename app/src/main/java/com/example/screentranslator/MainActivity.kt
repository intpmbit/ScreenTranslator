package com.example.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "مترجم الشاشة - الجزء 1"
            textSize = 22f
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }

        val btnPermission = Button(this).apply {
            text = "1) منح إذن الظهور فوق التطبيقات"
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        val btnStart = Button(this).apply {
            text = "2) تشغيل الفقاعة"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "امنح إذن الظهور فوق التطبيقات أولاً",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    startForegroundService(
                        Intent(this@MainActivity, FloatingService::class.java)
                    )
                }
            }
        }

        val btnStop = Button(this).apply {
            text = "3) إيقاف الفقاعة"
            setOnClickListener {
                stopService(Intent(this@MainActivity, FloatingService::class.java))
            }
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(btnPermission)
        root.addView(btnStart)
        root.addView(btnStop)
        setContentView(root)

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val granted = Settings.canDrawOverlays(this)
        statusText.text = if (granted) {
            "إذن الظهور فوق التطبيقات: ✅ ممنوح"
        } else {
            "إذن الظهور فوق التطبيقات: ❌ غير ممنوح"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}