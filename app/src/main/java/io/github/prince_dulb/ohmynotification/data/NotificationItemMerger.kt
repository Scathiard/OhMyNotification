package io.github.prince_dulb.ohmynotification.data

import io.github.prince_dulb.ohmynotification.core.model.NotificationObservation
import io.github.prince_dulb.ohmynotification.core.model.SnapshotCopyWarning
import io.github.prince_dulb.ohmynotification.core.normalize.NormalizedContent

internal data class NotificationMergeOutcome(
    val item: NotificationItemEntity,
    val isNewItem: Boolean,
)

internal object NotificationItemMerger {
    fun merge(
        previous: NotificationItemEntity?,
        observation: NotificationObservation,
        content: NormalizedContent,
    ): NotificationMergeOutcome {
        val identity = observation.identity
        val titleFailed = SnapshotCopyWarning.TITLE_COPY_FAILED in observation.copyWarnings
        val bodyFailed = observation.copyWarnings.any {
            it != SnapshotCopyWarning.TITLE_COPY_FAILED && it != SnapshotCopyWarning.CAPTURE_TEXT_TRUNCATED
        }
        // Never carry content from a removed lifecycle into a newly posted notification.
        val fallback = previous?.takeUnless { it.isRemoved }
        val title = if (titleFailed && content.title == null) fallback?.title else content.title
        val body = if (bodyFailed && content.body == null) fallback?.body else content.body
        val titleSize = if (title != content.title) fallback!!.titleOriginalCodePoints else content.titleOriginalCodePoints
        val bodySize = if (body != content.body) fallback!!.bodyOriginalCodePoints else content.bodyOriginalCodePoints
        val fingerprint = if (title != content.title || body != content.body) {
            if (previous != null && previous.title == title && previous.body == body) previous.contentFingerprint else null
        } else content.contentFingerprint
        val connectionGap = previous != null && (
            previous.runtimeSessionIdAtLastCapture != observation.runtimeSessionId ||
                (previous.lastListenerConnectionId != null && previous.lastListenerConnectionId != observation.listenerConnectionId)
            )
        val ambiguousReplacement = previous != null && !previous.isRemoved && connectionGap &&
            (previous.title != title || previous.body != body)
        val isNew = previous == null || previous.isRemoved || ambiguousReplacement
        val item = if (isNew) {
            NotificationItemEntity(
                sourcePackage = identity.sourcePackage,
                sourceUserRef = identity.sourceUserRef,
                systemKey = identity.systemKey,
                notificationId = identity.notificationId,
                tag = identity.tag,
                lifecycleGeneration = if (previous == null) 0 else previous.lifecycleGeneration + 1,
                firstReceivedAtEpochMillis = observation.observedAtEpochMillis,
                lastUpdatedAtEpochMillis = observation.observedAtEpochMillis,
                sortTimeEpochMillis = observation.observedAtEpochMillis,
                originalPostTimeEpochMillis = content.originalPostTimeEpochMillis,
                title = title,
                body = body,
                sourceLabelSnapshot = content.sourceLabelSnapshot,
                contentFingerprint = fingerprint,
                normalizationWarnings = content.warnings.joinToString(",") { it.name },
                normalizationStrategyVersion = content.strategyVersion,
                titleOriginalCodePoints = titleSize,
                bodyOriginalCodePoints = bodySize,
                hadContentIntent = observation.actionCapabilities.hasContentIntent,
                contentIntentCreatorPackage = observation.actionCapabilities.contentIntentCreatorPackage,
                notificationActionCount = observation.actionCapabilities.notificationActionCount,
                isRemoved = false,
                removedAtEpochMillis = null,
                lastRemovalReason = null,
                lastCallbackKind = observation.callbackKind.name,
                runtimeSessionIdAtLastCapture = observation.runtimeSessionId,
                lastListenerConnectionId = observation.listenerConnectionId,
                lastObservedPostTimeEpochMillis = observation.postTimeEpochMillis,
                identityUncertain = ambiguousReplacement,
            )
        } else {
            requireNotNull(previous).copy(
                notificationId = identity.notificationId,
                tag = identity.tag,
                lastUpdatedAtEpochMillis = maxOf(
                    previous.lastUpdatedAtEpochMillis,
                    observation.observedAtEpochMillis,
                ),
                sortTimeEpochMillis = previous.sortTimeEpochMillis,
                originalPostTimeEpochMillis =
                    previous.originalPostTimeEpochMillis ?: content.originalPostTimeEpochMillis,
                title = title,
                body = body,
                sourceLabelSnapshot = content.sourceLabelSnapshot ?: previous.sourceLabelSnapshot,
                contentFingerprint = fingerprint,
                normalizationWarnings = content.warnings.joinToString(",") { it.name },
                normalizationStrategyVersion = content.strategyVersion,
                titleOriginalCodePoints = titleSize,
                bodyOriginalCodePoints = bodySize,
                hadContentIntent = observation.actionCapabilities.hasContentIntent,
                contentIntentCreatorPackage = observation.actionCapabilities.contentIntentCreatorPackage,
                notificationActionCount = observation.actionCapabilities.notificationActionCount,
                lastCallbackKind = observation.callbackKind.name,
                runtimeSessionIdAtLastCapture = observation.runtimeSessionId,
                lastListenerConnectionId = observation.listenerConnectionId,
                lastObservedPostTimeEpochMillis = observation.postTimeEpochMillis,
            )
        }
        return NotificationMergeOutcome(item, isNew)
    }

    fun hasSameCapturedState(
        previous: NotificationItemEntity,
        candidate: NotificationItemEntity,
    ): Boolean =
        previous.sourcePackage == candidate.sourcePackage &&
            previous.sourceUserRef == candidate.sourceUserRef &&
            previous.systemKey == candidate.systemKey &&
            previous.notificationId == candidate.notificationId &&
            previous.tag == candidate.tag &&
            previous.lifecycleGeneration == candidate.lifecycleGeneration &&
            previous.firstReceivedAtEpochMillis == candidate.firstReceivedAtEpochMillis &&
            previous.sortTimeEpochMillis == candidate.sortTimeEpochMillis &&
            previous.originalPostTimeEpochMillis == candidate.originalPostTimeEpochMillis &&
            previous.title == candidate.title &&
            previous.body == candidate.body &&
            previous.sourceLabelSnapshot == candidate.sourceLabelSnapshot &&
            previous.contentFingerprint == candidate.contentFingerprint &&
            previous.normalizationWarnings == candidate.normalizationWarnings &&
            previous.normalizationStrategyVersion == candidate.normalizationStrategyVersion &&
            previous.titleOriginalCodePoints == candidate.titleOriginalCodePoints &&
            previous.bodyOriginalCodePoints == candidate.bodyOriginalCodePoints &&
            previous.hadContentIntent == candidate.hadContentIntent &&
            previous.contentIntentCreatorPackage == candidate.contentIntentCreatorPackage &&
            previous.notificationActionCount == candidate.notificationActionCount &&
            previous.isRemoved == candidate.isRemoved &&
            previous.removedAtEpochMillis == candidate.removedAtEpochMillis &&
            previous.lastRemovalReason == candidate.lastRemovalReason &&
            previous.lastListenerConnectionId == candidate.lastListenerConnectionId &&
            previous.lastObservedPostTimeEpochMillis == candidate.lastObservedPostTimeEpochMillis &&
            previous.identityUncertain == candidate.identityUncertain
}
