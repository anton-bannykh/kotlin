/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.descriptors.WrappedVariableDescriptor
import org.jetbrains.kotlin.backend.common.ir.Symbols
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

private var IrField.nullableField by mapping<IrField, IrField>()

// Makes backing field type nullable; adds guards to lateinit property getters
class LateinitPropertiesLowering(val context: CommonBackendContext) : DeclarationTransformer {

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        when (declaration) {
            is IrProperty -> declaration.backingField?.nullableField?.let { newField ->
                declaration.backingField = newField
                transformGetter(newField, declaration.getter!!)
            }

            is IrField -> {
                val property = declaration.correspondingPropertySymbol?.owner ?: return null
                if (property.isLateinit && declaration.origin != IrDeclarationOrigin.FAKE_OVERRIDE) {
                    buildOrGetNullableField(declaration)
                }
            }
        }

        return null
    }

    private fun transformGetter(backingField: IrField, getter: IrFunction) {
        val type = backingField.type
        assert(!type.isPrimitiveType()) { "'lateinit' modifier is not allowed on primitive types" }
        val startOffset = getter.startOffset
        val endOffset = getter.endOffset
        getter.body = IrBlockBodyImpl(startOffset, endOffset) {
            val irBuilder = context.createIrBuilder(getter.symbol, startOffset, endOffset)
            irBuilder.run {
                val resultVar = scope.createTemporaryVariable(
                    irGetField(getter.dispatchReceiverParameter?.let { irGet(it) }, backingField)
                )
                resultVar.parent = getter
                statements.add(resultVar)
                val throwIfNull = irIfThenElse(
                    context.irBuiltIns.nothingType,
                    irNotEquals(irGet(resultVar), irNull()),
                    irReturn(irGet(resultVar)),
                    throwUninitializedPropertyAccessException(backingField.name.asString())
                )
                statements.add(throwIfNull)
            }
        }
    }

    private val throwErrorFunction by lazy { context.ir.symbols.ThrowUninitializedPropertyAccessException.owner }

    private fun IrBuilderWithScope.throwUninitializedPropertyAccessException(name: String) =
        throwUninitializedPropertyAccessException(name, throwErrorFunction)
}

private fun IrBuilderWithScope.throwUninitializedPropertyAccessException(name: String, throwErrorFunction: IrSimpleFunction) =
    irCall(throwErrorFunction).apply {
        putValueArgument(
            0,
            IrConstImpl.string(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                context.irBuiltIns.stringType,
                name
            )
        )
    }

private fun buildOrGetNullableField(originalField: IrField): IrField {
    if (originalField.type.isMarkedNullable()) return originalField
    return originalField::nullableField.getOrPut {
        buildField {
            updateFrom(originalField)
            type = originalField.type.makeNullable()
            name = originalField.name
        }.apply {
            parent = originalField.parent
            correspondingPropertySymbol = originalField.correspondingPropertySymbol
        }
    }
}

// Rewrites accesses to the old lateinit property backing field getters
// Handles lateinit local vars
// Handles ::isInitialized intrinsic
class LateinitBodyLowering(val context: CommonBackendContext) : BodyLoweringPass {

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        val nullableVariables = mutableMapOf<IrVariable, IrVariable>()

        irBody.transformChildrenVoid(object : IrElementTransformerVoid() {

            override fun visitVariable(declaration: IrVariable): IrStatement {
                declaration.transformChildrenVoid(this)

                if (!declaration.isLateinit) return declaration

                val descriptor = WrappedVariableDescriptor()
                val type = declaration.type.makeNullable()
                val newVar = IrVariableImpl(
                    declaration.startOffset,
                    declaration.endOffset,
                    declaration.origin,
                    IrVariableSymbolImpl(descriptor),
                    declaration.name,
                    type,
                    true,
                    false,
                    true
                ).also {
                    descriptor.bind(it)
                    it.parent = declaration.parent
                    it.initializer = IrConstImpl.constNull(declaration.startOffset, declaration.endOffset, context.irBuiltIns.nothingNType)
                }

                nullableVariables[declaration] = newVar

                return newVar
            }

            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val irVar = nullableVariables[expression.symbol.owner] ?: return expression

                val parent = irVar.parent as IrSymbolOwner
                val irBuilder = context.createIrBuilder(parent.symbol, expression.startOffset, expression.endOffset)

                return irBuilder.run {
                    irIfThenElse(
                        expression.type, irEqualsNull(irGet(irVar)),
                        throwUninitializedPropertyAccessException(irVar.name.asString()),
                        irGet(irVar)
                    )
                }
            }

            override fun visitSetVariable(expression: IrSetVariable): IrExpression {
                expression.transformChildrenVoid(this)
                val newVar = nullableVariables[expression.symbol.owner] ?: return expression
                return with(expression) {
                    IrSetVariableImpl(startOffset, endOffset, type, newVar.symbol, value, origin)
                }
            }

            override fun visitGetField(expression: IrGetField): IrExpression {
                expression.transformChildrenVoid(this)
                val newField = expression.symbol.owner.nullableField ?: return expression
                return with(expression) {
                    IrGetFieldImpl(startOffset, endOffset, newField.symbol, newField.type, receiver, origin, superQualifierSymbol)
                }
            }

            override fun visitSetField(expression: IrSetField): IrExpression {
                expression.transformChildrenVoid(this)
                val newField = expression.symbol.owner.nullableField ?: return expression
                return with(expression) {
                    IrSetFieldImpl(startOffset, endOffset, newField.symbol, receiver, value, type, origin, superQualifierSymbol)
                }
            }

            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)

                if (!Symbols.isLateinitIsInitializedPropertyGetter(expression.symbol)) return expression

                val receiver = expression.extensionReceiver as IrPropertyReference

                val property = receiver.getter?.owner?.resolveFakeOverride()?.correspondingProperty!!.also { assert(it.isLateinit) }

                val nullableField =
                    buildOrGetNullableField(property.backingField ?: error("Lateinit property is supposed to have backing field"))

                return expression.run { context.createIrBuilder(symbol, startOffset, endOffset) }.run {
                    irNotEquals(irGetField(receiver.dispatchReceiver, nullableField), irNull())
                }
            }
        })
    }

    private fun IrBuilderWithScope.throwUninitializedPropertyAccessException(name: String) =
        throwUninitializedPropertyAccessException(name, throwErrorFunction)

    private val throwErrorFunction by lazy { context.ir.symbols.ThrowUninitializedPropertyAccessException.owner }
}