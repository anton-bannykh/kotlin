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
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * A phase measured by [PerformanceManager], as a Java Flight Recorder event.
 *
 * The events are emitted whenever a recording asks for them, so the phases are also visible in a recording started
 * with `-XX:StartFlightRecording` and can be correlated there with any other JFR event (allocation samples, GC, etc.).
 * The threshold keeps the numerous sub-millisecond per-file phases out of such recordings. [PhaseMemoryRecording] lifts it,
 * and as the flight recorder applies the lowest threshold of all running recordings, such recordings get all phases meanwhile.
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
    var allocated: Long = 0

    // Not recorded, the flight recorder skips transient fields
    @Transient
    private var allocatedAtStart: Long = 0

    fun finish(compilationId: Long, phaseType: PhaseType, dynamicPhase: String?) {
        end()
        if (!shouldCommit()) return
        this.compilationId = compilationId
        this.phaseType = phaseType.name
        this.dynamicPhase = dynamicPhase
        heapUsedAtEnd = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        allocated = threadMXBean.currentThreadAllocatedBytes - allocatedAtStart
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
            event.allocatedAtStart = threadMXBean.currentThreadAllocatedBytes
            event.begin()
            return event
        }
    }
}

/**
 * Whether Java Flight Recorder can be used. `jdk.jfr` is missing from some runtimes, like a jlinked one,
 * and [CompilationPhaseEvent] fails to load there, so nothing may touch it unless this is `true`.
 */
internal object FlightRecorderSupport {
    val isAvailable: Boolean by lazy {
        try {
            Class.forName(CompilationPhaseEvent::class.java.name, true, CompilationPhaseEvent::class.java.classLoader)
            FlightRecorder.isAvailable()
        } catch (_: LinkageError) {
            false
        }
    }
}

/** The memory used by a phase ([dynamicPhase] is `null`) or a dynamic phase of [phaseType]. */
internal data class PhaseMemory(val phaseType: PhaseType, val dynamicPhase: String?, val stats: MemoryStats)

/**
 * An in-process Java Flight Recorder recording of the [CompilationPhaseEvent]s and of the GC events that tell how
 * much memory each phase used.
 *
 * The GC events are attributed to the phases by time after the recording is stopped, see [MemoryStats] for what that means.
 * The recording sees the events of the whole process, so only those of the compilation [compilationId] are kept,
 * which covers the child managers of the compilation whatever thread they run on.
 */
internal class PhaseMemoryRecording(private val compilationId: Long) {
    private val recording = Recording().apply {
        name = "Kotlin compilation phases"
        enable(CompilationPhaseEvent::class.java).withoutThreshold()
        enable(GC_HEAP_SUMMARY)
        enable(GARBAGE_COLLECTION)
        start()
    }

    /** Returns `null` when the recording can't be read, e.g. when the temporary directory is full. */
    fun stop(): List<PhaseMemory>? {
        var file: Path? = null
        try {
            recording.stop()
            file = Files.createTempFile("kotlin-compilation-phases", ".jfr")
            recording.dump(file)
            return readPhases(file)
        } catch (_: IOException) {
            return null
        } finally {
            recording.close()
            file?.let(Files::deleteIfExists)
        }
    }

    private fun readPhases(file: Path): List<PhaseMemory> {
        // Between collections the heap only grows, so its peak during a phase is either the usage right before one of
        // the collections that happened during the phase or the usage at the end of the phase
        val heapUsedBeforeGc = TreeMap<Instant, Long>()
        val gcPauses = TreeMap<Instant, Duration>()
        // The file is read twice rather than kept in memory, as the per-file phases of a large module produce a lot of events
        file.forEachEvent { event ->
            when (event.eventType.name) {
                GC_HEAP_SUMMARY -> if (event.getString("when") == "Before GC") heapUsedBeforeGc[event.startTime] = event.getLong("heapUsed")
                GARBAGE_COLLECTION -> gcPauses[event.startTime] = event.getDuration("sumOfPauses")
            }
        }

        fun <T> TreeMap<Instant, T>.during(event: RecordedEvent) = subMap(event.startTime, true, event.endTime, true).values

        val phases = mutableListOf<PhaseMemory>()
        file.forEachEvent { event ->
            if (event.eventType.name != CompilationPhaseEvent.NAME || event.getLong("compilationId") != compilationId) return@forEachEvent
            val pauses = gcPauses.during(event)
            val stats = MemoryStats(
                peakHeapBytes = maxOf(event.getLong("heapUsedAtEnd"), heapUsedBeforeGc.during(event).maxOrNull() ?: 0),
                allocatedBytes = event.getLong("allocated"),
                gcCount = pauses.size,
                gcPauseNanos = pauses.sumOf { it.toNanos() },
            )
            phases += PhaseMemory(PhaseType.valueOf(event.getString("phaseType")), event.getString("dynamicPhase"), stats)
        }
        return phases
    }

    private fun Path.forEachEvent(action: (RecordedEvent) -> Unit) {
        RecordingFile(this).use { while (it.hasMoreEvents()) action(it.readEvent()) }
    }

    private companion object {
        const val GC_HEAP_SUMMARY = "jdk.GCHeapSummary"
        const val GARBAGE_COLLECTION = "jdk.GarbageCollection"
    }
}
