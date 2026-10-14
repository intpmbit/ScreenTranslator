package com.example.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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

    private val REQ_PROJECTION = 200

    private val languages = arrayOf(
        "en" to "الإنجليزية",
        "ja" to "اليابانية",
        "ko" to "الكورية",
        "zh" to "الصينية"
    )

    private lateinit var statusText: TextView
    private lateinit var btnLang: Button

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
            text = "مترجم الشاشة - الجزء 2"
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
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        btnLang = Button(this).apply {
            setOnClickListener { cycleLanguage() }
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
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
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
        root.addView(btnLang)
        root.addView(btnStart)
        root.addView(btnStop)
        setContentView(root)

        updateLangButton()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        statusText.text = if (Settings.canDrawOverlays(this)) {
            "إذن الظهور فوق التطبيقات: ✅ ممنوح"
        } else {
            "إذن الظهور فوق التطبيقات: ❌ غير ممنوح"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, FloatingService::class.java).apply {
                    putExtra(FloatingService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(FloatingService.EXTRA_DATA, data)
                }
                startForegroundService(intent)
            } else {
                Toast.makeText(this, "لم يتم منح إذن تسجيل الشاشة", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun prefs() = getSharedPreferences(FloatingService.PREFS, MODE_PRIVATE)

    private fun cycleLanguage() {
        val current = prefs().getString(FloatingService.KEY_SRC, "en")
        val index = languages.indexOfFirst { it.first == current }
        val next = languages[(index + 1) % languages.size]
        prefs().edit().putString(FloatingService.KEY_SRC, next.first).apply()
        updateLangButton()
    }

    private fun updateLangButton() {
        val current = prefs().getString(FloatingService.KEY_SRC, "en")
        val name = languages.firstOrNull { it.first == current }?.second ?: "الإنجليزية"
        btnLang.text = "لغة النص على الشاشة: $name (اضغط للتغيير)"
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