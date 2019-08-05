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

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.stageController
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.symbols.IrEnumEntrySymbol
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.name.Name

class IrEnumEntryImpl(
    startOffset: Int,
    endOffset: Int,
    origin: IrDeclarationOrigin,
    override val symbol: IrEnumEntrySymbol,
    override val name: Name
) : IrDeclarationWithBodyBase<EnumEntryCarrier, IrExpressionBody>(startOffset, endOffset, origin, EnumEntryCarrier(), null),
    IrEnumEntry {

    constructor(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        symbol: IrEnumEntrySymbol
    ) :
            this(startOffset, endOffset, origin, symbol, symbol.descriptor.name)

    init {
        symbol.bind(this)
    }

    override val descriptor: ClassDescriptor get() = symbol.descriptor
    override var correspondingClass: IrClass? //by NullablePersistentVar()
        get() = getCarrier().correspondingClass
        set(v) {
            setCarrier().correspondingClass = v
        }

    override var initializerExpression: IrExpressionBody? //by NullablePersistentVar()
        get() = getBodyImpl()
        set(v) {
            setBodyImpl(v)
        }

    override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R {
        return visitor.visitEnumEntry(this, data)
    }

    override fun <D> acceptChildren(visitor: IrElementVisitor<Unit, D>, data: D) {
        if (stageController.bodiesEnabled) {
            initializerExpression?.accept(visitor, data)
        }
        correspondingClass?.accept(visitor, data)
    }

    override fun <D> transformChildren(transformer: IrElementTransformer<D>, data: D) {
        if (stageController.bodiesEnabled) {
            initializerExpression = initializerExpression?.transform(transformer, data)
        }
        correspondingClass = correspondingClass?.transform(transformer, data) as? IrClass
    }
}

class EnumEntryCarrier: CarrierBase<EnumEntryCarrier>() {

    var correspondingClass: IrClass? = null

    override fun clone(): EnumEntryCarrier {
        return EnumEntryCarrier().also {
            fillCopy(it)
        }
    }

    override fun fillCopy(t: EnumEntryCarrier) {
        super.fillCopy(t)
        t.correspondingClass = correspondingClass
    }
}