package com.example.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** طبقة تغطي الشاشة وتطبع الترجمة فوق مكان النص الأصلي. */
class TranslationOverlayView(context: Context) : View(context) {

    class Item(val rect: Rect, val text: String)

    private class Drawn(val box: RectF, val layout: StaticLayout, val textTop: Float)

    private val d = context.resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawn: List<Drawn> = emptyList()

    /**
     * @param bgAlpha شفافية الخلفية 0..255
     * @param light خلفية فاتحة بنص داكن (تشبه فقاعة المانجا)
     * @param widen توسيع الصندوق الضيق (النص العمودي) ليتسع للنص العربي الأفقي
     */
    fun setData(
        items: List<Item>, bgAlpha: Int, light: Boolean, widen: Boolean,
        screenW: Int, screenH: Int
    ) {
        val base = if (light) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
        bgPaint.color = Color.argb(bgAlpha, Color.red(base), Color.green(base), Color.blue(base))
        val textColor = if (light) Color.parseColor("#0F172A") else Color.WHITE

        val pad = 4 * d
        val inner = 4 * d
        val result = ArrayList<Drawn>()

        for (item in items) {
            val r = RectF(item.rect)
            if (widen) {
                val target = max(r.width(), min(r.height() * 0.9f, screenW * 0.5f))
                val cx = r.centerX()
                r.left = cx - target / 2f
                r.right = cx + target / 2f
            }
            r.inset(-pad, -pad)
            if (r.left < 0f) r.offset(-r.left, 0f)
            if (r.right > screenW) r.offset(screenW - r.right, 0f)
            if (r.top < 0f) r.offset(0f, -r.top)
            if (r.bottom > screenH) r.offset(0f, screenH - r.bottom)

            val availW = (r.width() - 2 * inner).toInt().coerceAtLeast(10)
            val availH = r.height() - 2 * inner
            val maxSize = min(availH, 26 * d)
            val minSize = 9 * d

            var size = max(maxSize, minSize)
            var layout: StaticLayout
            while (true) {
                layout = buildLayout(item.text, availW, size, textColor, light)
                if (layout.height <= availH || size <= minSize) break
                size -= d
            }
            val top = r.top + inner + max(0f, (availH - layout.height) / 2f)
            result.add(Drawn(r, layout, top))
        }
        drawn = result
        invalidate()
    }

    private fun buildLayout(
        text: String, width: Int, size: Float, color: Int, light: Boolean
    ): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (!light) setShadowLayer(2 * d, 0f, 1f, Color.BLACK)
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        // نعوّض أي إزاحة للنافذة حتى تتطابق الإحداثيات مع إحداثيات الشاشة
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

        val radius = 8 * d
        for (item in drawn) {
            canvas.drawRoundRect(item.box, radius, radius, bgPaint)
            canvas.save()
            canvas.translate(item.box.left + 4 * d, item.textTop)
            item.layout.draw(canvas)
            canvas.restore()
        }
    }
}
