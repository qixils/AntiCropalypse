package dev.qixils.acropalypse

import dev.minn.jda.ktx.util.SLF4J
import dev.minn.jda.ktx.util.awaitWith
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

private val logger by SLF4J("Scanner")

fun ByteArray.toInt(big: Boolean): Int {
    return toLong(big).toInt()
}

fun ByteArray.toLong(big: Boolean): Long {
    // convert to signed long
    var result = 0L
    for (i in indices) {
        result = if (big)
            result shl 8 or (this[i].toLong() and 0xff)
        else
            result or (this[i].toLong() and 0xff shl i * 8)
    }
    return result
}

fun ByteArray.crc32(): Long {
    val crc = CRC32()
    crc.update(this)
    return crc.value
}

fun ByteArray.indexOf(bytes: ByteArray): Int {
    for (i in 0..size - bytes.size) {
        var found = true
        for (j in bytes.indices) {
            if (this[i + j] != bytes[j]) {
                found = false
                break
            }
        }
        if (found)
            return i
    }
    return -1
}

fun InputStream.parsePNGChunk(): Pair<ByteArray, ByteArray> {
    val size = readNBytes(4).toInt(true)
    val ctype = readNBytes(4)
    val body = readNBytes(size)
    val crc = readNBytes(4).toLong(true)
    val computedCRC = (ctype + body).crc32()
    if (crc != computedCRC)
        throw IllegalStateException("CRC32 mismatch")
    return ctype to body
}

fun Int.toBytes(size: Int, big: Boolean): ByteArray {
    val result = ByteArray(size)
    for (i in 0 until size) {
        result[i] = if (big)
            (this shr (size - i - 1) * 8).toByte()
        else
            (this shr i * 8).toByte()
    }
    return result
}

/**
 * A specification of how confident the algorithm is that an image is vulnerable.
 */
enum class ScanConfidence(
    val displayName: String? = null,
    val description: String? = null,
) {

    /**
     * The image could not be scanned due to an error, likely a failure to download the image.
     */
    ERROR("Failed to download"),

    /**
     * No vulnerability was detected.
     */
    NONE("None"),

    /**
     * Trailing data was detected, although it may not be related to the vulnerability.
     */
    LOW("Possible", "Deletes all PNGs with excess data at the end of the file"),

    /**
     * While image data could not be fully recovered, data resembling IDAT chunks was detected.
     */
    MEDIUM("Likely", "Deletes PNGs with excess data loosely resembling another image"),

    /**
     * While image data could not be fully recovered, some valid IDAT chunks were detected.
     */
    HIGH("Very Likely", "Deletes PNGs with excess data highly resembling another image"),

    /**
     * The image is undoubtedly vulnerable.
     */
    CERTAIN("Certain", "Deletes PNGs with excess data that undoubtedly contains another image");

    companion object {
        /**
         * The default confidence level to use when deleting images.
         */
        val DEFAULT = HIGH
    }
}

class Scanner {

    companion object {
        private const val extension = ".png"
        private val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val IDAT = "IDAT".toByteArray()
        private val IEND = "IEND".toByteArray()
    }

