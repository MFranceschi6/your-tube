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

/**
 * Unit tests for [RelatedVideoClient].
 *
 * Uses the same OkHttp Interceptor-fake transport pattern as
 * [InnerTubeSearchClientTest] — no MockWebServer required.
 */
class RelatedVideoClientTest {

    // ── Test 1: Path-A (secondaryResultsRenderer) returns candidates ────────

    @Test
    fun `path-A renderer shape returns parsed candidates`() {
        val client = RelatedVideoClient(cannedClient(200, PATH_A_JSON))

        val results = client.getRelatedVideos("abc123")

        assertEquals(1, results.size)
        assertEquals(
            SearchResult(
                videoId = "related001",
                title = "Related Track One",
                channel = "Some Channel",
                durationSec = 243,
                thumbnailUrl = "https://img.youtube.com/vi/related001/mqdefault.jpg",
            ),
            results.first(),
        )
    }

    // ── Test 2: Path-A empty → Path-B fallback returns candidates ───────────

    @Test
    fun `path-A empty and path-B legacy shape returns parsed candidates`() {
        val client = RelatedVideoClient(cannedClient(200, PATH_B_FALLBACK_JSON))

        val results = client.getRelatedVideos("abc123")

        assertTrue(results.isNotEmpty(), "Expected fallback candidates, got empty list")
        assertEquals("related002", results.first().videoId)
        assertEquals("Fallback Track", results.first().title)
        assertEquals("Other Channel", results.first().channel)
        assertEquals(185, results.first().durationSec)
    }

    // ── Test 3: Both paths empty returns empty list ──────────────────────────

    @Test
    fun `both paths empty returns empty list`() {
        val client = RelatedVideoClient(cannedClient(200, BOTH_EMPTY_JSON))

        val results = client.getRelatedVideos("abc123")

        assertEquals(emptyList(), results)
    }

    // ── Test 4: HTTP non-2xx returns empty list ──────────────────────────────

    @Test
    fun `HTTP 429 returns empty list without throwing`() {
        val client = RelatedVideoClient(cannedClient(429, "rate limited"))

        val results = client.getRelatedVideos("abc123")

        assertEquals(emptyList(), results)
    }

    // ── Test 5: Malformed JSON returns empty list ────────────────────────────

    @Test
    fun `malformed JSON returns empty list without throwing`() {
        val client = RelatedVideoClient(cannedClient(200, ")]}'garbage{not json"))

        val results = client.getRelatedVideos("abc123")

        assertEquals(emptyList(), results)
    }

    // ── Test 6: lockupViewModel shape returns candidates ─────────────────────

    @Test
    fun `lockupViewModel shape returns parsed candidates`() {
        val client = RelatedVideoClient(cannedClient(200, LOCKUP_JSON))

        val results = client.getRelatedVideos("abc123")

        assertEquals(1, results.size)
        val r = results.first()
        assertEquals("lock001", r.videoId)
        assertEquals("Smooth Criminal", r.title)
        assertEquals("MJ Channel", r.channel)
        // duration badge "9:26" → 9*60+26 = 566
        assertEquals(566, r.durationSec)
        assertEquals("https://i.ytimg.com/vi/lock001/hqdefault.jpg", r.thumbnailUrl)
    }

    // ── Test 7: lockupViewModel non-VIDEO type is skipped ────────────────────

    @Test
    fun `lockupViewModel non-VIDEO contentType is skipped`() {
        val client = RelatedVideoClient(cannedClient(200, LOCKUP_PLAYLIST_JSON))

        val results = client.getRelatedVideos("abc123")

        assertEquals(emptyList(), results)
    }

    // ── Test 8: mixed lockupViewModel and compactVideoRenderer in one results array ─

    @Test
    fun `mixed lockupViewModel and compactVideoRenderer both parsed`() {
        val client = RelatedVideoClient(cannedClient(200, MIXED_RENDERERS_JSON))

        val results = client.getRelatedVideos("abc123")

        assertEquals(2, results.size)
        assertEquals("lock002", results[0].videoId)
        assertEquals("compact003", results[1].videoId)
    }

    // ── Test 9: lockupViewModel missing duration badge returns durationSec 0 ─

