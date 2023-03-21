package dev.qixils.acropalypse

import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.events.onButton
import dev.minn.jda.ktx.events.onCommand
import dev.minn.jda.ktx.interactions.commands.*
import dev.minn.jda.ktx.interactions.components.*
import dev.minn.jda.ktx.jdabuilder.intents
import dev.minn.jda.ktx.jdabuilder.light
import dev.minn.jda.ktx.messages.*
import dev.minn.jda.ktx.util.SLF4J
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer
import net.dv8tion.jda.api.entities.channel.attribute.IThreadContainer
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.messages.MessageRequest
import net.dv8tion.jda.internal.utils.PermissionUtil
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@OptIn(ExperimentalSerializationApi::class)
object Bot {
    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking {
            start()
        }
        // TODO: allow graceful shutdown
    }

    private val urlPattern = Pattern.compile("https?://\\S+\\.[Pp][Nn][Gg]")
    private val requiredPermissions = setOf(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)
    private val stateFile = File("acropalypse.cbor")
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val cbor = Cbor {  }
    private val scanner = Scanner()
    private val jda: JDA
    private val logger by SLF4J

    /**
     * The state of the bot.
     * This is loaded from [stateFile] and saved to it when changed.
     */
    val state: BotState = if (stateFile.exists()) cbor.decodeFromByteArray(stateFile.readBytes()) else BotState()

    init {
        logger.atInfo().log("Initializing")
        // load token
        val token = System.getenv("BOT_TOKEN")
        if (token.isNullOrBlank()) {
            throw IllegalArgumentException("BOT_TOKEN environment variable is not set")
        }
        // schedule state saving
        scheduler.scheduleAtFixedRate({
            try {
                saveState()
            } catch (e: Exception) {
                logger.atError().setCause(e).log("Failed to save state")
            }
        }, 1, 1, TimeUnit.MINUTES)
        // build JDA
        jda = light(token, enableCoroutines=true) {
            intents += GatewayIntent.MESSAGE_CONTENT // necessary to scan attachments
            setActivity(Activity.watching("for vulnerable images"))
        }
        // disable @everyone pings
        MessageRequest.setDefaultMentions(emptySet())
        // create commands
        jda.updateCommands {
            slash("purge", "Searches for and deletes vulnerable images according to the configured confidence") {
                restrict(guild = true, Permission.MESSAGE_MANAGE)
            }
            slash("count", "Searches for and counts potentially vulnerable images") {
                restrict(guild = true, Permission.MESSAGE_MANAGE)
            }
            slash("confidence", "Configures how confident the bot should be before deleting an image") {
                restrict(guild = true, Permission.MESSAGE_MANAGE)
                option<String>("level", "The new confidence level", true) {
                    choice(ScanConfidence.LOW.displayName!!, ScanConfidence.LOW.name)
                    choice(ScanConfidence.MEDIUM.displayName!!, ScanConfidence.MEDIUM.name)
                    choice(ScanConfidence.HIGH.displayName!!, ScanConfidence.HIGH.name)
                    choice(ScanConfidence.CERTAIN.displayName!!, ScanConfidence.CERTAIN.name)
                }
            }
        }.queue()
        // purge
        jda.onCommand("purge") { event ->
            if (event.guild!!.idLong in state.inProgressScans) {
                event.reply("I'm already scanning this guild! Please wait until I'm done.").setEphemeral(true).queue()
                return@onCommand
            }
            val confidence = state.deletionThreshold[event.guild!!.idLong] ?: ScanConfidence.DEFAULT
            event.reply(MessageCreate {
                content = buildString {
                    append("Per this guild's configured `/confidence` level, I will delete images with **")
                    append(confidence.displayName).append("** or higher confidence.\n")
                    append("> ").append(confidence.description)
                    append("\nIs this okay?")
                }
                components += row(
                    success("purge:yes", "Yes"),
                    secondary("purge:no", "No")
                )
            }).setEphemeral(true).queue()
        }
        jda.onButton("purge:no") { event ->
            event.editMessage_("Okay, I won't delete any images.", replace = true).queue()
        }
        jda.onButton("purge:yes") { event ->
            if (event.guild!!.idLong in state.inProgressScans) {
                event.editMessage("I'm already scanning this guild! Please wait until I'm done.").queue()
                return@onButton
            }
            event.editMessage(MessageEdit(replace = true) {
                content = buildString {
                    append("Okay, I'll start scanning for images to delete. ")
                    append("This will take a while, so I'll DM you the results when I'm done. ")
                    append("(Don't forget to enable DMs from server members!)")
                }
                components += row(
                    secondary("scan:cancel", "Cancel")
                )
            }).queue()
            val confidence = state.deletionThreshold[event.guild!!.idLong] ?: ScanConfidence.DEFAULT
            state.inProgressScans[event.guild!!.idLong] = ScanState(event.user.idLong, confidence)
            scan(event.guild!!)
        }
        // count
        jda.onCommand("count") { event ->
            if (event.guild!!.idLong in state.inProgressScans) {
                event.reply("I'm already scanning this guild! Please wait until I'm done.").setEphemeral(true).queue()
                return@onCommand
            }
            event.reply(MessageCreate {
                content = buildString {
                    append("Scanning has begun. This will take a while, so I'll DM you the results when I'm done. ")
                    append("(Don't forget to enable DMs from server members!)")
                }
                components += row(
                    secondary("scan:cancel", "Cancel")
                )
            }).setEphemeral(true).queue()
            state.inProgressScans[event.guild!!.idLong] = ScanState(event.user.idLong, null)
            scan(event.guild!!)
        }
        // TODO: support cancelling
        // configure
        jda.onCommand("confidence") { event ->
            val level = ScanConfidence.valueOf(event.getOption("level")!!.asString)
            state.deletionThreshold[event.guild!!.idLong] = level
            event.reply_(buildString {
                append("Okay, I'll delete images with **")
                append(level.displayName).append("** or higher confidence.\n")
                append("> ").append(level.description)
            }).setEphemeral(true).queue()
        }
    }

    private suspend fun start() = coroutineScope {
        // wait for load
        logger.atInfo().log("Waiting for ready")
        jda.awaitReady()
        logger.atInfo().log("Ready")
        val guildIdIterator = state.inProgressScans.keys.iterator()
        while (guildIdIterator.hasNext()) {
            val guildId = guildIdIterator.next()
            val guild = jda.getGuildById(guildId)
            if (guild == null) {
                logger.atWarn().log { "Guild $guildId no longer exists, skipping scan" }
            } else {
                logger.atInfo().log { "Resuming scan for ${guild.name} ($guildId)" }
                launch { scan(guild) }
            }
            guildIdIterator.remove()
        }
    }

    @Synchronized
    private fun saveState(state: BotState) {
        stateFile.writeBytes(cbor.encodeToByteArray(state))
    }

    private fun saveState() {
        saveState(state)
    }

    private suspend fun scanMessage(message: Message, threshold: ScanConfidence = ScanConfidence.CERTAIN): ScanConfidence {
        logger.atDebug().log { "Scanning message ${message.jumpUrl} (threshold: ${threshold.name})" }
        var confidence = ScanConfidence.ERROR
        for (attachment in message.attachments) {
            if (attachment.isImage) {
                logger.atDebug().log { "Scanning image attachment ${attachment.url} in message ${message.jumpUrl}" }
                val scanResult = scanner.scan(attachment.url, threshold)
                if (scanResult == ScanConfidence.ERROR)
                    logger.atDebug().log { "Unexpected error for attachment ${attachment.url} in message ${message.jumpUrl}; possibly corrupted" }
                confidence = max(confidence, scanResult)
                if (confidence >= threshold)
                    return tally(message, confidence)
            }
        }
        for (match in urlPattern.matcher(message.contentRaw).results()) {
            logger.atDebug().log { "Scanning URL ${match.group()} in message ${message.jumpUrl}" }
            confidence = max(confidence, scanner.scan(match.group(), threshold))
            if (confidence >= threshold)
                return tally(message, confidence)
        }
        return tally(message, confidence)
    }

    private fun tally(message: Message, confidence: ScanConfidence): ScanConfidence {
        if (confidence > ScanConfidence.NONE)
            logger.atInfo().log { "Found image with confidence ${confidence.name} in message ${message.jumpUrl}" }
        state.inProgressScans[message.guild.idLong]?.tally(confidence)
        return confidence
    }

    private suspend fun scan(guild: Guild) {
        logger.atInfo().log { "Scanning guild ${guild.name} (${guild.id})" }
        val scanState = state.inProgressScans[guild.idLong] ?: run {
            logger.atError().log { "No scan state found for guild ${guild.name} (${guild.id})" }
            return
        }
        val threshold = scanState.threshold

        // scan channels
        coroutineScope {
            for (channel in guild.channels) {
                launch { tryScan(channel, scanState) }
            }
        }

        // DM requester results
        try {
            val requester = jda.retrieveUserById(scanState.requester).await()
            requester.openPrivateChannel().await().send(buildString {
                append("I have finished scanning ").append(guild.name).append(" for vulnerable screenshots. ")
                val skippedChannels = scanState.channels.filter { it.value.missing.isNotEmpty() }
                if (skippedChannels.isNotEmpty()) {
                    append("During my scan, I had to skip the following channels (and their threads) for lack of permissions:\n")
                    for ((channelId, channelState) in skippedChannels) {
                        val missingPermissions = channelState.missing
                        val channel = guild.getTextChannelById(channelId)
                        append(" - ")
                        if (channel != null)
                            append('#').append(channel.name).append(" (").append(channel.asMention).append(')')
                        else
                            append("<#").append(channelId).append('>')
                        append(" — Missing: ")
                        append(missingPermissions.joinToString { it.getName() })
                        append('\n')
                    }
                }
                if (threshold != null) {
                    val deleted = scanState.tally.filter { it.key >= threshold }.map { it.value }.sum()
                    append("In my scan, I deleted ").append(deleted)
                    if (threshold != ScanConfidence.CERTAIN)
                        append(" potentially")
                    append(" vulnerable screenshots.\n")
                } else {
                    append("Per your request, I did not delete any screenshots.\n")
                }
                append("The full results, where the first column corresponds to the likelihood of an image being vulnerable, are as follows:\n>>> ")
                for ((confidence, count) in scanState.tally) {
                    if (threshold != null && confidence > threshold) break
                    append(confidence.displayName).append(": ").append(count).append('\n')
                }
                if (threshold != null && threshold < ScanConfidence.CERTAIN)
                    append("_To reduce server load, statistics were not collected for confidence levels above the threshold you selected._")
            }).await()
        } catch (e: Exception) {
            logger.atWarn().setCause(e).log { "Failed to message user ${scanState.requester} from guild ${guild.name} (${guild.id})" }
        }

        // remove scan state
        state.inProgressScans.remove(guild.idLong)
    }

    private suspend fun tryScan(channel: GuildChannel, scanState: ScanState) = coroutineScope {
        logger.atDebug().log { "Possibly scanning channel ${channel.name} (${channel.id})" }
        val threshold = scanState.threshold
        val channelState = scanState.channels.getOrPut(channel.idLong) { ParentChannelScanState() }
        // ensure we have permission to read messages, manage messages, and view message history
        if (channelState.missing.isNotEmpty())
            return@coroutineScope
        if (channel !is IPermissionContainer)
            return@coroutineScope
        val requiredPermissions = requiredPermissions.toMutableSet()
        if (threshold != null) requiredPermissions += Permission.MESSAGE_MANAGE
        val missingPermissions = mutableSetOf<Permission>()
        for (permission in requiredPermissions) {
            if (!PermissionUtil.checkPermission(channel, channel.guild.selfMember, permission))
                missingPermissions += permission
        }
        if (missingPermissions.isNotEmpty()) {
            channelState.missing += missingPermissions
            return@coroutineScope
        }
        // scan channel and threads
        logger.atDebug().log { "Scanning channel ${channel.name} (${channel.id})" }
        if (channel is GuildMessageChannel)
            launch { scan(channel, scanState, channelState)}
        if (channel is IThreadContainer) {
            val threads = channel.threadChannels.toMutableList()
            threads += channel.retrieveArchivedPublicThreadChannels().await()
            try {
                threads += channel.retrieveArchivedPrivateJoinedThreadChannels().await() // TODO: search through more private threads
            } catch (e: ErrorResponseException) {
                if (e.errorResponse != ErrorResponse.INVALID_CHANNEL_TYPE)
                    logger.atError().setCause(e).log("Unexpected error while retrieving private threads")
            }
            for (thread in threads) {
                val threadScanState = channelState.threads.getOrPut(thread.idLong) { ThreadScanState() }
                launch { scan(thread, scanState, threadScanState) }
            }
        }
    }

    private suspend fun scan(channel: GuildMessageChannel, scanState: ScanState, channelState: ChannelScanState) {
        if (channelState.lastMessage == Long.MAX_VALUE) return
        logger.atDebug().log { "Scanning channel ${channel.name} (${channel.id})" }
        val threshold = scanState.threshold
        while (true) {
            logger.atDebug().log { "Scanning messages in ${channel.name} (${channel.id}) after ${channelState.lastMessage}" }
            val messages = retryUntilSuccess(7) { channel.getHistoryAfter(channelState.lastMessage, 100).await() }
            if (messages.isEmpty) {
                channelState.lastMessage = Long.MAX_VALUE
                break
            }
            for (message in messages.retrievedHistory.sortedBy { it.idLong }) {
                // TODO: async message scanning? (needs graceful shutdown)
                // TODO: stop searching when we hit a message older than discord's implementation of PNG stripping?
                val result = scanMessage(message, threshold ?: ScanConfidence.CERTAIN)
                if (threshold != null && result >= threshold) {
                    // TODO: archive message
                    message.delete().queue()
                    logger.atDebug().log { "Deleted message in #${channel.name} in ${channel.guild.name}: ${message.jumpUrl}" }
                }
                channelState.lastMessage = message.idLong
            }
        }
    }
}

fun <T : Comparable<T>> max(a: T, b: T): T {
    return if (a > b) a else b
}
