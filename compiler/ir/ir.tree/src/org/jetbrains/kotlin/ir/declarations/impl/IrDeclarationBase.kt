/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.ir.declarations.impl

import org.jetbrains.kotlin.ir.IrElementBase
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.carriers.BodyCarrier
import org.jetbrains.kotlin.ir.declarations.impl.carriers.Carrier
import org.jetbrains.kotlin.ir.declarations.impl.carriers.DeclarationCarrier
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.utils.SmartList

abstract class IrDeclarationBase<T : DeclarationCarrier<T>>(
    startOffset: Int,
    endOffset: Int,
    origin: IrDeclarationOrigin
) : IrPersistingElementBase<T>(startOffset, endOffset),
    IrDeclaration,
    DeclarationCarrier<T> {

    override var parentField: IrDeclarationParent? = null

    override var parent: IrDeclarationParent
        get() = getCarrier().parentField!!
        set(p) {
            if (getCarrier().parentField !== p) {
                setCarrier().parentField = p
            }
        }

    override var originField: IrDeclarationOrigin = origin

    override var origin: IrDeclarationOrigin
        get() = getCarrier().originField
        set(p) {
            if (getCarrier().originField !== p) {
                setCarrier().originField = p
            }
        }

    var removedOn: Int = Int.MAX_VALUE

    init {
        stageController.register(this)
    }

    override val annotations: MutableList<IrConstructorCall> = SmartList()

    override val metadata: MetadataSource?
        get() = null

    override fun ensureLowered() {
        if (stageController.currentStage > loweredUpTo) {
            stageController.lazyLower(this)
        }
    }
}

abstract class IrPersistingElementBase<T : Carrier<T>>(
    startOffset: Int,
    endOffset: Int
) : IrElementBase(startOffset, endOffset),
    Carrier<T> {

    var loweredUpTo = stageController.currentStage

    var mask = 1L shl stageController.currentStage

    val createdOn: Int
        get() = java.lang.Long.bitCount(java.lang.Long.lowestOneBit(mask) - 1)

    private var values: Array<Any?>? = null

    abstract fun ensureLowered()

    protected fun getCarrier(): T {
        stageController.currentStage.let { stage ->
            ensureLowered()

            val m = (1L shl (stage + 1)) - 1L

            if ((mask and m.inv()) == 0L) return this as T

            val index = java.lang.Long.bitCount(mask and m) - 1

            return values!![index] as T
        }
    }

    protected fun setCarrier(): T {
        val stage = stageController.currentStage

        ensureLowered()

        val m = (1L shl stage) - 1L
        val bit = 1L shl stage

        if ((mask and m.inv()) == bit) return this as T

        val index = java.lang.Long.bitCount(mask and m) - 1

        if (index < 0 || loweredUpTo > stage) {
            error("access before creation")
        }

        if (index == 0 || !this.eq(values!![index - 1] as T)) {
            val newValues = values?.let {
                if (index == it.size) {
                    it.copyOf(values!!.size + 1).also {
                        values = it
                    }
                } else {
                    error("retrospective modification")
                }
            } ?: arrayOfNulls<Any?>(1).also {
                values = it
            }

            newValues[index] = this.clone()
        } else {
            val maskCopy = mask
            mask = maskCopy xor java.lang.Long.highestOneBit(maskCopy and m)
        }
        mask = mask or bit

        return this as T
    }
}

abstract class IrBodyBase<B: IrBodyBase<B>>(
    startOffset: Int,
    endOffset: Int,
    private var initializer: (B.() -> Unit)?
): IrPersistingElementBase<BodyCarrier>(startOffset, endOffset), IrBody, BodyCarrier {
    override var containerField: IrDeclaration? = null

    var container: IrDeclaration
        get() = getCarrier().containerField!!
        set(p) {
            if (getCarrier().containerField !== p) {
                setCarrier().containerField = p
            }
        }

    protected fun <T> checkEnabled(fn: () -> T): T {
        if (!stageController.bodiesEnabled) error("Bodies disabled!")
        initializer?.let { initFn ->
            initializer = null
            // Uninitialized body shouldn't have been lowered yet, hence it was created on stage `loweredUpTo`
            stageController.withStage(loweredUpTo) {
                initFn.invoke(this as B)
            }
        }
        if (loweredUpTo + 1 < stageController.currentStage) {
            stageController.lowerBody(this)
        }
        return fn()
    }

    override fun ensureLowered() {
//        if (!stageController.bodiesEnabled) error("Bodies disabled!")
        // TODO
    }
}