    @Test
    fun `lockupViewModel missing duration badge returns durationSec 0`() {
        val client = RelatedVideoClient(cannedClient(200, LOCKUP_NO_DURATION_JSON))

        val results = client.getRelatedVideos("abc123")

        assertEquals(1, results.size)
        assertEquals(0, results.first().durationSec)
    }

    // ── Mix queue tests (YT-0293) ─────────────────────────────────────────────

    @Test
    fun `getMixQueue returns tracks from playlistPanelVideoRenderer`() {
        val client = RelatedVideoClient(cannedClient(200, MIX_QUEUE_JSON))

        val results = client.getMixQueue("dQw4w9WgXcQ")

        assertEquals(2, results.size)
        assertEquals(
            SearchResult(
                videoId = "dQw4w9WgXcQ",
                title = "Rick Astley - Never Gonna Give You Up (Official Video)",
                channel = "Rick Astley",
                durationSec = 214,   // "3:34" → 3*60+34 = 214
                thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=large",
            ),
            results[0],
        )
        assertEquals("Zi_XLOBDo_Y", results[1].videoId)
        assertEquals("Michael Jackson", results[1].channel)
        assertEquals(296, results[1].durationSec)  // "4:56" → 4*60+56 = 296
    }

    @Test
    fun `getMixQueue returns empty on HTTP error`() {
        val client = RelatedVideoClient(cannedClient(429, "rate limited"))

        val results = client.getMixQueue("dQw4w9WgXcQ")

        assertEquals(emptyList(), results)
    }

    @Test
    fun `getMixQueue filters tracks with blank videoId`() {
        val client = RelatedVideoClient(cannedClient(200, MIX_QUEUE_BLANK_VIDEOID_JSON))

        val results = client.getMixQueue("dQw4w9WgXcQ")

        // One entry has a blank videoId and must be filtered; only the valid one is returned.
        assertEquals(1, results.size)
        assertEquals("validTrack", results[0].videoId)
    }

    // ── YT-0296 Mix continuation / pagination ────────────────────────────────

    @Test
    fun `getMixQueueWithContinuation surfaces first continuation token alongside items`() {
        val client = RelatedVideoClient(cannedClient(200, MIX_INITIAL_WITH_TOKEN_JSON))

        val result = client.getMixQueueWithContinuation("dQw4w9WgXcQ")

        assertEquals(2, result.items.size)
        assertEquals("dQw4w9WgXcQ", result.items[0].videoId)
        assertEquals("Zi_XLOBDo_Y", result.items[1].videoId)
        assertEquals(MIX_TOKEN_PAGE_2, result.nextToken)
    }

    @Test
    fun `getMixQueueWithContinuation returns null token when continuations missing`() {
        // Same as the legacy MIX_QUEUE_JSON without a continuations[] block — terminal
        // initial page. Items should still parse; token should be null.
        val client = RelatedVideoClient(cannedClient(200, MIX_QUEUE_JSON))

        val result = client.getMixQueueWithContinuation("dQw4w9WgXcQ")

        assertEquals(2, result.items.size)
        assertEquals(null, result.nextToken)
    }

    @Test
    fun `getMixContinuation parses appended items and exposes next token`() {
        val client = RelatedVideoClient(cannedClient(200, MIX_CONTINUATION_PAGE_2_JSON))

        val result = client.getMixContinuation(MIX_TOKEN_PAGE_2)

        assertEquals(2, result.items.size)
        assertEquals("5anLPw0Efmo", result.items[0].videoId)
        assertEquals(566, result.items[0].durationSec) // "9:26"
        assertEquals("h_D3VFfhvs4", result.items[1].videoId)
        assertEquals(MIX_TOKEN_PAGE_3, result.nextToken)
    }

    @Test
    fun `getMixContinuation returns null token on terminal page`() {
        val client = RelatedVideoClient(cannedClient(200, MIX_CONTINUATION_TERMINAL_JSON))

        val result = client.getMixContinuation(MIX_TOKEN_PAGE_3)

        assertEquals(1, result.items.size)
        assertEquals("terminalTrack", result.items[0].videoId)
        assertEquals(null, result.nextToken)
    }

