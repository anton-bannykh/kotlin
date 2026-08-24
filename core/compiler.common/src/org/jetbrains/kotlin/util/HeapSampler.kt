/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.util

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Samples the used heap size in the background and keeps the maximum observed since the last [markPeak] call.
 *
 * A single thread is started once and keeps running while the sampler is active, rather than one thread per
 * measured phase: measured phases are short and numerous, so starting and joining a thread around each of them
 * would cost more than the sampling itself. This mirrors `CommonizerMemoryTracker`, which tracks the commonizer
 * the same way.
 *
 * The heap is shared by the whole process, so a sampler can only describe one unit of work at a time.
 * See [MemoryStats] for what that means for the reported numbers.
 *
 * [heapUsedBytes] is injectable so that the peak tracking can be tested without provoking real allocations.
 */
class HeapSampler(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val heapUsedBytes: () -> Long = ::currentHeapUsedBytes,
) {
    private val lock = Any()
    private var peak: Long = 0
    private val running = AtomicBoolean(false)
    private var samplingThread: Thread? = null

    /** Reads the current usage and folds it into the running peak. */
    fun sample(): Long {
        val used = heapUsedBytes()
        synchronized(lock) {
            if (used > peak) peak = used
        }
        return used
    }

    /** The maximum usage observed since the last [markPeak] call, or since [start] if it was never called. */
    val peakBytes: Long
        get() = synchronized(lock) { peak }

    /**
     * Drops the recorded peak down to the current usage and returns it.
     * Call it when a new phase starts, so that its peak isn't inherited from the previous one.
     */
    fun markPeak(): Long {
        val used = heapUsedBytes()
        synchronized(lock) { peak = used }
        return used
    }

    fun start() {
        check(samplingThread == null) { "The heap sampler is already started" }
        running.set(true)
        markPeak()
        samplingThread = thread(isDaemon = true, name = THREAD_NAME) {
            while (running.get()) {
                sample()
                try {
                    Thread.sleep(intervalMillis)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    /** Stops the sampling thread. Does nothing if the sampler was never started or is already stopped. */
    fun stop() {
        running.set(false)
        samplingThread?.let {
            // Interrupt instead of only waiting, otherwise the join would block until the pending sleep expires
            it.interrupt()
            it.join()
        }
        samplingThread = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS: Long = 50
        private const val THREAD_NAME: String = "Kotlin compiler heap sampler"

        fun currentHeapUsedBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }
}
