package dev.ksurdy.claudeautocomplete.backend

object FailureMapper {
    private val NOT_LOGGED_IN_MARKERS = listOf("Not logged in", "Failed to authenticate", "OAuth session expired", "Invalid API key")
    private val USAGE_LIMIT_MARKERS = listOf("usage limit", "limit reached", "rate limit", "hit your limit", "out of extra usage")

    fun map(message: String): CompletionResult.Failure {
        val kind = when {
            NOT_LOGGED_IN_MARKERS.any { message.contains(it, ignoreCase = true) } -> FailureKind.NotLoggedIn
            message.contains("issue with the selected model", ignoreCase = true) -> FailureKind.InvalidModel
            USAGE_LIMIT_MARKERS.any { message.contains(it, ignoreCase = true) } -> FailureKind.RateLimited
            else -> FailureKind.Other
        }
        return CompletionResult.Failure(kind, message.trim())
    }
}
