/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

@file:JvmName("CommonIdePlatformUtil")
package org.jetbrains.kotlin.platform.impl

import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments
import org.jetbrains.kotlin.config.TargetPlatformVersion
import org.jetbrains.kotlin.platform.IdePlatform
import org.jetbrains.kotlin.platform.IdePlatformKind
import org.jetbrains.kotlin.resolve.TargetPlatform

object CommonIdePlatformKind : IdePlatformKind<CommonIdePlatformKind>() {
    override val compilerPlatform = TargetPlatform.Common

    override val platforms = listOf(Platform)
    override val defaultPlatform = Platform

    override val argumentsClass = K2MetadataCompilerArguments::class.java

    object Platform : IdePlatform<CommonIdePlatformKind, CommonCompilerArguments>() {
        override val kind = CommonIdePlatformKind
        override val version = TargetPlatformVersion.NoVersion
        override fun createArguments(init: CommonCompilerArguments.() -> Unit) = K2MetadataCompilerArguments().apply(init)
    }
}

val IdePlatformKind<*>?.isCommon
    get() = this is CommonIdePlatformKind

val IdePlatform<*, *>?.isCommon
    get() = this is CommonIdePlatformKind.Platform