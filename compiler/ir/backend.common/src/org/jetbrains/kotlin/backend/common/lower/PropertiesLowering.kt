/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.*
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.name.Name

class PropertiesLowering(
    private val context: BackendContext,
    private val originOfSyntheticMethodForAnnotations: IrDeclarationOrigin? = null,
    private val skipExternalProperties: Boolean = false,
    private val generateAnnotationFields: Boolean = false,
    private val computeSyntheticMethodName: ((IrProperty) -> String)? = null
) : DeclarationTransformer {

    override fun lower(irFile: IrFile) {
        runPostfix(true).toFileLoweringPass().lower(irFile)
    }

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        when (declaration) {
            is IrSimpleFunction -> {
                declaration.correspondingPropertySymbol?.owner?.let { property ->
                    if (!skipExternalProperties || !property.isEffectivelyExternal()) {
                        return listOf(declaration)
                    }
                }
            }
            is IrField -> {
                declaration.correspondingPropertySymbol?.owner?.let { property ->
                    if (!skipExternalProperties || !property.isEffectivelyExternal()) {
                        // JvmFields in a companion object refer to companion's owners and should not be generated within companion.
                        val kind = (property.parent as? IrClass)?.kind ?: ClassKind.CLASS
                        if (generateAnnotationFields || (kind != ClassKind.ANNOTATION_CLASS && declaration.parent == property.parent)) {
                            return listOf(declaration)
                        }
                    }
                }
            }
            is IrProperty -> {
                if (!skipExternalProperties || !declaration.isEffectivelyExternal()) {
                    if (declaration.annotations.isNotEmpty() && originOfSyntheticMethodForAnnotations != null
                        && computeSyntheticMethodName != null
                    ) {
                        val methodName = computeSyntheticMethodName.invoke(declaration) // Workaround KT-4113
                        return listOf(createSyntheticMethodForAnnotations(declaration, originOfSyntheticMethodForAnnotations, methodName))
                    }

                    return listOf()
                }
            }
        }

        return null
    }

    private fun createSyntheticMethodForAnnotations(declaration: IrProperty, origin: IrDeclarationOrigin, name: String): IrFunctionImpl {
        val descriptor = WrappedSimpleFunctionDescriptor(declaration.descriptor.annotations)
        val symbol = IrSimpleFunctionSymbolImpl(descriptor)
        return IrFunctionImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, symbol, Name.identifier(name),
            declaration.visibility, Modality.OPEN, context.irBuiltIns.unitType,
            isInline = false, isExternal = false, isTailrec = false, isSuspend = false, isExpect = false, isFakeOverride = false,
            isOperator = false
        ).apply {
            descriptor.bind(this)

            val extensionReceiver = declaration.getter?.extensionReceiverParameter
            if (extensionReceiver != null) {
                // Use raw type of extension receiver to avoid generic signature, which would be useless for this method.
                extensionReceiverParameter = extensionReceiver.copyTo(this, type = extensionReceiver.type.classifierOrFail.typeWith())
            }

            body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET)

            annotations = declaration.annotations
            metadata = declaration.metadata
            parent = declaration.parent
        }
    }

    companion object {
        fun checkNoProperties(irFile: IrFile) {
            irFile.acceptVoid(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitProperty(declaration: IrProperty) {
                    error("No properties should remain at this stage")
                }
            })
        }
    }
}

class LocalDelegatedPropertiesLowering : IrElementTransformerVoid(), BodyLoweringPass {

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        irBody.accept(this, null)
    }

    override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty): IrStatement {
        declaration.transformChildrenVoid(this)

        val initializer = declaration.delegate.initializer!!
        declaration.delegate.initializer = IrBlockImpl(
            initializer.startOffset, initializer.endOffset, initializer.type, null,
            listOfNotNull(
                declaration.getter,
                declaration.setter,
                initializer
            )
        )

        return declaration.delegate
    }
}
