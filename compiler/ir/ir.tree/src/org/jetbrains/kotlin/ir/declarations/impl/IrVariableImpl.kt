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

import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.name.Name

// TODO remove persistency
class IrVariableImpl(
    startOffset: Int,
    endOffset: Int,
    origin: IrDeclarationOrigin,
    override val symbol: IrVariableSymbol,
    override val name: Name,
    override val type: IrType,
    override val isVar: Boolean,
    override val isConst: Boolean,
    override val isLateinit: Boolean
) :
    IrDeclarationBase<VariableCarrier>(startOffset, endOffset, origin, VariableCarrier()),
    IrVariable {

    constructor(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        symbol: IrVariableSymbol,
        type: IrType
    ) : this(
        startOffset, endOffset, origin, symbol,
        symbol.descriptor.name, type,
        isVar = symbol.descriptor.isVar,
        isConst = symbol.descriptor.isConst,
        isLateinit = symbol.descriptor.isLateInit
    )

    constructor(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: VariableDescriptor,
        type: IrType
    ) : this(startOffset, endOffset, origin, IrVariableSymbolImpl(descriptor), type)

    constructor(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: VariableDescriptor,
        type: IrType,
        initializer: IrExpression?
    ) : this(startOffset, endOffset, origin, descriptor, type) {
        this.initializer = initializer
    }

    init {
        symbol.bind(this)
    }

    override val descriptor: VariableDescriptor get() = symbol.descriptor

        // TODO Either variable is not persistent, or make this a body
    override var initializer: IrExpression? //by NullablePersistentVar()
        get() = getCarrier().initializer
        set(v) {
            setCarrier().initializer = v
        }

    override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R =
        visitor.visitVariable(this, data)

    override fun <D> acceptChildren(visitor: IrElementVisitor<Unit, D>, data: D) {
        initializer?.accept(visitor, data)
    }

    override fun <D> transformChildren(transformer: IrElementTransformer<D>, data: D) {
        initializer = initializer?.transform(transformer, data)
    }
}

class VariableCarrier : CarrierBase<VariableCarrier>() {

    var initializer: IrExpression? = null

    override fun clone(): VariableCarrier {
        return VariableCarrier().also {
            fillCopy(it)
        }
    }

    override fun fillCopy(t: VariableCarrier) {
        super.fillCopy(t)
        t.initializer = initializer
    }
}