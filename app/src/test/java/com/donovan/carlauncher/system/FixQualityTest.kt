package com.donovan.carlauncher.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These rules exist because of a real symptom: the car marker jumping backwards a block
 * and then snapping forward again, every few seconds, on the road.
 *
 * The cause was not the GPS chip. The launcher listened to GPS and network together and
 * accepted whichever fix arrived last, so a cached Wi-Fi triangulation would routinely
 * overwrite a five-metre satellite fix with a five-hundred-metre guess at where the car
 * had been. The first test below is that bug, pinned.
 */
class FixQualityTest {

    private val second = 1_000_000_000L

    private fun gps(accuracy: Float, atSeconds: Long) =
        Fix("gps", accuracy, atSeconds * second)

    private fun network(accuracy: Float, atSeconds: Long) =
        Fix("network", accuracy, atSeconds * second)

    @Test
    fun `a vague network fix never displaces a fresh gps fix`() {
        // The backwards teleport, exactly: a 600m Wi-Fi guess arriving one second after
        // a 5m satellite fix.
        val current = gps(accuracy = 5f, atSeconds = 100)
        val candidate = network(accuracy = 600f, atSeconds = 101)
        assertFalse(FixQuality.isBetter(candidate, current))
    }

    @Test
    fun `even an accurate-looking network fix loses to fresh gps`() {
        // Network providers can report optimistic accuracy; the provider is the signal.
        val current = gps(accuracy = 12f, atSeconds = 100)
        assertFalse(FixQuality.isBetter(network(accuracy = 8f, atSeconds = 101), current))
    }

    @Test
    fun `gps always displaces network`() {
        val current = network(accuracy = 500f, atSeconds = 100)
        assertTrue(FixQuality.isBetter(gps(accuracy = 30f, atSeconds = 101), current))
    }

    @Test
    fun `network is accepted once gps has gone quiet`() {
        // Under cover or in a tunnel the satellite fix stops arriving, and a rough
        // position is better than a frozen one.
        val current = gps(accuracy = 5f, atSeconds = 100)
        val candidate = network(accuracy = 600f, atSeconds = 100 + 13)
        assertTrue(FixQuality.isBetter(candidate, current))
    }

    @Test
    fun `a fix from the past is rejected`() {
        // Android really does deliver these out of order.
        val current = gps(accuracy = 5f, atSeconds = 100)
        assertFalse(FixQuality.isBetter(gps(accuracy = 3f, atSeconds = 99), current))
    }

    @Test
    fun `the very first fix is always accepted`() {
        assertTrue(FixQuality.isBetter(network(accuracy = 2000f, atSeconds = 1), null))
    }

    @Test
    fun `a slightly vaguer gps fix is still accepted`() {
        // Accuracy wanders between fixes; refusing every wobble would freeze the marker.
        val current = gps(accuracy = 5f, atSeconds = 100)
        assertTrue(FixQuality.isBetter(gps(accuracy = 25f, atSeconds = 101), current))
    }

    @Test
    fun `a much vaguer gps fix is rejected`() {
        val current = gps(accuracy = 5f, atSeconds = 100)
        assertFalse(FixQuality.isBetter(gps(accuracy = 300f, atSeconds = 101), current))
    }

    @Test
    fun `a fix with no accuracy does not displace one that has it`() {
        val current = gps(accuracy = 8f, atSeconds = 100)
        val noAccuracy = Fix("gps", Float.MAX_VALUE, 101 * second)
        assertFalse(FixQuality.isBetter(noAccuracy, current))
    }

    @Test
    fun `network improving on network is accepted`() {
        val current = network(accuracy = 800f, atSeconds = 100)
        assertTrue(FixQuality.isBetter(network(accuracy = 120f, atSeconds = 101), current))
    }

    @Test
    fun `a long run of network fixes cannot drift the car while gps is healthy`() {
        // The on-road symptom was repeated, not a one-off, so simulate the interleave.
        var current = gps(accuracy = 5f, atSeconds = 0)
        var accepted = 0
        for (t in 1..60) {
            val candidate = if (t % 2 == 0) {
                network(accuracy = 700f, atSeconds = t.toLong())
            } else {
                gps(accuracy = 5f, atSeconds = t.toLong())
            }
            if (FixQuality.isBetter(candidate, current)) {
                current = candidate
                accepted++
            }
        }
        assertEquals30(accepted)
        assertTrue("only gps should have survived", current.isGps)
    }

    private fun assertEquals30(accepted: Int) {
        // Exactly the 30 gps fixes; none of the 30 network ones.
        assertTrue("expected 30 accepted, got $accepted", accepted == 30)
    }
}
