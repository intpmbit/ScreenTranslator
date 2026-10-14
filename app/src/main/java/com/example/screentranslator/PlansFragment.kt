package com.example.screentranslator

import android.app.Fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

@Suppress("DEPRECATION")
class PlansFragment : Fragment() {

    private class Plan(
        val name: String,
        val price: String,
        val period: String,
        val desc: String,
        val badge: String?,
        val features: List<String>,
        val button: String,
        val highlight: Boolean,
        val current: Boolean
    )

    private val plans = listOf(
        Plan(
            "المجانية", "0$", "للأبد",
            "كل ما تحتاجه للترجمة اليومية",
            null,
            listOf(
                "ترجمة محلية تعمل بدون إنترنت بعد تحميل النموذج",
                "أربع لغات: الإنجليزية واليابانية والكورية والصينية",
                "النافذة المنبثقة والتعريب الشفاف المباشر",
                "تخصيص حجم الفقاعة وشفافيتها"
            ),
            "خطتك الحالية", false, true
        ),
        Plan(
            "برو", "2.99$", "شهرياً",
            "ترجمة أذكى تفهم السياق",
            "الأكثر طلباً",
            listOf(
                "ترجمة بالسياق بالذكاء الاصطناعي (قريباً)",
                "تنظيف النص قبل الترجمة (قريباً)",
                "قاموس مصطلحات الألعاب (قريباً)",
                "سجل الترجمات (قريباً)"
            ),
            "قريباً", true, false
        ),
        Plan(
            "ألتيميت", "5.99$", "شهرياً",
            "للقرّاء واللاعبين المتواصلين",
            null,
            listOf(
                "كل مزايا برو",
                "ترجمة تلقائية مستمرة أثناء اللعب (قريباً)",
                "لغات إضافية (قريباً)",
                "دعم ذو أولوية (قريباً)"
            ),
            "قريباً", false, false
        )
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_plans, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val list = view.findViewById<LinearLayout>(R.id.plansContainer)
        val inflater = LayoutInflater.from(ctx)

        for (plan in plans) {
            val card = inflater.inflate(R.layout.item_plan, list, false)
            card.findViewById<TextView>(R.id.planName).text = plan.name
            card.findViewById<TextView>(R.id.planPrice).text = plan.price
            card.findViewById<TextView>(R.id.planPeriod).text = plan.period
            card.findViewById<TextView>(R.id.planDesc).text = plan.desc

            val badge = card.findViewById<TextView>(R.id.planBadge)
            if (plan.badge != null) {
                badge.text = plan.badge
                badge.visibility = View.VISIBLE
            }

            if (plan.highlight) card.setBackgroundResource(R.drawable.bg_card_highlight)

            val features = card.findViewById<LinearLayout>(R.id.featuresContainer)
            for (f in plan.features) {
                val row = inflater.inflate(R.layout.item_feature, features, false)
                row.findViewById<TextView>(R.id.featureText).text = f
                features.addView(row)
            }

            val button = card.findViewById<View>(R.id.planButton)
            val label = card.findViewById<TextView>(R.id.planButtonLabel)
            label.text = plan.button
            if (plan.highlight) {
                button.setBackgroundResource(R.drawable.bg_btn_primary)
                label.setTextColor(ctx.getColor(R.color.white))
            } else {
                button.setBackgroundResource(R.drawable.bg_btn_outline)
                label.setTextColor(ctx.getColor(R.color.text_primary))
            }
            if (plan.current) {
                button.alpha = 0.6f
            } else {
                button.setOnClickListener {
                    Toast.makeText(ctx, "هذه الباقة قادمة قريباً", Toast.LENGTH_SHORT).show()
                }
            }
            list.addView(card)
        }
    }
}
