/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.ir.backend.js.JsStatementOrigins
import org.jetbrains.kotlin.backend.common.overrides.DefaultFakeOverrideClassFilter
import org.jetbrains.kotlin.backend.common.serialization.*
import org.jetbrains.kotlin.backend.common.serialization.encodings.BinarySymbolData
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.backend.common.serialization.proto.IrFile as ProtoIrFile
import org.jetbrains.kotlin.ir.backend.js.JsMappingState
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrLinker
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.path
import org.jetbrains.kotlin.ir.expressions.IrStatementOriginImpl
import org.jetbrains.kotlin.ir.serialization.CarrierDeserializer
import org.jetbrains.kotlin.ir.symbols.IrFileSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.library.SerializedIrFile
import org.jetbrains.kotlin.library.impl.DeclarationId
import org.jetbrains.kotlin.library.impl.DeclarationIrTableMemoryReader
import org.jetbrains.kotlin.library.impl.IrArrayMemoryReader
import org.jetbrains.kotlin.library.impl.IrLongArrayMemoryReader
import org.jetbrains.kotlin.protobuf.ExtensionRegistryLite

class IcFileDeserializer(
    val linker: JsIrLinker,
    file: IrFile,
    originalFileReader: IrLibraryFile,
    fileProto: org.jetbrains.kotlin.backend.common.serialization.proto.IrFile,
    deserializeBodies: Boolean,
    allowErrorNodes: Boolean,
    deserializeInlineFunctions: Boolean,
    val moduleDeserializer: IrModuleDeserializer,
    useGlobalSignatures: Boolean,
    handleNoModuleDeserializerFound: (IdSignature, ModuleDescriptor, Collection<IrModuleDeserializer>) -> IrModuleDeserializer,
    val originalEnqueue: IdSignature.(IcFileDeserializer) -> Unit,
    val icFileData: SerializedIcDataForFile,
    val mappingState: JsMappingState,
    val publicSignatureToIcFileDeserializer: MutableMap<IdSignature, IcFileDeserializer>,
    val enqueue: IdSignature.(IcFileDeserializer) -> Unit,
) {

    val originalSymbolDeserializer =
        IrSymbolDeserializer(
            linker.symbolTable,
            originalFileReader,
            file.symbol,
            fileProto.actualList,
            { idSig -> idSig.originalEnqueue(this) },
            linker::handleExpectActualMapping,
            useGlobalSignatures = useGlobalSignatures,
            enqueueAllDeclarations = true
        ) { idSig, symbolKind ->
            assert(idSig.isPublic)

            val topLevelSig = idSig.topLevelSignature()
            val actualModuleDeserializer =
                moduleDeserializer.findModuleDeserializerForTopLevelId(topLevelSig) ?: handleNoModuleDeserializerFound(
                    idSig,
                    moduleDeserializer.moduleDescriptor,
                    moduleDeserializer.moduleDependencies
                )

            actualModuleDeserializer.deserializeIrSymbol(idSig, symbolKind)
        }

    private val originalDeclarationDeserializer = IrDeclarationDeserializer(
        linker.builtIns,
        linker.symbolTable,
        linker.symbolTable.irFactory,
        originalFileReader,
        file,
        allowErrorNodes,
        deserializeInlineFunctions,
        deserializeBodies,
        originalSymbolDeserializer,
        linker.fakeOverrideBuilder.platformSpecificClassFilter,
        linker.fakeOverrideBuilder,
    )

    val originalFileDeserializer = IrFileDeserializer(file, originalFileReader, fileProto, originalSymbolDeserializer, originalDeclarationDeserializer)

    val originalVisited = HashSet<IdSignature>()

    // Explicitly exported declarations (e.g. top-level initializers) must be deserialized before all other declarations.
    // Thus we schedule their deserialization in deserializer's constructor.
    val explicitlyExportedToCompiler: Collection<IdSignature> = fileProto.explicitlyExportedToCompilerList.map {
        val symbolData = originalSymbolDeserializer.parseSymbolData(it)
        originalSymbolDeserializer.deserializeIdSignature(symbolData.signatureId)
    }

    fun allOriginalDeclarationSignatures(): Collection<IdSignature> = originalFileDeserializer.reversedSignatureIndex.keys

    // IC data processing starts here

    private val icFileReader = FileReaderFromSerializedIrFile(icFileData.file)

    val symbolDeserializer = IrSymbolDeserializer(
        linker.symbolTable,
        icFileReader,
        file.symbol,
        emptyList(),
        { idSig -> enqueueLocalTopLevelDeclaration(idSig) },
        { _, s -> s },
        enqueueAllDeclarations = true,
        useGlobalSignatures = true,
        deserializedSymbols = originalFileDeserializer.symbolDeserializer.deserializedSymbols,
        ::deserializePublicSymbol
    )

    private val declarationDeserializer = IrDeclarationDeserializer(
        linker.builtIns,
        linker.symbolTable,
        linker.symbolTable.irFactory,
        icFileReader,
        file,
        allowErrorNodes = true,
        deserializeInlineFunctions = true,
        deserializeBodies = true,
        symbolDeserializer,
        DefaultFakeOverrideClassFilter,
        linker.fakeOverrideBuilder,
        skipMutableState = true,
        additionalStatementOriginIndex = additionalStatementOriginIndex,
        allowErrorStatementOrigins = true,
    )

    private val protoFile: ProtoIrFile = ProtoIrFile.parseFrom(icFileData.file.fileData.codedInputStream, ExtensionRegistryLite.newInstance())

    private val carrierDeserializer = CarrierDeserializer(declarationDeserializer, icFileData.carriers)

    val reversedSignatureIndex: Map<IdSignature, Int> = protoFile.declarationIdList.map { symbolDeserializer.deserializeIdSignature(it) to it }.toMap()

    val visited = HashSet<IdSignature>()

    val mappingsDeserializer = mappingState.mappingsDeserializer(icFileData.mappings, { code ->
        val symbolData = symbolDeserializer.parseSymbolData(code)
        symbolDeserializer.deserializeIdSignature(symbolData.signatureId)
    }) {
        deserializeIrSymbol(it)
    }

    val topLevelSignatures = IrLongArrayMemoryReader(icFileData.order.topLevelSignatures).array.map {
        val symbolData = symbolDeserializer.parseSymbolData(it)
        symbolDeserializer.deserializeIdSignature(symbolData.signatureId)
    }

    init {
        originalFileDeserializer.reversedSignatureIndex.keys.forEach {
            publicSignatureToIcFileDeserializer[it] = this
        }

        reversedSignatureIndex.keys.forEach {
            publicSignatureToIcFileDeserializer[it] = this
        }
    }

    private val containerSigToOrder = mutableMapOf<IdSignature, ByteArray>().also { map ->
        val containerIds = IrLongArrayMemoryReader(icFileData.order.containerSignatures).array
        val declarationIds = IrArrayMemoryReader(icFileData.order.declarationSignatures)

        containerIds.forEachIndexed { index, id ->
            val symbolData = symbolDeserializer.parseSymbolData(id)
            val containerSig = symbolDeserializer.deserializeIdSignature(symbolData.signatureId)

            map[containerSig] = declarationIds.tableItemBytes(index)
        }
    }

    fun loadClassOrder(classSignature: IdSignature): List<IrSymbol>? {
        val bytes = containerSigToOrder[classSignature] ?: return null

        return IrLongArrayMemoryReader(bytes).array.map(::deserializeIrSymbol)
    }


    private fun deserializePublicSymbol(idSig: IdSignature, kind: BinarySymbolData.SymbolKind) : IrSymbol {
        return if (moduleDeserializer.contains(idSig)) moduleDeserializer.deserializeIrSymbol(idSig, kind) else null ?: run {
            val fileDeserializer = publicSignatureToIcFileDeserializer[idSig.topLevelSignature()] ?: error("file deserializer not found: $idSig")
            fileDeserializer.deserializeIrSymbol(idSig, kind)
        }
    }

    private fun enqueueLocalTopLevelDeclaration(idSig: IdSignature) {
        // We only care about declarations from IC cache. They all are in the map.
        val deser = publicSignatureToIcFileDeserializer[idSig] ?: return
        idSig.enqueue(deser)
    }

    fun deserializeDeclaration(idSig: IdSignature): IrDeclaration? {
        cachedDeclaration(idSig)?.let { return it }

        val idSigIndex = reversedSignatureIndex[idSig] ?: return null
//            error("Not found Idx for $idSig")
        val declarationStream = icFileReader.irDeclaration(idSigIndex).codedInputStream
        val declarationProto = org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration.parseFrom(declarationStream, ExtensionRegistryLite.newInstance())
        return declarationDeserializer.deserializeDeclaration(declarationProto)
    }

    // Return declaration iff it was already deserialized
    private fun cachedDeclaration(idSig: IdSignature): IrDeclaration? {
        val symbol = symbolDeserializer.deserializedSymbols[idSig] // Same map is used for both symbol deserializers

        if (symbol != null && symbol.isBound) return symbol.owner as? IrDeclaration

        return null
    }

    fun deserializeAnyDeclaration(idSig: IdSignature): IrDeclaration? {
        if (idSig is IdSignature.FileSignature) return null // TODO: is it needed

        cachedDeclaration(idSig)?.let { return it }

        // TODO fast path?
        val maybeTopLevel = if (!idSig.isLocal || idSig.hasTopLevel) idSig.topLevelSignature() else idSig

        if (maybeTopLevel in originalFileDeserializer.reversedSignatureIndex.keys) {
            originalFileDeserializer.deserializeFileImplicitDataIfFirstUse()
            originalFileDeserializer.deserializeDeclaration(maybeTopLevel)

            // At this point the declaration should've been deserialized
            return cachedDeclaration(idSig) // Will be null in case of fake overrides
        } else if (maybeTopLevel in reversedSignatureIndex) {
            return deserializeDeclaration(maybeTopLevel)
        }

        // TODO: error?
        return null
    }

    fun deserializeIrSymbol(code: Long): IrSymbol {
        return symbolDeserializer.deserializeIrSymbol(code)
    }

    fun deserializeIrSymbol(idSig: IdSignature, symbolKind: BinarySymbolData.SymbolKind): IrSymbol {
        idSig.enqueue(this)
        return symbolDeserializer.deserializeIrSymbol(idSig, symbolKind)
    }

    fun injectCarriers(declaration: IrDeclaration, signature: IdSignature) {
        carrierDeserializer.injectCarriers(declaration, signature)
    }

    companion object {
        private val additionalStatementOrigins = JsStatementOrigins::class.nestedClasses.toList()
        private val additionalStatementOriginIndex =
            additionalStatementOrigins.mapNotNull { it.objectInstance as? IrStatementOriginImpl }.associateBy { it.debugName }
    }
}

private class FileReaderFromSerializedIrFile(val irFile: SerializedIrFile) : IrLibraryFile() {
    private val declarationReader = DeclarationIrTableMemoryReader(irFile.declarations)
    private val typeReader = IrArrayMemoryReader(irFile.types)
    private val signatureReader = IrArrayMemoryReader(irFile.signatures)
    private val stringReader = IrArrayMemoryReader(irFile.strings)
    private val bodyReader = IrArrayMemoryReader(irFile.bodies)

    override fun irDeclaration(index: Int): ByteArray = declarationReader.tableItemBytes(DeclarationId(index))

    override fun type(index: Int): ByteArray = typeReader.tableItemBytes(index)

    override fun signature(index: Int): ByteArray = signatureReader.tableItemBytes(index)

    override fun string(index: Int): ByteArray = stringReader.tableItemBytes(index)

    override fun body(index: Int): ByteArray = bodyReader.tableItemBytes(index)
}