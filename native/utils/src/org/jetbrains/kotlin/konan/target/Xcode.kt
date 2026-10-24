/*
 * Copyright 2010-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.konan.target

import org.jetbrains.kotlin.konan.KonanExternalToolFailure
import org.jetbrains.kotlin.konan.MissingXcodeException
import org.jetbrains.kotlin.konan.exec.Command
import java.io.File
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

data class XcodeVersion(val major: Int, val minor: Int) : Comparable<XcodeVersion> {
    override fun compareTo(other: XcodeVersion): Int {
        return when (val majorComparison = major.compareTo(other.major)) {
            0 -> minor.compareTo(other.minor)
            else -> majorComparison
        }
    }

    override fun toString(): String {
        return "$major.$minor"
    }

    companion object {
        fun parse(version: String): XcodeVersion? {
            val split = version.split("(\\s+|\\.|-)".toRegex())
            return XcodeVersion(
                major = split[0].toIntOrNull() ?: return null,
                minor = split.getOrNull(1)?.toIntOrNull() ?: return null,
            )
        }

        val maxTested = XcodeVersion(27, 0)
    }
}

interface Xcode {
    val toolchain: String
    val macosxSdk: String
    val iphoneosSdk: String
    val iphonesimulatorSdk: String
    val version: XcodeVersion
    val appletvosSdk: String
    val appletvsimulatorSdk: String
    val watchosSdk: String
    val watchsimulatorSdk: String
    // Xcode.app/Contents/Developer/usr
    val additionalTools: String
    val simulatorRuntimes: String

    fun pathToPlatformSdk(platformName: String): String = when (platformName.lowercase(Locale.getDefault())) {
        "macosx" -> macosxSdk
        "iphoneos" -> iphoneosSdk
        "iphonesimulator" -> iphonesimulatorSdk
        "appletvos" -> appletvosSdk
        "appletvsimulator" -> appletvsimulatorSdk
        "watchos" -> watchosSdk
        "watchsimulator" -> watchsimulatorSdk
        else -> error("Unknown Apple platform: $platformName")
    }

    companion object {
        // Don't cache the instance: the compiler might be executed in a Gradle daemon process,
        // so current Xcode might actually change between different invocations.
        @Deprecated("", ReplaceWith("this.findCurrent()"), DeprecationLevel.WARNING)
        val current: Xcode
            get() = findCurrent()

        var xcodeOverride: Xcode? = null

        fun findCurrent(): Xcode = xcodeOverride ?: defaultCurrent()

        fun defaultCurrent(): Xcode = InstalledXcode()

        /**
         * The whole Xcode installed at [developerDir] (a `…/Contents/Developer` path), with every `xcrun`/
         * `xcode-select` query routed to it via `DEVELOPER_DIR`. Used on the build side (see the build's
         * `XcodeValueSource`) to snapshot a provisioned Xcode inside a Gradle `ValueSource`, so its toolchain/SDK
         * paths are resolved once, configuration-cache-safely, instead of shelling out at configuration time.
         */
        fun forDeveloperDir(developerDir: String): Xcode = InstalledXcode(developerDir)
    }
}

/**
 * The Xcode selected for the current process. By default this is the system selection (`xcode-select`); when
 * [developerDir] is given it is passed as `DEVELOPER_DIR` to every `xcrun`/`xcode-select` invocation, so all paths,
 * the version and the simulator runtimes are resolved from that specific Xcode instead — e.g. a whole Xcode
 * provisioned under `$KONAN_DATA_DIR/dependencies/xcode_<version>_<build>`.
 */
internal class InstalledXcode(private val developerDir: String? = null) : Xcode {

    private val environment: Map<String, String> =
        developerDir?.let { mapOf("DEVELOPER_DIR" to it) } ?: emptyMap()

    override val toolchain by lazy {
        val ldPath = xcrun("-f", "ld") // = $toolchain/usr/bin/ld
        Path(ldPath).parent.parent.absolutePathString()
    }

    // The selected Xcode.app bundle (its `Contents/Developer` is what `xcode-select -print-path` returns), or `null`
    // for a Command-Line-Tools-only selection whose developer dir is not inside an `.app` bundle.
    internal val xcodeApp: File?
        get() = try {
            val developerPath = Command(listOf("/usr/bin/xcode-select", "-print-path"), environment = environment)
                .getOutputLines().firstOrNull()?.trim()
            developerPath?.let { File(it).parentFile?.parentFile?.takeIf { app -> app.exists() && app.name.endsWith(".app") } }
        } catch (e: Exception) {
            null
        }

    override val additionalTools: String by lazy {
        val bitcodeBuildToolPath = xcrun("-f", "bitcode-build-tool")
        Path(bitcodeBuildToolPath).parent.parent.absolutePathString()
    }

    override val simulatorRuntimes: String by lazy {
        Command(listOf("/usr/bin/xcrun", "simctl", "list", "runtimes", "-j"), environment = environment)
            .getOutputLines().joinToString(separator = "\n")
    }
    override val macosxSdk by lazy { getSdkPath("macosx") }
    override val iphoneosSdk by lazy { getSdkPath("iphoneos") }
    override val iphonesimulatorSdk by lazy { getSdkPath("iphonesimulator") }
    override val appletvosSdk by lazy { getSdkPath("appletvos") }
    override val appletvsimulatorSdk by lazy { getSdkPath("appletvsimulator") }
    override val watchosSdk: String by lazy { getSdkPath("watchos") }
    override val watchsimulatorSdk: String by lazy { getSdkPath("watchsimulator") }

