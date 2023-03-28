package dev.qixils.anticropalypse

import com.amazonaws.HttpMethod
import com.amazonaws.SdkClientException
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.GetObjectRequest
import com.amazonaws.services.s3.model.S3ObjectSummary
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.events.CoroutineEventListener
import dev.minn.jda.ktx.events.CoroutineEventManager
import dev.minn.jda.ktx.events.listener
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer
import net.dv8tion.jda.api.entities.channel.attribute.IThreadContainer
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.utils.messages.MessageRequest
import net.dv8tion.jda.internal.utils.PermissionUtil
import java.net.URI
import java.net.URL
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.io.path.Path
import kotlin.system.exitProcess
import kotlin.time.Duration

@OptIn(ExperimentalSerializationApi::class)
object Bot {
    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking {
            start()
        }
    }

    private val urlPattern = Pattern.compile("https?://\\S+\\.[Pp][Nn][Gg]")
    private val requiredPermissions = setOf(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)
    private val rootDir = Path("data")
    private val archiveDir = rootDir.resolve("archive").apply { Files.createDirectories(this) }
    private val stateFile = rootDir.resolve("state.cbor")
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val cbor = Cbor {  }
    private val scanner = Scanner()
    private val jda: JDA
    private val logger by SLF4J
    private val scanJob = SupervisorJob()
    private val s3: AmazonS3?
    private val bucket: String
    private var stopping = false
    private val shutdownThread = Thread(::shutdown, "Shutdown")
    private val archiveLocks = mutableMapOf<String, Mutex>()
    private val archiveMetaLock = Mutex()
    private val cutoff = OffsetDateTime.of(2023, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC)

    /**
     * The state of the bot.
     * This is loaded from [stateFile] and saved to it when changed.
     */
    val state: BotState = if (Files.exists(stateFile)) {
        cbor.decodeFromByteArray(Files.readAllBytes(stateFile))
    } else {
        BotState()
    }

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
        var endpoint = System.getenv("S3_ENDPOINT")
        val region = System.getenv("S3_REGION")
        val accessKey = System.getenv("S3_ACCESS_KEY")
        val secretKey = System.getenv("S3_SECRET_KEY")
        bucket = System.getenv("S3_BUCKET")
        if (region == null || accessKey == null || secretKey == null) {
            s3 = null
        } else {
            s3 = AmazonS3ClientBuilder.standard().apply {
                credentials = AWSStaticCredentialsProvider(BasicAWSCredentials(accessKey, secretKey))
                if (endpoint == null)
                    this.region = region
                else {
                    if (!endpoint.startsWith(region))
                        endpoint = "$region.$endpoint"
                    setEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(endpoint, region))
                }
            }.build()!!
            if (!s3.doesBucketExistV2(bucket)) {
                s3.createBucket(bucket)
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
            slash("opt-out", "Opts-out of various scanning functionalities") {
                if (s3 != null) {
                    subcommand("archiving", "Opts-out of having your deleted screenshots backed up for you to download")
                }
                subcommand("everything", "Opts-out of having your vulnerable screenshots scanned, deleted, or archived")
            }
            if (s3 != null) {
                slash("download", "Fetches a download link for all of your vulnerable images that were deleted")
                slash("forget-me", "Removes your archives of deleted images")
            }
        }.queue()
        // purge
        jda.onCommand("purge") { event ->
            if (event.guild!!.idLong in state.inProgressScans) {
                event.reply("I'm already scanning this server! Please wait until I'm done.").setEphemeral(true).queue()
                return@onCommand
            }
            val confidence = state.deletionThreshold[event.guild!!.idLong] ?: ScanConfidence.DEFAULT
            val previousScan = state.finishedScans[event.guild!!.idLong]
            event.reply(MessageCreate {
                content = if (previousScan == null || previousScan > confidence) buildString {
                    append("Per this server's configured `/confidence` level, I will delete images with **")
                    append(confidence.displayName).append("** or higher confidence.\n")
                    append("> ").append(confidence.description)
                    append("\nIs this okay?")
                } else buildString {
                    append("This guild has already had vulnerable images scanned for and deleted with **")
                    append(previousScan.displayName).append("** or higher confidence. ")
                    append("A new scan is unlikely to produce new results unless I have been granted access to ")
                    append("channels I was previously unable to reach. ")
                    append("This is because Discord, likely accidentally, started fixing vulnerable images in early ")
                    append("January 2023, so a secondary scan should not find anything new.\n")
                    append("However, if you would like to continue with scanning for and deleting images with **")
                    append(confidence.displayName).append("** or higher `/confidence`, please confirm below.")
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
                event.editMessage("I'm already scanning this server! Please wait until I'm done.").queue()
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
                event.reply("I'm already scanning this server! Please wait until I'm done.").setEphemeral(true).queue()
                return@coroutineScope
            }
            val hasPreviousScan = event.guild!!.idLong in state.finishedScans
            val previousScan = state.finishedScans[event.guild!!.idLong]
            event.reply(MessageCreate {
                content = if (!hasPreviousScan) buildString {
                    append("Scanning has begun. This will take a while, so I'll DM you the results when I'm done. ")
                    append("(Don't forget to enable DMs from server members!)")
                } else buildString {
                    append("Scanning has begun. This will take a while, so I'll DM you the results when I'm done.\n")
                    append("Note that this guild has already scanned for vulnerable images")
                    if (previousScan != null)
                        append(" with **").append(previousScan.displayName).append("** or higher confidence")
                    append(". A new scan is unlikely to produce new results unless I have been granted access to ")
                    append("channels I was previously unable to reach. ")
                    append("This is because Discord, likely accidentally, started fixing vulnerable images in early ")
                    append("January 2023, so a secondary scan should not find anything new.\n")
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
        jda.onSubCommand("opt-out everything") { event ->
            val ephemeral = event.isFromGuild
            val userId = event.user.idLong
            val flags = state.optOut[userId] ?: mutableSetOf()
            if (!flags.contains(OptOutFlag.EVERYTHING)) {
                flags.clear()
                flags.add(OptOutFlag.EVERYTHING)
                state.optOut[userId] = flags
                event.reply("Your images will no longer be scanned for the Acropalypse vulnerability.").setEphemeral(ephemeral).queue()
            } else {
                event.reply(MessageCreate {
                    content = buildString {
                        append("You have already opted out of having your images scanned for the Acropalypse vulnerability. ")
                        append("Would you like to opt back in?")
                    }
                    components += row(
                        primary("opt-out:everything:yes", "Yes"),
                        secondary("opt-out:everything:no", "No")
                    )
                }).setEphemeral(ephemeral).queue()
            }
        }
        jda.onButton("opt-out:everything:no") { event ->
            event.editMessage_("You remain opted-out from image scanning.", replace = true).queue()
        }
        jda.onButton("opt-out:everything:yes") { event ->
            state.optOut.remove(event.user.idLong)
            event.editMessage_("You have been opted back in to image scanning.", replace = true).queue()
        }
        if (s3 != null) {
            jda.onSubCommand("opt-out archiving") { event ->
                val ephemeral = event.isFromGuild
                val userId = event.user.idLong
                val flags = state.optOut[userId] ?: mutableSetOf()
                if (flags.contains(OptOutFlag.EVERYTHING)) {
                    event.reply("You have already opted out of all scanning. Please use `/opt-out everything` if you wish to opt back in.").setEphemeral(ephemeral).queue()
                } else if (flags.contains(OptOutFlag.ARCHIVING)) {
                    event.reply(MessageCreate {
                        content = buildString {
                            append("You have already opted out of having your images backed up before deletion. ")
                            append("Would you like to opt back in?")
                        }
                        components += row(
                            primary("opt-out:archiving:yes", "Yes"),
                            secondary("opt-out:archiving:no", "No")
                        )
                    }).setEphemeral(ephemeral).queue()
                } else {
                    flags.add(OptOutFlag.ARCHIVING)
                    state.optOut[userId] = flags
                    event.reply("Your images will no longer be backed up before being deleted.").setEphemeral(ephemeral).queue()
                }
            }
            jda.onButton("opt-out:archiving:no") { event ->
                event.editMessage_("You remain opted-out from image archiving.", replace = true).queue()
            }
            jda.onButton("opt-out:archiving:yes") { event ->
                val userId = event.user.idLong
                if (state.optOut[userId]?.contains(OptOutFlag.EVERYTHING) == true) {
                    event.editMessage_("You have already opted out of all scanning. Please use `/opt-out everything` if you wish to opt back in.", replace = true).queue()
                    return@onButton
                }
                state.optOut[userId]?.remove(OptOutFlag.ARCHIVING)
                if (state.optOut[userId]?.isEmpty() == true)
                    state.optOut.remove(userId)
                event.editMessage_("You have been opted back in to image archiving.", replace = true).queue()
            }
        }
        if (s3 != null) {
            // download
            jda.onCommand("download") { event ->
                val reply = event.deferReply(event.isFromGuild)
                if (!event.isFromGuild) {
                    // find all guilds with data available for this user
                    val objects = s3.listObjectsV2(bucket, "archive/")
                    val guilds = objects.objectSummaries
                        .filter { it.key.split('/')[2].substringBefore('.').toLong() == event.user.idLong }
                        .map { it.key.split('/')[1] }
                        .distinct()
                    if (guilds.isEmpty()) {
                        reply.setContent("You do not currently have any archived images.")
                    } else if (guilds.size == 1) {
                        setDownloadLink(event, reply, guilds.first().toLong())
                    } else {
                        reply.setContent("From which server would you like to download your archived images?")
                        reply.setComponents(row(StringSelectMenu("download:guild") {
                            for (guildId in guilds) {
                                val guild = jda.getGuildById(guildId)?.name ?: "<unknown server>"
                                option(guild, guildId)
                            }
                        }))
                    }
                } else {
                    setDownloadLink(event, reply, event.guild!!.idLong)
                }
                reply.queue()
            }
            jda.onStringSelect("download:guild") { event ->
                val reply = event.deferEdit().setReplace(true)
                val guildId = event.values.first().toLong()
                val guild = jda.getGuildById(guildId)
                if (guild == null)
                    reply.setContent("That server no longer exists.")
                else
                    setDownloadLink(event, reply, guildId)
                reply.queue()
            }
            // forget-me
            jda.onCommand("forget-me") { event ->
                val ephemeral = event.isFromGuild
                val userId = event.user.idLong
                val toDelete = archivesForUser(userId)
                if (toDelete.isEmpty()) {
                    event.reply(buildString {
                        append("You do not currently have any archived images to delete. ")
                        append("If you wish to prevent archiving going forwards, please use `/opt-out archiving`.")
                    }).setEphemeral(ephemeral).queue()
                } else {
                    event.reply(MessageCreate {
                        content = buildString {
                            append("Are you sure you want to delete all of your archived images")
                            if (toDelete.size > 1)
                                append(" across ${toDelete.size} servers")
                            append("? This action cannot be undone.")
                        }
                        components += row(
                            primary("forget-me:yes", "Yes"),
                            secondary("forget-me:no", "No")
                        )
                    }).setEphemeral(ephemeral).queue()
                }
            }
            jda.onButton("forget-me:yes") { event ->
                val userId = event.user.idLong
                val toDelete = archivesForUser(userId)
                if (toDelete.isEmpty()) {
                    event.editMessage_("You do not currently have any archived images to delete.", replace = true).queue()
                } else {
                    val callback = event.deferEdit()
                    withContext(Dispatchers.IO) { toDelete.forEach { s3.deleteObject(bucket, it.key) } }
                    callback.setContent("Your archived images have been deleted.").setReplace(true).queue()
                }
            }
            jda.onButton("forget-me:no") { event ->
                event.editMessage_("Your archived images have not been deleted.", replace = true).queue()
            }
        }

        if (s3 == null) logger.atInfo().log("S3 not configured; archiving disabled")

        // shutdown hook
        Runtime.getRuntime().addShutdownHook(shutdownThread)
    }

    private suspend fun start() = coroutineScope {
        // wait for load
        logger.atInfo().log("Waiting for ready")
        jda.awaitReady()
        logger.atInfo().log("=====")
        logger.atInfo().log { "Logged in as ${jda.selfUser.asTag} (${jda.selfUser.id})" }
        logger.atInfo().log { "Serving ${jda.guilds.size} guilds" }

        val guildIterator = state.inProgressScans.keys.iterator()
        while (guildIterator.hasNext()) {
            val guildId = guildIterator.next()
            val guild = jda.getGuildById(guildId)
            if (guild == null) {
                logger.atWarn().log { "Guild $guildId no longer exists, skipping scan" }
                guildIterator.remove()
            } else {
                logger.atInfo().log { "Resuming scan for ${guild.name} ($guildId)" }
                launch(scanJob) { scan(guild) }
            }
        }
        // wait for commands
        launch(Dispatchers.IO) {
            val scanner = java.util.Scanner(System.`in`)
            while (scanner.hasNextLine()) {
                when (scanner.nextLine()) {
                    "stop" -> exitProcess(0)
                    "save" -> {
                        saveState()
                        logger.atInfo().log("Saved state")
                    }
                    "info" -> logger.atInfo().log { "Currently scanning ${state.inProgressScans.size} guilds" }
                    else -> logger.atWarn().log("Unknown command")
                }
            }
        }
    }

    private fun shutdown() {
        logger.atInfo().log("Stopping")
        stopping = true
        Thread.sleep(10 * 1000)
        runBlocking { scanJob.cancelAndJoin() }
        jda.shutdown()
        jda.awaitShutdown()
        saveState()
        logger.atInfo().log("Stopped")
    }

    private fun setDownloadLink(interaction: IReplyCallback, reply: MessageRequest<*>, guildId: Long) {
        if (s3 == null) {
            logger.atWarn().setCause(Exception()).log("#sendDownloadLink called when s3 is null?")
            return
        }
        val guildName = jda.getGuildById(guildId)?.name ?: "<unknown server>"
        val key = "archive/${guildId}/${interaction.user.idLong}.zip"
        val url: URL?
        = if (s3.doesObjectExist(bucket, key)) {
            val expiration: Date = Date.from(Instant.now().plus(1, ChronoUnit.HOURS))
            try {
                s3.generatePresignedUrl(bucket, key, expiration, HttpMethod.GET)
            } catch (e: SdkClientException) {
                null
            }
        } else {
            null
        }
        if (url == null) {
            reply.setContent("Could not find any archived images for you in $guildName.")
        } else {
            reply.setContent(buildString {
                append("Your images from ").append(guildName)
                append(" will be available for download at the following link for one hour:\n<")
                append(url).append('>')
            })
        }
    }

    private fun archivesForUser(userId: Long): List<S3ObjectSummary> {
        assert(s3 != null) { "#filesForUser should not be called when s3 is null" }
        return s3!!.listObjectsV2(bucket, "archive/").objectSummaries
            .filter { it.key.split('/')[2].substringBefore('.').toLong() == userId }
    }

    @Synchronized
    private fun saveState() {
        Files.write(stateFile, cbor.encodeToByteArray(state))
    }

    private suspend fun scanMessage(message: Message, threshold: ScanConfidence = ScanConfidence.CERTAIN): ScanConfidence {
        if (state.isOptedOut(message.author.idLong)) {
            logger.atDebug().log { "Skipping message ${message.jumpUrl} from opt-out user ${message.author.asTag}" }
            return tally(message, ScanConfidence.OPTED_OUT)
        }
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
        val scanState = state.inProgressScans[guild.idLong]
        if (scanState == null) {
            logger.atError().log { "No scan state found for guild ${guild.name} (${guild.id})" }
            return
        }
        val threshold = scanState.threshold

        if (threshold != null && s3 != null) withContext(Dispatchers.IO) {
            val archivePath = archiveDir.resolve(guild.id)
            if (!Files.exists(archivePath)) {
                Files.createDirectories(archivePath)
                // download existing archives for this guild
                for (archive in s3.listObjectsV2(bucket, "archive/${guild.id}/").objectSummaries) {
                    val archiveFile = archivePath.resolve(archive.key.split('/').last())
                    logger.atDebug().log { "Downloading archive ${archive.key} to $archiveFile" }
                    s3.getObject(GetObjectRequest(bucket, archive.key), archiveFile.toFile())
                }
            }
        }

        // scan channels
        if (scanState.closing == null) {
            coroutineScope {
                for (channel in guild.channels) {
                    launch { tryScan(channel, scanState) }
                }
            }
            if (stopping) return
            scanState.closing = ClosingState()
        }

        val closingState = scanState.closing!!

        // DM requester results
        val requester = try {
            jda.retrieveUserById(scanState.requester).await()
        } catch (e: ErrorResponseException) {
            logger.atWarn().log { "Could not find requester ${scanState.requester} for guild ${guild.name} (${guild.id})" }
            null
        }
        if (!closingState.requesterMessaged && requester != null) {
            try {
                val requesterChannel = retryUntilSuccess<PrivateChannel, Exception>(
                    0,
                    { e ->
                        if (e !is ErrorResponseException) throw e
                        if (e.errorResponse != ErrorResponse.OPEN_DM_TOO_FAST) throw e
                    },
                    { requester.openPrivateChannel().await() }
                )
                requesterChannel.splitAndSend(buildString {
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
                        append(confidence.displayName)
                        if (threshold != null && confidence == threshold)
                            append(" or higher")
                        append(": ").append(count).append('\n')
                    }
                    if (threshold != null && threshold < ScanConfidence.CERTAIN)
                        append("_To reduce server load, statistics were not collected for confidence levels above the threshold you selected._")
                }).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.atWarn().setCause(e)
                    .log { "Failed to message user ${scanState.requester} from guild ${guild.name} (${guild.id})" }
            }
            scanState.closing!!.requesterMessaged = true
        }

        // DM members their removed messages
        if (s3 != null && threshold != null) { withContext(Dispatchers.IO) {
            val archivePath = archiveDir.resolve(guild.id)
            for (zipPath in Files.list(archivePath)) {
                // get user id
                val userId = zipPath.fileName.toString().substringBefore('.').toLong()
                if (userId in closingState.archivesTriedMessage) continue
                if (state.isOptedOut(userId, OptOutFlag.ARCHIVING)) continue
                // upload archive to s3
                // TODO: skip upload for small archives (<8MB)
                if (userId !in closingState.archivesUploaded) {
                    s3.putObject(
                        bucket,
                        "archive/${guild.id}/${zipPath.fileName}",
                        zipPath.toFile()
                    )
                }
                // retrieve user
                val user: User
                try {
                    user = jda.retrieveUserById(userId).await()
                } catch (e: ErrorResponseException) {
                    logger.atWarn().log { "Failed to retrieve user $userId from guild ${guild.name} (${guild.id})" }
                    closingState.archivesNotMessaged.add(userId)
                    continue
                }
                // send message
                val channel = retryUntilSuccess<PrivateChannel, Exception>(
                    0,
                    { e ->
                        if (e !is ErrorResponseException) throw e
                        if (e.errorResponse != ErrorResponse.OPEN_DM_TOO_FAST) throw e
                    },
                    { user.openPrivateChannel().await() }
                )
                val isFirstDM = channel.latestMessageIdLong == 0L && channel.iterableHistory.takeAsync(1).await().isEmpty()
                val message = if (isFirstDM) buildString {
                    append("Hi there! A server you are or were in, ").append(guild.name)
                    append(", requested that I scan their server for and delete certain old screenshots. ")
                    append("Specifically, I have deleted screenshots that I found to be vulnerable to a ")
                    append("recently discovered exploit which could allow bad actors to extract the original ")
                    append("image for an edited screenshot on certain devices (Google Pixel, Windows Snipping ")
                    append("Tool, etc.) For example, if you've ever taken a cropped screenshot of a purchase ")
                    append("confirmation email or an email from your work/school, a bad actor could extract ")
                    append("your original screenshot with potentially identifying information like your name, ")
                    append("phone number, address, etc.\n\n")
                    append("During this scan of the server, I found and deleted several screenshots of yours ")
                    append("that were susceptible to this vulnerability. If at any time you would like to ")
                    append("download these screenshots then please run the `/download` command to receive a ")
                    append("temporary download link. Otherwise, you may run `/forget-me` to remove all of ")
                    append("your archived screenshots and `/opt-out` to opt-out of having your messages ")
                    append("deleted and/or archived in the future.")
                } else if (user.idLong == requester?.idLong) buildString {
                    append("Ah, I see why you needed my help! It seems I found and deleted several screenshots ")
                    append("of yours. Well, like everyone else, you can run `/download` to receive a ")
                    append("temporary download link, `/forget-me` to remove all of your archived screenshots, ")
                    append("and/or `/opt-out` to opt-out of having your messages deleted and/or archived in ")
                    append("the future.")
                } else buildString {
                    append("Hi again! A server you are or were in, ").append(guild.name)
                    append(", has new archived screenshots available for you. Per usual, you can run ")
                    append("`/download` to download them, `/forget-me` to delete them, and/or ")
                    append("`/opt-out` to not have your images archived/deleted anymore.")
                }
                channel.sendMessage(message).queue(
                    { closingState.archivesMessaged.add(userId) },
                    { e ->
                        // user probably just has DMs disabled, no big deal
                        logger.atDebug().setCause(e).log { "Failed to message user $userId from guild ${guild.name} (${guild.id})" }
                        closingState.archivesNotMessaged.add(userId)
                    }
                )
            }
            // delete archive directory
            Files.walk(archivePath).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        } }

        // remove scan state
        logger.atInfo().log { "Finished scanning guild ${guild.name} (${guild.id})" }
        state.inProgressScans.remove(guild.idLong)
        val previousScan = state.finishedScans[guild.idLong]
        if (previousScan == null)
            state.finishedScans[guild.idLong] = threshold
        else if (threshold != null)
            state.finishedScans[guild.idLong] = min(threshold, previousScan)
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
            threads += channel.retrieveArchivedPublicThreadChannels().takeWhileAsync { true }.await()
            try {
                val privateThreads = if (PermissionUtil.checkPermission(channel, channel.guild.selfMember, Permission.MANAGE_THREADS))
                    channel.retrieveArchivedPrivateThreadChannels()
                else
                    channel.retrieveArchivedPrivateJoinedThreadChannels()
                privateThreads.forEachAsync({
                    threads += it
                    return@forEachAsync true
                }, {
                    // empty exception handler (handled by the await() and catch)
                }).await()
            } catch (e: ErrorResponseException) {
                if (e.errorResponse != ErrorResponse.INVALID_CHANNEL_TYPE)
                    logger.atError().setCause(e).log("Unexpected error while retrieving private threads")
            }
            for (thread in threads) {
                if (thread.isArchived && thread.isLocked) continue // TODO: temp unlock?
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
            val messages = history.retrievedHistory.filter { it.timeCreated.isBefore(cutoff) }.sortedBy { it.idLong }
            if (messages.isEmpty()) {
                channelState.lastMessage = Long.MAX_VALUE
                break
            }
            for (message in messages) {
                if (stopping) return // check for cancellation
                withContext(NonCancellable) {
                    val result = scanMessage(message, threshold ?: ScanConfidence.CERTAIN)
                    if (threshold != null && result >= threshold) {
                        if (s3 != null) {
                            val lockKey = "${message.guild.idLong}/${message.author.idLong}"
                            val lock = archiveMetaLock.withLock { archiveLocks.getOrPut(lockKey) { Mutex() } }
                            try {
                                lock.lock()
                                archive(message)
                                logger.atDebug().log { "Archived message in #${channel.name} in ${channel.guild.name}: ${message.jumpUrl}" }
                            } catch (e: Exception) {
                                logger.atError().setCause(e).log("Failed to archive message")
                            } finally {
                                lock.unlock()
                            }
                        }
                        message.delete().queue({
                            logger.atDebug().log { "Deleted message in #${channel.name} in ${channel.guild.name}: ${message.jumpUrl}" }
                        }, {
                            logger.atError().setCause(it).log { "Failed to delete message: ${message.jumpUrl}" }
                        })
                    }
                    channelState.lastMessage = message.idLong
                }
            }
        }
    }

    private suspend fun archive(message: Message) {
        val channel = message.channel as GuildMessageChannel
        val guild = channel.guild
        val author = message.author
        // add TXT file with message content & image attachments to ZIP
        val path = archiveDir.resolve(Path(guild.id, "${author.id}.zip"))
        val uri = URI.create("jar:" + path.toUri()) // TODO: this seems redundant
        withContext(Dispatchers.IO) {
            FileSystems.newFileSystem(uri, mapOf("create" to true, "encoding" to "UTF-8")).use { fs ->
                val txtPath = fs.getPath("${message.id}.txt")
                Files.writeString(txtPath, buildString {
                    append("Channel: #").append(channel.name).append(" (").append(channel.id).append(")\n")
                    append("Timestamp: ").append(message.timeCreated.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" UTC\n")
                    append("URL: ").append(message.jumpUrl).append('\n')
                    if (message.contentRaw.isNotEmpty())
                        append("Message: ").append(message.contentRaw)
                    else
                        append("(no message content)")
                })
                for (attachment in message.attachments) {
                    val attachmentPath = fs.getPath("${message.id}-${attachment.fileName}")
                    try {
                        scanner.strip(attachment.proxy.download().await()).use {
                            Files.copy(it, attachmentPath)
                        }
                    } catch (_: FileAlreadyExistsException) {
                        // unimportant error, probably just failed to delete the message in the original purge
                        logger.atDebug().log { "Failed to archive attachment ${attachment.url} as file already exists" }
                    } catch (e: Exception) {
                        logger.atWarn().setCause(e).log { "Failed to archive attachment ${attachment.url}" }
                    }
                }
            }
        }
    }
}

fun <T : Comparable<T>> max(a: T, b: T): T {
    return if (a > b) a else b
}

fun <T : Comparable<T>> min(a: T, b: T): T {
    return if (a < b) a else b
}

/**
 * Requires [CoroutineEventManager] to be used!
 *
 * Opens an event listener scope for simple hooking. This is a special listener which is used to listen for commands!
 *
 * ## Example
 *
 * ```kotlin
 * jda.onCommand("ping") { event ->
 *     event.reply("Pong!").queue()
 * }
 * ```
 *
 * @param[name] The command name
 * @param[timeout] The timeout [Duration] to use for this listener, or null to use the default from the event manager
 * @param[consumer] The event consumer function
 *
 * @return[CoroutineEventListener] The created event listener instance (can be used to remove later)
 */
fun JDA.onSubCommand(name: String, timeout: Duration? = null, consumer: suspend CoroutineEventListener.(GenericCommandInteractionEvent) -> Unit) = listener<GenericCommandInteractionEvent>(timeout=timeout) {
    if (it.fullCommandName == name)
        consumer(it)
}

private val whitespace = Pattern.compile("\\s")

fun MessageChannel.splitAndSend(text: String): RestAction<*> {
    var current = ""
    var action: RestAction<*>? = null
    for (line in text.split("\n")) {
        if ((current.length + line.length + 1) <= 2000) {
            current += '\n' + line
            continue
        }
        if (current.isNotEmpty()) {
            action = action?.flatMap { sendMessage(current) } ?: sendMessage(current)
        }
        current = line
    }
    action = action?.flatMap { sendMessage(current) } ?: sendMessage(current)
    return action
}
