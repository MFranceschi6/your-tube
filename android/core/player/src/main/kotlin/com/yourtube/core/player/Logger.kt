package com.yourtube.core.player

import android.util.Log
import javax.inject.Inject

/**
 * Tiny logging abstraction local to `core/player`.
 *
 * Production code in this module logs through this interface so plain JVM unit
 * tests do not need Robolectric just to satisfy `android.util.Log` calls.
 *
 * The scope is intentionally limited to `core/player`. If other modules later
 * need the same abstraction, that is a separate task.
 */
interface Logger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Production [Logger] that delegates straight to [android.util.Log].
 *
 * Behavior is unchanged from the previous direct `Log.*` calls.
 */
class AndroidLogger @Inject constructor() : Logger {
    override fun debug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun info(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
