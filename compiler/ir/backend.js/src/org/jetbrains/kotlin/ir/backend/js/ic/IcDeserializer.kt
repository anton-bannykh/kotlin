/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.backend.common.overrides.DefaultFakeOverrideClassFilter
import org.jetbrains.kotlin.backend.common.serialization.*
import org.jetbrains.kotlin.backend.common.serialization.encodings.BinarySymbolData
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrLinker
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.path
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.library.SerializedIrFile
import org.jetbrains.kotlin.library.impl.IrArrayMemoryReader
import org.jetbrains.kotlin.protobuf.ExtensionRegistryLite
import kotlin.collections.ArrayDeque
import kotlin.collections.HashSet

import org.jetbrains.kotlin.backend.common.serialization.proto.IrFile as ProtoFile
import org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration as ProtoIrDeclaration

class IcDeserializer(
    val linker: JsIrLinker,
    val context: JsIrBackendContext,
) {
    fun injectIcData(module: IrModuleFragment, icData: SerializedIcData) {
        // Prepare per-file indices

        val moduleDeserializer = linker.moduleDeserializer(module.descriptor)

        val fileDeserializers = moduleDeserializer.fileDeserializers()

        val fileQueue = ArrayDeque<IcFileDeserializer>()
        val signatureQueue = ArrayDeque<IdSignature>()

        val publicSignatureToIcFileDeserializer = mutableMapOf<IdSignature, IcFileDeserializer>()

        fun IdSignature.enqueue(icDeserializer: IcFileDeserializer) {
            if (this !in icDeserializer.visited) {
                fileQueue.addLast(icDeserializer)
                signatureQueue.addLast(this)
                icDeserializer.visited += this
            }
        }

        fun IdSignature.enqueue() {
            assert(this.isPublic)
            val icDeserializer = publicSignatureToIcFileDeserializer[this] ?: error("Couldn't find file deserializer for signature $this")
            enqueue(icDeserializer)
        }

        val pathToIcFileData = icData.files.associateBy {
            it.file.path
        }

        // Add all signatures withing the module to a queue ( declarations and bodies )
        // TODO add bodies
        fileDeserializers.forEach { fileDeserializer ->
            val icFileData = pathToIcFileData[fileDeserializer.file.path]!!

            val icDeserializer = IcFileDeserializer(linker, fileDeserializer, icFileData, {
                it.enqueue(this)
            }) { idSig, kind ->
                publicSignatureToIcFileDeserializer[idSig]?.deserializeIrSymbol(idSig, kind)
                    ?: moduleDeserializer.deserializeIrSymbol(idSig, kind)
            }

            fileDeserializer.symbolDeserializer.deserializedSymbols.keys.forEach {
                it.enqueue(icDeserializer)

                if (it.isPublic) {
                    publicSignatureToIcFileDeserializer[it] = icDeserializer
                }
            }

            icDeserializer.reversedSignatureIndex.keys.forEach {
                if (it.isPublic) {
                    publicSignatureToIcFileDeserializer[it] = icDeserializer
                }
            }
        }

        while (signatureQueue.isNotEmpty()) {
            val icFileDeserializer = fileQueue.removeFirst()
            val signature = signatureQueue.removeFirst()

            // Deserialize the declaration
            val declaration = icFileDeserializer.deserializeDeclaration(signature)

            //    deserialize related mappings
            //    deserialize related carriers
        }
    }

    class IcFileDeserializer(
        val linker: JsIrLinker,
        val fileDeserializer: IrFileDeserializer,
        val icFileData: SerializedIcDataForFile,
        val enqueueLocalTopLevelDeclaration: IcFileDeserializer.(IdSignature) -> Unit,
        val deserializePublicSymbol: (IdSignature, BinarySymbolData.SymbolKind) -> IrSymbol,
    ) {

        private val fileReader = FileReaderFromSerializedIrFile(icFileData.file)

        private val symbolDeserializer = IrSymbolDeserializer(
            linker.symbolTable,
            fileReader,
            emptyList(),
            { enqueueLocalTopLevelDeclaration(it) },
            { _, s -> s },
            deserializePublicSymbol
        )

        private val declarationDeserializer = IrDeclarationDeserializer(
            linker.logger,
            linker.builtIns,
            linker.symbolTable,
            linker.symbolTable.irFactory,
            fileReader,
            fileDeserializer.file,
            linker.deserializeFakeOverrides,
            fileDeserializer.declarationDeserializer.allowErrorNodes,
            deserializeInlineFunctions = true,
            deserializeBodies = true,
            symbolDeserializer,
            DefaultFakeOverrideClassFilter,
            { /* Don't care about fake overrides */ },
        )

        private val protoFile: ProtoFile = ProtoFile.parseFrom(icFileData.file.fileData.codedInputStream, ExtensionRegistryLite.newInstance())

        val reversedSignatureIndex: Map<IdSignature, Int> = protoFile.declarationIdList.map { symbolDeserializer.deserializeIdSignature(it) to it }.toMap()

        val visited = HashSet<IdSignature>()

        fun deserializeDeclaration(idSig: IdSignature): IrDeclaration {
            // Check if the declaration was deserialized before
            // TODO is this needed?
            val symbol = symbolDeserializer.deserializedSymbols[idSig]
            if (symbol != null && symbol.isBound) return symbol.owner as IrDeclaration

            // Do deserialize stuff
            val idSigIndex = reversedSignatureIndex[idSig] ?: error("Not found Idx for $idSig")
            val declarationStream = fileReader.irDeclaration(idSigIndex).codedInputStream
            val declarationProto = ProtoIrDeclaration.parseFrom(declarationStream, ExtensionRegistryLite.newInstance())
            return declarationDeserializer.deserializeDeclaration(declarationProto)
        }

        fun deserializeIrSymbol(idSig: IdSignature, symbolKind: BinarySymbolData.SymbolKind): IrSymbol {
            enqueueLocalTopLevelDeclaration(idSig)
            return symbolDeserializer.deserializeIrSymbol(idSig, symbolKind)
        }
    }
}

class FileReaderFromSerializedIrFile(val irFile: SerializedIrFile) : IrLibraryFile() {
    val declarationReader = IrArrayMemoryReader(irFile.declarations)
    val typeReader = IrArrayMemoryReader(irFile.types)
    val signatureReader = IrArrayMemoryReader(irFile.signatures)
    val stringReader = IrArrayMemoryReader(irFile.strings)
    val bodyReader = IrArrayMemoryReader(irFile.bodies)

    override fun irDeclaration(index: Int): ByteArray = declarationReader.tableItemBytes(index)

    override fun type(index: Int): ByteArray = typeReader.tableItemBytes(index)

    override fun signature(index: Int): ByteArray = signatureReader.tableItemBytes(index)

    override fun string(index: Int): ByteArray = stringReader.tableItemBytes(index)

    override fun body(index: Int): ByteArray = bodyReader.tableItemBytes(index)
}