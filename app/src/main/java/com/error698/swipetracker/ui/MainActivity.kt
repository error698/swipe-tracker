package com.error698.swipetracker.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.error698.swipetracker.data.SwipeApp
import com.error698.swipetracker.data.SwipeRepository
import com.error698.swipetracker.service.AppMonitorService
import com.error698.swipetracker.service.SwipeAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var bumbleTotalText: TextView
    private lateinit var bumbleTodayText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var permissionsContainer: LinearLayout

    private val swipeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStats()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = buildUI()
        setContentView(root)

        refreshStats()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
        checkPermissions()

        val filter = IntentFilter(SwipeAccessibilityService.ACTION_SWIPE_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(swipeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(swipeReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(swipeReceiver) } catch (_: Exception) {}
    }

    // ─── Build UI programmatically ───

    private fun buildUI(): View {
        val bg = Color.parseColor("#0f0f1a")

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }

        // Title
        root.addView(TextView(this).apply {
            text = "SwipeTracker"
            setTextColor(Color.parseColor("#FFC629"))
            textSize = 32f
            typeface = Typeface.create("serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Right swipe counter for Bumble"
            setTextColor(Color.parseColor("#666680"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(24))
        })

        // Status
        statusText = TextView(this).apply {
            text = "Checking permissions..."
            setTextColor(Color.parseColor("#888899"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#1a1a2e"))
        }
        root.addView(statusText)

        // Permissions container
        permissionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(permissionsContainer)

        // Start/Stop button
        startButton = Button(this).apply {
            text = "Start Tracking"
            setTextColor(Color.parseColor("#0f0f1a"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#FFC629"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { toggleService() }
        }
        root.addView(startButton.apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        })

        root.addView(createSpacer(24))

        // ── Bumble card ──
        root.addView(createAppCard(
            "Bumble", "#FFC629"
        ) { total, today ->
            bumbleTotalText = total
            bumbleTodayText = today
        })

        root.addView(createSpacer(24))

        // Reset button
        root.addView(Button(this).apply {
            text = "Reset All Data"
            setTextColor(Color.parseColor("#555566"))
            textSize = 13f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reset data?")
                    .setMessage("This will clear all swipe history.")
                    .setPositiveButton("Reset") { _, _ ->
                        SwipeRepository.reset(this@MainActivity)
                        refreshStats()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        scroll.addView(root)
        return scroll
    }

    private fun createAppCard(
        name: String,
        accentHex: String,
        assignViews: (total: TextView, today: TextView) -> Unit
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.parseColor("#1a1a2e"))
        }

        card.addView(TextView(this).apply {
            text = name
            setTextColor(Color.parseColor(accentHex))
            textSize = 20f
            typeface = Typeface.create("serif", Typeface.BOLD)
        })

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }

        val todayText = TextView(this).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 36f
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }

        val todayLabel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(todayText)
            addView(TextView(context).apply {
                text = "TODAY"
                setTextColor(Color.parseColor("#555566"))
                textSize = 10f
                letterSpacing = 0.15f
            })
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val totalText = TextView(this).apply {
            text = "0"
            setTextColor(Color.parseColor(accentHex))
            textSize = 36f
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }

        val totalLabel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(totalText)
            addView(TextView(context).apply {
                text = "ALL TIME"
                setTextColor(Color.parseColor("#555566"))
                textSize = 10f
                letterSpacing = 0.15f
            })
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        statsRow.addView(todayLabel)
        statsRow.addView(totalLabel)
        card.addView(statsRow)

        assignViews(totalText, todayText)
        return card
    }

    private fun createSpacer(dpHeight: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(dpHeight)
        )
    }

    // ─── Stats ───

    private fun refreshStats() {
        val store = SwipeRepository.load(this)
        val today = java.time.LocalDate.now().toString()

        bumbleTotalText.text = store.bumble.total.toString()
        bumbleTodayText.text = store.bumble.sessions
            .filter { it.date == today }.sumOf { it.count }.toString()
    }

    // ─── Permissions ───

    private fun checkPermissions() {
        permissionsContainer.removeAllViews()
        var allGranted = true

        // 1. Accessibility service
        val accEnabled = isAccessibilityEnabled()
        if (!accEnabled) {
            allGranted = false
            permissionsContainer.addView(createPermissionRow(
                "Accessibility Service",
                "Required to detect right swipes"
            ) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }

        // 2. Usage stats
        val usageGranted = isUsageStatsGranted()
        if (!usageGranted) {
            allGranted = false
            permissionsContainer.addView(createPermissionRow(
                "Usage Access",
                "Required to detect when Bumble opens"
            ) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            })
        }

        // 3. Overlay
        val overlayGranted = Settings.canDrawOverlays(this)
        if (!overlayGranted) {
            allGranted = false
            permissionsContainer.addView(createPermissionRow(
                "Display Over Other Apps",
                "Required for floating swipe counter"
            ) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            })
        }

        // 4. Notification (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!notifGranted) {
                allGranted = false
                permissionsContainer.addView(createPermissionRow(
                    "Notifications",
                    "Required for background service"
                ) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100
                    )
                })
            }
        }

        if (allGranted) {
            statusText.text = "✅ All permissions granted — ready to track!"
            statusText.setTextColor(Color.parseColor("#4ECDC4"))
            startButton.isEnabled = true
        } else {
            statusText.text = "⚠️ Grant permissions below to enable tracking"
            statusText.setTextColor(Color.parseColor("#E94057"))
            startButton.isEnabled = false
        }
    }

    private fun createPermissionRow(
        title: String,
        desc: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.parseColor("#1a1025"))

            val textContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textContainer.addView(TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
            textContainer.addView(TextView(context).apply {
                text = desc
                setTextColor(Color.parseColor("#666680"))
                textSize = 11f
            })
            addView(textContainer)

            addView(Button(context).apply {
                text = "Grant"
                setTextColor(Color.parseColor("#0f0f1a"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setBackgroundColor(Color.parseColor("#FFC629"))
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener { onClick() }
            })

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabled.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    private fun isUsageStatsGranted(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // ─── Service control ───

    private fun toggleService() {
        val intent = Intent(this, AppMonitorService::class.java)
        startForegroundService(intent)
        Toast.makeText(this, "SwipeTracker monitoring started!", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
