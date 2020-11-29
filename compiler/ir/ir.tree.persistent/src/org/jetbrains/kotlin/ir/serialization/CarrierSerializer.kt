/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.serialization

import org.jetbrains.kotlin.backend.common.serialization.IrFileSerializer
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.persistent.PersistentIrElementBase
import org.jetbrains.kotlin.ir.declarations.persistent.carriers.*
import org.jetbrains.kotlin.ir.declarations.persistent.carriers.AnonymousInitializerCarrier
import org.jetbrains.kotlin.ir.declarations.persistent.carriers.ClassCarrier
import org.jetbrains.kotlin.ir.declarations.persistent.carriers.ConstructorCarrier
import org.jetbrains.kotlin.ir.declarations.persistent.carriers.EnumEntryCarrier
import org.jetbrains.kotlin.ir.declarations.persistent.carriers.ErrorDeclarationCarrier

class CarrierSerializer(val fileSerializer: IrFileSerializer) {
    private val serializerImpl = IrCarrierSerializerImpl(fileSerializer)

    fun <T : PersistentIrElementBase<*>> serializeCarriers(elements: Iterable<T>): SerializedCarriers {
        TODO()
    }

    private fun serializeCarriers(element: IrDeclaration) {
        // TODO save bytes
        if (element is PersistentIrElementBase<*>) {
            with (serializerImpl) {
                element.values?.forEach {
                    when (it) {
                        is AnonymousInitializerCarrier -> serializeAnonymousInitializerCarrier(it)
                        is ClassCarrier -> serializeClassCarrier(it)
                        is ConstructorCarrier -> serializeConstructorCarrier(it)
                        is EnumEntryCarrier -> serializeEnumEntryCarrier(it)
                        is ErrorDeclarationCarrier -> serializeErrorDeclarationCarrier(it)
                        is FieldCarrier -> serializeFieldCarrier(it)
                        is FunctionCarrier -> serializeFunctionCarrier(it)
                        is LocalDelegatedPropertyCarrier -> serializeLocalDelegatedPropertyCarrier(it)
                        is PropertyCarrier -> serializePropertyCarrier(it)
                        is TypeAliasCarrier -> serializeTypeAliasCarrier(it)
                        is TypeParameterCarrier -> serializeTypeParameterCarrier(it)
                        is ValueParameterCarrier -> serializeValueParameterCarrier(it)
                        else -> error("unknown Carrier") // TODO bodies
                    }
                }
            }
        }
    }
}

class SerializedCarriers()