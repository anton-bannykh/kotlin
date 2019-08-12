/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.NullableBodyLoweringPass
import org.jetbrains.kotlin.backend.common.descriptors.WrappedClassConstructorDescriptor
import org.jetbrains.kotlin.backend.common.descriptors.WrappedFieldDescriptor
import org.jetbrains.kotlin.backend.common.ir.copyParameterDeclarationsFrom
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irBlockBody
import org.jetbrains.kotlin.backend.common.lower.irIfThen
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.getOrPut
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.backend.js.mapping
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrConstructorImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFieldImpl
import org.jetbrains.kotlin.ir.declarations.impl.MappingKey
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrConstructorSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFieldSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.*

private var IrEnumEntry.getInstanceFun by mapping(object: MappingKey<IrEnumEntry, IrSimpleFunction>{})

private var IrEnumEntry.instanceField by mapping(object: MappingKey<IrEnumEntry, IrField>{})

class EnumUsageLowering(val context: JsIrBackendContext) : BodyLoweringPass {
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitGetEnumValue(expression: IrGetEnumValue): IrExpression {
                val enumEntry = expression.symbol.owner
                val klass = enumEntry.parent as IrClass
                return if (klass.isExternal) lowerExternalEnumEntry(enumEntry, klass) else lowerEnumEntry(enumEntry, klass)
            }
        })
    }

    private fun lowerExternalEnumEntry(enumEntry: IrEnumEntry, klass: IrClass) =
        enumEntry::instanceField.getOrPut { createFieldForEntry(enumEntry, klass) }.let {
            JsIrBuilder.buildGetField(it.symbol, classAsReceiver(klass), null, klass.defaultType)
        }

    private fun classAsReceiver(irClass: IrClass): IrExpression {
        val intrinsic = context.libraryIntrinsics.jsClass
        return JsIrBuilder.buildCall(intrinsic, context.irBuiltIns.anyType, listOf(irClass.defaultType))
    }

    private fun createFieldForEntry(entry: IrEnumEntry, irClass: IrClass): IrField {
        val descriptor = WrappedFieldDescriptor()
        val symbol = IrFieldSymbolImpl(descriptor)
        return entry.run {
            IrFieldImpl(startOffset, endOffset, origin, symbol, name, irClass.defaultType, Visibilities.PUBLIC, false, true, true).also {
                descriptor.bind(it)
                it.parent = irClass
                irClass.declarations += it
            }
        }
    }

    private fun lowerEnumEntry(enumEntry: IrEnumEntry, klass: IrClass) =
        enumEntry.getInstanceFun!!.run { JsIrBuilder.buildCall(symbol) }
}


private fun createEntryAccessorName(enumName: String, enumEntry: IrEnumEntry) =
    "${enumName}_${enumEntry.name.identifier}_getInstance"

private fun IrEnumEntry.getType(irClass: IrClass) = (correspondingClass ?: irClass).defaultType

// Should be applied recursively
class EnumClassConstructorLowering(val context: JsIrBackendContext) : DeclarationTransformer {

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        return if (declaration is IrClass && declaration.isEnumClass &&
            !declaration.descriptor.isExpect && !declaration.isEffectivelyExternal()
        ) {
            EnumClassConstructorTransformer(context, declaration).transform()
        } else null
    }
}

private var IrConstructor.originalConstructor by mapping(object : MappingKey<IrConstructor, IrConstructor>{})
private var IrConstructor.newConstructor by mapping(object : MappingKey<IrConstructor, IrConstructor>{})
private var IrClass.correspondingEntry by mapping(object : MappingKey<IrClass, IrEnumEntry>{})

private var IrValueDeclaration.valueParameter by mapping(object : MappingKey<IrValueDeclaration, IrValueParameter>{})
private var IrValueParameter.valueDeclaration by mapping(object : MappingKey<IrValueParameter, IrValueDeclaration>{})



class EnumClassConstructorTransformer(val context: JsIrBackendContext, private val irClass: IrClass) {

    fun transform(): List<IrDeclaration> {
        // Add `name` and `ordinal` parameters to enum class constructors
        lowerEnumConstructorsSignature()

        return listOf(irClass)
    }

