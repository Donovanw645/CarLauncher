package com.donovan.carlauncher.traffic

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * District selection is what keeps the app from downloading eleven megabytes of feeds
 * to find the cameras on one commute. Getting it wrong is quiet in a bad way: too
 * narrow and hazards simply never appear.
 */
class CaltransDistrictsTest {

    private val miles = 1609.344

    @Test
    fun `Sacramento resolves to district 3`() {
        val d = CaltransDistricts.near(38.5816, -121.4944, 25 * miles)
        assertTrue("expected D3 in $d", 3 in d)
    }

    @Test
    fun `Los Angeles spans several overlapping districts`() {
        val d = CaltransDistricts.near(34.0522, -118.2437, 25 * miles)
        assertTrue("expected D7 in $d", 7 in d)
        assertTrue("LA sits where districts overlap, got $d", d.size > 1)
    }

    @Test
    fun `San Diego resolves to district 11`() {
        assertTrue(11 in CaltransDistricts.near(32.7157, -117.1611, 25 * miles))
    }

    @Test
    fun `a bigger radius never finds fewer districts`() {
        val near = CaltransDistricts.near(38.5816, -121.4944, 10 * miles)
        val far = CaltransDistricts.near(38.5816, -121.4944, 100 * miles)
        assertTrue(far.containsAll(near))
        assertTrue(far.size >= near.size)
    }

    @Test
    fun `a point just outside a district is still found once padded`() {
        // Just north of D3's top edge. Without radius padding this returns nothing from
        // D3 and a driver at the boundary silently loses every nearby hazard.
        val edge = CaltransDistricts.all.first { it.n == 3 }
        val d = CaltransDistricts.near(edge.latMax + 0.15, -121.0, 25 * miles)
        assertTrue("expected D3 just past its edge, got $d", 3 in d)
    }

    @Test
    fun `somewhere far outside California matches nothing`() {
        assertTrue(CaltransDistricts.near(51.5074, -0.1278, 25 * miles).isEmpty())
    }

    @Test
    fun `every district box is well formed`() {
        for (b in CaltransDistricts.all) {
            assertTrue("D${b.n} lat range", b.latMin < b.latMax)
            assertTrue("D${b.n} lon range", b.lonMin < b.lonMax)
            assertTrue("D${b.n} is in California", b.latMin > 32.0 && b.latMax < 42.5)
            assertTrue("D${b.n} is west of Greenwich", b.lonMax < 0)
        }
    }
}
