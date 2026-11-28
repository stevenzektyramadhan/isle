package io.github.stevenzektyramadhan.isle.data.extractor

import org.junit.Assert.*
import org.junit.Test

class YouTubeSearchTest {
    @Test fun parsesFirstNonLiveVideoFromYtInitialData() {
        val html = """
            <html><script>var ytInitialData = {
              "contents": {
                "twoColumnSearchResultsRenderer": {
                  "primaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "itemSectionRenderer": {
                            "contents": [
                              {
                                "videoRenderer": {
                                  "videoId": "live123",
                                  "title": {"runs": [{"text": "Lofi Radio Live"}]},
                                  "ownerText": {"runs": [{"text": "Lofi Girl"}]},
                                  "badges": [{"metadataBadgeRenderer": {"style": "BADGE_STYLE_TYPE_LIVE_NOW"}}]
                                }
                              },
                              {
                                "videoRenderer": {
                                  "videoId": "vid456",
                                  "title": {"runs": [{"text": "Midnight Beats"}]},
                                  "ownerText": {"runs": [{"text": "Chill Channel"}]},
                                  "lengthText": {"simpleText": "3:45"}
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
              }
            };</script></html>
        """.trimIndent()

        val track = YouTubeSearch.parseFirstVideo(html)
        assertNotNull(track)
        assertEquals("vid456", track?.id)
        assertEquals("Midnight Beats", track?.title)
        assertEquals("Chill Channel", track?.artist)
        assertEquals("https://www.youtube.com/watch?v=vid456", track?.originalUrl)
        assertEquals(225L, track?.durationSecs)
    }

    @Test fun parsesMmSsAndHhMmSsDurations() {
        assertEquals(225L, YouTubeSearch.parseDurationSecs("3:45"))
        assertEquals(3750L, YouTubeSearch.parseDurationSecs("1:02:30"))
        assertNull(YouTubeSearch.parseDurationSecs("LIVE"))
    }
}
