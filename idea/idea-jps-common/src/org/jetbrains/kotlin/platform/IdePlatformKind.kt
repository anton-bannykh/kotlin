/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.platform

import org.jetbrains.kotlin.ApplicationExtensionDescriptor
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.resolve.TargetPlatform

abstract class IdePlatformKind<Kind : IdePlatformKind<Kind>> {
    abstract val compilerPlatform: TargetPlatform
    abstract val platforms: List<IdePlatform<Kind, *>>

    abstract val defaultPlatform: IdePlatform<Kind, *>

    abstract val argumentsClass: Class<out CommonCompilerArguments>

    val name: String
        get() = compilerPlatform.platformName

    override fun toString() = name

    companion object : ApplicationExtensionDescriptor<IdePlatformKind<*>>(
        "org.jetbrains.kotlin.platform.TargetPlatformKind", IdePlatformKind::class.java
    ) {
        val ALL_KINDS = getInstances()
        val All_PLATFORMS = getInstances().flatMap { it.platforms }
    }
}

fun IdePlatformKind<*>?.orDefault(): IdePlatformKind<*> {
    return this ?: DefaultIdeTargetPlatformKindProvider.defaultPlatform.kind
}

fun IdePlatform<*, *>?.orDefault(): IdePlatform<*, *> {
    return this ?: DefaultIdeTargetPlatformKindProvider.defaultPlatform
}