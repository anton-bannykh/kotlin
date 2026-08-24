/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.util

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Records real Java Flight Recorder events and reads them back.
 *
 * Asserting on `commit()` being called would prove nothing: without a running recording that has the event
 * enabled, `commit()` is a silent no-op, so an implementation that emits nothing at all would pass just as well.
 * The only way to tell the difference is to run a recording and look at what ends up in it.
 */
class CompilationPhaseEventTest {
    /**
     * The sampling interval is long enough that only the sampler's first sample runs during a test,
     * reading the same `fakeHeap` the test does.
     */
    private val longInterval: Long = 60_000

    private fun createPerformanceManager() = PerformanceManagerImpl(JvmPlatforms.defaultJvmPlatform, "Test")

    private fun <T> recordingPhaseEvents(block: () -> T): List<RecordedEvent> {
        val recording = Recording()
        recording.enable(EVENT_NAME).withoutThreshold()
        recording.start()
        try {
            block()
        } finally {
            recording.stop()
        }

        val directory: Path = Files.createTempDirectory("kotlin-compilation-phase-events")
        val dump = directory.resolve("events.jfr")
        try {
            recording.dump(dump)
            return RecordingFile.readAllEvents(dump).filter { it.eventType.name == EVENT_NAME }
        } finally {
            recording.close()
            Files.deleteIfExists(dump)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun aMeasuredPhaseIsRecordedWithItsHeapFigures() {
        var fakeHeap = 100L
        val sampler = HeapSampler(longInterval) { fakeHeap }
        val perfManager = createPerformanceManager()

        val events = recordingPhaseEvents {
            perfManager.enableMemoryTracking(sampler)
            perfManager.notifyDynamicPhaseStarted("DevirtualizationAnalysis")
            fakeHeap = 700L
            sampler.sample()
            fakeHeap = 250L
            perfManager.notifyDynamicPhaseFinished("DevirtualizationAnalysis", PhaseType.Backend)
        }

        val event = events.single()
        assertEquals("Backend", event.getString("parentPhase"))
        assertEquals("DevirtualizationAnalysis", event.getString("phase"))
        assertEquals(700L, event.getLong("peakBytes"))
        assertEquals(250L, event.getLong("endBytes"))
        // The event carries a start time and a duration, so the phase shows up on the timeline as a span
        // rather than an instant. `withoutThreshold` above makes sure it is recorded however short it was.
        assertEquals(1, events.size)
    }

    @Test
    fun eachRunOfARepeatedlyMeasuredPhaseIsRecordedSeparately() {
        // The report merges the runs of a file lowering into one peak; the recording keeps them apart, so a
        // phase that got slow or greedy on one particular run is still visible. These runs are instantaneous,
        // which is exactly why `recordingPhaseEvents` disables the event's default threshold.
        var fakeHeap = 100L
        val sampler = HeapSampler(longInterval) { fakeHeap }
        val perfManager = createPerformanceManager()

        val events = recordingPhaseEvents {
            perfManager.enableMemoryTracking(sampler)
            for (peak in listOf(300L, 900L, 200L)) {
                // Back down to the retained level before the run starts: a phase's peak legitimately includes
                // whatever was still allocated when it began, so without this the third run would report the
                // 900 it inherited from the second rather than its own 200.
                fakeHeap = 100L
                perfManager.notifyDynamicPhaseStarted("FileLowering")
                fakeHeap = peak
                sampler.sample()
                perfManager.notifyDynamicPhaseFinished("FileLowering", PhaseType.IrLowering)
            }
        }

        assertEquals(listOf(300L, 900L, 200L), events.map { it.getLong("peakBytes") })
        assertTrue(events.all { it.getString("phase") == "FileLowering" })
    }

    @Test
    fun nothingIsRecordedWhenMemoryTrackingIsDisabled() {
        val perfManager = createPerformanceManager()

        val events = recordingPhaseEvents {
            perfManager.notifyDynamicPhaseStarted("Untracked phase")
            perfManager.notifyDynamicPhaseFinished("Untracked phase", PhaseType.Backend)
        }

        assertEquals(emptyList(), events)
    }

    companion object {
        /**
         * Tools read a recording by event name, so the name is part of the contract with them rather than an
         * implementation detail. It is spelled out here so that changing it can't go unnoticed.
         */
        private const val EVENT_NAME = "org.jetbrains.kotlin.CompilationPhase"
    }
}
