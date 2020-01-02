/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid


var IrClass.syntheticPrimaryConstructor by mapping<IrClass, IrConstructor>()

// Create primary constructor if it doesn't exist
class PrimaryConstructorLowering(context: CommonBackendContext) : DeclarationTransformer {

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        if (declaration is IrClass) {
            val constructors = stageController.withInitialStateOf(declaration) { declaration.declarations.filterIsInstance<IrConstructor>() }

            if (constructors.any { it.isPrimary }) return null

            declaration.syntheticPrimaryConstructor = createPrimaryConstructor(declaration)
        }

        return null
    }

    private object SYNTHETIC_PRIMARY_CONSTRUCTOR : IrDeclarationOriginImpl("SYNTHETIC_PRIMARY_CONSTRUCTOR")

    private val unitType = context.irBuiltIns.unitType

    private fun createPrimaryConstructor(irClass: IrClass): IrConstructor {
        // TODO better API for declaration creation. This case doesn't fit the usual transformFlat-like API.
        val declaration = stageController.unrestrictDeclarationListsAccess {
            irClass.addConstructor {
                origin = SYNTHETIC_PRIMARY_CONSTRUCTOR
                isPrimary = true
                visibility = Visibilities.PRIVATE
            }
        }

        declaration.body = irClass.run {
            IrBlockBodyImpl(startOffset, endOffset, listOf(IrInstanceInitializerCallImpl(startOffset, endOffset, symbol, unitType)))
        }

        return declaration
    }
}

class DelegateToSyntheticPrimaryConstructor: BodyLoweringPass {
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (container is IrConstructor && !container.isPrimary) {
            container.parentAsClass.syntheticPrimaryConstructor?.let { primary ->
                val initializeTransformer = object : IrElementTransformerVoid() {
                    override fun visitDeclaration(declaration: IrDeclaration) = declaration // optimize visiting

                    override fun visitInstanceInitializerCall(expression: IrInstanceInitializerCall) = expression.run {
                        IrDelegatingConstructorCallImpl(startOffset, endOffset, type, primary.symbol)
                    }
                }

                irBody.transformChildrenVoid(initializeTransformer)
            }
        }
    }
}