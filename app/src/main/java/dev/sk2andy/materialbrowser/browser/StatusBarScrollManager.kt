package dev.sk2andy.materialbrowser.browser

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

/**
 * Root-powered, zero-AccessibilityService Status Bar Tap-To-Top manager.
 * Uses root-granted SYSTEM_ALERT_WINDOW (TYPE_APPLICATION_OVERLAY) and in-window touch detection.
 */
class StatusBarScrollManager(
    private val activity: Activity,
    private val controller: BrowserController,
) {
    private val windowManager by lazy { activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var isForeground = false
    private var isPageAllowed = false

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var isTrackingTap = false

    fun onResume() {
        isForeground = true
        updateOverlay()
    }

    fun onPause() {
        isForeground = false
        removeOverlay()
    }

    fun onPageContextChanged(allowed: Boolean) {
        isPageAllowed = allowed
        updateOverlay()
    }

    fun updateOverlay() {
        handler.post {
            val shouldShow = controller.isStatusBarScrollEnabled && isForeground && isPageAllowed
            if (shouldShow) {
                attachOverlayIfNeeded()
            } else {
                removeOverlay()
            }
        }
    }

    private fun attachOverlayIfNeeded() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(activity)) {
            return
        }

        val targetWidthPercent = controller.statusBarScrollTargetWidthPercent
        val screenWidth = availableScreenWidthPx()
        val targetWidth = StatusBarScrollRules.targetWidthPx(screenWidth, targetWidthPercent)
        val statusBarHeight = statusBarHeightPx()

        val view = View(activity).apply {
            contentDescription = "Scroll to top"
            setOnTouchListener { v, event ->
                val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        downTime = System.currentTimeMillis()
                        isTrackingTap = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - downY
                        val dx = event.rawX - downX
                        if (dy > touchSlop && dy > abs(dx)) {
                            isTrackingTap = false
                            expandNotificationShade()
                        } else if (abs(dx) > touchSlop) {
                            isTrackingTap = false
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isTrackingTap && (System.currentTimeMillis() - downTime <= ViewConfiguration.getTapTimeout() + 150)) {
                            triggerScrollToTop(v)
                        }
                        isTrackingTap = false
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        isTrackingTap = false
                        true
                    }
                    else -> false
                }
            }
        }

        val params = WindowManager.LayoutParams(
            targetWidth,
            statusBarHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsSides(0)
                setFitInsetsTypes(0)
            }
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (e: Throwable) {
            Log.w("StatusBarScrollManager", "Could not attach application overlay: ${e.message}")
        }
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Throwable) {}
        }
        overlayView = null
    }

    fun handleInWindowTouchEvent(event: MotionEvent): Boolean {
        if (!controller.isStatusBarScrollEnabled || !isPageAllowed) return false
        val statusBarHeight = statusBarHeightPx()
        val screenWidth = availableScreenWidthPx()
        val targetWidthPercent = controller.statusBarScrollTargetWidthPercent
        val targetWidth = StatusBarScrollRules.targetWidthPx(screenWidth, targetWidthPercent)
        val startX = (screenWidth - targetWidth) / 2
        val endX = (screenWidth + targetWidth) / 2

        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y in 0f..statusBarHeight.toFloat() && event.x in startX.toFloat()..endX.toFloat()) {
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                    isTrackingTap = true
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTrackingTap) {
                    val dy = event.y - downY
                    val dx = event.x - downX
                    if (abs(dy) > touchSlop || abs(dx) > touchSlop) {
                        isTrackingTap = false
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isTrackingTap && (System.currentTimeMillis() - downTime <= ViewConfiguration.getTapTimeout() + 150)) {
                    isTrackingTap = false
                    triggerScrollToTop(activity.window.decorView)
                    return true
                }
                isTrackingTap = false
            }
            MotionEvent.ACTION_CANCEL -> {
                isTrackingTap = false
            }
        }
        return false
    }

    private fun triggerScrollToTop(anchorView: View) {
        anchorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        controller.scrollSelectedBrowserEngineToVerticalOffset(0)
    }

    private fun expandNotificationShade() {
        try {
            val statusBarService = activity.getSystemService("statusbar")
            val statusBarManager = Class.forName("android.app.StatusBarManager")
            val expand = statusBarManager.getMethod("expandNotificationsPanel")
            expand.invoke(statusBarService)
        } catch (_: Throwable) {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd statusbar expand-notifications"))
            } catch (_: Throwable) {}
        }
    }

    private fun availableScreenWidthPx(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds.width().coerceAtLeast(1)
        } else {
            activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        }
    }

    private fun statusBarHeightPx(): Int {
        val insetsTop = ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        if (insetsTop > 0) return insetsTop
        val resId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId != 0) activity.resources.getDimensionPixelSize(resId) else (28 * activity.resources.displayMetrics.density).toInt()
    }

    fun destroy() {
        removeOverlay()
    }
}