    private fun lowerEnumConstructorsSignature() {
        irClass.declarations.transform { declaration ->
            if (declaration is IrConstructor) {
                transformEnumConstructor(declaration, irClass)
            } else
                declaration
        }

        irClass.declarations.forEach {
            if (it is IrEnumEntry) {
                it.correspondingClass?.let { klass ->
                    klass.correspondingEntry = it
                }
            }
        }
    }

    private fun transformEnumConstructor(enumConstructor: IrConstructor, enumClass: IrClass): IrConstructor {
        val loweredConstructorDescriptor = WrappedClassConstructorDescriptor()
        val loweredConstructorSymbol = IrConstructorSymbolImpl(loweredConstructorDescriptor)

        return IrConstructorImpl(
            enumConstructor.startOffset,
            enumConstructor.endOffset,
            enumConstructor.origin,
            loweredConstructorSymbol,
            enumConstructor.name,
            enumConstructor.visibility,
            enumConstructor.returnType,
            enumConstructor.isInline,
            enumConstructor.isExternal,
            enumConstructor.isPrimary
        ).apply {
            loweredConstructorDescriptor.bind(this)
            parent = enumClass
            valueParameters += JsIrBuilder.buildValueParameter("name", 0, context.irBuiltIns.stringType).also { it.parent = this }
            valueParameters += JsIrBuilder.buildValueParameter("ordinal", 1, context.irBuiltIns.intType).also { it.parent = this }
            copyParameterDeclarationsFrom(enumConstructor, false)
            body = enumConstructor.body

            enumConstructor.newConstructor = this
            this.originalConstructor = enumConstructor

            val oldParameters = enumConstructor.valueParameters
            val newParameters = valueParameters
            oldParameters.forEach { old ->
                // TODO Match by index?
                val new = newParameters.single { it.name == old.name }
                old.valueParameter = new
                new.valueDeclaration = old
            }
        }
    }
}


class EnumClassConstructorBodyTransformer(val context: JsIrBackendContext) : BodyLoweringPass {
    override fun lower(irBody: IrBody, container: IrDeclaration) {

        if (container is IrValueParameter) {
            ((container.parent as? IrFunction)?.parent as? IrClass)?.let { irClass ->
                if (container.valueDeclaration != null) {
                    container.defaultValue?.let { value ->
                        value.patchDeclarationParents(container.parent)
                        fixReferencesToConstructorParameters(irClass, value)
                    }
                }
            }
        }

        (container.parent as? IrClass)?.let { irClass ->

            // TODO Don't apply to everything
            // The first step creates a new `IrConstructor` with new `IrValueParameter`s so references to old `IrValueParameter`s must be replaced with new ones.
            irBody?.let { fixReferencesToConstructorParameters(irClass, it) }

            if (container is IrConstructor) {

                if (irClass.isEnumClass) {
                    container.originalConstructor?.let {
                        container.acceptVoid(PatchDeclarationParentsVisitor(irClass))

                        // Make sure InstanceInitializer exists
                        insertInstanceInitializer(irClass, container)

                        // Pass these parameters to delegating constructor calls
                        lowerEnumConstructorsBody(container)
                    }
                }

                irClass.correspondingEntry?.let { enumEntry ->
                    // Lower `IrEnumConstructorCall`s inside of enum entry class constructors to corresponding `IrDelegatingConstructorCall`s.
                    // Add `name` and `ordinal` parameters.
                    lowerEnumEntryClassConstructors(irClass, enumEntry, container)
                }
            }

            if (container is IrEnumEntry) {
                // Lower `IrEnumConstructorCall`s to corresponding `IrCall`s.
                // Add `name` and `ordinal` constant parameters only for calls to the "enum class" constructors ("enum entry class" constructors
                // already delegate these parameters)
                lowerEnumEntryInitializerExpression(irClass, container)
            }
        }
    }

    private fun insertInstanceInitializer(irClass: IrClass, declaration: IrConstructor) {
        val blockBody = declaration.body as IrBlockBody

        if (!blockBody.statements.any { it is IrInstanceInitializerCall }) {
            blockBody.statements.transformFlat {
                if (it is IrEnumConstructorCall)
                    listOf(
                        it, IrInstanceInitializerCallImpl(
                            declaration.startOffset, declaration.startOffset,
                            irClass.symbol, context.irBuiltIns.unitType
                        )
                    )
                else null
            }
        }
    }

