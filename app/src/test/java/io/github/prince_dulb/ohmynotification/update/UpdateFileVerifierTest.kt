package io.github.prince_dulb.ohmynotification.update

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateFileVerifierTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = ByteArray(65_537) { (it % 251).toByte() }
    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Test fun verifiesEveryByteAndDoesNotReadPastTheStream() {
        val file = temp.newFile()
        UpdateFileVerifier.copyVerified(ByteArrayInputStream(bytes), file, bytes.size.toLong(), sha(bytes))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun wrongDigestShortAndOversizedInputsAreRejectedWithoutPartialFiles() {
        listOf(bytes.size.toLong() to "00".repeat(32), (bytes.size + 1L) to sha(bytes),
            (bytes.size - 1L) to sha(bytes)).forEach { (size, digest) ->
            val file = temp.newFile()
            assertThrows(IllegalArgumentException::class.java) {
                UpdateFileVerifier.copyVerified(ByteArrayInputStream(bytes), file, size, digest)
            }
            assertFalse(file.exists())
        }
    }

    @Test fun interruptedDownloadLeavesNoInstallablePartialCopy() {
        val file = temp.newFile()
        val stream = object : InputStream() {
            var reads = 0
            override fun read(): Int { if (++reads > 100) throw IOException("synthetic interruption"); return 1 }
        }
        assertThrows(IOException::class.java) { UpdateFileVerifier.copyVerified(stream, file, 10_000, "00".repeat(32)) }
        assertFalse(file.exists())
    }

    @Test fun invalidMetadataIsRejectedBeforeReadingTheInput() {
        val file = java.io.File(temp.root, "never-created.part")
        val stream = object : InputStream() { override fun read(): Int = error("must not read") }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateFileVerifier.copyVerified(stream, file, UpdatePolicy.MAX_APK_BYTES + 1, "00".repeat(32))
        }
        assertFalse(file.exists())
    }
}
