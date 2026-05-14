package com.yourtube.core.network

import com.yourtube.core.network.di.NetworkModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * YT-0309 — pins the OkHttp `callTimeout` applied by [NetworkModule.provideOkHttpClient].
 * The 14s cap must be present so airplane-mode hangs lose the race deterministically
 * against the 15s Kotlin `withTimeoutOrNull` ceilings in `DefaultPlayerController` and
 * `PlaybackService`. A future drive-by removal of the timeout (or a new `OkHttpClient.Builder()`
 * call without it) fails this test before reaching smoke.
 */
class NetworkModuleTest {

    @Test
    fun `provideOkHttpClient has 14s call timeout`() {
        val client = NetworkModule.provideOkHttpClient()
        assertEquals(
            14_000,
            client.callTimeoutMillis,
            "OkHttp callTimeout must be 14 000 ms (14s) so it loses the race vs " +
                "the 15s Kotlin withTimeoutOrNull ceilings (DefaultPlayerController + PlaybackService).",
        )
    }
}
