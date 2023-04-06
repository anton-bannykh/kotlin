/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.js.klib

import org.jetbrains.kotlin.incremental.js.IncrementalDataProvider
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.properties.Properties
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.KotlinLibraryVersioning
import org.jetbrains.kotlin.library.metadata.KlibMetadataProtoBuf
import org.jetbrains.kotlin.library.metadata.KlibMetadataProtoBuf.Header
import org.jetbrains.kotlin.library.metadata.parsePackageFragment
import java.io.ByteArrayOutputStream

val FAKE_IC_LIBRARY_PATH = "/fake_ic_library_path"

class IcKotlinLibrary(incrementalData: IncrementalDataProvider) : KotlinLibrary {

    private val parts: Map<String, Map<String, ByteArray>> = run {
        val result = mutableMapOf<String, MutableMap<String, ByteArray>>()

        incrementalData.compiledPackageParts.entries.forEach { (f, tv) ->
            val proto = parsePackageFragment(tv.metadata)
            val fqName = proto.getExtension(KlibMetadataProtoBuf.fqName)
            result.getOrPut(fqName, ::mutableMapOf).put(f.name, tv.metadata)
        }

        result
    }

    override val libraryName: String
        get() = "current-modules-clean-parts"

    override val libraryFile: File = File(FAKE_IC_LIBRARY_PATH)

    override val componentList: List<String>
        get() = TODO("Not yet implemented")
    override val versions: KotlinLibraryVersioning
        get() = TODO("Not yet implemented")
    override val isDefault: Boolean
        get() = TODO("Not yet implemented")
    override val manifestProperties: Properties = Properties()
    override val has_pre_1_4_manifest: Boolean
        get() = TODO("Not yet implemented")
    override val moduleHeaderData: ByteArray = run {

        val bos = ByteArrayOutputStream()

        val headerBuilder = Header.newBuilder()
        headerBuilder.setModuleName(libraryName)
        headerBuilder.addAllPackageFragmentName(parts.keys)
        headerBuilder.build().writeTo(bos)

        bos.toByteArray()
    }

    override fun packageMetadataParts(fqName: String): Set<String> {
        return parts[fqName]?.keys ?: emptySet()
    }

    override fun packageMetadata(fqName: String, partName: String): ByteArray {
        return parts[fqName]?.get(partName) ?: error("Metadata not found for package $fqName part $partName")
    }

    override val dataFlowGraph: ByteArray?
        get() = null

    override fun irDeclaration(index: Int, fileIndex: Int): ByteArray {
        TODO("Not yet implemented")
    }

    override fun type(index: Int, fileIndex: Int): ByteArray {
        TODO("Not yet implemented")
    }

    override fun signature(index: Int, fileIndex: Int): ByteArray {
        TODO("Not yet implemented")
    }

    override fun string(index: Int, fileIndex: Int): ByteArray {
        TODO("Not yet implemented")
    }

    override fun body(index: Int, fileIndex: Int): ByteArray {
        TODO("Not yet implemented")
    }

    override fun debugInfo(index: Int, fileIndex: Int): ByteArray? {
        TODO("Not yet implemented")
    }

    override fun file(index: Int): ByteArray {
        TODO("Not yet implemented")
    }

    override fun fileCount(): Int {
        TODO("Not yet implemented")
    }

    override fun types(fileIndex: Int): ByteArray {
        TODO("Not yet implemented")
    }

    override fun signatures(fileIndex: Int): ByteArray {
        TODO("Not yet implemented")
    }

    override fun strings(fileIndex: Int): ByteArray {
        TODO("Not yet implemented")
    }

    override fun declarations(fileIndex: Int): ByteArray {
        TODO("Not yet implemented")
    }

    override fun bodies(fileIndex: Int): ByteArray {
        TODO("Not yet implemented")
    }
}