package com.example.screentranslator

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val REQ_PROJECTION = 200
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusCard: View
    private lateinit var dotCore: View
    private lateinit var dotHalo: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvPermStatus: TextView
    private lateinit var tvPermAction: TextView
    private lateinit var btnStart: View
    private lateinit var btnStop: View

    private val chips = linkedMapOf<String, TextView>()
    private var pulse: ObjectAnimator? = null

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusCard = findViewById(R.id.statusCard)
        dotCore = findViewById(R.id.dotCore)
        dotHalo = findViewById(R.id.dotHalo)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSub = findViewById(R.id.tvStatusSub)
        ivStatusIcon = findViewById(R.id.ivStatusIcon)
        tvPermStatus = findViewById(R.id.tvPermStatus)
        tvPermAction = findViewById(R.id.tvPermAction)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        chips["en"] = findViewById(R.id.chipEn)
        chips["ja"] = findViewById(R.id.chipJa)
        chips["ko"] = findViewById(R.id.chipKo)
        chips["zh"] = findViewById(R.id.chipZh)
        chips.forEach { (code, view) ->
            view.setOnClickListener {
                prefs().edit().putString(FloatingService.KEY_SRC, code).apply()
                updateChips()
            }
        }

        findViewById<View>(R.id.rowPermission).setOnClickListener { openOverlaySettings() }
        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            handler.postDelayed({ refresh() }, 300)
        }

        updateChips()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        stopPulse()
    }

    // ---------------------------------------------------------------
    // الحالة
    // ---------------------------------------------------------------

    private fun refresh() {
        val perm = Settings.canDrawOverlays(this)
        val running = FloatingService.isRunning

        if (perm) {
            tvPermStatus.text = "ممنوح"
            tvPermStatus.setTextColor(getColor(R.color.success))
            tvPermAction.text = "تعديل"
        } else {
            tvPermStatus.text = "غير ممنوح"
            tvPermStatus.setTextColor(getColor(R.color.warning))
            tvPermAction.text = "منح"
        }

        when {
            running -> applyState(
                R.drawable.bg_status_active, R.color.success,
                "الخدمة نشطة",
                "الفقاعة جاهزة. اضغطها فوق أي لعبة أو مانجا للترجمة",
                R.drawable.ic_check, true
            )
            !perm -> applyState(
                R.drawable.bg_status_warning, R.color.warning,
                "يلزم منح إذن",
                "فعّل إذن الظهور فوق التطبيقات للبدء",
                R.drawable.ic_shield, false
            )
            else -> applyState(
                R.drawable.bg_status_inactive, R.color.text_muted,
                "الخدمة متوقفة",
                "اضغط «تشغيل الفقاعة» للبدء",
                R.drawable.ic_play, false
            )
        }

        btnStart.alpha = if (running) 0.45f else 1f
        btnStop.alpha = if (running) 1f else 0.45f
    }

    private fun applyState(
        bg: Int, colorRes: Int, title: String, sub: String, icon: Int, animate: Boolean
    ) {
        val color = getColor(colorRes)
        statusCard.setBackgroundResource(bg)
        dotCore.backgroundTintList = ColorStateList.valueOf(color)
        dotHalo.backgroundTintList = ColorStateList.valueOf(color)
        tvStatusTitle.text = title
        tvStatusSub.text = sub
        ivStatusIcon.setImageResource(icon)
        ivStatusIcon.setColorFilter(color)
        if (animate) startPulse() else stopPulse()
    }

    private fun startPulse() {
        if (pulse?.isRunning == true) return
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            dotHalo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.5f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.5f, 1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 0f)
        ).apply {
            duration = 1400
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        dotHalo.alpha = 0f
    }

    // ---------------------------------------------------------------
    // الإجراءات
    // ---------------------------------------------------------------

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun onStartClicked() {
        when {
            !Settings.canDrawOverlays(this) -> {
                Toast.makeText(this, "امنح إذن الظهور فوق التطبيقات أولاً", Toast.LENGTH_LONG).show()
                openOverlaySettings()
            }
            FloatingService.isRunning ->
                Toast.makeText(this, "الفقاعة تعمل بالفعل", Toast.LENGTH_SHORT).show()
            else -> {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
            }
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

    // ---------------------------------------------------------------
    // اللغة
    // ---------------------------------------------------------------

    private fun prefs() = getSharedPreferences(FloatingService.PREFS, MODE_PRIVATE)

    private fun updateChips() {
        val current = prefs().getString(FloatingService.KEY_SRC, "en")
        chips.forEach { (code, view) ->
            val on = code == current
            view.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
            view.setTextColor(getColor(if (on) R.color.white else R.color.text_secondary))
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