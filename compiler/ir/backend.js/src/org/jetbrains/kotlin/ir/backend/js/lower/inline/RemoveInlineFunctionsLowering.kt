/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower.inline

import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid


class RemoveInlineFunctionsLowering(val context: JsIrBackendContext) : DeclarationTransformer {

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {

        if (declaration is IrFunction && declaration.isInline) return emptyList()

        declaration.transform(object : IrElementTransformerVoid() {
            override fun visitClass(declaration: IrClass): IrStatement {
                declaration.transformChildrenVoid()

                declaration.declarations.transformFlat {
                    if (it is IrFunction && it.isInline) emptyList() else null
                }

                return declaration
            }
        }, null)

        return null
    }
}
