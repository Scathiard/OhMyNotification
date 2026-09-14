package io.github.prince_dulb.ohmynotification.capture

internal enum class ListenerDiagnosticEvent {
    PROCESS_START, STORAGE_READY, STORAGE_FAILED, UI_START, UI_STOP,
    SERVICE_CREATE, SERVICE_BIND, SERVICE_CONNECT, SERVICE_DISCONNECT, SERVICE_DESTROY, TASK_REMOVED,
    HEALTH, RECOVERY_STAGE, BIND_REQUEST, UNBIND_REQUEST, USER_REQUEST, AUTOMATIC_REQUEST, EXPORT;

    val detailedOnly: Boolean get() = this == UI_START || this == UI_STOP
}

/** Only closed event names and numeric metadata can enter the journal. */
internal data class ListenerDiagnosticRecord(
    val event: ListenerDiagnosticEvent,
    val wallMillis: Long,
    val elapsedMillis: Long,
    val pid: Int,
    val access: Int,
    val connected: Boolean,
    val processing: Boolean,
    val observed: Boolean,
    val gap: Boolean,
    val detail: Int = -1,
) {
    fun isRedundantAfter(previous: ListenerDiagnosticRecord?): Boolean =
        previous != null && (event == ListenerDiagnosticEvent.HEALTH || event == ListenerDiagnosticEvent.STORAGE_FAILED) &&
            copy(wallMillis = previous.wallMillis, elapsedMillis = previous.elapsedMillis) == previous

    fun line(): String = listOf(event.name, wallMillis, elapsedMillis, pid, access,
        if (connected) 1 else 0, if (processing) 1 else 0, if (observed) 1 else 0,
        if (gap) 1 else 0, detail).joinToString(" ")

    companion object {
        const val MAX_RECORDS = 256
        const val MAX_BYTES = 65_536
        fun retainedLines(text: String): List<String> = text.lineSequence().filter { line ->
            val parts = line.split(' ')
            parts.size == 10 && ListenerDiagnosticEvent.entries.any { it.name == parts[0] } &&
                parts.drop(1).all { it.length <= 20 && it.toLongOrNull() != null }
        }.takeLastBounded(MAX_RECORDS)

        private fun Sequence<String>.takeLastBounded(limit: Int): List<String> {
            val retained = ArrayDeque<String>()
            for (line in this) {
                if (retained.size == limit) retained.removeFirst()
                retained.addLast(line)
            }
            return retained.toList()
        }
    }
}
