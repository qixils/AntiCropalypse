package dev.qixils.acropalypse

import kotlinx.serialization.Serializable
import net.dv8tion.jda.api.Permission

@Serializable
data class BotState(
    val inProgressScans: MutableMap<Long, ScanState> = mutableMapOf(), // map of guild ID to scan state
    val finishedScans: MutableMap<Long, ScanConfidence> = mutableMapOf(),
    val deletionThreshold: MutableMap<Long, ScanConfidence> = mutableMapOf(), // map of guild ID to deletion threshold
    val optOut: MutableMap<Long, MutableSet<OptOutFlag>> = mutableMapOf(), // set of user IDs that have opted out of scanning
)

@Serializable
enum class OptOutFlag {
    ARCHIVING,
    EVERYTHING;
}

@Serializable
data class ScanState(
    val requester: Long,
    val threshold: ScanConfidence? = null,
    var closing: ClosingState? = null,
    val channels: MutableMap<Long, ParentChannelScanState> = mutableMapOf(),
    val tally: MutableMap<ScanConfidence, Int> = mutableMapOf(),
) {
    init {
        ScanConfidence.values().forEach {
            if (it !in tally && (threshold == null || it <= threshold))
                tally[it] = 0
        }
    }

    fun tally(confidence: ScanConfidence) {
        tally[confidence] = (tally[confidence] ?: 0) + 1
    }
}

@Serializable
data class ClosingState(
    var requesterMessaged: Boolean = false,
    val archivesUploaded: MutableSet<Long> = mutableSetOf(),
    val archivesMessaged: MutableSet<Long> = mutableSetOf(),
    val archivesNotMessaged: MutableSet<Long> = mutableSetOf(), // archive was uploaded but unable to be messaged
) {
    val archivesTriedMessage = archivesMessaged + archivesNotMessaged
}

interface ChannelScanState {
    var lastMessage: Long
}

@Serializable
data class ParentChannelScanState(
    val missingPermissions: MutableSet<Permission> = mutableSetOf(),
    val threads: MutableMap<Long, ThreadScanState> = mutableMapOf(),
    override var lastMessage: Long = 0,
) : ChannelScanState

@Serializable
data class ThreadScanState(
    override var lastMessage: Long = 0,
) : ChannelScanState
