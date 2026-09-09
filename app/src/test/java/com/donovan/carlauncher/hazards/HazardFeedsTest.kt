package com.donovan.carlauncher.hazards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hazard feeds are third-party and change without notice, and this app is the
 * device's home screen - a parser that throws takes the launcher down with it. These
 * fixtures are trimmed from real responses captured from the live services.
 */
class HazardFeedsTest {

    // ------------------------------------------------------------- lane closures

    private fun lcs(
        closureId: String = "C5BB",
        typeOfClosure: String = "Lane",
        lanesClosed: String = "1",
        startEpoch: String = "1000",
        endEpoch: String = "2000",
    ) = """
    {"data":[{"lcs":{
      "location":{"begin":{
        "beginLatitude":"38.509395","beginLongitude":"-121.521802",
        "beginRoute":"I-5","beginLocationName":"Gloria Dr","beginNearbyPlace":"Sacramento"}},
      "closure":{
        "closureID":"$closureId","logNumber":"1",
        "closureTimestamp":{"closureStartEpoch":"$startEpoch","closureEndEpoch":"$endEpoch"},
        "typeOfClosure":"$typeOfClosure","typeOfWork":"Pavement Work",
        "lanesClosed":"$lanesClosed","estimatedDelay":"10"}
    }}]}
    """.trimIndent()

    @Test
    fun `lane closure is parsed with position and detail`() {
        val out = HazardFeeds.parseLaneClosures(lcs(), nowEpoch = 1500)
        assertEquals(1, out.size)
        val h = out[0]
        assertEquals(HazardKind.LANE_CLOSURE, h.kind)
        assertEquals(38.509395, h.lat, 1e-6)
        assertEquals(-121.521802, h.lon, 1e-6)
        assertEquals("I-5", h.route)
        assertTrue(h.title.contains("I-5"))
        assertTrue(h.detail.contains("Pavement Work"))
        assertTrue(h.detail.contains("10 min delay"))
    }

    @Test
    fun `full closure outranks a lane closure`() {
        val out = HazardFeeds.parseLaneClosures(
            lcs(typeOfClosure = "Full", lanesClosed = "All"), nowEpoch = 1500,
        )
        assertEquals(HazardKind.FULL_CLOSURE, out.single().kind)
        // A road that is gone must sort ahead of one that is merely narrowed.
        assertTrue(HazardKind.FULL_CLOSURE.ordinal < HazardKind.LANE_CLOSURE.ordinal)
    }

    @Test
    fun `closures that already ended are dropped`() {
        val out = HazardFeeds.parseLaneClosures(lcs(endEpoch = "2000"), nowEpoch = 9999)
        assertTrue("an expired closure is not a hazard", out.isEmpty())
    }

