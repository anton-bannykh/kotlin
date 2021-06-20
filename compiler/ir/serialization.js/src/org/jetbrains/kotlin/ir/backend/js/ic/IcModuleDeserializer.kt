/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.backend.common.serialization.*
import org.jetbrains.kotlin.backend.common.serialization.encodings.BinarySymbolData
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.backend.js.JsMapping
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrLinker
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrModuleFragmentImpl
import org.jetbrains.kotlin.ir.declarations.persistent.PersistentIrFactory
import org.jetbrains.kotlin.ir.symbols.IrFileSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.library.IrLibrary
import org.jetbrains.kotlin.library.impl.IrLongArrayMemoryReader
import org.jetbrains.kotlin.protobuf.ExtensionRegistryLite

import org.jetbrains.kotlin.backend.common.serialization.proto.IrFile as ProtoFile

class IcModuleDeserializer(
    val irFactory: PersistentIrFactory,
    val mapping: JsMapping,
    val linker: JsIrLinker,
    val icData: SerializedIcData,
    moduleDescriptor: ModuleDescriptor,
    override val klib: IrLibrary,
    override val strategy: DeserializationStrategy,
    private val containsErrorCode: Boolean = false,
    private val useGlobalSignatures: Boolean = false,
) : IrModuleDeserializer(moduleDescriptor) {

    private val fileToDeserializerMap = mutableMapOf<IrFile, IrFileDeserializer>()

    internal val moduleReversedFileIndex = mutableMapOf<IdSignature, IcFileDeserializer>()

    override val moduleDependencies by lazy {
        moduleDescriptor.allDependencyModules.filter { it != moduleDescriptor }.map { linker.resolveModuleDeserializer(it, null) }
    }

    override fun fileDeserializers(): Collection<IrFileDeserializer> {
        return fileToDeserializerMap.values
    }

    override fun init(delegate: IrModuleDeserializer) {
        val fileCount = klib.fileCount()

        val files = ArrayList<IrFile>(fileCount)

        for (i in 0 until fileCount) {
            val fileStream = klib.file(i).codedInputStream
            val fileProto = ProtoFile.parseFrom(fileStream, ExtensionRegistryLite.newInstance())
            files.add(deserializeIrFile(fileProto, i, delegate, containsErrorCode))
        }

        moduleFragment.files.addAll(files)

        fileToDeserializerMap.values.forEach { it.symbolDeserializer.deserializeExpectActualMapping() }
    }

    private fun IrSymbolDeserializer.deserializeExpectActualMapping() {
        actuals.forEach {
            val expectSymbol = parseSymbolData(it.expectSymbol)
            val actualSymbol = parseSymbolData(it.actualSymbol)

            val expect = deserializeIdSignature(expectSymbol.signatureId)
            val actual = deserializeIdSignature(actualSymbol.signatureId)

            assert(linker.expectUniqIdToActualUniqId[expect] == null) {
                "Expect signature $expect is already actualized by ${linker.expectUniqIdToActualUniqId[expect]}, while we try to record $actual"
            }
            linker.expectUniqIdToActualUniqId[expect] = actual
            // Non-null only for topLevel declarations.
            findModuleDeserializerForTopLevelId(actual)?.let { md -> linker.topLevelActualUniqItToDeserializer[actual] = md }
        }
    }

    override fun referenceSimpleFunctionByLocalSignature(file: IrFile, idSignature: IdSignature) : IrSimpleFunctionSymbol =
        fileToDeserializerMap[file]?.symbolDeserializer?.referenceSimpleFunctionByLocalSignature(idSignature)
            ?: error("No deserializer for file $file in module ${moduleDescriptor.name}")

    override fun referencePropertyByLocalSignature(file: IrFile, idSignature: IdSignature): IrPropertySymbol =
        fileToDeserializerMap[file]?.symbolDeserializer?.referencePropertyByLocalSignature(idSignature)
            ?: error("No deserializer for file $file in module ${moduleDescriptor.name}")

    // TODO: fix to topLevel checker
    override fun contains(idSig: IdSignature): Boolean = idSig in moduleReversedFileIndex

    override fun deserializeIrSymbol(idSig: IdSignature, symbolKind: BinarySymbolData.SymbolKind): IrSymbol {
        assert(idSig.isPublic)

        val topLevelSignature = idSig.topLevelSignature()
        val icDeserializer = moduleReversedFileIndex[topLevelSignature]
            ?: error("No file for $topLevelSignature (@ $idSig) in module $moduleDescriptor")

        topLevelSignature.originalEnqueue(icDeserializer)
        linker.modulesWithReachableTopLevels.add(this)

        return icDeserializer.fileDeserializationState.originalFileDeserializer.symbolDeserializer.deserializeIrSymbol(idSig, symbolKind).also {
            linker.deserializedSymbols.add(it)
        }
    }

    override val moduleFragment: IrModuleFragment = IrModuleFragmentImpl(moduleDescriptor, linker.builtIns, emptyList())

    private val pathToIcFileData = icData.files.associateBy {
        it.file.path
    }

    private val publicSignatureToIcFileDeserializer = mutableMapOf<IdSignature, IcFileDeserializer>()

    private val pathToFileSymbol = mutableMapOf<String, IrFileSymbol>()

    private fun deserializeIrFile(fileProto: ProtoFile, fileIndex: Int, moduleDeserializer: IrModuleDeserializer, allowErrorNodes: Boolean): IrFile {

        val fileReader = IrLibraryFileFromKlib(moduleDeserializer.klib, fileIndex)
        val file = fileReader.createFile(moduleFragment, fileProto)

        pathToFileSymbol[file.path] = file.symbol

        val icFileData = pathToIcFileData[file.path]!!

        val fileDeserializationState = IcFileDeserializationState(
            linker,
            file,
            fileReader,
            fileProto,
            strategy.needBodies,
            allowErrorNodes,
            strategy.inlineBodies,
            moduleDeserializer,
            useGlobalSignatures,
            linker::handleNoModuleDeserializerFound,
            { fs -> originalEnqueue(fs) }
        )

        val icDeserializer = IcFileDeserializer(
            linker,
            fileDeserializationState.originalFileDeserializer,
            icFileData,
            pathToFileSymbol = { p -> pathToFileSymbol[p]!! },
            mapping.state,
            moduleDeserializer,
            publicSignatureToIcFileDeserializer,
            { fileDeserializer -> enqueue(fileDeserializer) },
            fileDeserializationState,
        )

        fileDeserializationState.icDeserializer = icDeserializer

        icDeserializers += icDeserializer

        fileDeserializationState.explicitlyExportedToCompiler.forEach { it.topLevelSignature().originalEnqueue(icDeserializer) }

        fileToDeserializerMap[file] = fileDeserializationState.originalFileDeserializer

        val topLevelDeclarations = fileDeserializationState.originalFileDeserializer.reversedSignatureIndex.keys
        topLevelDeclarations.forEach {
            moduleReversedFileIndex.putIfAbsent(it, icDeserializer) // TODO Why not simple put?
        }

        if (strategy.theWholeWorld) {
            fileDeserializationState.allDeclarationSignatures().forEach { it.originalEnqueue(icDeserializer) }
        }
        if (strategy.theWholeWorld || strategy.explicitlyExported) {
            linker.modulesWithReachableTopLevels.add(this)
        }



        return file
    }

    override fun addModuleReachableTopLevel(idSig: IdSignature) {
        val fileLocalDeserializationState = moduleReversedFileIndex[idSig] ?: error("No file found for key $idSig")
        idSig.originalEnqueue(fileLocalDeserializationState)
    }

    override fun deserializeReachableDeclarations() {
        while (!originalSignatureQueue.isEmpty()) {
            val signature = originalSignatureQueue.removeFirst()
            val pendingFileDeserializationState = originalFileQueue.removeFirst()

            pendingFileDeserializationState.originalFileDeserializer.deserializeFileImplicitDataIfFirstUse()

            // TODO Is this check needed?
            val existedSymbol = pendingFileDeserializationState.originalSymbolDeserializer.deserializedSymbols[signature]
            if (existedSymbol == null || !existedSymbol.isBound) {
                pendingFileDeserializationState.originalFileDeserializer.deserializeDeclaration(signature)
            }
        }
    }

    val originalFileQueue = ArrayDeque<IcFileDeserializationState>()
    val originalSignatureQueue = ArrayDeque<IdSignature>()

    fun IdSignature.originalEnqueue(icDeserializer: IcFileDeserializer) {
        val fileState = icDeserializer.fileDeserializationState
        if (this !in fileState.originalVisited) {
            originalFileQueue.addLast(fileState)
            originalSignatureQueue.addLast(this)
            fileState.originalVisited += this
        }
    }

    val fileQueue = ArrayDeque<IcFileDeserializer>()
    val signatureQueue = ArrayDeque<IdSignature>()

    val icDeserializers = mutableListOf<IcFileDeserializer>()
    val classToDeclarationSymbols = mutableMapOf<IrClass, List<IrSymbol>>()

    fun IdSignature.enqueue(icDeserializer: IcFileDeserializer) {
        if (this !in icDeserializer.visited) {
            fileQueue.addLast(icDeserializer)
            signatureQueue.addLast(this)
            icDeserializer.visited += this
        }
    }

    override fun postProcess() {
        // Add all signatures withing the module to a queue ( declarations and bodies )
        for (icDeserializer in icDeserializers) {
            val currentFilePath = icDeserializer.fileDeserializer.file.path

            icDeserializer.fileDeserializer.symbolDeserializer.deserializedSymbols.keys.forEach { idSig ->
                if (idSig.isPublic) {
                    idSig.enqueue(icDeserializer)
                } else {
                    if (idSig is IdSignature.GlobalFileLocalSignature && idSig.filePath == currentFilePath ||
                        idSig is IdSignature.GlobalScopeLocalDeclaration && idSig.filePath == currentFilePath
                    ) {
                        idSig.enqueue(icDeserializer)
                    }
                }
            }
        }

        while (signatureQueue.isNotEmpty()) {
            val icFileDeserializer = fileQueue.removeFirst()
            val signature = signatureQueue.removeFirst()

            if (signature is IdSignature.FileSignature) continue

            val symbol = icFileDeserializer.symbolDeserializer.deserializedSymbols[signature]
            // Deserialize the declaration
            val declaration = if (symbol?.isBound == true) symbol.owner as IrDeclaration else icFileDeserializer.deserializeDeclaration(signature) ?: continue

            icFileDeserializer.injectCarriers(declaration, signature)

            icFileDeserializer.mappingsDeserializer(signature, declaration)

            // Make sure all members are loaded
            if (declaration is IrClass) {
                icFileDeserializer.loadClassOrder(signature)?.let {
                    classToDeclarationSymbols[declaration] = it
                }
            }
        }

        irFactory.stageController.withStage(1000) {

            for (icDeserializer in icDeserializers) {
                val fd = icDeserializer.fileDeserializer
                val order = icDeserializer.icFileData.order

                fd.file.declarations.retainAll { it.isEffectivelyExternal() }

                IrLongArrayMemoryReader(order.topLevelSignatures).array.forEach {
                    val symbolData = icDeserializer.symbolDeserializer.parseSymbolData(it)
                    val idSig = icDeserializer.symbolDeserializer.deserializeIdSignature(symbolData.signatureId)

                    // Don't create unbound symbols for top-level declarations we don't need.
                    if (idSig in icDeserializer.visited) {
                        val declaration = icDeserializer.deserializeIrSymbol(idSig, symbolData.kind).owner as IrDeclaration
                        fd.file.declarations += declaration
                    }
                }
            }

            for ((klass, declarations) in classToDeclarationSymbols.entries) {
                irFactory.stageController.unrestrictDeclarationListsAccess {
                    klass.declarations.clear()
                    for (ds in declarations) {
                        klass.declarations += ds.owner as IrDeclaration
                    }
                }
            }
        }
    }
}

