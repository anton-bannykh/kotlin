/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations

import org.jetbrains.kotlin.ir.util.transformFlat
import java.util.*
import kotlin.NoSuchElementException
import kotlin.reflect.KProperty

// TODO threadlocal
var stageController: StageController = NoopController()

interface StageController {
    val currentStage: Int

    fun lazyLower(declaration: IrDeclaration) {}

    fun lazyLower(file: IrFile) {}
}

class NoopController : StageController {
    override val currentStage: Int = 0
}

class PersistentVar<T : Any>(private val container: IrDeclaration?,
                             initValue: T) {
    private fun ensureLowered() {
        container?.let { stageController.lazyLower(it) }
    }

    val changes = TreeMap(mapOf(0 to initValue))

    operator fun getValue(thisRef: Any, property: KProperty<*>): T {
        ensureLowered()
        return changes.lowerEntry(stageController.currentStage + 1)!!.value
    }

    operator fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
        ensureLowered()
        changes[stageController.currentStage] = value
    }
}


class NullablePersistentVar<T>(private val container: IrDeclaration?) {
    private fun ensureLowered() {
        container?.let { stageController.lazyLower(it) }
    }

    private val changes = TreeMap<Int, T?>()

    operator fun getValue(thisRef: Any, property: KProperty<*>): T? {
        ensureLowered()
        return changes.lowerEntry(stageController.currentStage + 1)?.value
    }

    operator fun setValue(thisRef: Any, property: KProperty<*>, value: T?) {
        ensureLowered()
        changes[stageController.currentStage] = value
    }
}

class LateInitPersistentVar<T : Any>(private val container: IrDeclaration?) {
    private val createdOn = stageController.currentStage

    private fun ensureLowered() {
        container?.let { stageController.lazyLower(it) }
    }

    val changes = TreeMap<Int, T>()

    operator fun getValue(thisRef: Any, property: KProperty<*>): T {
        if (stageController.currentStage != createdOn) ensureLowered()
        return changes.lowerEntry(stageController.currentStage + 1)!!.value
    }

    operator fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
        if (stageController.currentStage != createdOn) ensureLowered()
        changes[stageController.currentStage] = value
    }
}

class ParentPersistentVar<T : Any>(private val container: IrDeclaration?) {
    private val createdOn = stageController.currentStage

    private fun ensureLowered() {
        val parent = changes.lowerEntry(stageController.currentStage + 1)?.value
        if (parent is IrDeclaration) {
            stageController.lazyLower(parent)
        } else if (parent is IrFile) {
            stageController.lazyLower(parent)
        }
    }

    val changes = TreeMap<Int, T>()

    operator fun getValue(thisRef: Any, property: KProperty<*>): T {
        if (stageController.currentStage != createdOn) ensureLowered()
        return changes.lowerEntry(stageController.currentStage + 1)!!.value
    }

    operator fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
        if (stageController.currentStage != createdOn) ensureLowered()
        changes[stageController.currentStage] = value
    }
}


interface SimpleList<T> : List<T> {

    fun add(element: T): Boolean

    fun addFirst(element: T)

    fun addAll(elements: Collection<T>): Boolean

    fun addFirstAll(elements: Collection<T>)

    operator fun plusAssign(element: T)

    operator fun plusAssign(elements: Collection<T>)

    fun removeAll(predicate: (T) -> Boolean): Boolean

    fun removeAll(elements: Collection<T>): Boolean

    fun clear()

    fun transform(transformation: (T) -> T)

    fun transformFlat(transformation: (T) -> List<T>?)

    fun remove(element: T): Boolean
}

inline fun <T, R> Iterable<T>.mapTo(to: SimpleList<in R>, fn: (T) -> R) = forEach { to.add(fn(it)) }

inline fun <T, R> Iterable<T>.mapNotNullTo(to: SimpleList<in R>, fn: (T) -> R?) = forEach { fn(it)?.let { to.add(it) } }

inline fun <T, R> Iterable<T>.mapIndexedTo(
    destination: SimpleList<in R>,
    transform: (index: Int, T) -> R
): SimpleList<in R> {
    forEachIndexed { index, value ->
        destination.add(transform(index, value))
    }

    return destination
}

class SimpleMutableList<T>(private val list: MutableList<T>) : SimpleList<T>, List<T> by list {
    override fun add(element: T): Boolean = list.add(element)

    override fun addFirst(element: T) {
        list.add(0, element)
    }

    override fun addAll(elements: Collection<T>): Boolean = list.addAll(elements)

    override fun addFirstAll(elements: Collection<T>) {
        list.addAll(0, elements)
    }

    override fun plusAssign(element: T) {
        list += element
    }

    override fun plusAssign(elements: Collection<T>) {
        list += elements
    }

    override fun removeAll(predicate: (T) -> Boolean): Boolean = list.removeAll(predicate)

    override fun removeAll(elements: Collection<T>): Boolean = list.removeAll(elements)

    override fun clear() {
        list.clear()
    }

    override fun transform(transformation: (T) -> T) {
        list.replaceAll(transformation)
    }

    override fun transformFlat(transformation: (T) -> List<T>?) {
        list.transformFlat(transformation)
    }

    override fun remove(element: T): Boolean = list.remove(element)
}

private class Wrapper<T>(
    val value: T
) {
    private val addedOn: Int = stageController.currentStage
    private var removedOn: Int = Int.MAX_VALUE

    val alive: Boolean
        get() = stageController.currentStage in addedOn until removedOn

    fun remove() {
        removedOn = stageController.currentStage
    }
}

