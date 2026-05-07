package com.yourtube.core.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Mirrors `VisitorIDCacheTests` from iOS YT-0162. Exercises the
 * [Deferred]-based cooperative coalescing primitive.
 */
class VisitorIdCacheTest {

    @Test
    fun `concurrent first fetches collapse to a single transport hit`() {
        val loadCount = AtomicInteger(0)
        val gate = CountDownLatch(1)

        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    loadCount.incrementAndGet()
                    // Hold the response until all 5 callers are await()ing the
                    // shared Deferred — this proves coalescing isn't an
                    // accident of fast sequencing.
                    gate.await(2, TimeUnit.SECONDS)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(VISITOR_BODY.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

        val cache = VisitorIdCache(client)

        val results = runBlocking {
            val launches = (1..5).map {
                async(Dispatchers.IO, start = CoroutineStart.DEFAULT) {
                    cache.fetch()
                }
            }
            // Give all 5 a moment to subscribe to the shared Deferred.
            delay(50)
            gate.countDown()
            launches.awaitAll()
        }

        assertTrue(
            results.all { it == EXPECTED_TOKEN },
            "Expected all 5 awaiters to receive '$EXPECTED_TOKEN', got: $results",
        )
        assertEquals(
            1,
            loadCount.get(),
            "Concurrent first-fetches must collapse to exactly 1 transport hit, got ${loadCount.get()}",
        )
    }

    @Test
    fun `subsequent fetches hit the cache without further transport calls`() {
        val loadCount = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    loadCount.incrementAndGet()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(VISITOR_BODY.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

        val cache = VisitorIdCache(client)
        runBlocking {
            cache.fetch()
            cache.fetch()
            cache.fetch()
        }

        assertEquals(1, loadCount.get())
    }

    @Test
    fun `transport failure returns null without crashing the caller`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { _ -> throw RuntimeException("offline") },
            )
            .build()

        val cache = VisitorIdCache(client)
        val result = runBlocking { cache.fetch() }
        assertNull(result)
    }

    @Test
    fun `non-2xx response returns null without crashing`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("ERR")
                        .body("server down".toResponseBody("text/plain".toMediaType()))
                        .build()
                },
            )
            .build()

        val cache = VisitorIdCache(client)
        val result = runBlocking { cache.fetch() }
        assertNull(result)
    }

    @Test
    fun `malformed JSON returns null without crashing`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{not json".toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

        val cache = VisitorIdCache(client)
        val result = runBlocking { cache.fetch() }
        assertNull(result)
    }

    @Test
    fun `caller cancellation propagates as CancellationException`() {
        // Use an interceptor that sleeps long enough for cancellation to fire.
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Thread.sleep(2_000)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(VISITOR_BODY.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

        val cache = VisitorIdCache(client)

        runBlocking {
            val scope = CoroutineScope(Dispatchers.IO)
            val deferred = scope.async { cache.fetch() }
            // Cancel before the interceptor has time to return.
            delay(50)
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                deferred.cancelAndJoin()
                // cancelAndJoin awaits without throwing, so we re-await to surface.
                deferred.await()
            }
        }
    }

    private companion object {
        private const val EXPECTED_TOKEN = "V0_TEST_TOKEN"
        private const val VISITOR_BODY =
            """{"responseContext":{"visitorData":"V0_TEST_TOKEN"}}"""
    }
}
