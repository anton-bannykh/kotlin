/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.blackbox

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.konan.test.blackbox.support.EnforcedHostTarget
import org.jetbrains.kotlin.konan.test.blackbox.support.compilation.callCompiler
import org.jetbrains.kotlin.konan.test.blackbox.support.settings.KotlinNativeClassLoader
import org.jetbrains.kotlin.konan.test.blackbox.support.settings.PlatformLibs
import org.jetbrains.kotlin.konan.test.blackbox.support.settings.configurables
import org.jetbrains.kotlin.konan.test.blackbox.support.settings.provisionedXcodeCompilerArgs
import org.jetbrains.kotlin.native.executors.runProcess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("caches")
@EnforcedHostTarget
class ObjCSubclassCacheTest : AbstractNativeSimpleTest() {
    // KT-90589: the cache entry points of final fake overrides (KT-87777) must not be generated for methods
    // implemented by an external Objective-C class: there is no Kotlin implementation for them to call.
    @Test
    fun finalFakeOverridesOfExternalObjCMethodsHaveNoUnresolvedEntryPoints() {
        assumeTrue(targets.testTarget.family.isAppleFamily)

        val source = buildDir.resolve("lib.kt").apply {
            writeText(
                """
                import platform.darwin.NSObject

                class KotlinSubclass : NSObject() {
                    fun foo(): Int = 42
                }

                fun sharedSmoke(): Int = KotlinSubclass().foo()
                """.trimIndent()
            )
        }
        val library = buildDir.resolve("lib.klib")
        compile("-p", "library", source.path, "-o", library.path)

        val cacheDir = buildDir.resolve("cache").apply { mkdirs() }
        compile(
            "-p", "static", "-g", "-Xenable-incremental-compilation", "-Xic-cache-dir=${cacheDir.path}",
            "-Xinclude=${library.path}", "-o", buildDir.resolve("shared").path,
        )
        val staticLibrary = buildDir.resolve("libshared.a")
        assertTrue(staticLibrary.exists()) { "$staticLibrary must have been produced" }

        // The static library is self-contained with respect to Kotlin code: every Kotlin symbol it references
        // must be defined in it, otherwise the consumer only links with `-dead_strip`.
        fun kotlinSymbols(vararg options: String): Set<String> {
            val result = runProcess("${testRunSettings.configurables.absoluteLlvmHome}/bin/llvm-nm", *options, staticLibrary.path)
            return result.stdout.lineSequence()
                .map { it.trim().substringAfterLast(' ') }
                .filter { it.startsWith("_kfun:") }
                .toSet()
        }
        val unresolved = kotlinSymbols("--undefined-only") - kotlinSymbols("--defined-only")
        assertEquals(emptySet<String>(), unresolved, "Unresolved Kotlin symbols in $staticLibrary")
    }

    private fun compile(vararg args: String) {
        val compilerArgs = buildList {
            addAll(args)
            testRunSettings.get<PlatformLibs>().compilerFlag?.let { add(it) }
            addAll(provisionedXcodeCompilerArgs)
        }.toTypedArray()
        val result = callCompiler(
            compilerArgs = compilerArgs,
            kotlinNativeClassLoader = testRunSettings.get<KotlinNativeClassLoader>().classLoader,
        )
        assertEquals(ExitCode.OK, result.exitCode) {
            "Compilation failed: ${compilerArgs.joinToString(" ")}\n${result.toolOutput}"
        }
    }
}
