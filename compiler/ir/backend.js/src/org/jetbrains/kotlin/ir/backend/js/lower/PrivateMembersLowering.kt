/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.backend.common.descriptors.WrappedValueParameterDescriptor
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.mapping
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.declarations.impl.MappingKey
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.Name

val STATIC_THIS_PARAMETER = object : IrDeclarationOriginImpl("STATIC_THIS_PARAMETER") {}

private var IrFunction.correspondingStatic by mapping(object : MappingKey<IrFunction, IrSimpleFunction> {})
private var IrSimpleFunction.correspondingMember by mapping(object : MappingKey<IrSimpleFunction, IrFunction> {})

class PrivateMembersLowering(val context: JsIrBackendContext) : ClassLoweringPass {

    override fun lower(irClass: IrClass) {

        irClass.declarations.transformFlat {
            when (it) {
                is IrSimpleFunction -> transformMemberToStaticFunction(it)?.let { staticFunction ->
                    listOf(staticFunction)
                }
                is IrProperty -> listOf(it.apply {
                    getter = getter?.let { g -> transformAccessor(g) }
                    setter = setter?.let { s -> transformAccessor(s) }
                })
                else -> null
            }
        }
    }

    private fun transformAccessor(accessor: IrSimpleFunction) =
        transformMemberToStaticFunction(accessor) ?: accessor

    private fun transformMemberToStaticFunction(function: IrSimpleFunction): IrSimpleFunction? {

        if (function.visibility != Visibilities.PRIVATE || function.dispatchReceiverParameter == null) return null

        val descriptor = WrappedSimpleFunctionDescriptor()
        val symbol = IrSimpleFunctionSymbolImpl(descriptor)
        val staticFunction = function.run {
            IrFunctionImpl(
                startOffset, endOffset, origin,
                symbol, name, visibility, modality,
                returnType,
                isInline, isExternal, isTailrec, isSuspend
            ).also {
                descriptor.bind(it)
                it.parent = parent
                it.correspondingProperty = correspondingProperty
            }
        }


        staticFunction.typeParameters += function.typeParameters.map { it.deepCopyWithSymbols(staticFunction) }

        staticFunction.extensionReceiverParameter = function.extensionReceiverParameter?.copyTo(staticFunction)
        val thisDesc = WrappedValueParameterDescriptor()
        val thisSymbol = IrValueParameterSymbolImpl(thisDesc)
        staticFunction.valueParameters += IrValueParameterImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            STATIC_THIS_PARAMETER,
            thisSymbol,
            Name.identifier("\$this"),
            0,
            function.dispatchReceiverParameter!!.type,
            null,
            false,
            false
        ).also {
            thisDesc.bind(it)
            it.parent = staticFunction
        }

        function.correspondingStatic = staticFunction
        staticFunction.correspondingMember = function

        var parameterMapping: Map<IrValueParameter, IrValueParameter>? = null

        val parameterTransformer = object : IrElementTransformerVoid() {
            override fun visitGetValue(expression: IrGetValue) = parameterMapping!![expression.symbol.owner]?.let {
                expression.run { IrGetValueImpl(startOffset, endOffset, type, it.symbol, origin) }
            } ?: expression
        }

        fun IrBody.copyWithParameters(): IrBody {
            return deepCopyWithSymbols(staticFunction).also {
                it.transform(parameterTransformer, null)
            }
        }

        function.body?.let {
            staticFunction.body = when (it) {
                is IrBlockBody -> IrBlockBodyImpl(it.startOffset, it.endOffset) {
                    statements += (it.copyWithParameters() as IrBlockBody).statements
                }
                is IrExpressionBody -> IrExpressionBodyImpl(it.startOffset, it.endOffset) {
                    expression = (it.copyWithParameters() as IrExpressionBody).expression
                }
                is IrSyntheticBody -> it
                else -> error("Unexpected body kind: ${it.javaClass}")
            }
        }

        staticFunction.valueParameters += function.valueParameters.map {
            // TODO better way to avoid copying default value
            it.copyTo(staticFunction, index = it.index + 1, defaultValue = null).apply {
                it.defaultValue?.let { originalDefault ->
                    defaultValue = IrExpressionBodyImpl(it.startOffset, it.endOffset) {
                        expression = (originalDefault.copyWithParameters() as IrExpressionBody).expression
                    }
                }
            }
        }

        val oldParameters =
            listOfNotNull(function.extensionReceiverParameter, function.dispatchReceiverParameter) + function.valueParameters
        val newParameters = listOfNotNull(staticFunction.extensionReceiverParameter) + staticFunction.valueParameters
        assert(oldParameters.size == newParameters.size)

        parameterMapping = oldParameters.zip(newParameters).toMap()

        return staticFunction
    }
}

class PrivateMemberBodiesLowering(val context: JsIrBackendContext) : BodyLoweringPass {

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        irBody.transform(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                super.visitCall(expression)

                return expression.symbol.owner.correspondingStatic?.let {
                    transformPrivateToStaticCall(expression, it)
                } ?: expression
            }

            override fun visitFunctionReference(expression: IrFunctionReference) = expression.symbol.owner.correspondingStatic?.let {
                transformPrivateToStaticReference(expression) {
                    IrFunctionReferenceImpl(
                        expression.startOffset, expression.endOffset,
                        expression.type,
                        it.symbol, it.descriptor,
                        expression.typeArgumentsCount, expression.valueArgumentsCount,
                        expression.origin
                    )
                }
            } ?: expression

            override fun visitPropertyReference(expression: IrPropertyReference): IrExpression {
                return if (expression.getter?.owner?.correspondingStatic != null || expression.setter?.owner?.correspondingStatic != null) {
                    transformPrivateToStaticReference(expression) {
                        IrPropertyReferenceImpl(
                            expression.startOffset, expression.endOffset,
                            expression.type,
                            expression.symbol, // TODO remap property symbol based on remapped getter/setter?
                            expression.typeArgumentsCount,
                            expression.field,
                            expression.getter?.owner?.correspondingStatic?.symbol ?: expression.getter,
                            expression.setter?.owner?.correspondingStatic?.symbol ?: expression.setter,
                            expression.origin
                        )
                    }
                } else expression
            }


            private fun transformPrivateToStaticCall(expression: IrCall, staticTarget: IrSimpleFunction): IrCall {
                val newExpression = IrCallImpl(
                    expression.startOffset, expression.endOffset,
                    expression.type,
                    staticTarget.symbol, staticTarget.descriptor,
                    expression.typeArgumentsCount,
                    expression.origin,
                    expression.superQualifierSymbol
                )

                newExpression.extensionReceiver = expression.extensionReceiver
                expression.dispatchReceiver?.let { newExpression.putValueArgument(0, it) }

                for (i in 0 until expression.valueArgumentsCount) {
                    newExpression.putValueArgument(i + 1, expression.getValueArgument(i))
                }
                newExpression.copyTypeArgumentsFrom(expression)

                return newExpression
            }

            private fun transformPrivateToStaticReference(
                expression: IrCallableReference,
                builder: () -> IrCallableReference
            ): IrCallableReference {

                val newExpression = builder()

                newExpression.extensionReceiver = expression.extensionReceiver

                newExpression.dispatchReceiver = expression.dispatchReceiver
                for (i in 0 until expression.valueArgumentsCount) {
                    newExpression.putValueArgument(i, expression.getValueArgument(i))
                }
                newExpression.copyTypeArgumentsFrom(expression)

                return newExpression
            }
        }, null)
    }
}