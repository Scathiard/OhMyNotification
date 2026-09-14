package io.github.prince_dulb.ohmynotification

import android.app.Application
import io.github.prince_dulb.ohmynotification.capture.ListenerRebindController
import io.github.prince_dulb.ohmynotification.capture.NotificationSnapshotFactory
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStore
import io.github.prince_dulb.ohmynotification.capture.SourceLabelResolver
import io.github.prince_dulb.ohmynotification.capture.StatusNotificationController
import io.github.prince_dulb.ohmynotification.core.normalize.HmacSha256Fingerprinter
import io.github.prince_dulb.ohmynotification.core.normalize.NormalizationLimits
import io.github.prince_dulb.ohmynotification.core.normalize.NotificationNormalizer
import io.github.prince_dulb.ohmynotification.data.InstallKeyProvider
import io.github.prince_dulb.ohmynotification.data.AppSettingsStore
import io.github.prince_dulb.ohmynotification.data.InboxViewPreferencesStore
import io.github.prince_dulb.ohmynotification.data.MonitoringPolicyStore
import io.github.prince_dulb.ohmynotification.data.NotificationRepository
import io.github.prince_dulb.ohmynotification.data.OmnDatabase
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OmnApplication : Application() {
    lateinit var graph: OmnAppGraph
        private set
    private val ready = MutableStateFlow(false)
    val storageReady = ready.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        io.github.prince_dulb.ohmynotification.capture.ListenerDiagnostics.initialize(this)
        graph = OmnAppGraph(this)
        if (io.github.prince_dulb.ohmynotification.capture.ListenerDiagnostics.enabled) {
            io.github.prince_dulb.ohmynotification.capture.ListenerRuntimeState.addListener {
                io.github.prince_dulb.ohmynotification.capture.ListenerDiagnostics.record(
                    io.github.prince_dulb.ohmynotification.capture.ListenerDiagnosticEvent.HEALTH,
                )
            }
        }
        runBlocking { retryStorage() }

    }

    suspend fun retryStorage(): Boolean = withContext(Dispatchers.IO) {
        try {
            graph.repository.reconcilePolicyFromDatabase()
            ready.value = true
            io.github.prince_dulb.ohmynotification.capture.ListenerDiagnostics.record(
                io.github.prince_dulb.ohmynotification.capture.ListenerDiagnosticEvent.STORAGE_READY,
            )
            true
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            reportStorageFailure()
            false
        }
    }

    fun reportStorageFailure() {
        io.github.prince_dulb.ohmynotification.capture.ListenerDiagnostics.record(
            io.github.prince_dulb.ohmynotification.capture.ListenerDiagnosticEvent.STORAGE_FAILED,
        )
        ready.value = false
        io.github.prince_dulb.ohmynotification.capture.ListenerRuntimeState.recordFailure()
        io.github.prince_dulb.ohmynotification.capture.ListenerRuntimeState.setProcessingOperational(false)
    }
}

class OmnAppGraph(application: Application) {
    init { io.github.prince_dulb.ohmynotification.capture.ListenerRuntimeState.initialize(application) }
    val runtimeSessionId: String = UUID.randomUUID().toString()
    val serialScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    val policyStore = MonitoringPolicyStore(application.packageName)
    val appSettingsStore = AppSettingsStore(application, policyStore)
    val inboxViewPreferencesStore = InboxViewPreferencesStore(application)
    val runtimeActionStore = RuntimeActionStore()
    val sourceLabelResolver = SourceLabelResolver(application.packageManager, application.packageName)
    val statusNotificationController = StatusNotificationController(application)
    internal val listenerRebindController = ListenerRebindController(application)
    val snapshotFactory = NotificationSnapshotFactory(
        ownPackageName = application.packageName,
        runtimeSessionId = runtimeSessionId,
    )
    val repository = NotificationRepository(
        database = OmnDatabase.get(application),
        normalizer = NotificationNormalizer(
            limits = NormalizationLimits(),
            fingerprinter = HmacSha256Fingerprinter(
                InstallKeyProvider.contentFingerprintKey(application),
            ),
        ),
        runtimeActionStore = runtimeActionStore,
        policyStore = policyStore,
        onStorageFailure = { (application as OmnApplication).reportStorageFailure() },
    )

}
