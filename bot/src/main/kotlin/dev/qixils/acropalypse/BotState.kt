package dev.qixils.acropalypse

import kotlinx.serialization.Serializable
import net.dv8tion.jda.api.Permission

@Serializable
data class BotState(
    val inProgressScans: MutableMap<Long, ScanState> = mutableMapOf(), // map of guild ID to scan state
    val deletionThreshold: MutableMap<Long, ScanConfidence> = mutableMapOf(), // map of guild ID to deletion threshold
    val optOut: MutableSet<Long> = mutableSetOf(), // set of user IDs that have opted out of scanning
)

@Serializable
data class ScanState(
    val requester: Long,
    val threshold: ScanConfidence? = null,
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