    internal val xcodebuildVersion: XcodeVersion
        get() = xcrun("xcodebuild", "-version")
            .removePrefix("Xcode ")
            .parseXcodeVersion()

    internal val bundleVersion: XcodeVersion
        get() = bash("""/usr/libexec/PlistBuddy "$(xcode-select -print-path)/../Info.plist" -c "Print :CFBundleShortVersionString"""")
            .parseXcodeVersion()

    // ProductBuildVersion (e.g. "17E192") of the selected Xcode, read from its version.plist — the same source the CI
    // agent images use to name $KONAN_DATA_DIR/dependencies/xcode_<version>_<build> (vm-templates symlink-xcode.sh).
    internal val productBuildVersion: String
        get() = bash("""/usr/libexec/PlistBuddy "$(xcode-select -print-path)/../version.plist" -c "Print :ProductBuildVersion"""").trim()

    override val version by lazy {
        try {
            bundleVersion
        } catch (e: KonanExternalToolFailure) {
            xcodebuildVersion
        }
    }

    private fun xcrun(vararg args: String): String {
        val result = Command(listOf("/usr/bin/xcrun", *args), environment = environment).getResultCapturingErrors()
        result.outputLines.firstOrNull()?.takeIf { result.exitCode == 0 }?.let { return it }
        throw MissingXcodeException(xcrunFailureMessage(args.toList(), result))
    }

    // See also https://youtrack.jetbrains.com/issue/KT-50923.
    private fun xcrunFailureMessage(args: List<String>, result: Command.Result): String = buildString {
        val developerDirPrefix = developerDir?.let { "DEVELOPER_DIR=$it " }.orEmpty()
        val errors = result.errorLines.joinToString("\n")

        appendLine("An error occurred during an xcrun execution. Make sure that Xcode and its command line tools are properly installed.")
        appendLine("Failed command: $developerDirPrefix/usr/bin/xcrun ${args.joinToString(" ")}")
        appendLine("Xcode: ${describeXcode()}")
        appendLine("Exit code: ${result.exitCode}")
        if (errors.isNotBlank()) {
            appendLine("xcrun output:")
            result.errorLines.forEach { appendLine("    $it") }
        }
        append(xcrunFailureHint(errors, developerDir))
    }

    private fun describeXcode(): String = when {
        developerDir != null -> "$developerDir (passed to xcrun as DEVELOPER_DIR)"
        else -> {
            val inheritedDeveloperDir = System.getenv("DEVELOPER_DIR")
            val selectedPath = try {
                Command(listOf("/usr/bin/xcode-select", "-print-path")).getResultCapturingErrors()
                    .takeIf { it.exitCode == 0 }?.outputLines?.firstOrNull()?.trim()
            } catch (e: Exception) {
                null
            }
            when {
                inheritedDeveloperDir != null -> "$inheritedDeveloperDir (set by the DEVELOPER_DIR environment variable)"
                selectedPath != null -> "$selectedPath (selected with xcode-select)"
                else -> "unknown: 'xcode-select -print-path' failed"
            }
        }
    }

    private fun bash(command: String): String =
        Command(listOf("/bin/bash", "-c", command), environment = environment).getOutputLines().joinToString("\n")

    private fun getSdkPath(sdk: String) = xcrun("--sdk", sdk, "--show-sdk-path")

    private fun String.parseXcodeVersion(): XcodeVersion {
        return XcodeVersion.parse(this) ?: throw MissingXcodeException("Couldn't parse Xcode version from '$this'")
    }
}

/**
 * A suggestion for fixing a failed `xcrun` invocation, based on its standard error output [errors].
 * [developerDir] is the `DEVELOPER_DIR` xcrun was invoked with, if any.
 */
internal fun xcrunFailureHint(errors: String, developerDir: String?): String {
    val developerDirPrefix = developerDir?.let { "DEVELOPER_DIR=$it " }.orEmpty()
    return when {
        errors.contains("license", ignoreCase = true) ->
            // E.g. "You have not agreed to the Xcode license agreements. Please run 'sudo xcodebuild -license' ..."
            "The Xcode license agreement has not been accepted for this Xcode. " +
                    "To accept it, run 'sudo ${developerDirPrefix}xcodebuild -license accept', or launch this Xcode once and accept it there."
        errors.contains("runFirstLaunch") ->
            // E.g. "A required plugin failed to load. ... try running 'xcodebuild -runFirstLaunch'."
            "This Xcode has not finished its first launch setup. " +
                    "To finish it, run 'sudo ${developerDirPrefix}xcodebuild -runFirstLaunch', or launch this Xcode once."
        errors.contains("missing DEVELOPER_DIR path") ->
            "There is no Xcode at the DEVELOPER_DIR path. Make sure that DEVELOPER_DIR points to the 'Contents/Developer' directory of an installed Xcode."
        errors.contains("command line tools instance") || errors.contains("invalid active developer path") ->
            // A Command Line Tools-only setup, or no developer tools at all.
            "The full Xcode is required, but it is not installed or not selected. " +
                    "Install Xcode, launch it once to finish its setup, and select it with 'sudo xcode-select --switch /Applications/Xcode.app' " +
                    "(use the path to your Xcode)."
        else ->
            "Try running the failed command in Terminal and fix the errors by making Xcode (and its command line tools) configuration correct."
    }
}
