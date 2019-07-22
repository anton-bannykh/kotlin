/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.NullableBodyLoweringPass
import org.jetbrains.kotlin.backend.common.atMostOne
import org.jetbrains.kotlin.backend.common.descriptors.WrappedFieldDescriptor
import org.jetbrains.kotlin.backend.common.ir.DeclarationBiMapKey
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.backend.js.mapping
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFieldImpl
import org.jetbrains.kotlin.ir.declarations.impl.MappingKey
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrFieldSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isThrowable
import org.jetbrains.kotlin.ir.util.isThrowableTypeOrSubtype
import org.jetbrains.kotlin.ir.util.transformFlat
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.name.Name


data class DirectThrowableSuccessors(val klass: IrClass, val message: IrField, val cause: IrField)

private object MessageAccessorToMessageFieldKey : DeclarationBiMapKey<IrField, IrSimpleFunction>

private object CauseAccessorToCauseFieldKey : DeclarationBiMapKey<IrField, IrSimpleFunction>

private var IrClass.pendingSuperUsages by mapping(object : MappingKey<IrClass, DirectThrowableSuccessors> {})

class ThrowableSuccessorsLowering(val context: JsIrBackendContext) : DeclarationTransformer {
    private val nothingType get() = context.irBuiltIns.nothingType
    private val stringType get() = context.irBuiltIns.stringType
    private val throwableType get() = context.irBuiltIns.throwableType

    private val throwableClass = context.throwableClass

    private val messagePropertyName = Name.identifier("message")
    private val causePropertyName = Name.identifier("cause")

    private val messageGetter by lazy {
        throwableClass.owner.declarations.filterIsInstance<IrFunction>().atMostOne { it.name == Name.special("<get-message>") }?.symbol
            ?: throwableClass.owner.declarations.filterIsInstance<IrProperty>().atMostOne { it.name == messagePropertyName }?.getter?.symbol!!
    }
    private val causeGetter by lazy {
        throwableClass.owner.declarations.filterIsInstance<IrFunction>().atMostOne { it.name == Name.special("<get-cause>") }?.symbol
            ?: throwableClass.owner.declarations.filterIsInstance<IrProperty>().atMostOne { it.name == causePropertyName }?.getter?.symbol!!
    }

    private val messageAccessorToFieldMap = context.declarationFactory.getMapping(MessageAccessorToMessageFieldKey)
    private val causeAccessorToFieldMap = context.declarationFactory.getMapping(CauseAccessorToCauseFieldKey)

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        declaration.accept(ThrowableAccessorCreationVisitor(), null)

        return null
    }

    inner class ThrowableAccessorCreationVisitor : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

        override fun visitBody(body: IrBody) {
            // Stop
        }

        override fun visitClass(declaration: IrClass) {

            if (isDirectChildOfThrowable(declaration)) {
                val messageField = createBackingField(declaration, messagePropertyName, stringType)
                val causeField = createBackingField(declaration, causePropertyName, throwableType)

                val existedMessageAccessor = ownPropertyAccessor(declaration, messageGetter)
                val newMessageAccessor = if (existedMessageAccessor.origin == IrDeclarationOrigin.FAKE_OVERRIDE) {
                    createPropertyAccessor(existedMessageAccessor, messageField).also {
                        messageAccessorToFieldMap.link(messageField, it)
                    }
                } else existedMessageAccessor

                val existedCauseAccessor = ownPropertyAccessor(declaration, causeGetter)
                val newCauseAccessor = if (existedCauseAccessor.origin == IrDeclarationOrigin.FAKE_OVERRIDE) {
                    createPropertyAccessor(existedCauseAccessor, causeField).also {
                        causeAccessorToFieldMap.link(causeField, it)
                    }
                } else existedCauseAccessor


                declaration.declarations.transformFlat {
                    when (it) {
                        existedMessageAccessor -> listOf(newMessageAccessor)
                        existedCauseAccessor -> listOf(newCauseAccessor)
                        else -> null
                    }
                }

                declaration.pendingSuperUsages = DirectThrowableSuccessors(declaration, messageField, causeField)
            }
        }

        private fun createBackingField(declaration: IrClass, name: Name, type: IrType): IrField {
            val fieldDescriptor = WrappedFieldDescriptor()
            val fieldSymbol = IrFieldSymbolImpl(fieldDescriptor)
            val fieldDeclaration = IrFieldImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                JsIrBuilder.SYNTHESIZED_DECLARATION,
                fieldSymbol,
                name,
                type,
                Visibilities.PRIVATE,
                true,
                false,
                false
            ).apply {
                parent = declaration
                fieldDescriptor.bind(this)
            }

            declaration.declarations += fieldDeclaration
            return fieldDeclaration
        }

        private fun createPropertyAccessor(fakeAccessor: IrSimpleFunction, field: IrField): IrSimpleFunction {
            val name = fakeAccessor.name
            val function = JsIrBuilder.buildFunction(name, fakeAccessor.returnType, fakeAccessor.parent).apply {
                overriddenSymbols += fakeAccessor.overriddenSymbols
                correspondingProperty = fakeAccessor.correspondingProperty
                dispatchReceiverParameter = fakeAccessor.dispatchReceiverParameter?.copyTo(this)
            }

//            val thisReceiver = JsIrBuilder.buildGetValue(function.dispatchReceiverParameter!!.symbol)
//            val returnValue = JsIrBuilder.buildGetField(field.symbol, thisReceiver, type = field.type)
//            val returnStatement = JsIrBuilder.buildReturn(function.symbol, returnValue, nothingType)
//            function.body = JsIrBuilder.buildBlockBody(listOf(returnStatement))

            return function
        }
    }

    private fun isDirectChildOfThrowable(irClass: IrClass) = irClass.superTypes.any { it.isThrowable() }
    private fun ownPropertyAccessor(irClass: IrClass, irBase: IrFunctionSymbol) =
        irClass.declarations.filterIsInstance<IrProperty>().mapNotNull { it.getter }
            .singleOrNull { it.overriddenSymbols.any { s -> s == irBase } }
            ?: irClass.declarations.filterIsInstance<IrSimpleFunction>().single { it.overriddenSymbols.any { s -> s == irBase } }
}

