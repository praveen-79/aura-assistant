package com.aura.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Part 5: Real-Time Visual State HUD & Floating Overlay Controller.
 *
 * Visually communicates the 10 lifecycle states (Idle Pearl, Listening Cyan, Planning Yellow,
 * Executing Blue, Speaking Violet, Cooldown Amber, etc.) with iOS AssistiveTouch-style
 * magnetic screen-edge docking and expandable state cards.
 */
class AuraHUDController(
    private val context: Context,
    private val onBubbleClicked: () -> Unit
) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayRoot: FrameLayout? = null
    private var bubbleContainer: FrameLayout? = null
    private var orbView: View? = null
    private var statusCard: LinearLayout? = null
    private var stateBadgeView: TextView? = null
    private var statusDetailView: TextView? = null

    private var windowParams: WindowManager.LayoutParams? = null
    private var isExpanded = false

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun isOverlayPermitted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Initializes and displays the floating HUD on screen.
     */
    fun showHUD() {
        if (overlayRoot != null || !isOverlayPermitted()) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = context.resources.displayMetrics
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
            y = dp(160)
        }
        windowParams = params

        val root = FrameLayout(context)

        // 1. Floating Circular Bubble (58dp x 58dp)
        val bubble = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E60F172A"))
                setStroke(dp(2), Color.parseColor("#40FFFFFF"))
            }
            elevation = dp(16).toFloat()
            alpha = 0.70f
        }
        bubbleContainer = bubble

        // Concentric frosted inner ring (34dp)
        val ring = View(context).apply {
            val s = dp(34)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }
        bubble.addView(ring)

        // Inner glowing Aura orb (20dp)
        val orb = View(context).apply {
            val s = dp(20)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(AuraState.IDLE_WAKE_MONITOR.badgeColorHex))
            }
        }
        orbView = orb
        bubble.addView(orb)

        // 2. Expandable Info Card
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = FrameLayout.LayoutParams(dp(260), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F2090D1A"))
                setStroke(dp(2), Color.parseColor("#38BDF8"))
            }
            elevation = dp(20).toFloat()
            visibility = View.GONE
        }
        statusCard = card

        val badge = TextView(context).apply {
            text = "STANDBY"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00F5D4"))
        }
        stateBadgeView = badge
        card.addView(badge)

        val detail = TextView(context).apply {
            text = "Say 'Hey Aura' or 'ఆరా' to begin"
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, dp(4), 0, 0)
        }
        statusDetailView = detail
        card.addView(detail)

        root.addView(card)
        root.addView(bubble)

        // 3. Drag, Release, and Magnetic Edge Snapping
        setupTouchInteraction(root, bubble, params, bubbleSize)

        try {
            windowManager.addView(root, params)
            overlayRoot = root
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupTouchInteraction(
        root: FrameLayout,
        bubble: FrameLayout,
        params: WindowManager.LayoutParams,
        bubbleSize: Int
    ) {
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
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (e: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        toggleExpandedCard()
                        onBubbleClicked()
                    } else {
                        // Magnetic Snap to Nearest Screen Edge
                        val screenWidth = context.resources.displayMetrics.widthPixels
                        val snapX = if (params.x + (bubbleSize / 2) < screenWidth / 2) {
                            dp(12) // Snap Left
                        } else {
                            screenWidth - bubbleSize - dp(12) // Snap Right
                        }
                        params.x = snapX
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (e: Exception) {}
                        if (!isExpanded) {
                            bubble.alpha = 0.70f
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Updates the HUD display to accurately reflect the active AuraState.
     */
    fun updateState(state: AuraState, message: String? = null) {
        mainHandler.post {
            val color = Color.parseColor(state.badgeColorHex)

            // Update Glowing Orb Color
            (orbView?.background as? GradientDrawable)?.setColor(color)

            // Update Card Border Stroke & Badge
            (statusCard?.background as? GradientDrawable)?.setStroke(dp(2), color)
            stateBadgeView?.text = state.displayName.uppercase()
            stateBadgeView?.setTextColor(color)

            val text = message ?: when (state) {
                AuraState.DISABLED -> "Assistant is disabled"
                AuraState.IDLE_WAKE_MONITOR -> "Standby • Say 'Hey Aura' / 'ఆరా'"
                AuraState.CAPTURING_COMMAND -> "Listening for command..."
                AuraState.INTERPRETING_COMMAND -> "Planning action sequence..."
                AuraState.EXECUTING_ACTIONS -> "Executing on screen..."
                AuraState.VERIFYING_RESULT -> "Verifying result..."
                AuraState.SPEAKING_RESPONSE -> "Speaking response..."
                AuraState.COOLDOWN -> "Taking a brief pause..."
                AuraState.CANCELLING -> "Cancelling task..."
                AuraState.ERROR_RECOVERY -> "Recovering..."
            }
            statusDetailView?.text = text

            // Adjust Opacity & Expansion Based on State
            if (state == AuraState.IDLE_WAKE_MONITOR || state == AuraState.COOLDOWN) {
                bubbleContainer?.alpha = 0.70f
                if (!isExpanded) collapseCard()
            } else {
                bubbleContainer?.alpha = 1.0f
                expandCard()
            }
        }
    }

    fun toggleExpandedCard() {
        if (isExpanded) collapseCard() else expandCard()
    }

    fun expandCard() {
        mainHandler.post {
            statusCard?.visibility = View.VISIBLE
            bubbleContainer?.alpha = 1.0f
            isExpanded = true
        }
    }

    fun collapseCard() {
        mainHandler.post {
            statusCard?.visibility = View.GONE
            bubbleContainer?.alpha = 0.70f
            isExpanded = false
        }
    }

    fun hideHUD() {
        mainHandler.post {
            overlayRoot?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {}
            }
            overlayRoot = null
            bubbleContainer = null
            orbView = null
            statusCard = null
            isExpanded = false
        }
    }
}
