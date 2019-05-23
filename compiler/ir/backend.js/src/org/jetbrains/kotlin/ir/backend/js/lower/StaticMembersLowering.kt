/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.ir.addChild
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

// Move static member declarations from classes to top level
class StaticMembersLowering(val context: JsIrBackendContext) : DeclarationTransformer {

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        val staticDeclarationsInClasses = mutableListOf<IrDeclaration>()
        declaration.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.parent is IrClass && !declaration.isEffectivelyExternal())
                    staticDeclarationsInClasses.add(declaration)
                super.visitClass(declaration)
            }

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                if (declaration.parent is IrClass && declaration.isStaticMethodOfClass && !declaration.isEffectivelyExternal())
                    staticDeclarationsInClasses.add(declaration)
                super.visitSimpleFunction(declaration)
            }

            override fun visitVariable(declaration: IrVariable) {
                // TODO: Don't generate variables inside classes
                if (declaration.parent is IrClass)
                    staticDeclarationsInClasses.add(declaration)
                super.visitVariable(declaration)
            }
        })

        staticDeclarationsInClasses.forEach {
            val klass = it.parentAsClass
            klass.declarations.remove(it)
            it.parent = declaration.parent
        }

        return if (staticDeclarationsInClasses.isEmpty()) null else listOf(declaration) + staticDeclarationsInClasses
    }
}