package dev.qixils.acropalypse

import com.amazonaws.HttpMethod
import com.amazonaws.SdkClientException
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.CreateBucketRequest
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.events.onButton
import dev.minn.jda.ktx.events.onCommand
import dev.minn.jda.ktx.events.onStringSelect
import dev.minn.jda.ktx.interactions.commands.*
import dev.minn.jda.ktx.interactions.components.*
import dev.minn.jda.ktx.jdabuilder.intents
import dev.minn.jda.ktx.jdabuilder.light
import dev.minn.jda.ktx.messages.*
import dev.minn.jda.ktx.util.SLF4J
import kotlinx.coroutines.*
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
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.messages.MessageRequest
import net.dv8tion.jda.internal.utils.PermissionUtil
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

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
    private val scanJob = SupervisorJob()
    private val s3: AmazonS3?
    private val bucket: String?
    private var stopping = false

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
        // load S3 client
        val endpoint = System.getenv("S3_ENDPOINT")
        val region = System.getenv("S3_REGION")
        val accessKeyId = System.getenv("S3_ACCESS_KEY_ID")
        val secretAccessKey = System.getenv("S3_SECRET_ACCESS_KEY")
        bucket = System.getenv("S3_BUCKET")
        if (endpoint == null || region == null || accessKeyId == null || secretAccessKey == null || bucket == null) {
            s3 = null
        } else {
            s3 = AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(endpoint, region))
                .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials(accessKeyId, secretAccessKey)))
                .build()!!
            if (!s3.doesBucketExistV2(bucket)) {
                s3.createBucket(CreateBucketRequest(bucket, region))
            }
        }
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
            slash("opt-out", "Opt out of having your images scanned or deleted")
            if (s3 != null) {
                slash("download", "Fetches a download link for all of your vulnerable images that were deleted")
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
        jda.onButton("purge:yes") { event -> coroutineScope {
            if (event.guild!!.idLong in state.inProgressScans) {
                event.editMessage("I'm already scanning this guild! Please wait until I'm done.").queue()
                return@coroutineScope
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
            launch(scanJob) { scan(event.guild!!) }
        } }
        // count
        jda.onCommand("count") { event -> coroutineScope {
            if (event.guild!!.idLong in state.inProgressScans) {
                event.reply("I'm already scanning this guild! Please wait until I'm done.").setEphemeral(true).queue()
                return@coroutineScope
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
            launch(scanJob) { scan(event.guild!!) }
        } }
        jda.onButton("scan:cancel") { event ->
            state.inProgressScans.remove(event.guild!!.idLong)
            event.editMessage_("The scan has been cancelled.", replace = true).queue()
        }
        // configure
        jda.onCommand("confidence") { event ->
            val level = ScanConfidence.valueOf(event.getOption("level")!!.asString)
            state.deletionThreshold[event.guild!!.idLong] = level
            event.reply_(buildString {
                append("The minimum confidence for deleting images has been set to **")
                append(level.displayName).append("** or higher.\n")
                append("> ").append(level.description)
            }).setEphemeral(true).queue()
        }
        // opt-out
        jda.onCommand("opt-out") { event ->
            val ephemeral = event.isFromGuild
            if (event.user.idLong !in state.optOut) {
                state.optOut.add(event.user.idLong)
                event.reply_("Your images will no longer be scanned for the Acropalypse vulnerability.").setEphemeral(ephemeral).queue()
            } else {
                event.reply(MessageCreate {
                    content = buildString {
                        append("You have already opted out of having your images scanned for the Acropalypse vulnerability.")
                        append("Would you like to opt back in?")
                    }
                    components += row(
                        primary("opt-out:yes", "Yes"),
                        secondary("opt-out:no", "No")
                    )
                }).queue()
            }
        }
        jda.onButton("opt-out:no") { event ->
            event.editMessage_("You remain opted-out from image scanning.", replace = true).queue()
        }
        jda.onButton("opt-out:yes") { event ->
            state.optOut.remove(event.user.idLong)
            event.editMessage_("You have been opted back in to image scanning.", replace = true).queue()
        }
        // download
        if (s3 != null) {
            jda.onCommand("download") { event ->
                if (!event.isFromGuild) {
                    // find all guilds with data available for this user
                    val objects = s3.listObjectsV2(bucket, "archive/")
                    val guilds = objects.objectSummaries
                        .map { it.key.split('/')[1] }
                        .distinct()
                    if (guilds.isEmpty()) {
                        event.reply("You do not currently have any archived images.").queue()
                    } else {
                        event.reply(MessageCreate {
                            content = "From which guild would you like to download your archived images?"
                            components += row(StringSelectMenu("download:guild") {
                                guilds.forEach { guildId ->
                                    val guild = jda.getGuildById(guildId)
                                    if (guild != null) {
                                        option(guild.name, guildId)
                                    }
                                }
                            })
                        }).queue()
                    }
                } else {
                    sendDownloadLink(event, event.guild!!.idLong)
                }
            }
            jda.onStringSelect("download:guild") { event ->
                val guildId = event.values.first().toLong()
                val guild = jda.getGuildById(guildId)
                if (guild == null) {
                    event.reply("That guild no longer exists.").queue()
                } else {
                    sendDownloadLink(event, guildId)
                }
            }
        }
    }

    private suspend fun start() = coroutineScope {
        // wait for load
        logger.atInfo().log("Waiting for ready")
        jda.awaitReady()
        logger.atInfo().log("Ready")
        for (guildId in state.inProgressScans.keys) {
            val guild = jda.getGuildById(guildId)
            if (guild == null) {
                logger.atWarn().log { "Guild $guildId no longer exists, skipping scan" }
            } else {
                logger.atInfo().log { "Resuming scan for ${guild.name} ($guildId)" }
                launch(scanJob) { scan(guild) }
            }
        }
        // wait for commands
        val scanner = java.util.Scanner(System.`in`)
        while (scanner.hasNextLine()) {
            when (scanner.nextLine()) {
                "stop" -> shutdown()
                "save" -> saveState()
                "info" -> logger.atInfo().log { "Currently scanning ${state.inProgressScans.size} guilds" }
                else -> logger.atWarn().log("Unknown command")
            }
        }
    }

    private suspend fun shutdown() {
        logger.atInfo().log("Stopping")
        stopping = true
        delay(60.seconds)
        scanJob.cancelAndJoin()
        jda.shutdown()
        jda.awaitShutdown()
        saveState()
        logger.atInfo().log("Stopped")
        exitProcess(0)
    }

    private fun sendDownloadLink(interaction: IReplyCallback, guildId: Long) {
        if (s3 == null) {
            logger.atWarn().setCause(Exception()).log("#sendDownloadLink called when s3 is null?")
            return
        }
        val key = "archive/${guildId}/${interaction.user.idLong}.zip"
        val expiration: Date = Date.from(Instant.now().plus(1, ChronoUnit.HOURS))
        val url = try {
            s3.generatePresignedUrl(bucket, key, expiration, HttpMethod.GET)
        } catch (e: SdkClientException) {
            null
        }
        if (url == null) {
            interaction.reply("Could not find any archived images for you in ${jda.getGuildById(guildId)!!.name}.")
                .setEphemeral(interaction.isFromGuild).queue()
        } else {
            interaction.reply_(buildString {
                append("Your images from ").append(jda.getGuildById(guildId)!!.name)
                append(" will be available for download at the following link for one hour:\n<")
                append(url).append('>')
            }).setEphemeral(interaction.isFromGuild).queue()
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
            logger.atDebug().log { "Found image with confidence ${confidence.name} in message ${message.jumpUrl}" }
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
        withContext(NonCancellable) {
            try {
                val requester = jda.retrieveUserById(scanState.requester).await()
                requester.openPrivateChannel().await().send(buildString {
                    append("I have finished scanning ").append(guild.name).append(" for vulnerable screenshots. ")
                    val skippedChannels = scanState.channels.filter { it.value.missingPermissions.isNotEmpty() }
                    if (skippedChannels.isNotEmpty()) {
                        append("During my scan, I had to skip the following channels (and their threads) for lack of permissions:\n")
                        for ((channelId, channelState) in skippedChannels) {
                            val missingPermissions = channelState.missingPermissions
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

            // TODO: DM members their removed messages

            // remove scan state
            state.inProgressScans.remove(guild.idLong)
        }
    }

    private suspend fun tryScan(channel: GuildChannel, scanState: ScanState) = coroutineScope {
        logger.atDebug().log { "Possibly scanning channel ${channel.name} (${channel.id})" }
        val threshold = scanState.threshold
        val channelState = scanState.channels.getOrPut(channel.idLong) { ParentChannelScanState() }
        // ensure we have permission to read messages, manage messages, and view message history
        if (channelState.missingPermissions.isNotEmpty())
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
            channelState.missingPermissions += missingPermissions
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
            if (channel.guild.idLong !in state.inProgressScans) {
                logger.atDebug().log { "Aborting scan of ${channel.name} (${channel.id}) due to cancellation for guild ${channel.guild.name} (${channel.guild.id})" }
                return
            }
            if (stopping) return
            logger.atDebug().log { "Scanning messages in ${channel.name} (${channel.id}) after ${channelState.lastMessage}" }
            val history = retryUntilSuccess(7) { channel.getHistoryAfter(channelState.lastMessage, 100).await() }
            if (history.isEmpty) {
                channelState.lastMessage = Long.MAX_VALUE
                break
            }
            withContext(NonCancellable) { // hold on mom I'm scanning
                val messages = history.retrievedHistory.sortedBy { it.idLong }
                channelState.lastMessage = messages.last().idLong
                for (message in messages) { launch {
                    // TODO: stop searching when we hit a message older than discord's implementation of PNG stripping?
                    val result = scanMessage(message, threshold ?: ScanConfidence.CERTAIN)
                    if (threshold != null && result >= threshold) {
                        // TODO: archive message
                        message.delete().queue()
                        logger.atDebug().log { "Deleted message in #${channel.name} in ${channel.guild.name}: ${message.jumpUrl}" }
                    }
                } }
            }
        }
    }
}

fun <T : Comparable<T>> max(a: T, b: T): T {
    return if (a > b) a else b
}
