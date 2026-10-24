/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.blackbox

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.konan.test.blackbox.support.EnforcedHostTarget
import org.jetbrains.kotlin.konan.test.blackbox.support.TestCompilerArgs
import org.jetbrains.kotlin.konan.test.blackbox.support.compilation.TestCompilationResult.Companion.assertSuccess
import org.jetbrains.kotlin.konan.test.blackbox.support.compilation.callCompiler
import org.jetbrains.kotlin.konan.test.blackbox.support.settings.KotlinNativeClassLoader
import org.jetbrains.kotlin.konan.test.blackbox.support.settings.configurables
import org.jetbrains.kotlin.native.executors.runProcess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@EnforcedHostTarget
class SyntheticVariablesDebugInfoTest : AbstractNativeSimpleTest() {
    // KT-86710: variables created by the compiler must not be visible in the debugger. The catch parameters of the
    // try-catch generated around cinterop calls returning `CValue` (`e`) and of the catch-all generated for
    // `finally` blocks (`t`) used to be described in the debug info as local variables.
    @Test
    fun syntheticCatchParametersHaveNoDebugInfo() {
        val defFile = buildDir.resolve("example.def").apply {
            writeText(
                """
                package example
                ---
                typedef struct { int a; int b; } Example;
                static inline Example getExampleStruct(void) { Example result = { 1, 2 }; return result; }
                """.trimIndent()
            )
        }
        val cinteropKlib = cinteropToLibrary(defFile, buildDir, TestCompilerArgs.EMPTY).assertSuccess().resultingArtifact

        val source = buildDir.resolve("main.kt").apply {
            writeText(
                """
                import example.*
                import kotlinx.cinterop.*

                fun main() {
                    val example = getExampleStruct()
                    try {
                        println(example.useContents { a + b })
                    } catch (userException: IllegalStateException) {
                        println(userException)
                    } finally {
                        println("finally")
                    }
                }
                """.trimIndent()
            )
        }
        val executable = buildDir.resolve("main.${targets.testTarget.family.exeSuffix}")
        val compilerArgs = arrayOf(
            "-g", "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
            "-l", cinteropKlib.path, source.path, "-o", executable.path,
        )
        val result = callCompiler(
            compilerArgs = compilerArgs,
            kotlinNativeClassLoader = testRunSettings.get<KotlinNativeClassLoader>().classLoader,
        )
        assertEquals(ExitCode.OK, result.exitCode) {
            "Compilation failed: ${compilerArgs.joinToString(" ")}\n${result.toolOutput}"
        }

        // On Apple targets, the debug info is in the dSYM bundle produced next to the executable.
        val debugInfoFile = executable.resolveSibling("${executable.name}.dSYM").takeIf { it.exists() } ?: executable
        val dump = runProcess(
            "${testRunSettings.configurables.absoluteLlvmHome}/bin/llvm-dwarfdump",
            "--name=kfun:#main(){}", "--show-children", debugInfoFile.path,
        ).stdout
        assertEquals(listOf("example", "userException"), dump.topLevelVariableNames()) {
            "Unexpected local variables of main() in the debug info:\n$dump"
        }
    }

    // Names of the variables that are direct children of the dumped subprogram (inlined functions excluded).
    private fun String.topLevelVariableNames(): List<String> {
        val tagLine = Regex("""^0x[0-9a-f]+:( +)(DW_TAG_\w+)""")
        val nameLine = Regex("""^\s+DW_AT_name\s+\("(.*)"\)""")
        val subprogramIndent = lines().firstNotNullOf { tagLine.find(it) }.groupValues[1].length
        val result = mutableListOf<String>()
        var inTopLevelVariable = false
        for (line in lines()) {
            val tag = tagLine.find(line)
            if (tag != null) {
                inTopLevelVariable = tag.groupValues[2] == "DW_TAG_variable" && tag.groupValues[1].length == subprogramIndent + 2
                continue
            }
            if (inTopLevelVariable) {
                nameLine.find(line)?.let {
                    result += it.groupValues[1]
                    inTopLevelVariable = false
                }
            }
        }
        return result
    }
}
