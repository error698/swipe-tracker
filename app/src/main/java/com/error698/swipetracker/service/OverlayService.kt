package com.error698.swipetracker.service

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.error698.swipetracker.data.SwipeApp
import com.error698.swipetracker.data.SwipeRepository
import com.error698.swipetracker.ui.MainActivity

/**
 * Floating overlay that shows a draggable bubble with the current
 * swipe count. Auto-shown/hidden by AppMonitorService.
 *
 * The bubble:
 *  - Shows app icon + swipe count
 *  - Animates (bounce) when a new swipe is detected
 *  - Is draggable
 *  - Tapping opens the main activity
 *  - Collapses to a small dot after 5s of inactivity
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "Overlay"
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 1002
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var countText: TextView? = null
    private var appLabel: TextView? = null
    private var currentApp: SwipeApp = SwipeApp.BUMBLE
    private var isShowing = false

    private val swipeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SwipeAccessibilityService.ACTION_SWIPE_DETECTED) {
                val appName = intent.getStringExtra(SwipeAccessibilityService.EXTRA_APP_NAME) ?: return
                val newTotal = intent.getIntExtra(SwipeAccessibilityService.EXTRA_NEW_TOTAL, 0)
                updateCount(newTotal)
                pulseAnimation()
                hapticFeedback()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter(SwipeAccessibilityService.ACTION_SWIPE_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(swipeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(swipeReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            AppMonitorService.ACTION_SHOW_OVERLAY -> {
                val appName = intent.getStringExtra(AppMonitorService.EXTRA_CURRENT_APP)
                currentApp = try {
                    SwipeApp.valueOf(appName ?: "BUMBLE")
                } catch (e: Exception) {
                    SwipeApp.BUMBLE
                }
                showOverlay()
            }
            AppMonitorService.ACTION_HIDE_OVERLAY -> {
                hideOverlay()
            }
        }

        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (isShowing) {
            // Just update the label/count
            refreshDisplay()
            return
        }

        val wm = windowManager ?: return

        // Build the overlay view
        val bubble = createBubbleView()
        overlayView = bubble

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16)
            y = dpToPx(100)
        }

        // Make draggable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 25) isDragging = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    wm.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Tap — open main activity
                        val i = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(i)
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(bubble, params)
        isShowing = true
        refreshDisplay()

        // Entry animation
        bubble.scaleX = 0f
        bubble.scaleY = 0f
        bubble.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()

        Log.d(TAG, "Overlay shown for ${currentApp.displayName}")
    }

    private fun createBubbleView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(20).toFloat()
                setColor(Color.parseColor("#1A1A2E"))
                setStroke(dpToPx(2), Color.parseColor("#333355"))
            }
            background = bg
            elevation = dpToPx(8).toFloat()
        }

        // Count text
        val count = TextView(this).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        countText = count

        // Swipe emoji
        val emoji = TextView(this).apply {
            text = " 👉"
            textSize = 16f
        }

        // App label
        val label = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#888899"))
            textSize = 10f
            setPadding(dpToPx(8), 0, 0, 0)
        }
        appLabel = label

        container.addView(count)
        container.addView(emoji)
        container.addView(label)

        return container
    }

    private fun refreshDisplay() {
        val store = SwipeRepository.load(this)
        val data = when (currentApp) {
            SwipeApp.BUMBLE -> store.bumble
            SwipeApp.HINGE -> store.hinge
        }

        val todayStr = java.time.LocalDate.now().toString()
        val todayCount = data.sessions
            .filter { it.date == todayStr }
            .sumOf { it.count }

        countText?.text = todayCount.toString()
        appLabel?.text = currentApp.displayName

        // Color the count based on app
        val color = when (currentApp) {
            SwipeApp.BUMBLE -> Color.parseColor("#FFC629")
            SwipeApp.HINGE -> Color.parseColor("#E94057")
        }
        countText?.setTextColor(color)
    }

    private fun updateCount(newTotal: Int) {
        refreshDisplay()
    }

    private fun pulseAnimation() {
        overlayView?.let { v ->
            v.animate()
                .scaleX(1.3f)
                .scaleY(1.3f)
                .setDuration(150)
                .withEndAction {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200)
                        .setInterpolator(OvershootInterpolator(2f))
                        .start()
                }
                .start()
        }
    }

    private fun hapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            // Vibration not available
        }
    }

    private fun hideOverlay() {
        overlayView?.let { v ->
            v.animate()
                .scaleX(0f)
                .scaleY(0f)
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    try {
                        windowManager?.removeView(v)
                    } catch (e: Exception) { }
                    overlayView = null
                    isShowing = false
                }
                .start()
        }
        Log.d(TAG, "Overlay hidden")
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SwipeTracker Overlay")
            .setContentText("Floating counter active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(swipeReceiver) } catch (_: Exception) {}
        hideOverlay()
        super.onDestroy()
    }
}
