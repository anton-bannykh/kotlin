/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.*

interface PropertyCarrier : DeclarationCarrier<PropertyCarrier> {
    var backingFieldField: IrField?
    var getterField: IrSimpleFunction?
    var setterField: IrSimpleFunction?
    var metadataField: MetadataSource?

    override fun eq(other: PropertyCarrier): Boolean {
        return parentField === other.parentField &&
                originField == other.originField &&
                backingFieldField === other.backingFieldField &&
                getterField === other.getterField &&
                setterField === other.setterField &&
                metadataField === other.metadataField
    }

    override fun clone(): PropertyCarrier {
        return PropertyCarrierImpl(
            lastModified,
            parentField,
            originField,
            backingFieldField,
            getterField,
            setterField,
            metadataField
        )
    }
}

class PropertyCarrierImpl(
    override val lastModified: Int,
    override var parentField: IrDeclarationParent?,
    override var originField: IrDeclarationOrigin,
    override var backingFieldField: IrField?,
    override var getterField: IrSimpleFunction?,
    override var setterField: IrSimpleFunction?,
    override var metadataField: MetadataSource?
) : PropertyCarrier