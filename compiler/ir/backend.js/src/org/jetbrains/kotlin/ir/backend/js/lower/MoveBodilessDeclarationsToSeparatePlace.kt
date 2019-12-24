/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.ir.addChild
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.utils.getJsModule
import org.jetbrains.kotlin.ir.backend.js.utils.getJsQualifier
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.symbols.IrFileSymbol
import org.jetbrains.kotlin.ir.util.UniqId
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.name.FqName

private val BODILESS_BUILTIN_CLASSES = listOf(
    "kotlin.String",
    "kotlin.Nothing",
    "kotlin.Array",
    "kotlin.Any",
    "kotlin.ByteArray",
    "kotlin.CharArray",
    "kotlin.ShortArray",
    "kotlin.IntArray",
    "kotlin.LongArray",
    "kotlin.FloatArray",
    "kotlin.DoubleArray",
    "kotlin.BooleanArray",
    "kotlin.Boolean",
    "kotlin.Byte",
    "kotlin.Short",
    "kotlin.Int",
    "kotlin.Float",
    "kotlin.Double",
    "kotlin.Function"
).map { FqName(it) }.toSet()

private class DescriptorlessIrFileSymbol : IrFileSymbol {
    override fun bind(owner: IrFile) {
        _owner = owner
    }

    override val descriptor: PackageFragmentDescriptor
        get() = error("Operation is unsupported")

    private var _owner: IrFile? = null
    override val owner get() = _owner!!

    override var uniqId: UniqId
        get() = error("Operation is unsupported")
        set(value) { error("Operation is unsupported") }

    override val isBound get() = _owner != null
}

private fun isBuiltInClass(declaration: IrDeclaration): Boolean =
    declaration is IrClass && declaration.fqNameWhenAvailable in BODILESS_BUILTIN_CLASSES

private fun collectExternalClasses(container: IrDeclarationContainer, includeCurrentLevel: Boolean): List<IrClass> {
    val externalClasses =
        container.declarations.filterIsInstance<IrClass>().filter { it.isEffectivelyExternal() }

    val nestedExternalClasses =
        externalClasses.flatMap { collectExternalClasses(it, true) }

    return if (includeCurrentLevel)
        externalClasses + nestedExternalClasses
    else
        nestedExternalClasses
}

fun moveBodilessDeclarationsToSeparatePlace(context: JsIrBackendContext, moduleFragment: IrModuleFragment) {
    MoveBodilessDeclarationsToSeparatePlaceLowering(context).let { moveBodiless ->
        moduleFragment.files.forEach {
            moveBodiless.lower(it)
        }
    }
}

class MoveBodilessDeclarationsToSeparatePlaceLowering(private val context: JsIrBackendContext) : DeclarationTransformer {

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        val irFile = declaration.parent as? IrFile ?: return null

        val externalPackageFragment by lazy {
            context.externalPackageFragment.getOrPut(irFile.symbol) {
                IrFileImpl(fileEntry = irFile.fileEntry, fqName = irFile.fqName, symbol = DescriptorlessIrFileSymbol()).also {
                    it.annotations += irFile.annotations
                }
            }
        }

        context.externalNestedClasses += collectExternalClasses(irFile, includeCurrentLevel = false)

        if (irFile.getJsModule() != null || irFile.getJsQualifier() != null) {
            externalPackageFragment.declarations += declaration
            declaration.parent = externalPackageFragment

            context.packageLevelJsModules += externalPackageFragment

            return emptyList()
        } else {
            val d = declaration as? IrDeclarationWithName ?: return null

            if (isBuiltInClass(d)) {
                context.bodilessBuiltInsPackageFragment.addChild(d)
                return emptyList()
            } else if (d.isEffectivelyExternal()) {
                if (d.getJsModule() != null)
                    context.declarationLevelJsModules.add(d)

                externalPackageFragment.declarations += d
                d.parent = externalPackageFragment

                return emptyList()
            }

            return null
        }
    }
}