class ThrowableSuccessorsBodyLowering(val context: JsIrBackendContext) : NullableBodyLoweringPass {

    private val unitType get() = context.irBuiltIns.unitType
    private val nothingNType get() = context.irBuiltIns.nothingNType
    private val nothingType get() = context.irBuiltIns.nothingType
    private val stringType get() = context.irBuiltIns.stringType
    private val booleanType get() = context.irBuiltIns.booleanType
    private val throwableType get() = context.irBuiltIns.throwableType

    private val propertyGetter get() = context.intrinsics.jsGetJSField.symbol
    private val propertySetter get() = context.intrinsics.jsSetJSField.symbol
    private val eqeqeqSymbol get() = context.irBuiltIns.eqeqSymbol

    private val messageName get() = JsIrBuilder.buildString(stringType, "message")
    private val causeName get() = JsIrBuilder.buildString(stringType, "cause")
    private val nameName get() = JsIrBuilder.buildString(stringType, "name")

    private val throwableClass = context.throwableClass
    private val throwableConstructors = context.throwableConstructors

    private val defaultCtor = context.defaultThrowableCtor
    private val toString =
        throwableClass.owner.declarations.filterIsInstance<IrSimpleFunction>().single { it.name == Name.identifier("toString") }.symbol

    private val messagePropertyName = Name.identifier("message")
    private val causePropertyName = Name.identifier("cause")

    private val messageGetter by lazy {
        throwableClass.owner.declarations.filterIsInstance<IrFunction>().atMostOne { it.name == Name.special("<get-message>") }?.symbol
            ?: throwableClass.owner.declarations.filterIsInstance<IrProperty>().atMostOne { it.name == messagePropertyName }?.getter?.symbol!!
    }
    private val causeGetter by lazy {
        throwableClass.owner.declarations.filterIsInstance<IrFunction>().atMostOne { it.name == Name.special("<get-cause>") }?.symbol
            ?: throwableClass.owner.declarations.filterIsInstance<IrProperty>().atMostOne { it.name == causePropertyName }?.getter?.symbol!!
    }

    private val captureStackFunction = context.captureStackSymbol
    private val newThrowableFunction = context.newThrowableSymbol

    private val messageAccessorToFieldMap = context.declarationFactory.getMapping(MessageAccessorToMessageFieldKey)
    private val causeAccessorToFieldMap = context.declarationFactory.getMapping(CauseAccessorToCauseFieldKey)

