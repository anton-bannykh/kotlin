/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.persistentIrGenerator

internal fun PersistentIrGenerator.generateProperty() {
    val backingFieldField = Field(
        "backingField",
        IrField + "?",
        fieldProto,
        fieldType = IrFieldSymbol + "?",
        fieldToPropValueConversion = +"?.owner",
        propToFieldValueConversion = +"?.symbol"
    )
    val getterField = Field(
        "getter",
        IrSimpleFunction + "?",
        simpleFunctionProto,
        fieldType = IrSimpleFunctionSymbol + "?",
        fieldToPropValueConversion = +"?.owner",
        propToFieldValueConversion = +"?.symbol"
    )
    val setterField = Field(
        "setter",
        IrSimpleFunction + "?",
        simpleFunctionProto,
        fieldType = IrSimpleFunctionSymbol + "?",
        fieldToPropValueConversion = +"?.owner",
        propToFieldValueConversion = +"?.symbol"
    )

    writeFile("PersistentIrPropertyCommon.kt", renderFile("org.jetbrains.kotlin.ir.declarations.persistent") {
        lines(
            id,
            +"internal abstract class PersistentIrPropertyCommon(",
            arrayOf(
                startOffset,
                endOffset,
                origin,
                name,
                +"override var " + visibility, // TODO non-persisted state
                +"override val isVar: Boolean",
                +"override val isConst: Boolean",
                +"override val isLateinit: Boolean",
                +"override val isDelegated: Boolean",
                isExternal,
                isExpect,
                containerSource,
                irFactory,
            ).join(separator = ",\n").indent(),
            +") : " + baseClasses("Property") + " " + blockSpaced(
                commonFields,
                backingFieldField.toPersistentField(+"null"),
                propertyAccessor("getter"),
                propertyAccessor("setter"),
                +"override var metadata: " + MetadataSource + "? = null",
                +"override var attributeOwnerId: " + IrAttributeContainer + " = this",
                setState(
                    "Property",
                    backingFieldField,
                    getterField,
                    setterField,
                )
            ),
            id,
        )()
    })

    writeFile("carriers/PropertyCarrier.kt", renderFile("org.jetbrains.kotlin.ir.declarations.persistent.carriers") {
        carriers(
            "Property",
            backingFieldField,
            getterField,
            setterField,
        )()
    })

    addCarrierProtoMessage(
        "Property",
        backingFieldField,
        getterField,
        setterField,
    )
}

internal fun PersistentIrGenerator.propertyAccessor(name: String): E {
    return lines(
        +"private var _${name}Field: " + IrSimpleFunction + "? = null",
        id,
        +"override var ${name}Field: " + IrSimpleFunctionSymbol + "?",
        lines(
            +"get() = _${name}Field?.symbol",
            +"set(s) " + block(
                +"_${name}Field = s?.owner"
            )
        ).indent(),
        id,
        +"override var ${name}: IrSimpleFunction?",
        lines(
            +"get() = getCarrier().let " + block(
                +"if (it === this) _${name}Field else ${name}Field?.owner"
            ),
            +"set(v) " + block(
                +"val gc = getCarrier()",
                +"if (gc !== this || _${name}Field == v) return",
                id,
                +"val sc = setCarrier()",
                +"if (sc !== this) error(\"Modification may happen only to the current instance\")",
                id,
                +"_${name}Field = v"
            )
        ).indent()
    )
}