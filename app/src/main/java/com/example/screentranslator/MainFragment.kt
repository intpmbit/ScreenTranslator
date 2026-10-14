package com.example.screentranslator

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Fragment
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView

@Suppress("DEPRECATION")
class MainFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private var pulse: ObjectAnimator? = null

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

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_main, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusCard = view.findViewById(R.id.statusCard)
        dotCore = view.findViewById(R.id.dotCore)
        dotHalo = view.findViewById(R.id.dotHalo)
        tvStatusTitle = view.findViewById(R.id.tvStatusTitle)
        tvStatusSub = view.findViewById(R.id.tvStatusSub)
        ivStatusIcon = view.findViewById(R.id.ivStatusIcon)
        tvPermStatus = view.findViewById(R.id.tvPermStatus)
        tvPermAction = view.findViewById(R.id.tvPermAction)
        btnStart = view.findViewById(R.id.btnStart)
        btnStop = view.findViewById(R.id.btnStop)

        chips["en"] = view.findViewById(R.id.chipEn)
        chips["ja"] = view.findViewById(R.id.chipJa)
        chips["ko"] = view.findViewById(R.id.chipKo)
        chips["zh"] = view.findViewById(R.id.chipZh)
        chips.forEach { (code, chip) ->
            chip.setOnClickListener {
                Prefs.get(requireContext()).edit().putString(Prefs.KEY_SRC, code).apply()
                updateChips()
            }
        }

        view.findViewById<View>(R.id.rowPermission).setOnClickListener {
            (activity as MainActivity).openOverlaySettings()
        }
        btnStart.setOnClickListener { (activity as MainActivity).requestStart() }
        btnStop.setOnClickListener {
            requireContext().stopService(Intent(requireContext(), FloatingService::class.java))
            handler.postDelayed({ if (isAdded) refresh() }, 300)
        }
        updateChips()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        stopPulse()
        super.onPause()
    }

    private fun refresh() {
        if (!isAdded) return
        val ctx = requireContext()
        val perm = Settings.canDrawOverlays(ctx)
        val running = FloatingService.isRunning

        if (perm) {
            tvPermStatus.text = "ممنوح"
            tvPermStatus.setTextColor(ctx.getColor(R.color.success))
            tvPermAction.text = "تعديل"
        } else {
            tvPermStatus.text = "غير ممنوح"
            tvPermStatus.setTextColor(ctx.getColor(R.color.warning))
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
        val color = requireContext().getColor(colorRes)
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
        if (::dotHalo.isInitialized) dotHalo.alpha = 0f
    }

    private fun updateChips() {
        val ctx = requireContext()
        val current = Prefs.srcLang(ctx)
        chips.forEach { (code, chip) ->
            val on = code == current
            chip.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
            chip.setTextColor(ctx.getColor(if (on) R.color.white else R.color.text_secondary))
        }
    }
}
