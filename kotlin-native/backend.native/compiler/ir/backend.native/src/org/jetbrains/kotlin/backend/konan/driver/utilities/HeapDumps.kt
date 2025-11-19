/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.driver.utilities

import com.sun.management.HotSpotDiagnosticMXBean
import java.lang.management.ManagementFactory


private const val MXBEAN_NAME = "com.sun.management:type=HotSpotDiagnostic"

fun dumpHeap(filePath: String, live: Boolean = true) {
    try {
        val mx: HotSpotDiagnosticMXBean =
                ManagementFactory.newPlatformMXBeanProxy<HotSpotDiagnosticMXBean?>(
                        ManagementFactory.getPlatformMBeanServer(),
                        MXBEAN_NAME,
                        HotSpotDiagnosticMXBean::class.java)!!
        mx.dumpHeap(filePath, live) // live=true keeps only reachable objects
        println("Heap dump written to " + filePath)
    } catch (e: Exception) {
        throw RuntimeException("Heap dump failed", e)
    }
}

fun liveHeapSize(): Long {
    System.gc()
    return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
}