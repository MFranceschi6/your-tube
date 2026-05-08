package com.yourtube.app.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.yourtube.core.common.ConnectivityMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `ConnectivityManager`-backed [ConnectivityMonitor].
 *
 * Reports `true` only when the active network has both `NET_CAPABILITY_INTERNET`
 * AND `NET_CAPABILITY_VALIDATED` — the catalog requires a positive platform
 * signal to flip Search into the offline (C5) variant; we don't want to surface
 * "You're offline" from a captive portal that has internet but no validation.
 */
@Singleton
class AndroidConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConnectivityMonitor {

    override fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
