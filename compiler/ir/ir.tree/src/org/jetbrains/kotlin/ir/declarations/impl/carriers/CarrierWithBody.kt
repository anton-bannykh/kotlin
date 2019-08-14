/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.expressions.IrBody

interface CarrierWithBody<B: IrBody, in T : CarrierWithBody<B, T>> : CarrierBase<T> {
    var bodyField: B?
}