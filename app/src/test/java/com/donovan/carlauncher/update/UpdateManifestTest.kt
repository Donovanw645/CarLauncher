package com.donovan.carlauncher.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The manifest decides what gets installed over the top of this app, so its two safety
 * checks - https only, and a real sha256 - are the difference between an update channel
 * and an arbitrary-code-execution channel. They are worth pinning down in tests.
 */
class UpdateManifestTest {

    private val sha = "b48f0d16deb583aec52230f9f0dcd2251ee68df754b9dab138a156f81ae2ab27"

    private fun json(
        url: String = "https://github.com/o/r/releases/download/v1.2/app.apk",
        hash: String = sha,
        extra: String = "",
    ) = """
    {"versionCode":3,"versionName":"1.2","apkUrl":"$url","sha256":"$hash",
     "sizeBytes":15560290,"minSdk":24,"notes":"Test"$extra}
    """.trimIndent()

    @Test
    fun `a good manifest parses`() {
        val m = UpdateManifest.parse(json())
        assertEquals(3L, m.versionCode)
        assertEquals("1.2", m.versionName)
        assertEquals(sha, m.sha256)
        assertEquals(15560290L, m.sizeBytes)
        assertEquals(24, m.minSdk)
    }

    @Test
    fun `an uppercase hash is normalised`() {
        assertEquals(sha, UpdateManifest.parse(json(hash = sha.uppercase())).sha256)
    }

    @Test
    fun `plain http is refused`() {
        try {
            UpdateManifest.parse(json(url = "http://example.com/app.apk"))
            fail("cleartext download URLs must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun `a missing hash is refused`() {
        try {
            UpdateManifest.parse(json(hash = ""))
            fail("an unverifiable APK must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sha256"))
        }
    }

    @Test
    fun `a truncated hash is refused`() {
        try {
            UpdateManifest.parse(json(hash = "abc123"))
            fail("a short hash must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sha256"))
        }
    }

    @Test
    fun `a hash with non-hex characters is refused`() {
        val bad = "z".repeat(64)
        try {
            UpdateManifest.parse(json(hash = bad))
            fail("a non-hex hash must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sha256"))
        }
    }

    @Test
    fun `unknown fields are ignored so the channel can add some later`() {
        val m = UpdateManifest.parse(json(extra = ""","channel":"beta","future":{"a":1}"""))
        assertEquals(3L, m.versionCode)
    }

    @Test
    fun `pending states are the ones that light the nav rail badge`() {
        val m = UpdateManifest.parse(json())
        assertTrue(UpdateState.Available(m).isPending)
        assertTrue(UpdateState.Downloading(m, 1, 2).isPending)
        assertTrue(UpdateState.Ready(m).isPending)
        assertTrue(!UpdateState.Idle.isPending)
        assertTrue(!UpdateState.Checking.isPending)
        assertTrue(!UpdateState.UpToDate(0).isPending)
        assertTrue(!UpdateState.Failed("x").isPending)
    }

    @Test
    fun `download fraction is clamped`() {
        val m = UpdateManifest.parse(json())
        assertEquals(0.5f, UpdateState.Downloading(m, 50, 100).fraction, 1e-6f)
        assertEquals(0f, UpdateState.Downloading(m, 10, 0).fraction, 1e-6f)
        assertEquals(1f, UpdateState.Downloading(m, 200, 100).fraction, 1e-6f)
    }
}
