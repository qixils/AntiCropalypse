package dev.qixils.anticropalypse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.pow

suspend inline fun <T : Any?> retryUntilSuccess(limit: Int = 10, block: () -> T): T {
    return retryUntilSuccess<T, Exception>(limit, block = block)
}

suspend inline fun <T : Any?, reified E : Exception> retryUntilSuccess(limit: Int = 10, exceptionHandler: (E) -> Unit = {}, block: () -> T): T {
    var count = 0
    var exception: Exception? = null
    while (limit <= 0 || count < limit) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e !is E)
                throw e
            exceptionHandler(e)
            exception = e
            count++
            delay(2f.pow(count).toLong() * 1000L)
        }
    }
    throw exception ?: RuntimeException("Retry limit exceeded")
}
