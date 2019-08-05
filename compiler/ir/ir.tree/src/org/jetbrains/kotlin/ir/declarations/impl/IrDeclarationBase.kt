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
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody

abstract class IrDeclarationBase<T : CarrierBase<out T>>(
    startOffset: Int,
    endOffset: Int,
    override val origin: IrDeclarationOrigin,
    initValue: T
) : IrElementBase(startOffset, endOffset),
    IrDeclaration, HasUserdata {

    override var parent: IrDeclarationParent
        get() = getCarrier().parent!!
        set(p) {
            setCarrier().parent = p
        }

    override val annotations: SimpleList<IrExpressionBody> = DumbPersistentList()

    var loweredUpTo = stageController.currentStage

    val createdOn = loweredUpTo

    override val userdata: MutableMap<MappingKey<*, *>, *> = mutableMapOf()

    override val metadata: MetadataSource?
        get() = null

    val values = Array<Any?>(60) { null }.also {
        it[stageController.currentStage] = initValue // TODO 0 -> currentStage?
    }

    protected fun getCarrier(): T {
        stageController.currentStage.let { stage ->
            values[stage]?.let {
                return it as T
            }
            if (stage < createdOn) {
                error("Cannot access declaration before is was created ($stage < $createdOn)")
            }
            if (stage - 1 > loweredUpTo) {
                stageController.lazyLower(this)
            }

            var i = stage - 1
            while (values[i] == null) --i
            val r = values[i]
            while (++i != stage) values[i] = r

            return r as T
        }
    }

    protected fun setCarrier(): T {
        stageController.currentStage.let { stage ->
            values[stage]?.let {
                return it as T
            }
            if (stage < createdOn) {
                error("Cannot access declaration before is was created ($stage < $createdOn)")
            }

            if (values[stage + 1] != null) {
                error("Cannot modify old states ($stage < $loweredUpTo)")
            }

            if (stage - 1 > loweredUpTo) {
                stageController.lazyLower(this)
            }
            var i = stage - 1
            while (values[i] == null) --i
            var r = values[i]
            while (++i != stage) values[i] = r
            r = (r as T).clone()
            values[stage] = r

            return r
        }
    }
}

interface HasUserdata {
    val userdata: MutableMap<MappingKey<*, *>, *>
}

interface MappingKey<K : IrDeclaration, V>

abstract class CarrierBase<T : CarrierBase<T>> {
    var parent: IrDeclarationParent? = null

    abstract fun clone(): T

    open fun fillCopy(t: T) {
        t.parent = parent
    }
}

// TODO investigate what's wrong with this fun (NPE)
private fun <T> Array<Any?>.lowerEntry(index: Int): T {
    if (this[index] === null) {
        this[index] = lowerEntry(index - 1)
    }
    return this[index] as T
}
