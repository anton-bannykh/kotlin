/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.expressions.IrBlockBody

interface AnonymousInitializerCarrier : DeclarationCarrier<AnonymousInitializerCarrier> {
    var bodyField: IrBlockBody?

    override fun eq(other: AnonymousInitializerCarrier): Boolean {
        return parentField === other.parentField &&
                bodyField === other.bodyField
    }

    override fun clone(): AnonymousInitializerCarrier {
        return AnonymousInitializerCarrierImpl(
            parentField,
            bodyField
        )
    }
}

class AnonymousInitializerCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override var bodyField: IrBlockBody?
) : AnonymousInitializerCarrier