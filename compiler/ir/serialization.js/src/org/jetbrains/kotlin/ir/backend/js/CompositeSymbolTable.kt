/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.backend.common.descriptors.WrappedDeclarationDescriptor
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.resolve.descriptorUtil.module

class CompositeSymbolTable(val moduleMap: Map<ModuleDescriptor, SymbolTable>) : SymbolTable() {

    private val DeclarationDescriptor.symbolTable
        get() = moduleMap[(this as? WrappedDeclarationDescriptor<*>)?.containingModule ?: this.module] ?:
            error("Unexpected module: ${this.module}")

    private fun <T> all(fn: SymbolTable.() -> Set<T>): Set<T> {
        return moduleMap.values.flatMapTo(mutableSetOf()) { it.fn() }
    }

    private fun forAll(fn: SymbolTable.() -> Unit)  {
        moduleMap.values.forEach { it.fn() }
    }


    override fun referenceExternalPackageFragment(descriptor: PackageFragmentDescriptor): IrExternalPackageFragmentSymbol {
        return descriptor.symbolTable.referenceExternalPackageFragment(descriptor)
    }

    override fun declareExternalPackageFragment(descriptor: PackageFragmentDescriptor): IrExternalPackageFragment {
        return descriptor.symbolTable.declareExternalPackageFragment(descriptor)
    }

    override fun declareAnonymousInitializer(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: ClassDescriptor
    ): IrAnonymousInitializer {
        return descriptor.symbolTable.declareAnonymousInitializer(startOffset, endOffset, origin, descriptor)
    }

    override fun declareClass(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: ClassDescriptor,
        modality: Modality,
        classFactory: (IrClassSymbol) -> IrClass
    ): IrClass {
        return descriptor.symbolTable.declareClass(startOffset, endOffset, origin, descriptor, modality, classFactory)
    }

    override fun referenceClass(descriptor: ClassDescriptor): IrClassSymbol {
        return descriptor.symbolTable.referenceClass(descriptor)
    }

    override val unboundClasses: Set<IrClassSymbol>
        get() = all { unboundClasses }

    override fun declareConstructor(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: ClassConstructorDescriptor,
        constructorFactory: (IrConstructorSymbol) -> IrConstructor
    ): IrConstructor {
        return descriptor.symbolTable.declareConstructor(startOffset, endOffset, origin, descriptor, constructorFactory)
    }

    override fun referenceConstructor(descriptor: ClassConstructorDescriptor): IrConstructorSymbol {
        return descriptor.symbolTable.referenceConstructor(descriptor)
    }

    override val unboundConstructors: Set<IrConstructorSymbol>
        get() = all { unboundConstructors }

    override fun declareEnumEntry(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: ClassDescriptor,
        factory: (IrEnumEntrySymbol) -> IrEnumEntry
    ): IrEnumEntry {
        return descriptor.symbolTable.declareEnumEntry(startOffset, endOffset, origin, descriptor, factory)
    }

    override fun referenceEnumEntry(descriptor: ClassDescriptor): IrEnumEntrySymbol {
        return descriptor.symbolTable.referenceEnumEntry(descriptor)
    }

    override val unboundEnumEntries: Set<IrEnumEntrySymbol>
        get() = all { unboundEnumEntries }

    override fun declareField(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: PropertyDescriptor,
        type: IrType,
        fieldFactory: (IrFieldSymbol) -> IrField
    ): IrField {
        return descriptor.symbolTable.declareField(startOffset, endOffset, origin, descriptor, type, fieldFactory)
    }

    override fun declareField(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: PropertyDescriptor,
        type: IrType,
        irInitializer: IrExpressionBody?
    ): IrField {
        return descriptor.symbolTable.declareField(startOffset, endOffset, origin, descriptor, type, irInitializer)
    }

    override fun referenceField(descriptor: PropertyDescriptor): IrFieldSymbol {
        return descriptor.symbolTable.referenceField(descriptor)
    }

    override val unboundFields: Set<IrFieldSymbol>
        get() = all { unboundFields }

    override fun referenceProperty(descriptor: PropertyDescriptor, generate: () -> IrProperty): IrProperty {
        return descriptor.symbolTable.referenceProperty(descriptor, generate)
    }

    override fun declareProperty(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: PropertyDescriptor,
        isDelegated: Boolean,
        propertyFactory: (IrPropertySymbol) -> IrProperty
    ): IrProperty {
        return descriptor.symbolTable.declareProperty(startOffset, endOffset, origin, descriptor, isDelegated, propertyFactory)
    }

