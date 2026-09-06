package io.github.prince_dulb.ohmynotification.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.prince_dulb.ohmynotification.R
import io.github.prince_dulb.ohmynotification.capture.ListenerRebindResult
import io.github.prince_dulb.ohmynotification.capture.ListenerRebindTrigger
import io.github.prince_dulb.ohmynotification.capture.RuntimeActionStatus
import io.github.prince_dulb.ohmynotification.capture.LaunchableSource
import io.github.prince_dulb.ohmynotification.core.model.AppSettingsSnapshot
import io.github.prince_dulb.ohmynotification.core.model.NotificationType
import io.github.prince_dulb.ohmynotification.core.health.StatusPresentationDeriver
import io.github.prince_dulb.ohmynotification.core.health.StatusPresentationState
import io.github.prince_dulb.ohmynotification.data.NotificationItemEntity
import io.github.prince_dulb.ohmynotification.data.NotificationActionView
import io.github.prince_dulb.ohmynotification.data.NotificationRepository
import io.github.prince_dulb.ohmynotification.data.AppUserKey
import io.github.prince_dulb.ohmynotification.data.InboxSourceKeyCodec
import io.github.prince_dulb.ohmynotification.data.InboxViewFilter
import io.github.prince_dulb.ohmynotification.data.SourceSummaryRow
import io.github.prince_dulb.ohmynotification.core.timeline.TimelineGrouper
import io.github.prince_dulb.ohmynotification.core.timeline.TimelineGroupingCandidate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppUiState(
    val batteryUnrestricted: Boolean = false,
    val listenerAccessGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val listenerConnectionObserved: Boolean = false,
    val processingOperational: Boolean = true,
    val statusNotificationGranted: Boolean = false,
    val statusNotificationChannelEnabled: Boolean = true,
)

