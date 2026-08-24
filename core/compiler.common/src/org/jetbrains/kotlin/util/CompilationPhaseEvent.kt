/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.util

import jdk.jfr.Category
import jdk.jfr.DataAmount
import jdk.jfr.Description
import jdk.jfr.Event
import jdk.jfr.EventType
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace
import jdk.jfr.Threshold

/**
 * A measured compilation phase, as a Java Flight Recorder event.
 *
 * The point of recording the phases into JFR, rather than only into the performance report, is that a recording
 * already contains the events explaining *why* the heap grew - `jdk.GCHeapSummary`, `jdk.ObjectAllocationSample`,
 * the collection pauses - and those are only useful if it's visible which phase was running at the time.
 * The report answers "which phase used the most memory"; the recording answers "what was it doing".
 *
 * The heap figures duplicate the ones in [MemoryStats] on purpose: a recording is usually read on its own,
 * in a tool like JDK Mission Control, without the performance report at hand.
 */
@Name(CompilationPhaseEvent.NAME)
@Category("Kotlin", "Compiler")
@Label("Compilation Phase")
@Description("A compiler phase measured by the Kotlin compiler's performance manager")
@StackTrace(false)
// Phases are measured per file as well as per module, and the per-file ones are overwhelmingly short: compiling
// an empty `main` emits ~38000 events, 99% of them under a millisecond, which buries the phases worth looking at
// and costs about 2 MB of recording. A default threshold keeps those out while leaving every phase that takes
// real time; a recording that wants all of them can still ask, via `withoutThreshold()` or a custom .jfc.
@Threshold("1 ms")
internal class CompilationPhaseEvent : Event() {
    @Label("Parent Phase")
    var parentPhase: String = ""

    @Label("Phase")
    var phase: String = ""

    @Label("Peak Heap Used")
    @DataAmount
    var peakBytes: Long = 0

    @Label("Heap Used At End")
    @DataAmount
    var endBytes: Long = 0

    companion object {
        const val NAME: String = "org.jetbrains.kotlin.CompilationPhase"

        private val eventType: EventType = EventType.getEventType(CompilationPhaseEvent::class.java)

        /**
         * Whether a recording currently has this event turned on.
         *
         * Checking this before building an event is the idiomatic way to keep JFR free when nobody is recording,
         * and it is the only reliable one: [Event.commit] silently discards without a running recording, so a
         * cheaper-looking check like `FlightRecorder.isAvailable` would report success while producing nothing.
         */
        val isEnabled: Boolean
            get() = eventType.isEnabled
    }
}
