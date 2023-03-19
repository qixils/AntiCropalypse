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

fun ByteArray.toInt(big: Boolean): Int {
    // convert to signed int
    var result = 0
    for (i in indices) {
        result = if (big)
            result shl 8 or (this[i].toInt() and 0xff)
        else
            result or (this[i].toInt() and 0xff shl i * 8)
    }
    return result
}

fun ByteArray.crc32(): Int {
    val crc = CRC32()
    crc.update(this)
    return crc.value.toInt()
}

fun ByteArray.indexOf(bytes: ByteArray): Int {
    for (i in 0..size - bytes.size) {
        var found = true
        for (j in 0..bytes.size) {
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
    val csum = readNBytes(4).toInt(true)
    if (csum != (ctype + body).crc32())
        throw IllegalStateException("CRC32 mismatch")
    return ctype to body
}

fun Int.toBytes(size: Int, big: Boolean): ByteArray {
    val result = ByteArray(size)
    for (i in 0..size) {
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
        val DEFAULT = MEDIUM
    }
}

class Scanner {

    companion object {
        private const val extension = ".png"
        private val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }

    private val http = OkHttpClient.Builder()
        .callTimeout(1.minutes.toJavaDuration())
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val logger by SLF4J

    /**
     * Checks if the image at the given URL is vulnerable to the Acropalypse exploit (CVE-2023-21036).
     *
     * @param url the URL of the image to scan
     * @return the confidence level of the scan
     */
    suspend fun scan(url: String): ScanConfidence {
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
                while (true) {
                    val (type, _) = stream.parsePNGChunk()
                    if (type.contentEquals("IEND".toByteArray()))
                        break
                }
                // grab the trailing data
                val data = stream.readAllBytes()
                if (data.isEmpty())
                    return@awaitWith ScanConfidence.NONE
                // find the start of the next IDAT chunk
                val index = data.indexOf("IDAT".toByteArray())
                if (index == -1)
                    return@awaitWith ScanConfidence.LOW
                // skip first 12 bytes in case they were part of a chunk boundary
                var idat = data.sliceArray(12 until index - 8)
                val idatStream = idat.inputStream()
                while (true) {
                    val (type, body) = idatStream.parsePNGChunk()
                    if (type.contentEquals("IDAT".toByteArray()))
                        idat += body
                    else if (type.contentEquals("IEND".toByteArray()))
                        break
                    else
                        return@awaitWith ScanConfidence.MEDIUM // invalid chunk; probably reached end of stream? or just corrupt?
                }
                // slice off the adler32
                idat = idat.sliceArray(0 until idat.size - 4)
                // build bitstream
                val bits = mutableListOf<Boolean>()
                for (byte in idat) {
                    for (bit in 0..7) {
                        bits.add((byte.toInt() shr bit and 1) == 1)
                    }
                }
                // add some padding so we don't lose any bits
                bits += List(8) { false }
                // reconstruct bit-shifted bytestreams
                val byteOffsets = List(8) { i ->
                    val shifted = mutableListOf<Byte>()
                    for (j in i until bits.size - 7 step 8) {
                        var value = 0
                        for (k in 0..7) {
                            value = value or (if (bits[j + k]) 1 else 0) shl k
                        }
                        shifted.add(value.toByte())
                    }
                    shifted.toByteArray()
                }
                // bit wrangling sanity checks
                if (!byteOffsets[0].contentEquals(idat))
                    return@awaitWith ScanConfidence.HIGH
                if (byteOffsets[1].contentEquals(idat))
                    return@awaitWith ScanConfidence.HIGH
                // prefix the stream with 32k of "X" so backrefs can work
                val prefix = byteArrayOf(0x00.toByte()) + 0x8000.toBytes(2, true) + 0x8000.xor(0xffff).toBytes(2, true) + ByteArray(0x8000) { 'X'.code.toByte() }
                // scan for viable parses
                for (i in idat.indices) {
                    val truncated = byteOffsets[i % 8].sliceArray(i / 8 until byteOffsets[i % 8].size)
                    if (truncated[0].toInt() and 7 != 0b100)
                        continue
                    val decompressor = Inflater(true) // TODO: not sure if this matches the python "wbits=-15" parameter
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
            logger.warn("Unexpected error while scanning $url", e)
            return ScanConfidence.ERROR
        }
    }
}