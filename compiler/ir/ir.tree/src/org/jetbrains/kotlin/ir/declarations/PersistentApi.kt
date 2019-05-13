/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations

import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.transformFlat
import java.util.*
import kotlin.NoSuchElementException
import kotlin.reflect.KProperty

interface StageController {
    val currentStage: Int

    fun lowerUpTo(file: IrFile, stageNonInclusive: Int)

    val hasBody: Boolean
        get() = true
}

object NoopController : StageController {
    override val currentStage: Int = 0

    override fun lowerUpTo(file: IrFile, stageNonInclusive: Int) {}
}

open class PersistentPropertyBase(private val stageControllerCalculator: () -> StageController) {
    val stageController: StageController
        get() = stageControllerCalculator()
}

class PersistentVar<T : Any>(initValue: T, stageControllerCalculator: () -> StageController) :
    PersistentPropertyBase(stageControllerCalculator) {
    val changes = TreeMap(mapOf(0 to initValue))

    operator fun getValue(thisRef: Any, property: KProperty<*>): T {
        return changes.lowerEntry(stageController.currentStage + 1)!!.value
    }

    operator fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
        changes[stageController.currentStage] = value
    }
}

class NullablePersistentVar<T>(stageControllerCalculator: () -> StageController) : PersistentPropertyBase(stageControllerCalculator) {
    private val changes = TreeMap<Int, T?>()

    operator fun getValue(thisRef: Any, property: KProperty<*>): T? {
        return changes.lowerEntry(stageController.currentStage + 1)?.value
    }

    operator fun setValue(thisRef: Any, property: KProperty<*>, value: T?) {
        changes[stageController.currentStage] = value
    }
}

class LateInitPersistentVar<T : Any>(stageControllerCalculator: () -> StageController) : PersistentPropertyBase(stageControllerCalculator) {
    val changes = TreeMap<Int, T>()

    operator fun getValue(thisRef: Any, property: KProperty<*>): T {
        return changes.lowerEntry(stageController.currentStage + 1)!!.value
    }

    operator fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
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

class DumbPersistentList<T>(
    list: List<T> = emptyList(),
    stageControllerCalculator: () -> StageController
) : PersistentPropertyBase(stageControllerCalculator), SimpleList<T> {

    private inner class Wrapper<T>(
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

    private val innerList: MutableList<Wrapper<T>> =
        list.mapTo(mutableListOf()) { Wrapper(it) }

    override fun add(element: T): Boolean = innerList.add(Wrapper(element))

    override fun addFirst(element: T) {
        innerList.add(0, Wrapper(element))
    }

    override fun addAll(elements: Collection<T>): Boolean = innerList.addAll(elements.map { Wrapper(it) })

    override fun addFirstAll(elements: Collection<T>) {
        innerList.addAll(0, elements.map { Wrapper(it) })
    }

    override fun plusAssign(element: T) {
        add(element)
    }

    override fun plusAssign(elements: Collection<T>) {
        addAll(elements)
    }

    override fun removeAll(predicate: (T) -> Boolean): Boolean {
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
        return removeAll { it in elements }
    }

    override fun clear() {
        removeAll { true }
    }

    override fun transform(transformation: (T) -> T) {
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
        innerList.forEach {
            if (it.alive && it.value == element) {
                it.remove()
                return true
            }
        }

        return false
    }

    override val size: Int
        get() = innerList.count { it.alive }

    override fun contains(element: T): Boolean {
        return innerList.find { it.alive && it.value == element } != null
    }

    override fun containsAll(elements: Collection<T>): Boolean {
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
        return innerList[skipNAlive(index + 1) - 1].value
    }

    override fun indexOf(element: T): Int {
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
        return size == 0
    }

    override fun iterator(): Iterator<T> {
        return listIterator()
    }

    override fun lastIndexOf(element: T): Int {
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
        val result = listIterator()
        for (i in 0..index) result.next()
        return result
    }

    override fun listIterator(): ListIterator<T> {
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


fun IrDeclaration.calculateStageController() = (this.file as? IrFileImpl)?.stageController ?: NoopController

fun <T : Any> IrDeclaration.PersistentVar(initValue: T) =
    PersistentVar(initValue, this::calculateStageController)

fun <T> IrDeclaration.NullablePersistentVar() =
    NullablePersistentVar<T>(this::calculateStageController)

fun <T : Any> IrDeclaration.LateInitPersistentVar() =
    LateInitPersistentVar<T>(this::calculateStageController)

fun <T> IrDeclaration.DumbPersistentList() =
    DumbPersistentList<T>(emptyList(), this::calculateStageController)
