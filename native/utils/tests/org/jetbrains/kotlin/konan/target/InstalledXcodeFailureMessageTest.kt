/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.target

import org.jetbrains.kotlin.konan.MissingXcodeException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// KT-90283
internal class InstalledXcodeFailureMessageTest {
    @Test
    fun `xcrun failure message names the Xcode and contains the xcrun output`() {
        assumeTrue(HostManager.hostIsMac)
        val developerDir = "/nonexistent/KT-90283/Xcode.app/Contents/Developer"

        val message = assertThrows<MissingXcodeException> { Xcode.forDeveloperDir(developerDir).additionalTools }.message

        assertTrue("Failed command: DEVELOPER_DIR=$developerDir /usr/bin/xcrun -f bitcode-build-tool" in message, message)
        assertTrue("Xcode: $developerDir (passed to xcrun as DEVELOPER_DIR)" in message, message)
        assertTrue("xcrun: error: missing DEVELOPER_DIR path: $developerDir" in message, message)
        assertTrue("There is no Xcode at the DEVELOPER_DIR path." in message, message)
    }

    @Test
    fun `license agreement not accepted`() {
        val errors = "You have not agreed to the Xcode license agreements. " +
                "Please run 'sudo xcodebuild -license' from within a Terminal window to review and agree to the Xcode and Apple SDKs license."
        val developerDir = "/Users/me/.konan/dependencies/xcode_27.0_27A266a/Contents/Developer"

        val hint = xcrunFailureHint(errors, developerDir)
        assertTrue("The Xcode license agreement has not been accepted" in hint, hint)
        assertTrue("'sudo DEVELOPER_DIR=$developerDir xcodebuild -license accept'" in hint, hint)

        assertTrue("'sudo xcodebuild -license accept'" in xcrunFailureHint(errors, developerDir = null))
    }

    @Test
    fun `first launch not finished`() {
        val errors = "xcodebuild: error: A required plugin failed to load. Please ensure system content is up-to-date — " +
                "try running 'xcodebuild -runFirstLaunch'."
        val hint = xcrunFailureHint(errors, developerDir = null)
        assertTrue("has not finished its first launch setup" in hint, hint)
        assertTrue("'sudo xcodebuild -runFirstLaunch'" in hint, hint)
    }

    @Test
    fun `no full Xcode selected`() {
        val commandLineToolsOnly = "xcode-select: error: tool 'xcodebuild' requires Xcode, " +
                "but active developer directory '/Library/Developer/CommandLineTools' is a command line tools instance"
        val noDeveloperTools = "xcrun: error: invalid active developer path (/Library/Developer/CommandLineTools), " +
                "missing xcrun at: /Library/Developer/CommandLineTools/usr/bin/xcrun"
        for (errors in listOf(commandLineToolsOnly, noDeveloperTools)) {
            val hint = xcrunFailureHint(errors, developerDir = null)
            assertTrue("The full Xcode is required, but it is not installed or not selected." in hint, hint)
            assertTrue("xcode-select --switch" in hint, hint)
        }
    }

    @Test
    fun `unknown failure`() {
        val hint = xcrunFailureHint("xcrun: error: unable to find utility \"foo\", not a developer tool or in PATH", developerDir = null)
        assertTrue("Try running the failed command in Terminal" in hint, hint)
    }
}
