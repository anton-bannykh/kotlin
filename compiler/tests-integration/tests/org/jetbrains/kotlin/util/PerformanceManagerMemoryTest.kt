/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.util

import jdk.jfr.FlightRecorder
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PerformanceManagerMemoryTest {
    private val allocationSize = 64 * 1024 * 1024

    @Volatile
    private var sink: Any? = null

    private fun allocateAndCollect() {
        sink = ByteArray(allocationSize)
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
        sink = null
    }

    /** Like `-Xdetailed-perf` along with `-Xreport-perf` or `-Xdump-perf`. */
    private fun createTrackingManager() = PerformanceManagerImpl(JvmPlatforms.defaultJvmPlatform, "Test").apply {
        detailedPerf = true
        enableExtendedStats()
    }

    @Test
    fun memoryOfPhasesAndDynamicPhasesIsReported() {
        val perfManager = createTrackingManager()
        perfManager.measureCompilation().use {
            perfManager.tryMeasurePhaseTime(PhaseType.Backend) {
                repeat(2) {
                    perfManager.tryMeasureDynamicPhaseTime("Per file", PhaseType.Backend, ::allocateAndCollect)
                }
            }
        }

        val phase = perfManager.unitStats.memoryStats!!.getValue(PhaseType.Backend)
        val dynamicPhase = perfManager.unitStats.dynamicMemoryStats!!.single().also { assertEquals("Per file", it.name) }.memory
        // The runs of a dynamic phase are summed up, and the enclosing phase includes them
        assertTrue(dynamicPhase.allocatedBytes >= 2L * allocationSize, dynamicPhase.toString())
        assertTrue(dynamicPhase.gcCount >= 2, dynamicPhase.toString())
        assertTrue(dynamicPhase.peakHeapBytes >= allocationSize, dynamicPhase.toString())
        assertTrue(phase.allocatedBytes >= dynamicPhase.allocatedBytes, phase.toString())
        assertTrue(phase.gcCount >= dynamicPhase.gcCount, phase.toString())
    }

    @Test
    fun phasesOfChildManagersOnOtherThreadsAreReported() {
        val perfManager = createTrackingManager()
        perfManager.measureCompilation().use {
            thread {
                val child = PerformanceManagerImpl.createChildIfNeeded(perfManager, start = false)!!
                child.tryMeasureDynamicPhaseTime("In child", PhaseType.Backend, ::allocateAndCollect)
            }.join()
        }

        val memory = perfManager.unitStats.dynamicMemoryStats!!.single { it.name == "In child" }.memory
        assertTrue(memory.allocatedBytes >= allocationSize, memory.toString())
    }

    @Test
    fun phasesOfOtherCompilationsAreNotReported() {
        // Like a Kotlin daemon running another compilation alongside this one
        val perfManager = createTrackingManager()
        perfManager.measureCompilation().use {
            thread {
                val other = PerformanceManagerImpl(JvmPlatforms.defaultJvmPlatform, "Other")
                other.tryMeasureDynamicPhaseTime("In other compilation", PhaseType.Backend, ::allocateAndCollect)
            }.join()
            perfManager.tryMeasureDynamicPhaseTime("In this compilation", PhaseType.Backend, ::allocateAndCollect)
        }

        assertEquals(listOf("In this compilation"), perfManager.unitStats.dynamicMemoryStats!!.map { it.name })
    }

    private fun runningMemoryRecordings() =
        FlightRecorder.getFlightRecorder().recordings.count { it.name == "Kotlin compilation phases" }

    @Test
    fun theMeasurementIsFinishedByTheCodeThatStartedIt() {
        val before = runningMemoryRecordings()
        val perfManager = createTrackingManager()

        perfManager.measureCompilation().use {
            // Like the first stage of a one-stage Kotlin/Native compilation
            perfManager.measureCompilation().use {}
            assertFalse(perfManager.isFinalized)
            assertEquals(before + 1, runningMemoryRecordings())
        }

        assertTrue(perfManager.isFinalized)
        assertEquals(before, runningMemoryRecordings())
        assertNotNull(perfManager.unitStats.memoryStats)
    }

    @Test
    fun theMeasurementIsFinishedWhenTheCompilationFails() {
        val before = runningMemoryRecordings()
        val perfManager = createTrackingManager()

        assertFailsWith<IllegalStateException> {
            perfManager.measureCompilation().use { error("Compilation failed") }
        }

        assertTrue(perfManager.isFinalized)
        assertEquals(before, runningMemoryRecordings())
    }

    @Test
    fun memoryIsNotReportedWhenTrackingIsDisabled() {
        val before = runningMemoryRecordings()
        val perfManager = PerformanceManagerImpl(JvmPlatforms.defaultJvmPlatform, "Test")

        perfManager.measureCompilation().use {
            assertEquals(before, runningMemoryRecordings())
            perfManager.tryMeasureDynamicPhaseTime("Phase", PhaseType.Backend, ::allocateAndCollect)
        }

        assertNull(perfManager.unitStats.memoryStats)
    }
}
