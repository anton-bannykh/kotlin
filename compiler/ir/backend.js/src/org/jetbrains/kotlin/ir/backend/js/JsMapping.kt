/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.backend.common.DefaultMapping
import org.jetbrains.kotlin.backend.common.Mapping
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.symbols.IrSymbol

class JsMapping(private val irFactory: IrFactory) : DefaultMapping() {
    val outerThisFieldSymbols = newDeclarationToDeclarationMapping<IrClass, IrField>()
    val innerClassConstructors = newDeclarationToDeclarationMapping<IrConstructor, IrConstructor>()
    val originalInnerClassPrimaryConstructorByClass = newDeclarationToDeclarationMapping<IrClass, IrConstructor>()
    val secondaryConstructorToDelegate = newDeclarationToDeclarationMapping<IrConstructor, IrSimpleFunction>()
    val secondaryConstructorToFactory = newDeclarationToDeclarationMapping<IrConstructor, IrSimpleFunction>()
    val objectToGetInstanceFunction = newDeclarationToDeclarationMapping<IrClass, IrSimpleFunction>()
    val objectToInstanceField = newDeclarationToDeclarationMapping<IrClass, IrField>()
    val classToSyntheticPrimaryConstructor = newDeclarationToDeclarationMapping<IrClass, IrConstructor>()
    val privateMemberToCorrespondingStatic = newDeclarationToDeclarationMapping<IrFunction, IrSimpleFunction>()

    val constructorToInitFunction = newDeclarationToDeclarationMapping<IrConstructor, IrSimpleFunction>()

    val enumEntryToGetInstanceFun = newDeclarationToDeclarationMapping<IrEnumEntry, IrSimpleFunction>()
    val enumEntryToInstanceField = newDeclarationToDeclarationMapping<IrEnumEntry, IrField>()
    val enumConstructorToNewConstructor = newDeclarationToDeclarationMapping<IrConstructor, IrConstructor>()
    val enumClassToCorrespondingEnumEntry = newDeclarationToDeclarationMapping<IrClass, IrEnumEntry>()
    val enumConstructorOldToNewValueParameters = newDeclarationToDeclarationMapping<IrValueDeclaration, IrValueParameter>()
    val enumEntryToCorrespondingField = newDeclarationToDeclarationMapping<IrEnumEntry, IrField>()
    val enumClassToInitEntryInstancesFun = newDeclarationToDeclarationMapping<IrClass, IrSimpleFunction>()


    override fun <K : IrDeclaration, V : IrDeclaration> newDeclarationToDeclarationMapping(): Mapping.Delegate<K, V> {
        return JsMappingDelegate<K, V>()
    }

    override fun <K : IrDeclaration, V : Collection<IrDeclaration>> newDeclarationToDeclarationCollectionMapping(): Mapping.Delegate<K, V> {
        return super.newDeclarationToDeclarationCollectionMapping()
    }

    // Triggers `StageController.lazyLower` on access
//    override fun <K : IrDeclaration, V> newMapping(): Mapping.Delegate<K, V> {
//        return JsMappingDelegate<K, V>().also {
//            allMappings += it
//        }
//    }

    private val allMappings = mutableListOf<JsMappingDelegate<*, *>>()

    private inner class JsMappingDelegate<K: IrDeclaration, V: IrDeclaration> : Mapping.Delegate<K, V>() {

        init {
            allMappings += this
        }

        private val map: MutableMap<IrSymbol, IrSymbol> = mutableMapOf()

        override operator fun get(key: K): V? {
            irFactory.stageController.lazyLower(key)
            return map[(key as IrSymbolOwner).symbol]?.owner as V
        }

        override operator fun set(key: K, value: V?) {
            irFactory.stageController.lazyLower(key)
            if (value == null) {
                map.remove((key as IrSymbolOwner).symbol)
            } else {
                map[(key as IrSymbolOwner).symbol] = (value as IrSymbolOwner).symbol
            }
        }
    }

    fun serializeMappings(declarations: Iterable<IrDeclaration>): SerializedMappings {
        allMappings.forEach { mapping ->
            // TODO mapping.serializeFor(dec
        }

        return SerializedMappings()
    }

    fun deserializeMappings(mapping: SerializedMappings) {
        // TODO
    }
}

class SerializedMappings()