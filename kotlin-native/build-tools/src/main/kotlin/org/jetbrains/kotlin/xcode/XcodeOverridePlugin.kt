/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.xcode

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.WHOLE_XCODE_PROPERTY
import org.jetbrains.kotlin.isInternalServerEnabled
import org.jetbrains.kotlin.isWholeXcodeProvisioningEnabled
import org.jetbrains.kotlin.konan.MissingXcodeException
import org.jetbrains.kotlin.konan.target.*
import org.jetbrains.kotlin.konan.util.DependencyProcessor
import org.jetbrains.kotlin.nativeDistribution.nativeProtoDistribution

open class XcodeOverridePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Only for macOS hosts.
        if (!HostManager.hostIsMac)
            return
        val wholeXcodeOn = project.isWholeXcodeProvisioningEnabled()
        // We snapshot the Xcode into Xcode.xcodeOverride so that nothing resolves the Apple toolchain live via xcrun at configuration time.
        // The cases:
        //  - whole-Xcode on                         -> snapshot the provisioned Xcode ;
        //  - whole-Xcode off, no internal server    -> snapshot the system-selected Xcode ;
        //  - whole-Xcode off, internal server       -> nothing to do: the toolchain resolves from the downloaded
        //                                              dependency paths (the InternalServer branch), with no xcrun.
        if (!wholeXcodeOn && DependencyProcessor.isInternalSeverAvailable)
            return
        val xcodeProvider: Provider<Xcode> = project.providers.of(XcodeValueSource::class) {
            parameters.wholeXcode.set(wholeXcodeOn)
            if (wholeXcodeOn) {
                parameters.konanProperties.set(project.nativeProtoDistribution.konanProperties)
                project.providers.gradleProperty("konan.data.dir").orNull?.let { parameters.konanDataDir.set(it) }
                parameters.teamCity.set(project.providers.environmentVariable("TEAMCITY_VERSION").isPresent)
                parameters.serverEnabled.set(project.isInternalServerEnabled())
            }
        }
        // Don't obtain the Xcode right away: applying this plugin must not fail the configuration of the whole build
        // just because Xcode is not set up (KT-90283). The value source is obtained once some Xcode property is needed.
        Xcode.xcodeOverride = LazyXcode(
                xcodeProvider,
                failureHint = if (wholeXcodeOn) {
                    "This build uses the Xcode provisioned for it ($WHOLE_XCODE_PROPERTY=true). " +
                            "To use the Xcode selected with xcode-select instead, pass -P$WHOLE_XCODE_PROPERTY=false."
                } else null
        )
    }
}

/**
 * Delegates to the [Xcode] provided by [xcode], obtaining it only when one of its properties is first accessed.
 * [failureHint], if any, is appended to the messages of [MissingXcodeException]s thrown by the properties.
 */
private class LazyXcode(private val xcode: Provider<Xcode>, private val failureHint: String?) : Xcode {
    private val resolved: Xcode by lazy { xcode.get() }

    private inline fun <T> property(get: Xcode.() -> T): T = try {
        resolved.get()
    } catch (e: Exception) {
        val missingXcode = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<MissingXcodeException>().firstOrNull()
        if (missingXcode == null || failureHint == null) throw e
        throw MissingXcodeException("${missingXcode.message}\n$failureHint", e)
    }

    override val toolchain: String get() = property { toolchain }
    override val macosxSdk: String get() = property { macosxSdk }
    override val iphoneosSdk: String get() = property { iphoneosSdk }
    override val iphonesimulatorSdk: String get() = property { iphonesimulatorSdk }
    override val version: XcodeVersion get() = property { version }
    override val appletvosSdk: String get() = property { appletvosSdk }
    override val appletvsimulatorSdk: String get() = property { appletvsimulatorSdk }
    override val watchosSdk: String get() = property { watchosSdk }
    override val watchsimulatorSdk: String get() = property { watchsimulatorSdk }
    override val additionalTools: String get() = property { additionalTools }
    override val simulatorRuntimes: String get() = property { simulatorRuntimes }
}
