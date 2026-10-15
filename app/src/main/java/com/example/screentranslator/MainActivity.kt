package com.example.screentranslator

import android.Manifest
import android.app.Activity
import android.app.Fragment
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private val REQ_PROJECTION = 200
    private var tab = 0
    private val navViews = ArrayList<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tab = savedInstanceState?.getInt("tab") ?: 0
        buildNav()
        if (savedInstanceState == null) showTab(0) else updateNav()

        requestNotificationPermissionIfNeeded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    // ---------------------------------------------------------------
    // شريط التنقل (يُبنى من res/menu/menu_bottom_nav.xml)
    // ---------------------------------------------------------------

    private fun buildNav() {
        val bar = findViewById<LinearLayout>(R.id.navBar)
        val menu = PopupMenu(this, bar).menu
        MenuInflater(this).inflate(R.menu.menu_bottom_nav, menu)

        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val v = layoutInflater.inflate(R.layout.item_nav, bar, false)
            v.findViewById<ImageView>(R.id.navIcon).setImageDrawable(item.icon)
            v.findViewById<TextView>(R.id.navLabel).text = item.title
            v.contentDescription = item.title
            v.setOnClickListener { if (i != tab) showTab(i) }
            bar.addView(v)
            navViews.add(v)
        }
        updateNav()
    }

    private fun updateNav() {
        navViews.forEachIndexed { i, v ->
            val selected = i == tab
            val color = getColor(if (selected) R.color.primary else R.color.text_muted)
            val pill = v.findViewById<View>(R.id.navPill)
            pill.background = if (selected) getDrawable(R.drawable.bg_nav_pill) else null
            v.findViewById<ImageView>(R.id.navIcon).setColorFilter(color)
            v.findViewById<TextView>(R.id.navLabel).setTextColor(color)
        }
    }

    private fun showTab(index: Int) {
        tab = index
        val fragment: Fragment = when (index) {
            0 -> MainFragment()
            1 -> SettingsFragment()
            else -> PlansFragment()
        }
        fragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
        updateNav()
    }

    // ---------------------------------------------------------------
    // أوامر تستدعيها الشاشات
    // ---------------------------------------------------------------

    fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    fun requestStart() {
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
                // اخفاء النشاط عن الواجهة مع بقاء التطبيق متاحاً عند إعادة فتحه
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "لم يتم منح إذن تسجيل الشاشة", Toast.LENGTH_LONG).show()
            }
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
