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
import org.jetbrains.kotlin.ir.declarations.impl.carriers.CarrierBase
import org.jetbrains.kotlin.ir.expressions.IrCall

abstract class IrDeclarationBase<T : CarrierBase<T>>(
    startOffset: Int,
    endOffset: Int,
    override val origin: IrDeclarationOrigin
) : IrElementBase(startOffset, endOffset),
    IrDeclaration,
    HasUserdata,
    CarrierBase<T> {

    override var parentField: IrDeclarationParent? = null

    override var parent: IrDeclarationParent
        get() = getCarrier().parentField!!
        set(p) {
            if (getCarrier().parentField !== p) {
                setCarrier().parentField = p
            }
        }

    override val annotations: SimpleList<IrCall> = DumbPersistentList()

    var loweredUpTo = stageController.currentStage

    override val userdata: MutableMap<MappingKey<*, *>, *> = mutableMapOf()

    override val metadata: MetadataSource?
        get() = null

    var mask = 1L shl stageController.currentStage

    private var values: Array<Any?>? = null

//    init {
//        stageController.register(this)
//    }

    protected fun getCarrier(): T {
        stageController.currentStage.let { stage ->
            if (stage > loweredUpTo) {
                stageController.lazyLower(this)
            }

            val m = (1L shl (stage + 1)) - 1L

            if ((mask and m.inv()) == 0L) return this as T

            val index = java.lang.Long.bitCount(mask and m) - 1

            return values!![index] as T
        }
    }

    protected fun setCarrier(): T {
        stageController.currentStage.let { stage ->
            if (stage > loweredUpTo) {
                stageController.lazyLower(this)
            }

            val bit = 1L shl stage

            if ((mask and bit) != 0L) return this as T

            val index = java.lang.Long.bitCount(mask and (bit - 1L)) - 1

            val newValues = values?.let {
                if (index == it.size) {
                    it.copyOf(values!!.size + 1).also {
                        values = it
                    }
                } else it
            } ?: arrayOfNulls<Any?>(1).also {
                values = it
            }

            if (index == 0 || !this.eq(newValues[index - 1] as T)) {
                newValues[index] = this.clone()
            } else {
                mask = mask xor java.lang.Long.highestOneBit(mask and (bit - 1L))
            }
            mask = mask or bit

            return this as T
        }
    }
}

interface HasUserdata {
    val userdata: MutableMap<MappingKey<*, *>, *>
}

interface MappingKey<K : IrDeclaration, V>