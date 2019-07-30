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

import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.impl.IrUninitializedType
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.name.Name

abstract class IrFunctionBase<T : FunctionBaseCarrier<out T>>(
    startOffset: Int,
    endOffset: Int,
    origin: IrDeclarationOrigin,
    override val name: Name,
    override val visibility: Visibility,
    override val isInline: Boolean,
    override val isExternal: Boolean,
    initValue: T
) :
    IrDeclarationWithBodyBase<T, IrBody>(startOffset, endOffset, origin, initValue, null),
    IrFunction {

    private var returnTypeField: IrType
        // by PersistentVar(returnType)
        get() = getCarrier().returnTypeField
        set(v) {
            setCarrier().returnTypeField = v
        }

    final override var returnType: IrType
        get() = returnTypeField.let {
            if (it !== IrUninitializedType) it else error("Return type is not initialized")
        }
        set(c) {
            returnTypeField = c
        }

    override val typeParameters: SimpleList<IrTypeParameter> =
        DumbPersistentList()

    override var dispatchReceiverParameter: IrValueParameter? //by NullablePersistentVar()
        get() = getCarrier().dispatchReceiverParameter
        set(v) {
            setCarrier().dispatchReceiverParameter = v
        }

    override var extensionReceiverParameter: IrValueParameter? //by NullablePersistentVar()
        get() = getCarrier().extensionReceiverParameter
        set(v) {
            setCarrier().extensionReceiverParameter = v
        }

    override val valueParameters: SimpleList<IrValueParameter> =
        DumbPersistentList()

    final override var body: IrBody? //by NullablePersistentVar()
        get() = getBodyImpl()
        set(v) {
            setBodyImpl(v)
        }

    override var metadata: MetadataSource? //by NullablePersistentVar()
        get() = getCarrier().metadata
        set(v) {
            setCarrier().metadata = v
        }

    override fun <D> acceptChildren(visitor: IrElementVisitor<Unit, D>, data: D) {
        typeParameters.forEach { it.accept(visitor, data) }

        dispatchReceiverParameter?.accept(visitor, data)
        extensionReceiverParameter?.accept(visitor, data)
        valueParameters.forEach { it.accept(visitor, data) }

        body?.accept(visitor, data)
    }

    override fun <D> transformChildren(transformer: IrElementTransformer<D>, data: D) {
        typeParameters.transform { it.transform(transformer, data) }

        dispatchReceiverParameter = dispatchReceiverParameter?.transform(transformer, data)
        extensionReceiverParameter = extensionReceiverParameter?.transform(transformer, data)
        valueParameters.transform { it.transform(transformer, data) }

        body = body?.transform(transformer, data)
    }
}

abstract class FunctionBaseCarrier<T : FunctionBaseCarrier<T>>(
    var returnTypeField: IrType
) : CarrierBase<T>() {

    var dispatchReceiverParameter: IrValueParameter? = null

    var extensionReceiverParameter: IrValueParameter? = null

    var metadata: MetadataSource? = null

    override fun fillCopy(t: T) {
        super.fillCopy(t)
        t.returnTypeField = returnTypeField
        t.dispatchReceiverParameter = dispatchReceiverParameter
        t.extensionReceiverParameter = extensionReceiverParameter
        t.metadata = metadata
    }
}
