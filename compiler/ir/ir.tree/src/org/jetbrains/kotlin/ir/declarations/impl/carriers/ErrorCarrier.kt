/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent

interface ErrorCarrier : DeclarationCarrier<ErrorCarrier> {

    override fun eq(other: ErrorCarrier): Boolean {
        return parentField === other.parentField &&
                originField === other.originField
    }

    override fun clone(): ErrorCarrier {
        return ErrorCarrierImpl(lastModified, parentField, originField)
    }
}

class ErrorCarrierImpl(
    override val lastModified: Int,
    override var parentField: IrDeclarationParent?,
    override var originField: IrDeclarationOrigin
) : ErrorCarrier