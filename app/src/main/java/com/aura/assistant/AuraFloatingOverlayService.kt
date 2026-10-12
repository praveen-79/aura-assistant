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
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Floating Overlay ("Display Over Other Apps") & Continuous Cross-App Screen Reader Service.
 * Stays visible on top of Instagram, WhatsApp, YouTube, Maps, etc., reads the active app screen
 * context, speaks prompts, and continuously listens for the user's next voice command without relaxing.
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
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var statusTextView: TextView? = null
    private var micBadgeView: TextView? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isSpeaking = false
    private var isListening = false
    private var continuousMode = true
    private var currentLangTag = "en-IN"

    private val mainHandler = Handler(Looper.getMainLooper())

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
            if (canDrawOverlays() && overlayView == null) {
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
                    continuousMode = true
                    updateOverlayUi("📱 In $currentActiveApp — Screen Reader Ready", false)
                    val prompt = speakText ?: buildScreenReaderPromptForApp(currentActiveApp)
                    speakAndThenListen(prompt)
                }
                ACTION_READ_ACTIVE_SCREEN -> {
                    continuousMode = true
                    val summary = buildScreenReaderPromptForApp(currentActiveApp)
                    updateOverlayUi("📖 $summary", false)
                    speakAndThenListen(summary)
                }
                ACTION_SPEAK_AND_LISTEN -> {
                    continuousMode = true
                    val msg = speakText ?: "Task completed. What command should I perform next?"
                    updateOverlayUi("🤖 $msg", false)
                    speakAndThenListen(msg)
                }
                else -> {
                    if (!appName.isNullOrBlank()) {
                        currentActiveApp = appName
                    }
                    continuousMode = true
                    val msg = speakText ?: "Aura Floating Screen Reader active over other apps. What command should I perform next?"
                    updateOverlayUi("🎙️ Aura Overlay Active ($currentActiveApp)", false)
                    speakAndThenListen(msg)
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
        val channelId = "aura_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Aura Floating Screen Reader & Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Aura voice recognition & screen reader active over other apps"
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
            .setContentTitle("Aura AI Active Over Apps ($currentActiveApp)")
            .setContentText("Listening for commands in $currentActiveApp — Never relaxing!")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()

        try {
            startForeground(1001, notification)
        } catch (e: Exception) {}
    }

    private fun showFloatingOverlayWindow() {
        if (overlayView != null || !canDrawOverlays()) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 90
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 64f
                setColor(Color.parseColor("#E60D1326"))
                setStroke(3, Color.parseColor("#00F5D4"))
            }
            elevation = 20f
        }

        val micBtn = TextView(this).apply {
            text = "🎙️"
            textSize = 18f
            setPadding(14, 6, 14, 6)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3300F5D4"))
            }
            setOnClickListener {
                continuousMode = true
                if (isSpeaking) {
                    tts?.stop()
                    isSpeaking = false
                }
                startListeningNow()
            }
        }
        micBadgeView = micBtn

        val statusTv = TextView(this).apply {
            text = "📱 Aura Ready ($currentActiveApp) — Speak command"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            maxWidth = 540
            setPadding(16, 4, 16, 4)
            setOnClickListener {
                continuousMode = true
                startListeningNow()
            }
        }
        statusTextView = statusTv

        val readBtn = TextView(this).apply {
            text = "📖 Read"
            setTextColor(Color.parseColor("#FFD166"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(18, 8, 18, 8)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 36f
                setColor(Color.parseColor("#26FFD166"))
                setStroke(2, Color.parseColor("#FFD166"))
            }
            setOnClickListener {
                continuousMode = true
                val summary = buildScreenReaderPromptForApp(currentActiveApp)
                updateOverlayUi("📖 $summary", false)
                speakAndThenListen(summary)
            }
        }

        val homeBtn = TextView(this).apply {
            text = "🏠"
            textSize = 15f
            setPadding(14, 6, 10, 6)
            setOnClickListener {
                val openMain = Intent(this@AuraFloatingOverlayService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(openMain)
            }
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FF6B8B"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(12, 6, 8, 6)
            setOnClickListener {
                continuousMode = false
                stopListening()
                removeOverlayWindow()
                stopSelf()
            }
        }

        root.addView(micBtn)
        root.addView(statusTv)
        root.addView(readBtn)
        root.addView(homeBtn)
        root.addView(closeBtn)

        // Allow dragging the floating overlay pill anywhere on screen
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            windowManager?.updateViewLayout(root, params)
                        } catch (e: Exception) {}
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(root, params)
            overlayView = root
        } catch (e: Exception) {}
    }

    private fun updateOverlayUi(statusText: String, listeningState: Boolean) {
        mainHandler.post {
            statusTextView?.text = statusText
            micBadgeView?.text = if (listeningState) "🟢" else (if (isSpeaking) "🔊" else "🎙️")
        }
    }

    private fun removeOverlayWindow() {
        mainHandler.post {
            overlayView?.let {
                try {
                    windowManager?.removeView(it)
                } catch (e: Exception) {}
            }
            overlayView = null
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale("en", "IN")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    mainHandler.post {
                        stopListening()
                        micBadgeView?.text = "🔊"
                    }
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (continuousMode) {
                        mainHandler.postDelayed({
                            startListeningNow()
                        }, 300)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    if (continuousMode) {
                        mainHandler.postDelayed({
                            startListeningNow()
                        }, 300)
                    }
                }
            })
        }
    }

    fun speakAndThenListen(textToSpeak: String) {
        val clean = textToSpeak.replace(Regex("[*_`#]"), "").trim()
        if (clean.isEmpty()) {
            if (continuousMode) startListeningNow()
            return
        }
        stopListening()
        if (!ttsReady || tts == null) {
            mainHandler.postDelayed({
                if (continuousMode) startListeningNow()
            }, 600)
            return
        }
        isSpeaking = true
        val params = Bundle()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, params, "aura_overlay_utt_${System.currentTimeMillis()}")
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        updateOverlayUi("🟢 Listening in $currentActiveApp... Speak command!", true)
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        isListening = false
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        // Auto-restart listening in continuous mode so Aura never relaxes while other apps are open
                        if (continuousMode && !isSpeaking) {
                            mainHandler.postDelayed({
                                if (continuousMode && !isSpeaking) {
                                    startListeningNow()
                                }
                            }, 900)
                        } else {
                            updateOverlayUi("🎙️ Tap to speak in $currentActiveApp", false)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull()?.trim() ?: ""
                        if (spoken.isNotEmpty()) {
                            handleSpokenOverlayCommand(spoken)
                        } else if (continuousMode && !isSpeaking) {
                            mainHandler.postDelayed({ startListeningNow() }, 400)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        if (!partial.isNullOrBlank()) {
                            updateOverlayUi("🎙️ \"$partial\" ...", true)
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLangTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            sr.cancel()
            sr.startListening(recIntent)
            isListening = true
            updateOverlayUi("🟢 Listening in $currentActiveApp... Speak command!", true)
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
                "Screen Reader: You are currently in $appName. You can say Open Reels, Search followed by any name, Open Story Camera, or Open another app. What command should I perform next?"
            "whatsapp" in key ->
                "Screen Reader: You are currently in WhatsApp. You can say Send message to any contact name, Call a contact, or Open another app. What command should I perform next?"
            "youtube" in key ->
                "Screen Reader: You are currently in YouTube. You can say Play followed by any song or video, Open Shorts, Volume up, or Open another app. What command should I perform next?"
            "map" in key ->
                "Screen Reader: You are currently in Google Maps. You can say Navigate to any place, Check battery, or Call a contact. What command should I perform next?"
            else ->
                "Screen Reader: Currently active over $appName. I am ready to execute commands on this screen. What command should I perform next?"
        }
    }

    private fun handleSpokenOverlayCommand(spokenRaw: String) {
        val spoken = spokenRaw.trim()
        val lower = spoken.lowercase()
        updateOverlayUi("⚡ Executing: \"$spoken\"", false)

        // 1. Check if user explicitly asked to stop/relax
        if (Regex("\\b(relax|go to sleep|stop listening|close overlay|nothing else)\\b").containsMatchIn(lower) ||
            spoken.contains("ఆపు") || spoken.contains("చాలు") || spoken.contains("बस करो")
        ) {
            continuousMode = false
            updateOverlayUi("⏸️ Paused. Tap 🎙️ anytime to speak!", false)
            speakAndThenListen("Paused continuous listening. Tap the floating microphone anytime.")
            return
        }

        // 2. Check if user asked Screen Reader ("read screen" / "what is on screen" / "what can I do")
        if (Regex("\\b(read screen|screen reader|what is on screen|what's on screen|what can i do)\\b").containsMatchIn(lower) ||
            spoken.contains("స్క్రీన్") || spoken.contains("स्क्रीन")
        ) {
            val summary = buildScreenReaderPromptForApp(currentActiveApp)
            updateOverlayUi("📖 $summary", false)
            speakAndThenListen(summary)
            return
        }

        // 3. Delegate to MainActivity to execute the command in the currently active app (or switch apps)
        val activity = MainActivity.instance
        if (activity != null) {
            val reply = activity.executeVoiceCommandFromOverlay(spoken, currentActiveApp)
            updateOverlayUi("✅ $reply", false)
            speakAndThenListen("$reply What command should I perform next?")
        } else {
            speakAndThenListen("Executing $spoken. What command should I perform next?")
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
