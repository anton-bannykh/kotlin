/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.impl.IrBodyBase
import org.jetbrains.kotlin.ir.declarations.impl.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.impl.IrPersistingElementBase
import org.jetbrains.kotlin.ir.symbols.*
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty

// TODO threadlocal
var stageController: StageController = object : StageController {}

interface StageController {
    val currentStage: Int get() = 0

    fun lazyLower(declaration: IrDeclaration) {}

    fun lowerBody(body: IrBodyBase<*>) {}

    fun <T> withStage(stage: Int, fn: () -> T): T = fn()

    val bodiesEnabled: Boolean get() = true

    fun <T> withInitialIr(block: () -> T): T = block()

    fun <T> withInitialStateOf(declaration: IrDeclaration, block: () -> T): T = block()

    fun register(declaration: IrDeclarationBase<*>) {}

    fun <K: IrDeclaration, V> getUserdata(declaration: IrDeclaration): MutableMap<MappingKey<K, V>, V> = error("Userdata not supported")

    fun <T> restrictTo(declaration: IrDeclaration, fn: () -> T): T = fn()

    fun <T> unsafe(fn: () -> T): T = fn()

    fun <T> bodyLowering(fn: () -> T): T = fn()

    fun canModify(element: IrElement): Boolean = true

    fun <T> unrestrictDeclarationListsAccess(fn: () -> T): T = fn()

    fun <T> declarationListAccess(declaration: IrClass, fn: () -> T): T = fn()
}

open class NoopController(override var currentStage: Int = 0) : StageController {

    override var bodiesEnabled: Boolean = true

    override fun <T> withStage(stage: Int, fn: () -> T): T {
        val prevStage = currentStage
        currentStage = stage
        try {
            return fn()
        } finally {
            currentStage = prevStage
        }
    }

    override fun <T> withInitialIr(block: () -> T): T = withStage(0, block)

    override fun <T> withInitialStateOf(declaration: IrDeclaration, block: () -> T): T = withStage((declaration as? IrPersistingElementBase<*>)?.createdOn ?: 0, block)

    private val userDataMap: MutableMap<IrDeclaration, MutableMap<MappingKey<IrDeclaration, Any>, Any>> = mutableMapOf()

    override fun <K : IrDeclaration, V> getUserdata(declaration: IrDeclaration): MutableMap<MappingKey<K, V>, V> {
        return userDataMap.getOrPut(declaration) { mutableMapOf<MappingKey<IrDeclaration, Any>, Any>() } as MutableMap<MappingKey<K, V>, V>
    }

    private var restricted: Boolean = false

    private var restrictedToDeclaration: IrDeclaration? = null

    override fun <T> restrictTo(declaration: IrDeclaration, fn: () -> T): T = restrictionImpl(declaration, fn)

    override fun <T> unsafe(fn: () -> T): T = restrictionImpl(null, fn)

    private fun <T> restrictionImpl(declaration: IrDeclaration?, fn: () -> T): T {
        val prev = restrictedToDeclaration
        restrictedToDeclaration = declaration
        val wereBodiesEnabled = bodiesEnabled
        bodiesEnabled = false
        val wasRestricted = restricted
        restricted = true
        val wereDeclarationListsRestricted = declarationListsRestricted
        declarationListsRestricted = true
        try {
            return fn()
        } finally {
            restrictedToDeclaration = prev
            bodiesEnabled = wereBodiesEnabled
            restricted = wasRestricted
            declarationListsRestricted = wereDeclarationListsRestricted
        }
    }

    override fun <T> bodyLowering(fn: () -> T): T {
        val wereBodiesEnabled = bodiesEnabled
        bodiesEnabled = true
        val wasRestricted = restricted
        restricted = true
        val wereDeclarationListsRestricted = declarationListsRestricted
        declarationListsRestricted = true
        try {
            return fn()
        } finally {
            bodiesEnabled = wereBodiesEnabled
            restricted = wasRestricted
            declarationListsRestricted = wereDeclarationListsRestricted
        }
    }

    override fun canModify(element: IrElement): Boolean {
        return true
//        return !restricted || restrictedToDeclaration === element || element is IrPersistingElementBase<*> && element.createdOn == currentStage
    }

    private var declarationListsRestricted = false

    override fun <T> unrestrictDeclarationListsAccess(fn: () -> T): T {
        val wereDeclarationListsRestricted = declarationListsRestricted
        declarationListsRestricted = false
        try {
            return fn()
        } finally {
            declarationListsRestricted = wereDeclarationListsRestricted
        }
    }

    override fun <T> declarationListAccess(declaration: IrClass, fn: () -> T): T {
        if (declarationListsRestricted && declaration.visibility != Visibilities.LOCAL && currentStage != (declaration as? IrPersistingElementBase<*>)?.createdOn ?: 0) {
            error("Cannot access declaration list at non-initial stage")
        }

        return fn()
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

inline fun <T> IrClass.withInitialState(noinline fn: IrClass.() -> T): T {
    return stageController.withInitialStateOf(this) {
        this.fn()
    }
}

inline fun <T> withInitialIr(noinline fn: () -> T): T {
    return stageController.withInitialIr(fn)
}

inline val IrClass.initialDeclarations: List<IrDeclaration>
    get() = stageController.withInitialStateOf(this) { this.declarations }

val IrClass.initialFunctions: Sequence<IrSimpleFunction>
    get() = initialDeclarations.asSequence().filterIsInstance<IrSimpleFunction>()

val IrClassSymbol.initialFunctions: Sequence<IrSimpleFunctionSymbol>
    get() = owner.initialFunctions.map { it.symbol }

val IrClass.initialConstructors: Sequence<IrConstructor>
    get() = initialDeclarations.asSequence().filterIsInstance<IrConstructor>()

val IrClassSymbol.initialConstructors: Sequence<IrConstructorSymbol>
    get() = owner.initialConstructors.map { it.symbol }

val IrClass.initialFields: Sequence<IrField>
    get() = initialDeclarations.asSequence().filterIsInstance<IrField>()

val IrClassSymbol.initialFields: Sequence<IrFieldSymbol>
    get() = owner.initialFields.map { it.symbol }

val IrClass.initialPrimaryConstructor: IrConstructor?
    get() = initialDeclarations.singleOrNull { it is IrConstructor && it.isPrimary } as IrConstructor?

val IrClass.initialProperties: Sequence<IrProperty>
    get() = initialDeclarations.asSequence().filterIsInstance<IrProperty>()