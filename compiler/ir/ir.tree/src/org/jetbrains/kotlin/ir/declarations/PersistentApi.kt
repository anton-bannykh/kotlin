/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations

import org.jetbrains.kotlin.ir.declarations.impl.IrBodyBase
import org.jetbrains.kotlin.ir.declarations.impl.IrDeclarationBase
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty

// TODO threadlocal
var stageController: StageController = NoopController()

interface StageController {
    val currentStage: Int

    fun lazyLower(declaration: IrDeclaration) {}

    fun lazyLower(file: IrFile) {}

    fun lowerBody(body: IrBodyBase<*>) {}

    fun <T> withStage(stage: Int, fn: () -> T): T = fn()

    fun tryLoad(symbol: IrSymbol) {}

    val bodiesEnabled: Boolean

    fun <T> withInitialIr(block: () -> T): T

    fun register(declaration: IrDeclarationBase<*>) {}

    fun <K: IrDeclaration, V> getUserdata(declaration: IrDeclaration): MutableMap<MappingKey<K, V>, V>
}

class NoopController(override var currentStage: Int = 0) : StageController {

    override val bodiesEnabled: Boolean
        get() = true

    override fun <T> withInitialIr(block: () -> T): T = block()

    private val userDataMap: MutableMap<IrDeclaration, MutableMap<MappingKey<IrDeclaration, Any>, Any>> = mutableMapOf()

    override fun <K : IrDeclaration, V> getUserdata(declaration: IrDeclaration): MutableMap<MappingKey<K, V>, V> {
        return userDataMap.getOrPut(declaration) { mutableMapOf<MappingKey<IrDeclaration, Any>, Any>() } as MutableMap<MappingKey<K, V>, V>
    }
}

interface MappingKey<K : IrDeclaration, V>

class MappingDelegate<K : IrDeclaration, V>(
    val key: MappingKey<K, V>
) {
    private val K.map: MutableMap<MappingKey<K, V>, V>
        get() = stageController.getUserdata(this)

    operator fun getValue(thisRef: K, desc: KProperty<*>): V? {
        stageController.lazyLower(thisRef)
        return thisRef.map[key] as V?
    }

    operator fun setValue(thisRef: K, desc: KProperty<*>, value: V?) {
        stageController.lazyLower(thisRef)
        if (value == null) {
            thisRef.map.remove(key)
        } else {
            thisRef.map[key] = value
        }
    }
}

fun <V : Any> KMutableProperty0<V?>.getOrPut(fn: () -> V) = this.get() ?: fn().also {
    this.set(it)
}

fun <K : IrDeclaration, V> mapping(key: MappingKey<K, V>) = MappingDelegate(key)

inline fun <K : IrDeclaration, V> mapping() = MappingDelegate(object : MappingKey<K, V> {})