@Composable
internal fun OmnAppScreen(
    state: AppUiState,
    repository: NotificationRepository,
    inboxViewFilter: Flow<InboxViewFilter>,
    appSettings: Flow<AppSettingsSnapshot>,
    currentUserRef: String,
    loadLaunchableSources: () -> List<LaunchableSource>,
    onOpenNotificationAccess: () -> Unit,
    onRequestStatusNotification: () -> Unit,
    onOpenStatusChannel: () -> Unit,
    onRequestListenerRebind: (ListenerRebindTrigger) -> ListenerRebindResult,
    onSetSourceExcluded: suspend (String, Boolean) -> Boolean,
    onSetNotificationTypeRecorded: suspend (NotificationType, Boolean) -> Boolean,
    onSetGroupingWindowMinutes: suspend (Int) -> Boolean,
    onApplyInboxViewFilter: suspend (InboxViewFilter) -> Boolean,
    onOpenNotification: suspend (NotificationItemEntity) -> RuntimeActionStatus,
    onBatchAction: suspend (Set<Long>, Set<AppUserKey>, BatchAction) -> BatchResult,
    onUndoArchive: suspend (io.github.prince_dulb.ohmynotification.data.ArchiveChange) -> Unit,
    onOpenOnboarding: () -> Unit,
    updateContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sources by repository.sourceSummaries.collectAsStateWithLifecycle(emptyList())
    val exclusions by repository.excludedSources.collectAsStateWithLifecycle(emptyList())
    val excludedPackages = remember(exclusions) {
        exclusions.mapTo(mutableSetOf()) { exclusion -> exclusion.sourcePackage }
    }
    val viewFilter by inboxViewFilter.collectAsStateWithLifecycle(InboxViewFilter())
    val settings by appSettings.collectAsStateWithLifecycle(AppSettingsSnapshot.DEFAULT)
    val runtimeActionItemIds by repository.runtimeActionItemIds.collectAsStateWithLifecycle(emptySet())
    val archiveCount by remember(runtimeActionItemIds) {
        repository.archiveCount(runtimeActionItemIds)
    }.collectAsStateWithLifecycle(0L)
    val launchableSources by produceState(emptyList<LaunchableSource>()) {
        value = withContext(Dispatchers.IO) { loadLaunchableSources() }
    }
    val filterChoices = remember(sources, launchableSources, viewFilter, currentUserRef) {
        val recorded = sources.associateBy { source ->
            AppUserKey(source.sourceUserRef, source.sourcePackage)
        }
        val launchable = launchableSources.associateBy(LaunchableSource::sourcePackage)
        val allKeys = (
            recorded.keys + viewFilter.includedSources + viewFilter.excludedSources
        ).distinct()
        val userCountByPackage = allKeys.groupingBy(AppUserKey::sourcePackage).eachCount()
        allKeys
            .map { source ->
                val summary = recorded[source]
                val installed = launchable[source.sourcePackage]
                SourceChoice(
                    source = source,
                    sourceLabel = summary?.sourceLabelSnapshot ?: installed?.sourceLabel ?: source.sourcePackage,
                    profileLabel = when {
                        userCountByPackage[source.sourcePackage] == 1 -> null
                        source.sourceUserRef == currentUserRef -> "当前资料"
                        else -> "其他资料"
                    },
                    recordCount = summary?.recordCount ?: 0L,
                )
            }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, SourceChoice::sourceLabel)
                    .thenBy { choice -> choice.source.sourceUserRef },
            )
    }
    val exclusionChoices = remember(sources, launchableSources, currentUserRef) {
        val recordedByPackage = sources.groupBy(SourceSummaryRow::sourcePackage)
        val launchableByPackage = launchableSources.associateBy(LaunchableSource::sourcePackage)
        (launchableByPackage.keys + recordedByPackage.keys)
            .distinct()
            .map { sourcePackage ->
                SourceChoice(
                    source = AppUserKey(currentUserRef, sourcePackage),
                    sourceLabel = recordedByPackage[sourcePackage]
                        ?.maxByOrNull(SourceSummaryRow::latestTimeEpochMillis)
                        ?.sourceLabelSnapshot
                        ?: launchableByPackage[sourcePackage]?.sourceLabel
                        ?: sourcePackage,
                    profileLabel = null,
                    recordCount = recordedByPackage[sourcePackage]
                        .orEmpty()
                        .sumOf(SourceSummaryRow::recordCount),
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SourceChoice::sourceLabel))
    }
    var filterDraft by remember { mutableStateOf(emptySet<AppUserKey>()) }
    var filterSaving by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<SourceDialog?>(null) }
    var destination by remember { mutableStateOf(AppDestination.INBOX) }
    var rebindFeedback by remember { mutableStateOf<String?>(null) }
    val expandedDrawers = remember { mutableStateMapOf<Long, Boolean>() }
    val snackbar = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val actionView = if (destination == AppDestination.UNAVAILABLE_ARCHIVE) {
        NotificationActionView.UNAVAILABLE_ARCHIVE
    } else {
        NotificationActionView.ACTIONABLE
    }
    val pagingFlow = remember(viewFilter, actionView, runtimeActionItemIds) {
        repository.pagedItems(viewFilter, actionView, runtimeActionItemIds)
    }
    val pagingItems = pagingFlow.collectAsLazyPagingItems()
    val loadedItems = pagingItems.itemSnapshotList.items
    val loadedIds = loadedItems.map { it.itemId }
    val manualArchiveIds by remember(loadedIds) {
        repository.manualArchiveIds(loadedIds)
    }.collectAsStateWithLifecycle(emptyList())
    val nodes = remember(loadedItems, settings.groupingWindowMinutes) {
        groupTimeline(loadedItems, settings.groupingWindowMinutes)
    }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptyMap<Long, AppUserKey>()) }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Map<Long, AppUserKey>?>(null) }
    var pendingSourceAction by remember { mutableStateOf<Pair<BatchAction, Map<Long, AppUserKey>>?>(null) }
    fun execute(action: BatchAction, targets: Map<Long, AppUserKey>) {
        if (busy || targets.isEmpty()) return
        busy = true
        coroutineScope.launch {
            val result = try {
                onBatchAction(targets.keys, targets.values.toSet(), action).also {
                    selected = emptyMap()
                    selecting = false
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            } finally {
                busy = false
            }
            if (result == null) {
                snackbar.showSnackbar("操作未能完成，请重试")
                return@launch
            }
            val message = when (action) {
                BatchAction.ARCHIVE -> "已归档 ${result.count} 条通知"
                BatchAction.RESTORE -> "已取消 ${result.count} 条手动归档；没有跳转动作的记录仍留在这里"
                BatchAction.DELETE -> "已永久删除 ${result.count} 条记录"
                else -> "已对 ${result.count} 个来源应用执行：${action.label}"
            }
            val answer = snackbar.showSnackbar(message, actionLabel = result.undo?.let { "撤销" })
            if (answer == SnackbarResult.ActionPerformed && result.undo != null) {
                try { onUndoArchive(result.undo) }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { snackbar.showSnackbar("撤销失败，请到归档页重试") }
            }
        }
    }

    fun requestAction(action: BatchAction, targets: Map<Long, AppUserKey>) {
        when (action) {
            BatchAction.DELETE -> pendingDelete = targets.toMap()
            BatchAction.STOP, BatchAction.START -> pendingSourceAction = action to targets.toMap()
            else -> execute(action, targets.toMap())
        }
    }
    LaunchedEffect(destination, viewFilter) {
        selecting = false
        selected = emptyMap()
    }
    if (pendingDelete != null) {
        val targets = requireNotNull(pendingDelete)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("永久删除 ${targets.size} 条记录？") },
            text = { Text("这会永久删除所选 ${targets.size} 条归档通知和当前跳转动作，无法恢复。来源以后再次发送或更新时仍可能重新出现。") },
            confirmButton = { TextButton(onClick = { pendingDelete = null; execute(BatchAction.DELETE, targets) }) { Text("永久删除 ${targets.size} 条") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
    pendingSourceAction?.let { (action, targets) ->
        val appCount = targets.values.map { it.sourcePackage }.toSet().size
        AlertDialog(
            onDismissRequest = { pendingSourceAction = null },
            title = { Text("${action.label}（$appCount 个 App）？") },
            text = { Text("只影响未来通知，已有记录保留。对同一 App 的所有资料生效；排除期间的通知无法补回。") },
            confirmButton = { TextButton(onClick = { pendingSourceAction = null; execute(action, targets) }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { pendingSourceAction = null }) { Text("取消") } },
        )
    }
    val inboxListState = rememberLazyListState()
    val archiveListState = rememberLazyListState()
    val listState = if (destination == AppDestination.UNAVAILABLE_ARCHIVE) {
        archiveListState
    } else {
        inboxListState
    }

    LaunchedEffect(state.listenerConnected) {
        if (state.listenerConnected) rebindFeedback = null
    }

    LaunchedEffect(actionView, nodes.size, loadedItems.size) {
        if (nodes.isEmpty() || loadedItems.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            lastVisible != null && shouldPrefetchTimeline(
                lastVisibleIndex = lastVisible.index,
                totalItemCount = layout.totalItemsCount,
                lastVisibleBottom = lastVisible.offset + lastVisible.size,
                viewportEnd = layout.viewportEndOffset,
                viewportSize = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0),
            )
        }
            .distinctUntilChanged()
            .filter { shouldPrefetch -> shouldPrefetch }
            .collect { pagingItems[loadedItems.lastIndex] }
    }

    BackHandler(enabled = destination != AppDestination.INBOX) {
        destination = AppDestination.INBOX
    }

    BackHandler(enabled = selecting) {
        if (!busy) { selecting = false; selected = emptyMap() }
    }

    if (destination == AppDestination.SETTINGS) {
        Surface(modifier = modifier.fillMaxSize()) {
            SettingsScreen(
                settings = settings,
                snackbarHostState = snackbar,
                onBack = { destination = AppDestination.INBOX },
                unavailableArchiveCount = archiveCount,
                onOpenOnboarding = onOpenOnboarding,
                updateContent = updateContent,
                onOpenUnavailableArchive = { destination = AppDestination.UNAVAILABLE_ARCHIVE },
                onManageExcludedApps = { dialog = SourceDialog.EXCLUDE },
                onSetNotificationTypeRecorded = onSetNotificationTypeRecorded,
                onSetGroupingWindowMinutes = onSetGroupingWindowMinutes,
            )
        }
    } else Surface(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
          Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (selecting) SelectionToolbar(
                count = selected.size,
                archive = actionView == NotificationActionView.UNAVAILABLE_ARCHIVE,
                busy = busy,
                onExit = { selecting = false; selected = emptyMap() },
                onSelectAll = {
                    busy = true
                    val snapshotFilter = viewFilter
                    val snapshotView = actionView
                    val snapshotActions = runtimeActionItemIds.toSet()
                    coroutineScope.launch {
                        try {
                            selected = repository.selectionSnapshot(snapshotFilter, snapshotView, snapshotActions)
                                .associate { it.itemId to AppUserKey(it.sourceUserRef, it.sourcePackage) }
                        } catch (_: Exception) { snackbar.showSnackbar("全选失败，请重试") }
                        finally { busy = false }
                    }
                },
                onAction = { requestAction(it, selected) },
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!selecting) item(key = "header") {
                    if (!selecting) TextButton(onClick = { selecting = true }) { Text("批量操作") }
                    if (destination == AppDestination.UNAVAILABLE_ARCHIVE) {
                        UnavailableArchiveHeader(
                            selectedCount = viewFilter.includedSources.size,
                            hiddenCount = viewFilter.excludedSources.size,
                            onBack = { destination = AppDestination.INBOX },
                            onOpenFilters = {
                                filterDraft = visibleSourceSelection(viewFilter, filterChoices)
                                filterSaving = false
                                dialog = SourceDialog.FILTER
                            },
                        )
                    } else {
                        InboxHeader(
                            state = state,
                            selectedCount = viewFilter.includedSources.size,
                            hiddenCount = viewFilter.excludedSources.size,
                            onOpenFilters = {
                                filterDraft = visibleSourceSelection(viewFilter, filterChoices)
                                filterSaving = false
                                dialog = SourceDialog.FILTER
                            },
                            onOpenSettings = { destination = AppDestination.SETTINGS },
                            onOpenNotificationAccess = onOpenNotificationAccess,
                            onRequestStatusNotification = onRequestStatusNotification,
                            onOpenStatusChannel = onOpenStatusChannel,
                            rebindFeedback = rebindFeedback,
                            onRequestListenerRebind = {
                                val result = onRequestListenerRebind(ListenerRebindTrigger.USER)
                                val message = result.userMessage()
                                rebindFeedback = message
                                coroutineScope.launch { snackbar.showSnackbar(message) }
                            },
                        )
                    }
                }

                if (pagingItems.loadState.refresh is LoadState.Error) {
                    item(key = "refresh-error") {
                        PagingLoadError(
                            message = "通知记录载入失败。已有数据没有被删除。",
                            onRetry = pagingItems::retry,
                        )
                    }
                } else if (nodes.isEmpty() && pagingItems.loadState.refresh is LoadState.NotLoading) {
                    item(key = "empty") {
                        EmptyInbox(isUnavailableArchive = actionView == NotificationActionView.UNAVAILABLE_ARCHIVE)
                    }
                }

                itemsIndexed(nodes, key = { _, node -> node.key }) { _, node ->
                    TimelineNodeCard(
                        node = node,
                        expanded = drawerExpanded(expandedDrawers, node.anchor.itemId),
                        onToggle = {
                            val itemId = node.anchor.itemId
                            expandedDrawers[itemId] = !drawerExpanded(expandedDrawers, itemId)
                        },
                        onOpen = { item ->
                            val status = onOpenNotification(item)
                            val message = when (status) {
                                RuntimeActionStatus.ACCEPTED -> "已交给原应用打开"
                                RuntimeActionStatus.CANCELED -> "原应用已取消这次跳转"
                                RuntimeActionStatus.NOT_FOUND -> "系统当前没有可重新取得的原通知动作"
                                RuntimeActionStatus.SECURITY_REJECTED -> "系统拒绝了这次跳转"
                            }
                            snackbar.showSnackbar(message)
                            status
                        },
                        selecting = selecting,
                        selectedIds = selected.keys,
                        busy = busy,
                        manualArchiveIds = manualArchiveIds.toSet(),
                        onSelect = { item ->
                            if (!busy) {
                                selecting = true
                                selected = if (item.itemId in selected) selected - item.itemId
                                    else selected + (item.itemId to item.sourceKey())
                            }
                        },
                        onEntryAction = { item, action -> requestAction(action, mapOf(item.itemId to item.sourceKey())) },
                        hasRuntimeAction = repository::hasRuntimeAction,
                        allowRecoveryAttempt = actionView == NotificationActionView.UNAVAILABLE_ARCHIVE,
                        excludedPackages = excludedPackages,
                        onSetSourceExcluded = { item, excluded ->
                            val saved = onSetSourceExcluded(item.sourcePackage, excluded)
                            coroutineScope.launch {
                                snackbar.showSnackbar(
                                    if (saved && excluded) {
                                        "已不再记录 ${item.sourceName()} 的新通知"
                                    } else if (saved) {
                                        "已重新记录 ${item.sourceName()} 的新通知"
                                    } else {
                                        "未能保存设置，监控范围没有改变"
                                    },
                                )
                            }
                            saved
                        },
                        onOnlyViewSource = { item ->
                            coroutineScope.launch {
                                val saved = onApplyInboxViewFilter(
                                    viewFilter.only(item.sourceKey()),
                                )
                                snackbar.showSnackbar(
                                    if (saved) "现在只查看 ${item.sourceName()}"
                                    else "查看范围保存失败，仍使用原筛选",
                                )
                            }
                        },
                        onHideSource = { item ->
                            coroutineScope.launch {
                                val saved = onApplyInboxViewFilter(
                                    viewFilter.without(item.sourceKey()),
                                )
                                snackbar.showSnackbar(
                                    if (saved) "已不查看 ${item.sourceName()}"
                                    else "查看范围保存失败，仍使用原筛选",
                                )
                            }
                        },
                    )
                }

                if (pagingItems.loadState.append is LoadState.Loading) {
                    item(key = "loading") {
                        Text(
                            text = "正在载入更早的记录…",
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (pagingItems.loadState.append is LoadState.Error) {
                    item(key = "append-error") {
                        PagingLoadError(
                            message = "更早的记录载入失败。",
                            onRetry = pagingItems::retry,
                        )
                    }
                }
            }
          }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding(),
            )
        }
    }

    when (dialog) {
        SourceDialog.FILTER -> SourceSelectionDialog(
            title = "只查看这些应用",
            sources = filterChoices,
            checkedSources = filterDraft,
            emptyMeansAll = true,
            supportingText = null,
            onToggle = { source, checked ->
                filterDraft = filterDraft.toMutableSet().apply {
                    if (checked) add(source) else remove(source)
                }
            },
            onClear = { filterDraft = emptySet() },
            confirmLabel = if (filterSaving) "保存中…" else "应用",
            confirmEnabled = !filterSaving,
            showCancel = true,
            onConfirm = {
                if (!filterSaving) {
                    filterSaving = true
                    coroutineScope.launch {
                        val saved = onApplyInboxViewFilter(
                            viewFilterFromVisibleSelection(viewFilter, filterDraft, filterChoices),
                        )
                        filterSaving = false
                        if (saved) dialog = null else snackbar.showSnackbar("查看范围保存失败，仍使用原筛选")
                    }
                }
            },
            onDismiss = { if (!filterSaving) dialog = null },
        )
        SourceDialog.EXCLUDE -> SourceSelectionDialog(
            title = "不监控这些应用（所有资料）",
            sources = exclusionChoices,
            checkedSources = exclusionChoices
                .asSequence()
                .map(SourceChoice::source)
                .filter { source -> exclusions.any { it.sourcePackage == source.sourcePackage } }
                .toSet(),
            emptyMeansAll = false,
            supportingText = "受 Android 限制，应用列表可能不完整；未列出的应用仍会默认记录，首次形成历史后即可在这里排除。当前排除按同包的所有资料生效。",
            onToggle = { source, excluded ->
                coroutineScope.launch {
                    if (!onSetSourceExcluded(source.sourcePackage, excluded)) {
                        snackbar.showSnackbar("未能保存设置，监控范围没有改变")
                    }
                }
            },
            onClear = null,
            onConfirm = { dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun InboxHeader(
    state: AppUiState,
    selectedCount: Int,
    hiddenCount: Int,
    onOpenFilters: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onRequestStatusNotification: () -> Unit,
    onOpenStatusChannel: () -> Unit,
    rebindFeedback: String?,
    onRequestListenerRebind: () -> Unit,
) {
    val presentation = StatusPresentationDeriver.derive(
        listenerAccessGranted = state.listenerAccessGranted,
        connectionObserved = state.listenerConnectionObserved,
        listenerConnected = state.listenerConnected,
        processingOperational = state.processingOperational,
    )
    val healthy = presentation == StatusPresentationState.LISTENING
    val healthTitle = when (presentation) {
        StatusPresentationState.LISTENING -> "● 正在记录通知"
        StatusPresentationState.PROCESSING_INTERRUPTED -> "◆ 记录链路发生故障"
        StatusPresentationState.WAITING_FOR_CONNECTION -> "◆ 已授权，等待系统连接"
        StatusPresentationState.LISTENER_INTERRUPTED -> "◆ 监听连接已中断"
        StatusPresentationState.ACCESS_REQUIRED -> "◆ 需要通知使用权"
    }
    val listenerDetail = when (presentation) {
        StatusPresentationState.LISTENING -> "已连接"
        StatusPresentationState.PROCESSING_INTERRUPTED -> "处理故障"
        StatusPresentationState.WAITING_FOR_CONNECTION -> "等待连接"
        StatusPresentationState.LISTENER_INTERRUPTED -> "已中断"
        StatusPresentationState.ACCESS_REQUIRED -> "未授权"
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.inbox_eyebrow),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (healthy) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = healthTitle,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "通知使用权 ${state.listenerAccessGranted.status()}　监听 $listenerDetail\n通知权限 ${state.statusNotificationGranted.status()}　状态渠道 ${state.statusNotificationChannelEnabled.status()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (!state.listenerAccessGranted) {
            Button(onClick = onOpenNotificationAccess, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_grant_notification_access))
            }
        }
        if (state.listenerAccessGranted && !state.listenerConnected) {
            Button(onClick = onRequestListenerRebind, modifier = Modifier.fillMaxWidth()) {
                Text("请求重新连接监听")
            }
            rebindFeedback?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onOpenNotificationAccess, modifier = Modifier.fillMaxWidth()) {
                Text("打开通知使用权设置")
            }
        }
        if (!state.statusNotificationGranted) {
            Button(onClick = onRequestStatusNotification, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_grant_status_notification))
            }
        }
        if (state.statusNotificationGranted && !state.statusNotificationChannelEnabled) {
            Button(onClick = onOpenStatusChannel, modifier = Modifier.fillMaxWidth()) {
                Text("重新开启状态通知渠道")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onOpenFilters, modifier = Modifier.weight(1f)) {
                Text(viewFilterLabel(selectedCount, hiddenCount, includeAppSuffix = false))
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                Text("设置")
            }
        }
    }
}

