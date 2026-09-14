package io.github.prince_dulb.ohmynotification.capture

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import io.github.prince_dulb.ohmynotification.R
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded local event log; detailed counters remain opt-in at build time. */
internal object ListenerDiagnostics {
    enum class Counter { POST_CALLBACK, REMOVAL_CALLBACK, SNAPSHOT_CAPTURED, SNAPSHOT_EXCLUDED, SNAPSHOT_FAILED, QUEUE_REJECTED, CAPTURE_PROCESSED }
    private val counters = java.util.concurrent.atomic.AtomicIntegerArray(Counter.entries.size)
    fun count(counter: Counter) { if (detailedEnabled) counters.incrementAndGet(counter.ordinal) }
    fun counterSnapshot(): Map<String, Int> = Counter.entries.associate { it.name to counters.get(it.ordinal) }
    private sealed interface Work {
        data class Record(val line: String) : Work
        data class Export(val result: CompletableDeferred<String>) : Work
    }
    private var app: Context? = null
    private var queue: Channel<Work>? = null
    private val dropped = AtomicInteger()
    private val ioFailures = AtomicInteger()
    private val admissionLock = Any()
    private var lastRecord: ListenerDiagnosticRecord? = null
    @Volatile private var detailedEnabled = false
    val enabled: Boolean get() = queue != null

    fun initialize(context: Context) {
        if (enabled) return
        detailedEnabled = context.resources.getBoolean(R.bool.omn_listener_diagnostics)
        app = context.applicationContext
        val channel = Channel<Work>(64)
        queue = channel
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val file = AtomicFile(File(context.noBackupFilesDir, "omn-listener-diagnostics.log"))
            val rows = ArrayDeque<String>()
            try {
                file.openRead().use { input ->
                    val bytes = input.readNBytes(ListenerDiagnosticRecord.MAX_BYTES + 1)
                    if (bytes.size <= ListenerDiagnosticRecord.MAX_BYTES) {
                        rows.addAll(ListenerDiagnosticRecord.retainedLines(bytes.toString(Charsets.UTF_8)))
                    } else ioFailures.incrementAndGet()
                }
            } catch (_: FileNotFoundException) { /* First run. */ }
            catch (_: Exception) { ioFailures.incrementAndGet() }
            var pending: Work? = null
            while (true) {
              val work = pending ?: channel.receiveCatching().getOrNull() ?: break
              pending = null
              when (work) {
                is Work.Record -> {
                    if (rows.size == ListenerDiagnosticRecord.MAX_RECORDS) rows.removeFirst()
                    rows.addLast(work.line)
                    // Drain only work already queued. No delay, timer, or background polling.
                    for (index in 1 until 32) {
                        val next = channel.tryReceive().getOrNull() ?: break
                        if (next !is Work.Record) { pending = next; break }
                        if (rows.size == ListenerDiagnosticRecord.MAX_RECORDS) rows.removeFirst()
                        rows.addLast(next.line)
                    }
                    var stream: java.io.FileOutputStream? = null
                    try {
                        stream = file.startWrite()
                        stream.write(rows.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
                        file.finishWrite(stream)
                    } catch (_: Exception) {
                        runCatching { file.failWrite(stream) }
                        ioFailures.incrementAndGet()
                    }
                }
                is Work.Export -> work.result.complete(report(context, rows.toList()))
              }
            }
        }
        record(ListenerDiagnosticEvent.PROCESS_START)
    }

    fun record(event: ListenerDiagnosticEvent, detail: Int = -1) {
        val channel = queue ?: return
        val context = app ?: return
        if (!detailedEnabled && event.detailedOnly) return
        // Diagnostic failures must never alter the production recovery or capture path.
        try {
            val access = runCatching {
                context.getSystemService(NotificationManager::class.java)
                    .isNotificationListenerAccessGranted(OmnNotificationListenerComponent.componentName(context))
            }.fold({ if (it) 1 else 0 }, { -1 })
            val record = ListenerDiagnosticRecord(event, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                Process.myPid(), access, ListenerRuntimeState.isConnected(),
                ListenerRuntimeState.isProcessingOperational(), ListenerRuntimeState.hasObservedConnectionState(),
                ListenerRuntimeState.hasKnownGap(), detail)
            synchronized(admissionLock) {
                if (record.isRedundantAfter(lastRecord)) return
                if (channel.trySend(Work.Record(record.line())).isSuccess) lastRecord = record
                else dropped.incrementAndGet()
            }
        } catch (_: Exception) { dropped.incrementAndGet() }
    }

    suspend fun export(): String? {
        val channel = queue ?: return null
        record(ListenerDiagnosticEvent.EXPORT)
        val result = CompletableDeferred<String>()
        if (!channel.trySend(Work.Export(result)).isSuccess) return null
        return withTimeoutOrNull(5_000) { result.await() }
    }

    private fun report(context: Context, rows: List<String>): String = buildString {
        appendLine("OMN-LISTENER-DIAGNOSTICS 1")
        appendLine("versionCode=" + runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(-1))
        appendLine("sdk=${Build.VERSION.SDK_INT} dropped=${dropped.get()} ioFailures=${ioFailures.get()}")
        appendLine("mode=" + if (detailedEnabled) "detailed" else "basic")
        if (detailedEnabled) appendLine("Current-process counters: " + counterSnapshot().entries.joinToString { "${it.key}=${it.value}" })
        appendLine("Columns: event wallMillis elapsedMillis pid access connected processing observed gap detail")
        appendLine("Recovery stages: " + ListenerRecoveryState.entries.joinToString { "${it.ordinal}=${it.name}" })
        appendLine("Request results: " + ListenerRebindResult.entries.joinToString { "${it.ordinal}=${it.name}" })
        rows.forEach(::appendLine)
        appendLine("System exits (bounded; absence is not proof of no process death): wallMillis pid reason status importance")
        try {
            val exits = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 8)
            for (exit in exits) appendLine("EXIT ${exit.timestamp} ${exit.pid} ${exit.reason} ${exit.status} ${exit.importance}")
        } catch (_: Exception) { appendLine("EXIT_UNAVAILABLE") }
        appendLine("Event tail may be missing after sudden process death; no notification content is collected.")
    }
}
