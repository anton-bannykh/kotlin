/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.util

import jdk.jfr.Category
import jdk.jfr.DataAmount
import jdk.jfr.Event
import jdk.jfr.EventType
import jdk.jfr.FlightRecorder
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.Recording
import jdk.jfr.StackTrace
import jdk.jfr.Threshold
import jdk.jfr.consumer.RecordingStream
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * A phase measured by [PerformanceManager], as a Java Flight Recorder event.
 *
 * The events are emitted whenever a recording asks for them, so the phases are also visible in a recording started
 * with `-XX:StartFlightRecording` and can be correlated there with any other JFR event (allocation samples, GC, etc.).
 * The threshold keeps the numerous sub-millisecond per-file phases out of such recordings; [PhaseMemoryRecording] lifts it.
 *
 * A process like a Kotlin daemon may run several compilations at once, [compilationId] tells their phases apart.
 */
@Name(CompilationPhaseEvent.NAME)
@Category("Kotlin", "Compiler")
@Label("Compilation Phase")
@StackTrace(false)
@Threshold("1 ms")
internal class CompilationPhaseEvent : Event() {
    @Label("Compilation Id")
    var compilationId: Long = 0

    @Label("Phase Type")
    var phaseType: String = ""

    @Label("Dynamic Phase")
    var dynamicPhase: String? = null

    @Label("Heap Used At End")
    @DataAmount
    var heapUsedAtEnd: Long = 0

    @Label("Allocated By Thread")
    @DataAmount
    var allocated: Long = threadMXBean.currentThreadAllocatedBytes

    fun finish(compilationId: Long, phaseType: PhaseType, dynamicPhase: String?) {
        end()
        if (!shouldCommit()) return
        this.compilationId = compilationId
        this.phaseType = phaseType.name
        this.dynamicPhase = dynamicPhase
        heapUsedAtEnd = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        allocated = threadMXBean.currentThreadAllocatedBytes - allocated
        commit()
    }

    companion object {
        const val NAME: String = "org.jetbrains.kotlin.CompilationPhase"

        private val threadMXBean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        private val eventType = EventType.getEventType(CompilationPhaseEvent::class.java)

        /** Returns `null` when no recording wants the event, which keeps the measurements free in that case. */
        fun startIfEnabled(): CompilationPhaseEvent? {
            if (!eventType.isEnabled) return null
            val event = CompilationPhaseEvent()
            event.begin()
            return event
        }
    }
}

/**
 * An in-process Java Flight Recorder recording of the [CompilationPhaseEvent]s and of the GC events that tell how
 * much memory each phase used.
 *
 * The events are delivered while the compilation runs, and the GC events are attributed to the phases by time once
 * it finishes, see [MemoryStats] for what that means. The recording sees the events of the whole process, so only
 * those of the compilation [compilationId] are kept, which covers the child managers of the compilation whatever
 * thread they run on.
 */
internal class PhaseMemoryRecording(private val compilationId: Long) {
    private val lock = Any()
    private val heapUsedBeforeGc = TreeMap<Instant, Long>()
    private val gcPauses = TreeMap<Instant, Duration>()
    private val phaseEvents = mutableListOf<PhaseEvent>()

    private val recording: Recording
    private val stream: RecordingStream

    init {
        val recorder = FlightRecorder.getFlightRecorder()
        synchronized(streamCreationLock) {
            // The stream runs a `Recording` of its own that it exposes nowhere, so it is taken from the flight
            // recorder's list, where several compilations that start at once would make the newcomer ambiguous
            val recordingsBefore = recorder.recordings.toSet()
            stream = RecordingStream()
            recording = recorder.recordings.single { it !in recordingsBefore && it.name.startsWith(STREAM_RECORDING_NAME) }
        }
        recording.name = RECORDING_NAME
        stream.enable(CompilationPhaseEvent::class.java).withoutThreshold()
        stream.enable(GC_HEAP_SUMMARY)
        stream.enable(GARBAGE_COLLECTION)
        stream.onEvent { event ->
            synchronized(lock) {
                when (event.eventType.name) {
                    GC_HEAP_SUMMARY -> if (event.getString("when") == "Before GC") heapUsedBeforeGc[event.startTime] = event.getLong("heapUsed")
                    GARBAGE_COLLECTION -> gcPauses[event.startTime] = event.getDuration("sumOfPauses")
                    CompilationPhaseEvent.NAME -> if (event.getLong("compilationId") == compilationId) {
                        phaseEvents.add(
                            PhaseEvent(
                                event.startTime,
                                event.endTime,
                                PhaseType.valueOf(event.getString("phaseType")),
                                event.getString("dynamicPhase"),
                                event.getLong("heapUsedAtEnd"),
                                event.getLong("allocated"),
                            )
                        )
                    }
                }
            }
        }
        stream.startAsync()
    }

    fun stop(): List<MemoryStats> {
        // The stream delivers only what the JVM has flushed into its repository, so closing it right away would drop
        // the events since the last flush, which are the last phases of the compilation. Stopping the recording
        // flushes them into its final chunk, and the stream ends once it has delivered it
        recording.stop()
        try {
            stream.awaitTermination()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            stream.close()
        }

        // Between collections the heap only grows, so its peak during a phase is either the usage right before one of
        // the collections that happened during the phase or the usage at the end of the phase
        synchronized(lock) {
            fun <T> TreeMap<Instant, T>.during(event: PhaseEvent) = subMap(event.startTime, true, event.endTime, true).values

            return phaseEvents.map { event ->
                val pauses = gcPauses.during(event)
                MemoryStats(
                    event.phaseType,
                    event.dynamicPhase,
                    peakHeapBytes = maxOf(event.heapUsedAtEnd, heapUsedBeforeGc.during(event).maxOrNull() ?: 0),
                    allocatedBytes = event.allocated,
                    gcCount = pauses.size,
                    gcPauseNanos = pauses.sumOf { it.toNanos() },
                )
            }
                .groupBy { it.phaseType to it.dynamicPhase }
                .values.map { it.reduce(MemoryStats::plus) }
        }
    }

    private class PhaseEvent(
        val startTime: Instant,
        val endTime: Instant,
        val phaseType: PhaseType,
        val dynamicPhase: String?,
        val heapUsedAtEnd: Long,
        val allocated: Long,
    )

    private companion object {
        const val GC_HEAP_SUMMARY = "jdk.GCHeapSummary"
        const val GARBAGE_COLLECTION = "jdk.GarbageCollection"
        const val STREAM_RECORDING_NAME = "Recording Stream"
        const val RECORDING_NAME = "Kotlin compilation phases"

        val streamCreationLock = Any()
    }
}
