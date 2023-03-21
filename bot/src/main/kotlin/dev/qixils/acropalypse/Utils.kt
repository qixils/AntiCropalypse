package dev.qixils.acropalypse

import kotlinx.coroutines.delay
import kotlin.math.pow

suspend fun <T : Any> retryUntilSuccess(limit: Int = 10, block: suspend () -> T): T {
    return retryUntilSuccess(null, 0, limit, block)
}

private suspend fun <T : Any> retryUntilSuccess(exception: Exception?, count: Int, limit: Int, block: suspend () -> T): T {
    if (count >= limit)
        throw RuntimeException("Retry limit exceeded", exception)
    return try {
        block()
    } catch (e: Exception) {
        delay(2f.pow(count).toLong() * 1000L)
        retryUntilSuccess(e, count + 1, limit, block)
    }
}