    private fun lowerEnumConstructorsBody(constructor: IrConstructor) {
        IrEnumClassConstructorTransformer(constructor).transformBody()
    }

    private inner class IrEnumClassConstructorTransformer(val constructor: IrConstructor) : IrElementTransformerVoid() {
        val builder = context.createIrBuilder(constructor.symbol)

        fun transformBody() {
            constructor.body?.transformChildrenVoid(this)
        }

        override fun visitEnumConstructorCall(expression: IrEnumConstructorCall) =
            builder.irDelegatingConstructorCall(expression.symbol.owner).apply {
                for (i in 0..1) {
                    putValueArgument(i, builder.irGet(constructor.valueParameters[i]))
                }
            }

        override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
            val delegatingConstructor = expression.symbol.owner.let { it.newConstructor ?: it }

            return builder.irDelegatingConstructorCall(delegatingConstructor).apply {
                var valueArgIdx = 0
                for (i in 0..1) {
                    putValueArgument(valueArgIdx++, builder.irGet(constructor.valueParameters[i]))
                }
                for (i in 0 until expression.valueArgumentsCount) {
                    putValueArgument(valueArgIdx++, expression.getValueArgument(i))
                }
            }
        }
    }

    private fun fixReferencesToConstructorParameters(irClass: IrClass, body: IrBody) {
        body.transformChildrenVoid(object : IrElementTransformerVoid() {
            private val builder = context.createIrBuilder(irClass.symbol)

            override fun visitGetValue(expression: IrGetValue): IrExpression {
                expression.symbol.owner.valueParameter?.let {
                    return builder.irGet(it)
                }

                return super.visitGetValue(expression)
            }
        })
    }


    private fun lowerEnumEntryClassConstructors(irClass: IrClass, entry: IrEnumEntry, constructor: IrConstructor) {
        constructor.transformChildrenVoid(IrEnumEntryClassConstructorTransformer(irClass, entry, true))
    }

    private inner class IrEnumEntryClassConstructorTransformer(
        val irClass: IrClass,
        val entry: IrEnumEntry,
        val isInsideConstructor: Boolean
    ) :
        IrElementTransformerVoid() {

        private val enumEntries = irClass.declarations.filterIsInstance<IrEnumEntry>()

        private val builder = context.createIrBuilder(irClass.symbol)

        private fun IrEnumEntry.getNameExpression() = builder.irString(this.name.identifier)
        private fun IrEnumEntry.getOrdinalExpression() = builder.irInt(enumEntries.indexOf(this))

        private fun buildConstructorCall(constructor: IrConstructor) =
            if (isInsideConstructor)
                builder.irDelegatingConstructorCall(constructor)
            else
                builder.irCall(constructor)

        override fun visitEnumConstructorCall(expression: IrEnumConstructorCall): IrExpression {
            var constructor = expression.symbol.owner
            val constructorWasTransformed = constructor.newConstructor != null

            // Enum entry class constructors are not transformed
            if (constructorWasTransformed)
                constructor = constructor.newConstructor!!

            return buildConstructorCall(constructor).apply {
                var valueArgIdx = 0

                // Enum entry class constructors already delegate name and ordinal parameters in their body
                if (constructorWasTransformed) {
                    putValueArgument(valueArgIdx++, entry.getNameExpression())
                    putValueArgument(valueArgIdx++, entry.getOrdinalExpression())
                }
                for (i in 0 until expression.valueArgumentsCount) {
                    putValueArgument(valueArgIdx++, expression.getValueArgument(i))
                }
            }
        }
    }

    private fun lowerEnumEntryInitializerExpression(irClass: IrClass, entry: IrEnumEntry) {
        entry.initializerExpression =
            entry.initializerExpression?.transform(IrEnumEntryClassConstructorTransformer(irClass, entry, false), null)
    }
}

//-------------------------------------------------------

// Should be applied recursively
class EnumClassLowering(val context: JsIrBackendContext) : DeclarationTransformer {
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        return if (declaration is IrClass && declaration.isEnumClass &&
            !declaration.descriptor.isExpect && !declaration.isEffectivelyExternal()
        ) {
            EnumClassTransformer(context, declaration).transform()
        } else null
    }
}


private var IrEnumEntry.correspondingField by mapping(object : MappingKey<IrEnumEntry, IrVariable>{}) // TODO IrField
private var IrSimpleFunction.initField by mapping(object : MappingKey<IrSimpleFunction, IrVariable> {}) // TODO IrField

