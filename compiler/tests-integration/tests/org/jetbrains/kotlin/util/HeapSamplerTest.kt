/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.util

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class HeapSamplerTest {
    /**
     * The sampling interval is long enough that the background thread only takes its very first sample during a test.
     * That sample reads the same [fakeHeap] the test does, so it can't make the assertions flaky.
     */
    private val longInterval: Long = 60_000

    @Test
    fun peakKeepsTheMaximumUntilItIsMarkedAgain() {
        var fakeHeap = 100L
        val sampler = HeapSampler(longInterval) { fakeHeap }

        assertEquals(100L, sampler.markPeak())

        fakeHeap = 300L
        sampler.sample()
        fakeHeap = 200L
        sampler.sample()

        // Usage dropping back doesn't lower the recorded peak
        assertEquals(300L, sampler.peakBytes)

        fakeHeap = 150L
        assertEquals(150L, sampler.markPeak())
        // Marking forgets the peak of whatever was measured before
        assertEquals(150L, sampler.peakBytes)
    }

    @Test
    fun sampleReturnsCurrentUsageAndFoldsItIntoThePeak() {
        var fakeHeap = 10L
        val sampler = HeapSampler(longInterval) { fakeHeap }
        sampler.markPeak()

        fakeHeap = 42L
        assertEquals(42L, sampler.sample())
        assertEquals(42L, sampler.peakBytes)
    }

    @Test
    fun startAndStopAreSafeToCallAroundSampling() {
        var fakeHeap = 7L
        val sampler = HeapSampler(longInterval) { fakeHeap }

        sampler.start()
        fakeHeap = 21L
        sampler.sample()
        sampler.stop()

        assertEquals(21L, sampler.peakBytes)
        // Stopping twice is a no-op rather than an error, so a failed compilation can always clean up
        sampler.stop()
    }
}
