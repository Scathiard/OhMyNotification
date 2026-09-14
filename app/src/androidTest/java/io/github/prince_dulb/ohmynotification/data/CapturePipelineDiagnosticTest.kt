package io.github.prince_dulb.ohmynotification.data

import android.os.Bundle
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.prince_dulb.ohmynotification.OmnApplication
import io.github.prince_dulb.ohmynotification.capture.*
import io.github.prince_dulb.ohmynotification.core.model.ObservedCallbackKind
import io.github.prince_dulb.ohmynotification.core.normalize.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit device diagnostic: no production writes, no notification content in output. */
@RunWith(AndroidJUnit4::class)
class CapturePipelineDiagnosticTest {
    @Test fun reportsCaptureStagesWithoutExportingContent() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as OmnApplication
        val graph = app.graph
        val field = OmnNotificationListenerService::class.java.getDeclaredField("activeService")
            .apply { isAccessible = true }
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var service: OmnNotificationListenerService? = null
        while (service == null && SystemClock.elapsedRealtime() < deadline) {
            service = (field.get(null) as java.lang.ref.WeakReference<*>).get() as? OmnNotificationListenerService
            if (service == null) Thread.sleep(100)
        }
        assertNotNull("Listener did not connect", service)
        val results = Bundle()
        results.putBoolean("storageReady", app.storageReady.value)
        results.putBoolean("processingOperational", ListenerRuntimeState.isProcessingOperational())
        results.putBoolean("knownGap", ListenerRuntimeState.hasKnownGap())
        results.putLong("productionRows", graph.repository.currentItemCount())
        results.putInt("productionActions", graph.runtimeActionStore.itemIds.value.size)
        val policy = graph.policyStore.snapshot()
        results.putInt("excludedSourceCount", policy.userExcludedPackages.size)
        results.putInt("recordedTypeCount", policy.recordedNotificationTypes.size)
        val source = InstrumentationRegistry.getArguments().getString("sourcePackage")
        if (source != null) {
            results.putBoolean("requestedSourceExcluded", policy.excludes(source))
            val exclusions = graph.repository.excludedSources.first()
            val entry = exclusions.firstOrNull { it.sourcePackage == source }
            if (entry != null) {
                results.putLong("requestedSourceExclusionSavedAtEpochMillis", entry.excludedAtEpochMillis)
                results.putInt("sourcesWithSameExclusionTimestamp", exclusions.count {
                    it.excludedAtEpochMillis == entry.excludedAtEpochMillis
                })
            }
        }
        val samples = service!!.activeNotifications.filter { source == null || it.packageName == source }
        results.putInt("activeSamples", samples.size)
        val db = Room.inMemoryDatabaseBuilder(context, OmnDatabase::class.java).build()
        val repository = NotificationRepository(db, NotificationNormalizer(NormalizationLimits(),
            HmacSha256Fingerprinter(ByteArray(32) { 1 })), RuntimeActionStore(), MonitoringPolicyStore(context.packageName))
        var captured = 0
        var committed = 0
        val exceptions = linkedSetOf<String>()
        try {
            for (sample in samples) {
                try {
                    val snapshot = graph.snapshotFactory.capture(sample, ObservedCallbackKind.RECOVERY_SNAPSHOT,
                        "diagnostic-only", policy)
                    if (snapshot is SnapshotResult.Captured) {
                        captured++
                        if (repository.processCaptured(snapshot, "Diagnostic source") != null) committed++
                    } else if (snapshot is SnapshotResult.Excluded) {
                        results.putInt("excluded_${snapshot.reason}", results.getInt("excluded_${snapshot.reason}") + 1)
                    }
                } catch (failure: Exception) {
                    exceptions += failure.javaClass.name + ":" + failure.stackTrace.take(5).joinToString("|") {
                        "${it.className}.${it.methodName}:${it.lineNumber}"
                    }
                }
            }
            results.putInt("capturedSamples", captured)
            results.putInt("isolatedCommittedSamples", committed)
            results.putString("exceptionLocationsOnly", exceptions.joinToString(";"))
            instrumentation.sendStatus(0, results)
        } finally { db.close() }
    }
}
