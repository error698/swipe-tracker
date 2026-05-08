# SwipeTracker 👉

**Automatic right-swipe counter for Bumble & Hinge**

An Android app that uses Accessibility Services to detect when you swipe right (like) on Bumble or Hinge, and keeps a running count — per app, per day, and all time. A floating overlay bubble shows your live count while you're swiping.

---

## How It Works

### Architecture (3 services working together)

```
┌─────────────────────────────────────┐
│  SwipeAccessibilityService          │  ← Detects right swipes
│  - Monitors ONLY Bumble & Hinge     │
│  - Watches for Like button clicks   │
│  - Traverses accessibility tree     │
│  - Debounces duplicate events       │
│  - Broadcasts swipe events          │
└──────────────┬──────────────────────┘
               │ broadcast
┌──────────────▼──────────────────────┐
│  OverlayService                     │  ← Floating bubble UI
│  - Shows live count over any app    │
│  - Draggable bubble (like Chat Heads)│
│  - Pulse animation + haptic on swipe│
│  - Tap to open dashboard            │
└─────────────────────────────────────┘
               ▲ show/hide
┌──────────────┴──────────────────────┐
│  AppMonitorService                  │  ← Auto-launch trigger
│  - Polls UsageStatsManager (2s)     │
│  - Detects Bumble/Hinge foreground  │
│  - Shows overlay when app opens     │
│  - Hides overlay when app closes    │
│  - Persists across reboots          │
└─────────────────────────────────────┘
```

### Swipe Detection Strategy

The Accessibility Service listens to Bumble (`com.bumble.app`) and Hinge (`co.hinge.app`) using these signals:

1. **Like button click** (primary, most reliable)
   - Catches `TYPE_VIEW_CLICKED` events
   - Scans `contentDescription`, `text`, and `viewIdResourceName`
   - Matches keywords: `like`, `yes`, `heart`, `interested`, `superswipe`, `rose`
   - Filters out pass/nope actions to avoid false positives

2. **Content change tracking** (secondary)
   - Monitors `TYPE_WINDOW_CONTENT_CHANGED`
   - Hashes visible profile text to detect card transitions
   - Currently logged for debugging; can be enabled as a fallback

3. **800ms debounce** prevents double-counting from rapid event firing

---

## Setup

### Prerequisites
- Android 8.0+ (API 26+)
- Android Studio Hedgehog or newer
- Samsung Galaxy A54 or any Android phone

### Build & Install

1. Open the project in Android Studio
2. Sync Gradle
3. Connect your phone via USB (USB debugging enabled)
4. Run the app

### Grant Permissions (in-app flow)

The app guides you through 4 permissions:

| Permission | Why | Where |
|---|---|---|
| **Accessibility Service** | Detect swipes inside Bumble/Hinge | Settings → Accessibility → SwipeTracker → ON |
| **Usage Access** | Detect when dating apps are opened | Settings → Usage Access → SwipeTracker → ON |
| **Display Over Other Apps** | Floating counter bubble | Settings → Special Access → Display Over Apps → ON |
| **Notifications** (Android 13+) | Background service notification | Grant when prompted |

### Start Tracking

After granting all permissions, tap **"Start Tracking"** in the app. That's it — the service runs in the background and auto-detects when you open Bumble or Hinge.

---

## Calibration / Fine-tuning

Bumble and Hinge update their UI periodically. If swipes stop being detected:

### Debug with Logcat

```bash
adb logcat -s SwipeTracker AppMonitor Overlay
```

This shows every accessibility event the service processes, including the content descriptions and text it finds on buttons.

### Add new keywords

In `SwipeAccessibilityService.kt`, add terms to `LIKE_KEYWORDS`:

```kotlin
private val LIKE_KEYWORDS = listOf(
    "like", "yes", "heart", "interested",
    "match", "superswipe", "super like", "rose",
    // Add new terms here if Bumble/Hinge changes their UI
)
```

### Enable content-change counting

If button detection misses gesture-based swipes, uncomment the content-change counter in `handleContentChange()`:

```kotlin
// Uncomment these lines:
Log.d(TAG, "Profile changed in ${app.displayName}")
recordSwipeDebounced(app)
```

⚠️ This will also count LEFT swipes since content change alone can't determine direction. Use alongside button detection for best accuracy.

---

## Data Storage

All data is stored locally in SharedPreferences (`swipe_tracker_prefs`). Nothing is sent anywhere. Structure:

```json
{
  "bumble": {
    "total": 42,
    "sessions": [
      { "date": "2026-05-07", "count": 15 },
      { "date": "2026-05-06", "count": 27 }
    ]
  },
  "hinge": {
    "total": 31,
    "sessions": [...]
  }
}
```

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/error698/swipetracker/
│   ├── data/
│   │   └── SwipeRepository.kt        # Data model + SharedPreferences persistence
│   ├── service/
│   │   ├── SwipeAccessibilityService.kt  # Core swipe detector
│   │   ├── AppMonitorService.kt          # Foreground app watcher
│   │   └── OverlayService.kt            # Floating bubble overlay
│   ├── receiver/
│   │   └── BootReceiver.kt              # Auto-start on reboot
│   └── ui/
│       └── MainActivity.kt              # Dashboard + permission setup
└── res/
    ├── values/
    │   ├── strings.xml
    │   └── themes.xml
    └── xml/
        └── accessibility_service_config.xml
```

---

## Known Limitations

- **Gesture-only swipes** (no button tap) may not be detected by the click handler alone. Enable content-change tracking as a fallback if needed.
- **UI changes** in Bumble/Hinge can break keyword matching. Check logcat and update keywords.
- **Battery**: UsageStatsManager polling at 2s intervals has minimal battery impact. The accessibility service is event-driven and essentially zero-cost when dating apps aren't open.
- **Can't distinguish swipe direction** from content changes alone — only the click handler reliably identifies RIGHT swipes specifically.
"# swipe-tracker" 
