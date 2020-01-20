/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrBodyBase
import org.jetbrains.kotlin.ir.declarations.impl.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrPersistingElementBase
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isLocal

open class MutableController(val context: JsIrBackendContext) : StageController {

    override var currentStage: Int = 0

    override fun lazyLower(declaration: IrDeclaration) {
        if (declaration is IrDeclarationBase<*> && currentStage - 1 > declaration.loweredUpTo) {
            val stageNonInclusive = currentStage

            while (declaration.loweredUpTo + 1 < stageNonInclusive) {
                val i = declaration.loweredUpTo + 1
                val parentBefore = withStage(i) { declaration.parent }

                withStage(i) {
                    val fileBefore = declaration.fileOrNull as? IrFileImpl
                    // TODO a better way to skip declarations in external package fragments
                    if (declaration.removedOn > i && fileBefore != null && fileBefore.symbol !in context.externalPackageFragmentSymbols) {
                        val lowering = loweringList[i - 1]
                        if (lowering is DeclarationLowering) {

                            val result = restrictTo(declaration) { lowering.declarationTransformer(context).transformFlat(declaration) }
                            if (result != null) {
                                result.forEach {
                                    it.parent = parentBefore
                                }

                                if (parentBefore is IrDeclarationContainer) {
                                    stageController.unrestrictDeclarationListsAccess {

                                        val correspondingProperty = when (declaration) {
                                            is IrSimpleFunction -> declaration.correspondingPropertySymbol?.owner
                                            is IrField -> declaration.correspondingPropertySymbol?.owner
                                            else -> null
                                        }

                                        var index = -1
                                        parentBefore.declarations.forEachIndexed { i, v ->
                                            if (v == declaration || index == -1 && v == correspondingProperty) {
                                                index = i
                                            }
                                        }

                                        if (index != -1 && declaration !is IrProperty) {
                                            if (parentBefore.declarations[index] == declaration) {
                                                parentBefore.declarations.removeAt(index)
                                            }
                                            parentBefore.declarations.addAll(index, result)
                                        } else {
                                            parentBefore.declarations.addAll(result)
                                        }

                                        if (declaration.parent == parentBefore && declaration !in result) {
                                            declaration.removedOn = currentStage
                                        }
                                    }
                                }
                            }
                        } else if (declaration.isLocal) {
                            // Handle local declarations in case they leak through types
                            var lastBodyContainer: IrDeclaration? = null
                            var parent = declaration.parent
                            while (parent is IrDeclaration) {
                                if (parent !is IrClass) {
                                    lastBodyContainer = parent
                                }
                                parent = parent.parent
                            }
                            lastBodyContainer?.apply {
                                when (this) {
                                    is IrFunction -> body // TODO What about local declarations inside default arguments?
                                    is IrField -> initializer
                                    else -> null
                                }?.let { withStage(i + 1) { lazyLower(it) } }
                            }
                        }
                    }
                    declaration.loweredUpTo = i
                }
            }
        }
    }


    override fun lazyLower(body: IrBody) {
        if (body is IrBodyBase<*> && body.loweredUpTo + 1 < currentStage) {
            for (i in (body.loweredUpTo + 1) until currentStage) {
                withStage(i) {
                    val declaration = body.container
                    val fileBefore = declaration.fileOrNull as? IrFileImpl
                    if (fileBefore != null) {
                        val lowering = loweringList[i - 1]

                        if (lowering is BodyLowering) {
                            stageController.bodyLowering {
                                lowering.bodyLowering(context).lower(body, declaration)
                            }
                        }
                    }
                    body.loweredUpTo = i
                }
            }
        }
    }

    override var bodiesEnabled: Boolean = true

    override fun <T> withStage(stage: Int, fn: () -> T): T {
        val prevStage = currentStage
        currentStage = stage
        try {
            return fn()
        } finally {
            currentStage = prevStage
        }
    }

    override fun <T> withInitialIr(block: () -> T): T = restrictionImpl(null) { withStage(0, block) }

    override fun <T> withInitialStateOf(declaration: IrDeclaration, block: () -> T): T = withStage((declaration as? IrPersistingElementBase<*>)?.createdOn ?: 0, block)

    private var restricted: Boolean = false

    private var restrictedToDeclaration: IrDeclaration? = null

    override fun <T> restrictTo(declaration: IrDeclaration, fn: () -> T): T = restrictionImpl(declaration, fn)

    private fun <T> restrictionImpl(declaration: IrDeclaration?, fn: () -> T): T {
        val prev = restrictedToDeclaration
        restrictedToDeclaration = declaration
        val wereBodiesEnabled = bodiesEnabled
        bodiesEnabled = false
        val wasRestricted = restricted
        restricted = true
        val wereDeclarationListsRestricted = declarationListsRestricted
        declarationListsRestricted = true
        try {
            return fn()
        } finally {
            restrictedToDeclaration = prev
            bodiesEnabled = wereBodiesEnabled
            restricted = wasRestricted
            declarationListsRestricted = wereDeclarationListsRestricted
        }
    }

    override fun <T> bodyLowering(fn: () -> T): T {
        val wereBodiesEnabled = bodiesEnabled
        bodiesEnabled = true
        val wasRestricted = restricted
        restricted = true
        val wereDeclarationListsRestricted = declarationListsRestricted
        declarationListsRestricted = true
        try {
            return fn()
        } finally {
            bodiesEnabled = wereBodiesEnabled
            restricted = wasRestricted
            declarationListsRestricted = wereDeclarationListsRestricted
        }
    }

    override fun canModify(element: IrElement): Boolean {
        return true
//        return !restricted || restrictedToDeclaration === element || element is IrPersistingElementBase<*> && element.createdOn == currentStage
    }

    private var declarationListsRestricted = false

    override fun <T> unrestrictDeclarationListsAccess(fn: () -> T): T {
        val wereDeclarationListsRestricted = declarationListsRestricted
        declarationListsRestricted = false
        try {
            return fn()
        } finally {
            declarationListsRestricted = wereDeclarationListsRestricted
        }
    }

    override fun canAccessDeclarationsOf(irClass: IrClass): Boolean {
        return !declarationListsRestricted || irClass.visibility == Visibilities.LOCAL
    }
}