class DumbPersistentList<T>(
    private val container: IrDeclaration?,

    list: List<T> = emptyList()
) : SimpleList<T> {
    private val innerList: MutableList<Wrapper<T>> =
        list.mapTo(mutableListOf()) { Wrapper(it) }

    private fun ensureLowered() {
        container?.let { stageController.lazyLower(it) }
    }

    override fun add(element: T): Boolean {
        ensureLowered()
        return innerList.add(Wrapper(element))
    }

    override fun addFirst(element: T) {
        ensureLowered()
        innerList.add(0, Wrapper(element))
    }

    override fun addAll(elements: Collection<T>): Boolean {
        ensureLowered()
        return innerList.addAll(elements.map { Wrapper(it) })
    }

    override fun addFirstAll(elements: Collection<T>) {
        ensureLowered()
        innerList.addAll(0, elements.map { Wrapper(it) })
    }

    override fun plusAssign(element: T) {
        ensureLowered()
        add(element)
    }

    override fun plusAssign(elements: Collection<T>) {
        ensureLowered()
        addAll(elements)
    }

    override fun removeAll(predicate: (T) -> Boolean): Boolean {
        ensureLowered()
        var result = false
        innerList.forEach {
            if (it.alive && predicate(it.value)) {
                it.remove()
                result = true
            }
        }
        return result
    }

    override fun removeAll(elements: Collection<T>): Boolean {
        ensureLowered()
        return removeAll { it in elements }
    }

    override fun clear() {
        ensureLowered()
        removeAll { true }
    }

    override fun transform(transformation: (T) -> T) {
        ensureLowered()
        innerList.transformFlat {
            if (it.alive) {
                val newValue = transformation(it.value)
                if (newValue === it.value) null else {
                    it.remove()
                    listOf(it, Wrapper(newValue))
                }
            } else null
        }
    }

    override fun transformFlat(transformation: (T) -> List<T>?) {
        ensureLowered()
        innerList.transformFlat {
            if (!it.alive) null else {
                transformation(it.value)?.let { newElements ->
                    val result = mutableListOf(it)

                    var preserved = false

                    for (e in newElements) {
                        if (it.value === e) {
                            preserved = true
                        } else {
                            result += Wrapper(e)
                        }
                    }

                    if (!preserved) {
                        it.remove()
                    }

                    result
                }
            }
        }
    }

    override fun remove(element: T): Boolean {
        ensureLowered()
        innerList.forEach {
            if (it.alive && it.value == element) {
                it.remove()
                return true
            }
        }

        return false
    }

    override val size: Int
        get(): Int {
            ensureLowered()
            return innerList.count { it.alive }
        }

    override fun contains(element: T): Boolean {
        ensureLowered()
        return innerList.find { it.alive && it.value == element } != null
    }

    override fun containsAll(elements: Collection<T>): Boolean {
        ensureLowered()
        return elements.all { contains(it) }
    }

    private fun skipNAlive(n: Int): Int {
        var result = 0
        var skipped = 0
        while (skipped < n) {
            while (!innerList[result].alive) ++result
            ++skipped
            ++result
        }

        return result
    }

    override fun get(index: Int): T {
        ensureLowered()
        return innerList[skipNAlive(index + 1) - 1].value
    }

    override fun indexOf(element: T): Int {
        ensureLowered()
        var translatedIndex = -1;
        for (i in 0 until innerList.size) {
            val w = innerList[i]
            if (w.alive) {
                ++translatedIndex
                if (w.value == element) return translatedIndex
            }
        }
        return -1
    }

    override fun isEmpty(): Boolean {
        ensureLowered()
        return size == 0
    }

    override fun iterator(): Iterator<T> {
        ensureLowered()
        return listIterator()
    }

    override fun lastIndexOf(element: T): Int {
        ensureLowered()
        var translatedIndex = -1;
        var result = -1
        for (i in 0 until innerList.size) {
            val w = innerList[i]
            if (w.alive) {
                ++translatedIndex
                if (w.value == element) result = translatedIndex
            }
        }
        return result
    }

    override fun listIterator(index: Int): ListIterator<T> {
        ensureLowered()
        val result = listIterator()
        for (i in 0..index) result.next()
        return result
    }

    override fun listIterator(): ListIterator<T> {
        ensureLowered()
        return object : ListIterator<T> {
            val innerIterator = innerList.listIterator()

            var aliveBefore = 0

            override fun hasNext(): Boolean {
                while (innerIterator.hasNext()) {
                    val n = innerIterator.next()
                    if (n.alive) {
                        innerIterator.previous()
                        return true
                    }
                }
                return false
            }

            override fun hasPrevious(): Boolean {
                return aliveBefore > 0
            }

            override fun next(): T {
                while (innerIterator.hasNext()) {
                    val n = innerIterator.next()
                    if (n.alive) {
                        ++aliveBefore
                        return n.value
                    }
                }
                throw NoSuchElementException()
            }

            override fun nextIndex(): Int {
                return aliveBefore
            }

            override fun previous(): T {
                while (innerIterator.hasPrevious()) {
                    val p = innerIterator.previous()
                    if (p.alive) {
                        --aliveBefore
                        return p.value
                    }
                }
                throw NoSuchElementException()
            }

            override fun previousIndex(): Int {
                return aliveBefore - 1
            }
        }
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<T> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

fun <T : Any> IrDeclaration.PersistentVar(initValue: T) =
    PersistentVar(this, initValue)

fun <T> IrDeclaration.NullablePersistentVar() =
    NullablePersistentVar<T>(this)

fun <T : Any> IrDeclaration.LateInitPersistentVar() =
    LateInitPersistentVar<T>(this)

fun <T : Any> IrDeclaration.ParentPersistentVar() =
    ParentPersistentVar<T>(this)

fun <T> IrDeclaration.DumbPersistentList() =
    DumbPersistentList<T>(this, kotlin.collections.emptyList())