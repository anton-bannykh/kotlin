/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.ir.addChild
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.utils.getJsModule
import org.jetbrains.kotlin.ir.backend.js.utils.getJsQualifier
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFileSymbolImpl
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


class MoveBodilessDeclarationsToSeparatePlaceLowering(private val context: JsIrBackendContext) : DeclarationTransformer {

    fun isBuiltInClass(declaration: IrDeclaration): Boolean =
        declaration is IrClass && declaration.fqNameWhenAvailable in BODILESS_BUILTIN_CLASSES

    fun collectExternalClasses(container: IrDeclarationContainer, includeCurrentLevel: Boolean): List<IrClass> {
        val externalClasses =
            container.declarations.filterIsInstance<IrClass>().filter { it.isEffectivelyExternal() }

        val nestedExternalClasses =
            externalClasses.flatMap { collectExternalClasses(it, true) }

        return if (includeCurrentLevel)
            externalClasses + nestedExternalClasses
        else
            nestedExternalClasses
    }

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        val irFile = declaration.parent as? IrFile ?: error("Only top-level declarations within an IrFile are allowed for this lowering")

        context.externalNestedClasses += collectExternalClasses(irFile, includeCurrentLevel = false)

        if (irFile.getJsModule() != null || irFile.getJsQualifier() != null) {
            val newFile = context.packageLevelJsModules.getOrPut(irFile) {
                val newFragmentDescriptor = EmptyPackageFragmentDescriptor(context.module, irFile.fqName)
                val newFragmentSymbol = IrFileSymbolImpl(newFragmentDescriptor)
                val newFragment = IrFileImpl(irFile.fileEntry, newFragmentSymbol)
                newFragment.annotations += irFile.annotations
                newFragment
            }

            newFile.declarations += declaration
            // TODO this is WAT?
//            declaration.parent = newFile

            return emptyList()
        } else {
            val d = declaration as? IrDeclarationWithName ?: return null

            if (isBuiltInClass(d)) {
                context.bodilessBuiltInsPackageFragment.addChild(d)
                return emptyList()
            } else if (d.isEffectivelyExternal()) {
                if (d.getJsModule() != null)
                    context.declarationLevelJsModules.add(d)

                context.externalPackageFragment.addChild(d)
                return emptyList()
            }

            return null
        }
    }
}
