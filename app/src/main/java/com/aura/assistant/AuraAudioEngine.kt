package com.aura.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Part 4: Audio Lifecycle, Wake-Word Monitoring & Stop-Word Cancellation Engine.
 *
 * Enforces single microphone ownership, dual-mode recognition (low-resource wake monitor vs.
 * active command capture), echo cancellation protection against TTS feedback, and instant
 * stop-word cancellation across English, Telugu, and Hindi.
 */
class AuraAudioEngine(
    private val context: Context,
    private val listener: AudioEngineListener
) {

    companion object {
        private const val TAG = "AuraAudioEngine"

        // Wake word regex patterns across English, Telugu, and Hindi
        private val WAKE_WORD_PATTERN = Regex(
            "(?i)^(?:hey\\s+aura|aura|hello\\s+aura|hi\\s+aura|ok\\s+aura|హే\\s*ఆరా|ఆరా|హలో\\s*ఆరా|ఓకే\\s*ఆరా|हे\\s*ऑरा|ऑरा)(?:[,\\s]+(.*))?$"
        )

        // Stop words and cancellation triggers
        private val STOP_WORD_PATTERN = Regex(
            "(?i)\\b(stop\\s+aura|cancel|aura\\s+stop|stop\\s+talking|shut\\s+up|be\\s+quiet|keep\\s+quiet|silent|silence|pause|abort)\\b"
        )

        private val INDIC_STOP_WORDS = listOf(
            "ఆగు", "ఆపు", "చాలు", "మాట్లాడకు", "నోరు మూసుకో", "నోరు మూయి", "శాంతంగా ఉండు", "వద్దు",
            "रुक जाओ", "शांत रहो", "चुप रहो", "बस करो", "बंद करो"
        )
    }

    interface AudioEngineListener {
        fun onWakeWordDetected(rawSpoken: String, trailingCommand: String?)
        fun onCommandCaptured(spokenText: String)
        fun onStopWordDetected(reason: String)
        fun onSpeechError(errorCode: Int, errorMessage: String)
        fun onRmsChanged(rmsdB: Float)
        fun onBeginningOfSpeech()
        fun onEndOfSpeech()
    }

    enum class AudioMode {
        IDLE_WAKE_MONITOR,
        ACTIVE_COMMAND_CAPTURE,
        MUTED_PAUSED
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var currentMode: AudioMode = AudioMode.MUTED_PAUSED
        private set

    @Volatile
    private var isTtsSpeaking: Boolean = false

    @Volatile
    private var isCooldownActive: Boolean = false

    private var consecutiveErrors = 0
    private var isListening = false

    // Configurable parameters
    var preferredLanguage: String = "te-IN"
    var endOfUtteranceTimeoutMs: Long = 2000L

    init {
        mainHandler.post { initRecognizer() }
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "SpeechRecognizer is not available on this device")
            listener.onSpeechError(SpeechRecognizer.ERROR_CLIENT, "Speech recognizer not available")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(InternalRecognitionListener())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate SpeechRecognizer", e)
        }
    }

    /**
     * Start low-resource wake-word monitoring in IDLE mode.
     */
    fun startWakeWordMonitoring() {
        mainHandler.post {
            currentMode = AudioMode.IDLE_WAKE_MONITOR
            restartRecognition()
        }
    }

    /**
     * Switch to full command capture mode after wake word is detected.
     */
    fun startCommandCapture() {
        mainHandler.post {
            currentMode = AudioMode.ACTIVE_COMMAND_CAPTURE
            restartRecognition()
        }
    }

    /**
     * Pauses/mutes the microphone (during speaking, cooldown, or when disabled).
     */
    fun stopListening() {
        mainHandler.post {
            currentMode = AudioMode.MUTED_PAUSED
            internalStop()
        }
    }

    /**
     * Inform audio engine whether TTS is currently outputting speech.
     * Enforces echo cancellation: mic is silenced while Aura speaks!
     */
    fun setTtsSpeaking(speaking: Boolean) {
        isTtsSpeaking = speaking
        if (speaking) {
            internalStop()
        } else {
            resumeIfNeeded()
        }
    }

    /**
     * Set cooldown state (prevents assistant from hearing its own reverberation).
     */
    fun setCooldown(active: Boolean) {
        isCooldownActive = active
        if (active) {
            internalStop()
        } else {
            resumeIfNeeded()
        }
    }

    private fun resumeIfNeeded() {
        if (!isTtsSpeaking && !isCooldownActive && currentMode != AudioMode.MUTED_PAUSED) {
            mainHandler.postDelayed({ restartRecognition() }, 300)
        }
    }

    private fun internalStop() {
        isListening = false
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling speech recognizer: ${e.message}")
        }
    }

    private fun restartRecognition() {
        if (isTtsSpeaking || isCooldownActive || currentMode == AudioMode.MUTED_PAUSED) {
            return
        }

        if (speechRecognizer == null) {
            initRecognizer()
        }

        val sr = speechRecognizer ?: return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, preferredLanguage)
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("te-IN", "en-IN", "hi-IN"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, endOfUtteranceTimeoutMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, endOfUtteranceTimeoutMs)
        }

        try {
            sr.cancel()
            sr.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            isListening = false
            scheduleRetry(1000)
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        mainHandler.postDelayed({
            if (currentMode != AudioMode.MUTED_PAUSED && !isTtsSpeaking && !isCooldownActive) {
                restartRecognition()
            }
        }, delayMs)
    }

    /**
     * Processes recognized text against wake-words, stop-words, and general commands.
     */
    private fun processRecognizedText(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return

        // 1. Check for Stop / Cancellation Phrases first
        if (isStopPhrase(text)) {
            Log.d(TAG, "Stop phrase recognized: $text")
            listener.onStopWordDetected(text)
            return
        }

        // 2. In Wake-Word Monitoring mode: check if utterance begins with wake word
        if (currentMode == AudioMode.IDLE_WAKE_MONITOR) {
            val match = WAKE_WORD_PATTERN.matchEntire(text)
            if (match != null) {
                val trailing = match.groupValues.getOrNull(1)?.trim()?.ifEmpty { null }
                Log.d(TAG, "Wake word detected: '$text', trailing: '$trailing'")
                listener.onWakeWordDetected(text, trailing)
            } else {
                // Ignore random ambient chatter when idle
                Log.v(TAG, "Ignored speech during idle: $text")
            }
            return
        }

        // 3. In Active Command Capture mode: deliver the full utterance
        if (currentMode == AudioMode.ACTIVE_COMMAND_CAPTURE) {
            // Strip wake-word prefix if user repeated it (e.g. "Hey Aura open YouTube" -> "open YouTube")
            val clean = stripWakePrefix(text)
            Log.d(TAG, "Command captured: $clean")
            listener.onCommandCaptured(clean)
        }
    }

    private fun isStopPhrase(text: String): Boolean {
        if (STOP_WORD_PATTERN.containsMatchIn(text)) return true
        for (indicStop in INDIC_STOP_WORDS) {
            if (text.contains(indicStop)) return true
        }
        return false
    }

    private fun stripWakePrefix(text: String): String {
        val match = WAKE_WORD_PATTERN.matchEntire(text)
        if (match != null) {
            val trailing = match.groupValues.getOrNull(1)?.trim()
            if (!trailing.isNullOrEmpty()) return trailing
        }
        return text
    }

    fun destroy() {
        stopListening()
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying speech recognizer", e)
            }
        }
    }

    private inner class InternalRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
            consecutiveErrors = 0
        }

        override fun onBeginningOfSpeech() {
            listener.onBeginningOfSpeech()
        }

        override fun onRmsChanged(rmsdB: Float) {
            listener.onRmsChanged(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListening = false
            listener.onEndOfSpeech()
        }

        override fun onError(error: Int) {
            isListening = false
            consecutiveErrors++

            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech matched"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing RECORD_AUDIO permission"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                else -> "Unknown error ($error)"
            }

            Log.w(TAG, "SpeechRecognizer error: $errorMsg (code $error, count $consecutiveErrors)")

            // For routine idle timeouts/no-match, seamlessly restart in idle mode without surfacing errors
            if (currentMode == AudioMode.IDLE_WAKE_MONITOR &&
                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            ) {
                scheduleRetry(600)
                return
            }

            listener.onSpeechError(error, errorMsg)

            val backoff = when {
                consecutiveErrors > 5 -> 4000L
                consecutiveErrors > 2 -> 2000L
                else -> 1000L
            }
            scheduleRetry(backoff)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            consecutiveErrors = 0
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val spoken = matches?.firstOrNull()?.trim() ?: ""

            if (spoken.isNotEmpty()) {
                processRecognizedText(spoken)
            } else if (currentMode == AudioMode.IDLE_WAKE_MONITOR) {
                scheduleRetry(500)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            if (!partial.isNullOrEmpty() && isStopPhrase(partial)) {
                // Fast-path immediate cancellation even during partial speech!
                Log.d(TAG, "Stop phrase detected in partial results: $partial")
                internalStop()
                listener.onStopWordDetected(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
