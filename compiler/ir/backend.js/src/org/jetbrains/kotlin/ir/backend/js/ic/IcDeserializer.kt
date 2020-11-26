/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrLinker
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.IdSignature

class IcDeserializer(
    val linker: JsIrLinker,
    val context: JsIrBackendContext,
) {
    fun injectIcData(module: IrModuleFragment, icData: SerializedIcData) {
        // Prepare per-file indices

        val moduleDeserializer = linker.moduleDeserializer(module.descriptor)

        val fileDeserializers = moduleDeserializer.fileDeserializers()

        val fileToDeserializer = fileDeserializers.associateBy { it.file }

        val fileQueue = ArrayDeque<IrFile>()
        val signatureQueue = ArrayDeque<IdSignature>()


        // Add all signatures withing the module to a queue ( declarations and bodies )
        // TODO add bodies
        fileDeserializers.forEach { fileDeserializer ->
            fileDeserializer.symbolDeserializer.deserializedSymbols.keys.forEach {
                fileQueue.addLast(fileDeserializer.file)
                signatureQueue.addLast(it)
            }
        }

        while (fileQueue.isNotEmpty()) {
            val file = fileQueue.removeFirst()
            val signature = signatureQueue.removeFirst()

            val deserializer = fileToDeserializer[file]



            //    deserialize related mappings
            //    deserialize related carriers
            //    add entities to the queue
        }
    }

}