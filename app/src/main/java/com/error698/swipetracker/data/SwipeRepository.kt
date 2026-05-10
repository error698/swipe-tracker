package com.error698.swipetracker.data

import android.content.Context
import com.google.gson.Gson

data class SwipeSession(
    val date: String,       // ISO date e.g. "2026-05-07"
    val count: Int
)

data class AppSwipeData(
    var total: Int = 0,
    val sessions: MutableList<SwipeSession> = mutableListOf()
)

data class SwipeStore(
    val bumble: AppSwipeData = AppSwipeData(),
    val hinge: AppSwipeData = AppSwipeData()
)

object SwipeRepository {
    private const val PREFS_NAME = "swipe_tracker_prefs"
    private const val KEY_DATA = "swipe_data"
    private val gson = Gson()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): SwipeStore {
        val json = prefs(ctx).getString(KEY_DATA, null) ?: return SwipeStore()
        return try {
            gson.fromJson(json, SwipeStore::class.java)
        } catch (e: Exception) {
            SwipeStore()
        }
    }

    fun save(ctx: Context, store: SwipeStore) {
        prefs(ctx).edit().putString(KEY_DATA, gson.toJson(store)).apply()
    }

    /**
     * Increment right-swipe count for the given app.
     * Thread-safe via synchronized block.
     * Returns the new total for that app.
     */
    @Synchronized
    fun recordSwipe(ctx: Context, app: SwipeApp): Int {
        val store = load(ctx)
        val data = when (app) {
            SwipeApp.BUMBLE -> store.bumble
            SwipeApp.HINGE -> store.hinge
        }

        data.total += 1

        val today = java.time.LocalDate.now().toString()
        val lastSession = data.sessions.lastOrNull()
        if (lastSession != null && lastSession.date == today) {
            // Update today's session count
            data.sessions[data.sessions.lastIndex] =
                lastSession.copy(count = lastSession.count + 1)
        } else {
            data.sessions.add(SwipeSession(date = today, count = 1))
        }

        save(ctx, store)
        return data.total
    }

    fun reset(ctx: Context) {
        save(ctx, SwipeStore())
    }
}

enum class SwipeApp(val packageName: String, val displayName: String) {
    BUMBLE("com.bumble.app", "Bumble"),
    HINGE("co.hinge.app", "Hinge");

    companion object {
        fun fromPackage(pkg: String): SwipeApp? = entries.find { it.packageName == pkg }
    }
}
