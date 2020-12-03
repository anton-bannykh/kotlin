/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.backend.common.DefaultMapping
import org.jetbrains.kotlin.backend.common.Mapping
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.library.impl.*

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
        return JsMappingDelegate<K, V>(irFactory).also {
            allMappings += it
        }
    }

    override fun <K : IrDeclaration, V : Collection<IrDeclaration>> newDeclarationToDeclarationCollectionMapping(): Mapping.Delegate<K, V> {
        return JsMappingCollectionDelegate<K, V>(irFactory).also {
            allMappings += it
        }
    }

    private val allMappings = mutableListOf<SerializableMapping>()

    fun serializeMappings(declarations: Iterable<IrDeclaration>, symbolSerializer: (IrSymbol) -> Long): SerializedMappings {
        return SerializedMappings(allMappings.map { mapping ->
            val keys = mutableListOf<Long>()
            val values = mutableListOf<ByteArray>()
            declarations.forEach { d ->
                mapping.serializeMapping(d, symbolSerializer)?.let { bytes ->
                    keys += symbolSerializer((d as IrSymbolOwner).symbol)
                    values += bytes
                }
            }

            SerializedMapping(
                IrMemoryLongArrayWriter(keys).writeIntoMemory(),
                IrMemoryArrayWriter(values).writeIntoMemory(),
            )
        })
    }

    fun deserializeMappings(mapping: SerializedMappings, symbolDeserializer: (Long) -> IrSymbol) {
        if (allMappings.size != mapping.mappings.size) error("Mapping size mismatch")

        allMappings.zip(mapping.mappings).forEach { (mapping, bytes) ->
            val keys = IrLongArrayMemoryReader(bytes.keys).array.map(symbolDeserializer)
            val values = IrArrayMemoryReader(bytes.values).toArray()

            if (keys.size != values.size) error("Keys size != values size")

            keys.zip(values).forEach { (s, b) ->
                mapping.loadMapping(s.owner as IrDeclaration, b, symbolDeserializer)
            }
        }
    }
}

class SerializedMappings(
    val mappings: List<SerializedMapping>
)

class SerializedMapping(
    val keys: ByteArray,
    val values: ByteArray,
)

private interface SerializableMapping {
    fun serializeMapping(declaration: IrDeclaration, symbolSerializer: (IrSymbol) -> Long): ByteArray?

    fun loadMapping(declaration: IrDeclaration, mapping: ByteArray, symbolDeserializer: (Long) -> IrSymbol)
}

private class JsMappingDelegate<K : IrDeclaration, V : IrDeclaration>(val irFactory: IrFactory) : Mapping.Delegate<K, V>(), SerializableMapping {

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

    override fun serializeMapping(declaration: IrDeclaration, symbolSerializer: (IrSymbol) -> Long): ByteArray? {
        return map[(declaration as IrSymbolOwner).symbol]?.let { symbol ->
            symbolSerializer(symbol).toByteArray()
        }
    }

    override fun loadMapping(declaration: IrDeclaration, mapping: ByteArray, symbolDeserializer: (Long) -> IrSymbol) {
        map[(declaration as IrSymbolOwner).symbol] = symbolDeserializer(mapping.toLong())
    }
}

private class JsMappingCollectionDelegate<K : IrDeclaration, V : Collection<IrDeclaration>>(val irFactory: IrFactory) : Mapping.Delegate<K, V>(), SerializableMapping {
    private val map: MutableMap<IrSymbol, Collection<IrSymbol>> = mutableMapOf()

    override operator fun get(key: K): V? {
        irFactory.stageController.lazyLower(key)
        return map[(key as IrSymbolOwner).symbol]?.map { it.owner as IrDeclaration } as? V
    }

    override operator fun set(key: K, value: V?) {
        irFactory.stageController.lazyLower(key)
        if (value == null) {
            map.remove((key as IrSymbolOwner).symbol)
        } else {
            map[(key as IrSymbolOwner).symbol] = value.map { (it as IrSymbolOwner).symbol }
        }
    }

    override fun serializeMapping(declaration: IrDeclaration, symbolSerializer: (IrSymbol) -> Long): ByteArray? {
        return map[(declaration as IrSymbolOwner).symbol]?.let { symbols ->
            IrMemoryLongArrayWriter(symbols.map(symbolSerializer)).writeIntoMemory()
        }
    }

    override fun loadMapping(declaration: IrDeclaration, mapping: ByteArray, symbolDeserializer: (Long) -> IrSymbol) {
        map[(declaration as IrSymbolOwner).symbol] = IrLongArrayMemoryReader(mapping).array.map(symbolDeserializer)
    }
}

fun ByteArray.toLong(): Long {
    var result = this[0].toLong()
    for (i in 1..7) {
        result = (result shl 8) or this[i].toLong()
    }
    return result
}

fun Long.toByteArray(): ByteArray {
    val result = ByteArray(8)

    var self = this

    for (i in 7 downTo 0) {
        result[i] = self.toByte()
        self = self ushr 8
    }

    return result
}