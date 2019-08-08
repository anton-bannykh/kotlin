/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody

interface ErrorCarrier : CarrierBase<ErrorCarrier> {

    override fun eq(other: ErrorCarrier): Boolean {
        return parentField === other.parentField && annotationsField.eq(other.annotationsField)
    }

    override fun clone(): ErrorCarrier {
        return ErrorCarrierImpl(parentField, annotationsField.clone())
    }
}

class ErrorCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override val annotationsField: MutableList<IrExpressionBody>
) : ErrorCarrier