package com.yourtube.core.network

import com.yourtube.core.common.model.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class InnerTubeSearchClientTest {

    @Test
    fun `parses videoRenderers and skips non-video shelf entries`() {
        val client = InnerTubeSearchClient(cannedClient(code = 200, body = loadFixture(FIXTURE)))

        val results = client.search("lofi")

        assertEquals(
            listOf(
                SearchResult(
                    videoId = "alpha123",
                    title = "Late Night Coding Mix",
                    channel = "Open Waves",
                    durationSec = 3723,
                    thumbnailUrl = "https://img.youtube.com/vi/alpha123/mqdefault.jpg",
                ),
                SearchResult(
                    videoId = "beta456",
                    title = "Live Lofi Stream",
                    channel = "Desk Radio",
                    durationSec = 0,
                    thumbnailUrl = "https://img.youtube.com/vi/beta456/mqdefault.jpg",
                ),
                SearchResult(
                    videoId = "gamma789",
                    title = "Focus Session",
                    channel = "Studyverse",
                    durationSec = 272,
                    thumbnailUrl = "https://img.youtube.com/vi/gamma789/mqdefault.jpg",
                ),
            ),
            results,
        )
    }

    @Test
    fun `non-2xx response returns empty without throwing`() {
        val client = InnerTubeSearchClient(cannedClient(code = 500, body = "internal error"))

        assertEquals(emptyList(), client.search("anything"))
    }

    @Test
    fun `malformed JSON returns empty without throwing`() {
        val client = InnerTubeSearchClient(cannedClient(code = 200, body = "{not json"))

        assertEquals(emptyList(), client.search("anything"))
    }

    @Test
    fun `unexpected JSON shape returns empty without throwing`() {
        val client = InnerTubeSearchClient(cannedClient(code = 200, body = """{"contents":{}}"""))

        assertEquals(emptyList(), client.search("anything"))
    }

    @Test
    fun `request body escapes embedded quotes in the query`() {
        var sentBody = ""
        val client = InnerTubeSearchClient(
            cannedClient(code = 200, body = "{}") { request ->
                val buffer = okio.Buffer()
                request.body?.writeTo(buffer)
                sentBody = buffer.readUtf8()
            },
        )

        client.search("""he said "hi"""")

        assertTrue(sentBody.contains("""he said \"hi\""""), "body was: $sentBody")
    }

    private fun cannedClient(
        code: Int,
        body: String,
        onRequest: (okhttp3.Request) -> Unit = {},
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                onRequest(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "ERR")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            },
        )
        .build()

    private fun loadFixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "Missing fixture: $name" }
            .readText()

    private companion object {
        private const val FIXTURE = "innertube-search-fixture.json"
    }
}
