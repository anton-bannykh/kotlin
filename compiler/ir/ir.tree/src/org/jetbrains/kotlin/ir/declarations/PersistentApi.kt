/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations

import org.jetbrains.kotlin.ir.declarations.impl.IrBodyBase
import org.jetbrains.kotlin.ir.declarations.impl.IrDeclarationBase
import org.jetbrains.kotlin.ir.symbols.IrSymbol

// TODO threadlocal
var stageController: StageController = NoopController()

interface StageController {
    val currentStage: Int

    fun lazyLower(declaration: IrDeclaration) {}

    fun lazyLower(file: IrFile) {}

    fun lowerBody(body: IrBodyBase<*>) {}

    fun <T> withStage(stage: Int, fn: () -> T): T = fn()

    fun tryLoad(symbol: IrSymbol) {}

    val bodiesEnabled: Boolean

    fun <T> withInitialIr(block: () -> T): T

    fun register(declaration: IrDeclarationBase<*>) {}
}

class NoopController(override var currentStage: Int = 0) : StageController {

    override val bodiesEnabled: Boolean
        get() = true

    override fun <T> withInitialIr(block: () -> T): T = block()
}
