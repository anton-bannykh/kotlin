/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.lower.FinallyBlocksLowering
import org.jetbrains.kotlin.backend.common.lower.LocalDeclarationPopupLowering
import org.jetbrains.kotlin.backend.common.lower.TailrecLowering
import org.jetbrains.kotlin.backend.common.phaser.PhasePrerequisites
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin

@PhasePrerequisites(InitializersLowering::class, LocalDeclarationPopupLowering::class, TailrecLowering::class)
internal class NativeFinallyBlocksLowering(context: CommonBackendContext) : FinallyBlocksLowering(context, context.irBuiltIns.throwableType) {
    // Compiler temporaries get no debug info, so the debugger doesn't show this variable.
    override val syntheticCatchParameterOrigin: IrDeclarationOrigin
        get() = IrDeclarationOrigin.IR_TEMPORARY_VARIABLE
}
