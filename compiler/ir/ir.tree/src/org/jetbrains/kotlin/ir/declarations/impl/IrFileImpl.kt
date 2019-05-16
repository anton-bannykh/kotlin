/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.ir.declarations.impl

import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.ir.IrElementBase
import org.jetbrains.kotlin.ir.SourceManager
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.IrFileSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrFileSymbolImpl
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.name.FqName

class IrFileImpl(
    override val fileEntry: SourceManager.FileEntry,
    override val symbol: IrFileSymbol,
    override val fqName: FqName,
    val stageController: StageController
) :
    IrElementBase(0, fileEntry.maxOffset),
    IrFile {

    constructor(
        fileEntry: SourceManager.FileEntry,
        symbol: IrFileSymbol,
        stageController: StageController
    ) : this(fileEntry, symbol, symbol.descriptor.fqName, stageController)

    constructor(
        fileEntry: SourceManager.FileEntry,
        packageFragmentDescriptor: PackageFragmentDescriptor,
        stageController: StageController
    ) : this(fileEntry, IrFileSymbolImpl(packageFragmentDescriptor), packageFragmentDescriptor.fqName, stageController)

    init {
        symbol.bind(this)
    }

    override val packageFragmentDescriptor: PackageFragmentDescriptor get() = symbol.descriptor

    var loweredUpTo = 0

    override val declarations: SimpleList<IrDeclaration> =
        DumbPersistentList(null, emptyList()) { stageController }

    override val annotations: SimpleList<IrCall> =
        DumbPersistentList(null, emptyList()) { stageController }

    override var metadata: MetadataSource.File? by NullablePersistentVar() { stageController }

    override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R =
        visitor.visitFile(this, data)

    override fun <D> acceptChildren(visitor: IrElementVisitor<Unit, D>, data: D) {
        declarations.forEach { it.accept(visitor, data) }
    }

    override fun <D> transformChildren(transformer: IrElementTransformer<D>, data: D) {
        declarations.transform { it.transform(transformer, data) as IrDeclaration }
    }
}
