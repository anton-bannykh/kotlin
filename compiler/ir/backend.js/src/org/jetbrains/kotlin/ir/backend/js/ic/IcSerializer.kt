/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.serialization.DeclarationTable
import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureSerializer
import org.jetbrains.kotlin.ir.backend.js.JsMapping
import org.jetbrains.kotlin.ir.backend.js.SerializedMappings
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsGlobalDeclarationTable
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrFileSerializer
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsManglerIr
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.persistent.PersistentIrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.persistent.PersistentIrFactory
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.serialization.CarrierSerializer
import org.jetbrains.kotlin.ir.serialization.SerializedCarriers
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.library.SerializedIrFile

class IcSerializer(
    val logger: LoggingContext,
    irBuiltIns: IrBuiltIns,
    val mappings: JsMapping,
    val irFactory: PersistentIrFactory,
) {

    private val signaturer = IdSignatureSerializer(JsManglerIr)
    private val globalDeclarationTable = JsGlobalDeclarationTable(signaturer, irBuiltIns)

    fun serializeDeclarations(declarations: Iterable<IrDeclaration>): SerializedIcData {

        // TODO serialize body carriers and new bodies as well

        val icData = declarations.groupBy {
            // TODO don't move declarations or effects outside the original file
            // TODO Or invent a different mechanism for that
            it.file
        }.entries.map { (file, declarations) ->

            val fileSerializer = JsIrFileSerializer(logger, IcDeclarationTable(globalDeclarationTable), mutableMapOf(), skipExpects = true, icMode = true)

            // Only save newly created declarations
            val newDeclarations = declarations.filter { d ->
                d is PersistentIrDeclarationBase<*> && d.createdOn > 0
            }

            val serializedIrFile = fileSerializer.serializeDeclarationsForIC(file, newDeclarations)

            val serializedCarriers = CarrierSerializer(fileSerializer).serializeCarriers(declarations)

            val serializedMappings = mappings.serializeMappings(declarations)

            SerializedIcDataForFile(
                serializedIrFile,
                serializedCarriers,
                serializedMappings
            )
        }

        return SerializedIcData(icData)
    }

    // Returns precomputed signatures for the newly created declarations. Delegates to the default table otherwise.
    inner class IcDeclarationTable(globalDeclarationTable: JsGlobalDeclarationTable) : DeclarationTable(globalDeclarationTable) {
        override fun isExportedDeclaration(declaration: IrDeclaration): Boolean {
            if (declaration is PersistentIrDeclarationBase<*>) return true
            return super.isExportedDeclaration(declaration)
        }

        override fun tryComputeBackendSpecificSignature(declaration: IrDeclaration): IdSignature? {
            return super.tryComputeBackendSpecificSignature(declaration)
        }

        override fun signatureByDeclaration(declaration: IrDeclaration): IdSignature {
            return irFactory.declarationSignature(declaration) ?: super.signatureByDeclaration(declaration)
        }
    }
}

class SerializedIcDataForFile(
    val file: SerializedIrFile,
    val carriers: SerializedCarriers,
    val mappings: SerializedMappings,
)

class SerializedIcData(
    val files: Collection<SerializedIcDataForFile>,
)