    private val http = OkHttpClient.Builder()
        .callTimeout(1.minutes.toJavaDuration())
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Checks if the image at the given URL is vulnerable to the Acropalypse exploit (CVE-2023-21036).
     *
     * @param url the URL of the image to scan
     * @param threshold the maximum confidence level to return
     * @return the confidence level of the scan
     */
    suspend fun scan(url: String, threshold: ScanConfidence = ScanConfidence.CERTAIN): ScanConfidence {
        if (!url.contains(extension, true))
            return ScanConfidence.NONE
        // download image
        val request = http.newCall(Request.Builder().url(url).build())
        try {
            return request.awaitWith {
                if (it.code != 200)
                    return@awaitWith ScanConfidence.ERROR
                val stream: InputStream = it.body!!.byteStream()
                // ensure image is PNG
                val bytes = stream.readNBytes(header.size)
                if (bytes.size != header.size)
                    return@awaitWith ScanConfidence.NONE
                for (i in header.indices) {
                    if (bytes[i] != header[i])
                        return@awaitWith ScanConfidence.NONE
                }
                // find end of cropped PNG
                try {
                    while (true) {
                        val (type, _) = stream.parsePNGChunk()
                        if (type.contentEquals(IEND))
                            break
                    }
                } catch (e: IllegalStateException) {
                    logger.atDebug().setCause(e).log { "Image $url is corrupt" }
                    return@awaitWith ScanConfidence.ERROR
                } catch (e: IllegalArgumentException) {
                    // reached end of stream!?
                    logger.atDebug().setCause(e).log { "Image $url is corrupt" }
                    return@awaitWith ScanConfidence.ERROR
                }
                // grab the trailing data
                val trailing = stream.readAllBytes()
                if (trailing.isEmpty())
                    return@awaitWith ScanConfidence.NONE
                else if (threshold == ScanConfidence.LOW)
                    return@awaitWith ScanConfidence.LOW
                // skip first 12 bytes in case they were part of a chunk boundary
                val search = trailing.sliceArray(12 until trailing.size)
                // find the start of the next IDAT chunk
                val nextIDAT = search.indexOf(IDAT)
                if (nextIDAT == -1)
                    return@awaitWith ScanConfidence.LOW
                else if (threshold == ScanConfidence.MEDIUM)
                    return@awaitWith ScanConfidence.MEDIUM
                var idat = search.sliceArray(0 until nextIDAT - 8)
                val trailingStream = trailing.sliceArray(nextIDAT - 4 + 12 until trailing.size).inputStream()
                try {
                    while (true) {
                        val (type, body) = trailingStream.parsePNGChunk()
                        if (type.contentEquals(IDAT))
                            idat += body
                        else if (type.contentEquals(IEND))
                            break
                        else {
                            logger.atError().log { "Invalid chunk type $type" }
                            return@awaitWith ScanConfidence.MEDIUM // invalid chunk; probably reached end of stream? or just corrupt?
                        }
                    }
                } catch (e: IllegalStateException) {
                    logger.atWarn().setCause(e).log("Illegal state ($url)")
                    return@awaitWith ScanConfidence.MEDIUM // invalid chunk; maybe corrupt
                } catch (e: IllegalArgumentException) {
                    logger.atWarn().setCause(e).log("Illegal argument ($url)")
                    return@awaitWith ScanConfidence.MEDIUM // reached end of stream; maybe corrupt
                }
                if (threshold == ScanConfidence.HIGH)
                    return@awaitWith ScanConfidence.HIGH
                // slice off the adler32
                idat = idat.sliceArray(0 until idat.size - 4)
                // build bitstream
                val bits = mutableListOf<Int>()
                for (byte in idat) {
                    for (bit in 0 until 8) {
                        bits.add((byte.toInt() ushr bit) and 1)
                    }
                }
                // add some padding so we don't lose any bits
                bits += List(7) { 0 }
                // reconstruct bit-shifted bytestreams
                val byteOffsets = List(8) { i ->
                    val shifted = mutableListOf<Byte>()
                    for (j in i until bits.size - 7 step 8) {
                        var value = 0
                        for (k in 0 until 8) {
                            value = value or (bits[j + k] shl k)
                        }
                        shifted.add(value.toByte())
                    }
                    shifted.toByteArray()
                }
                // bit wrangling sanity checks
                if (!byteOffsets[0].contentEquals(idat)) {
                    logger.atError().log("byteOffsets[0] != idat")
                    return@awaitWith ScanConfidence.HIGH
                }
                if (byteOffsets[1].contentEquals(idat)) {
                    logger.atError().log("byteOffsets[1] == idat")
                    return@awaitWith ScanConfidence.HIGH
                }
                // prefix the stream with 32k of "X" so backrefs can work
                var prefix = byteArrayOf(0x00.toByte())
                prefix += 0x8000.toBytes(2, false)
                prefix += 0x8000.xor(0xffff).toBytes(2, false)
                prefix += ByteArray(0x8000) { 'X'.code.toByte() }
                // scan for viable parses
                for (i in idat.indices) {
                    val truncated = byteOffsets[i % 8].sliceArray(i / 8 until byteOffsets[i % 8].size)
                    if (truncated[0].toInt() and 7 != 0b100)
                        continue
                    val decompressor = Inflater(true)
                    try {
                        val input = prefix + truncated
                        decompressor.setInput(prefix + truncated)
                        val dummyBuffer = ByteArray(0x8000)
                        do {
                            decompressor.inflate(dummyBuffer)
                        } while (!decompressor.finished())
                        val remaining = input.sliceArray(input.size - decompressor.remaining until input.size)
                        if (remaining.isEmpty() || remaining.contentEquals(byteArrayOf(0x00.toByte())))
                            return@awaitWith ScanConfidence.CERTAIN
                    } catch (e: DataFormatException) {
                        // this will happen almost every time
                    }
                }
                return@awaitWith ScanConfidence.HIGH
            }
        } catch (e: IOException) {
            return ScanConfidence.ERROR
        } catch (e: Exception) {
            logger.atError().setCause(e).log { "Unexpected error while scanning $url" }
            return ScanConfidence.ERROR
        }
    }
}