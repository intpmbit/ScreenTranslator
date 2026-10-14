package com.example.screentranslator

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.FrameLayout

object Prefs {
    const val NAME = "prefs"
    const val KEY_SRC = "src_lang"
    const val KEY_MODE = "display_mode"
    const val KEY_ORIENT = "orientation"
    const val KEY_BUBBLE_SIZE = "bubble_size"
    const val KEY_BUBBLE_ALPHA = "bubble_alpha"
    const val KEY_OVERLAY_BG = "overlay_bg"

    const val MODE_POPUP = "popup"
    const val MODE_OVERLAY = "overlay"
    const val ORIENT_H = "horizontal"
    const val ORIENT_V = "vertical"

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun srcLang(ctx: Context): String = get(ctx).getString(KEY_SRC, "en") ?: "en"
    fun mode(ctx: Context): String = get(ctx).getString(KEY_MODE, MODE_POPUP) ?: MODE_POPUP
    fun orientation(ctx: Context): String = get(ctx).getString(KEY_ORIENT, ORIENT_H) ?: ORIENT_H
    fun bubbleSize(ctx: Context): Int = get(ctx).getInt(KEY_BUBBLE_SIZE, 64).coerceIn(40, 96)
    fun bubbleAlpha(ctx: Context): Int = get(ctx).getInt(KEY_BUBBLE_ALPHA, 100).coerceIn(30, 100)
    fun overlayBg(ctx: Context): Int = get(ctx).getInt(KEY_OVERLAY_BG, 1).coerceIn(0, 2)
}

/** يضبط الأبعاد الداخلية للفقاعة بحسب حجمها (يُستخدم في الخدمة وفي معاينة الإعدادات). */
object BubbleStyle {
    fun applyInner(root: View, sizePx: Int) {
        val core = root.findViewById<View>(R.id.bubbleCore)
        val lp = core.layoutParams as FrameLayout.LayoutParams
        val m = (sizePx * 0.11f).toInt()
        lp.setMargins(m, m, m, m)
        core.layoutParams = lp

        val icon = root.findViewById<View>(R.id.bubbleIcon)
        val ip = icon.layoutParams
        ip.width = (sizePx * 0.4f).toInt()
        ip.height = ip.width
        icon.layoutParams = ip
    }
}