    override fun lower(irBody: IrBody?, container: IrDeclaration) {
        var body = irBody

        (container as? IrSimpleFunction)?.let { function ->
            messageAccessorToFieldMap.oldByNew(container)?.let { field ->
                val thisReceiver = JsIrBuilder.buildGetValue(function.dispatchReceiverParameter!!.symbol)
                val returnValue = JsIrBuilder.buildGetField(field.symbol, thisReceiver, type = field.type)
                val returnStatement = JsIrBuilder.buildReturn(function.symbol, returnValue, nothingType)
                function.body = JsIrBuilder.buildBlockBody(listOf(returnStatement))
                body = function.body
            }

            causeAccessorToFieldMap.oldByNew(container)?.let { field ->
                val thisReceiver = JsIrBuilder.buildGetValue(function.dispatchReceiverParameter!!.symbol)
                val returnValue = JsIrBuilder.buildGetField(field.symbol, thisReceiver, type = field.type)
                val returnStatement = JsIrBuilder.buildReturn(function.symbol, returnValue, nothingType)
                function.body = JsIrBuilder.buildBlockBody(listOf(returnStatement))
                body = function.body
            }
        }

        if (body != null) {
            var parent = container.parent
            while (parent is IrDeclaration) {
                if (parent is IrClass) {
                    parent.pendingSuperUsages?.let {
                        body?.transformChildren(ThrowableDirectSuccessorTransformer(it), it.klass)
                    }
                }
                parent = parent.parent
            }
        }

        if (container is IrConstructor && container.isPrimary && body != null) {
            container.transform(ThrowableNameSetterTransformer(), container.parent)
        }
        body?.transform(ThrowablePropertiesUsageTransformer(), null)
        body?.transform(ThrowableInstanceCreationLowering(), null)
    }

    inner class ThrowableNameSetterTransformer : IrElementTransformer<IrDeclarationParent> {
        override fun visitClass(declaration: IrClass, data: IrDeclarationParent) = super.visitClass(declaration, declaration)

        override fun visitConstructor(declaration: IrConstructor, data: IrDeclarationParent): IrStatement {
            declaration.transformChildren(this, data)

            if (!declaration.isPrimary) return declaration

            val klass = data as IrClass
            if (klass.defaultType.isThrowableTypeOrSubtype()) {

                (declaration.body as? IrBlockBody)?.let {
                    it.statements += JsIrBuilder.buildCall(propertySetter, unitType).apply {
                        putValueArgument(0, JsIrBuilder.buildGetValue(klass.thisReceiver!!.symbol))
                        putValueArgument(1, nameName)
                        putValueArgument(2, JsIrBuilder.buildString(stringType, klass.name.asString()))
                    }
                }
            }

            return declaration
        }

    }

    inner class ThrowableInstanceCreationLowering : IrElementTransformerVoid() {
        override fun visitCall(expression: IrCall): IrExpression {
            if (expression.symbol !in throwableConstructors) return super.visitCall(expression)

            expression.transformChildrenVoid(this)

            val (messageArg, causeArg) = extractConstructorParameters(expression)

            return expression.run {
                IrCallImpl(startOffset, endOffset, type, newThrowableFunction, newThrowableFunction.descriptor).also {
                    it.putValueArgument(0, messageArg)
                    it.putValueArgument(1, causeArg)
                }
            }
        }

        private fun extractConstructorParameters(expression: IrFunctionAccessExpression): Pair<IrExpression, IrExpression> {
            val nullValue = { IrConstImpl.constNull(expression.startOffset, expression.endOffset, nothingNType) }
            return when {
                expression.valueArgumentsCount == 0 -> Pair(nullValue(), nullValue())
                expression.valueArgumentsCount == 2 -> expression.run { Pair(getValueArgument(0)!!, getValueArgument(1)!!) }
                else -> {
                    val arg = expression.getValueArgument(0)!!
                    when {
                        arg.type.makeNotNull().isThrowable() -> Pair(nullValue(), arg)
                        else -> Pair(arg, nullValue())
                    }
                }
            }
        }
    }

    private inner class ThrowableDirectSuccessorTransformer(private val successor: DirectThrowableSuccessors) :
        IrElementTransformer<IrDeclarationParent> {

        override fun visitClass(declaration: IrClass, data: IrDeclarationParent) = declaration

        override fun visitFunction(declaration: IrFunction, data: IrDeclarationParent) = super.visitFunction(declaration, declaration)

        override fun visitCall(expression: IrCall, data: IrDeclarationParent): IrElement {
            if (expression.superQualifierSymbol != throwableClass) return super.visitCall(expression, data)

            expression.transformChildren(this, data)

            val superField = when {
                expression.symbol == messageGetter -> successor.message
                expression.symbol == causeGetter -> successor.cause
                else -> error("Unknown accessor")
            }

            return expression.run { IrGetFieldImpl(startOffset, endOffset, superField.symbol, type, dispatchReceiver, origin) }
        }

        override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall, data: IrDeclarationParent): IrElement {
            if (expression.symbol !in throwableConstructors) return super.visitDelegatingConstructorCall(expression, data)

            expression.transformChildren(this, data)

            val (messageArg, causeArg, paramStatements) = extractConstructorParameters(expression, data)

            val newDelegation = expression.run {
                IrDelegatingConstructorCallImpl(startOffset, endOffset, type, defaultCtor, defaultCtor.descriptor)
            }

            val klass = successor.klass
            val receiver = { IrGetValueImpl(expression.startOffset, expression.endOffset, klass.thisReceiver!!.symbol) }

            val fillStatements = fillThrowableInstance(expression, receiver, messageArg, causeArg)

            return expression.run {
                IrCompositeImpl(startOffset, endOffset, type, origin, paramStatements + newDelegation + fillStatements)
            }
        }

