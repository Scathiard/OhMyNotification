package io.github.prince_dulb.ohmynotification.data

internal data class PositiveHealthEvidence(
    val runtimeSessionId: String,
    val listenerConnectionId: String,
    val occurredAtEpochMillis: Long,
)

internal class PositiveHealthEvidenceGate(
    private val maxRememberedConnections: Int = 16,
) {
    private data class ConnectionKey(
        val runtimeSessionId: String,
        val listenerConnectionId: String,
    )

    private val lastRecordedAtByConnection = object : LinkedHashMap<ConnectionKey, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConnectionKey, Long>?): Boolean =
            size > maxRememberedConnections
    }

    init {
        require(maxRememberedConnections > 0)
    }

    @Synchronized
    fun isDue(evidence: PositiveHealthEvidence, minimumIntervalMillis: Long): Boolean {
        require(minimumIntervalMillis >= 0L)
        val lastRecordedAt = lastRecordedAtByConnection[evidence.connectionKey()] ?: return true
        return evidence.occurredAtEpochMillis < lastRecordedAt ||
            evidence.occurredAtEpochMillis - lastRecordedAt >= minimumIntervalMillis
    }

    @Synchronized
    fun markRecorded(evidence: PositiveHealthEvidence) {
        lastRecordedAtByConnection[evidence.connectionKey()] = evidence.occurredAtEpochMillis
    }

    private fun PositiveHealthEvidence.connectionKey() = ConnectionKey(
        runtimeSessionId = runtimeSessionId,
        listenerConnectionId = listenerConnectionId,
    )
}
