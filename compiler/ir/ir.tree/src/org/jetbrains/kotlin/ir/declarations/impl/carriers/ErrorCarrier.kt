/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent

interface ErrorCarrier : CarrierBase<ErrorCarrier> {

    override fun eq(other: ErrorCarrier): Boolean {
        return parentField === other.parentField
    }

    override fun clone(): ErrorCarrier {
        return ErrorCarrierImpl(parentField)
    }
}

class ErrorCarrierImpl(override var parentField: IrDeclarationParent?) : ErrorCarrier