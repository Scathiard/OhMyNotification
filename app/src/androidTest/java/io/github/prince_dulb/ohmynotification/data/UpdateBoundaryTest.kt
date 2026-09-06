package io.github.prince_dulb.ohmynotification.data

import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.prince_dulb.ohmynotification.update.AppUpdater
import io.github.prince_dulb.ohmynotification.update.UpdatePhase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateBoundaryTest {
    @Test fun updaterConstructionDoesNotCheckOrDownload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(UpdatePhase.IDLE, AppUpdater(context).state.value.phase)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        assertTrue(packageInfo.signingInfo!!.apkContentsSigners.isNotEmpty())
    }

    @Test fun updateProviderNeverExposesNotificationDatabaseOrPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "${context.packageName}.updates"
        val provider = context.packageManager.resolveContentProvider(authority, 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val updateUri = FileProvider.getUriForFile(context, authority, File(context.filesDir, "updates/synthetic.apk"))
        assertEquals("content", updateUri.scheme)
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, authority, context.getDatabasePath("oh-my-notification.db"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, authority, File(context.filesDir, "../shared_prefs/omn-inbox-view.xml"))
        }
    }
}
