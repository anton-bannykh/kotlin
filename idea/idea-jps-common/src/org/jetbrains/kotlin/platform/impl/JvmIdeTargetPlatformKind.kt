/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

@file:JvmName("JvmIdePlatformUtil")
package org.jetbrains.kotlin.platform.impl

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.platform.IdeTargetPlatform
import org.jetbrains.kotlin.platform.IdeTargetPlatformKind
import org.jetbrains.kotlin.resolve.jvm.platform.JvmPlatform

object JvmIdeTargetPlatformKind : IdeTargetPlatformKind<JvmIdeTargetPlatformKind>() {
    override val compilerPlatform = JvmPlatform

    override val platforms = JvmTarget.values().map { ver -> Platform(ver) }

    override val defaultPlatform = Platform(JvmTarget.JVM_1_6)

    override val argumentsClass = K2JVMCompilerArguments::class.java

    data class Platform(override val version: JvmTarget) : IdeTargetPlatform<JvmIdeTargetPlatformKind, K2JVMCompilerArguments>() {
        override val kind get() = JvmIdeTargetPlatformKind

        override fun createArguments(init: K2JVMCompilerArguments.() -> Unit) = K2JVMCompilerArguments()
            .apply { jvmTarget = this@Platform.version.description }
            .apply(init)
    }
}

val IdeTargetPlatformKind<*>?.isJvm
    get() = this is JvmIdeTargetPlatformKind

val IdeTargetPlatform<*, *>?.isJvm
    get() = this is JvmIdeTargetPlatformKind.Platform