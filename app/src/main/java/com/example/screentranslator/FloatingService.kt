package com.example.screentranslator

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

class FloatingService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val PREFS = "prefs"
        const val KEY_SRC = "src_lang"
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    private var bubbleView: View? = null
    private var cardView: View? = null
    private var cardText: TextView? = null

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var readerW = 0
    private var readerH = 0

    private val imageLock = Any()
    private var heldImage: Image? = null

    private var busy = false
    private val translators = HashMap<String, Translator>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bgThread = HandlerThread("capture-thread").also { it.start() }
        bgHandler = Handler(bgThread.looper)
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        if (projection == null) {
            val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            val data = intent?.let { getProjectionData(it) }
            if (code != Activity.RESULT_OK || data == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            setupProjection(code, data)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeCard()
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { e.printStackTrace() }
        }
        bubbleView = null

        synchronized(imageLock) {
            heldImage?.close()
            heldImage = null
        }
        virtualDisplay?.release()
        imageReader?.close()
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projection?.stop()
        projection = null

        translators.values.forEach { it.close() }
        translators.clear()
        bgThread.quitSafely()
        super.onDestroy()
    }

    // ---------------------------------------------------------------
    // Foreground
    // ---------------------------------------------------------------

    private fun startAsForeground() {
        val channelId = "floating_bubble_channel"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(channelId, "الفقاعة العائمة", NotificationManager.IMPORTANCE_LOW)
        )

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("مترجم الشاشة يعمل")
            .setContentText("اضغط على الفقاعة لترجمة الشاشة")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(
                1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            Build.VERSION.SDK_INT >= 29 -> startForeground(
                1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            else -> startForeground(1, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun getProjectionData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra<Intent>(EXTRA_DATA)
        }
    }

    // ---------------------------------------------------------------
    // التقاط الشاشة (MediaProjection)
    // ---------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun screenSize(): IntArray {
        val m = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(m)
        return intArrayOf(m.widthPixels, m.heightPixels, m.densityDpi)
    }

    private fun setupProjection(code: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)

        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { stopSelf() }
            }
        }
        p.registerCallback(cb, mainHandler)
        projectionCallback = cb
        projection = p

        val (w, h, dpi) = screenSize()
        val reader = buildReader(w, h)
        imageReader = reader
        readerW = w
        readerH = h

        virtualDisplay = p.createVirtualDisplay(
            "ScreenTranslator",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            bgHandler
        )
    }

    private fun buildReader(w: Int, h: Int): ImageReader {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                synchronized(imageLock) {
                    heldImage?.close()
                    heldImage = img
                }
            }
        }, bgHandler)
        return reader
    }

    /** إذا تغيّر اتجاه الشاشة (طولي/عرضي) نعدّل حجم العرض الافتراضي. */
    private fun syncDisplaySize(): Boolean {
        val (w, h, dpi) = screenSize()
        if (w == readerW && h == readerH) return false

        synchronized(imageLock) {
            heldImage?.close()
            heldImage = null
        }
        val newReader = buildReader(w, h)
        virtualDisplay?.resize(w, h, dpi)
        virtualDisplay?.surface = newReader.surface
        imageReader?.close()
        imageReader = newReader
        readerW = w
        readerH = h
        return true
    }

    private fun Image.toBitmapCopy(): Bitmap {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        val result = Bitmap.createBitmap(padded, 0, 0, width, height)
        if (result !== padded) padded.recycle()
        return result
    }

    private fun grabBitmap(): Bitmap? = synchronized(imageLock) {
        try { heldImage?.toBitmapCopy() } catch (e: Exception) { null }
    }

    // ---------------------------------------------------------------
    // النقر على الفقاعة: التقاط -> OCR -> ترجمة -> عرض
    // ---------------------------------------------------------------

    private fun onBubbleClicked() {
        if (busy || projection == null) return
        busy = true

        // نخفي الفقاعة والكارت حتى لا يظهرا في الصورة
        removeCard()
        bubbleView?.visibility = View.INVISIBLE

        val resized = syncDisplaySize()
        mainHandler.postDelayed({ captureAndProcess() }, if (resized) 700L else 350L)
    }

    private fun captureAndProcess() {
        val bitmap = grabBitmap()
        bubbleView?.visibility = View.VISIBLE

        if (bitmap == null) {
            toast("تعذر التقاط الشاشة، حاول مرة أخرى")
            busy = false
            return
        }
        showCard("جارٍ قراءة النص...")
        runOcr(bitmap)
    }

    private fun recognizerFor(lang: String): TextRecognizer = when (lang) {
        TranslateLanguage.JAPANESE ->
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        TranslateLanguage.KOREAN ->
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        TranslateLanguage.CHINESE ->
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        else ->
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun runOcr(bitmap: Bitmap) {
        val lang = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_SRC, TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
        val recognizer = recognizerFor(lang)

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                bitmap.recycle()
                recognizer.close()

                val sep = if (lang == TranslateLanguage.JAPANESE ||
                    lang == TranslateLanguage.CHINESE) "" else " "
                val blocks = result.textBlocks
                    .map { it.text.replace("\n", sep).trim() }
                    .filter { it.length >= 2 }
                    .take(25)

                if (blocks.isEmpty()) {
                    showCard("لم يتم العثور على نص في الشاشة.\nتأكد أن لغة النص مطابقة للغة المختارة في التطبيق.")
                    busy = false
                } else {
                    translateBlocks(lang, blocks)
                }
            }
            .addOnFailureListener { e ->
                bitmap.recycle()
                recognizer.close()
                showCard("فشلت قراءة النص: ${e.message}")
                busy = false
            }
    }

    private fun translateBlocks(lang: String, blocks: List<String>) {
        showCard("جارٍ الترجمة...\n(في أول مرة يتم تحميل نموذج اللغة ويحتاج إنترنت)")

        val translator = translators.getOrPut(lang) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(lang)
                    .setTargetLanguage(TranslateLanguage.ARABIC)
                    .build()
            )
        }

        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                val tasks = blocks.map { translator.translate(it) }
                Tasks.whenAllComplete(tasks).addOnSuccessListener {
                    val out = tasks.filter { it.isSuccessful }.mapNotNull { it.result }
                    showCard(
                        if (out.isEmpty()) "فشلت الترجمة."
                        else out.joinToString(separator = "\n\n• ", prefix = "• ")
                    )
                    busy = false
                }
            }
            .addOnFailureListener { e ->
                showCard("فشل تحميل نموذج الترجمة.\nتأكد من الإنترنت (مطلوب أول مرة فقط).\n${e.message}")
                busy = false
            }
    }

    // ---------------------------------------------------------------
    // كارت الترجمة
    // ---------------------------------------------------------------

    private fun showCard(message: String) {
        if (cardView == null) buildCard()
        cardText?.text = message
    }

    private fun buildCard() {
        val d = resources.displayMetrics.density
        val screen = screenSize()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE202124"))
                cornerRadius = 16 * d
            }
        }

        val close = TextView(this).apply {
            text = "✕  إغلاق"
            textSize = 16f
            setTextColor(Color.parseColor("#FF8A80"))
            setPadding(0, (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            setOnClickListener { removeCard() }
        }

        val tv = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(0f, 1.2f)
        }
        val scroll = ScrollView(this).apply { addView(tv) }

        root.addView(
            close,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (screen[1] * 0.3).toInt()
            )
        )

        val params = WindowManager.LayoutParams(
            screen[0] - (24 * d).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (24 * d).toInt()
        }

        windowManager.addView(root, params)
        cardView = root
        cardText = tv
    }

    private fun removeCard() {
        cardView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { e.printStackTrace() }
        }
        cardView = null
        cardText = null
    }

    // ---------------------------------------------------------------
    // الفقاعة
    // ---------------------------------------------------------------

    private fun showBubble() {
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()

        val bubble = TextView(this).apply {
            text = "文"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC1E88E5"))
                setStroke((2 * density).toInt(), Color.WHITE)
            }
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (200 * density).toInt()
        }

        val touchSlop = 10 * density
        var startX = 0
        var startY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var moved = false

        bubble.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleClicked()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, params)
        bubbleView = bubble
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}