        private fun fillThrowableInstance(
            expression: IrFunctionAccessExpression,
            receiver: () -> IrExpression,
            messageArg: IrExpression,
            causeArg: IrExpression
        ): List<IrStatement> {

            val setMessage = expression.run {
                IrSetFieldImpl(startOffset, endOffset, successor.message.symbol, receiver(), messageArg, unitType)
            }

            val setCause = expression.run {
                IrSetFieldImpl(startOffset, endOffset, successor.cause.symbol, receiver(), causeArg, unitType)
            }

            val setStackTrace = IrCallImpl(expression.startOffset, expression.endOffset, unitType, captureStackFunction).apply {
                putValueArgument(0, receiver())
            }

            return listOf(setMessage, setCause, setStackTrace)
        }

        private fun extractConstructorParameters(
            expression: IrFunctionAccessExpression,
            parent: IrDeclarationParent
        ): Triple<IrExpression, IrExpression, List<IrStatement>> {
            val nullValue = { IrConstImpl.constNull(expression.startOffset, expression.endOffset, nothingNType) }
            // Wrap parameters into variables to keep original evaluation order
            return when {
                expression.valueArgumentsCount == 0 -> Triple(nullValue(), nullValue(), emptyList())
                expression.valueArgumentsCount == 2 -> {
                    val msg = expression.getValueArgument(0)!!
                    val cus = expression.getValueArgument(1)!!
                    val irValM = JsIrBuilder.buildVar(msg.type, parent, initializer = msg)
                    val irValC = JsIrBuilder.buildVar(cus.type, parent, initializer = cus)

                    val check = JsIrBuilder.buildCall(eqeqeqSymbol, booleanType).apply {
                        putValueArgument(0, JsIrBuilder.buildGetValue(irValM.symbol))
                        putValueArgument(1, nullValue())
                    }

                    val msgElvis = JsIrBuilder.buildIfElse(
                        stringType.makeNullable(), check,
                        safeCallToString(irValC), JsIrBuilder.buildGetValue(irValM.symbol)
                    )

                    Triple(msgElvis, JsIrBuilder.buildGetValue(irValC.symbol), listOf(irValM, irValC))
                }
                else -> {
                    val arg = expression.getValueArgument(0)!!
                    val irVal = JsIrBuilder.buildVar(arg.type, parent, initializer = arg)
                    val argValue = JsIrBuilder.buildGetValue(irVal.symbol)
                    when {
                        arg.type.makeNotNull().isThrowable() -> Triple(safeCallToString(irVal), argValue, listOf(irVal))
                        else -> Triple(argValue, nullValue(), listOf(irVal))
                    }
                }
            }
        }
    }

    private fun safeCallToString(receiver: IrValueDeclaration): IrExpression {
        val value = JsIrBuilder.buildGetValue(receiver.symbol)
        val check = JsIrBuilder.buildCall(eqeqeqSymbol, booleanType).apply {
            putValueArgument(0, value)
            putValueArgument(1, JsIrBuilder.buildNull(value.type))
        }
        val value2 = JsIrBuilder.buildGetValue(receiver.symbol)
        val call = JsIrBuilder.buildCall(toString, stringType).apply { dispatchReceiver = value2 }
        return JsIrBuilder.buildIfElse(stringType.makeNullable(), check, JsIrBuilder.buildNull(stringType), call)
    }

    inner class ThrowablePropertiesUsageTransformer : IrElementTransformerVoid() {
        override fun visitCall(expression: IrCall): IrExpression {
            val transformRequired = expression.superQualifierSymbol == null || expression.superQualifierSymbol?.owner == throwableClass

            if (!transformRequired) return super.visitCall(expression)

            expression.transformChildrenVoid(this)

            val owner = expression.symbol
            return when (owner) {
                messageGetter -> {
                    IrCallImpl(expression.startOffset, expression.endOffset, expression.type, propertyGetter).apply {
                        putValueArgument(0, expression.dispatchReceiver!!)
                        putValueArgument(1, messageName)
                    }
                }
                causeGetter -> {
                    IrCallImpl(expression.startOffset, expression.endOffset, expression.type, propertyGetter).apply {
                        putValueArgument(0, expression.dispatchReceiver!!)
                        putValueArgument(1, causeName)
                    }
                }
                else -> expression
            }
        }
    }
}