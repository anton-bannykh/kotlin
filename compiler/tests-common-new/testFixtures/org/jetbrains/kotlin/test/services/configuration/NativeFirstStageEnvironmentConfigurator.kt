/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.services.configuration

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.konan.config.*
import org.jetbrains.kotlin.konan.library.KlibNativeDistributionLibraryProvider
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.platform.konan.isNative
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.CompilationStage
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.cliBasedFacadesEnabled
import org.jetbrains.kotlin.test.services.targetPlatform
import java.io.File

class NativeFirstStageEnvironmentConfigurator(
    testServices: TestServices,
    private val customNativeHome: File? = null
) : NativeEnvironmentConfigurator(testServices, customNativeHome) {
    override val compilationStage: CompilationStage
        get() = CompilationStage.FIRST

    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        if (!module.targetPlatform(testServices).isNative()) return

        customNativeHome?.let {
            configuration.konanHome = it.absolutePath
            System.setProperty("kotlin.native.home", it.absolutePath) // TODO KT-84799: remove the line after dropping forward testing against 2.3 compiler
        }

        configuration.konanProducedArtifactKind = CompilerOutputKind.LIBRARY
        configuration.konanOutputPath = getKlibArtifactDir(testServices, module.name).absolutePath
        configuration.konanDontCompressKlib = true

        val dependencies = module.regularDependencies.map { getKlibArtifactDir(testServices, it.dependencyModule.name).absolutePath }
        val friends = module.friendDependencies.map { getKlibArtifactDir(testServices, it.dependencyModule.name).absolutePath }

        val runtimeDependencies = getRuntimeLibraryProviders(module).flatMap { provider ->
            // Ignore `KlibNativeDistributionLibraryProvider`, because it is anyway applied in loadNativeKlibsInProductionPipeline().
            if (provider is KlibNativeDistributionLibraryProvider) emptyList() else provider.getLibraryPaths()
        }

        configuration.konanLibraries = runtimeDependencies + dependencies + friends
        configuration.konanFriendLibraries = friends

        if (testServices.cliBasedFacadesEnabled) {
            configuration.addSourcesForDependsOnClosure(module, testServices)
        }
    }
}
