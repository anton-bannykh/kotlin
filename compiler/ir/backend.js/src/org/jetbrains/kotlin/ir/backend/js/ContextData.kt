/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.backend.js.JsDeclarationFactory
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.ir.SourceManager
import org.jetbrains.kotlin.ir.SourceRangeInfo
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.backend.js.lower.CallableReferenceKey
import org.jetbrains.kotlin.ir.backend.js.lower.ConstructorPair
import org.jetbrains.kotlin.ir.backend.js.lower.DirectThrowableSuccessors
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.declarations.impl.MappingKey
import org.jetbrains.kotlin.ir.symbols.IrEnumEntrySymbol
import org.jetbrains.kotlin.ir.symbols.IrExternalPackageFragmentSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.name.FqName
import kotlin.reflect.KProperty

class MappingDelegate<K : IrDeclaration, V>(
    val key: MappingKey<K, V>
) {
    private val K.map: MutableMap<MappingKey<K, V>, V>
        get() = (this as IrDeclarationBase).userdata as MutableMap<MappingKey<K, V>, V>

    operator fun getValue(thisRef: K, desc: KProperty<*>): V? {
        return thisRef.map[key] as V?
    }

    operator fun setValue(thisRef: K, desc: KProperty<*>, value: V?) {
        if (value == null) {
            thisRef.map.remove(key)
        } else {
            thisRef.map[key] = value
        }
    }
}

fun <K : IrDeclaration, V> mapping(key: MappingKey<K, V>) = MappingDelegate(key)

class ContextData(
    val irModuleFragment: IrModuleFragment
) {

    // From JsIrBackendContext:

    private class DescriptorlessExternalPackageFragmentSymbol : IrExternalPackageFragmentSymbol {
        override val descriptor: PackageFragmentDescriptor
            get() = error("Operation is unsupported")

        private var _owner: IrExternalPackageFragment? = null
        override val owner get() = _owner!!

        override val isBound get() = _owner != null

        override fun bind(owner: IrExternalPackageFragment) {
            _owner = owner
        }
    }

    val externalPackageFragment: IrPackageFragment = IrExternalPackageFragmentImpl(
        DescriptorlessExternalPackageFragmentSymbol(),
        FqName.ROOT
    )

    val bodilessBuiltInsPackageFragment: IrPackageFragment = IrExternalPackageFragmentImpl(
        DescriptorlessExternalPackageFragmentSymbol(),
        FqName("kotlin")
    )

    val packageLevelJsModules = mutableMapOf<IrFile, IrFile>()
    val declarationLevelJsModules = mutableListOf<IrDeclarationWithName>()

    private val internalPackageFragmentDescriptor = EmptyPackageFragmentDescriptor(irModuleFragment.descriptor, FqName(""))
    val implicitDeclarationFile by lazy {
        IrFileImpl(
            object : SourceManager.FileEntry {
                override val name = "<implicitDeclarations>"
                override val maxOffset = UNDEFINED_OFFSET

                override fun getSourceRangeInfo(beginOffset: Int, endOffset: Int) =
                    SourceRangeInfo(
                        "",
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET
                    )

                override fun getLineNumber(offset: Int) = UNDEFINED_OFFSET
                override fun getColumnNumber(offset: Int) = UNDEFINED_OFFSET
            },
            internalPackageFragmentDescriptor
        ).also {
            irModuleFragment.files += it
        }
    }

    private var testContainerField: IrSimpleFunction? = null

    val hasTests get() = testContainerField != null

    val testContainer: IrSimpleFunction
        get() = testContainerField ?: JsIrBuilder.buildFunction("test fun", irModuleFragment.irBuiltins.unitType, implicitDeclarationFile).apply {
            body = JsIrBuilder.buildBlockBody(emptyList())
            testContainerField = this
            implicitDeclarationFile.declarations += this
        }

    val sharedVariablesManager = JsSharedVariablesManager(irModuleFragment.irBuiltins, implicitDeclarationFile)
    val declarationFactory = JsDeclarationFactory()

    val secondaryConstructorToFactoryCache = mutableMapOf<IrConstructor, ConstructorPair>()
    val inlineClassTransformedFunctionsCache = mutableMapOf<IrFunctionSymbol, IrSimpleFunctionSymbol>()
    val pendingThrowableSuperUsages = mutableMapOf<IrClass, DirectThrowableSuccessors>()

    data class BridgeInfo(
        val function: IrSimpleFunction,
        val bridge: IrSimpleFunction,
        val delegateTo: IrSimpleFunction
    )

    val bridgeToBridgeInfoMapping = mutableMapOf<IrSimpleFunction, BridgeInfo>()
}
