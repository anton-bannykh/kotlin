/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

@file:JvmName("JsIdePlatformUtil")
package org.jetbrains.kotlin.platform.impl

import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.config.TargetPlatformVersion
import org.jetbrains.kotlin.js.resolve.JsPlatform
import org.jetbrains.kotlin.platform.IdeTargetPlatform
import org.jetbrains.kotlin.platform.IdeTargetPlatformKind

object JsIdeTargetPlatformKind : IdeTargetPlatformKind<JsIdeTargetPlatformKind>() {
    override val compilerPlatform = JsPlatform

    override val platforms = listOf(Platform)
    override val defaultPlatform = Platform

    override val argumentsClass = K2JSCompilerArguments::class.java

    object Platform : IdeTargetPlatform<JsIdeTargetPlatformKind, K2JSCompilerArguments>() {
        override val kind = JsIdeTargetPlatformKind
        override val version = TargetPlatformVersion.NoVersion
        override fun createArguments(init: K2JSCompilerArguments.() -> Unit) = K2JSCompilerArguments().apply(init)
    }
}

val IdeTargetPlatformKind<*>?.isJavaScript
    get() = this is JsIdeTargetPlatformKind

val IdeTargetPlatform<*, *>?.isJavaScript
    get() = this is JsIdeTargetPlatformKind.Platform
