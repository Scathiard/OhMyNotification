package io.github.prince_dulb.ohmynotification.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun numericVersionsAndFinalAfterRc() {
        assertTrue(ReleaseVersion.parse("1.10.0")!! > ReleaseVersion.parse("1.9.9")!!)
        assertTrue(ReleaseVersion.parse("1.1.0")!! > ReleaseVersion.parse("1.1.0-rc1")!!)
        assertEquals(ReleaseVersion.parse("1.1.0"), ReleaseVersion.parse("v1.1.0"))
        assertNull(ReleaseVersion.parse("../1.1.0"))
        assertNull(ReleaseVersion.parse("1.1.0-preview"))
    }

    @Test fun sidecarMustBindDigestToExactApk() {
        val digest = "ab".repeat(32)
        assertEquals(digest, UpdatePolicy.checksum("$digest  OMN.apk\n", "OMN.apk"))
        assertEquals(digest, UpdatePolicy.checksum("${digest.uppercase()} *OMN.apk", "OMN.apk"))
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.checksum("$digest  other.apk", "OMN.apk") }
        assertThrows(IllegalStateException::class.java) { UpdatePolicy.checksum("1234 OMN.apk", "OMN.apk") }
        assertThrows(IllegalStateException::class.java) { UpdatePolicy.checksum("$digest OMN.apk\n$digest other.apk", "OMN.apk") }
    }

    @Test fun downloadMustBelongToExactOfficialRelease() {
        val url = "https://github.com/prince-dulb/OhMyNotification/releases/download/v1.1.0/OMN.apk"
        UpdatePolicy.requireAssetUrl(url, "v1.1.0", "OMN.apk")
        listOf(url.replace("https:", "http:"), url.replace("github.com", "github.com.attacker.test"),
            url.replace("prince-dulb", "other"), "$url?redirect=1", url.replace("v1.1.0", "v9.0.0")).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.requireAssetUrl(bad, "v1.1.0", "OMN.apk") }
        }
    }

    @Test fun rejectWrongPackageOldVersionAndWrongOrHistoricalSigner() {
        fun verify(pkg: String = "example.omn", code: Long = 15, signer: Set<String> = setOf("release")) =
            UpdatePolicy.verifyIdentity("example.omn", pkg, 14, code, setOf("release"), signer)
        verify()
        assertThrows(IllegalArgumentException::class.java) { verify(pkg = "other.app") }
        assertThrows(IllegalArgumentException::class.java) { verify(code = 14) }
        assertThrows(IllegalArgumentException::class.java) { verify(code = 13) }
        assertThrows(IllegalArgumentException::class.java) { verify(signer = setOf("old-debug")) }
        assertThrows(IllegalArgumentException::class.java) { verify(signer = emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { verify(signer = setOf("release", "extra")) }
    }
}