private var IrSimpleFunction.correspondingEnum by mapping(object : MappingKey<IrSimpleFunction, IrClass>{})
private var IrClass.enumInitInstanceFun by mapping(object : MappingKey<IrClass, IrSimpleFunction>{})

private var IrSimpleFunction.getInstanceToEnumEntry by mapping(object : MappingKey<IrSimpleFunction, IrEnumEntry>{})

private var IrFunction.syntheticBodyKind by mapping(object : MappingKey<IrFunction, IrSyntheticBodyKind>{})

class EnumClassTransformer(val context: JsIrBackendContext, private val irClass: IrClass) {
    private val builder = context.createIrBuilder(irClass.symbol)
    private val enumEntries = irClass.declarations.filterIsInstance<IrEnumEntry>()
    private val enumName = irClass.name.identifier

    fun transform(): List<IrDeclaration> {

        // Create instance variable for each enum entry initialized with `null`
        val entryInstances = createEnumEntryInstanceVariables()

        // Create boolean flag that indicates if entry instances were initialized.
        val entryInstancesInitializedVar = createEntryInstancesInitializedVar()

        // Create function that initializes all enum entry instances using `IrEnumEntry.initializationExpression`.
        // It should be called on the first `IrGetEnumValue`, consecutive calls to this function will do nothing.
        val initEntryInstancesFun = createInitEntryInstancesFun()

        initEntryInstancesFun.initField = entryInstancesInitializedVar
        irClass.enumInitInstanceFun = initEntryInstancesFun
        initEntryInstancesFun.correspondingEnum = irClass

        // Create entry instance getters. These are used to lower `IrGetEnumValue`.
        val entryGetInstanceFuns = createGetEntryInstanceFuns()

        lowerSyntheticFunction()

        return listOf(irClass) + entryInstances + listOf(entryInstancesInitializedVar, initEntryInstancesFun) + entryGetInstanceFuns
    }

    private fun lowerSyntheticFunction() {
        irClass.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitBody(body: IrBody) {
                // Skip
            }

            override fun visitFunction(declaration: IrFunction) {
                (declaration.body as? IrSyntheticBody)?.let { body ->
                    declaration.syntheticBodyKind = body.kind
                    declaration.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET)
                }
            }
        })
    }

    private fun createEnumEntryInstanceVariables() = enumEntries.map { enumEntry ->
        val type = enumEntry.getType(irClass).makeNullable()
        val name = "${enumName}_${enumEntry.name.identifier}_instance"
        builder.run {
            scope.createTmpVariable(irImplicitCast(irNull(), type), name)
        }.also {
            enumEntry.correspondingField = it
        }
    }

    private fun createGetEntryInstanceFuns() = enumEntries.mapIndexed { index, enumEntry ->
        enumEntry::getInstanceFun.getOrPut { buildFunction(createEntryAccessorName(enumName, enumEntry), enumEntry.getType(irClass)) }.also {
            it.getInstanceToEnumEntry = enumEntry
            it.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET)
        }
    }

    private fun createInitEntryInstancesFun() = buildFunction("${enumName}_initEntries").also {
        it.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET)
    }

    private fun createEntryInstancesInitializedVar(): IrVariable {
        return builder.scope.createTemporaryVariable(
            builder.irBoolean(false),
            "${enumName}_entriesInitialized"
        )
    }

    private fun buildFunction(
        name: String,
        returnType: IrType = context.irBuiltIns.unitType
    ) = JsIrBuilder.buildFunction(name, returnType, irClass)
}


class EnumClassBodyTransformer(val context: JsIrBackendContext) : BodyLoweringPass {
    private val throwISESymbol = context.throwISEymbol