    @Test
    fun `closures far in the future are dropped`() {
        val out = HazardFeeds.parseLaneClosures(
            lcs(startEpoch = "999999", endEpoch = "1000000"), nowEpoch = 1000,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a closure with no coordinates is skipped rather than crashing`() {
        val body = """{"data":[{"lcs":{"location":{"begin":{}},"closure":{"closureID":"X"}}}]}"""
        assertTrue(HazardFeeds.parseLaneClosures(body, 1).isEmpty())
    }

    // ------------------------------------------------------------- chain control

    private fun cc(status: String, description: String) = """
    {"data":[{"cc":{
      "index":"3-ALP-89-23.6-N-237",
      "location":{"latitude":"38.787944","longitude":"-119.939680",
                  "route":"SR-89","locationName":"Luther Pass"},
      "inService":"true",
      "statusData":{"status":"$status","statusDescription":"$description"}
    }}]}
    """.trimIndent()

    @Test
    fun `chain control in force is reported`() {
        val out = HazardFeeds.parseChainControl(cc("R-2", "Chains required on all vehicles"))
        val h = out.single()
        assertEquals(HazardKind.CHAIN_CONTROL, h.kind)
        assertTrue(h.title.contains("Luther Pass"))
        assertEquals("Chains required on all vehicles", h.detail)
    }

    @Test
    fun `R-0 means no controls and must not be shown`() {
        // The feed reports every checkpoint in the district, and almost all of them are
        // R-0. Treating those as hazards would bury the real ones.
        val out = HazardFeeds.parseChainControl(
            cc("R-0", "No chain controls are in effect at this time.")
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `out of service checkpoints are ignored`() {
        val body = cc("R-1", "Chains required").replace("\"inService\":\"true\"", "\"inService\":\"false\"")
        assertTrue(HazardFeeds.parseChainControl(body).isEmpty())
    }

    // ---------------------------------------------------------------------- CHP

    private val chpXml = """
    <State>
     <Center ID="SAHB">
      <Dispatch ID="SACC">
       <Log ID="260908SA0530">
        <LogTime>"Sep  8 2026  1:22PM"</LogTime>
        <LogType>"1183-Trfc Collision-Unkn Inj"</LogType>
        <Location>"Us50 E / 34th St"</Location>
        <LocationDesc>"EB JWO"</LocationDesc>
        <Area>"South Sac"</Area>
        <LATLON>"38559948:121466568"</LATLON>
        <LogDetails>
          <details><IncidentDetail>"[2] DEBRI IN RDWY "</IncidentDetail></details>
          <details><IncidentDetail>"[1] BLU ACUR MDX VS WHI TOYT SD "</IncidentDetail></details>
        </LogDetails>
       </Log>
       <Log ID="260908SA0777">
        <LogType>"1125-Traffic Hazard"</LogType>
        <Location>"I5 N / Richards"</Location>
        <LATLON>"38600000:121500000"</LATLON>
        <LogDetails></LogDetails>
       </Log>
      </Dispatch>
     </Center>
    </State>
    """.trimIndent()

    @Test
    fun `CHP packed coordinates decode to California`() {
        val out = HazardFeeds.parseChp(chpXml)
        assertEquals(2, out.size)
        val first = out.first()
        assertEquals(38.559948, first.lat, 1e-6)
        // The feed drops the minus sign; California is entirely west of Greenwich.
        assertEquals(-121.466568, first.lon, 1e-6)
        assertTrue("longitude must be negative", first.lon < 0)
    }

    @Test
    fun `CHP radio codes are stripped from the headline`() {
        val out = HazardFeeds.parseChp(chpXml)
        assertTrue(out[0].title.startsWith("Trfc Collision"))
        assertTrue("the numeric code should be gone", !out[0].title.startsWith("1183"))
    }

    @Test
    fun `collisions and hazards get different kinds`() {
        val out = HazardFeeds.parseChp(chpXml)
        assertEquals(HazardKind.COLLISION, out[0].kind)
        assertEquals(HazardKind.HAZARD, out[1].kind)
    }

    @Test
    fun `CHP detail carries the original call, not the latest update`() {
        // The narrative arrives newest-first; the last line is what was first reported,
        // which is the bit that says what actually happened.
        val out = HazardFeeds.parseChp(chpXml)
        assertTrue(out[0].detail.contains("BLU ACUR MDX"))
    }

    @Test
    fun `malformed CHP records are skipped without throwing`() {
        val broken = """
        <State><Log ID="a"><LATLON>"nonsense"</LATLON></Log>
        <Log ID="b"><LATLON>"0:0"</LATLON></Log>
        <Log ID="c"><LogType>"1125-Traffic Hazard"</LogType></Log></State>
        """.trimIndent()
        assertTrue(HazardFeeds.parseChp(broken).isEmpty())
    }

    @Test
    fun `empty and junk bodies do not throw`() {
        assertTrue(HazardFeeds.parseChp("").isEmpty())
        assertTrue(HazardFeeds.parseChp("<State></State>").isEmpty())
        assertNotNull(HazardFeeds.parseLaneClosures("""{"data":[]}""", 1))
        assertNotNull(HazardFeeds.parseChainControl("""{"data":[]}"""))
    }

    @Test
    fun `feed urls are zero padded the way Caltrans serves them`() {
        assertTrue(HazardFeeds.lcsUrl(3).endsWith("/d3/lcs/lcsStatusD03.json"))
        assertTrue(HazardFeeds.lcsUrl(11).endsWith("/d11/lcs/lcsStatusD11.json"))
        assertTrue(HazardFeeds.ccUrl(4).endsWith("/d4/cc/ccStatusD04.json"))
        assertNull(null)
    }
}
