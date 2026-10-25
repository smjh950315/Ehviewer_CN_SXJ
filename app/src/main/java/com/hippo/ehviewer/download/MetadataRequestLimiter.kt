package com.hippo.ehviewer.download

/** Spaces this queue's requests to at most 100 galleries in any rolling minute. */
internal class MetadataRequestLimiter {
    private var nextRequestAt = 0L

    fun delayMillis(now: Long): Long = (nextRequestAt - now).coerceAtLeast(0L)

    fun onRequestStarted(now: Long) {
        nextRequestAt = maxOf(nextRequestAt, now + INTERVAL_MILLIS)
    }

    fun onRequestFinished(now: Long) {
        // Wait after completion too: an overloaded network executor must not cause a catch-up burst.
        nextRequestAt = maxOf(nextRequestAt, now + INTERVAL_MILLIS)
    }

    companion object {
        private const val INTERVAL_MILLIS = 60_000L / 100
    }
}