@Composable
private fun UnavailableArchiveHeader(
    selectedCount: Int,
    hiddenCount: Int,
    onBack: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("返回可跳转通知") }
        Text(
            text = "已归档通知",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "这些记录仍保存在本机。点按其中一条会重扫一次系统当前仍存在的通知；若重新取得同一条的动作就尝试打开，但不保证成功。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onOpenFilters, modifier = Modifier.fillMaxWidth()) {
            Text(viewFilterLabel(selectedCount, hiddenCount, includeAppSuffix = true))
        }
    }
}

@Composable
private fun TimelineNodeCard(
    node: TimelineNode,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: suspend (NotificationItemEntity) -> RuntimeActionStatus,
    selecting: Boolean,
    selectedIds: Set<Long>,
    busy: Boolean,
    manualArchiveIds: Set<Long>,
    onSelect: (NotificationItemEntity) -> Unit,
    onEntryAction: (NotificationItemEntity, BatchAction) -> Unit,
    hasRuntimeAction: (NotificationItemEntity) -> Boolean,
    allowRecoveryAttempt: Boolean,
    excludedPackages: Set<String>,
    onSetSourceExcluded: suspend (NotificationItemEntity, Boolean) -> Boolean,
    onOnlyViewSource: (NotificationItemEntity) -> Unit,
    onHideSource: (NotificationItemEntity) -> Unit,
) {
    val source = node.anchor
    val sourceExcluded = source.sourcePackage in excludedPackages
    var actionMenuExpanded by remember { mutableStateOf(false) }
    var showExclusionDialog by remember { mutableStateOf(false) }
    var requestedExcluded by remember { mutableStateOf(true) }
    var exclusionSaving by remember { mutableStateOf(false) }
    var exclusionError by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SourceAppIcon(
                        sourcePackage = source.sourcePackage,
                        sourceName = source.sourceName(),
                        size = 36.dp,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(source.sourceName(), fontWeight = FontWeight.Bold)
                        Text(
                            text = if (node.members.size == 1) {
                                "1 条 · ${formatTime(source.sortTimeEpochMillis)}"
                            } else {
                                "${node.members.size} 条 · ${formatTime(node.members.last().sortTimeEpochMillis)}—${formatTime(source.sortTimeEpochMillis)}"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (node.members.size > 1) {
                        TextButton(onClick = onToggle) {
                            Text(if (expanded) "收起" else "展开")
                        }
                    }
                    Box {
                        IconButton(
                            enabled = !selecting && !busy,
                            onClick = { actionMenuExpanded = true },
                            modifier = Modifier.semantics {
                                contentDescription = "${source.sourceName()} 应用操作"
                            },
                        ) {
                            Text("⋮", style = MaterialTheme.typography.titleLarge)
                        }
                        DropdownMenu(
                            expanded = actionMenuExpanded,
                            onDismissRequest = { actionMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (sourceExcluded) "重新记录这个 App"
                                        else "不要再记录这个 App",
                                    )
                                },
                                onClick = {
                                    actionMenuExpanded = false
                                    exclusionError = false
                                    requestedExcluded = !sourceExcluded
                                    showExclusionDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("仅查看此 App") },
                                onClick = {
                                    actionMenuExpanded = false
                                    onOnlyViewSource(source)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("不查看此 App") },
                                onClick = {
                                    actionMenuExpanded = false
                                    onHideSource(source)
                                },
                            )
                        }
                    }
                }
                HorizontalDivider()
                val displayedMembers = if (node.members.size == 1 || expanded) {
                    node.members
                } else {
                    listOf(source)
                }
                displayedMembers.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider()
                    NotificationEntry(
                        item = item,
                        onOpen = onOpen,
                        selecting = selecting,
                        selected = item.itemId in selectedIds,
                        busy = busy,
                        manuallyArchived = item.itemId in manualArchiveIds,
                        onSelect = { onSelect(item) },
                        onAction = { onEntryAction(item, it) },
                        hasRuntimeAction = hasRuntimeAction,
                        allowRecoveryAttempt = allowRecoveryAttempt,
                        showTime = node.members.size > 1,
                    )
                }
            }
        }
    }
    if (showExclusionDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!exclusionSaving) showExclusionDialog = false
            },
            title = {
                Text(
                    if (requestedExcluded) {
                        "不要再记录 ${source.sourceName()}？"
                    } else {
                        "重新记录 ${source.sourceName()}？"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (requestedExcluded) {
                            "之后不再记录这个 App 的新通知；已有记录会保留。当前版本对同一 App 的所有资料生效。"
                        } else {
                            "之后会重新记录这个 App 的新通知；排除期间的通知无法补回。当前版本对同一 App 的所有资料生效。"
                        },
                    )
                    if (exclusionError) {
                        Text(
                            "设置没有保存，请重试。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !exclusionSaving,
                    onClick = {
                        exclusionSaving = true
                        exclusionError = false
                        coroutineScope.launch {
                            val saved = onSetSourceExcluded(source, requestedExcluded)
                            exclusionSaving = false
                            exclusionError = !saved
                            if (saved) showExclusionDialog = false
                        }
                    },
                ) {
                    Text(
                        when {
                            exclusionSaving -> "保存中…"
                            requestedExcluded -> "不再记录"
                            else -> "重新记录"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !exclusionSaving,
                    onClick = { showExclusionDialog = false },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun NotificationEntry(
    item: NotificationItemEntity,
    onOpen: suspend (NotificationItemEntity) -> RuntimeActionStatus,
    selecting: Boolean,
    selected: Boolean,
    busy: Boolean,
    manuallyArchived: Boolean,
    onSelect: () -> Unit,
    onAction: (BatchAction) -> Unit,
    hasRuntimeAction: (NotificationItemEntity) -> Boolean,
    allowRecoveryAttempt: Boolean,
    showTime: Boolean,
) {
    val scope = rememberCoroutineScope()
    var opening by remember(item.itemId) { mutableStateOf(false) }
    val runtimeAvailable = hasRuntimeAction(item)
    Column(
        Modifier.fillMaxWidth().combinedClickable(
            enabled = !busy && !opening,
            onLongClick = onSelect,
            onClick = {
                if (selecting) onSelect()
                else if (runtimeAvailable || allowRecoveryAttempt) {
                    opening = true
                    scope.launch { try { onOpen(item) } finally { opening = false } }
                }
            },
        ).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = { onSelect() }, enabled = !busy)
            if (showTime) Text(formatTime(item.sortTimeEpochMillis), style = MaterialTheme.typography.labelMedium)
        }
        item.title?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) }
        item.body?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
        if (!selecting) {
            notificationActionPrompt(runtimeAvailable, allowRecoveryAttempt)?.let {
                Text(if (opening) "正在尝试打开…" else it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Row {
                if (allowRecoveryAttempt) {
                    if (manuallyArchived) TextButton(onClick = { onAction(BatchAction.RESTORE) }, enabled = !busy) { Text("取消手动归档") }
                    TextButton(onClick = { onAction(BatchAction.DELETE) }, enabled = !busy) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { onAction(BatchAction.ARCHIVE) }, enabled = !busy) { Text("归档") }
                }
            }
            if (allowRecoveryAttempt) Text(
                if (manuallyArchived) "手动归档" else "当前没有跳转动作",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SourceAppIcon(
    sourcePackage: String,
    sourceName: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, sourcePackage) {
        value = SourceIconCache.load(context, sourcePackage)
    }
    val semanticsModifier = modifier
        .size(size)
        .semantics { contentDescription = "$sourceName 应用图标" }
    val bitmap = icon
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = semanticsModifier,
        )
    } else {
        Surface(
            modifier = semanticsModifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    sourceName.take(1).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun EmptyInbox(isUnavailableArchive: Boolean) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (isUnavailableArchive) "归档是空的" else "当前没有可尝试跳转的通知",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (isUnavailableArchive) {
                    "手动归档或当前没有跳转动作的通知会出现在这里。"
                } else {
                    "收到带原通知动作的新通知后，它会出现在这里；其他历史保留在设置里的已归档通知。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PagingLoadError(message: String, onRetry: () -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun SourceSelectionDialog(
    title: String,
    sources: List<SourceChoice>,
    checkedSources: Set<AppUserKey>,
    emptyMeansAll: Boolean,
    supportingText: String?,
    onToggle: (AppUserKey, Boolean) -> Unit,
    onClear: (() -> Unit)?,
    confirmLabel: String = "完成",
    confirmEnabled: Boolean = true,
    showCancel: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                supportingText?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (sources.isEmpty()) {
                    Text("收到第一条通知后，来源应用会出现在这里。")
                } else {
                    LazyColumn(Modifier.heightIn(max = 440.dp)) {
                        items(sources, key = { source -> sourceDialogSaveableItemKey(source.source) }) { source ->
                            val checked = source.source in checkedSources
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(source.source, !checked) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onToggle(source.source, it) },
                                )
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(
                                        if (source.profileLabel == null) source.sourceLabel
                                        else "${source.sourceLabel} · ${source.profileLabel}",
                                    )
                                    Text(
                                        "${source.recordCount} 条 · ${source.source.sourcePackage}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
        },
        dismissButton = if ((emptyMeansAll && onClear != null) || showCancel) {
            {
                Row {
                    if (emptyMeansAll && onClear != null) {
                        TextButton(onClick = onClear, enabled = confirmEnabled) { Text("查看全部") }
                    }
                    if (showCancel) {
                        TextButton(onClick = onDismiss, enabled = confirmEnabled) { Text("取消") }
                    }
                }
            }
        } else {
            null
        },
    )
}

private data class TimelineNode(val members: List<NotificationItemEntity>) {
    val anchor: NotificationItemEntity get() = members.first()
    val key: Long get() = anchor.itemId
}

private data class SourceChoice(
    val source: AppUserKey,
    val sourceLabel: String,
    val profileLabel: String?,
    val recordCount: Long,
)

private fun groupTimeline(
    items: List<NotificationItemEntity>,
    groupingWindowMinutes: Int,
): List<TimelineNode> {
    val byId = items.associateBy(NotificationItemEntity::itemId)
    val candidates = items.map { item ->
        TimelineGroupingCandidate(
            itemId = item.itemId,
            sourcePackage = item.sourcePackage,
            sourceUserRef = item.sourceUserRef,
            firstReceivedAtEpochMillis = item.firstReceivedAtEpochMillis,
        )
    }
    return TimelineGrouper.group(
        candidates,
        windowMillis = groupingWindowMinutes * 60_000L,
    ).map { group ->
        TimelineNode(group.memberItemIds.map { itemId -> requireNotNull(byId[itemId]) })
    }
}

private fun NotificationItemEntity.sourceName(): String = sourceLabelSnapshot ?: sourcePackage

internal fun notificationActionPrompt(
    runtimeAvailable: Boolean,
    allowRecoveryAttempt: Boolean,
): String? = when {
    runtimeAvailable -> "点按尝试打开原内容"
    allowRecoveryAttempt -> "尝试重新获取并打开原内容（不一定成功）"
    else -> null
}

private fun NotificationItemEntity.sourceKey(): AppUserKey =
    AppUserKey(sourceUserRef, sourcePackage)

private fun visibleSourceSelection(
    filter: InboxViewFilter,
    choices: List<SourceChoice>,
): Set<AppUserKey> = if (filter.includedSources.isNotEmpty()) {
    filter.includedSources
} else {
    choices.mapTo(linkedSetOf(), SourceChoice::source) - filter.excludedSources
}

private fun viewFilterFromVisibleSelection(
    currentFilter: InboxViewFilter,
    selection: Set<AppUserKey>,
    choices: List<SourceChoice>,
): InboxViewFilter {
    val allSources = choices.mapTo(linkedSetOf(), SourceChoice::source)
    return when {
        selection == visibleSourceSelection(currentFilter, choices) -> currentFilter
        selection.isEmpty() || selection == allSources -> InboxViewFilter()
        else -> InboxViewFilter(includedSources = selection)
    }
}

private fun viewFilterLabel(
    selectedCount: Int,
    hiddenCount: Int,
    includeAppSuffix: Boolean,
): String {
    val suffix = if (includeAppSuffix) "应用" else ""
    return when {
        selectedCount > 0 -> "查看：$selectedCount 个$suffix"
        hiddenCount > 0 -> "查看：隐藏 $hiddenCount 个$suffix"
        else -> "查看：全部$suffix"
    }
}

internal fun sourceDialogSaveableItemKey(source: AppUserKey): String =
    InboxSourceKeyCodec.encode(source)

internal fun drawerExpanded(expansionOverrides: Map<Long, Boolean>, anchorItemId: Long): Boolean =
    expansionOverrides[anchorItemId] ?: true

internal fun shouldPrefetchTimeline(
    lastVisibleIndex: Int,
    totalItemCount: Int,
    lastVisibleBottom: Int,
    viewportEnd: Int,
    viewportSize: Int,
): Boolean {
    if (totalItemCount <= 0 || lastVisibleIndex < totalItemCount - PREFETCH_ITEM_THRESHOLD) return false
    return lastVisibleBottom <= viewportEnd + viewportSize * PREFETCH_VIEWPORTS
}

private fun Boolean.status(): String = if (this) "正常" else "不可用"

private fun ListenerRebindResult.userMessage(): String = when (this) {
    ListenerRebindResult.REQUESTED -> "已请求系统连接；状态变为“正在记录通知”才算成功"
    ListenerRebindResult.ALREADY_CONNECTED -> "监听已经连接"
    ListenerRebindResult.ACCESS_REQUIRED -> "请先授予通知使用权"
    ListenerRebindResult.COOLDOWN_OR_AUTOMATIC_LIMIT -> "操作太快，请稍等一秒再试"
    ListenerRebindResult.PLATFORM_REJECTED -> "系统未接受重连请求"
}

private fun formatTime(epochMillis: Long): String = TIME_FORMATTER.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)

private enum class SourceDialog { FILTER, EXCLUDE }
private enum class AppDestination { INBOX, SETTINGS, UNAVAILABLE_ARCHIVE }

private object SourceIconCache {
    private val icons = LruCache<String, ImageBitmap>(64)

    suspend fun load(context: Context, sourcePackage: String): ImageBitmap? = withContext(Dispatchers.IO) {
        synchronized(icons) { icons.get(sourcePackage) }?.let { return@withContext it }
        val bitmap = runCatching {
            val drawable = context.packageManager.getApplicationIcon(sourcePackage)
                .constantState
                ?.newDrawable()
                ?.mutate()
                ?: context.packageManager.getApplicationIcon(sourcePackage).mutate()
            val width = drawable.intrinsicWidth.coerceAtLeast(1).coerceAtMost(192)
            val height = drawable.intrinsicHeight.coerceAtLeast(1).coerceAtMost(192)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { output ->
                drawable.setBounds(0, 0, width, height)
                drawable.draw(AndroidCanvas(output))
            }.asImageBitmap()
        }.getOrNull() ?: return@withContext null
        synchronized(icons) { icons.put(sourcePackage, bitmap) }
        bitmap
    }
}

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private const val PREFETCH_ITEM_THRESHOLD = 3
private const val PREFETCH_VIEWPORTS = 2
