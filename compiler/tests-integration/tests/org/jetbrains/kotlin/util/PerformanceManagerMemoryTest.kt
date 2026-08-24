/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.util

import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class PerformanceManagerMemoryTest {
    /**
     * Long enough that the sampling thread only takes its first sample during a test, reading the same
     * `fakeHeap` the test itself does. Every explicit peak below is produced by an explicit [HeapSampler.sample] call.
     */
    private val longInterval: Long = 60_000

    private fun createPerformanceManager() = PerformanceManagerImpl(JvmPlatforms.defaultJvmPlatform, "Test")

    private fun PerformanceManager.memoryOf(phaseName: String): MemoryStats? =
        unitStats.dynamicStats?.single { it.name == phaseName }?.memory

    @Test
    fun peakAndEndUsageAreAttributedToTheMeasuredPhase() {
        var fakeHeap = 100L
        val sampler = HeapSampler(longInterval) { fakeHeap }
        val perfManager = createPerformanceManager()
        perfManager.enableMemoryTracking(sampler)

        perfManager.notifyDynamicPhaseStarted("Phase A")
        fakeHeap = 500L
        sampler.sample() // a peak reached while the phase is running
        fakeHeap = 200L // ... and released again before it finishes
        perfManager.notifyDynamicPhaseFinished("Phase A", PhaseType.Backend)

        assertEquals(MemoryStats(peakBytes = 500L, endBytes = 200L), perfManager.memoryOf("Phase A"))
    }

    @Test
    fun repeatedPhaseKeepsTheHighestPeakAndTheLastEndUsage() {
        // This is how a file lowering is measured: once per file, all the runs under the same phase name.
        var fakeHeap = 100L
        val sampler = HeapSampler(longInterval) { fakeHeap }
        val perfManager = createPerformanceManager()
        perfManager.enableMemoryTracking(sampler)

        perfManager.notifyDynamicPhaseStarted("FileLowering")
        fakeHeap = 300L
        sampler.sample()
        fakeHeap = 150L
        perfManager.notifyDynamicPhaseFinished("FileLowering", PhaseType.IrLowering)

        // The second run peaks lower but ends higher
        perfManager.notifyDynamicPhaseStarted("FileLowering")
        fakeHeap = 220L
        sampler.sample()
        fakeHeap = 210L
        perfManager.notifyDynamicPhaseFinished("FileLowering", PhaseType.IrLowering)

        assertEquals(MemoryStats(peakBytes = 300L, endBytes = 210L), perfManager.memoryOf("FileLowering"))
    }

    @Test
    fun aPhaseDoesNotInheritThePeakOfThePreviousOne() {
        var fakeHeap = 100L
        val sampler = HeapSampler(longInterval) { fakeHeap }
        val perfManager = createPerformanceManager()
        perfManager.enableMemoryTracking(sampler)

        perfManager.notifyDynamicPhaseStarted("Greedy phase")
        fakeHeap = 900L
        sampler.sample()
        perfManager.notifyDynamicPhaseFinished("Greedy phase", PhaseType.Backend)

        fakeHeap = 120L
        perfManager.notifyDynamicPhaseStarted("Modest phase")
        fakeHeap = 130L
        sampler.sample()
        perfManager.notifyDynamicPhaseFinished("Modest phase", PhaseType.Backend)

        assertEquals(MemoryStats(peakBytes = 900L, endBytes = 900L), perfManager.memoryOf("Greedy phase"))
        assertEquals(MemoryStats(peakBytes = 130L, endBytes = 130L), perfManager.memoryOf("Modest phase"))
    }

    @Test
    fun childManagersMeasureTheSameHeapAndTheirStatsAreAggregated() {
        // Kotlin/Native measures the backend phases on a child manager per compiled fragment
        // and merges the results into the main one.
        var fakeHeap = 100L
        val sampler = HeapSampler(longInterval) { fakeHeap }
        val perfManager = createPerformanceManager()
        perfManager.enableMemoryTracking(sampler)

        val child = PerformanceManagerImpl.createChildIfNeeded(perfManager, start = false)!!
        child.notifyDynamicPhaseStarted("DevirtualizationAnalysis")
        fakeHeap = 900L
        sampler.sample()
        fakeHeap = 400L
        child.notifyDynamicPhaseFinished("DevirtualizationAnalysis", PhaseType.Backend)

        perfManager.addOtherUnitStats(child.unitStats)

        assertEquals(MemoryStats(peakBytes = 900L, endBytes = 400L), perfManager.memoryOf("DevirtualizationAnalysis"))
    }

    @Test
    fun memoryIsNotRecordedWhenTrackingIsDisabled() {
        val perfManager = createPerformanceManager()

        perfManager.notifyDynamicPhaseStarted("Phase A")
        perfManager.notifyDynamicPhaseFinished("Phase A", PhaseType.Backend)

        assertNull(perfManager.memoryOf("Phase A"))
    }
}
