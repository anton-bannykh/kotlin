/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.runOnFilePostfix
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrStatementOriginImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.visitors.*

object SYNTHESIZED_INIT_BLOCK : IrStatementOriginImpl("SYNTHESIZED_INIT_BLOCK")

open class InitializersLowering(context: CommonBackendContext) : InitializersLoweringBase(context), BodyLoweringPass {

    override fun lower(irFile: IrFile) {
        runOnFilePostfix(irFile, true)
    }

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (container !is IrConstructor) return

        val irClass = container.constructedClass

        val instanceInitializerStatements = extractInitializers(irClass) {
            (it is IrField && !it.isStatic) || (it is IrAnonymousInitializer && !it.isStatic)
        }

        container.body?.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitInstanceInitializerCall(expression: IrInstanceInitializerCall): IrExpression {
                return IrBlockImpl(irClass.startOffset, irClass.endOffset, context.irBuiltIns.unitType, null, instanceInitializerStatements)
                    .deepCopyWithSymbols(container).also {
                        // Handle declarations, copied from initializers
                        // TODO WTF really
                        it.acceptVoid(object : IrElementVisitorVoid {
                            override fun visitElement(element: IrElement) {
                                element.acceptChildrenVoid(this)
                            }

                            override fun visitConstructor(declaration: IrConstructor) {
                                super.visitConstructor(declaration)

                                declaration.body?.let { lower(it, declaration) }
                            }
                        })
                    }
            }
        })
    }
}

abstract class InitializersLoweringBase(open val context: CommonBackendContext) {
    protected fun extractInitializers(irClass: IrClass, filter: (IrDeclaration) -> Boolean) =
        stageController.unrestrictDeclarationListsAccess { // TODO What about fields that were added by lowerings? e.g. captured outer class or locals?
            irClass.declarations.filter(filter).mapNotNull {
                when (it) {
                    is IrField -> handleField(irClass, it)
                    is IrAnonymousInitializer -> handleAnonymousInitializer(it)
                    else -> null
                }
            }
        }

    private fun handleField(irClass: IrClass, declaration: IrField): IrStatement? =
        declaration.initializer?.run {
            val receiver = if (!declaration.isStatic) // TODO isStaticField
                IrGetValueImpl(startOffset, endOffset, irClass.thisReceiver!!.type, irClass.thisReceiver!!.symbol)
            else
                null
            IrSetFieldImpl(startOffset, endOffset, declaration.symbol, receiver, expression, context.irBuiltIns.unitType)
        }

    private fun handleAnonymousInitializer(declaration: IrAnonymousInitializer): IrStatement =
        with(declaration) {
            IrBlockImpl(startOffset, endOffset, context.irBuiltIns.unitType, SYNTHESIZED_INIT_BLOCK, body.statements)
        }
}

// Remove anonymous initializers and set field initializers to `null`
class InitializersCleanupLowering(
    val context: CommonBackendContext,
    private val shouldEraseFieldInitializer: (IrField) -> Boolean = { true }
) : DeclarationTransformer {

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        if (declaration is IrAnonymousInitializer && !declaration.isStatic) return emptyList()

        if (declaration is IrField && !declaration.isStatic && declaration.parent is IrClass) {
            if (shouldEraseFieldInitializer(declaration)) {
                declaration.initializer = null
            } else {
                declaration.initializer?.let { declaration.initializer = it.deepCopyWithSymbols() }
            }
        }

        return null
    }
}