/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.ir.backend.js.SerializedMappings
import org.jetbrains.kotlin.ir.serialization.SerializedCarriers
import org.jetbrains.kotlin.library.SerializedIrFile
import org.jetbrains.kotlin.library.impl.IrArrayMemoryReader
import org.jetbrains.kotlin.library.impl.IrArrayWriter
import org.jetbrains.kotlin.library.impl.IrMemoryArrayWriter
import java.io.File

class SerializedIcData(
    val files: Collection<SerializedIcDataForFile>,
)

class SerializedIcDataForFile(
    val file: SerializedIrFile,
    val carriers: SerializedCarriers,
    val mappings: SerializedMappings,
    val order: SerializedOrder,
)

class SerializedOrder(
    val topLevelSignatures: ByteArray,
    val containerSignatures: ByteArray,
    val declarationSignatures: ByteArray,
)

fun SerializedIcData.writeTo(dir: File) {
    if (!dir.mkdirs()) error("Failed to create output dir: ${dir.absolutePath}")



    files.sortedBy { it.file.path }.let { files ->
        // .file
        IrArrayWriter(files.map { it.file.fileData }).writeIntoFile(File(dir, "file.fileData").absolutePath)
        IrArrayWriter(files.map { it.file.declarations }).writeIntoFile(File(dir, "file.declarations").absolutePath)
        IrArrayWriter(files.map { it.file.types }).writeIntoFile(File(dir, "file.types").absolutePath)
        IrArrayWriter(files.map { it.file.signatures }).writeIntoFile(File(dir, "file.signatures").absolutePath)
        IrArrayWriter(files.map { it.file.strings }).writeIntoFile(File(dir, "file.strings").absolutePath)
        IrArrayWriter(files.map { it.file.bodies }).writeIntoFile(File(dir, "file.bodies").absolutePath)
        // .carriers
        IrArrayWriter(files.map { it.carriers.signatures }).writeIntoFile(File(dir, "carriers.signatures").absolutePath)
        IrArrayWriter(files.map { it.carriers.declarationCarriers }).writeIntoFile(File(dir, "carriers.declarationCarriers").absolutePath)
        IrArrayWriter(files.map { it.carriers.bodyCarriers }).writeIntoFile(File(dir, "carriers.bodyCarriers").absolutePath)
        IrArrayWriter(files.map { it.carriers.removedOn }).writeIntoFile(File(dir, "carriers.removedOn").absolutePath)
        // .mappings
        IrArrayWriter(files.map { it.mappings.keyBytes() }).writeIntoFile(File(dir, "mappings.keys").absolutePath)
        IrArrayWriter(files.map { it.mappings.valueBytes() }).writeIntoFile(File(dir, "mappings.values").absolutePath)
        // .order
        IrArrayWriter(files.map { it.order.topLevelSignatures }).writeIntoFile(File(dir, "order.topLevelSignatures").absolutePath)
        IrArrayWriter(files.map { it.order.containerSignatures }).writeIntoFile(File(dir, "order.containerSignatures").absolutePath)
        IrArrayWriter(files.map { it.order.declarationSignatures }).writeIntoFile(File(dir, "order.declarationSignatures").absolutePath)
    }
}

private fun SerializedMappings.keyBytes() = IrMemoryArrayWriter(mappings.map { it.keys }).writeIntoMemory()
private fun SerializedMappings.valueBytes() = IrMemoryArrayWriter(mappings.map { it.values }).writeIntoMemory()


fun File.readIcData(): SerializedIcData {
    if (!this.isDirectory) error("Directory doesn't exist: ${this.absolutePath}")

    // .file
    val fileFileDataReader = IrArrayMemoryReader(File(this, "file.fileData").readBytes())
    val fileDeclarationsReader = IrArrayMemoryReader(File(this, "file.declarations").readBytes())
    val fileTypesReader = IrArrayMemoryReader(File(this, "file.types").readBytes())
    val fileSignaturesReader = IrArrayMemoryReader(File(this, "file.signatures").readBytes())
    val fileStringsReader = IrArrayMemoryReader(File(this, "file.strings").readBytes())
    val fileBodiesReader = IrArrayMemoryReader(File(this, "file.bodies").readBytes())
    // .carriers
    val carriersSignaturesReader = IrArrayMemoryReader(File(this, "carriers.signatures").readBytes())
    val carriersDeclarationCarriersReader = IrArrayMemoryReader(File(this, "carriers.declarationCarriers").readBytes())
    val carriersBodyCarriersReader = IrArrayMemoryReader(File(this, "carriers.bodyCarriers").readBytes())
    val carriersRemovedOnReader = IrArrayMemoryReader(File(this, "carriers.removedOn").readBytes())
    // .mappings
    val mappings
}