package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ir.addChild
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrScript
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl

//This lower takes part of old LocalDeclarationLowering job to pop up local classes from functions
class LocalClassPopupLowering(val context: BackendContext) : BodyLoweringPass {

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        val extractedLocalClasses = arrayListOf<Pair<IrClass, IrDeclarationContainer>>()

        irBody.transform(object : IrElementTransformerVoidWithContext() {

            override fun visitClassNew(declaration: IrClass): IrStatement {
                val newDeclaration = super.visitClassNew(declaration)
                if (newDeclaration !is IrClass || !newDeclaration.isLocalNotInner()) {
                    return newDeclaration
                }

                //find first class local or not;
                // to reproduce original LocalDeclarationLowering behaviour add: '|| !currentParent.isLocal' condition
                val newContainer = run {
                    var currentParent = container.parent
                    while (currentParent is IrDeclaration && currentParent !is IrClass && currentParent !is IrScript) {
                        currentParent = currentParent.parent
                    }

                    currentParent as IrDeclarationContainer // IrClass or IrScript or IrPackageFragment
                }

                extractedLocalClasses.add(newDeclaration to newContainer)
                return IrCompositeImpl(declaration.startOffset, declaration.endOffset, context.irBuiltIns.unitType)
            }
        }, null)

        for ((local, newContainer) in extractedLocalClasses) {
            newContainer.addChild(local)
        }
    }
}