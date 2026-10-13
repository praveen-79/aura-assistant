package com.aura.assistant

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aura.assistant.AuraState.CANCELLING
import com.aura.assistant.AuraState.CAPTURING_COMMAND
import com.aura.assistant.AuraState.COOLDOWN
import com.aura.assistant.AuraState.DISABLED
import com.aura.assistant.AuraState.ERROR_RECOVERY
import com.aura.assistant.AuraState.EXECUTING_ACTIONS
import com.aura.assistant.AuraState.IDLE_WAKE_MONITOR
import com.aura.assistant.AuraState.INTERPRETING_COMMAND
import com.aura.assistant.AuraState.SPEAKING_RESPONSE
import com.aura.assistant.AuraState.VERIFYING_RESULT
import java.util.concurrent.CopyOnWriteArrayList

/** Lifecycle states of the Aura voice assistant. */
enum class AuraState(val displayName: String, val badgeColorHex: String) {
    /** Assistant is turned off. */
    DISABLED("Disabled", "#475569"),

    /** Low-resource wake-word listening. */
    IDLE_WAKE_MONITOR("Standby", "#E2E8F0"),

    /** Active microphone command capture. */
    CAPTURING_COMMAND("Listening", "#00F5D4"),

    /** Parsing & planning actions. */
    INTERPRETING_COMMAND("Planning", "#FBBF24"),

    /** Executing sequential Android actions. */
    EXECUTING_ACTIONS("Executing", "#38BDF8"),

    /** Verifying action outcome via Accessibility. */
    VERIFYING_RESULT("Verifying", "#818CF8"),

    /** Speaking voice summary aloud. */
    SPEAKING_RESPONSE("Speaking", "#C084FC"),

    /** Quiet period to prevent the assistant from hearing its own voice. */
    COOLDOWN("Cooldown", "#F59E0B"),

    /** Safely rolling back after a stop word. */
    CANCELLING("Cancelling", "#FB7185"),

    /** Handling failures gracefully. */
    ERROR_RECOVERY("Recovering", "#EF4444");

    fun isInteractive(): Boolean = this == CAPTURING_COMMAND || this == INTERPRETING_COMMAND || this == EXECUTING_ACTIONS || this == VERIFYING_RESULT
}

/** Receives state change notifications. Always invoked on the Main Thread. */
interface StateChangeListener {
    fun onStateChanged(oldState: AuraState, newState: AuraState, reason: String?)
}

/**
 * Thread-safe state machine for the Aura assistant lifecycle.
 *
 * - All transitions are serialized by the instance lock.
 * - Listeners are notified on the Main Thread, in the exact order transitions happened.
 * - Leaving [DISABLED] is only possible through [enableAssistant].
 * - [COOLDOWN] can only go to [IDLE_WAKE_MONITOR] or [DISABLED]; entering it
 *   schedules an automatic return to [IDLE_WAKE_MONITOR] after [cooldownDurationMs].
 *
 * @param mainHandler Handler bound to the Main Looper. Injectable so tests can supply their own.
 */
