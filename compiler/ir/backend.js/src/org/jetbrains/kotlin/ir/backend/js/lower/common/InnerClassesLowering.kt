/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower.common

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.lower.VariableRemapper
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.backend.js.ContextData
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class InnerClassesDeclarationLowering(val context: JsIrBackendContext) : DeclarationTransformer {
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {

        if (declaration is IrClass && declaration.isInner) {
            val data = context.getContextData(declaration)
            declaration.declarations += data.declarationFactory.getOuterThisField(declaration)
        } else if (declaration is IrConstructor) {
            val oldConstructor = declaration
            val irClass = oldConstructor.parent as? IrClass ?: return null
            if (!irClass.isInner) return null

            val data = context.getContextData(declaration)
            val newConstructor = data.declarationFactory.getInnerClassConstructorWithOuterThisParameter(oldConstructor)
            createNewConstructorBody(irClass, newConstructor, oldConstructor, data)
            oldConstructor.valueParameters.forEach { param ->
                param.defaultValue?.expression?.let { oldDefault ->
                    newConstructor.valueParameters[param.index + 1].defaultValue =
                        IrExpressionBodyImpl(oldDefault.startOffset, oldDefault.endOffset) {
                            expression = oldDefault.deepCopyWithSymbols(newConstructor)
                        }
                }
            }
            return listOf(newConstructor)
        }

        return null
    }

    fun createNewConstructorBody(irClass: IrClass, newConstructor: IrConstructor, oldConstructor: IrConstructor, data: ContextData) {
        val parentThisField = data.declarationFactory.getOuterThisField(irClass)

        oldConstructor.body?.let { oldConstructorBody ->
            newConstructor.body = IrBlockBodyImpl(oldConstructorBody.startOffset, oldConstructorBody.endOffset) {
                val outerThisParameter = newConstructor.valueParameters[0]

                oldConstructorBody as? IrBlockBody ?: throw AssertionError("Unexpected constructor body: ${oldConstructorBody}")

                statements.addAll(oldConstructorBody.statements)

                context.createIrBuilder(newConstructor.symbol, newConstructor.startOffset, newConstructor.endOffset).apply {
                    statements.add(0, irSetField(irGet(irClass.thisReceiver!!), parentThisField, irGet(outerThisParameter)))
                }
                if (statements.find { it is IrInstanceInitializerCall } == null) {
                    val delegatingConstructorCall =
                        statements.find { it is IrDelegatingConstructorCall } as IrDelegatingConstructorCall?
                            ?: throw AssertionError("Delegating constructor call expected: ${newConstructor.dump()}")
                    delegatingConstructorCall.apply { dispatchReceiver = IrGetValueImpl(startOffset, endOffset, outerThisParameter.symbol) }
                }
                patchDeclarationParents(newConstructor)

                val oldConstructorParameterToNew = data.primaryConstructorParameterMap(newConstructor)

                transformChildrenVoid(VariableRemapper(oldConstructorParameterToNew))
            }
        }
    }
}

private fun ContextData.primaryConstructorParameterMap(loweredConstructor: IrConstructor): Map<IrValueParameter, IrValueParameter> {
    val oldConstructorParameterToNew = HashMap<IrValueParameter, IrValueParameter>()

    val originalConstructor = declarationFactory.getInnerClassConstructorWithInnerThisParameter(loweredConstructor)

    originalConstructor.valueParameters.forEach { old ->
        oldConstructorParameterToNew[old] = loweredConstructor.valueParameters[old.index + 1]
    }

    return oldConstructorParameterToNew
}

// TODO replace with declaration transformer, which creates lazy bodies
class InnerClassesMemberBodyLowering(val context: JsIrBackendContext) : BodyLoweringPass {

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        val irClass = container.parent as? IrClass ?: return

        if (!irClass.isInner) return

        val data = context.getContextData(irClass)

        if (container is IrField || container is IrAnonymousInitializer || container is IrValueParameter) {
            // TODO Property initializer references primary constructor value parameters. Doesn't feel right to be honest
            val primaryConstructor = irClass.declarations.find { it is IrConstructor && it.isPrimary } as? IrConstructor
            if (primaryConstructor != null) {
                val oldConstructorParameterToNew = data.primaryConstructorParameterMap(primaryConstructor)
                irBody.transformChildrenVoid(VariableRemapper(oldConstructorParameterToNew))
            }
        }

