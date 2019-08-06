/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.MetadataSource

interface PropertyCarrier : CarrierBase<PropertyCarrier> {
    var backingFieldField: IrField?
    var getterField: IrSimpleFunction?
    var setterField: IrSimpleFunction?
    var metadataField: MetadataSource?

    override fun eq(other: PropertyCarrier): Boolean {
        return parentField === other.parentField &&
                backingFieldField === other.backingFieldField &&
                getterField === other.getterField &&
                setterField === other.setterField &&
                metadataField === other.metadataField
    }

    override fun clone(): PropertyCarrier {
        return PropertyCarrierImpl(
            parentField,
            backingFieldField,
            getterField,
            setterField,
            metadataField
        )
    }
}

class PropertyCarrierImpl(
    override var parentField: IrDeclarationParent?,
    override var backingFieldField: IrField?,
    override var getterField: IrSimpleFunction?,
    override var setterField: IrSimpleFunction?,
    override var metadataField: MetadataSource?
) : PropertyCarrier