    override fun referenceProperty(descriptor: PropertyDescriptor): IrPropertySymbol {
        return descriptor.symbolTable.referenceProperty(descriptor)
    }

    override val unboundProperties: Set<IrPropertySymbol>
        get() = all { unboundProperties }

    override fun declareSimpleFunction(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: FunctionDescriptor,
        functionFactory: (IrSimpleFunctionSymbol) -> IrSimpleFunction
    ): IrSimpleFunction {
        return descriptor.symbolTable.declareSimpleFunction(startOffset, endOffset, origin, descriptor, functionFactory)
    }

    override fun referenceSimpleFunction(descriptor: FunctionDescriptor): IrSimpleFunctionSymbol {
        return descriptor.symbolTable.referenceSimpleFunction(descriptor)
    }

    override fun referenceDeclaredFunction(descriptor: FunctionDescriptor): IrSimpleFunctionSymbol {
        return descriptor.symbolTable.referenceDeclaredFunction(descriptor)
    }

    override val unboundSimpleFunctions: Set<IrSimpleFunctionSymbol>
        get() = all { unboundSimpleFunctions }

    override fun declareGlobalTypeParameter(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: TypeParameterDescriptor,
        typeParameterFactory: (IrTypeParameterSymbol) -> IrTypeParameter
    ): IrTypeParameter {
        return descriptor.symbolTable.declareGlobalTypeParameter(startOffset, endOffset, origin, descriptor, typeParameterFactory)
    }

    override fun declareScopedTypeParameter(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: TypeParameterDescriptor,
        typeParameterFactory: (IrTypeParameterSymbol) -> IrTypeParameter
    ): IrTypeParameter {
        return descriptor.symbolTable.declareScopedTypeParameter(startOffset, endOffset, origin, descriptor, typeParameterFactory)
    }

    override val unboundTypeParameters: Set<IrTypeParameterSymbol>
        get() = all { unboundTypeParameters }

    override fun declareValueParameter(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: ParameterDescriptor,
        type: IrType,
        varargElementType: IrType?,
        valueParameterFactory: (IrValueParameterSymbol) -> IrValueParameter
    ): IrValueParameter {
        return descriptor.symbolTable.declareValueParameter(
            startOffset,
            endOffset,
            origin,
            descriptor,
            type,
            varargElementType,
            valueParameterFactory
        )
    }

    override fun introduceValueParameter(irValueParameter: IrValueParameter) {
        irValueParameter.descriptor.symbolTable.introduceValueParameter(irValueParameter)
    }

    override fun referenceValueParameter(descriptor: ParameterDescriptor): IrValueParameterSymbol {
        return descriptor.symbolTable.referenceValueParameter(descriptor)
    }

    override fun referenceTypeParameter(classifier: TypeParameterDescriptor): IrTypeParameterSymbol {
        return classifier.symbolTable.referenceTypeParameter(classifier)
    }

    override val unboundValueParameters: Set<IrValueParameterSymbol>
        get() = all { unboundValueParameters }

    override fun declareVariable(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: VariableDescriptor,
        type: IrType,
        variableFactory: (IrVariableSymbol) -> IrVariable
    ): IrVariable {
        return descriptor.symbolTable.declareVariable(startOffset, endOffset, origin, descriptor, type, variableFactory)
    }

    override fun declareVariable(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: VariableDescriptor,
        type: IrType,
        irInitializerExpression: IrExpression?
    ): IrVariable {
        return descriptor.symbolTable.declareVariable(startOffset, endOffset, origin, descriptor, type, irInitializerExpression)
    }

    override fun referenceVariable(descriptor: VariableDescriptor): IrVariableSymbol {
        return descriptor.symbolTable.referenceVariable(descriptor)
    }

    override val unboundVariables: Set<IrVariableSymbol>
        get() = all { unboundVariables }

    override fun declareLocalDelegatedProperty(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: VariableDescriptorWithAccessors,
        type: IrType
    ): IrLocalDelegatedProperty {
        return descriptor.symbolTable.declareLocalDelegatedProperty(startOffset, endOffset, origin, descriptor, type)
    }

    override fun referenceLocalDelegatedProperty(descriptor: VariableDescriptorWithAccessors): IrLocalDelegatedPropertySymbol {
        return descriptor.symbolTable.referenceLocalDelegatedProperty(descriptor)
    }

    override fun enterScope(owner: DeclarationDescriptor) {
        forAll { enterScope(owner) }
    }

    override fun leaveScope(owner: DeclarationDescriptor) {
        forAll { leaveScope(owner) }
    }

    override fun referenceValue(value: ValueDescriptor): IrValueSymbol {
        return value.symbolTable.referenceValue(value)
    }
}