        irBody.fixThisReference(container, irClass, data)
    }

    private val IrValueSymbol.classForImplicitThis: IrClass?
        // TODO: is this the correct way to get the class?
        // -1 means value is either IMPLICIT or EXTENSION receiver
        get() = if (this is IrValueParameterSymbol && owner.index == -1 && owner.name.isSpecial /* <this> */)
            owner.type.classOrNull?.owner
        else
            null

    private fun IrBody.fixThisReference(container: IrDeclaration, irClass: IrClass, data: ContextData) {
        transform(object : IrElementTransformerVoid() {
            private var enclosingConstructor: IrConstructor? = container as? IrConstructor

            // TODO: maybe add another transformer that skips specified elements
            override fun visitClass(declaration: IrClass): IrStatement =
                declaration

            override fun visitConstructor(declaration: IrConstructor): IrStatement =
                try {
                    enclosingConstructor = declaration
                    super.visitConstructor(declaration)
                } finally {
                    enclosingConstructor = null
                }

            override fun visitGetValue(expression: IrGetValue): IrExpression {
                expression.transformChildrenVoid(this)

                val implicitThisClass = expression.symbol.classForImplicitThis
                if (implicitThisClass == null || implicitThisClass == irClass) return expression

                val startOffset = expression.startOffset
                val endOffset = expression.endOffset
                val origin = expression.origin

                var irThis: IrExpression = IrGetValueImpl(startOffset, endOffset, irClass.thisReceiver!!.symbol, origin)
                var innerClass = irClass
                while (innerClass != implicitThisClass) {
                    if (!innerClass.isInner) {
                        // Captured 'this' unrelated to inner classes nesting hierarchy, leave it as is -
                        // should be transformed by closures conversion.
                        return expression
                    }

                    irThis = if (enclosingConstructor != null && irClass == innerClass) {
                        // Might be before a super() call (e.g. an argument to one), in which case the JVM bytecode verifier will reject
                        // an attempt to access the field. Good thing we have a local variable as well.
                        IrGetValueImpl(startOffset, endOffset, enclosingConstructor!!.valueParameters[0].symbol, origin)
                    } else {
                        val outerThisField = data.declarationFactory.getOuterThisField(innerClass)
                        IrGetFieldImpl(startOffset, endOffset, outerThisField.symbol, outerThisField.type, irThis, origin)
                    }
                    innerClass = innerClass.parentAsClass
                }
                return irThis
            }
        }, null)
    }
}

class InnerClassConstructorCallsLowering(val context: JsIrBackendContext) : BodyLoweringPass {
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        val data = context.getContextData(container)
        irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)

                val dispatchReceiver = expression.dispatchReceiver ?: return expression
                val callee = expression.symbol as? IrConstructorSymbol ?: return expression
                val parent = callee.owner.parent as? IrClass ?: return expression
                if (!parent.isInner) return expression

                val newCallee = data.declarationFactory.getInnerClassConstructorWithOuterThisParameter(callee.owner)
                val newCall = IrCallImpl(
                    expression.startOffset, expression.endOffset, expression.type, newCallee.symbol, newCallee.descriptor,
                    0, // TODO type arguments map
                    expression.origin
                )

                newCall.putValueArgument(0, dispatchReceiver)
                for (i in 1..newCallee.valueParameters.lastIndex) {
                    newCall.putValueArgument(i, expression.getValueArgument(i - 1))
                }

                return newCall
            }

            override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
                expression.transformChildrenVoid(this)

                val dispatchReceiver = expression.dispatchReceiver ?: return expression
                val classConstructor = expression.symbol.owner
                if (!(classConstructor.parent as IrClass).isInner) return expression

                val newCallee = data.declarationFactory.getInnerClassConstructorWithOuterThisParameter(classConstructor)
                val newCall = IrDelegatingConstructorCallImpl(
                    expression.startOffset, expression.endOffset, context.irBuiltIns.unitType, newCallee.symbol, newCallee.descriptor,
                    classConstructor.typeParameters.size
                ).apply { copyTypeArgumentsFrom(expression) }

                newCall.putValueArgument(0, dispatchReceiver)
                for (i in 1..newCallee.valueParameters.lastIndex) {
                    newCall.putValueArgument(i, expression.getValueArgument(i - 1))
                }

                return newCall
            }

            override fun visitFunctionReference(expression: IrFunctionReference): IrExpression {
                expression.transformChildrenVoid(this)

                val callee = expression.symbol as? IrConstructorSymbol ?: return expression
                val parent = callee.owner.parent as? IrClass ?: return expression
                if (!parent.isInner) return expression

                val newCallee = data.declarationFactory.getInnerClassConstructorWithOuterThisParameter(callee.owner)

                val newReference = expression.run {
                    IrFunctionReferenceImpl(
                        startOffset,
                        endOffset,
                        type,
                        newCallee.symbol,
                        newCallee.descriptor,
                        typeArgumentsCount,
                        origin
                    )
                }

                newReference.let {
                    it.dispatchReceiver = expression.dispatchReceiver
                    it.extensionReceiver = expression.extensionReceiver
                    for (t in 0 until expression.typeArgumentsCount) {
                        it.putTypeArgument(t, expression.getTypeArgument(t))
                    }

                    for (v in 0 until expression.valueArgumentsCount) {
                        it.putValueArgument(v, expression.getValueArgument(v))
                    }
                }

                return newReference
            }
            // TODO callable references?
        })
    }
}

