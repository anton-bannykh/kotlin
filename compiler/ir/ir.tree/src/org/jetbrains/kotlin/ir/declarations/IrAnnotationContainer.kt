/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations

import org.jetbrains.kotlin.ir.expressions.IrExpressionBody

interface IrAnnotationContainer {
    // TODO IrAnnotationBody
    val annotations: SimpleList<IrExpressionBody>
}