class IcFileDeserializationState(
    val linker: KotlinIrLinker,
    file: IrFile,
    fileReader: IrLibraryFile,
    fileProto: ProtoFile,
    deserializeBodies: Boolean,
    allowErrorNodes: Boolean,
    deserializeInlineFunctions: Boolean,
    moduleDeserializer: IrModuleDeserializer,
    useGlobalSignatures: Boolean,
    handleNoModuleDeserializerFound: (IdSignature, ModuleDescriptor, Collection<IrModuleDeserializer>) -> IrModuleDeserializer,
    val enqueue: IdSignature.(IcFileDeserializer) -> Unit,
) {

    lateinit var icDeserializer: IcFileDeserializer

    val originalSymbolDeserializer =
        IrSymbolDeserializer(
            linker.symbolTable,
            fileReader,
            file.path,
            fileProto.actualsList,
            { idSig, _ -> idSig.enqueue(icDeserializer) },
            linker::handleExpectActualMapping,
            useGlobalSignatures = useGlobalSignatures,
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
        fileReader,
        file,
        allowErrorNodes,
        deserializeInlineFunctions,
        deserializeBodies,
        originalSymbolDeserializer,
        linker.fakeOverrideBuilder.platformSpecificClassFilter,
        linker.fakeOverrideBuilder,
    )

    val originalFileDeserializer = IrFileDeserializer(file, fileReader, fileProto, originalSymbolDeserializer, originalDeclarationDeserializer)

    val originalVisited = HashSet<IdSignature>()

    // Explicitly exported declarations (e.g. top-level initializers) must be deserialized before all other declarations.
    // Thus we schedule their deserialization in deserializer's constructor.
    val explicitlyExportedToCompiler: Collection<IdSignature> = fileProto.explicitlyExportedToCompilerList.map {
        val symbolData = originalSymbolDeserializer.parseSymbolData(it)
        originalSymbolDeserializer.deserializeIdSignature(symbolData.signatureId)
    }

    fun allDeclarationSignatures(): Collection<IdSignature> = originalFileDeserializer.reversedSignatureIndex.keys
}