    @Test
    fun `getMixContinuation returns empty on blank token without HTTP`() {
        // No canned response is set; an HTTP call would surface as a connection failure.
        val client = RelatedVideoClient(OkHttpClient())

        val result = client.getMixContinuation("")

        assertEquals(emptyList(), result.items)
        assertEquals(null, result.nextToken)
    }

    @Test
    fun `getMixContinuation returns empty on HTTP 429`() {
        val client = RelatedVideoClient(cannedClient(429, "rate limited"))

        val result = client.getMixContinuation(MIX_TOKEN_PAGE_2)

        assertEquals(emptyList(), result.items)
        assertEquals(null, result.nextToken)
    }

    @Test
    fun `getMixContinuation returns empty on malformed JSON`() {
        val client = RelatedVideoClient(cannedClient(200, "not json"))

        val result = client.getMixContinuation(MIX_TOKEN_PAGE_2)

        assertEquals(emptyList(), result.items)
        assertEquals(null, result.nextToken)
    }

    /**
     * YT-0296 acceptance: walk initial page + one continuation page through the parser
     * and assert the merged ordered list and terminal token state.
     */
    @Test
    fun `walking initial plus continuation produces merged ordered list with terminal token state`() {
        // Page 1: initial /next call, returns 2 items + token A.
        val page1Client = RelatedVideoClient(cannedClient(200, MIX_INITIAL_WITH_TOKEN_JSON))
        val page1 = page1Client.getMixQueueWithContinuation("dQw4w9WgXcQ")

        // Page 2: continuation call with token A, returns 2 more items + token B.
        val page2Client = RelatedVideoClient(cannedClient(200, MIX_CONTINUATION_PAGE_2_JSON))
        assertEquals(MIX_TOKEN_PAGE_2, page1.nextToken)
        val page2 = page2Client.getMixContinuation(page1.nextToken!!)

        // Page 3: continuation call with token B, returns 1 more item + null (terminal).
        val page3Client = RelatedVideoClient(cannedClient(200, MIX_CONTINUATION_TERMINAL_JSON))
        assertEquals(MIX_TOKEN_PAGE_3, page2.nextToken)
        val page3 = page3Client.getMixContinuation(page2.nextToken!!)

        val merged = page1.items + page2.items + page3.items

        assertEquals(
            listOf("dQw4w9WgXcQ", "Zi_XLOBDo_Y", "5anLPw0Efmo", "h_D3VFfhvs4", "terminalTrack"),
            merged.map { it.videoId },
        )
        // Terminal token state — the consumer should stop paginating after page 3.
        assertEquals(null, page3.nextToken)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun cannedClient(code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
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

    private companion object {

        /**
         * Path-A: secondaryResults → secondaryResultsRenderer → results[]
         * Contains one compactVideoRenderer with a simpleText title.
         * Duration "4:03" → 4*60+3 = 243 seconds.
         */
        private val PATH_A_JSON = """
            {
              "contents": {
                "twoColumnWatchNextResults": {
                  "secondaryResults": {
                    "secondaryResultsRenderer": {
                      "results": [
                        {
                          "compactVideoRenderer": {
                            "videoId": "related001",
                            "title": { "simpleText": "Related Track One" },
                            "shortBylineText": {
                              "runs": [ { "text": "Some Channel" } ]
                            },
                            "lengthText": { "simpleText": "4:03" },
                            "thumbnail": {
                              "thumbnails": [
                                { "url": "https://img.youtube.com/vi/related001/default.jpg", "width": 120, "height": 90 },
                                { "url": "https://img.youtube.com/vi/related001/mqdefault.jpg", "width": 320, "height": 180 }
                              ]
                            }
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        /**
         * Path-A renderer results array is explicitly empty; Path-B legacy
         * secondaryResults → secondaryResults → results[] has one candidate.
         *
         * NOTE: kotlinx.serialization JSON parsing keeps only the last duplicate
         * key in a JSON object; both keys named "secondaryResults" appear at the
         * same level so the parser retains the inner-results one. The Path-A
         * renderer key is a sibling at the OUTER level ("secondaryResultsRenderer"),
         * so it survives. The Path-B key ("secondaryResults") overrides any earlier
         * key of the same name — which is exactly the legacy fallback shape we need.
         *
         * Duration "3:05" → 3*60+5 = 185 seconds.
         */
        private val PATH_B_FALLBACK_JSON = """
            {
              "contents": {
                "twoColumnWatchNextResults": {
                  "secondaryResults": {
                    "secondaryResultsRenderer": {
                      "results": []
                    },
                    "secondaryResults": {
                      "results": [
                        {
                          "compactVideoRenderer": {
                            "videoId": "related002",
                            "title": { "simpleText": "Fallback Track" },
                            "shortBylineText": {
                              "runs": [ { "text": "Other Channel" } ]
                            },
                            "lengthText": { "simpleText": "3:05" },
                            "thumbnail": {
                              "thumbnails": [
                                { "url": "https://img.youtube.com/vi/related002/mqdefault.jpg", "width": 320, "height": 180 }
                              ]
                            }
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        /** Both results arrays are empty. */
        private val BOTH_EMPTY_JSON = """
            {
              "contents": {
                "twoColumnWatchNextResults": {
                  "secondaryResults": {
                    "secondaryResultsRenderer": {
                      "results": []
                    },
                    "secondaryResults": {
                      "results": []
                    }
                  }
                }
              }
            }
        """.trimIndent()

        /**
         * Current InnerTube /next shape: Path-B flat with lockupViewModel items.
         * Duration badge "9:26" → 9*60+26 = 566 seconds.
         */
        private val LOCKUP_JSON = """
            {
              "contents": {
                "twoColumnWatchNextResults": {
                  "secondaryResults": {
                    "secondaryResults": {
                      "results": [
                        {
                          "lockupViewModel": {
                            "contentId": "lock001",
                            "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                            "contentImage": {
                              "thumbnailViewModel": {
                                "image": {
                                  "sources": [
                                    { "url": "https://i.ytimg.com/vi/lock001/default.jpg", "width": 168, "height": 94 },
                                    { "url": "https://i.ytimg.com/vi/lock001/hqdefault.jpg", "width": 336, "height": 188 }
                                  ]
                                },
                                "overlays": [
                                  {
                                    "thumbnailBottomOverlayViewModel": {
                                      "badges": [
                                        {
                                          "thumbnailBadgeViewModel": {
                                            "text": "9:26"
                                          }
                                        }
                                      ]
                                    }
                                  }
                                ]
                              }
                            },
                            "metadata": {
                              "lockupMetadataViewModel": {
                                "title": { "content": "Smooth Criminal" },
                                "metadata": {
                                  "contentMetadataViewModel": {
                                    "metadataRows": [
                                      {
                                        "metadataParts": [
                                          { "text": { "content": "MJ Channel" } }
                                        ]
                                      }
                                    ]
                                  }
                                }
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        /** lockupViewModel with contentType = LOCKUP_CONTENT_TYPE_PLAYLIST — must be filtered out. */
        private val LOCKUP_PLAYLIST_JSON = """
            {
              "contents": {
                "twoColumnWatchNextResults": {
                  "secondaryResults": {
                    "secondaryResults": {
                      "results": [
                        {
                          "lockupViewModel": {
                            "contentId": "PLtest",
                            "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST",
                            "metadata": {
                              "lockupMetadataViewModel": {
                                "title": { "content": "Best Of Playlist" }
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        /**
         * Mixed: first item is lockupViewModel, second is legacy compactVideoRenderer.
         * Both must be parsed and returned in order.
         */
        private val MIXED_RENDERERS_JSON = """
            {
              "contents": {
                "twoColumnWatchNextResults": {
                  "secondaryResults": {
                    "secondaryResults": {
                      "results": [
                        {
                          "lockupViewModel": {
                            "contentId": "lock002",
                            "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                            "contentImage": {
                              "thumbnailViewModel": {
                                "image": { "sources": [{ "url": "https://i.ytimg.com/vi/lock002/hq.jpg" }] },
                                "overlays": [
                                  {
                                    "thumbnailBottomOverlayViewModel": {
                                      "badges": [{ "thumbnailBadgeViewModel": { "text": "3:30" } }]
                                    }
                                  }
                                ]
                              }
                            },
                            "metadata": {
                              "lockupMetadataViewModel": {
                                "title": { "content": "Lockup Track" },
                                "metadata": {
                                  "contentMetadataViewModel": {
                                    "metadataRows": [{ "metadataParts": [{ "text": { "content": "Chan A" } }] }]
                                  }
                                }
                              }
                            }
                          }
                        },
                        {
                          "compactVideoRenderer": {
                            "videoId": "compact003",
                            "title": { "simpleText": "Compact Track" },
                            "shortBylineText": { "runs": [{ "text": "Chan B" }] },
                            "lengthText": { "simpleText": "4:00" },
                            "thumbnail": { "thumbnails": [{ "url": "https://i.ytimg.com/vi/compact003/mq.jpg" }] }
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        /**
         * YT-0293 Mix queue: valid `playlistPanelVideoRenderer` shape with 2 entries.
         * Entry 0: dQw4w9WgXcQ / "3:34" (214 s) / Rick Astley
         * Entry 1: Zi_XLOBDo_Y / "4:56" (296 s) / Michael Jackson
         */
        private val MIX_QUEUE_JSON = """
            {
              "contents": {
                "twoColumnWatchNextResults": {
                  "playlist": {
                    "playlist": {
                      "playlistId": "RDdQw4w9WgXcQ",
                      "contents": [
                        {
                          "playlistPanelVideoRenderer": {
                            "videoId": "dQw4w9WgXcQ",
                            "title": {"simpleText": "Rick Astley - Never Gonna Give You Up (Official Video)"},
                            "longBylineText": {"runs": [{"text": "Rick Astley"}]},
                            "lengthText": {"simpleText": "3:34"},
                            "thumbnail": {"thumbnails": [
                              {"url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", "width": 168, "height": 94},
                              {"url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=large", "width": 336, "height": 188}
                            ]}
                          }
                        },
                        {
                          "playlistPanelVideoRenderer": {
                            "videoId": "Zi_XLOBDo_Y",
                            "title": {"simpleText": "Michael Jackson - Billie Jean (Official Video)"},
                            "longBylineText": {"runs": [{"text": "Michael Jackson"}]},
                            "lengthText": {"simpleText": "4:56"},
                            "thumbnail": {"thumbnails": [
                              {"url": "https://i.ytimg.com/vi/Zi_XLOBDo_Y/hqdefault.jpg", "width": 168, "height": 94},
                              {"url": "https://i.ytimg.com/vi/Zi_XLOBDo_Y/hqdefault.jpg?sqp=large", "width": 336, "height": 188}
                            ]}
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        /**
         * YT-0293 Mix queue: one valid entry + one with a blank videoId.
         * The blank-videoId entry must be filtered out by `toMixTrack()`.
         */
        private val MIX_QUEUE_BLANK_VIDEOID_JSON = """
            {
              "contents": {
                "twoColumnWatchNextResults": {
                  "playlist": {
                    "playlist": {
                      "contents": [
                        {
                          "playlistPanelVideoRenderer": {
                            "videoId": "validTrack",
                            "title": {"simpleText": "Valid Track"},
                            "longBylineText": {"runs": [{"text": "Chan"}]},
                            "lengthText": {"simpleText": "3:00"},
                            "thumbnail": {"thumbnails": [{"url": "https://img/valid"}]}
                          }
                        },
                        {
                          "playlistPanelVideoRenderer": {
                            "videoId": "",
                            "title": {"simpleText": "Blank ID Track"},
                            "longBylineText": {"runs": [{"text": "Chan"}]},
                            "lengthText": {"simpleText": "3:00"},
                            "thumbnail": {"thumbnails": [{"url": "https://img/blank"}]}
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        // YT-0296 Mix continuation tokens — synthetic, scrubbed-shape strings.
        private const val MIX_TOKEN_PAGE_2 = "TOKEN_PAGE_2_SCRUBBED"
        private const val MIX_TOKEN_PAGE_3 = "TOKEN_PAGE_3_SCRUBBED"

        /**
         * YT-0296 initial Mix page with a `continuations[].nextContinuationData.continuation`
         * token at the same nesting level as `contents[]` (sibling on `playlist.playlist`).
         */
        private val MIX_INITIAL_WITH_TOKEN_JSON = """
            {
              "contents": {
                "twoColumnWatchNextResults": {
                  "playlist": {
                    "playlist": {
                      "playlistId": "RDdQw4w9WgXcQ",
                      "contents": [
                        {
                          "playlistPanelVideoRenderer": {
                            "videoId": "dQw4w9WgXcQ",
                            "title": {"simpleText": "Rick Astley - Never Gonna Give You Up"},
                            "longBylineText": {"runs": [{"text": "Rick Astley"}]},
                            "lengthText": {"simpleText": "3:34"},
                            "thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg"}]}
                          }
                        },
                        {
                          "playlistPanelVideoRenderer": {
                            "videoId": "Zi_XLOBDo_Y",
                            "title": {"simpleText": "Michael Jackson - Billie Jean"},
                            "longBylineText": {"runs": [{"text": "Michael Jackson"}]},
                            "lengthText": {"simpleText": "4:56"},
                            "thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/vi/Zi_XLOBDo_Y/hq.jpg"}]}
                          }
                        }
                      ],
                      "continuations": [
                        {
                          "nextContinuationData": {
                            "continuation": "$MIX_TOKEN_PAGE_2",
                            "clickTrackingParams": "SCRUBBED"
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        /**
         * YT-0296 continuation response: `continuationContents.playlistPanelContinuation`
         * wraps the same `contents[] + continuations[]` shape as the initial page.
         */
        private val MIX_CONTINUATION_PAGE_2_JSON = """
            {
              "continuationContents": {
                "playlistPanelContinuation": {
                  "contents": [
                    {
                      "playlistPanelVideoRenderer": {
                        "videoId": "5anLPw0Efmo",
                        "title": {"simpleText": "Michael Jackson - Smooth Criminal"},
                        "longBylineText": {"runs": [{"text": "Michael Jackson"}]},
                        "lengthText": {"simpleText": "9:26"},
                        "thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/vi/5anLPw0Efmo/hq.jpg"}]}
                      }
                    },
                    {
                      "playlistPanelVideoRenderer": {
                        "videoId": "h_D3VFfhvs4",
                        "title": {"simpleText": "Smash Mouth - All Star"},
                        "longBylineText": {"runs": [{"text": "Smash Mouth"}]},
                        "lengthText": {"simpleText": "3:24"},
                        "thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/vi/h_D3VFfhvs4/hq.jpg"}]}
                      }
                    }
                  ],
                  "continuations": [
                    {
                      "nextContinuationData": {
                        "continuation": "$MIX_TOKEN_PAGE_3",
                        "clickTrackingParams": "SCRUBBED"
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        /**
         * YT-0296 terminal continuation page: items present, but no `continuations[]`
         * block — the parser must surface `nextToken = null` so the consumer stops.
         */
        private val MIX_CONTINUATION_TERMINAL_JSON = """
            {
              "continuationContents": {
                "playlistPanelContinuation": {
                  "contents": [
                    {
                      "playlistPanelVideoRenderer": {
                        "videoId": "terminalTrack",
                        "title": {"simpleText": "Terminal Track"},
                        "longBylineText": {"runs": [{"text": "Some Channel"}]},
                        "lengthText": {"simpleText": "3:00"},
                        "thumbnail": {"thumbnails": [{"url": "https://img/terminal"}]}
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        /** lockupViewModel with no overlays/badges — durationSec should be 0. */
        private val LOCKUP_NO_DURATION_JSON = """
            {
              "contents": {
                "twoColumnWatchNextResults": {
                  "secondaryResults": {
                    "secondaryResults": {
                      "results": [
                        {
                          "lockupViewModel": {
                            "contentId": "nodur001",
                            "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                            "contentImage": {
                              "thumbnailViewModel": {
                                "image": { "sources": [{ "url": "https://i.ytimg.com/vi/nodur001/hq.jpg" }] }
                              }
                            },
                            "metadata": {
                              "lockupMetadataViewModel": {
                                "title": { "content": "No Duration Track" }
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
