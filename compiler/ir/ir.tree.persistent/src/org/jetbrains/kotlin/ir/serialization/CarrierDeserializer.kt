/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.serialization

import org.jetbrains.kotlin.backend.common.serialization.IrDeclarationDeserializer
import org.jetbrains.kotlin.backend.common.serialization.proto.PirAnonymousInitializerCarrier
import org.jetbrains.kotlin.backend.common.serialization.proto.PirClassCarrier
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSymbolOwner
import org.jetbrains.kotlin.ir.declarations.persistent.PersistentIrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.persistent.PersistentIrClass
import org.jetbrains.kotlin.ir.declarations.persistent.PersistentIrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.persistent.carriers.DeclarationCarrier
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.protobuf.CodedInputStream
import org.jetbrains.kotlin.protobuf.ExtensionRegistryLite

class CarrierDeserializer(declarationDeserializer: IrDeclarationDeserializer) {
    private val carrierDeserializerImpl = IrCarrierDeserializerImpl(declarationDeserializer)

    private fun load(symbol: IrSymbol): CodedInputStream = TODO()

    private fun PersistentIrDeclarationBase<*>.load(fn: (CodedInputStream) -> DeclarationCarrier): Unit = TODO()

    fun injectCarriers(declaration: IrDeclaration) {
        // TODO bodies
        // TODO inject the carriers into the declarationImpl

        if (declaration is PersistentIrDeclarationBase<*>) {
            val symbol = (declaration as IrSymbolOwner).symbol

            when (declaration) {
                is PersistentIrAnonymousInitializer -> declaration.load { carrierDeserializerImpl.deserializeAnonymousInitializerCarrier(PirAnonymousInitializerCarrier.parseFrom(it, ExtensionRegistryLite.newInstance())) }

                is PersistentIrClass -> PirClassCarrier.parseFrom(load(symbol), ExtensionRegistryLite.newInstance())
                else -> TODO()
            }


        }
    }
}