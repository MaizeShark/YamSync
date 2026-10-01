package com.anisync.android.data.util

import com.anisync.android.data.network.ApiErrorMessages
import com.anisync.android.domain.Result
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Runs a server call and folds whatever goes wrong into [Result.Error].
 *
 * The single entry point for the data layer. The Yamtrack client already throws [ApiError]s, so this
 * mostly describes them; the other branches are the safety net for anything thrown below it.
 *
 * **Cancellation is rethrown.** A blanket `catch (e: Exception)` swallows `CancellationException`,
 * which turns a screen the user navigated away from into a spurious error and breaks structured
 * concurrency for everything above it.
 */
suspend fun <T> safeApiCall(
    apiCall: suspend () -> T,
): Result<T> {
    return try {
        Result.Success(apiCall())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val apiError = e.findApiError() ?: when (e) {
            is InterruptedIOException -> ApiError.Timeout(e)
            is IOException -> ApiError.Offline(e)
            else -> null
        }
        apiError?.toResult() ?: Result.Error(
            // Some platform exceptions (NetworkOnMainThreadException) carry no message at all.
            message = e.message?.takeIf { it.isNotBlank() } ?: ApiErrorMessages.describe(ApiError.Unknown("")),
            exception = e,
        )
    }
}

/** The first [ApiError] in this throwable's cause chain, if there is one. */
fun Throwable.findApiError(): ApiError? =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
        .firstOrNull { it is ApiError } as? ApiError

private fun ApiError.toResult(): Result.Error = Result.Error(
    message = ApiErrorMessages.describe(this),
    code = statusCode(),
    countdownSeconds = countdownSeconds(),
    exception = this,
)

/**
 * The HTTP status a consumer would recognise.
 *
 * A handful of screens branch on this: 429 drives the countdown toast and the refresh gate, 401
 * the expired-session handling. Everything else only shows the message.
 */
private fun ApiError.statusCode(): Int? = when (this) {
    is ApiError.RateLimited -> 429
    is ApiError.Deferred -> 429
    is ApiError.SessionExpired -> 401
    is ApiError.TokenRejected -> 401
    is ApiError.PermissionDenied -> 401
    is ApiError.ApiDisabled -> 403
    is ApiError.ServerError -> statusCode
    is ApiError.Validation -> 400
    is ApiError.GraphQLError -> statusCode
    is ApiError.LoginFailed -> 401
    is ApiError.Offline, is ApiError.Timeout, is ApiError.ParseError, is ApiError.Unknown -> null
}

private fun ApiError.countdownSeconds(): Long? = when (this) {
    is ApiError.RateLimited -> retryAfterSeconds
    is ApiError.Deferred -> retryAfterSeconds
    else -> null
}
