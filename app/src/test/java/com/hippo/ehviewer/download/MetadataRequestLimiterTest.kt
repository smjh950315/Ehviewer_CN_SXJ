package com.hippo.ehviewer.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRequestLimiterTest {
    @Test
    fun fastRequestsStayBelow100InEveryRollingMinute() {
        val limiter = MetadataRequestLimiter()
        val starts = ArrayList<Long>()
        var now = 10_000L
        repeat(300) {
            now += limiter.delayMillis(now)
            starts.add(now)
            limiter.onRequestStarted(now)
            limiter.onRequestFinished(now)
        }
        for (start in starts) {
            assertTrue(starts.count { it >= start && it < start + 60_000L } <= 100)
        }
        assertEquals(600L, starts[100] - starts[99])
    }

    @Test
    fun delayedNetworkExecutionCannotCauseACatchUpBurst() {
        val limiter = MetadataRequestLimiter()
        limiter.onRequestStarted(0L)
        // The network executor was busy before this request could actually run.
        limiter.onRequestFinished(5_000L)
        assertEquals(600L, limiter.delayMillis(5_000L))
        assertEquals(1L, limiter.delayMillis(5_599L))
        assertEquals(0L, limiter.delayMillis(5_600L))
    }

    @Test
    fun failuresAndRestartsKeepTheSameRateLimit() {
        val limiter = MetadataRequestLimiter()
        limiter.onRequestStarted(1_000L)
        // Failed and cancelled requests also consume a slot; a new run reuses this limiter.
        limiter.onRequestFinished(1_100L)
        assertEquals(600L, limiter.delayMillis(1_100L))
        assertEquals(400L, limiter.delayMillis(1_300L))
        assertEquals(0L, limiter.delayMillis(30_000L))
        limiter.onRequestStarted(30_000L)
        assertEquals(600L, limiter.delayMillis(30_000L))
    }
}
