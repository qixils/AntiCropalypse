package dev.qixils.acropalypse

import kotlinx.serialization.Serializable
import net.dv8tion.jda.api.Permission

@Serializable
data class BotState(
    var inProgressScans: MutableMap<Long, ScanState> = mutableMapOf(), // map of guild ID to scan state
    var deletionThreshold: MutableMap<Long, ScanConfidence> = mutableMapOf(), // map of guild ID to deletion threshold
    var disabledScans: MutableMap<Long, Long> = mutableMapOf(), // map of user ID to instant of disabling
)

@Serializable
data class ScanState(
    val requester: Long,
    val threshold: ScanConfidence? = null,
    val skippedChannels: MutableMap<Long, Set<Permission>> = mutableMapOf(), // map of channel ID to missing permissions
    val tally: MutableMap<ScanConfidence, Int> = mutableMapOf(),
    var lastChannel: Long = 0,
    var lastThread: Long = 0,
    var lastMessage: Long = 0,
)
