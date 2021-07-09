/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

object Timer {
    private class Stat {
        private var cnt = 0
        private var time = 0L

        fun <T> record(fn: () -> T): T {
            val start = System.currentTimeMillis()
            try {
                return fn()
            } finally {
                time += System.currentTimeMillis() - start
                ++cnt
            }

        }

        fun avg(): Long = time / cnt
    }

    private val statMap = mutableMapOf<String, Stat>()

    fun <T> run(name: String, fn: () -> T): T {
        return statMap.getOrPut(name, ::Stat).record(fn)
    }

    private var cnt = 0

    fun report() {
        println(cnt++)
        for ((name, stat) in statMap.entries) {
            report(name)
        }
        println()
    }

    fun report(name: String) {
        statMap[name]?.let { stat ->
            println("$name: ${stat.avg()}ms")
        }
    }

    fun clear() {
        cnt = 0
        statMap.clear()
    }
}