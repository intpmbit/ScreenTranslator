package com.example.screentranslator

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
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
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlin.math.abs
import kotlin.math.min

class FloatingService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val PREFS = "prefs"
        const val KEY_SRC = "src_lang"

        @Volatile
        var isRunning = false
    }

    // -------------------------------------------------------------------
    // الحالة
    // -------------------------------------------------------------------
    /** هل الترجمة موقوفة مؤقتاً (الخدمة تعمل지만 لا تُترجم). */
    var isPaused = false
        private set

    /** هل الفقاعة مخفية (المستخدم أخفاها، الخدمة ما زالت تعمل). */
    var isBubbleHidden = false
        private set

    /** هل لوحة التحكم ظاهرة. */
    var isPanelVisible = false
        private set

    // -------------------------------------------------------------------
    // نوافذ النظام
    // -------------------------------------------------------------------
    private lateinit var windowManager: WindowManager
    private lateinit var inflater: LayoutInflater
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    // الفقاعة
    private var bubbleView: View? = null

    // بطاقة الترجمة (الناتج)
    private var cardView: View? = null
    private var cStatusRow: View? = null
    private var cProgress: View? = null
    private var cStatus: TextView? = null
    private var cScroll: ScrollView? = null
    private var cTranslation: TextView? = null
    private var cCopy: View? = null
    private var cCopyLabel: TextView? = null
    private var cLang: TextView? = null
    private var lastTranslation = ""
    private var currentLangLabel = ""

    // لوحة التحكم العائمة
    private var panelView: View? = null
    private var panelPauseBtn: View? = null
    private var panelPauseLabel: TextView? = null
    private var panelHideBtn: TextView? = null
    private var panelStopBtn: View? = null
    private var panelTranslateBtn: View? = null

    // التقاط الشاشة
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

    private val density get() = resources.displayMetrics.density

    // -------------------------------------------------------------------
    // دورة حياة الخدمة
    // -------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        inflater = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_ScreenTranslator))
        bgThread = HandlerThread("capture-thread").also { it.start() }
        bgHandler = Handler(bgThread.looper)
        showBubble()
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        // معالجة الإجراءات الواردة من الإشعارات / لوحة التحكم
        val action = intent?.action
        when (action) {
            NotificationActionReceiver.ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            NotificationActionReceiver.ACTION_PAUSE -> {
                setPaused(true)
                updateNotification()
                return START_NOT_STICKY
            }
            NotificationActionReceiver.ACTION_RESUME -> {
                setPaused(false)
                updateNotification()
                return START_NOT_STICKY
            }
            NotificationActionReceiver.ACTION_TRANSLATE -> {
                if (!isPaused && projection != null) {
                    onBubbleClicked()
                }
                return START_NOT_STICKY
            }
        }

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
        isRunning = false
        isPaused = false
        isBubbleHidden = false
        isPanelVisible = false

        removeCard()
        removePanel()
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

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(1)
        super.onDestroy()
    }

    // -------------------------------------------------------------------
    // الواجهة الأمامية والإشعارات
    // -------------------------------------------------------------------

    private fun startAsForeground() {
        val channelId = "floating_bubble_channel"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(channelId, "مترجم الشاشة", NotificationManager.IMPORTANCE_LOW)
        )

        val pauseAction = PendingIntent.getService(
            this, 101,
            Intent(this, FloatingService::class.java).setAction(NotificationActionReceiver.ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val resumeAction = PendingIntent.getService(
            this, 102,
            Intent(this, FloatingService::class.java).setAction(NotificationActionReceiver.ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = PendingIntent.getService(
            this, 103,
            Intent(this, FloatingService::class.java).setAction(NotificationActionReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val translateAction = PendingIntent.getService(
            this, 104,
            Intent(this, FloatingService::class.java).setAction(NotificationActionReceiver.ACTION_TRANSLATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, channelId)
            .setContentTitle("مترجم الشاشة")
            .setContentText(if (isPaused) "موقوف مؤقتاً" else "يعمل في الخلفية")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, if (isPaused) "استئناف" else "إيقاف مؤقت", pauseAction)
            .addAction(android.R.drawable.ic_media_play, "ترجمة الآن", translateAction)
            .addAction(android.R.drawable.ic_delete, "إيقاف الترجمة", stopAction)

        val notification = builder.build()

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

    /** تحديث نص الإشعارات حسب الحالة الحالية. */
    private fun updateNotification() {
        if (!isRunning) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "floating_bubble_channel"
        manager.createNotificationChannel(
            NotificationChannel(channelId, "مترجم الشاشة", NotificationManager.IMPORTANCE_LOW)
        )

        val pauseAction = PendingIntent.getService(
            this, 101,
            Intent(this, FloatingService::class.java).setAction(NotificationActionReceiver.ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val resumeAction = PendingIntent.getService(
            this, 102,
            Intent(this, FloatingService::class.java).setAction(NotificationActionReceiver.ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = PendingIntent.getService(
            this, 103,
            Intent(this, FloatingService::class.java).setAction(NotificationActionReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val translateAction = PendingIntent.getService(
            this, 104,
            Intent(this, FloatingService::class.java).setAction(NotificationActionReceiver.ACTION_TRANSLATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, channelId)
            .setContentTitle("مترجم الشاشة")
            .setContentText(if (isPaused) "موقوف مؤقتاً" else "يعمل في الخلفية")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, if (isPaused) "استئناف" else "إيقاف مؤقت", pauseAction)
            .addAction(android.R.drawable.ic_media_play, "ترجمة الآن", translateAction)
            .addAction(android.R.drawable.ic_delete, "إيقاف الترجمة", stopAction)

        manager.notify(1, builder.build())
    }

    @Suppress("DEPRECATION")
    private fun getProjectionData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra<Intent>(EXTRA_DATA)
        }
    }

    // -------------------------------------------------------------------
    // التحكم في الإيقاف المؤقت / الاستئناف / الإخفاء
    // -------------------------------------------------------------------

    private fun setPaused(paused: Boolean) {
        isPaused = paused
        panelPauseLabel?.text = if (paused) "استئناف" else "إيقاف مؤقت"
        panelPauseBtn?.setBackgroundResource(
            if (paused) R.drawable.bg_btn_primary else R.drawable.bg_btn_outline
        )
        updateNotification()
    }

    private fun togglePause() {
        setPaused(!isPaused)
    }

    /** إظهار / إخفاء الفقاعة فقط (البقاء في الخدمة). */
    private fun toggleBubbleVisibility() {
        isBubbleHidden = !isBubbleHidden
        bubbleView?.visibility = if (isBubbleHidden) View.GONE else View.VISIBLE
        panelHideBtn?.setText(if (isBubbleHidden) "إظهار الفقاعة" else "إخفاء الفقاعة")
        removePanel()
    }

    // -------------------------------------------------------------------
    // التقاط الشاشة
    // -------------------------------------------------------------------

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

    // -------------------------------------------------------------------
    // خط المعالجة: التقاط → OCR → ترجمة → عرض
    // -------------------------------------------------------------------

    private fun setBusy(value: Boolean) {
        busy = value
        bubbleView?.alpha = if (value) 0.6f else 1f
    }

    /** النقر على الفقاعة يفتح لوحة التحكم بدلاً من الترجمة المباشرة. */
    private fun onBubbleClicked() {
        if (busy || projection == null) return
        if (isPanelVisible) {
            removePanel()
        } else {
            showPanel()
        }
    }

    /** الترجمة الفورية من لوحة التحكم / الإشعار. */
    private fun translateNow() {
        if (busy || isPaused || projection == null) return
        removePanel()
        setBusy(true)
        bubbleView?.visibility = View.INVISIBLE

        val resized = syncDisplaySize()
        mainHandler.postDelayed({ captureAndProcess() }, if (resized) 700L else 350L)
    }

    private fun captureAndProcess() {
        val bitmap = grabBitmap()
        bubbleView?.visibility = View.VISIBLE

        if (bitmap == null) {
            toast("تعذر التقاط الشاشة، حاول مرة أخرى")
            setBusy(false)
            return
        }
        showLoading("جارٍ قراءة النص...")
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
            TextRecognition.getClient()
    }

    private fun langName(lang: String) = when (lang) {
        TranslateLanguage.JAPANESE -> "اليابانية"
        TranslateLanguage.KOREAN -> "الكورية"
        TranslateLanguage.CHINESE -> "الصينية"
        else -> "الإنجليزية"
    }

    private fun runOcr(bitmap: Bitmap) {
        val lang = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_SRC, TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
        currentLangLabel = "${langName(lang)}  ←  العربية"
        cLang?.text = currentLangLabel
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
                    showMessage("لم يتم العثور على نص في الشاشة.\nتأكد أن لغة النص مطابقة للغة المختارة في التطبيق.")
                    setBusy(false)
                } else {
                    translateBlocks(lang, blocks)
                }
            }
            .addOnFailureListener { e ->
                bitmap.recycle()
                recognizer.close()
                showMessage("فشلت قراءة النص: ${e.message}")
                setBusy(false)
            }
    }

    private fun translateBlocks(lang: String, blocks: List<String>) {
        showLoading("جارٍ الترجمة...\n(في أول مرة يتم تحميل نموذج اللغة ويحتاج إنترنت)")

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
                    if (out.isEmpty()) {
                        showMessage("فشلت الترجمة.")
                    } else {
                        showResult(out)
                    }
                    setBusy(false)
                }
            }
            .addOnFailureListener { e ->
                showMessage("فشل تحميل نموذج الترجمة.\nتأكد من الإنترنت (مطلوب أول مرة فقط).\n${e.message}")
                setBusy(false)
            }
    }

    // -------------------------------------------------------------------
    // بطاقة الترجمة
    // -------------------------------------------------------------------

    private fun ensureCard() {
        if (cardView != null) return
        val d = density
        val screen = screenSize()

        val root = inflater.inflate(R.layout.dialog_translation, null)
        cStatusRow = root.findViewById(R.id.statusRow)
        cProgress = root.findViewById(R.id.progress)
        cStatus = root.findViewById(R.id.tvStatus)
        cScroll = root.findViewById(R.id.scroll)
        cTranslation = root.findViewById(R.id.tvTranslation)
        cCopy = root.findViewById(R.id.btnCopy)
        cCopyLabel = root.findViewById(R.id.btnCopyLabel)
        cLang = root.findViewById(R.id.tvLangChip)
        cLang?.text = currentLangLabel

        root.findViewById<View>(R.id.ivCloseTop).setOnClickListener { removeCard() }
        root.findViewById<View>(R.id.btnClose).setOnClickListener { removeCard() }
        cCopy?.setOnClickListener { copyTranslation() }

        val params = WindowManager.LayoutParams(
            screen[0] - (24 * d).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (28 * d).toInt()
        }

        windowManager.addView(root, params)
        cardView = root

        root.alpha = 0f
        root.translationY = 40 * d
        root.animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun showLoading(message: String) {
        ensureCard()
        cStatusRow?.visibility = View.VISIBLE
        cProgress?.visibility = View.VISIBLE
        cStatus?.text = message
        cScroll?.visibility = View.GONE
        cCopy?.visibility = View.GONE
    }

    private fun showMessage(message: String) {
        ensureCard()
        cStatusRow?.visibility = View.VISIBLE
        cProgress?.visibility = View.GONE
        cStatus?.text = message
        cScroll?.visibility = View.GONE
        cCopy?.visibility = View.GONE
    }

    private fun showResult(items: List<String>) {
        ensureCard()
        lastTranslation = items.joinToString("\n\n")
        val shown = items.joinToString(separator = "\n\n• ", prefix = "• ")

        cStatusRow?.visibility = View.GONE
        cTranslation?.text = shown
        cScroll?.visibility = View.VISIBLE
        cCopy?.visibility = View.VISIBLE
        cCopyLabel?.text = "نسخ الترجمة"
        fitScroll()
    }

    private fun fitScroll() {
        val tv = cTranslation ?: return
        val scroll = cScroll ?: return
        val d = density
        val screen = screenSize()
        val textWidth = screen[0] - (24 * d).toInt() - (40 * d).toInt()
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(textWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxH = (screen[1] * 0.35).toInt()
        val lp = scroll.layoutParams
        lp.height = min(tv.measuredHeight, maxH)
        scroll.layoutParams = lp
    }

    private fun copyTranslation() {
        if (lastTranslation.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("translation", lastTranslation))
        cCopyLabel?.text = "تم النسخ ✓"
        mainHandler.postDelayed({ cCopyLabel?.text = "نسخ الترجمة" }, 1500)
    }

    private fun removeCard() {
        cardView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { e.printStackTrace() }
        }
        cardView = null
        cStatusRow = null
        cProgress = null
        cStatus = null
        cScroll = null
        cTranslation = null
        cCopy = null
        cCopyLabel = null
        cLang = null
    }

    // -------------------------------------------------------------------
    // لوحة التحكم العائمة
    // -------------------------------------------------------------------

    private fun showPanel() {
        if (isPanelVisible) return
        val d = density
        val screen = screenSize()

        val root = inflater.inflate(R.layout.control_panel, null)
        panelPauseBtn = root.findViewById(R.id.btnPause)
        panelPauseLabel = root.findViewById(R.id.tvPauseLabel)
        panelHideBtn = root.findViewById(R.id.btnHide)
        panelStopBtn = root.findViewById(R.id.btnStop)
        panelTranslateBtn = root.findViewById(R.id.btnTranslate)

        panelPauseLabel?.text = if (isPaused) "استئناف" else "إيقاف مؤقت"
        panelPauseBtn?.setBackgroundResource(
            if (isPaused) R.drawable.bg_btn_primary else R.drawable.bg_btn_outline
        )
        panelHideBtn?.setText(if (isBubbleHidden) "إظهار الفقاعة" else "إخفاء الفقاعة")

        panelPauseBtn?.setOnClickListener { togglePause() }
        panelHideBtn?.setOnClickListener { toggleBubbleVisibility() }
        panelStopBtn?.setOnClickListener { stopSelf() }
        panelTranslateBtn?.setOnClickListener { translateNow() }

        // إغلاق اللوحة بالنقر خارجها
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                removePanel()
                true
            } else false
        }

        val params = WindowManager.LayoutParams(
            (260 * d).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * d).toInt()
            y = (100 * d).toInt()
        }

        windowManager.addView(root, params)
        panelView = root
        isPanelVisible = true
    }

    private fun removePanel() {
        panelView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { e.printStackTrace() }
        }
        panelView = null
        panelPauseBtn = null
        panelPauseLabel = null
        panelHideBtn = null
        panelStopBtn = null
        panelTranslateBtn = null
        isPanelVisible = false
    }

    // -------------------------------------------------------------------
    // الفقاعة
    // -------------------------------------------------------------------

    private fun showBubble() {
        val d = density
        val size = (64 * d).toInt()
        val bubble = inflater.inflate(R.layout.floating_bubble, null)

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (200 * d).toInt()
        }

        val touchSlop = 10 * d
        var startX = 0
        var startY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var moved = false

        bubble.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    v.foreground?.setHotspot(event.x, event.y)
                    v.isPressed = true
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        windowManager.updateViewLayout(v, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (!moved) onBubbleClicked() else snapToEdge(v, params)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, params)
        bubbleView = bubble
        bubbleView?.visibility = if (isBubbleHidden) View.GONE else View.VISIBLE
    }

    /** بعد السحب تلتصق الفقاعة بأقرب حافة جانبية. */
    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val sw = screenSize()[0]
        val target = if (params.x + view.width / 2 < sw / 2) 0 else sw - view.width
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                try { windowManager.updateViewLayout(view, params) } catch (e: Exception) { cancel() }
            }
            start()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
