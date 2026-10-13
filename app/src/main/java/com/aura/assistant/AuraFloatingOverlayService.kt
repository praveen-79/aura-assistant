package com.aura.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * iOS AssistiveTouch-Style Circular Floating Bubble & Continuous Cross-App Screen Reader Service.
 *
 * Architecture Highlights:
 * 1. iOS AssistiveTouch Circular Bubble: 58dp floating circular glass orb with concentric rings,
 *    smooth drag & drop, and magnetic snap-to-edge on release.
 * 2. Two-Phase Dual Command Flow: Takes composite commands (e.g. "Open Instagram and search for cars"),
 *    executes step 1 (opens Instagram), and enters silent Standby Mode.
 * 3. Silent Standby & Screen Reader: The assistant goes completely silent (stops continuous talkback),
 *    docks to screen edge at 65% opacity, and ONLY the Screen Reader observer stays active.
 * 4. Comeback Wake Word Trigger: When the user speaks the comeback wake word ("Hey Aura" / "Aura" / "ఆరా")
 *    a second time, Aura comes back from standby, grounds against the active screen reader context,
 *    and fulfills the second command!
 */
class AuraFloatingOverlayService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START_OVERLAY = "com.aura.assistant.START_OVERLAY"
        const val ACTION_UPDATE_APP = "com.aura.assistant.UPDATE_APP"
        const val ACTION_READ_ACTIVE_SCREEN = "com.aura.assistant.READ_ACTIVE_SCREEN"
        const val ACTION_SPEAK_AND_LISTEN = "com.aura.assistant.SPEAK_AND_LISTEN"
        const val ACTION_STOP_OVERLAY = "com.aura.assistant.STOP_OVERLAY"

        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_SPEAK_TEXT = "extra_speak_text"
        const val EXTRA_LANG = "extra_lang"

        var isRunning = false
        var currentActiveApp: String = "Aura Assistant"
        var pendingSecondCommand: String? = null
        var isStandbyMode = false
    }

    private var windowManager: WindowManager? = null
    private var overlayRoot: FrameLayout? = null
    private var bubbleContainer: FrameLayout? = null
    private var orbView: View? = null
    private var expandedMenu: LinearLayout? = null
    private var statusTextView: TextView? = null
    private var appHeaderView: TextView? = null

    private var windowParams: WindowManager.LayoutParams? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isSpeaking = false
    private var isListening = false
    private var continuousMode = true
    private var currentLangTag = "en-IN"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoCollapseRunnable: Runnable? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundNotification()
        tts = TextToSpeech(this, this)
        mainHandler.post {
            initSpeechRecognizer()
            if (canDrawOverlays()) {
                showFloatingOverlayWindow()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        mainHandler.post {
            if (canDrawOverlays() && overlayRoot == null) {
                showFloatingOverlayWindow()
            }
            val act = intent?.action ?: ACTION_START_OVERLAY
            val appName = intent?.getStringExtra(EXTRA_APP_NAME)
            val speakText = intent?.getStringExtra(EXTRA_SPEAK_TEXT)
            val lang = intent?.getStringExtra(EXTRA_LANG)
            if (!lang.isNullOrBlank()) {
                currentLangTag = lang
            }

            when (act) {
                ACTION_STOP_OVERLAY -> {
                    continuousMode = false
                    stopListening()
                    removeOverlayWindow()
                    stopSelf()
                }
                ACTION_UPDATE_APP -> {
                    if (!appName.isNullOrBlank()) {
                        currentActiveApp = appName
                    }
                    appHeaderView?.text = "📱 Screen: $currentActiveApp"
                    val prompt = speakText ?: buildScreenReaderPromptForApp(currentActiveApp)
                    speakAndTakeBreak(prompt, isScreenReaderPrompt = false)
                }
                ACTION_READ_ACTIVE_SCREEN -> {
                    val summary = buildScreenReaderPromptForApp(currentActiveApp)
                    updateStatusText("📖 $summary")
                    speakAndTakeBreak(summary, isScreenReaderPrompt = true)
                }
                ACTION_SPEAK_AND_LISTEN -> {
                    val msg = speakText ?: "Task completed. Screen reader active."
                    updateStatusText("🤖 $msg")
                    speakAndTakeBreak(msg, isScreenReaderPrompt = false)
                }
                else -> {
                    if (!appName.isNullOrBlank()) {
                        currentActiveApp = appName
                    }
                    appHeaderView?.text = "📱 Screen: $currentActiveApp"
                    val msg = speakText ?: "Aura Screen Reader ready on $currentActiveApp."
                    speakAndTakeBreak(msg, isScreenReaderPrompt = false)
                }
            }
        }
        return START_STICKY
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun startForegroundNotification() {
        val channelId = "aura_assistive_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Aura AssistiveTouch & Screen Reader",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Aura AssistiveTouch floating bubble & screen reader active over apps"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("Aura Assistive Screen Reader ($currentActiveApp)")
            .setContentText("Assistive Touch active • Screen Reader observing • Say 'Hey Aura'")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()

        try {
            startForeground(1001, notification)
        } catch (e: Exception) {}
    }

    /**
     * Builds the iOS AssistiveTouch Circular Floating Bubble + Expandable Control Card.
     */
    private fun showFloatingOverlayWindow() {
        if (overlayRoot != null || !canDrawOverlays()) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val bubbleSize = dp(58)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - bubbleSize - dp(12) // Default snap to right edge
            y = dp(140)
        }
        windowParams = params

        // Root container holding the bubble + expanded menu
        val root = FrameLayout(this)

        // 1. iOS AssistiveTouch Floating Circular Bubble (58dp x 58dp)
        val bubble = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#DD0F172A")) // Deep dark translucent glass
                setStroke(dp(2), Color.parseColor("#40FFFFFF")) // iOS frosted outer ring
            }
            elevation = dp(16).toFloat()
            alpha = 0.65f // Idle translucent state
        }
        bubbleContainer = bubble

        // Middle concentric frosted ring (36dp x 36dp)
        val middleRing = View(this).apply {
            val s = dp(36)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#26FFFFFF"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }
        bubble.addView(middleRing)

        // Inner glowing Aura orb (20dp x 20dp)
        val orb = View(this).apply {
            val s = dp(20)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#B3FFFFFF")) // Standby subtle pearl
            }
        }
        orbView = orb
        bubble.addView(orb)

        // 2. Expanded iOS Assistive Menu Card (hidden by default)
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = FrameLayout.LayoutParams(dp(290), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#EA090D1A")) // iOS glass card
                setStroke(dp(2), Color.parseColor("#00F5D4"))
            }
            elevation = dp(22).toFloat()
            visibility = View.GONE
        }
        expandedMenu = menu

        // Menu Header: Active Screen + Screen Reader Tag
        val header = TextView(this).apply {
            text = "📱 Screen: $currentActiveApp"
            setTextColor(Color.parseColor("#00F5D4"))
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        }
        appHeaderView = header
        menu.addView(header)

        // Status description
        val statusTv = TextView(this).apply {
            text = "Screen Reader Active • Call 'Hey Aura'"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        }
        statusTextView = statusTv
        menu.addView(statusTv)

        // Horizontal Action Bar: [🎙️ Mic] [📖 Read Screen] [🤫 Mute] [✕ Close]
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val micBtn = TextView(this).apply {
            text = "🎙️ Speak"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#2600F5D4"))
                setStroke(dp(1), Color.parseColor("#00F5D4"))
            }
            setOnClickListener {
                wakeUpAndStartListening()
            }
        }
        actionsRow.addView(micBtn)

        val spacer1 = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }
        actionsRow.addView(spacer1)

        val readBtn = TextView(this).apply {
            text = "📖 Read"
            setTextColor(Color.parseColor("#FFD166"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#26FFD166"))
                setStroke(dp(1), Color.parseColor("#FFD166"))
            }
            setOnClickListener {
                val summary = buildScreenReaderPromptForApp(currentActiveApp)
                updateStatusText("📖 $summary")
                speakAndTakeBreak(summary, isScreenReaderPrompt = true)
            }
        }
        actionsRow.addView(readBtn)

        val spacer2 = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }
        actionsRow.addView(spacer2)

        val quietBtn = TextView(this).apply {
            text = "🤫 Mute"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#2694A3B8"))
            }
            setOnClickListener {
                enterStandbyScreenReaderMode("Muted • Listening for 'Hey Aura'")
            }
        }
        actionsRow.addView(quietBtn)

        val spacer3 = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }
        actionsRow.addView(spacer3)

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FF6B8B"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                collapseToBubble()
            }
        }
        actionsRow.addView(closeBtn)

        menu.addView(actionsRow)

        root.addView(menu)
        root.addView(bubble)

        // 3. iOS AssistiveTouch Drag & Magnetic Snap-to-Edge Interaction
        var initialX = 0
        var initialY = 0
        var touchDownX = 0f
        var touchDownY = 0f
        var isDragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    isDragging = false
                    bubble.alpha = 1.0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownX).toInt()
                    val dy = (event.rawY - touchDownY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            windowManager?.updateViewLayout(root, params)
                        } catch (e: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // User tapped the AssistiveTouch bubble -> Toggle menu!
                        toggleExpandedMenu()
                    } else {
                        // Drag released -> Snap smoothly to nearest left or right edge!
                        val screenW = resources.displayMetrics.widthPixels
                        val snapX = if (params.x + (bubbleSize / 2) < screenW / 2) {
                            dp(12) // Snap to left edge
                        } else {
                            screenW - bubbleSize - dp(12) // Snap to right edge
                        }
                        params.x = snapX
                        try {
                            windowManager?.updateViewLayout(root, params)
                        } catch (e: Exception) {}
                        if (isStandbyMode && expandedMenu?.visibility != View.VISIBLE) {
                            bubble.alpha = 0.65f
                        }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(root, params)
            overlayRoot = root
        } catch (e: Exception) {}
    }

    private fun toggleExpandedMenu() {
        mainHandler.post {
            val menu = expandedMenu ?: return@post
            if (menu.visibility == View.VISIBLE) {
                collapseToBubble()
            } else {
                expandToMenu()
            }
        }
    }

    private fun expandToMenu() {
        mainHandler.post {
            bubbleContainer?.alpha = 1.0f
            appHeaderView?.text = "📱 Screen: $currentActiveApp"
            expandedMenu?.visibility = View.VISIBLE
            scheduleAutoCollapse()
        }
    }

    private fun collapseToBubble() {
        mainHandler.post {
            expandedMenu?.visibility = View.GONE
            if (isStandbyMode) {
                bubbleContainer?.alpha = 0.65f
            }
        }
    }

    private fun scheduleAutoCollapse() {
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        autoCollapseRunnable = Runnable {
            if (!isListening && !isSpeaking) {
                collapseToBubble()
            }
        }
        mainHandler.postDelayed(autoCollapseRunnable!!, 6000)
    }

    private fun updateOrbColor(colorInt: Int) {
        mainHandler.post {
            (orbView?.background as? GradientDrawable)?.setColor(colorInt)
        }
    }

    private fun updateStatusText(text: String) {
        mainHandler.post {
            statusTextView?.text = text
        }
    }

    private fun removeOverlayWindow() {
        mainHandler.post {
            overlayRoot?.let {
                try {
                    windowManager?.removeView(it)
                } catch (e: Exception) {}
            }
            overlayRoot = null
            bubbleContainer = null
            orbView = null
            expandedMenu = null
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            val teLocale = Locale("te", "IN")
            val teAvailable = tts?.isLanguageAvailable(teLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (teAvailable >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = teLocale
            } else {
                tts?.language = Locale("en", "IN")
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    updateOrbColor(Color.parseColor("#A855F7")) // Violet glowing when speaking
                    mainHandler.post {
                        stopListening()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    val isSr = utteranceId?.contains("screen_reader") == true
                    mainHandler.post {
                        if (isSr) {
                            wakeUpAndStartListening()
                        } else {
                            // After completing the command, TAKE A BREAK and enter Silent Standby!
                            enterStandbyScreenReaderMode("Screen Reader Active • Call 'Hey Aura'")
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    mainHandler.post {
                        enterStandbyScreenReaderMode("Screen Reader Active • Call 'Hey Aura'")
                    }
                }
            })
        }
    }

    private fun transliterateIndicToLatin(text: String): String {
        var s = text
        val map = mapOf(
            "యూట్యూబ్" to "YouTube",
            "వాట్సప్" to "WhatsApp",
            "ఇన్‌స్టా" to "Instagram",
            "తెరుస్తున్నాను" to "terustunnanu",
            "తెరిచాను" to "terichanu",
            "ప్లే చేస్తున్నాను" to "play chestunnanu",
            "కాల్ చేస్తున్నాను" to "call chestunnanu",
            "మెసేజ్ పంపుతున్నాను" to "message pamputunnanu",
            "సరే" to "Sare",
            "నమస్కారం" to "Namaskaram",
            "పూర్తయింది" to "poorthi ayindi",
            "మాట్లాడటం ఆపాను" to "maatladatam aapanu",
            "చెప్పండి" to "Cheppandi",
            "వింటున్నాను" to "vintunnanu",
            "బాగున్నారా" to "baagunnara",
            "ధన్యవాదాలు" to "dhanyavaadalu"
        )
        for ((k, v) in map) {
            s = s.replace(k, v)
        }
        return s.replace(Regex("[\\u0C00-\\u0C7F]"), "")
    }

    /**
     * Speaks brief voice response, then enters Silent Standby Mode so talkback stops.
     */
    fun speakAndTakeBreak(textToSpeak: String, isScreenReaderPrompt: Boolean = false) {
        val clean = textToSpeak.replace(Regex("[*_`#⚡✅📖🪟📸💬🔋🔊⏲⏰🔦🎵🗺🚀🤖🌸📘🧮]"), "").trim()
        if (clean.isEmpty()) {
            enterStandbyScreenReaderMode("Screen Reader Active • Call 'Hey Aura'")
            return
        }
        stopListening()
        if (!ttsReady || tts == null) {
            enterStandbyScreenReaderMode("Screen Reader Active • Call 'Hey Aura'")
            return
        }
        isSpeaking = true
        updateOrbColor(Color.parseColor("#A855F7")) // Violet glowing when speaking

        val hasTeluguScript = Regex("[\\u0C00-\\u0C7F]").containsMatchIn(clean)
        val textForTts = if (hasTeluguScript) {
            val teLocale = Locale("te", "IN")
            val teAvail = tts?.isLanguageAvailable(teLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (teAvail >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = teLocale
                clean
            } else {
                tts?.language = Locale("en", "IN")
                transliterateIndicToLatin(clean)
            }
        } else {
            tts?.language = Locale("en", "IN")
            clean
        }

        val params = Bundle()
        val tag = if (isScreenReaderPrompt) "screen_reader_${System.currentTimeMillis()}" else "task_break_${System.currentTimeMillis()}"
        tts?.speak(textForTts, TextToSpeech.QUEUE_FLUSH, params, tag)
    }

    /**
     * Enters SILENT STANDBY MODE:
     * - Assistant STOPS talking.
     * - Bubble docks at screen edge as an iOS AssistiveTouch circular orb with 65% opacity.
     * - ONLY the Screen Reader remains active in the background, observing the active screen.
     * - Silently listens for the comeback wake word ("Hey Aura" / "Aura" / "ఆరా").
     */
    fun enterStandbyScreenReaderMode(statusMsg: String) {
        isStandbyMode = true
        updateOrbColor(Color.parseColor("#B3FFFFFF")) // Standby subtle pearl ring
        updateStatusText(statusMsg)
        collapseToBubble()
        startListeningNow() // Silently listens for wake word
    }

    private fun wakeUpAndStartListening() {
        isStandbyMode = false
        bubbleContainer?.alpha = 1.0f
        updateOrbColor(Color.parseColor("#00F5D4")) // Cyan glowing active mic
        updateStatusText("🟢 Listening in $currentActiveApp... Speak command!")
        startListeningNow()
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        if (isStandbyMode) {
                            updateOrbColor(Color.parseColor("#B3FFFFFF"))
                            updateStatusText("💤 Standby • Say 'Hey Aura' / 'ఆరా'")
                        } else {
                            updateOrbColor(Color.parseColor("#00F5D4"))
                            updateStatusText("🟢 Listening in $currentActiveApp...")
                        }
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        isListening = false
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        if (!isSpeaking) {
                            mainHandler.postDelayed({
                                if (!isSpeaking) {
                                    startListeningNow()
                                }
                            }, 1200)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull()?.trim() ?: ""
                        if (spoken.isNotEmpty()) {
                            handleSpokenOverlayCommand(spoken)
                        } else if (!isSpeaking) {
                            mainHandler.postDelayed({ startListeningNow() }, 800)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        if (!partial.isNullOrBlank() && !isStandbyMode) {
                            updateStatusText("🎙️ \"$partial\" ...")
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {}
    }

    private fun startListeningNow() {
        if (isSpeaking) return
        if (speechRecognizer == null) {
            initSpeechRecognizer()
        }
        val sr = speechRecognizer ?: return
        val recIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("te-IN", "en-IN", "hi-IN"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            sr.cancel()
            sr.startListening(recIntent)
            isListening = true
            if (isStandbyMode) {
                updateOrbColor(Color.parseColor("#B3FFFFFF"))
            } else {
                updateOrbColor(Color.parseColor("#00F5D4"))
            }
        } catch (e: Exception) {
            isListening = false
        }
    }

    private fun stopListening() {
        isListening = false
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {}
    }

    private fun buildScreenReaderPromptForApp(appName: String): String {
        val key = appName.lowercase()
        return when {
            "insta" in key ->
                "స్క్రీన్ రీడర్: మీరు Instagram లో ఉన్నారు. Reels ఓపెన్ చేయవచ్చు లేదా సెర్చ్ చేయవచ్చు. తదుపరి కమాండ్ చెప్పండి."
            "whatsapp" in key ->
                "స్క్రీన్ రీడర్: మీరు WhatsApp లో ఉన్నారు. మెసేజ్ లేదా కాల్ చేయవచ్చు. తదుపరి కమాండ్ చెప్పండి."
            "youtube" in key ->
                "స్క్రీన్ రీడర్: మీరు YouTube లో ఉన్నారు. వీడియో లేదా పాట ప్లే చేయవచ్చు. తదుపరి కమాండ్ చెప్పండి."
            "map" in key ->
                "స్క్రీన్ రీడర్: మీరు Google Maps లో ఉన్నారు. లొకేషన్ నావిగేట్ చేయవచ్చు. తదుపరి కమాండ్ చెప్పండి."
            else ->
                "స్క్రీన్ రీడర్: మీరు $appName స్క్రీన్ పై ఉన్నారు. తదుపరి కమాండ్ చెప్పండి."
        }
    }

    /**
     * Splits compound/dual commands using conjunctions across English, Telugu, and Hindi:
     * e.g., "open instagram and search for cars" -> ("open instagram", "search for cars")
     * e.g., "ఇన్‌స్టాగ్రామ్ ఓపెన్ చేసి కార్స్ సెర్చ్ చేయి" -> ("ఇన్‌స్టాగ్రామ్ ఓపెన్", "కార్స్ సెర్చ్ చేయి")
     */
    private fun splitDualCommand(spokenText: String): Pair<String, String?> {
        val clean = spokenText.trim()
        val delimiters = listOf(
            Regex("(?i)\\s+(?:and\\s+then|and|then|after\\s+that)\\s+"),
            Regex("\\s+(?:చేసి|మరియు\\s+తర్వాత|మరియు|తర్వాత|తరువాత)\\s+"),
            Regex("(?i)\\s+(?:aur\\s+phir|aur|ke\\s+baad)\\s+")
        )
        for (del in delimiters) {
            val parts = clean.split(del, limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                return Pair(parts[0].trim(), parts[1].trim())
            }
        }
        return Pair(clean, null)
    }

    /**
     * Core Command & Comeback Wake Word Engine.
     */
    private fun handleSpokenOverlayCommand(spokenRaw: String) {
        val spoken = spokenRaw.trim()

        // 1. Keep Quiet / Stop / Take a Break ("keep quiet", "మాట్లాడకు", "నోరు మూసుకో", "ఆగు")
        val isQuietIntent = Regex("(?i)\\b(keep quiet|be quiet|quiet|shut up|stop talking|silent|silence|hush|stop speaking|take a break|pause|stay quiet|dont speak|don't speak|maatladaku|matladaku|noru moosuko|noru mooyi|aagu|aapu|chalu|shanthanga undu|shantham|chup raho|chup|shant raho|mat bolo)\\b").containsMatchIn(spoken) ||
            spoken.contains("మాట్లాడకు") || spoken.contains("నోరు మూసుకో") || spoken.contains("నోరు మూయి") || spoken.contains("ఆగు") || spoken.contains("శాంతంగా ఉండు") || spoken.contains("శాంతం") || spoken.contains("చాలు") || spoken.contains("వద్దు") || spoken.contains("ఆపు") || spoken.contains("शांत रहो") || spoken.contains("चुप रहो") || spoken.contains("चुप")
        if (isQuietIntent) {
            stopListening()
            try { tts?.stop() } catch (e: Exception) {}
            isSpeaking = false
            enterStandbyScreenReaderMode("🤫 Keeping quiet • Say 'Hey Aura' to wake")
            return
        }

        // 2. COMEBACK WAKE WORD DETECTION ("Hey Aura", "Aura", "హే ఆరా", "ఆరా", "ఓకే ఆరా")
        val isWakeOnly = Regex("(?i)^(hey aura|aura|hello aura|hi aura|ok aura|హే ఆరా|ఆరా|హలో ఆరా|ఓకే ఆరా|हे ऑरा|ऑरा)$").matches(spoken)
        if (isWakeOnly) {
            // Assistant comes back from Standby!
            isStandbyMode = false
            expandToMenu()

            // Check if there was a pending second command queued from a dual command!
            val pending = pendingSecondCommand
            if (!pending.isNullOrBlank()) {
                pendingSecondCommand = null
                val activity = MainActivity.instance
                val reply = if (activity != null) {
                    activity.executeVoiceCommandFromOverlay(pending, currentActiveApp)
                } else {
                    "Executing second task: $pending"
                }
                updateStatusText("✅ $reply")
                speakAndTakeBreak(reply, isScreenReaderPrompt = false)
                return
            }

            // No pending task: announce screen context and listen for command 2!
            val wakePrompt = if (spoken.contains("ఆరా") || Regex("[\\u0C00-\\u0C7F]").containsMatchIn(spoken)) {
                "నేను $currentActiveApp లో ఉన్నాను. తదుపరి కమాండ్ చెప్పండి!"
            } else {
                "I'm on $currentActiveApp. What command should I perform next?"
            }
            updateStatusText("🟢 $wakePrompt")
            speakAndTakeBreak(wakePrompt, isScreenReaderPrompt = true)
            return
        }

        // Strip wake word prefix if spoken with command (e.g. "Hey Aura open YouTube" -> "open YouTube")
        val cleanCommand = spoken.replace(Regex("(?i)^(?:hey aura|aura|hello aura|hi aura|ok aura|హే ఆరా|ఆరా|హలో ఆరా|ఓకే ఆరా|हे ऑरा|ऑरा)[,\\s]+"), "").trim()
        if (cleanCommand.isEmpty()) return

        isStandbyMode = false
        expandToMenu()

        // 3. Screen Reader Command ("read screen", "screen reader", "what is on screen", "స్క్రీన్ చదువు")
        if (Regex("(?i)\\b(read screen|screen reader|what is on screen|what's on screen|what can i do)\\b").containsMatchIn(cleanCommand.lowercase()) ||
            cleanCommand.contains("స్క్రీన్") || cleanCommand.contains("రీడర్") || cleanCommand.contains("स्क्रीन")
        ) {
            val summary = buildScreenReaderPromptForApp(currentActiveApp)
            updateStatusText("📖 $summary")
            speakAndTakeBreak(summary, isScreenReaderPrompt = true)
            return
        }

        // 4. DUAL COMMAND HANDLING (Decomposes "Open App AND do action"):
        val (part1, part2) = splitDualCommand(cleanCommand)
        val activity = MainActivity.instance

        if (part2 != null) {
            // Store step 2 as pending task to be executed upon comeback wake word or immediately after step 1
            pendingSecondCommand = part2

            // Execute step 1
            val replyPart1 = if (activity != null) {
                activity.executeVoiceCommandFromOverlay(part1, currentActiveApp)
            } else {
                "Opened app."
            }

            val followupNotice = if (Regex("[\\u0C00-\\u0C7F]").containsMatchIn(cleanCommand)) {
                "$replyPart1 స్క్రీన్ రీడర్ సిద్ధంగా ఉంది. రెండో పని కోసం 'ఆరా' అని పిలవండి."
            } else {
                "$replyPart1 Screen reader active. Call me with 'Hey Aura' when you're ready for the second task."
            }

            updateStatusText("⚡ $replyPart1 • Next: \"$part2\"")
            speakAndTakeBreak(followupNotice, isScreenReaderPrompt = false)
            return
        }

        // 5. Single Command Execution:
        if (activity != null) {
            val reply = activity.executeVoiceCommandFromOverlay(cleanCommand, currentActiveApp)
            updateStatusText("✅ $reply")
            // Clean confirmation, then enter silent standby so talkback stops!
            speakAndTakeBreak(reply, isScreenReaderPrompt = false)
        } else {
            speakAndTakeBreak("Executing $cleanCommand", isScreenReaderPrompt = false)
        }
    }

    override fun onDestroy() {
        isRunning = false
        continuousMode = false
        stopListening()
        removeOverlayWindow()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {}
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
