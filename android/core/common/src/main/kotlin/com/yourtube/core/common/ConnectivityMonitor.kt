package com.yourtube.core.common

/**
 * One-shot connectivity probe consumed by ViewModels that need to discriminate
 * a request failure as "offline" (catalog Search C5) vs. a generic server-side
 * error (catalog Search C4).
 *
 * The catalog rule:
 * > Detection: platform connectivity API; do NOT infer offline from a generic
 * > timeout.
 *
 * Implementation lives in `app/` and wraps `ConnectivityManager`. Tests provide
 * a hand-written fake — no mocking library needed.
 */
fun interface ConnectivityMonitor {
    /** Returns `true` when the device currently reports a validated internet route. */
    fun isOnline(): Boolean
}
