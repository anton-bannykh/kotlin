/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.MetadataSource

interface ClassCarrier : CarrierBase<ClassCarrier> {
    var thisReceiverField: IrValueParameter?
    var metadataField: MetadataSource?

    override fun eq(other: ClassCarrier): Boolean {
        return parentField === other.parentField &&
                thisReceiverField === other.thisReceiverField &&
                metadataField === other.metadataField
    }

    override fun clone(): ClassCarrier {
        return ClassCarrierImpl(
            parentField,
            thisReceiverField,
            metadataField
        )
    }
}

class ClassCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override var thisReceiverField: IrValueParameter?,
    override var metadataField: MetadataSource?
) : ClassCarrier
