/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("FoldInitializerAndIfToElvis")

package org.jetbrains.kotlin.ir.backend.js.lower.inline

import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ir.Symbols
import org.jetbrains.kotlin.backend.common.isBuiltInIntercepted
import org.jetbrains.kotlin.backend.common.isBuiltInSuspendCoroutineUninterceptedOrReturn
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class FunctionInlining(val context: JsIrBackendContext) : IrElementTransformerVoidWithContext(), DeclarationTransformer {
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        declaration.accept(this, null)
        declaration.patchDeclarationParents(declaration.parent)
        return null
    }

    fun inline(irFile: IrFile): IrElement {
        return irFile.accept(this, data = null)
    }

    private val arrayConstructorTransformer = ArrayConstructorTransformer(context)

    private val IrFunction.needsInlining get() = this.isInline && !this.isExternal

    override fun visitCall(expression: IrCall): IrExpression {
        val callSite = arrayConstructorTransformer.transformCall(super.visitCall(expression) as IrCall)

        if (!callSite.symbol.owner.needsInlining)
            return callSite

        val languageVersionSettings = context.configuration.languageVersionSettings
        when {
            Symbols.isLateinitIsInitializedPropertyGetter(callSite.symbol) ->
                return callSite
            // Handle coroutine intrinsics
            // TODO These should actually be inlined.
            callSite.descriptor.isBuiltInIntercepted(languageVersionSettings) ->
                error("Continuation.intercepted is not available with release coroutines")
            callSite.symbol.descriptor.isBuiltInSuspendCoroutineUninterceptedOrReturn(languageVersionSettings) ->
                return irCall(callSite, context.coroutineSuspendOrReturn)
            callSite.symbol == context.intrinsics.jsCoroutineContext ->
                return irCall(callSite, context.coroutineGetContextJs)
        }

        val callee = getFunctionDeclaration(callSite.symbol)                   // Get declaration of the function to be inlined.
        callee.transformChildrenVoid(this)                            // Process recursive inline.

        val parent = allScopes.map { it.irElement }.filterIsInstance<IrDeclarationParent>().lastOrNull()
        val inliner = Inliner(callSite, callee, currentScope!!, parent, context, this)
        return inliner.inline()
    }

    private fun getFunctionDeclaration(symbol: IrFunctionSymbol): IrFunction {
        val descriptor = symbol.descriptor.original
        val languageVersionSettings = context.configuration.languageVersionSettings
        // TODO: Remove these hacks when coroutine intrinsics are fixed.
        return when {
//            descriptor.isBuiltInIntercepted(languageVersionSettings) ->
//                error("Continuation.intercepted is not available with release coroutines")
//
//            descriptor.isBuiltInSuspendCoroutineUninterceptedOrReturn(languageVersionSettings) ->
//                context.ir.symbols.konanSuspendCoroutineUninterceptedOrReturn.owner
//
//            descriptor == context.ir.symbols.coroutineContextGetter ->
//                context.ir.symbols.konanCoroutineContextGetter.owner

            else -> (symbol.owner as? IrSimpleFunction)?.resolveFakeOverride() ?: symbol.owner
        }
    }
}

