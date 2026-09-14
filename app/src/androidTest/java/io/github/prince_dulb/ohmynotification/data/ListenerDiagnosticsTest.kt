package io.github.prince_dulb.ohmynotification.data

import io.github.prince_dulb.ohmynotification.capture.ListenerDiagnostics
import io.github.prince_dulb.ohmynotification.capture.ListenerDiagnosticEvent
import io.github.prince_dulb.ohmynotification.capture.ListenerDiagnosticRecord

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ListenerDiagnosticsTest {
    @Test fun basicExportIsAlwaysAvailableAndBuildFlagOnlyControlsDetails() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator required" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detailed = context.resources.getBoolean(io.github.prince_dulb.ohmynotification.R.bool.omn_listener_diagnostics)
        assertTrue(ListenerDiagnostics.enabled)
        val beforeCounters = ListenerDiagnostics.counterSnapshot()
        ListenerDiagnostics.count(ListenerDiagnostics.Counter.POST_CALLBACK)
        if (!detailed) assertEquals(beforeCounters, ListenerDiagnostics.counterSnapshot())
        val marker = android.os.Process.myPid()
        ListenerDiagnostics.record(ListenerDiagnosticEvent.UI_START, marker)
        ListenerDiagnostics.record(ListenerDiagnosticEvent.STORAGE_READY, marker)
        val report = ListenerDiagnostics.export()
        assertNotNull(report)
        assertTrue(report!!.startsWith("OMN-LISTENER-DIAGNOSTICS 1\n"))
        assertTrue(report.contains(if (detailed) "mode=detailed" else "mode=basic"))
        assertEquals(detailed, report.contains("Current-process counters:"))
        assertTrue(report.contains("\nEXPORT "))
        assertTrue(report.length < 70_000)
        val lines = ListenerDiagnosticRecord.retainedLines(report)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.size <= 256)
        assertTrue(lines.any { it.startsWith("STORAGE_READY ") && it.endsWith(" $marker") })
        assertEquals(detailed, lines.any { it.startsWith("UI_START ") && it.endsWith(" $marker") })
    }
}
