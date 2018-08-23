/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.platform.impl

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.PersistentLibraryKind
import com.intellij.openapi.roots.ui.configuration.libraries.CustomLibraryDescription
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.idea.caches.project.implementingModules
import org.jetbrains.kotlin.idea.framework.CommonLibraryKind
import org.jetbrains.kotlin.idea.framework.CommonStandardLibraryDescription
import org.jetbrains.kotlin.idea.framework.getCommonRuntimeLibraryVersion
import org.jetbrains.kotlin.idea.platform.IdeTargetPlatformKindTooling
import org.jetbrains.kotlin.idea.platform.tooling
import org.jetbrains.kotlin.idea.project.targetPlatform
import org.jetbrains.kotlin.idea.util.module
import org.jetbrains.kotlin.platform.IdeTargetPlatformKind
import org.jetbrains.kotlin.platform.impl.CommonIdeTargetPlatformKind
import org.jetbrains.kotlin.platform.impl.isCommon
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import javax.swing.Icon

object CommonIdeTargetPlatformKindTooling : IdeTargetPlatformKindTooling {
    override val kind = CommonIdeTargetPlatformKind

    const val MAVEN_COMMON_STDLIB_ID = "kotlin-stdlib-common" // TODO: KotlinCommonMavenConfigurator

    override val libraryKind = CommonLibraryKind

    override fun getLibraryDescription(project: Project) = CommonStandardLibraryDescription(project)

    override fun compilerArgumentsForProject(project: Project): CommonCompilerArguments? = null

    override val mavenLibraryIds = listOf(MAVEN_COMMON_STDLIB_ID)

    override val gradlePluginId = "kotlin-platform-common"

    override fun getTestIcon(declaration: KtNamedDeclaration, descriptor: DeclarationDescriptor): Icon? {
        val icons = IdeTargetPlatformKindTooling.getInstances()
            .mapNotNull { getTestIcon(declaration, descriptor) }
            .takeIf { it.isNotEmpty() }
            ?: return null

        return icons.distinct().singleOrNull() ?: AllIcons.RunConfigurations.TestState.Run
    }

    override fun acceptsAsEntryPoint(function: KtFunction): Boolean {
        val module = function.containingKtFile.module ?: return false
        return module.implementingModules.any { implementingModule ->
            implementingModule.targetPlatform?.kind?.takeIf { !it.isCommon }?.tooling?.acceptsAsEntryPoint(function) ?: false
        }
    }

    override fun getLibraryVersionProvider(project: Project): (Library) -> String? {
        return ::getCommonRuntimeLibraryVersion
    }
}