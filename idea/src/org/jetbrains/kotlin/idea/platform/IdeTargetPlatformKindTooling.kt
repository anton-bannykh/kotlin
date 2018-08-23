/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.platform

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.PersistentLibraryKind
import com.intellij.openapi.roots.ui.configuration.libraries.CustomLibraryDescription
import org.jetbrains.kotlin.ApplicationExtensionDescriptor
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.platform.IdeTargetPlatformKind
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import javax.swing.Icon

interface IdeTargetPlatformKindTooling {
    val kind: IdeTargetPlatformKind<*>

    val libraryKind: PersistentLibraryKind<*>?

    val mavenLibraryIds: List<String>
    val gradlePluginId: String

    fun compilerArgumentsForProject(project: Project): CommonCompilerArguments?

    fun getLibraryDescription(project: Project): CustomLibraryDescription

    fun getLibraryVersionProvider(project: Project): (Library) -> String?

    fun getTestIcon(declaration: KtNamedDeclaration, descriptor: DeclarationDescriptor): Icon?

    fun acceptsAsEntryPoint(function: KtFunction): Boolean

    companion object : ApplicationExtensionDescriptor<IdeTargetPlatformKindTooling>(
        "org.jetbrains.kotlin.idea.platform.IdeTargetPlatformKindTooling", IdeTargetPlatformKindTooling::class.java
    )
}

private val CACHED_TOOLING_SUPPORT = run {
    val allPlatformKinds = IdeTargetPlatformKind.ALL_KINDS
    val groupedTooling = IdeTargetPlatformKindTooling.getInstances().groupBy { it.kind }.mapValues { it.value.single() }

    for (kind in allPlatformKinds) {
        if (kind !in groupedTooling) {
            throw IllegalStateException(
                "Tooling support for the platform '$kind' is missing. " +
                        "Implement 'IdeTargetPlatformKindTooling' for it."
            )
        }
    }

    groupedTooling
}

val IdeTargetPlatformKind<*>.tooling: IdeTargetPlatformKindTooling
    get() = CACHED_TOOLING_SUPPORT[this] ?: error("Unknown platform $this")