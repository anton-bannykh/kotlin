/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
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
import org.jetbrains.kotlin.ir.expressions.impl.*
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

private var IrConstructor.newConstructor by mapping(object : MappingKey<IrConstructor, IrConstructor>{})
private var IrClass.correspondingEntry by mapping(object : MappingKey<IrClass, IrEnumEntry>{})
private var IrValueDeclaration.valueParameter by mapping(object : MappingKey<IrValueDeclaration, IrValueParameter>{})

// Should be applied recursively
class EnumClassConstructorLowering(val context: JsIrBackendContext) : DeclarationTransformer {

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        (declaration.parent as? IrClass)?.let { irClass ->
            if (!irClass.isEnumClass || irClass.descriptor.isExpect || irClass.isEffectivelyExternal()) return null

            if (declaration is IrConstructor) {
                // Add `name` and `ordinal` parameters to enum class constructors
                return listOf(transformEnumConstructor(declaration, irClass))
            }

            if (declaration is IrEnumEntry) {
                declaration.correspondingClass?.let { klass ->
                    klass.correspondingEntry = declaration
                }
            }
        }

        return null
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

            val newConstructor = this

            enumConstructor.body?.let { oldBody ->
                body = IrBlockBodyImpl(oldBody.startOffset, oldBody.endOffset) {
                    statements += (oldBody as IrBlockBody).statements

                    context.fixReferencesToConstructorParameters(enumClass, this)

                    acceptVoid(PatchDeclarationParentsVisitor(enumClass))

                    // Make sure InstanceInitializer exists
                    insertInstanceInitializer(enumClass, newConstructor)

                    // Pass these parameters to delegating constructor calls
                    lowerEnumConstructorsBody(newConstructor)
                }
            }

            enumConstructor.newConstructor = this

            val oldParameters = enumConstructor.valueParameters
            val newParameters = valueParameters
            oldParameters.forEach { old ->
                // TODO Match by index?
                val new = newParameters.single { it.name == old.name }
                old.valueParameter = new

                old.defaultValue?.let { default ->
                    new.defaultValue = IrExpressionBodyImpl(default.startOffset, default.endOffset) {
                        expression = default.expression
                        expression.patchDeclarationParents(newConstructor)
                        context.fixReferencesToConstructorParameters(enumClass, this)
                    }
                }
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
}

// The first step creates a new `IrConstructor` with new `IrValueParameter`s so references to old `IrValueParameter`s must be replaced with new ones.
private fun JsIrBackendContext.fixReferencesToConstructorParameters(irClass: IrClass, body: IrBody) {
    body.transformChildrenVoid(object : IrElementTransformerVoid() {
        private val builder = createIrBuilder(irClass.symbol)

        override fun visitGetValue(expression: IrGetValue): IrExpression {
            expression.symbol.owner.valueParameter?.let {
                return builder.irGet(it)
            }

            return super.visitGetValue(expression)
        }
    })
}

class EnumClassConstructorBodyTransformer(val context: JsIrBackendContext) : BodyLoweringPass {
    override fun lower(irBody: IrBody, container: IrDeclaration) {

        (container.parent as? IrClass)?.let { irClass ->

            // TODO Don't apply to everything
            context.fixReferencesToConstructorParameters(irClass, irBody)

            if (container is IrConstructor) {

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
        val initEntryInstancesFun = createInitEntryInstancesFun(entryInstancesInitializedVar)

        // Create entry instance getters. These are used to lower `IrGetEnumValue`.
        val entryGetInstanceFuns = createGetEntryInstanceFuns(initEntryInstancesFun)

        // Create body for `values` and `valueOf` functions
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
                    val kind = body.kind
                    declaration.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET) {
                        statements += when (kind) {
                            IrSyntheticBodyKind.ENUM_VALUES -> createEnumValuesBody(declaration, irClass)
                            IrSyntheticBodyKind.ENUM_VALUEOF -> createEnumValueOfBody(declaration, irClass)
                        }.statements
                    }
                }
            }
        })
    }

    private fun createEnumEntryInstanceVariables() = enumEntries.map { enumEntry ->
        val type = enumEntry.getType(irClass).makeNullable()
        val name = "${enumName}_${enumEntry.name.identifier}_instance"
        val result = builder.run {
            scope.createTmpVariable(irImplicitCast(irNull(), type), name)
        }.also {
            enumEntry.correspondingField = it
        }

        enumEntry.correspondingClass?.constructors?.forEach {
            // Initialize entry instance at the beginning of constructor so it can be used inside constructor body
            (it.body as? IrBlockBody)?.let { oldBody ->
                it.body = IrBlockBodyImpl(oldBody.startOffset, oldBody.endOffset) {
                    statements += oldBody.statements
                    statements.add(0, context.createIrBuilder(it.symbol).run {
                        irSetVar(result.symbol, irGet(enumEntry.correspondingClass!!.thisReceiver!!))
                    })
                }
            }
        }

        result
    }

    private fun createGetEntryInstanceFuns(initEntryInstancesFun: IrSimpleFunction) = enumEntries.mapIndexed { index, enumEntry ->
        enumEntry::getInstanceFun.getOrPut { buildFunction(createEntryAccessorName(enumName, enumEntry), enumEntry.getType(irClass)) }
            .also {
                it.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET) {
                    statements += context.createIrBuilder(it.symbol).irBlockBody(it) {
                        +irCall(initEntryInstancesFun)
                        +irReturn(irGet(enumEntry.correspondingField!!))
                    }.statements
                }
            }
    }

    private fun createInitEntryInstancesFun(entryInstancesInitializedVar: IrVariable) = buildFunction("${enumName}_initEntries").also {
        it.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET) {
            statements += context.createIrBuilder(it.symbol).irBlockBody(it) {
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

    private val throwISESymbol = context.throwISEymbol

    private fun createEnumValueOfBody(valueOfFun: IrFunction, irClass: IrClass): IrBlockBody {
        val nameParameter = valueOfFun.valueParameters[0]

        return context.createIrBuilder(valueOfFun.symbol).run {
            irBlockBody {
                +irReturn(
                    irWhen(
                        irClass.defaultType,
                        enumEntries.map {
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
                    enumEntries.map { irCall(it.getInstanceFun!!) }
                        .toArrayLiteral(valuesFun.returnType, irClass.defaultType)
                )
            }
        }
    }
}

// Should be applied recursively
class EnumClassRemoveEntriesLowering(val context: JsIrBackendContext) : DeclarationTransformer {
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        // Remove IrEnumEntry nodes from class declarations. Replace them with corresponding class declarations (if they have them).
        if (declaration is IrEnumEntry && !declaration.descriptor.isExpect && !declaration.isEffectivelyExternal()) {
            return listOfNotNull(declaration.correspondingClass)
        }

        return null
    }
}