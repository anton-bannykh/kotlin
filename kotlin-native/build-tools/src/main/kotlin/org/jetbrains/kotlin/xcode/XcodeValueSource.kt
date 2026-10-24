/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.xcode

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.jetbrains.kotlin.XcodeProvisioningSpec
import org.jetbrains.kotlin.konan.KonanExternalToolFailure
import org.jetbrains.kotlin.konan.MissingXcodeException
import org.jetbrains.kotlin.konan.target.*
import org.jetbrains.kotlin.provisionXcodeOrFail
import java.util.Properties

/**
 * A value of an [Xcode] property resolved inside [XcodeValueSource], or the reason why it couldn't be resolved.
 *
 * Failures are kept per property and reported only when the property is used (KT-90283): a single unavailable tool
 * (e.g. `bitcode-build-tool`) must not fail builds that never need it.
 */
private data class XcodeSnapshotProperty<T : Any>(val value: T?, val failure: String?) {
    fun get(): T = value ?: throw MissingXcodeException(failure!!)

    companion object {
        fun <T : Any> of(resolve: () -> T): XcodeSnapshotProperty<T> = try {
            XcodeSnapshotProperty(resolve(), null)
        } catch (e: MissingXcodeException) {
            XcodeSnapshotProperty(null, e.message)
        } catch (e: KonanExternalToolFailure) {
            XcodeSnapshotProperty(null, e.message ?: "Failed to run ${e.toolName}")
        }
    }
}

private data class XcodeSnapshot(
        private val additionalToolsProperty: XcodeSnapshotProperty<String>,
        private val appletvosSdkProperty: XcodeSnapshotProperty<String>,
        private val appletvsimulatorSdkProperty: XcodeSnapshotProperty<String>,
        private val iphoneosSdkProperty: XcodeSnapshotProperty<String>,
        private val iphonesimulatorSdkProperty: XcodeSnapshotProperty<String>,
        private val macosxSdkProperty: XcodeSnapshotProperty<String>,
        private val simulatorRuntimesProperty: XcodeSnapshotProperty<String>,
        private val toolchainProperty: XcodeSnapshotProperty<String>,
        private val versionProperty: XcodeSnapshotProperty<XcodeVersion>,
        private val watchosSdkProperty: XcodeSnapshotProperty<String>,
        private val watchsimulatorSdkProperty: XcodeSnapshotProperty<String>,
) : Xcode {
    constructor(original: Xcode) : this(
            additionalToolsProperty = XcodeSnapshotProperty.of { original.additionalTools },
            appletvosSdkProperty = XcodeSnapshotProperty.of { original.appletvosSdk },
            appletvsimulatorSdkProperty = XcodeSnapshotProperty.of { original.appletvsimulatorSdk },
            iphoneosSdkProperty = XcodeSnapshotProperty.of { original.iphoneosSdk },
            iphonesimulatorSdkProperty = XcodeSnapshotProperty.of { original.iphonesimulatorSdk },
            macosxSdkProperty = XcodeSnapshotProperty.of { original.macosxSdk },
            simulatorRuntimesProperty = XcodeSnapshotProperty.of { original.simulatorRuntimes },
            toolchainProperty = XcodeSnapshotProperty.of { original.toolchain },
            versionProperty = XcodeSnapshotProperty.of { original.version },
            watchosSdkProperty = XcodeSnapshotProperty.of { original.watchosSdk },
            watchsimulatorSdkProperty = XcodeSnapshotProperty.of { original.watchsimulatorSdk },
    )

    override val additionalTools: String get() = additionalToolsProperty.get()
    override val appletvosSdk: String get() = appletvosSdkProperty.get()
    override val appletvsimulatorSdk: String get() = appletvsimulatorSdkProperty.get()
    override val iphoneosSdk: String get() = iphoneosSdkProperty.get()
    override val iphonesimulatorSdk: String get() = iphonesimulatorSdkProperty.get()
    override val macosxSdk: String get() = macosxSdkProperty.get()
    override val simulatorRuntimes: String get() = simulatorRuntimesProperty.get()
    override val toolchain: String get() = toolchainProperty.get()
    override val version: XcodeVersion get() = versionProperty.get()
    override val watchosSdk: String get() = watchosSdkProperty.get()
    override val watchsimulatorSdk: String get() = watchsimulatorSdkProperty.get()
}

abstract class XcodeValueSource : ValueSource<Xcode, XcodeValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        /** When `true`, snapshot the whole provisioned Xcode instead of the system-selected one. */
        val wholeXcode: Property<Boolean>

        /**
         * The distribution's `konan.properties`, read for `xcodeVersion`/`xcodeBuild`/`xcodeArtifactUrl`.
         * Required iff [wholeXcode].
         */
        val konanProperties: RegularFileProperty

        /** `konan.data.dir` Gradle property, if set (dependencies live under `<konanDataDir>/dependencies`). */
        val konanDataDir: Property<String>

        /** Whether this is a TeamCity build (agents must ship the expected Xcode via their image). */
        val teamCity: Property<Boolean>

        /**
         * Whether the internal server is enabled. Only then may an Xcode that is neither provisioned nor installed
         * be downloaded; otherwise the build fails with an actionable message.
         */
        val serverEnabled: Property<Boolean>
    }

    override fun obtain(): Xcode {
        val xcode = if (parameters.wholeXcode.getOrElse(false)) provisionedXcode() else Xcode.defaultCurrent()
        return XcodeSnapshot(xcode)
    }

    private fun provisionedXcode(): Xcode {
        val properties = Properties().apply {
            parameters.konanProperties.get().asFile.inputStream().use { load(it) }
        }

        fun requiredProperty(name: String) = properties.getProperty(name)
                ?: error("whole-Xcode provisioning is on but '$name' is missing from konan.properties")

        val xcodeApp = provisionXcodeOrFail(
                XcodeProvisioningSpec(
                        konanDataDir = parameters.konanDataDir.orNull,
                        version = requiredProperty("xcodeVersion"),
                        build = requiredProperty("xcodeBuild"),
                        artifactUrl = requiredProperty("xcodeArtifactUrl"),
                        serverEnabled = parameters.serverEnabled.getOrElse(false),
                        isTeamCity = parameters.teamCity.getOrElse(false),
                )
        )
        return Xcode.forDeveloperDir(xcodeApp.resolve("Contents/Developer").path)
    }
}
