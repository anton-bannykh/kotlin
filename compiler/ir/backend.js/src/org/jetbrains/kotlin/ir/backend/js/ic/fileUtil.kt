/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.library.resolver.KotlinLibraryResolveResult
import java.io.File

// TODO more parameters for lowerings
fun buildCache(
    cachePath: String,
    klibPath: String,
) {
    // TODO md5
    // TODO perform lowerings
    CacheInfo(cachePath, File(klibPath).absolutePath).save()
}

fun checkCaches(
    allDependencies: KotlinLibraryResolveResult,
    cachePaths: List<String>,
) {
    val allLibs = allDependencies.getFullList().map { it.libraryFile.absolutePath }.toSet()

    val caches = cachePaths.map { CacheInfo.load(it)}

    for (c in caches) {
        if (c.libPath !in allLibs) error("Missing library: ${c.libPath}")
    }

    val missedLibs = allLibs - caches.map { it.libPath }
    if (!missedLibs.isEmpty()) {
        error("Missing caches for libraries: ${missedLibs}")
    }
}

// TODO md5 hash
data class CacheInfo(val path: String, val libPath: String) {
    fun save() {
        File(File(path), "info").writeText(libPath)
    }

    companion object {
        fun load(path: String): CacheInfo {
            val (libPath) = File(File(path), "info").readLines()

            return CacheInfo(path, libPath)
        }
    }
}
