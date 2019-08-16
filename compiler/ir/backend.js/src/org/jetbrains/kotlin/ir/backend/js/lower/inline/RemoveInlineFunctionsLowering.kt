/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower.inline

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.deepCopyWithVariables
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.mapping
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.MappingKey
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrErrorExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrExpressionBodyImpl
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

class RemoveInlineFunctionsLowering(val context: JsIrBackendContext) : DeclarationTransformer {

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {

        if (declaration is IrFunction && declaration.isInline &&
            declaration.typeParameters.any { it.isReified }) return emptyList()

        return null
    }
}

private var IrFunction.originalBody by mapping(object : MappingKey<IrFunction, IrBody> {})

private var IrValueParameter.originalBody by mapping(object : MappingKey<IrValueParameter, IrExpressionBody> {})

// TODO change the `expression` and `statements` instead of replacing the `body`? Should it be versioned then?
class CopyInlineFunctionBody(val context: JsIrBackendContext) : DeclarationTransformer {
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        if (declaration is IrFunction && declaration.isInline) {
            declaration.body?.let { originalBody ->
                declaration.originalBody = originalBody
                declaration.body = IrBlockBodyImpl(originalBody.startOffset, originalBody.endOffset)
            }
        }

        if (declaration is IrValueParameter && declaration.parent.let { it is IrFunction && it.isInline }) {
            declaration.defaultValue?.let { originalDefault ->
                declaration.originalBody = originalDefault
                declaration.defaultValue = IrExpressionBodyImpl(IrErrorExpressionImpl(originalDefault.startOffset, originalDefault.endOffset, declaration.type, ""))
            }
        }

        return null
    }
}

class FillInlineFunctionBody(val context: JsIrBackendContext) : BodyLoweringPass {
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (container is IrFunction) {
            container.originalBody?.let {
                (container.body as IrBlockBody).statements += (it.deepCopyWithSymbols(container) as IrBlockBody).statements
            }
        } else if (container is IrValueParameter) {
            container.originalBody?.let {
                container.defaultValue!!.expression = it.expression.deepCopyWithSymbols(container.parent)
            }
        }
    }
}