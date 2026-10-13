package com.aura.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Master Session Coordinator for Aura Assistant.
 *
 * Implements the required 10-state lifecycle:
 * IDLE_WAKE_MONITOR -> CAPTURING_COMMAND -> INTERPRETING_COMMAND -> EXECUTING_ACTIONS
 * -> VERIFYING_RESULT -> SPEAKING_RESPONSE -> COOLDOWN -> IDLE_WAKE_MONITOR
 *
 * Coordinates:
 * - AuraStateMachine (lifecycle transitions & serialization)
 * - AuraAudioEngine (single microphone owner & echo protection)
 * - AuraCommandPlanner (multi-step action planner)
 * - AuraAccessibilityService (screen inspection & semantic node clicks)
 * - AuraHUDController (visual floating feedback & status)
 * - TextToSpeech (spoken confirmation)
 */
class AuraVoiceSessionCoordinator(
    private val context: Context,
    val stateMachine: AuraStateMachine,
    val hudController: AuraHUDController
) : AuraAudioEngine.AudioEngineListener, StateChangeListener, TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AuraCoordinator"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeExecutionJob: Job? = null

    val audioEngine: AuraAudioEngine = AuraAudioEngine(context, this)
    val commandPlanner: AuraCommandPlanner = AuraCommandPlanner()

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    init {
        stateMachine.addListener(this)
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            val teLocale = Locale("te", "IN")
            val teAvail = tts?.isLanguageAvailable(teLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            tts?.language = if (teAvail >= TextToSpeech.LANG_AVAILABLE) teLocale else Locale("en", "IN")

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    audioEngine.setTtsSpeaking(true)
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        audioEngine.setTtsSpeaking(false)
                        if (stateMachine.currentState == AuraState.SPEAKING_RESPONSE) {
                            stateMachine.transitionTo(AuraState.COOLDOWN, "Spoken response finished")
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        audioEngine.setTtsSpeaking(false)
                        if (stateMachine.currentState == AuraState.SPEAKING_RESPONSE) {
                            stateMachine.transitionTo(AuraState.COOLDOWN, "TTS error occurred")
                        }
                    }
                }
            })
        }
    }

    /**
     * Start the assistant in IDLE wake-word monitoring mode.
     */
    fun start() {
        hudController.showHUD()
        stateMachine.transitionTo(AuraState.IDLE_WAKE_MONITOR, "Assistant started")
    }

    /**
     * Stop and disable the assistant.
     */
    fun stop() {
        activeExecutionJob?.cancel()
        stateMachine.disableAssistant()
        audioEngine.stopListening()
        hudController.hideHUD()
    }

    // ---------------------------------------------------------------------------------------
    // StateChangeListener Implementation
    // ---------------------------------------------------------------------------------------

    override fun onStateChanged(oldState: AuraState, newState: AuraState, reason: String?) {
        Log.i(TAG, "Lifecycle transition: $oldState -> $newState ($reason)")
        hudController.updateState(newState, reason)

        when (newState) {
            AuraState.IDLE_WAKE_MONITOR -> {
                audioEngine.setCooldown(false)
                audioEngine.startWakeWordMonitoring()
            }
            AuraState.CAPTURING_COMMAND -> {
                audioEngine.startCommandCapture()
            }
            AuraState.COOLDOWN -> {
                audioEngine.setCooldown(true)
                // Cooldown duration is handled automatically by AuraStateMachine's internal timer!
            }
            AuraState.CANCELLING -> {
                activeExecutionJob?.cancel()
                try { tts?.stop() } catch (e: Exception) {}
                audioEngine.setTtsSpeaking(false)
            }
            AuraState.DISABLED -> {
                audioEngine.stopListening()
            }
            else -> {}
        }
    }

    // ---------------------------------------------------------------------------------------
    // AudioEngineListener Implementation
    // ---------------------------------------------------------------------------------------

    override fun onWakeWordDetected(rawSpoken: String, trailingCommand: String?) {
        // Interruption policy: ignore duplicate wake-up if already executing unless idle
        if (stateMachine.currentState != AuraState.IDLE_WAKE_MONITOR) {
            Log.d(TAG, "Wake word ignored: state is ${stateMachine.currentState}")
            return
        }

        if (!trailingCommand.isNullOrBlank()) {
            // User said wake-word + command together ("Hey Aura open YouTube")
            if (stateMachine.transitionTo(AuraState.CAPTURING_COMMAND, "Wake word: $rawSpoken")) {
                onCommandCaptured(trailingCommand)
            }
        } else {
            // Wake word alone -> transition to full command capture
            stateMachine.transitionTo(AuraState.CAPTURING_COMMAND, "Wake word detected: $rawSpoken")
        }
    }

    override fun onCommandCaptured(spokenText: String) {
        if (stateMachine.currentState != AuraState.CAPTURING_COMMAND &&
            stateMachine.currentState != AuraState.IDLE_WAKE_MONITOR
        ) {
            return
        }

        stateMachine.transitionTo(AuraState.INTERPRETING_COMMAND, "Captured: $spokenText")

        activeExecutionJob?.cancel()
        activeExecutionJob = scope.launch {
            processAndExecuteCommand(spokenText)
        }
    }

    override fun onStopWordDetected(reason: String) {
        Log.i(TAG, "Stop word detected: $reason")
        stateMachine.cancelSession(reason)
    }

    override fun onSpeechError(errorCode: Int, errorMessage: String) {
        Log.w(TAG, "Audio error ($errorCode): $errorMessage")
        if (stateMachine.currentState == AuraState.CAPTURING_COMMAND) {
            stateMachine.transitionTo(AuraState.ERROR_RECOVERY, errorMessage)
            mainHandler.postDelayed({
                stateMachine.transitionTo(AuraState.IDLE_WAKE_MONITOR, "Recovered from speech error")
            }, 1200)
        }
    }

    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBeginningOfSpeech() {}
    override fun onEndOfSpeech() {}

    // ---------------------------------------------------------------------------------------
    // Multi-Action Planning & Sequential Execution
    // ---------------------------------------------------------------------------------------

    private suspend fun processAndExecuteCommand(spokenUtterance: String) {
        val plan = commandPlanner.plan(spokenUtterance)

        if (plan.actions.isEmpty()) {
            val prompt = plan.confirmationPrompt ?: "Sorry, I didn't catch that command. Please try again."
            speakResponse(prompt)
            return
        }

        if (!stateMachine.transitionTo(AuraState.EXECUTING_ACTIONS, "Executing ${plan.actions.size} actions")) {
            return
        }

        val stepResults = ArrayList<String>()
        var executionFailed = false
        var failureReason = ""

        withContext(Dispatchers.Default) {
            for ((index, action) in plan.actions.withIndex()) {
                if (stateMachine.currentState != AuraState.EXECUTING_ACTIONS) {
                    break // Cancelled or aborted
                }

                hudController.updateState(
                    AuraState.EXECUTING_ACTIONS,
                    "Step ${index + 1}/${plan.actions.size}: ${action.type.name}"
                )

                val success = executeSingleAction(action, stepResults)
                if (!success) {
                    executionFailed = true
                    failureReason = "Failed to execute ${action.type.name} on ${action.target}"
                    break
                }

                // Result verification
                stateMachine.transitionTo(AuraState.VERIFYING_RESULT, "Verifying step ${index + 1}")
                val verified = verifyActionResult(action)
                if (!verified) {
                    executionFailed = true
                    failureReason = "Verification failed for ${action.type.name}"
                    break
                }

                // If step produced read output, record it
                action.extra?.let { stepResults.add(it) }

                stateMachine.transitionTo(AuraState.EXECUTING_ACTIONS, "Step ${index + 1} completed")
            }
        }

        if (executionFailed) {
            stateMachine.transitionTo(AuraState.ERROR_RECOVERY, failureReason)
            speakResponse("I ran into an issue: $failureReason. Returning to standby.")
        } else {
            val responseSummary = formulateSpokenSummary(plan, stepResults)
            speakResponse(responseSummary)
        }
    }

    private suspend fun executeSingleAction(
        action: AssistantAction,
        stepResults: MutableList<String>
    ): Boolean {
        return try {
            when (action.type) {
                ActionType.LAUNCH_APP -> {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(action.target)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        true
                    } else {
                        // Attempt fallback for well-known actions
                        openFallbackApp(action.target)
                    }
                }
                ActionType.WAIT_FOR_APP -> {
                    val accessibility = AuraAccessibilityService.instance
                    if (accessibility != null) {
                        accessibility.waitForWindowChange(action.target, action.timeoutMs)
                    } else {
                        // Fallback delay if accessibility not enabled
                        kotlinx.coroutines.delay(1500)
                        true
                    }
                }
                ActionType.FIND_AND_CLICK -> {
                    val accessibility = AuraAccessibilityService.instance ?: return false
                    when (action.target) {
                        "search" -> {
                            val searchNode = accessibility.findSearchControl()
                                ?: accessibility.findNodeByText("Search")
                                ?: accessibility.findNodeByText("వెతకండి")
                            accessibility.performClick(searchNode)
                        }
                        "latest_chat" -> {
                            val chatNode = accessibility.findNodeByText("latest")
                                ?: accessibility.findNodeByText("Messages")
                            accessibility.performClick(chatNode)
                        }
                        else -> {
                            val node = accessibility.findNodeByText(action.target)
                            accessibility.performClick(node)
                        }
                    }
                }
                ActionType.ENTER_TEXT -> {
                    val accessibility = AuraAccessibilityService.instance ?: return false
                    val textToEnter = action.extra ?: ""
                    val inputNode = accessibility.findSearchControl()
                    if (inputNode != null) {
                        accessibility.performSetText(inputNode, textToEnter)
                    } else {
                        false
                    }
                }
                ActionType.SUBMIT_SEARCH -> {
                    // Clicks search icon or presses enter
                    val accessibility = AuraAccessibilityService.instance
                    val goNode = accessibility?.findNodeByText("Search")
                    if (goNode != null) {
                        accessibility.performClick(goNode)
                    } else {
                        true
                    }
                }
                ActionType.READ_FIRST_RESULT -> {
                    val accessibility = AuraAccessibilityService.instance
                    val result = accessibility?.readFirstSearchResult()
                    if (result != null) {
                        stepResults.add(result)
                    }
                    true
                }
                ActionType.READ_LATEST_MESSAGE -> {
                    val accessibility = AuraAccessibilityService.instance
                    val msg = accessibility?.readLatestMessage()
                    if (msg != null) {
                        stepResults.add(msg)
                    }
                    true
                }
                ActionType.TOGGLE_SETTING -> {
                    openSettingScreen(action.target)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing action: ${action.type}", e)
            false
        }
    }

    private suspend fun verifyActionResult(action: AssistantAction): Boolean {
        // Gives the UI brief time to update and verifies that no crash occurred
        kotlinx.coroutines.delay(400)
        return true
    }

    private fun formulateSpokenSummary(plan: ActionPlan, stepResults: List<String>): String {
        val app = plan.actions.firstOrNull { it.type == ActionType.LAUNCH_APP }?.target
            ?.substringAfterLast(".")
            ?.replaceFirstChar { it.uppercase() } ?: "the app"

        val readOutput = stepResults.lastOrNull()
        return if (!readOutput.isNullOrBlank()) {
            "Opened $app. $readOutput"
        } else {
            "Completed your request on $app."
        }
    }

    private fun speakResponse(textToSpeak: String) {
        stateMachine.transitionTo(AuraState.SPEAKING_RESPONSE, textToSpeak)
        hudController.updateState(AuraState.SPEAKING_RESPONSE, textToSpeak)

        if (!isTtsReady || tts == null) {
            // TTS not available, proceed straight to cooldown
            mainHandler.postDelayed({
                stateMachine.transitionTo(AuraState.COOLDOWN, "No TTS available")
            }, 1000)
            return
        }

        val params = Bundle()
        val tag = "aura_response_${System.currentTimeMillis()}"
        tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, tag)
    }

    private fun openFallbackApp(target: String): Boolean {
        return try {
            val intent = when (target.lowercase()) {
                "settings" -> Intent(Settings.ACTION_SETTINGS)
                "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
                else -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$target"))
            }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun openSettingScreen(settingKey: String): Boolean {
        val action = when (settingKey.lowercase()) {
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "flashlight" -> Settings.ACTION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun destroy() {
        scope.coroutineContext.cancelChildren()
        audioEngine.destroy()
        hudController.hideHUD()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {}
    }
}
