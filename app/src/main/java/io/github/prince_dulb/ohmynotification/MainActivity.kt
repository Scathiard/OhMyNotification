package io.github.prince_dulb.ohmynotification

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import android.os.PowerManager
import android.widget.Toast
import io.github.prince_dulb.ohmynotification.ui.OnboardingScreen
import io.github.prince_dulb.ohmynotification.ui.UpdateCard
import io.github.prince_dulb.ohmynotification.ui.BatchAction
import io.github.prince_dulb.ohmynotification.ui.BatchResult
import io.github.prince_dulb.ohmynotification.data.AppUserKey
import io.github.prince_dulb.ohmynotification.data.InboxViewFilter
import io.github.prince_dulb.ohmynotification.update.AppUpdater
import io.github.prince_dulb.ohmynotification.update.UpdatePolicy
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.github.prince_dulb.ohmynotification.capture.ListenerRebindResult
import io.github.prince_dulb.ohmynotification.capture.ListenerRebindTrigger
import io.github.prince_dulb.ohmynotification.capture.ListenerRuntimeState
import io.github.prince_dulb.ohmynotification.capture.OmnNotificationListenerComponent
import io.github.prince_dulb.ohmynotification.capture.OmnNotificationListenerService
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStatus
import io.github.prince_dulb.ohmynotification.data.NotificationItemEntity
import io.github.prince_dulb.ohmynotification.ui.AppUiState
import io.github.prince_dulb.ohmynotification.ui.OmnAppScreen
import io.github.prince_dulb.ohmynotification.ui.theme.OhMyNotificationTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val onboardingVisible = mutableStateOf(false)
    private val updater by lazy { AppUpdater(this) }
    private var verifyingInstall = false
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) lifecycleScope.launch { updater.refresh() }
        }
    }
    private val unknownSourcesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) installUpdate()
        else Toast.makeText(this, "未允许安装，下载的更新仍可稍后安装", Toast.LENGTH_LONG).show()
    }
    private val uiState = mutableStateOf(AppUiState())
    private var automaticRebindJob: Job? = null
    private val connectionListener: (Boolean) -> Unit = {
        runOnUiThread(::refreshUiState)
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshUiState()
        if (granted) {
            val graph = (application as OmnApplication).graph
            graph.serialScope.launch {
                graph.statusNotificationController.onExternalStateChanged(
                    currentRecordCount = graph.repository.currentItemCount(),
                    latestItem = graph.repository.latestItem(),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onboardingVisible.value = !getSharedPreferences("omn-onboarding", MODE_PRIVATE).getBoolean("v1.1-completed", false)
        refreshUiState()
        setContent {
            OhMyNotificationTheme {
                if (onboardingVisible.value) OnboardingScreen(
                    state = uiState.value,
                    onAccess = ::openNotificationAccessSettings,
                    onNotifications = {
                        if (!uiState.value.statusNotificationGranted) requestStatusNotificationPermission()
                        else openStatusNotificationChannel()
                    },
                    onBattery = { openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                    onAppSettings = { openSystemSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    onFinish = {
                        lifecycleScope.launch {
                            val saved = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                getSharedPreferences("omn-onboarding", MODE_PRIVATE).edit().putBoolean("v1.1-completed", true).commit()
                            }
                            if (saved) onboardingVisible.value = false
                            else Toast.makeText(this@MainActivity, "引导状态未能保存，请重试", Toast.LENGTH_LONG).show()
                        }
                    },
                ) else OmnAppScreen(
                    state = uiState.value,
                    repository = (application as OmnApplication).graph.repository,
                    inboxViewFilter =
                        (application as OmnApplication).graph.inboxViewPreferencesStore.filter,
                    appSettings = (application as OmnApplication).graph.appSettingsStore.settings,
                    currentUserRef = android.os.Process.myUserHandle().toString(),
                    loadLaunchableSources =
                        (application as OmnApplication).graph.sourceLabelResolver::launchableSources,
                    onOpenNotificationAccess = ::openNotificationAccessSettings,
                    onRequestStatusNotification = ::requestStatusNotificationPermission,
                    onOpenStatusChannel = ::openStatusNotificationChannel,
                    onRequestListenerRebind = ::requestListenerRebind,
                    onSetSourceExcluded = ::setSourceExcluded,
                    onSetNotificationTypeRecorded =
                        (application as OmnApplication).graph.appSettingsStore::setNotificationTypeRecorded,
                    onSetGroupingWindowMinutes =
                        (application as OmnApplication).graph.appSettingsStore::setGroupingWindowMinutes,
                    onApplyInboxViewFilter =
                        (application as OmnApplication).graph.inboxViewPreferencesStore::setFilter,
                    onOpenNotification = ::openNotification,
                    onBatchAction = ::performBatch,
                    onUndoArchive = { change ->
                        serialMutation {
                            val graph = (application as OmnApplication).graph
                            graph.repository.undoArchiveChange(change)
                            refreshSummary()
                        }
                    },
                    onOpenOnboarding = { onboardingVisible.value = true },
                    updateContent = {
                        UpdateCard(updater, ::installUpdate) {
                            openSystemSettings(Intent(Intent.ACTION_VIEW, Uri.parse(UpdatePolicy.RELEASES_URL)))
                        }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
        ListenerRuntimeState.addListener(connectionListener)
        refreshUiState()
        automaticRebindJob?.cancel()
        automaticRebindJob = lifecycleScope.launch {
            delay(AUTOMATIC_REBIND_DELAY_MILLIS)
            if (uiState.value.listenerAccessGranted && !ListenerRuntimeState.isConnected()) {
                requestListenerRebind(ListenerRebindTrigger.AUTOMATIC)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { updater.refresh() }
        refreshUiState()
    }

    override fun onStop() {
        unregisterReceiver(downloadReceiver)
        automaticRebindJob?.cancel()
        automaticRebindJob = null
        ListenerRuntimeState.removeListener(connectionListener)
        super.onStop()
    }

    private fun refreshUiState() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val graph = (application as OmnApplication).graph
        uiState.value = AppUiState(
            batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
            listenerAccessGranted = notificationManager.isNotificationListenerAccessGranted(
                OmnNotificationListenerComponent.componentName(this),
            ),
            listenerConnected = ListenerRuntimeState.isConnected(),
            listenerConnectionObserved = ListenerRuntimeState.hasObservedConnectionState(),
            processingOperational = ListenerRuntimeState.isProcessingOperational(),
            statusNotificationGranted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            statusNotificationChannelEnabled =
                graph.statusNotificationController.isChannelEnabled(),
        )
        graph.serialScope.launch {
            graph.statusNotificationController.onExternalStateChanged(
                currentRecordCount = graph.repository.currentItemCount(),
                latestItem = graph.repository.latestItem(),
            )
        }
    }

    private fun openNotificationAccessSettings() {
        val detailIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                OmnNotificationListenerComponent.componentName(this@MainActivity).flattenToString(),
            )
        }
        try {
            startActivity(detailIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun requestStatusNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openStatusNotificationChannel() {
        startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(
                    Settings.EXTRA_CHANNEL_ID,
                    (application as OmnApplication).graph.statusNotificationController.channelId(),
                )
            },
        )
    }

    private fun requestListenerRebind(trigger: ListenerRebindTrigger): ListenerRebindResult =
        (application as OmnApplication).graph.listenerRebindController.request(trigger)

    private suspend fun setSourceExcluded(sourcePackage: String, excluded: Boolean): Boolean {
        val graph = (application as OmnApplication).graph
        val result = CompletableDeferred<Boolean>()
        graph.serialScope.launch {
            result.complete(
                runCatching {
                    graph.repository.setSourceExcluded(sourcePackage, excluded)
                }.isSuccess,
            )
        }
        return result.await()
    }

    private suspend fun openNotification(item: NotificationItemEntity): RuntimeActionStatus {
        val graph = (application as OmnApplication).graph
        val immediate = graph.repository.sendRuntimeAction(item)
        if (immediate != RuntimeActionStatus.NOT_FOUND) return immediate
        return withContext(NonCancellable) {
            OmnNotificationListenerService.requestActiveNotificationRecovery()
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                RuntimeActionStatus.NOT_FOUND
            } else {
                graph.repository.sendRuntimeAction(item)
            }
        }
    }

    private suspend fun <T> serialMutation(block: suspend () -> T): T {
        val graph = (application as OmnApplication).graph
        val result = CompletableDeferred<T>()
        graph.serialScope.launch {
            try { result.complete(block()) } catch (failure: Throwable) { result.completeExceptionally(failure) }
        }
        return result.await()
    }

    private suspend fun refreshSummary() {
        val graph = (application as OmnApplication).graph
        graph.statusNotificationController.onItemDeleted(
            currentRecordCount = graph.repository.currentItemCount(),
            latestItem = graph.repository.latestItem(),
        )
    }

    private suspend fun performBatch(ids: Set<Long>, sources: Set<AppUserKey>, action: BatchAction): BatchResult = serialMutation {
        val graph = (application as OmnApplication).graph
        val result = when (action) {
            BatchAction.ARCHIVE -> graph.repository.archiveItems(ids).let { BatchResult(it.entries.size, it) }
            BatchAction.RESTORE -> graph.repository.restoreItems(ids).let { BatchResult(it.entries.size, it) }
            BatchAction.DELETE -> BatchResult(graph.repository.deleteArchivedItems(ids))
            BatchAction.STOP, BatchAction.START -> {
                val packages = sources.mapTo(hashSetOf()) { it.sourcePackage }
                graph.repository.setSourcesExcluded(packages, action == BatchAction.STOP)
                BatchResult(packages.size)
            }
            BatchAction.ONLY, BatchAction.HIDE -> {
                val old = graph.inboxViewPreferencesStore.filter.value
                val next = if (action == BatchAction.ONLY) InboxViewFilter(includedSources = sources) else {
                    val remaining = old.includedSources - sources
                    if (old.includedSources.isNotEmpty() && remaining.isNotEmpty()) InboxViewFilter(includedSources = remaining)
                    else InboxViewFilter(excludedSources = old.excludedSources + sources)
                }
                check(graph.inboxViewPreferencesStore.setFilter(next)) { "未能保存来源筛选" }
                BatchResult(sources.size)
            }
        }
        refreshSummary()
        result
    }

    private fun openSystemSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "系统没有提供此设置入口，请手动进入系统设置", Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(this, "系统拒绝打开该页面，请手动进入系统设置", Toast.LENGTH_LONG).show() }
    }

    private fun installUpdate() {
        if (verifyingInstall) return
        verifyingInstall = true
        lifecycleScope.launch {
            try {
                val intent = updater.verifiedInstallIntent()
                if (!packageManager.canRequestPackageInstalls()) {
                    unknownSourcesLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                } else {
                    startActivity(intent)
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                Toast.makeText(this@MainActivity, failure.message ?: "无法安装更新", Toast.LENGTH_LONG).show()
            } finally { verifyingInstall = false }
        }
    }

    private companion object {
        const val AUTOMATIC_REBIND_DELAY_MILLIS = 3_000L
    }
}
