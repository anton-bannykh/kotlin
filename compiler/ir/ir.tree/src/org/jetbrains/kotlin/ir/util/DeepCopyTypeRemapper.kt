/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.util

import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.IrTypeProjectionImpl
import org.jetbrains.kotlin.ir.types.impl.originalKotlinType

class DeepCopyTypeRemapper(
    private val symbolRemapper: SymbolRemapper
) : TypeRemapper {

    lateinit var deepCopy: DeepCopyIrTreeWithSymbols

    override fun enterScope(irTypeParametersContainer: IrTypeParametersContainer) {
        // TODO
    }

    override fun leaveScope() {
        // TODO
    }

    // TODODO
    override fun remapType(type: IrType): IrType
            = if (type !is IrSimpleType) type else {
        IrSimpleTypeImpl(type.originalKotlinType, symbolRemapper.getReferencedClassifier(type.classifier), type.hasQuestionMark, type.arguments.map {
            if (it !is IrTypeProjection) it else {
                IrTypeProjectionImpl(this.remapType(it.type), it.variance)
            }
        }, type.annotations.map { it.transform(deepCopy, null) as IrCall })
    }

}