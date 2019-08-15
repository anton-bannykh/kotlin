/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclaration

interface BodyCarrier : Carrier<BodyCarrier> {

    var containerField: IrDeclaration?

    override fun eq(other: BodyCarrier): Boolean {
        return containerField === other.containerField
    }

    override fun clone(): BodyCarrier {
        return BodyCarrierImpl(containerField)
    }
}

class BodyCarrierImpl(override var containerField: IrDeclaration?) : BodyCarrier