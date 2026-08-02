package org.soulstone.overwatch.scan

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkParserTest {

    @Test
    fun deflockParsesAlprNodesAndMetadata() = withServer { server ->
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "elements": [
                    {
                      "type": "node",
                      "id": 123,
                      "lat": 40.0,
                      "lon": -73.0,
                      "tags": {
                        "operator": "Example PD",
                        "manufacturer": "ExampleCam"
                      }
                    },
                    {"type": "way", "id": 456}
                  ]
                }
                """.trimIndent()
            )
        )
        val cacheDir = Files.createTempDirectory("overwatch-deflock-test").toFile()
        try {
            val client = DeflockClient(
                cacheDir = cacheDir,
                endpoints = listOf(server.url("/api/interpreter").toString())
            )

            val result = runBlocking { client.fetchAround(40.0, -73.0) }

            assertTrue(result is DeflockClient.FetchResult.Success)
            val point = (result as DeflockClient.FetchResult.Success).points.single()
            assertEquals(123L, point.id)
            assertEquals("Example PD", point.operator)
            assertEquals("ExampleCam", point.manufacturer)
            assertEquals("POST", server.takeRequest().method)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun citizenParsesTrendingIdsAndIncidentDetails() = withServer { server ->
        server.enqueue(
            MockResponse().setBody("""{"results":["a-1","","b-2"]}""")
        )
        server.enqueue(
            MockResponse().setBody(
                """{"title":"Police activity","level":3,"ll":[40.1,-73.1],"ts":1234,"police":"Precinct 7"}"""
            )
        )
        val client = CitizenClient(server.url("/api/incident").toString())

        val ids = runBlocking { client.trendingNear(40.0, -73.0) }
        val incident = runBlocking { client.fetchIncident("a-1") }

        assertTrue(ids is CitizenClient.TrendingResult.Success)
        assertEquals(listOf("a-1", "b-2"), (ids as CitizenClient.TrendingResult.Success).ids)
        assertEquals("Police activity", incident?.title)
        assertEquals(3, incident?.level)
        assertEquals("Precinct 7", incident?.precinct)
        assertEquals("/api/incident/trending", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/api/incident/a-1", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun wazeKeepsPoliceAlertsAndParsesEnvelopeFields() = withServer { server ->
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "alerts": [
                      {
                        "alert_id": "police-1",
                        "type": "POLICE",
                        "subtype": "HIDDEN",
                        "latitude": 40.2,
                        "longitude": -73.2,
                        "alert_confidence": 4,
                        "alert_reliability": 8,
                        "publish_datetime_utc": "2026-08-02T06:00:00Z"
                      },
                      {
                        "alert_id": "road-1",
                        "type": "ROAD_CLOSED",
                        "latitude": 40.3,
                        "longitude": -73.3
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        )
        val client = WazeClient(
            appToken = { "test-token" },
            baseUrl = server.url("/waze/alerts-and-jams").toString()
        )

        val result = runBlocking { client.fetchPoliceNear(40.0, -73.0, 200f) }

        assertTrue(result is WazeClient.FetchResult.Success)
        val alert = (result as WazeClient.FetchResult.Success).alerts.single()
        assertEquals("police-1", alert.uuid)
        assertEquals("HIDDEN", alert.subtype)
        assertEquals(4, alert.confidence)
        assertEquals(8, alert.reliability)
        assertEquals("test-token", server.takeRequest().getHeader("X-App-Token"))
    }

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }
}
