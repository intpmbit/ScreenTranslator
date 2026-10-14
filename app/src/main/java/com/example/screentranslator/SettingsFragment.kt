package com.example.screentranslator

import android.app.Fragment
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView

@Suppress("DEPRECATION")
class SettingsFragment : Fragment() {

    private lateinit var optPopup: View
    private lateinit var optOverlay: View
    private lateinit var optPopupCheck: View
    private lateinit var optOverlayCheck: View
    private lateinit var chipHoriz: TextView
    private lateinit var chipVert: TextView
    private lateinit var tvOrientHint: TextView
    private lateinit var bgChips: List<TextView>
    private lateinit var previewHolder: FrameLayout
    private lateinit var tvSizeValue: TextView
    private lateinit var tvAlphaValue: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = activity ?: return
        val prefs = Prefs.get(ctx)

        optPopup = view.findViewById(R.id.optPopup)
        optOverlay = view.findViewById(R.id.optOverlay)
        optPopupCheck = view.findViewById(R.id.optPopupCheck)
        optOverlayCheck = view.findViewById(R.id.optOverlayCheck)
        chipHoriz = view.findViewById(R.id.chipHoriz)
        chipVert = view.findViewById(R.id.chipVert)
        tvOrientHint = view.findViewById(R.id.tvOrientHint)
        bgChips = listOf(
            view.findViewById(R.id.chipBgLight),
            view.findViewById(R.id.chipBgMid),
            view.findViewById(R.id.chipBgSolid)
        )
        previewHolder = view.findViewById(R.id.previewHolder)
        tvSizeValue = view.findViewById(R.id.tvSizeValue)
        tvAlphaValue = view.findViewById(R.id.tvAlphaValue)
        val seekSize = view.findViewById<SeekBar>(R.id.seekSize)
        val seekAlpha = view.findViewById<SeekBar>(R.id.seekAlpha)

        // نمط العرض
        optPopup.setOnClickListener {
            prefs.edit().putString(Prefs.KEY_MODE, Prefs.MODE_POPUP).apply()
            refreshMode()
        }
        optOverlay.setOnClickListener {
            prefs.edit().putString(Prefs.KEY_MODE, Prefs.MODE_OVERLAY).apply()
            refreshMode()
        }

        // اتجاه القراءة
        chipHoriz.setOnClickListener {
            prefs.edit().putString(Prefs.KEY_ORIENT, Prefs.ORIENT_H).apply()
            refreshOrientation()
        }
        chipVert.setOnClickListener {
            prefs.edit().putString(Prefs.KEY_ORIENT, Prefs.ORIENT_V).apply()
            refreshOrientation()
        }

        // خلفية النمط الشفاف
        bgChips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                prefs.edit().putInt(Prefs.KEY_OVERLAY_BG, index).apply()
                refreshBg()
            }
        }

        // الحجم والشفافية
        seekSize.progress = Prefs.bubbleSize(ctx) - 40
        seekAlpha.progress = Prefs.bubbleAlpha(ctx) - 30
        tvSizeValue.text = "${Prefs.bubbleSize(ctx)} dp"
        tvAlphaValue.text = "${Prefs.bubbleAlpha(ctx)}%"

        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val v = 40 + p
                tvSizeValue.text = "$v dp"
                if (fromUser) {
                    prefs.edit().putInt(Prefs.KEY_BUBBLE_SIZE, v).apply()
                    renderPreview()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val v = 30 + p
                tvAlphaValue.text = "$v%"
                if (fromUser) {
                    prefs.edit().putInt(Prefs.KEY_BUBBLE_ALPHA, v).apply()
                    renderPreview()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        refreshMode()
        refreshOrientation()
        refreshBg()
        renderPreview()
    }

    private fun refreshMode() {
        val ctx = activity ?: return
        val overlay = Prefs.mode(ctx) == Prefs.MODE_OVERLAY
        optPopup.setBackgroundResource(if (!overlay) R.drawable.bg_option_on else R.drawable.bg_option_off)
        optOverlay.setBackgroundResource(if (overlay) R.drawable.bg_option_on else R.drawable.bg_option_off)
        optPopupCheck.visibility = if (!overlay) View.VISIBLE else View.INVISIBLE
        optOverlayCheck.visibility = if (overlay) View.VISIBLE else View.INVISIBLE
    }

    private fun refreshOrientation() {
        val ctx = activity ?: return
        val vertical = Prefs.orientation(ctx) == Prefs.ORIENT_V
        styleChip(chipHoriz, !vertical)
        styleChip(chipVert, vertical)
        tvOrientHint.text = if (vertical) {
            "مناسب للمانجا: تُرتَّب الفقاعات والأعمدة من اليمين إلى اليسار، وتظهر الترجمة على خلفية فاتحة تشبه فقاعة الحوار."
        } else {
            "مناسب للألعاب والنصوص العادية: تُقرأ الأسطر من الأعلى للأسفل ومن اليسار لليمين."
        }
    }

    private fun refreshBg() {
        val ctx = activity ?: return
        val current = Prefs.overlayBg(ctx)
        bgChips.forEachIndexed { i, chip -> styleChip(chip, i == current) }
    }

    private fun styleChip(chip: TextView, on: Boolean) {
        val ctx = activity ?: return
        chip.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        chip.setTextColor(ctx.getColor(if (on) R.color.white else R.color.text_secondary))
    }

    private fun renderPreview() {
        val ctx = activity ?: return
        val d = resources.displayMetrics.density
        val px = (Prefs.bubbleSize(ctx) * d).toInt()
        previewHolder.removeAllViews()
        val bubble = LayoutInflater.from(ctx).inflate(R.layout.floating_bubble, previewHolder, false)
        BubbleStyle.applyInner(bubble, px)
        bubble.alpha = Prefs.bubbleAlpha(ctx) / 100f
        previewHolder.addView(bubble, FrameLayout.LayoutParams(px, px, Gravity.CENTER))
    }
}
