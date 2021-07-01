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
import org.jetbrains.kotlin.library.impl.javaFile
import org.jetbrains.kotlin.library.resolver.KotlinLibraryResolveResult
import org.jetbrains.kotlin.name.FqName
import java.io.File
import java.io.PrintWriter
import java.security.MessageDigest

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
    // TODO: also hash dependencies
    val md5 = mainModule.lib.libraryFile.javaFile().md5()
    val oldCacheInfo = CacheInfo.load(cachePath)
    if (oldCacheInfo != null && md5 == oldCacheInfo.md5) return

    // TODO: clean

    // TODO: lower all the klibs
    val icData = if (allDependencies.getFullList().size == 1) {
        prepareSingleLibraryIcCache(project, analyzer, configuration, mainModule.lib, allDependencies, friendDependencies, exportedDeclarations)
    } else null

    icData?.writeTo(File(cachePath))

    CacheInfo(cachePath, mainModule.lib.libraryFile.absolutePath, md5, icData != null).save()
}

private fun File.md5(): ULong {
    val md5 = MessageDigest.getInstance("MD5")

    fun File.process(prefix: String = "") {
        if (isDirectory) {
            this.listFiles()!!.sortedBy { it.name }.forEach {
                md5.digest((prefix + it.name).toByteArray())
                it.process(prefix + it.name + "/")
            }
        } else {
            md5.digest(readBytes())
        }
    }

    this.process()

    val d = md5.digest()

    return ((d[0].toULong() and 0xFFUL)
            or ((d[1].toULong() and 0xFFUL) shl 8)
            or ((d[2].toULong() and 0xFFUL) shl 16)
            or ((d[3].toULong() and 0xFFUL) shl 24)
            or ((d[4].toULong() and 0xFFUL) shl 32)
            or ((d[5].toULong() and 0xFFUL) shl 40)
            or ((d[6].toULong() and 0xFFUL) shl 48)
            or ((d[7].toULong() and 0xFFUL) shl 56)
            )
}

fun checkCaches(
    allDependencies: KotlinLibraryResolveResult,
    cachePaths: List<String>,
): Map<String, SerializedIcData> {
    val allLibs = allDependencies.getFullList().map { it.libraryFile.absolutePath }.toSet()

    val caches = cachePaths.map { CacheInfo.load(it)!! }

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
data class CacheInfo(val path: String, val libPath: String, val md5: ULong, val hasIcData: Boolean) {
    fun save() {
        PrintWriter(File(File(path), "info")).use {
            it.println(libPath)
            it.println(md5.toString(16))
            it.println(hasIcData)
        }
    }

    companion object {
        fun load(path: String): CacheInfo? {
            val info = File(File(path), "info")

            if (!info.isFile) return null

            val (libPath, md5, hasIcData) = File(File(path), "info").readLines()

            return CacheInfo(path, libPath, md5.toULong(16), hasIcData == "true")
        }
    }
}
