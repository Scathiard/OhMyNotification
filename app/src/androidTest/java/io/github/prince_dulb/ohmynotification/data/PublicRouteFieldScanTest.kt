package io.github.prince_dulb.ohmynotification.data

import android.app.Notification
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.prince_dulb.ohmynotification.testsource.BoundedPublicRouteScan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicRouteFieldScanTest {
    private val target = "bilibili://video/99123456789?omn=synthetic"
    private fun scan(extras: Bundle): Bundle {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        return BoundedPublicRouteScan.compare(Notification().apply { this.extras = extras }, target)
    }
    @Test fun publicStringAndUriAreDetected() {
        for (asUri in listOf(false, true)) {
            val result = scan(Bundle().apply {
                if (asUri) putParcelable("synthetic", Uri.parse(target)) else putString("synthetic", target)
            })
            assertEquals(1, result.getInt("exactTargetValues"))
            assertTrue(result.getBoolean("completeWithinDeclaredFields"))
        }
    }
    @Test fun identifierAndSimilarAddressAreNotExactMatches() {
        val result = scan(Bundle().apply {
            putString("display_id", "99123456789")
            putString("different", target + "0")
        })
        assertEquals(0, result.getInt("exactTargetValues"))
        assertEquals(1, result.getInt("identifierOnlyValues"))
        assertEquals(1, result.getInt("embeddedTargetValues"))
    }
    @Test fun nestedIntentAndEscapedJsonAreDetected() {
        val result = scan(Bundle().apply {
            putBundle("nested", Bundle().apply { putParcelable("intent", Intent(Intent.ACTION_VIEW, Uri.parse(target))) })
            putString("json", "{\"route\":\"" + target.replace("/", "\\/") + "\"}")
        })
        assertEquals(2, result.getInt("exactTargetValues"))
        assertTrue(result.getBoolean("completeWithinDeclaredFields"))
    }
    @Test fun limitsAndUnsupportedValuesAreNotReportedAsComplete() {
        for (value in listOf("x".repeat(65537), "[".repeat(100) + "0" + "]".repeat(100))) {
            val result = scan(Bundle().apply { putString("bounded", value) })
            assertTrue(result.getInt("limitHits") > 0)
            assertFalse(result.getBoolean("completeWithinDeclaredFields"))
        }
        val binary = scan(Bundle().apply { putByteArray("opaque", byteArrayOf(1, 2)) })
        assertEquals(1, binary.getInt("unsupportedValues"))
        assertFalse(binary.getBoolean("completeWithinDeclaredFields"))
    }
    @Test fun outputContainsOnlyFixedCountersAndBooleans() {
        val result = scan(Bundle().apply { putString("PRIVATE_FIELD_NAME", target) })
        assertFalse(result.keySet().contains("PRIVATE_FIELD_NAME"))
        @Suppress("DEPRECATION")
        for (key in result.keySet()) assertTrue(result.get(key) is Int || result.get(key) is Boolean)
    }
    @Test fun unsupportedTypesRemainIncompleteAndCountersConserve() {
        val cases = listOf(
            Bundle().apply { putParcelable("private", android.graphics.drawable.Icon.createWithResource("android", android.R.drawable.ic_menu_view)) } to "unsupportedIconResource",
            Bundle().apply { putParcelable("private", android.graphics.drawable.Icon.createWithContentUri("content://omn.synthetic/not-read")) } to "unsupportedIconUri",
            Bundle().apply { putParcelable("private", android.graphics.drawable.Icon.createWithData(byteArrayOf(0), 0, 1)) } to "unsupportedIconData",
            Bundle().apply { putByteArray("private", byteArrayOf(0)) } to "unsupportedBytes",
            Bundle().apply { putIntArray("private", intArrayOf(0)) } to "unsupportedPrimitiveArray",
            Bundle().apply { putParcelable("private", android.widget.RemoteViews("android", android.R.layout.simple_list_item_1)) } to "unsupportedRemoteViews",
            Bundle().apply { putParcelable("private", android.graphics.Rect(0, 0, 1, 1)) } to "unsupportedFrameworkOther",
            Bundle().apply { putParcelable("private", android.content.pm.ApplicationInfo()) } to "unsupportedApplicationInfo"
        )
        for ((input, category) in cases) {
            val result = scan(input)
            assertEquals(category, 1, result.getInt(category))
            assertEquals(1, result.getInt("unsupportedValues"))
            assertEquals(1, result.keySet().filter { it.startsWith("unsupported") && it != "unsupportedValues" }.sumOf { result.getInt(it) })
            assertEquals(0, result.getInt("readErrors"))
            assertEquals(0, result.getInt("exactTargetValues"))
            assertFalse(result.getBoolean("completeWithinDeclaredFields"))
        }
    }
}
