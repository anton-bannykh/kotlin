/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent

interface ScriptCarrier : DeclarationCarrier<ScriptCarrier> {

    override fun eq(other: ScriptCarrier): Boolean {
        return parentField === other.parentField &&
                originField === other.originField
    }

    override fun clone(): ScriptCarrier {
        return ScriptCarrierImpl(
            parentField,
            originField
        )
    }
}

class ScriptCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override var originField: IrDeclarationOrigin
) : ScriptCarrier