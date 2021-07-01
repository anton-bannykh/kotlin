/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.analyzer.AbstractAnalyzerWithCompilerReport
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.backend.js.MainModule
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.resolver.KotlinLibraryResolveResult
import org.jetbrains.kotlin.name.FqName
import java.io.File
import java.io.PrintWriter

// TODO more parameters for lowerings
fun buildCache(
    cachePath: String,
    project: Project,
    mainModule: MainModule.Klib,
    analyzer: AbstractAnalyzerWithCompilerReport,
    configuration: CompilerConfiguration,
    allDependencies: KotlinLibraryResolveResult,
    friendDependencies: List<KotlinLibrary>,
    exportedDeclarations: Set<FqName> = emptySet(),
) {

    val icData = if (allDependencies.getFullList().size == 1) {
        prepareSingleLibraryIcCache(project, analyzer, configuration, mainModule.lib, allDependencies, friendDependencies, exportedDeclarations)
    } else null

    icData?.writeTo(File(cachePath))

    // TODO md5
    // TODO perform lowerings
    CacheInfo(cachePath, mainModule.lib.libraryFile.absolutePath, icData != null).save()
}

fun checkCaches(
    allDependencies: KotlinLibraryResolveResult,
    cachePaths: List<String>,
): Map<String, SerializedIcData> {
    val allLibs = allDependencies.getFullList().map { it.libraryFile.absolutePath }.toSet()

    val caches = cachePaths.map { CacheInfo.load(it)}

    val missedLibs = allLibs - caches.map { it.libPath }
    if (!missedLibs.isEmpty()) {
        error("Missing caches for libraries: ${missedLibs}")
    }

    val result = mutableMapOf<String, SerializedIcData>()

    for (c in caches) {
        if (c.libPath !in allLibs) error("Missing library: ${c.libPath}")

        if (c.hasIcData) {
            result[c.libPath] = File(c.path).readIcData()
        }
    }

    return result;
}

// TODO md5 hash
data class CacheInfo(val path: String, val libPath: String, val hasIcData: Boolean) {
    fun save() {
        PrintWriter(File(File(path), "info")).use {
            it.println(libPath)
            it.println(hasIcData)
        }
    }

    companion object {
        fun load(path: String): CacheInfo {
            val (libPath, hasIcData) = File(File(path), "info").readLines()

            return CacheInfo(path, libPath, hasIcData == "true")
        }
    }
}