    override fun lower(irBody: IrBody, container: IrDeclaration) {

        // Create instance variable for each enum entry initialized with `null`
        if (container is IrConstructor) {
            (container.parent as? IrClass)?.let { irClass ->
                irClass.correspondingEntry?.let { enumEntry ->
                    enumEntry.correspondingField!!.let { field ->
                        // Initialize entry instance at the beginning of constructor so it can be used inside constructor body
                        (container.body as? IrBlockBody)?.apply {
                            statements.add(0, context.createIrBuilder(container.symbol).run {
                                irSetVar(field.symbol, irGet(enumEntry.correspondingClass!!.thisReceiver!!))
                            })
                        }
                    }
                }
            }
        }

        // Create function that initializes all enum entry instances using `IrEnumEntry.initializationExpression`.
        // It should be called on the first `IrGetEnumValue`, consecutive calls to this function will do nothing.
        if (container is IrSimpleFunction) {
            container.initField?.let { entryInstancesInitializedVar ->
                val irClass = container.correspondingEnum!!

                (container.body as IrBlockBody).statements += context.createIrBuilder(container.symbol).irBlockBody(container) {
                    +irIfThen(irGet(entryInstancesInitializedVar), irReturnUnit())
                    +irSetVar(entryInstancesInitializedVar.symbol, irBoolean(true))

                    irClass.declarations.filterIsInstance<IrEnumEntry>().forEach { entry ->
                        entry.correspondingField?.let { instanceVar ->
                            +irSetVar(instanceVar.symbol, entry.initializerExpression!!.expression)
                        }
                    }
                }.also {
                    // entry.initializerExpression can have local declarations
                    it.acceptVoid(PatchDeclarationParentsVisitor(irClass))
                }.statements
            }
        }

        // Create entry instance getters. These are used to lower `IrGetEnumValue`.
        if (container is IrSimpleFunction) {
            container.getInstanceToEnumEntry?.let { enumEntry ->

                val irClass = enumEntry.parentAsClass
                val initEntryInstancesFun = irClass.enumInitInstanceFun!!

                (container.body as IrBlockBody).statements += context.createIrBuilder(container.symbol).irBlockBody(container) {
                    +irCall(initEntryInstancesFun)
                    +irReturn(irGet(enumEntry.correspondingField!!))
                }.statements
            }
        }

        // Create body for `values` and `valueOf` functions
        if (container is IrFunction) {
            container.syntheticBodyKind?.let { kind ->
                val irClass = container.parentAsClass

                (container.body as IrBlockBody).statements += when (kind) {
                    IrSyntheticBodyKind.ENUM_VALUES -> createEnumValuesBody(container, irClass)
                    IrSyntheticBodyKind.ENUM_VALUEOF -> createEnumValueOfBody(container, irClass)
                }.statements
            }
        }
    }

    // TODO cache
    private fun IrClass.enumEntries() = declarations.filterIsInstance<IrEnumEntry>()

    private fun createEnumValueOfBody(valueOfFun: IrFunction, irClass: IrClass): IrBlockBody {
        val nameParameter = valueOfFun.valueParameters[0]

        return context.createIrBuilder(valueOfFun.symbol).run {
            irBlockBody {
                +irReturn(
                    irWhen(
                        irClass.defaultType,
                        irClass.enumEntries().map {
                            irBranch(
                                irEquals(irString(it.name.identifier), irGet(nameParameter)), irCall(it.getInstanceFun!!)
                            )
                        } + irElseBranch(irCall(throwISESymbol))
                    )
                )
            }
        }
    }

    private fun List<IrExpression>.toArrayLiteral(arrayType: IrType, elementType: IrType): IrExpression {
        val irVararg = IrVarargImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, arrayType, elementType, this)

        return IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, arrayType, context.intrinsics.arrayLiteral).apply {
            putValueArgument(0, irVararg)
        }
    }

    private fun createEnumValuesBody(valuesFun: IrFunction, irClass: IrClass): IrBlockBody {
        return context.createIrBuilder(valuesFun.symbol).run {
            irBlockBody {
                +irReturn(
                    irClass.enumEntries().map { irCall(it.getInstanceFun!!) }
                        .toArrayLiteral(valuesFun.returnType, irClass.defaultType)
                )
            }
        }
    }
}


// Should be applied recursively
class EnumClassRemoveEntriesLowering(val context: JsIrBackendContext) : DeclarationTransformer {
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        if (declaration is IrClass && declaration.isEnumClass &&
            !declaration.descriptor.isExpect && !declaration.isEffectivelyExternal()
        ) {
            // Remove IrEnumEntry nodes from class declarations. Replace them with corresponding class declarations (if they have them).
            declaration.transformDeclarationsFlat {
                listOfNotNull(if (it is IrEnumEntry) it.correspondingClass else it)
            }
        }

        return null
    }
}