class AuraStateMachine(
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {

    /** Current state. Read from any thread; changed only through the transition methods. */
    @Volatile
    var currentState: AuraState = IDLE_WAKE_MONITOR
        private set

    /** Length of the quiet period after speaking. Read when [COOLDOWN] is entered. */
    @Volatile
    var cooldownDurationMs: Long = DEFAULT_COOLDOWN_MS

    private val listeners = CopyOnWriteArrayList<StateChangeListener>()

    // Guarded by `this`.
    private var cooldownTask: Runnable? = null
    private var cooldownToken = 0L

    fun addListener(listener: StateChangeListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: StateChangeListener) {
        listeners.remove(listener)
    }

    /**
     * Attempts to move to [newState].
     *
     * @return `true` if the transition was legal and applied, `false` if it was rejected.
     */
    @Synchronized
    fun transitionTo(newState: AuraState, reason: String? = null): Boolean =
        applyTransition(newState, reason)

    /**
     * Aborts the current session: goes through [CANCELLING] and back to [IDLE_WAKE_MONITOR].
     * Does nothing if there is no session to cancel (idle, disabled, cooldown, already cancelling).
     */
    @Synchronized
    fun cancelSession(reason: String = "User requested stop") {
        if (currentState !in CANCELLABLE_STATES) return
        if (applyTransition(CANCELLING, reason)) {
            applyTransition(IDLE_WAKE_MONITOR, "Session cancelled: $reason")
        }
    }

    /** Turns the assistant off from any state. */
    @Synchronized
    fun disableAssistant() {
        if (currentState != DISABLED) {
            applyTransition(DISABLED, "Assistant disabled")
        }
    }

    /** Turns the assistant on. The only way out of [DISABLED]. */
    @Synchronized
    fun enableAssistant() {
        if (currentState == DISABLED) {
            applyTransition(IDLE_WAKE_MONITOR, "Assistant enabled", explicitEnable = true)
        }
    }

    /** True while a command session is in progress. */
    fun isSessionActive(): Boolean = when (currentState) {
        CAPTURING_COMMAND,
        INTERPRETING_COMMAND,
        EXECUTING_ACTIONS,
        VERIFYING_RESULT -> true
        else -> false
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    @Synchronized
    private fun applyTransition(
        newState: AuraState,
        reason: String?,
        explicitEnable: Boolean = false
    ): Boolean {
        val oldState = currentState

        if (!isLegalTransition(oldState, newState, explicitEnable)) {
            Log.w(TAG, "Rejected illegal transition $oldState -> $newState")
            return false
        }

        if (oldState == COOLDOWN) cancelCooldownTimer()

        currentState = newState

        if (newState == COOLDOWN) scheduleCooldownTimer()

        // Posted while still holding the lock, so the Main Thread sees notifications in order.
        notifyListeners(oldState, newState, reason)
        return true
    }

    private fun isLegalTransition(
        from: AuraState,
        to: AuraState,
        explicitEnable: Boolean
    ): Boolean {
        if (from == DISABLED) return explicitEnable && to == IDLE_WAKE_MONITOR
        return TRANSITIONS[from]?.contains(to) == true
    }

    private fun notifyListeners(oldState: AuraState, newState: AuraState, reason: String?) {
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onStateChanged(oldState, newState, reason)
                } catch (e: Exception) {
                    // One misbehaving listener must not stop the others from being notified.
                    Log.e(TAG, "Listener failed on $oldState -> $newState", e)
                }
            }
        }
    }

    private fun scheduleCooldownTimer() {
        cooldownToken += 1
        val token = cooldownToken
        val task = Runnable { onCooldownElapsed(token) }
        cooldownTask = task
        mainHandler.postDelayed(task, cooldownDurationMs.coerceAtLeast(0L))
    }

    private fun cancelCooldownTimer() {
        cooldownTask?.let { mainHandler.removeCallbacks(it) }
        cooldownTask = null
        // Invalidate a task that already fired and is waiting for the lock.
        cooldownToken += 1
    }

    @Synchronized
    private fun onCooldownElapsed(token: Long) {
        if (token != cooldownToken || currentState != COOLDOWN) return
        applyTransition(IDLE_WAKE_MONITOR, "Cooldown elapsed")
    }

    private companion object {
        const val TAG = "AuraStateMachine"
        const val DEFAULT_COOLDOWN_MS = 2500L

        /** States where a stop word has something to abort. */
        val CANCELLABLE_STATES: Set<AuraState> = setOf(
            CAPTURING_COMMAND,
            INTERPRETING_COMMAND,
            EXECUTING_ACTIONS,
            VERIFYING_RESULT,
            SPEAKING_RESPONSE,
            ERROR_RECOVERY
        )

        /** Legal transitions. [DISABLED] has none here: it is left only via enableAssistant(). */
        val TRANSITIONS: Map<AuraState, Set<AuraState>> = mapOf(
            DISABLED to emptySet(),
            IDLE_WAKE_MONITOR to setOf(
                CAPTURING_COMMAND, ERROR_RECOVERY, DISABLED
            ),
            CAPTURING_COMMAND to setOf(
                INTERPRETING_COMMAND, IDLE_WAKE_MONITOR, CANCELLING, ERROR_RECOVERY, DISABLED
            ),
            INTERPRETING_COMMAND to setOf(
                EXECUTING_ACTIONS, SPEAKING_RESPONSE, CANCELLING, ERROR_RECOVERY, DISABLED
            ),
            EXECUTING_ACTIONS to setOf(
                VERIFYING_RESULT, CANCELLING, ERROR_RECOVERY, DISABLED
            ),
            VERIFYING_RESULT to setOf(
                EXECUTING_ACTIONS, SPEAKING_RESPONSE, CANCELLING, ERROR_RECOVERY, DISABLED
            ),
            SPEAKING_RESPONSE to setOf(
                COOLDOWN, CANCELLING, ERROR_RECOVERY, DISABLED
            ),
            COOLDOWN to setOf(
                IDLE_WAKE_MONITOR, DISABLED
            ),
            CANCELLING to setOf(
                IDLE_WAKE_MONITOR, ERROR_RECOVERY, DISABLED
            ),
            ERROR_RECOVERY to setOf(
                IDLE_WAKE_MONITOR, SPEAKING_RESPONSE, CANCELLING, DISABLED
            )
        )
    }
}
