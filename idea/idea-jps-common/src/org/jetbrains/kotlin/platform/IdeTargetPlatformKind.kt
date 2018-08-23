/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.platform

import org.jetbrains.kotlin.ApplicationExtensionDescriptor
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.resolve.TargetPlatform

abstract class IdeTargetPlatformKind<Kind : IdeTargetPlatformKind<Kind>> {
    abstract val compilerPlatform: TargetPlatform
    abstract val platforms: List<IdeTargetPlatform<Kind, *>>

    abstract val defaultPlatform: IdeTargetPlatform<Kind, *>

    abstract val argumentsClass: Class<out CommonCompilerArguments>

    val name: String
        get() = compilerPlatform.platformName

    override fun toString() = name

    companion object : ApplicationExtensionDescriptor<IdeTargetPlatformKind<*>>(
        "org.jetbrains.kotlin.platform.TargetPlatformKind", IdeTargetPlatformKind::class.java
    ) {
        val ALL_KINDS = getInstances()
        val All_PLATFORMS = getInstances().flatMap { it.platforms }
    }
}

fun IdeTargetPlatformKind<*>?.orDefault(): IdeTargetPlatformKind<*> {
    return this ?: DefaultIdeTargetPlatformKindProvider.defaultPlatform.kind
}

fun IdeTargetPlatform<*, *>?.orDefault(): IdeTargetPlatform<*, *> {
    return this ?: DefaultIdeTargetPlatformKindProvider.defaultPlatform
}